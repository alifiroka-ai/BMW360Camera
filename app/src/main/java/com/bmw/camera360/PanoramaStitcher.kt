package com.bmw.camera360

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.stitching.Stitcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PanoramaStitcher(private val context: Context) {

    fun stitchImages(imageFiles: List<File>, spotName: String): Boolean {
        val mats = mutableListOf<Mat>()

        for (file in imageFiles) {
            val mat = Imgcodecs.imread(file.absolutePath)
            if (!mat.empty()) {
                val resizedMat = Mat()
                val targetWidth = 1024.0
                val scale = targetWidth / mat.width()
                Imgproc.resize(mat, resizedMat, Size(targetWidth, mat.height() * scale))
                mats.add(resizedMat)
            }
            mat.release()
        }

        if (mats.size < 2) return false

        val resultMat = Mat()
        val stitcher = Stitcher.create(Stitcher.PANORAMA)
        val status = stitcher.stitch(mats, resultMat)

        for (m in mats) m.release()

        if (status != Stitcher.OK) {
            return false
        }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        val fileName = "$spotName-$timestamp.jpg"
        val tempFile = File(context.cacheDir, fileName)

        Imgcodecs.imwrite(tempFile.absolutePath, resultMat)
        resultMat.release()

        return saveToMediaStore(tempFile, fileName)
    }

    private fun saveToMediaStore(tempFile: File, fileName: String): Boolean {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BMW360")
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            tempFile.delete()
            return true
        }
        return false
    }
}
