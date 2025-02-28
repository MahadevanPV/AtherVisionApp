package com.example.athervisionapp

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
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
import androidx.compose.material.icons.filled.Add  // Using a simple Add icon instead
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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "CameraComponent"

/**
 * A composable function that provides a camera preview with basic controls
 */
@Composable
fun CameraComponent(
    onImageCaptured: (imageCapture: ImageCapture) -> Unit,
    onError: (Exception) -> Unit
) {
    // 1. Setup required state and instances
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraSelector = remember { CameraSelector.DEFAULT_BACK_CAMERA }
    val previewView = remember { PreviewView(context) }

    // 2. LaunchedEffect to bind camera use cases
    LaunchedEffect(key1 = true) {
        val cameraProvider = context.getCameraProvider()
        try {
            // Unbind any existing use cases before binding new ones
            cameraProvider.unbindAll()

            // Bind the camera use cases to the lifecycle owner
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

            // Connect the preview to the PreviewView
            preview.setSurfaceProvider(previewView.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            onError(exc)
        }
    }

    // 3. Create the UI
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
                imageVector = Icons.Default.Add,  // Using Add icon as a simple capture button
                contentDescription = "Take picture",
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

/**
 * Extension function to get the camera provider asynchronously
 */
suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).also { cameraProvider ->
        cameraProvider.addListener(
            {
                continuation.resume(cameraProvider.get())
            },
            ContextCompat.getMainExecutor(this)
        )
    }
}