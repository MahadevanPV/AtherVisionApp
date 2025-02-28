package com.example.athervisionapp

import android.graphics.RectF

/**
 * Represents a single object detection result
 */
data class DetectionResult(
    val boundingBox: RectF,      // The bounding box of the detected object
    val label: String,           // The class label of the detected object
    val confidence: Float        // The confidence score (0-1) of the detection
)