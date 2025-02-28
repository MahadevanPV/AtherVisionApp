package com.example.athervisionapp

object ModelInfo {

    const val MODEL_NAME = "yolov10n_float16.tflite"
    const val MODEL_WIDTH = 640
    const val MODEL_HEIGHT = 640

    // Add these constants to ModelInfo.kt
    const val OUTPUT_SIZE = 300  // The number of detection results
    const val OUTPUT_FEATURES = 6  // The number of features per detection (x, y, w, h, confidence, class)

    // COCO dataset labels
    val LABELS = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "cat",
        "dog", "horse", "cow", "backpack", "bottle", "cup", "chair", "bed", "toilet", "tv", "laptop", "mouse",
         "cell phone",
    )
}