package com.example.yololitertobjectdetection

class GeometricDistanceCalculator {
    // Known average height of a person in meters
    private val AVERAGE_PERSON_HEIGHT = 1.7f
    // Known average height of a car in meters
    private val AVERAGE_CAR_HEIGHT = 1.5f

    private val AVERAGE_BOTTLE_HEIGHT = 0.5f

    // Focal length of the camera (would need to be calibrated)
    private var FOCAL_LENGTH = 800f

    // Method to calibrate focal length using an object of known size at a known distance
    fun calibrateFocalLength(objectHeightPixels: Float, realWorldHeight: Float, knownDistance: Float) {
        // Formula: focal_length = (pixel_height * known_distance) / real_world_height
        FOCAL_LENGTH = (objectHeightPixels * knownDistance) / realWorldHeight
    }

    fun estimateDistance(detectedObject: BoundingBox, imageHeight: Int, className: String): Float {
        // Get the real-world object height based on the class
        val realWorldHeight = when (className.lowercase()) {
            "person" -> AVERAGE_PERSON_HEIGHT
            "car", "truck", "bus" -> AVERAGE_CAR_HEIGHT
            "Bottle" -> AVERAGE_BOTTLE_HEIGHT
            else -> 1.0f // Default estimation
        }

        // Calculate the height of the object in pixels
        val objectHeightPixels = (detectedObject.y2 - detectedObject.y1) * imageHeight

        // Use the formula: distance = (real world height * focal length) / apparent height in pixels
        return (realWorldHeight * FOCAL_LENGTH) / objectHeightPixels
    }
}