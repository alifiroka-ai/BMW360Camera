package com.bmw.camera360

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

private data class Aim(val yaw: Float, val elevation: Float)

private fun normalizeYaw(value: Float): Float = (value % 360f + 360f) % 360f
private fun signedYawDifference(target: Float, current: Float): Float =
    (target - current + 540f) % 360f - 180f

@Composable
private fun TargetDot(number: String, completed: Boolean, active: Boolean) {
    val color = when {
        completed -> Color(0xFF26B66F)
        active -> Color(0xFFFFC32B)
        else -> Color(0xFF59636D)
    }
    Box(
        modifier = Modifier.size(21.dp).background(color, CircleShape)
            .then(if (active) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(if (completed) "✓" else number, color = if (active) Color.Black else Color.White,
            fontSize = 10.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun CaptureMap(count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf("Sejajar", "Plafon", "Lantai").forEachIndexed { row, label ->
            Text(label, color = Color.White, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(12) { column ->
                    val index = row * 12 + column
                    TargetDot("${column + 1}", index < count, index == count)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Atas", color = Color.White, fontSize = 11.sp)
            TargetDot("↑", 36 < count, count == 36)
            Text("Bawah", color = Color.White, fontSize = 11.sp)
            TargetDot("↓", 37 < count, count == 37)
        }
        Text("Hijau: sudah  •  Kuning: berikutnya  •  Abu: belum",
            color = Color.White, fontSize = 10.sp)
    }
}

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
    var savedSpot by remember { mutableStateOf<String?>(null) }
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
    val yawDelta = next?.let { if (abs(it.elevation) == 90f) 0f else signedYawDifference(it.yaw, yaw) } ?: 0f
    val elevationDelta = next?.let { it.elevation - elevation } ?: 0f
    val aimed = next != null && abs(elevationDelta) <= 15f && abs(yawDelta) <= 12f

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
            }, modifier = Modifier.fillMaxSize()
        )

        if (next != null) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // White ring marks the center; moving yellow target guides the next frame.
                Box(modifier = Modifier.align(Alignment.Center).size(50.dp)
                    .border(2.dp, Color.White, CircleShape))
                val shiftX = (yawDelta / 35f).coerceIn(-1f, 1f) * maxWidth.value * 0.32f
                val shiftY = (-elevationDelta / 40f).coerceIn(-1f, 1f) * maxHeight.value * 0.24f
                Box(modifier = Modifier.align(Alignment.Center)
                    .offset(x = shiftX.dp, y = shiftY.dp).size(32.dp)
                    .background(if (aimed) Color(0xFF26B66F) else Color(0xFFFFC32B), CircleShape)
                    .border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Text("+", color = Color.Black, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)
                .background(Color.Black.copy(alpha = 0.80f), RoundedCornerShape(12.dp)).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("BMW 360 Camera", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Titik: ${spots[spotIndex]}", color = Color(0xFFFFC32B))
            if (rotationSensor == null) {
                Text("Sensor rotasi tidak tersedia di HP ini", color = Color.Red)
            } else if (baseYaw == null) {
                Text(if (savedSpot != null) "$savedSpot tersimpan di Galeri • Album BMW360"
                    else "Berdiri di tengah ruangan, lalu ketuk Mulai",
                    color = if (savedSpot != null) Color(0xFF26B66F) else Color.White,
                    textAlign = TextAlign.Center)
            } else {
                CaptureMap(capturedFrames.size)
                Text(if (next == null) "38/38 selesai • simpan hasilnya"
                    else "${capturedFrames.size}/38 • ${if (aimed) "Tepat sasaran, ketuk Potret" else "Arahkan kamera ke titik kuning"}",
                    color = if (aimed || next == null) Color(0xFF26B66F) else Color(0xFFFFC32B),
                    textAlign = TextAlign.Center)
            }
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
            .background(Color.Black.copy(alpha = 0.77f), RoundedCornerShape(12.dp)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (processing) {
                CircularProgressIndicator()
                Text("Menyusun panorama dan menyimpan ke Galeri...", color = Color.White)
            } else if (baseYaw == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { spotIndex = (spotIndex + 1) % spots.size }) { Text("Ganti titik") }
                    Button(onClick = { baseYaw = yaw; savedSpot = null },
                        enabled = rotationSensor != null && imageCapture != null) { Text("Mulai") }
                }
            } else {
                if (next != null) {
                    val direction = when {
                        abs(elevationDelta) > 15f -> if (elevationDelta > 0) "Arahkan ke atas" else "Arahkan ke bawah"
                        yawDelta > 12f -> "Putar ke kanan"
                        yawDelta < -12f -> "Putar ke kiri"
                        else -> "Titik sesuai"
                    }
                    Text("${capturedFrames.size + 1}/38 • $direction", color = Color.White)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val capture = imageCapture ?: return@Button
                        val pose = rotation.copyOf()
                        val file = File(context.cacheDir, "bmw_frame_${System.nanoTime()}.jpg")
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
                    }, enabled = (aimed || next == null) && !takingPicture) {
                        Text(if (next == null) "Foto tambahan" else "Potret")
                    }
                    if (next == null) {
                        Button(onClick = {
                            processing = true
                            val framesToSave = capturedFrames
                            val spotName = spots[spotIndex]
                            scope.launch {
                                val success = withContext(Dispatchers.IO) {
                                    try { PanoramaStitcher(context).stitchImages(framesToSave, spotName) }
                                    catch (e: Exception) {
                                        Log.e("BMW360", "Gagal menyimpan panorama", e)
                                        false
                                    }
                                }
                                processing = false
                                if (success) {
                                    framesToSave.forEach { it.file.delete() }
                                    capturedFrames = emptyList()
                                    baseYaw = null
                                    savedSpot = spotName
                                } else {
                                    Toast.makeText(context,
                                        "Belum berhasil. Ambil foto tambahan pada bagian yang belum tertutup, lalu simpan lagi.",
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }, enabled = !takingPicture) { Text("Simpan ke Galeri") }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Putar tubuh di tempat dan jaga posisi lensa tetap sama.", color = Color.White,
                fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}
