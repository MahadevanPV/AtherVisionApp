package com.example.athervisionapp

import android.graphics.RectF
import android.util.Log
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
import androidx.compose.ui.text.font.FontWeight
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
        val scaleX = size.width / imageWidth.coerceAtLeast(1).toFloat()
        val scaleY = size.height / imageHeight.coerceAtLeast(1).toFloat()

        detections.forEach { detection ->
            try {
                // Scale bounding box to match the display
                val scaledBox = RectF(
                    detection.boundingBox.left * scaleX,
                    detection.boundingBox.top * scaleY,
                    detection.boundingBox.right * scaleX,
                    detection.boundingBox.bottom * scaleY
                )

                // Ensure box has valid dimensions
                if (scaledBox.width() <= 0 || scaledBox.height() <= 0) {
                    return@forEach // Skip this detection
                }

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

                // Draw text background and text only if there's enough space
                if (scaledBox.top > 20) { // Ensure there's room above the box for text
                    val textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Measure text dimensions
                    val textLayoutResult = textMeasurer.measure(labelText, textStyle)
                    val textWidth = textLayoutResult.size.width.toFloat() + 8f
                    val textHeight = textLayoutResult.size.height.toFloat() + 8f

                    // Draw text background
                    drawRect(
                        color = boxColor.copy(alpha = 0.7f),
                        topLeft = Offset(scaledBox.left, (scaledBox.top - textHeight).coerceAtLeast(0f)),
                        size = Size(textWidth, textHeight)
                    )

                    // Draw label text
                    drawText(
                        textMeasurer = textMeasurer,
                        text = labelText,
                        style = textStyle,
                        topLeft = Offset(
                            scaledBox.left + 4f,
                            (scaledBox.top - textHeight + 4f).coerceAtLeast(4f)
                        )
                    )
                }
            } catch (e: Exception) {
                // Silently catch any drawing errors to prevent crashes
            }
        }
    }
}