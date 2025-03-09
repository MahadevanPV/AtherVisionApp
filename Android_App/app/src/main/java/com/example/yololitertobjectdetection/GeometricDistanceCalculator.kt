package com.example.yololitertobjectdetection

import android.util.Log

class GeometricDistanceCalculator {
    // Add a TAG for logging
    private val TAG = "GeometricDistCalc"

    private val objectHeights = mapOf(
        "person" to 1.7f,
        "car" to 1.5f,
        "truck" to 2.0f,
        "bus" to 3.0f,
        "motorcycle" to 1.3f,
        "bicycle" to 1.2f,
        "traffic light" to 0.9f,
        "stop sign" to 0.8f,
        "bottle" to 0.3f,
        "chair" to 0.8f,
        "dog" to 0.6f,
        "cat" to 0.4f
    )

    private val DEFAULT_HEIGHT = 1.0f
    // Focal length of the camera (would need to be calibrated)
    private var FOCAL_LENGTH = 800f

    // Method to calibrate focal length using an object of known size at a known distance
    fun calibrateFocalLength(objectHeightPixels: Float, realWorldHeight: Float, knownDistance: Float) {
        // Formula: focal_length = (pixel_height * known_distance) / real_world_height
        FOCAL_LENGTH = (objectHeightPixels * knownDistance) / realWorldHeight
    }

    // Better calibration method with logging
    fun calibrateWithKnownObject(objectRealHeight: Float, objectPixelHeight: Float, knownDistance: Float) {
        FOCAL_LENGTH = (objectPixelHeight * knownDistance) / objectRealHeight
        Log.d(TAG, "Calibrated focal length: $FOCAL_LENGTH")
    }

    fun estimateDistance(detectedObject: BoundingBox, imageHeight: Int, className: String): Float {
        // Get the appropriate real-world height based on object class
        val realWorldHeight = objectHeights[className.lowercase()] ?: DEFAULT_HEIGHT

        // Calculate the height of the object in pixels
        val objectHeightPixels = (detectedObject.y2 - detectedObject.y1) * imageHeight

        // Check for valid height to avoid division by zero
        if (objectHeightPixels <= 0) return 10.0f  // Default distance if calculation fails

        // Use the formula: distance = (real world height * focal length) / apparent height in pixels
        return (realWorldHeight * FOCAL_LENGTH) / objectHeightPixels
    }

    // Add a method to get color based on distance
    fun getColorForDistance(distance: Float): Int {
        return when {
            distance < 2.0f -> android.graphics.Color.RED     // Very close (danger zone)
            distance < 5.0f -> android.graphics.Color.rgb(255, 165, 0)  // Orange (caution)
            distance < 10.0f -> android.graphics.Color.GREEN  // Safe distance
            else -> android.graphics.Color.BLUE                // Far away
        }
    }
}