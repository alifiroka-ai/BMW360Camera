package com.bmw.camera360

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Device-to-world 3x3 rotation sampled immediately before shutter press. */
data class CapturedFrame(val file: File, val rotation: FloatArray)

/** A first-pass, orientation-guided full-sphere renderer; alignment depends on sensor calibration. */
class PanoramaStitcher(private val context: Context) {
    private val width = 2048
    private val height = 1024

    fun stitchImages(frames: List<CapturedFrame>, spotName: String): Boolean {
        if (frames.size < 38 || frames.any { it.rotation.size != 9 }) return false
        val size = width * height
        val red = FloatArray(size)
        val green = FloatArray(size)
        val blue = FloatArray(size)
        val weight = FloatArray(size)
        val yawSin = FloatArray(width) { x -> sin(2.0 * PI * (x + 0.5) / width).toFloat() }
        val yawCos = FloatArray(width) { x -> cos(2.0 * PI * (x + 0.5) / width).toFloat() }
        val elevationSin = FloatArray(height) { y -> cos(PI * (y + 0.5) / height).toFloat() }
        val elevationCos = FloatArray(height) { y -> sin(PI * (y + 0.5) / height).toFloat() }
        val (horizontalFov, verticalFov) = cameraFov()

        for (frame in frames) {
            val bitmap = loadUprightBitmap(frame.file) ?: return false
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val cx = (bitmap.width - 1) / 2.0f
                val cy = (bitmap.height - 1) / 2.0f
                val fx = (bitmap.width / (2.0 * tan(horizontalFov / 2.0))).toFloat()
                val fy = (bitmap.height / (2.0 * tan(verticalFov / 2.0))).toFloat()
                val r = frame.rotation
                for (y in 0 until height) {
                    val up = elevationSin[y]
                    val flat = elevationCos[y]
                    val row = y * width
                    for (x in 0 until width) {
                        val east = yawSin[x] * flat
                        val north = yawCos[x] * flat
                        // Transpose the Android device-to-ENU rotation matrix.
                        val dx = r[0] * east + r[3] * north + r[6] * up
                        val dy = r[1] * east + r[4] * north + r[7] * up
                        val dz = r[2] * east + r[5] * north + r[8] * up
                        if (dz >= -0.01f) continue
                        val u = cx + fx * dx / -dz
                        val v = cy - fy * dy / -dz
                        if (u < 1f || v < 1f || u >= bitmap.width - 1f || v >= bitmap.height - 1f) continue
                        val edge = min(min(u, bitmap.width - 1 - u) / bitmap.width,
                            min(v, bitmap.height - 1 - v) / bitmap.height)
                        val blend = min(1f, max(0f, (edge - 0.01f) * 12f))
                        if (blend <= 0f) continue
                        val color = pixels[v.toInt() * bitmap.width + u.toInt()]
                        val index = row + x
                        red[index] += ((color ushr 16) and 255) * blend
                        green[index] += ((color ushr 8) and 255) * blend
                        blue[index] += (color and 255) * blend
                        weight[index] += blend
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }

        // Never label missing image data as a full panorama.
        if (weight.count { it > 0f } < size * 0.995) return false
        val out = IntArray(size) { index ->
            val w = weight[index]
            if (w == 0f) 0xff000000.toInt() else {
                val rr = (red[index] / w).toInt().coerceIn(0, 255)
                val gg = (green[index] / w).toInt().coerceIn(0, 255)
                val bb = (blue[index] / w).toInt().coerceIn(0, 255)
                0xff000000.toInt() or (rr shl 16) or (gg shl 8) or bb
            }
        }
        val bitmap = Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
        return try { saveToMediaStore(bitmap, spotName) } finally { bitmap.recycle() }
    }

    private fun loadUprightBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = if (max(bounds.outWidth, bounds.outHeight) > 2500) 2 else 1
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val rotation = ExifInterface(file).rotationDegrees
        if (rotation == 0) return decoded
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (upright !== decoded) decoded.recycle()
        return upright
    }

    /** Physical lens estimate for a portrait 4:3 capture. Avoid pretending all phones have one FOV. */
    private fun cameraFov(): Pair<Double, Double> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: break
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: break
            if (focal <= 0) break
            return (2 * atan(size.height / (2.0 * focal))) to
                (2 * atan(size.width / (2.0 * focal)))
        }
        // A fallback estimate; users should inspect seams on their actual phone.
        return Math.toRadians(55.0) to Math.toRadians(70.0)
    }

    private fun saveToMediaStore(bitmap: Bitmap, spotName: String): Boolean {
        val name = spotName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name-$date.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BMW360")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val saved = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } == true
            if (!saved) { resolver.delete(uri, null, null); false }
            else {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                true
            }
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
