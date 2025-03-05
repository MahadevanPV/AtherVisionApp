package com.example.yololitertobjectdetection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.example.yololitertobjectdetection.MetaData.extractNamesFromLabelFile
import com.example.yololitertobjectdetection.MetaData.extractNamesFromMetadata
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import android.util.Log


class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String?,
    private val detectorListener: DetectorListener,
    private val message: (String) -> Unit
) {
    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    // Add the function right here, before the init block
    private fun checkGpuSupport(context: Context): Boolean {
        val compatList = CompatibilityList()
        val isSupported = compatList.isDelegateSupportedOnThisDevice

        // Log the result
        Log.d("GPU_SUPPORT", "GPU delegation supported: $isSupported")

        // Notify through the message callback
        message("GPU acceleration ${if (isSupported) "is" else "is not"} supported")

        return isSupported
    }

    init {
        // Check GPU support first
        val gpuSupported = checkGpuSupport(context)

        val options = Interpreter.Options().apply {
            if (gpuSupported) {
                val compatList = CompatibilityList()
                val delegateOptions = compatList.bestOptionsForThisDevice
                this.addDelegate(GpuDelegate(delegateOptions))
                Log.d("GPU_SUPPORT", "Using GPU acceleration for inference")
            } else {
                // Fall back to multi-threading on CPU
                this.setNumThreads(4)
                Log.d("GPU_SUPPORT", "Using CPU for inference (4 threads)")
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)

        labels.addAll(extractNamesFromMetadata(model))
        if (labels.isEmpty()) {
            if (labelPath == null) {
                message("Model not contains metadata, provide LABELS_PATH in Constants.kt")
                labels.addAll(MetaData.TEMP_CLASSES)
            } else {
                labels.addAll(extractNamesFromLabelFile(context, labelPath))
            }
        }

        labels.forEach(::println)

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]

            // If in case input shape is in format of [1, 3, ..., ...]
            if (inputShape[1] == 3) {
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }

        if (outputShape != null) {
            numElements = outputShape[1]
            numChannel = outputShape[2]
        }
    }

    fun restart(isGpu: Boolean) {
        interpreter.close()

        val options = if (isGpu) {
            val compatList = CompatibilityList()
            Interpreter.Options().apply{
                if(compatList.isDelegateSupportedOnThisDevice){
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    this.addDelegate(GpuDelegate(delegateOptions))
                } else {
                    this.setNumThreads(4)
                }
            }
        } else {
            Interpreter.Options().apply{
                this.setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)
    }


    fun close() {
        interpreter.close()
    }

    fun detect(frame: Bitmap) {
        if (tensorWidth == 0
            || tensorHeight == 0
            || numChannel == 0
            || numElements == 0) return

        // Start overall timing
        val totalStartTime = SystemClock.uptimeMillis()

        // Timing for image preprocessing
        val preprocessingStartTime = SystemClock.uptimeMillis()
        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)
        val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
        tensorImage.load(resizedBitmap)
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer
        val preprocessingTime = SystemClock.uptimeMillis() - preprocessingStartTime

        // Timing for model inference
        val inferenceStartTime = SystemClock.uptimeMillis()
        val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
        interpreter.run(imageBuffer, output.buffer)
        val inferenceTime = SystemClock.uptimeMillis() - inferenceStartTime

        // Timing for post-processing
        val postprocessingStartTime = SystemClock.uptimeMillis()
        val bestBoxes = bestBox(output.floatArray)
        val postprocessingTime = SystemClock.uptimeMillis() - postprocessingStartTime

        // Total processing time
        val totalTime = SystemClock.uptimeMillis() - totalStartTime

        if (bestBoxes.isEmpty()) {
            detectorListener.onEmptyDetect()
            return
        }

        // Pass all timing information to the listener
        detectorListener.onDetect(bestBoxes, totalTime)

        // Optionally log detailed timing
        Log.d("Detector", "Times - Preprocess: $preprocessingTime ms, " +
                "Inference: $inferenceTime ms, " +
                "Postprocess: $postprocessingTime ms, " +
                "Total: $totalTime ms")
    }
    private fun bestBox(array: FloatArray) : List<BoundingBox> {
        val boundingBoxes = mutableListOf<BoundingBox>()
        for (r in 0 until numElements) {
            val cnf = array[r * numChannel + 4]
            if (cnf > CONFIDENCE_THRESHOLD) {
                val x1 = array[r * numChannel]
                val y1 = array[r * numChannel + 1]
                val x2 = array[r * numChannel + 2]
                val y2 = array[r * numChannel + 3]
                val cls = array[r * numChannel + 5].toInt()
                val clsName = labels[cls]
                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cnf = cnf, cls = cls, clsName = clsName
                    )
                )
            }
        }
        return boundingBoxes
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long, detailedTiming: Map<String, Long> = emptyMap())
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.3F
    }
}