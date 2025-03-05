package com.example.yololitertobjectdetection.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.example.yololitertobjectdetection.Constants.MODEL_PATH
import com.example.yololitertobjectdetection.Detector
import com.example.yololitertobjectdetection.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.example.yololitertobjectdetection.R
import com.example.yololitertobjectdetection.GeometricDistanceCalculator

class CameraFragmentOld : Fragment(), Detector.DetectorListener {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    private val distanceCalculator = GeometricDistanceCalculator()

    private var lastProcessingTimeMs = 0L
    private val FRAME_INTERVAL_MS = 100 // 10 FPS (1000ms / 10 = 100ms)

    private val isFrontCamera = false
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.logoImage.setImageResource(R.drawable.ather)
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(requireContext(), MODEL_PATH, LABELS_PATH, this) {
                toast(it)
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview =  Preview.Builder()
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
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

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

            detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }

    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        requireActivity().runOnUiThread {
            binding.overlay.clear()
            // Optionally update latency text here if you want to show processing time
            // even when no objects are detected
            binding.tvLatency.text = "No objects detected"
        }
    }

    // If you've added detailed timing as in the example I provided:
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long, detailedTiming: Map<String, Long>) {
        // Create an instance of the calculator if you haven't already added it as a property
        val distanceCalculator = GeometricDistanceCalculator()

        // Create modified bounding boxes with distance information
        val boundingBoxesWithDistance = boundingBoxes.map { box ->
            // Get image height from the view
            val imageHeight = binding.viewFinder.height

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
            binding.overlay.apply {
                // Use the new bounding boxes with distance information
                setResults(boundingBoxesWithDistance)
                invalidate()
            }
            // Update the latency text
            binding.tvLatency.text = "Latency: $inferenceTime ms"
            // You can use detailedTiming if needed
            // Log.d("CameraFragment", "Detailed timing: $detailedTiming")
        }
    }

    private fun toast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }


}