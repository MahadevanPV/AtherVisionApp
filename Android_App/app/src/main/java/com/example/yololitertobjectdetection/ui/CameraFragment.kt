package com.example.yololitertobjectdetection.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.yololitertobjectdetection.BoundingBox
import com.example.yololitertobjectdetection.Constants.LABELS_PATH
import com.example.yololitertobjectdetection.Constants
import com.example.yololitertobjectdetection.Detector
import com.example.yololitertobjectdetection.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.example.yololitertobjectdetection.R
import com.example.yololitertobjectdetection.GeometricDistanceCalculator
import java.util.concurrent.atomic.AtomicBoolean
import androidx.navigation.fragment.findNavController
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import java.util.LinkedList

class CameraFragment : Fragment(), Detector.DetectorListener {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    private val distanceCalculator = GeometricDistanceCalculator()

    // Frame skipping variables for adaptive processing
    private var frameCounter = 0
    private var currentFrameSkip = 2  // Start with processing every 3rd frame
    private val processingTimesMs = LinkedList<Long>() // Track recent processing times
    private val MAX_PROCESSING_TIMES_TRACKED = 10 // Number of times to track for averaging
    private var lastAdaptationTime = 0L // Track last time we adapted frame skip
    private val ADAPTATION_INTERVAL_MS = 2000 // Adapt every 2 seconds

    private val isFrontCamera = false
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private lateinit var cameraExecutor: ExecutorService

