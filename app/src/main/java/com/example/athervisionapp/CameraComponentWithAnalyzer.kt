package com.example.athervisionapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "CameraComponentAnalyzer"

/**
 * A composable function that provides a camera preview with frame analysis and object detection
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraComponentWithAnalyzer(
    onImageCaptured: (imageCapture: ImageCapture) -> Unit,
    onError: (Exception) -> Unit,
    onFrameAnalyzed: (bitmap: Bitmap, width: Int, height: Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraSelector = remember { CameraSelector.DEFAULT_BACK_CAMERA }
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    // Set up the image analyzer
    LaunchedEffect(key1 = imageAnalysis) {
        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                // Log that we're processing a frame
                Log.d(TAG, "Processing camera frame")

                // Convert ImageProxy to Bitmap
                val bitmap = imageProxyToBitmap(imageProxy)

                // Run object detection
                val detector = ObjectDetector(context)
                val results = detector.detect(bitmap)

                // Log results
                Log.d(TAG, "Detection completed: ${results.size} objects found")
                if (results.isNotEmpty()) {
                    results.forEach {
                        Log.d(TAG, "Detected: ${it.label} (${it.confidence})")
                    }
                }

                // Pass results to callback
                onFrameAnalyzed(bitmap, imageProxy.width, imageProxy.height)
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing image: ${e.message}", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    // Bind camera use cases
    LaunchedEffect(key1 = true) {
        val cameraProvider = getCameraProvider(context)
        try {
            // Unbind any existing use cases before binding new ones
            cameraProvider.unbindAll()

            // Bind the camera use cases to the lifecycle owner
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
            )

            // Connect the preview to the PreviewView
            preview.setSurfaceProvider(previewView.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            onError(exc)
        }
    }

    // Create the UI
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Capture Button
        IconButton(
            onClick = { onImageCaptured(imageCapture) },
            modifier = Modifier
                .padding(bottom = 20.dp)
                .size(80.dp)
                .align(Alignment.BottomCenter)
                .border(2.dp, Color.White, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Take picture",
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
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

    val yuvImage = YuvImage(
        bytes,
        ImageFormat.NV21,
        imageProxy.width,
        imageProxy.height,
        null
    )

    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(
        Rect(0, 0, imageProxy.width, imageProxy.height),
        100,
        out
    )

    val imageBytes = out.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

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

/**
 * Get camera provider (with a unique name to avoid conflicts)
 */
private suspend fun getCameraProvider(context: Context): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(context).also { cameraProvider ->
        cameraProvider.addListener(
            {
                continuation.resume(cameraProvider.get())
            },
            ContextCompat.getMainExecutor(context)
        )
    }
}