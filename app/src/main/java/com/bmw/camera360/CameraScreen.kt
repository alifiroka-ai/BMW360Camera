package com.bmw.camera360

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CameraScreen(context: Context) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    
    val spots = listOf("Carport", "Taman Depan", "Ruang Tamu", "Kamar 1", "Kamar 2", "Kamar Mandi", "Sisa Lahan Belakang")
    var selectedSpot by remember { mutableStateOf(spots[2]) }

    var pitch by remember { mutableStateOf(0f) }
    var yaw by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    val matrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(matrix, orientation)
                    yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()

                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("BMW 360 Camera", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Titik: $selectedSpot", color = Color.Yellow)
            Text("Arah: Pitch ${pitch.toInt()}° | Yaw ${yaw.toInt()}°", color = Color.White)
            Text("Foto terkumpul: ${capturedFiles.size}", color = Color.Green)
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    val file = File(context.cacheDir, "frame_${System.currentTimeMillis()}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                    imageCapture?.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                capturedFiles = capturedFiles + file
                            }
                            override fun onError(exc: ImageCaptureException) {
                                Toast.makeText(context, "Gagal memotret", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                },
                enabled = !isProcessing
            ) {
                Text("Potret Frame")
            }

            Button(
                onClick = {
                    if (capturedFiles.size >= 3) {
                        isProcessing = true
                        scope.launch(Dispatchers.IO) {
                            val stitcher = PanoramaStitcher(context)
                            val cleanSpot = selectedSpot.lowercase().replace(" ", "-")
                            val success = stitcher.stitchImages(capturedFiles, cleanSpot)
                            isProcessing = false
                            launch(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(context, "Panorama tersimpan di Pictures/BMW360", Toast.LENGTH_LONG).show()
                                    capturedFiles = emptyList()
                                } else {
                                    Toast.makeText(context, "Gagal menyambung foto. Pastikan overlap cukup.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else {
                        Toast.makeText(context, "Minimal butuh 3 foto!", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = capturedFiles.size >= 3 && !isProcessing
            ) {
                Text(if (isProcessing) "Menjahit..." else "Proses Panorama")
            }
        }

        if (isProcessing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}
