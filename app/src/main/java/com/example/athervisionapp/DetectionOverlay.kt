package com.example.athervisionapp

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * A composable that draws bounding boxes and labels for detected objects
 */
@Composable
fun DetectionOverlay(
    detections: List<DetectionResult>,
    imageWidth: Int,
    imageHeight: Int,
    displayWidth: Int,
    displayHeight: Int
) {
    val textMeasurer = rememberTextMeasurer()

    // Define colors for different confidence levels
    val highConfidenceColor = Color(0xFF00FF00) // Green
    val mediumConfidenceColor = Color(0xFFFFFF00) // Yellow
    val lowConfidenceColor = Color(0xFFFF0000) // Red

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Calculate scaling factors to map model coordinates to screen coordinates
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        detections.forEach { detection ->
            // Scale bounding box to match the display
            val scaledBox = RectF(
                detection.boundingBox.left * scaleX,
                detection.boundingBox.top * scaleY,
                detection.boundingBox.right * scaleX,
                detection.boundingBox.bottom * scaleY
            )

            // Choose color based on confidence
            val boxColor = when {
                detection.confidence > 0.8f -> highConfidenceColor
                detection.confidence > 0.6f -> mediumConfidenceColor
                else -> lowConfidenceColor
            }

            // Draw bounding box
            drawRect(
                color = boxColor,
                topLeft = Offset(scaledBox.left, scaledBox.top),
                size = Size(scaledBox.width(), scaledBox.height()),
                style = Stroke(width = 3f)
            )

            // Prepare label text with confidence percentage
            val labelText = "${detection.label} ${(detection.confidence * 100).toInt()}%"

            // Draw text background
            val textStyle = TextStyle(
                color = Color.White,
                fontSize = 16.sp
            )

            // Measure text dimensions
            val textLayoutResult = textMeasurer.measure(labelText, textStyle)
            val textSize = Size(
                textLayoutResult.size.width.toFloat() + 8f,
                textLayoutResult.size.height.toFloat() + 8f
            )

            // Draw text background
            drawRect(
                color = boxColor.copy(alpha = 0.7f),
                topLeft = Offset(scaledBox.left, scaledBox.top - textSize.height),
                size = textSize
            )

            // Draw label text
            drawText(
                textMeasurer = textMeasurer,
                text = labelText,
                style = textStyle,
                topLeft = Offset(scaledBox.left + 4f, scaledBox.top - textSize.height + 4f)
            )
        }
    }
}