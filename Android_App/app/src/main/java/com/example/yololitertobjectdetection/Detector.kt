package com.example.yololitertobjectdetection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
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
    private var isYoloV12Format = false  // Flag to track model format
    private var vehicleClasses = listOf(
        "car", "truck", "bus", "motorcycle", "motorbike",
        "bicycle", "train", "airplane", "boat", "ship"
    )
    // Add frame caching variables
    private var lastFrameHash: Int = 0
    private var lastResults: List<BoundingBox> = emptyList()
    private var cacheHits = 0
    private var cacheMisses = 0
    private val HASH_THRESHOLD = 150 // Threshold for similarity - adjust based on testing

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
            Log.d("Detector", "Model output shape: ${outputShape.joinToString()}")

            // Check if the model is YOLOv12 format (typically [1, 84, 8400])
            if (outputShape.size == 3 && outputShape[1] > 10 && outputShape[2] > 1000) {
                isYoloV12Format = true
                Log.d("Detector", "Detected YOLOv12 format")
                numChannel = outputShape[1]  // Classes + box params (e.g., 84)
                numElements = outputShape[2] // Number of boxes (e.g., 8400)
            } else {
                // Original YOLOv10 format
                numElements = outputShape[1]
                numChannel = outputShape[2]
            }
        }
    }

    // Bitmap hash calculation helper
    private fun Bitmap.calculateHash(): Int {
        // Create a 16x16 downsampled version for hash comparison
        val size = 16
        val scaledBitmap = Bitmap.createScaledBitmap(this, size, size, true)

        // Calculate average color
        var r = 0
        var g = 0
        var b = 0
        var pixelCount = 0

        // Simple grid sampling (every 4th pixel) for faster calculation
        for (x in 0 until size step 4) {
            for (y in 0 until size step 4) {
                val pixel = scaledBitmap.getPixel(x, y)
                r += (pixel shr 16) and 0xff
                g += (pixel shr 8) and 0xff
                b += pixel and 0xff
                pixelCount++
            }
        }

        // Calculate averages
        r /= pixelCount
        g /= pixelCount
        b /= pixelCount

        // Create a perceptual hash by comparing pixels to average
        var hash = 0
        var bitPos = 0

        // Create hash from a subset of pixels (faster)
        for (x in 0 until size step 4) {
            for (y in 0 until size step 4) {
                if (bitPos >= 31) break // Int can hold 32 bits

                val pixel = scaledBitmap.getPixel(x, y)
                val pr = (pixel shr 16) and 0xff
                val pg = (pixel shr 8) and 0xff
                val pb = pixel and 0xff

                // Set bit if pixel is brighter than average
                if ((pr + pg + pb) / 3 > (r + g + b) / 3) {
                    hash = hash or (1 shl bitPos)
                }

                bitPos++
            }
        }

        // Clean up
        if (scaledBitmap != this) {
            scaledBitmap.recycle()
        }

        return hash
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

        // Reset cache when restarting
        lastFrameHash = 0
        lastResults = emptyList()
        cacheHits = 0
        cacheMisses = 0
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

        // Check frame hash to see if we can use cached results
        val frameHash = frame.calculateHash()
        val hashDifference = Math.abs(frameHash - lastFrameHash)

        if (hashDifference < HASH_THRESHOLD && lastResults.isNotEmpty()) {
            // Use cached results
            cacheHits++

            Log.d("Detector", "Cache hit! Using cached results. " +
                    "Hits: $cacheHits, Misses: $cacheMisses. Hash diff: $hashDifference")

            val cachedProcessingTime = 1L // Virtually instant
            detectorListener.onDetect(lastResults, cachedProcessingTime)
            return
        }

        // Cache miss - need to process the frame
        cacheMisses++

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
        val output = TensorBuffer.createFixedSize(
            if (isYoloV12Format) intArrayOf(1, numChannel, numElements) else intArrayOf(1, numElements, numChannel),
            OUTPUT_IMAGE_TYPE
        )
        interpreter.run(imageBuffer, output.buffer)
        val inferenceTime = SystemClock.uptimeMillis() - inferenceStartTime

        // Timing for post-processing
        val postprocessingStartTime = SystemClock.uptimeMillis()
        val bestBoxes = if (isYoloV12Format) {
            bestBoxYoloV12(output.floatArray)
        } else {
            bestBox(output.floatArray)
        }
        val postprocessingTime = SystemClock.uptimeMillis() - postprocessingStartTime

        // Total processing time
        val totalTime = SystemClock.uptimeMillis() - totalStartTime

        if (bestBoxes.isEmpty()) {
            detectorListener.onEmptyDetect()
            return
        }

        // Update cache
        lastFrameHash = frameHash
        lastResults = bestBoxes

        // Log cache statistics periodically
        if ((cacheHits + cacheMisses) % 100 == 0) {
            val hitRate = (cacheHits * 100.0) / (cacheHits + cacheMisses)
            Log.d("Detector", "Cache stats: Hit rate: $hitRate% (Hits: $cacheHits, Misses: $cacheMisses)")
        }

        // Create timing information map
        val timingInfo = mapOf(
            "preprocessing" to preprocessingTime,
            "inference" to inferenceTime,
            "postprocessing" to postprocessingTime
        )

        // Pass all timing information to the listener
        detectorListener.onDetect(bestBoxes, totalTime, timingInfo)

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
                val clsName = if (cls < labels.size) labels[cls] else "unknown"
                if (clsName != "unknown" && vehicleClasses.any { vehicle -> clsName.equals(vehicle, ignoreCase = true) }) {
                    boundingBoxes.add(
                        BoundingBox(
                            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                            cnf = cnf, cls = cls, clsName = clsName
                        )
                    )
                }
            }
        }
        return boundingBoxes
    }

    private fun bestBoxYoloV12(array: FloatArray): List<BoundingBox> {
        val boundingBoxes = mutableListOf<BoundingBox>()

        // Number of classes is numChannel - 4 (x,y,w,h coordinates)
        val numClasses = numChannel - 4

        // Process each of the boxes
        for (i in 0 until numElements) {
            // Find the class with highest confidence
            var maxClassIndex = 0
            var maxClassScore = 0f

            for (c in 0 until numClasses) {
                val score = array[(4 + c) * numElements + i]
                if (score > maxClassScore) {
                    maxClassScore = score
                    maxClassIndex = c
                }
            }

            // Filter by confidence threshold
            if (maxClassScore > CONFIDENCE_THRESHOLD) {
                // Get box coordinates (xywh format in YOLOv12)
                val x = array[0 * numElements + i]
                val y = array[1 * numElements + i]
                val w = array[2 * numElements + i]
                val h = array[3 * numElements + i]

                // Convert xywh (center, width, height) to xyxy (top-left, bottom-right)
                val x1 = x - w/2
                val y1 = y - h/2
                val x2 = x + w/2
                val y2 = y + h/2

                // Create bounding box
                val clsName = if (maxClassIndex < labels.size) labels[maxClassIndex] else "unknown"
                if (clsName != "unknown" && vehicleClasses.any { vehicle -> clsName.equals(vehicle, ignoreCase = true) }) {
                    boundingBoxes.add(
                        BoundingBox(
                            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                            cnf = maxClassScore, cls = maxClassIndex, clsName = clsName
                        )
                    )
                }
            }
        }

        // Apply non-maximum suppression to remove overlapping boxes
        return applyNMS(boundingBoxes)
    }

    // Non-maximum suppression to remove overlapping boxes
    private fun applyNMS(boxes: List<BoundingBox>, iouThreshold: Float = 0.45f): List<BoundingBox> {
        if (boxes.isEmpty()) return emptyList()

        // Sort boxes by confidence score (highest first)
        val sortedBoxes = boxes.sortedByDescending { it.cnf }
        val selectedBoxes = mutableListOf<BoundingBox>()
        val remainingBoxes = sortedBoxes.toMutableList()

        while (remainingBoxes.isNotEmpty()) {
            // Select box with highest confidence
            val best = remainingBoxes.removeAt(0)
            selectedBoxes.add(best)

            // Remove boxes with high IoU overlap and same class
            val iterator = remainingBoxes.iterator()
            while (iterator.hasNext()) {
                val box = iterator.next()
                if (box.cls == best.cls && calculateIoU(best, box) > iouThreshold) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    // Calculate Intersection over Union between two boxes
    private fun calculateIoU(a: BoundingBox, b: BoundingBox): Float {
        // Calculate intersection area
        val xMin = maxOf(a.x1, b.x1)
        val yMin = maxOf(a.y1, b.y1)
        val xMax = minOf(a.x2, b.x2)
        val yMax = minOf(a.y2, b.y2)

        if (xMin >= xMax || yMin >= yMax) return 0f  // No overlap

        val intersectionArea = (xMax - xMin) * (yMax - yMin)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)

        return intersectionArea / (areaA + areaB - intersectionArea)
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