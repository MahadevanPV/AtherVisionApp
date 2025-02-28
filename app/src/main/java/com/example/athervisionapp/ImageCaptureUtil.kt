package com.example.athervisionapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor

private const val TAG = "ImageCaptureUtil"
private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

/**
 * Helper class for handling image captures
 */
class ImageCaptureUtil(private val context: Context) {
    private val executor = ContextCompat.getMainExecutor(context)

    /**
     * Captures photo and saves it to specified storage
     */
    fun captureImage(
        imageCapture: ImageCapture,
        saveToGallery: Boolean = false,
        onImageCaptured: (Uri?) -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        // Create output options based on where we want to save the image
        val outputOptions = if (saveToGallery) {
            createMediaStoreOutputOptions(context)
        } else {
            createFileOutputOptions(context)
        }

        // Take the picture
        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    Log.d(TAG, "Photo capture succeeded: $savedUri")
                    onImageCaptured(savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    onError(exception)
                }
            }
        )
    }

    private fun createMediaStoreOutputOptions(context: Context): ImageCapture.OutputFileOptions {
        // Create time-stamped name for the image
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AtherVision")
            }
        }

        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
    }

    private fun createFileOutputOptions(context: Context): ImageCapture.OutputFileOptions {
        // Create an output file in app's internal storage
        val photoFile = File(
            context.getExternalFilesDir(null),
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        return ImageCapture.OutputFileOptions.Builder(photoFile).build()
    }
}