package com.example.athervisionapp


import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import com.example.athervisionapp.ModelInfo

/**
 * A class to perform object detection using YOLOv10-LiteRT TFLite model
 */
class ObjectDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val modelBuffer = ByteBuffer.allocateDirect(
        4 * ModelInfo.MODEL_WIDTH * ModelInfo.MODEL_HEIGHT * 3
    ).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputBuffer = Array(1) {
        Array(ModelInfo.OUTPUT_FEATURES) {
            FloatArray(ModelInfo.OUTPUT_SIZE)
        }
    }

    private val TAG = "ObjectDetector"

    init {
        try {
            Log.d(TAG, "📂 Attempting to load model: ${ModelInfo.MODEL_NAME}")
            val model = loadModelFile()
            Log.d(TAG, "📁 Model file loaded, size: ${model.capacity()} bytes")

            val options = Interpreter.Options()
            interpreter = Interpreter(model, options)
            Log.d(TAG, "✅ Model loaded successfully, interpreter initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading model: ${e.message}", e)
        }
    }

    /**
     * Load the TFLite model from assets
     */
    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(ModelInfo.MODEL_NAME)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Preprocess the bitmap and run inference
     */
    fun detect(bitmap: Bitmap, confidenceThreshold: Float = 0.5f): List<DetectionResult> {
        Log.d("ObjectDetector", "🔍 Running detection on ${bitmap.width}x${bitmap.height} bitmap")

        // Ensure interpreter is initialized
        val interpreter = interpreter ?: return emptyList()

        // Preprocess the image - resize and normalize
        preprocessImage(bitmap)

        // Run inference
        interpreter.run(modelBuffer, outputBuffer)

        // Process detection results
        val results = postprocessResults(confidenceThreshold)

        Log.d("ObjectDetector", "🎯 Detection found ${results.size} objects")
        return results
    }

    /**
     * Convert bitmap to input tensor
     */
    private fun preprocessImage(bitmap: Bitmap) {
        // Reset the buffer
        modelBuffer.rewind()

        // Resize the bitmap to the model's input dimensions
        val resizedBitmap = Bitmap.createScaledBitmap(
            bitmap,
            ModelInfo.MODEL_WIDTH,
            ModelInfo.MODEL_HEIGHT,
            true
        )

        // Convert bitmap to input tensor and normalize pixel values
        val pixels = IntArray(ModelInfo.MODEL_WIDTH * ModelInfo.MODEL_HEIGHT)
        resizedBitmap.getPixels(
            pixels, 0, ModelInfo.MODEL_WIDTH, 0, 0,
            ModelInfo.MODEL_WIDTH, ModelInfo.MODEL_HEIGHT
        )

        // Load the bitmap into input buffer
        // Normalize from 0-255 to 0-1
        for (i in pixels.indices) {
            val pixel = pixels[i]
            // Extract RGB values
            modelBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            modelBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            modelBuffer.putFloat((pixel and 0xFF) / 255.0f)          // B
        }
    }

    private fun postprocessResults(confidenceThreshold: Float): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()

        // The YOLOv10n model outputs in a different format than expected
        // Format: [1, 300, 6] where 300 is the number of boxes and 6 contains:
        // [x, y, width, height, confidence, class]

        // Loop through all 300 potential detections
        for (i in 0 until ModelInfo.OUTPUT_SIZE) {  // Changed OUTPUT_SIZE to ModelInfo.OUTPUT_SIZE
            // Get confidence (index 4)
            val confidence = outputBuffer[0][4][i]

            // Skip if below threshold
            if (confidence < confidenceThreshold) continue

            // Get class id (index 5)
            val classId = outputBuffer[0][5][i].toInt()

            // Skip if invalid class
            if (classId < 0 || classId >= ModelInfo.LABELS.size) continue

            // Get box coordinates (indices 0-3)
            val x = outputBuffer[0][0][i]
            val y = outputBuffer[0][1][i]
            val w = outputBuffer[0][2][i]
            val h = outputBuffer[0][3][i]

            // Convert to bounding box (YOLO gives center x,y and width, height)
            val left = (x - w/2) * ModelInfo.MODEL_WIDTH
            val top = (y - h/2) * ModelInfo.MODEL_HEIGHT
            val right = (x + w/2) * ModelInfo.MODEL_WIDTH
            val bottom = (y + h/2) * ModelInfo.MODEL_HEIGHT

            // Create bounding box - You need to add ModelInfo. prefix to all these instances too
            val boundingBox = RectF(
                left.coerceIn(0f, ModelInfo.MODEL_WIDTH.toFloat()),  // Changed MODEL_WIDTH to ModelInfo.MODEL_WIDTH
                top.coerceIn(0f, ModelInfo.MODEL_HEIGHT.toFloat()),  // Changed MODEL_HEIGHT to ModelInfo.MODEL_HEIGHT
                right.coerceIn(0f, ModelInfo.MODEL_WIDTH.toFloat()), // Changed MODEL_WIDTH to ModelInfo.MODEL_WIDTH
                bottom.coerceIn(0f, ModelInfo.MODEL_HEIGHT.toFloat()) // Changed MODEL_HEIGHT to ModelInfo.MODEL_HEIGHT
            )

            // Add to results
            results.add(
                DetectionResult(
                    boundingBox = boundingBox,
                    label = ModelInfo.LABELS[classId],
                    confidence = confidence
                )
            )
        }

        return results
    }

    /**
     * Clean up resources when detector is no longer needed
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}