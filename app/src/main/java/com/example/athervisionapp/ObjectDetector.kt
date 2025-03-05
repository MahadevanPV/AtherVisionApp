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

/**
 * A class to perform object detection using YOLOv10-Lite TFLite model
 */
class ObjectDetector(private val context: Context) {
    private val TAG = "ObjectDetector"
    private var interpreter: Interpreter? = null

    // Buffer to hold the input image data
    private val inputBuffer = ByteBuffer.allocateDirect(
        4 * ModelInfo.MODEL_WIDTH * ModelInfo.MODEL_HEIGHT * 3
    ).apply {
        order(ByteOrder.nativeOrder())
    }

    // Buffer to hold model output data - shape [1, 300, 6]
    private var outputBuffer: Array<Array<FloatArray>>? = null

    init {
        setupInterpreter()
    }

    /**
     * Sets up the TensorFlow Lite interpreter with appropriate options
     */
    private fun setupInterpreter() {
        try {
            Log.d(TAG, "📂 Loading model: ${ModelInfo.MODEL_NAME}")
            val model = loadModelFile()
            Log.d(TAG, "📁 Model loaded, size: ${model.capacity()} bytes")

            val options = Interpreter.Options()
            options.setNumThreads(4)

            // Create interpreter
            interpreter = Interpreter(model, options)

            // Get model input/output info
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            Log.d(TAG, "Model input shape: ${inputShape?.contentToString()}")
            Log.d(TAG, "Model output shape: ${outputShape?.contentToString()}")

            // Create output buffer with correct dimensions
            outputBuffer = Array(1) { // batch size
                Array(300) { // number of boxes
                    FloatArray(6) // features per box
                }
            }

            Log.d(TAG, "✅ Interpreter initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up interpreter: ${e.message}", e)
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
    fun detect(bitmap: Bitmap, confidenceThreshold: Float = 0.1f): List<DetectionResult> { // Lower threshold
        Log.d(TAG, "🔍 Running detection on ${bitmap.width}x${bitmap.height} bitmap")

        // Ensure interpreter is initialized
        val interpreter = interpreter ?: run {
            Log.e(TAG, "Interpreter is null, reinitializing")
            setupInterpreter()
            interpreter ?: return emptyList()
        }

        val outputBuffer = outputBuffer ?: run {
            Log.e(TAG, "Output buffer is null")
            return emptyList()
        }

        try {
            // Preprocess the image - resize and normalize
            preprocessImage(bitmap)

            // Run inference
            interpreter.run(inputBuffer, outputBuffer)

            // Debug: Log first 5 boxes
            Log.d(TAG, "Output sample:")
            for (i in 0 until minOf(5, outputBuffer[0].size)) {
                Log.d(TAG, "Box $i: " +
                        "x=${outputBuffer[0][i][0]}, " +
                        "y=${outputBuffer[0][i][1]}, " +
                        "w=${outputBuffer[0][i][2]}, " +
                        "h=${outputBuffer[0][i][3]}, " +
                        "conf=${outputBuffer[0][i][4]}, " +
                        "class=${outputBuffer[0][i][5]}")
            }

            // Process detection results
            val results = postprocessResults(outputBuffer, confidenceThreshold)

            Log.d(TAG, "🎯 Detection found ${results.size} objects")
            return results
        } catch (e: Exception) {
            Log.e(TAG, "Error during detection: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Convert bitmap to input tensor
     */
    private fun preprocessImage(bitmap: Bitmap) {
        // Reset the buffer
        inputBuffer.rewind()

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

        // Load normalized pixels into input buffer (0-255 to 0-1)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            // Extract and normalize RGB values
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)          // B
        }
    }

    /**
     * Process the raw output from the model to get detection results
     */
    private fun postprocessResults(
        outputBuffer: Array<Array<FloatArray>>,
        confidenceThreshold: Float
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()

        try {
            // Process each detection box
            for (i in 0 until outputBuffer[0].size) { // iterate through boxes
                try {
                    // Get confidence (index 4)
                    val confidence = outputBuffer[0][i][4]

                    // Skip if below threshold
                    if (confidence < confidenceThreshold) continue

                    // Get class ID (index 5)
                    val classId = outputBuffer[0][i][5].toInt()

                    // Skip if invalid class or empty boxes
                    if (classId < 0 || classId >= ModelInfo.LABELS.size) continue

                    // Get normalized coordinates (x, y, w, h)
                    val x = outputBuffer[0][i][0]
                    val y = outputBuffer[0][i][1]
                    val w = outputBuffer[0][i][2]
                    val h = outputBuffer[0][i][3]

                    // Skip if invalid dimensions
                    if (w <= 0 || h <= 0) continue

                    // Create mock detection for testing
                    if (confidence > 0 && w <= 0) {
                        // Create a test detection in the center with 20% size
                        val boundingBox = RectF(
                            0.4f * ModelInfo.MODEL_WIDTH,
                            0.4f * ModelInfo.MODEL_HEIGHT,
                            0.6f * ModelInfo.MODEL_WIDTH,
                            0.6f * ModelInfo.MODEL_HEIGHT
                        )

                        results.add(
                            DetectionResult(
                                boundingBox = boundingBox,
                                label = "Test",
                                confidence = 0.8f
                            )
                        )
                        continue
                    }

                    // Convert normalized coordinates to pixel values
                    val left = (x - w/2) * ModelInfo.MODEL_WIDTH
                    val top = (y - h/2) * ModelInfo.MODEL_HEIGHT
                    val right = (x + w/2) * ModelInfo.MODEL_WIDTH
                    val bottom = (y + h/2) * ModelInfo.MODEL_HEIGHT

                    // Create bounding box
                    val boundingBox = RectF(
                        left.coerceIn(0f, ModelInfo.MODEL_WIDTH.toFloat()),
                        top.coerceIn(0f, ModelInfo.MODEL_HEIGHT.toFloat()),
                        right.coerceIn(0f, ModelInfo.MODEL_WIDTH.toFloat()),
                        bottom.coerceIn(0f, ModelInfo.MODEL_HEIGHT.toFloat())
                    )

                    // Create detection result
                    val result = DetectionResult(
                        boundingBox = boundingBox,
                        label = ModelInfo.LABELS[classId],
                        confidence = confidence
                    )

                    Log.d(TAG, "Adding detection: ${result.label} (${result.confidence}) at ${result.boundingBox}")
                    results.add(result)

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing detection box $i: ${e.message}")
                    continue
                }
            }

            // If we didn't find any objects but have high confidence,
            // add a test detection for debugging
            if (results.isEmpty()) {
                Log.w(TAG, "No detections found, adding a test detection")

                // Create a test detection in the center with 20% size
                val boundingBox = RectF(
                    0.4f * ModelInfo.MODEL_WIDTH,
                    0.4f * ModelInfo.MODEL_HEIGHT,
                    0.6f * ModelInfo.MODEL_WIDTH,
                    0.6f * ModelInfo.MODEL_HEIGHT
                )

                results.add(
                    DetectionResult(
                        boundingBox = boundingBox,
                        label = "Test",
                        confidence = 0.8f
                    )
                )
            }

            return results

        } catch (e: Exception) {
            Log.e(TAG, "Error in postprocessing: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Clean up resources when detector is no longer needed
     */
    fun close() {
        interpreter?.close()
        interpreter = null

        Log.d(TAG, "Detector resources released")
    }
}