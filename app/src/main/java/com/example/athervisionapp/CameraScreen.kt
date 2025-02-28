package com.example.athervisionapp

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import android.util.Size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import android.util.Log


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * A full screen camera experience with object detection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onNavigateBack: () -> Unit,
    onImageCaptured: (Uri?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Initialize the object detector
    val objectDetector = remember { ObjectDetector(context) }

    // This will track when an image is being processed
    var isCapturing by remember { mutableStateOf(false) }

    // For storing detection results
    val detectionResults = remember { mutableStateListOf<DetectionResult>() }

    // Toggle for detection
    var detectionEnabled by remember { mutableStateOf(true) }

    // Track the preview size
    var previewWidth by remember { mutableStateOf(0) }
    var previewHeight by remember { mutableStateOf(0) }

    // Create an executor for background tasks
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // Create an image analyzer for object detection
    val imageAnalyzer = remember {
        ImageAnalysis.Builder()
            .setTargetResolution(Size(ModelInfo.MODEL_WIDTH, ModelInfo.MODEL_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    // Cleanup when leaving the screen
    DisposableEffect(key1 = true) {
        onDispose {
            cameraExecutor.shutdown()
            objectDetector.close()
        }
    }

    BackHandler {
        onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Object Detection Camera") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Toggle switch for detection
                    Switch(
                        checked = detectionEnabled,
                        onCheckedChange = { detectionEnabled = it }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Camera Component with analyzer
            CameraComponentWithAnalyzer(
                onImageCaptured = { imageCapture ->
                    if (!isCapturing) {
                        isCapturing = true
                        val imageCaptureUtil = ImageCaptureUtil(context)
                        imageCaptureUtil.captureImage(
                            imageCapture = imageCapture,
                            saveToGallery = true,
                            onImageCaptured = { uri ->
                                isCapturing = false
                                onImageCaptured(uri)
                            },
                            onError = { exception ->
                                isCapturing = false
                                Toast.makeText(
                                    context,
                                    "Error capturing image: ${exception.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                },
                onError = { exception ->
                    Toast.makeText(
                        context,
                        "Camera error: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                // In your CameraScreen.kt
                onFrameAnalyzed = { bitmap, width, height ->
                    previewWidth = width
                    previewHeight = height

                    if (detectionEnabled) {
                        // Run object detection
                        val results = objectDetector.detect(bitmap)

                        // Log results for debugging
                        Log.d("CameraScreen", "Detection results: ${results.size} objects")

                        // Update UI with results
                        detectionResults.clear()
                        detectionResults.addAll(results)

                    } else {
                        detectionResults.clear()
                    }
                }
            )

            // Show detection results overlay
            if (detectionResults.isNotEmpty()) {
                DetectionOverlay(
                    detections = detectionResults,
                    imageWidth = ModelInfo.MODEL_WIDTH,
                    imageHeight = ModelInfo.MODEL_HEIGHT,
                    displayWidth = previewWidth,
                    displayHeight = previewHeight
                )
            }

            // Show detection count
            if (detectionEnabled) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                ) {
                    Text(
                        text = "Detected: ${detectionResults.size}",
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Convert ImageProxy to Bitmap
 */
private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
    val buffer = imageProxy.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val yuvImage = android.graphics.YuvImage(
        bytes,
        android.graphics.ImageFormat.NV21,
        imageProxy.width,
        imageProxy.height,
        null
    )

    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(
        android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height),
        100,
        out
    )

    val imageBytes = out.toByteArray()
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

    // Rotate the bitmap if needed
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
    if (rotationDegrees != 0) {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(
            bitmap,
            0, 0,
            bitmap.width, bitmap.height,
            matrix,
            true
        )
    }

    return bitmap
}