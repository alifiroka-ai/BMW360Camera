package com.bmw.camera360

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

private data class Aim(val yaw: Float, val elevation: Float)

private fun normalizeYaw(value: Float): Float = (value % 360f + 360f) % 360f
private fun yawDifference(a: Float, b: Float): Float = abs((a - b + 540f) % 360f - 180f)

@Composable
fun CameraScreen(context: Context) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val rotationSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedFrames by remember { mutableStateOf<List<CapturedFrame>>(emptyList()) }
    var processing by remember { mutableStateOf(false) }
    var takingPicture by remember { mutableStateOf(false) }
    var rotation by remember { mutableStateOf(FloatArray(9)) }
    var yaw by remember { mutableStateOf(0f) }
    var elevation by remember { mutableStateOf(0f) }
    var baseYaw by remember { mutableStateOf<Float?>(null) }
    var spotIndex by remember { mutableStateOf(2) }
    val spots = remember { listOf("Carport", "Taman Depan", "Ruang Tamu", "Kamar 1", "Kamar 2", "Kamar Mandi", "Sisa Lahan Belakang") }
    val scope = rememberCoroutineScope()

    DisposableEffect(rotationSensor) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val matrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                // Back-camera optical axis is device -Z; matrix is device-to-world (east, north, up).
                val east = -matrix[2]
                val north = -matrix[5]
                val up = -matrix[8]
                rotation = matrix
                yaw = normalizeYaw(Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat())
                elevation = Math.toDegrees(asin(up.coerceIn(-1f, 1f).toDouble())).toFloat()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (rotationSensor != null) sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    val targets = baseYaw?.let { initial ->
        listOf(0f, 60f, -60f).flatMap { pitch ->
            (0 until 12).map { step -> Aim(normalizeYaw(initial + step * 30f), pitch) }
        } + Aim(initial, 90f) + Aim(initial, -90f)
    } ?: emptyList()
    val next = targets.getOrNull(capturedFrames.size)
    val aimed = next != null && abs(elevation - next.elevation) <= 15f &&
        (abs(next.elevation) == 90f || yawDifference(yaw, next.yaw) <= 12f)

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val capture = ImageCapture.Builder()
                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .build()
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        imageCapture = capture
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Kamera gagal dibuka: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("BMW 360 Camera", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Titik: ${spots[spotIndex]}", color = Color.Yellow)
            Text("Arah ${yaw.toInt()}°, tinggi ${elevation.toInt()}°", color = Color.White)
            if (rotationSensor == null) {
                Text("Sensor rotasi tidak tersedia di HP ini", color = Color.Red)
            } else if (baseYaw == null) {
                Text("Berdiri di tengah ruangan, lalu ketuk Mulai", color = Color.White)
            } else if (next != null) {
                Text("Foto ${capturedFrames.size + 1}/38: arah ${next.yaw.toInt()}°, tinggi ${next.elevation.toInt()}°", color = Color.White)
                Text(if (aimed) "Posisi sesuai — potret sekarang" else "Putar HP hingga posisi sesuai", color = if (aimed) Color.Green else Color.Yellow)
            } else {
                Text("38 arah terpotret. Siap memproses.", color = Color.Green)
            }
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (processing) CircularProgressIndicator()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (baseYaw == null) {
                    Button(onClick = { spotIndex = (spotIndex + 1) % spots.size }, enabled = !processing) { Text("Ganti titik") }
                    Button(onClick = { baseYaw = yaw }, enabled = rotationSensor != null && imageCapture != null) { Text("Mulai") }
                } else {
                    Button(onClick = {
                        val capture = imageCapture ?: return@Button
                        val pose = rotation.copyOf()
                        val file = File(context.cacheDir, "bmw_frame_${System.currentTimeMillis()}.jpg")
                        takingPicture = true
                        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    capturedFrames = capturedFrames + CapturedFrame(file, pose)
                                    takingPicture = false
                                }
                                override fun onError(exc: ImageCaptureException) {
                                    takingPicture = false
                                    Toast.makeText(context, "Gagal memotret: ${exc.message}", Toast.LENGTH_LONG).show()
                                }
                            })
                    }, enabled = aimed && !takingPicture && !processing) { Text("Potret") }
                    Button(onClick = {
                        processing = true
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                try { PanoramaStitcher(context).stitchImages(capturedFrames, spots[spotIndex]) }
                                catch (_: Exception) { false }
                            }
                            processing = false
                            Toast.makeText(context,
                                if (success) "JPG tersimpan di Pictures/BMW360" else "Panorama belum lengkap atau pemrosesan gagal. Ulangi di area kosong.",
                                Toast.LENGTH_LONG).show()
                            if (success) { capturedFrames = emptyList(); baseYaw = null }
                        }
                    }, enabled = next == null && !processing) { Text("Simpan JPG") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Putar tubuh di tempat. Jaga lensa di satu titik.", color = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(6.dp))
        }
    }
}