    // Flag to prevent processing more frames
    private val stopProcessing = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle the system back button more gracefully
        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "System back button pressed, handling safely")
                safeExit()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Mark to stop processing
        stopProcessing.set(true)

        // Clear analyzer first
        try {
            imageAnalyzer?.clearAnalyzer()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing analyzer in onDestroyView", e)
        }

        // Unbind camera use cases
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera in onDestroyView", e)
        }

        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()

        // Mark to stop processing
        stopProcessing.set(true)

        // Close detector on a background thread to avoid blocking UI
        val localDetector = detector
        detector = null

        if (localDetector != null) {
            Thread {
                try {
                    localDetector.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing detector in background thread", e)
                }
            }.start()
        }

        // Shutdown executor on a background thread
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            Thread {
                try {
                    cameraExecutor.shutdown()
                } catch (e: Exception) {
                    Log.e(TAG, "Error shutting down executor", e)
                }
            }.start()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Reset processing flag
        stopProcessing.set(false)

        try {
            binding.logoImage.setImageResource(R.drawable.ather)
            // Replace your existing back button code with this:
            binding.backButton?.setOnClickListener {
                val alertDialog = AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Exit Detection")
                    .setMessage("You are returning to the Home Screen?")
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()

                        // Set flag to stop processing immediately
                        stopProcessing.set(true)

                        // Clean up resources
                        try {
                            imageAnalyzer?.clearAnalyzer()
                            imageAnalyzer = null
                            cameraProvider?.unbindAll()
                            cameraProvider = null
                            detector = null
                        } catch (e: Exception) {
                            Log.e(TAG, "Error cleaning up resources", e)
                        }

                        // Navigate to home screen
                        try {
                            val intent = Intent(requireActivity(), requireActivity()::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error navigating to home", e)
                            requireActivity().finish()
                        }
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .create()

                // Ensure dialog buttons are visible
                alertDialog.setOnShowListener {
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark))
                    alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                }

                alertDialog.show()
            }

            cameraExecutor = Executors.newSingleThreadExecutor()

            try {
                val modelPath = Constants.getModelPath(requireContext())
                Log.d(TAG, "Loading model from: $modelPath")

                detector = Detector(requireContext(), modelPath, LABELS_PATH, this) {
                    toast(it)
                }

                if (allPermissionsGranted()) {
                    startCamera()
                } else {
                    ActivityCompat.requestPermissions(requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize detector", e)
                toast("Failed to load model: ${e.message}")
                // Don't crash, just show error on UI
                requireActivity().runOnUiThread {
                    binding.tvLatency?.text = "Model error: ${e.message}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated", e)
            toast("Error initializing camera: ${e.message}")
        }
    }

    // Safe method to exit the fragment
    private fun safeExit() {
        Log.d(TAG, "Safe exit initiated")

        // Make sure we only execute this once
        if (!stopProcessing.getAndSet(true)) {
            // First, immediately release all resources
            try {
                imageAnalyzer?.clearAnalyzer()
                imageAnalyzer = null
                cameraProvider?.unbindAll()
                cameraProvider = null

                // Release detector immediately
                detector?.close()
                detector = null

                if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
                    cameraExecutor.shutdownNow()
                }

                Log.d(TAG, "Resources released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing resources", e)
            }

            // Navigate back using the Navigation component
            try {
                requireActivity().runOnUiThread {
                    try {
                        // Since you're using Navigation component, use that for navigation
                        findNavController().navigateUp()
                        Log.d(TAG, "Navigation up triggered")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error with navigation up", e)
                        try {
                            // Fallback method
                            findNavController().popBackStack()
                            Log.d(TAG, "Pop back stack triggered")
                        } catch (e2: Exception) {
                            Log.e(TAG, "Failed to pop back stack", e2)
                            try {
                                // Last resort
                                requireActivity().onBackPressed()
                                Log.d(TAG, "Back pressed triggered")
                            } catch (e3: Exception) {
                                Log.e(TAG, "All navigation attempts failed", e3)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Navigation failed", e)
            }
        }
    }

    private fun startCamera() {
        if (!isAdded || isDetached || stopProcessing.get()) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                if (!isAdded || isDetached || stopProcessing.get()) return@addListener

                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                toast("Failed to start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        if (!isAdded || isDetached || _binding == null || stopProcessing.get()) return

        val cameraProvider = cameraProvider ?: run {
            Log.e(TAG, "Camera initialization failed - provider is null")
            toast("Camera initialization failed")
            return
        }

        try {
            val rotation = binding.viewFinder.display.rotation

            val cameraSelector = CameraSelector
                .Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    // Check immediately if we should stop processing
                    if (stopProcessing.get() || !isAdded || isDetached || _binding == null || detector == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    // Skip frames based on adaptive frame skipping
                    if (frameCounter++ % (currentFrameSkip + 1) != 0) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val bitmapBuffer =
                        Bitmap.createBitmap(
                            imageProxy.width,
                            imageProxy.height,
                            Bitmap.Config.ARGB_8888
                        )
                    imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
                    imageProxy.close()

                    // Check again if we should stop processing
                    if (stopProcessing.get() || !isAdded || isDetached || _binding == null || detector == null) {
                        return@setAnalyzer
                    }

                    val matrix = Matrix().apply {
                        postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                        if (isFrontCamera) {
                            postScale(
                                -1f,
                                1f,
                                imageProxy.width.toFloat(),
                                imageProxy.height.toFloat()
                            )
                        }
                    }

                    val rotatedBitmap = Bitmap.createBitmap(
                        bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                        matrix, true
                    )

                    // Final check before running inference
                    if (stopProcessing.get() || !isAdded || isDetached || _binding == null || detector == null) {
                        return@setAnalyzer
                    }

                    try {
                        detector?.detect(rotatedBitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "TensorFlow inference failed", e)
                        // Do not attempt further processing
                        stopProcessing.set(true)

                        // Update UI to show error
                        requireActivity().runOnUiThread {
                            if (isAdded && !isDetached && _binding != null) {
                                binding.tvLatency?.text = "Model error: ${e.message}"
                                binding.overlay?.clear()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image", e)
                    imageProxy.close()
                }
            }

            // Unbind before binding again
            cameraProvider.unbindAll()

            // Check if we should stop
            if (stopProcessing.get()) return

            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            toast("Camera setup failed: ${exc.message}")
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onStop() {
        super.onStop()
        // Set stop flag
        stopProcessing.set(true)

        // Release camera resources when the fragment is stopped
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStop", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (!stopProcessing.get() && detector != null && allPermissionsGranted()) {
                startCamera()
            } else if (!stopProcessing.get() && detector != null) {
                requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
        }
    }

    override fun onPause() {
        super.onPause()
        // Set stop flag
        stopProcessing.set(true)

        try {
            // Release camera resources when the fragment is paused
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause", e)
        }
    }

    // Adaptive frame skip logic - adjusts based on processing times
    private fun adaptFrameSkip(processingTimeMs: Long) {
        // Add to processing times history
        processingTimesMs.add(processingTimeMs)
        if (processingTimesMs.size > MAX_PROCESSING_TIMES_TRACKED) {
            processingTimesMs.removeFirst()
        }

        // Only adapt every few seconds to avoid rapid changes
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastAdaptationTime < ADAPTATION_INTERVAL_MS) {
            return
        }
        lastAdaptationTime = currentTime

        // Wait until we have enough data points
        if (processingTimesMs.size >= 5) {
            val avgProcessingTime = processingTimesMs.average().toLong()

            // Calculate new frame skip value based on average processing time
            val newFrameSkip = when {
                avgProcessingTime > 200 -> 3  // Very slow: process every 4th frame
                avgProcessingTime > 150 -> 2  // Slow: process every 3rd frame
                avgProcessingTime > 80 -> 1   // Moderate: process every 2nd frame
                else -> 0                     // Fast: process every frame
            }

            // Only update if the frame skip value changed
            if (newFrameSkip != currentFrameSkip) {
                currentFrameSkip = newFrameSkip

                // Log the adaptation for debugging
                Log.d(TAG, "Adaptive frame skip adjusted to: $currentFrameSkip (avg processing time: $avgProcessingTime ms)")

                // Show a transient message about adaptive performance
                requireActivity().runOnUiThread {
                    if (isAdded && !isDetached && _binding != null && !stopProcessing.get()) {
                        val fps = when (currentFrameSkip) {
                            0 -> "Max FPS"
                            1 -> "1/2 FPS"
                            2 -> "1/3 FPS"
                            else -> "1/${currentFrameSkip + 1} FPS"
                        }
                        val message = "Performance adaptation: $fps mode"
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "CameraFragment"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        try {
            if (isAdded && !isDetached && _binding != null && !stopProcessing.get()) {
                requireActivity().runOnUiThread {
                    binding.overlay?.clear()
                    binding.tvLatency?.text = "No objects detected"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onEmptyDetect", e)
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long, detailedTiming: Map<String, Long>) {
        try {
            if (!isAdded || isDetached || _binding == null || stopProcessing.get()) return

            // Adapt frame skipping based on processing performance
            adaptFrameSkip(inferenceTime)

            // Create modified bounding boxes with distance information
            val boundingBoxesWithDistance = boundingBoxes.map { box ->
                // Get image height from the view
                val imageHeight = binding.viewFinder?.height ?: 0

                // Calculate distance
                val distance = distanceCalculator.estimateDistance(box, imageHeight, box.clsName)

                // Format distance to 2 decimal places
                val distanceText = String.format("%.2f", distance)

                // Create a new bounding box with distance info added to the label
                BoundingBox(
                    box.x1, box.y1, box.x2, box.y2,
                    box.cnf, box.cls, "${box.clsName} ${distanceText}m"
                )
            }

            requireActivity().runOnUiThread {
                if (isAdded && !isDetached && _binding != null && !stopProcessing.get()) {
                    binding.overlay?.apply {
                        // Use the new bounding boxes with distance information
                        setResults(boundingBoxesWithDistance)
                        invalidate()
                    }

                    // Update the latency text with frame skip information
                    val frameRateInfo = when (currentFrameSkip) {
                        0 -> "Max"
                        1 -> "1/2"
                        2 -> "1/3"
                        else -> "1/${currentFrameSkip + 1}"
                    }
                    binding.tvLatency?.text = "Latency: $inferenceTime ms (FPS: $frameRateInfo)"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDetect", e)
        }
    }

    private fun toast(message: String) {
        try {
            lifecycleScope.launch(Dispatchers.Main) {
                if (isAdded && !isDetached && !stopProcessing.get()) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing toast", e)
        }
    }
}