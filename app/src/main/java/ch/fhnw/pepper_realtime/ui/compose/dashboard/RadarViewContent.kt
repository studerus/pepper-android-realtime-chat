package ch.fhnw.pepper_realtime.ui.compose.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.data.PerceptionData

/**
 * Tab 2: Radar View - Shows detected humans on a radar-style visualization
 */
@Composable
internal fun RadarViewContent(
    humans: List<PerceptionData.HumanInfo>,
    lastUpdate: String
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // Radar visualization (takes full space)
        RadarCanvas(
            humans = humans,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(4.dp)
        )
        
        // Footer overlay at bottom
        Text(
            text = "${humans.size} person(s) • $lastUpdate",
            color = DashboardColors.TextLight,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
        )
    }
}

@Composable
internal fun RadarCanvas(
    humans: List<PerceptionData.HumanInfo>,
    modifier: Modifier = Modifier
) {
    val robotColor = DashboardColors.AccentBlue
    val humanColor = DashboardColors.SuccessGreen
    val unknownHumanColor = Color(0xFF6B7280)
    val gridColor = Color(0xFFE5E7EB)
    
    // Maximum range in meters
    val maxRange = 4.0f
    
    Card(
        colors = CardDefaults.cardColors(containerColor = DashboardColors.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, DashboardColors.BorderColor),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val width = size.width
            val height = size.height
            
            // Robot position: top center (with some margin)
            val robotX = width / 2
            val robotY = 40f
            val robotRadius = 24f
            
            // Calculate scale based on available space
            val usableHeight = height - robotY - 30f
            val pixelsPerMeter = usableHeight / maxRange
            
            // Draw distance arcs (semicircles in front of robot)
            for (distance in 1..4) {
                val radius = distance * pixelsPerMeter
                drawArc(
                    color = gridColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(robotX - radius, robotY - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = 1f)
                )
                
                // Distance label
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#9CA3AF")
                        textSize = 20f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawText("${distance}m", robotX, robotY + radius + 14f, paint)
                }
            }
            
            // Draw center line (forward direction)
            drawLine(
                color = gridColor,
                start = Offset(robotX, robotY),
                end = Offset(robotX, height - 10f),
                strokeWidth = 1f
            )
            
            // Draw left/right indicator lines at 45 degrees
            val lineLength = 3.5f * pixelsPerMeter
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(robotX, robotY),
                end = Offset(robotX - lineLength * 0.7f, robotY + lineLength * 0.7f),
                strokeWidth = 1f
            )
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(robotX, robotY),
                end = Offset(robotX + lineLength * 0.7f, robotY + lineLength * 0.7f),
                strokeWidth = 1f
            )
            
            // Draw the robot (triangle pointing down = forward direction)
            val robotPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(robotX, robotY + robotRadius) // Bottom point (forward)
                lineTo(robotX - robotRadius * 0.8f, robotY - robotRadius * 0.4f) // Top left
                lineTo(robotX + robotRadius * 0.8f, robotY - robotRadius * 0.4f) // Top right
                close()
            }
            drawPath(robotPath, robotColor)
            
            // Draw detected humans
            humans.forEach { human ->
                // Skip if no valid position data
                if (human.positionX == 0.0 && human.positionY == 0.0) return@forEach
                
                // Transform coordinates
                val humanScreenX = robotX + (human.positionY.toFloat() * pixelsPerMeter)
                val humanScreenY = robotY + (human.positionX.toFloat() * pixelsPerMeter)
                
                // Check if within visible bounds
                if (humanScreenY < height - 20 && humanScreenY > robotY && 
                    humanScreenX > 20 && humanScreenX < width - 20) {
                    
                    val isRecognized = human.recognizedName != null
                    val isLooking = human.lookingAtRobot
                    val displayColor = when {
                        isRecognized && isLooking -> humanColor
                        isRecognized -> Color(0xFF10B981) // Lighter green
                        isLooking -> Color(0xFF3B82F6) // Blue for looking but unknown
                        else -> unknownHumanColor
                    }
                    val humanRadius = 10f
                    
                    // Draw human circle
                    drawCircle(
                        color = displayColor,
                        radius = humanRadius,
                        center = Offset(humanScreenX, humanScreenY)
                    )
                    
                    // Draw outer ring for recognized persons or those looking at robot
                    if (isRecognized || isLooking) {
                        drawCircle(
                            color = displayColor,
                            radius = humanRadius + 4f,
                            center = Offset(humanScreenX, humanScreenY),
                            style = Stroke(width = 2f)
                        )
                    }
                    
                    // Draw gaze indicator line if looking at robot
                    if (isLooking) {
                        val lineEndX = robotX
                        val lineEndY = robotY
                        drawLine(
                            color = displayColor.copy(alpha = 0.4f),
                            start = Offset(humanScreenX, humanScreenY),
                            end = Offset(lineEndX, lineEndY),
                            strokeWidth = 2f
                        )
                    }
                    
                    // Draw name or distance label
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            color = when {
                                isRecognized -> android.graphics.Color.parseColor("#059669")
                                isLooking -> android.graphics.Color.parseColor("#3B82F6")
                                else -> android.graphics.Color.parseColor("#4B5563")
                            }
                            textSize = 22f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = isRecognized
                        }
                        val label = human.recognizedName ?: "Track ${human.trackId}"
                        drawText(label, humanScreenX, humanScreenY + humanRadius + 16f, paint)
                        
                        // Draw gaze emoji indicator above the person
                        if (isLooking) {
                            val gazePaint = android.graphics.Paint().apply {
                                textSize = 18f
                                textAlign = android.graphics.Paint.Align.CENTER
                            }
                            drawText("👀", humanScreenX, humanScreenY - humanRadius - 6f, gazePaint)
                        }
                    }
                }
            }
        }
    }
}
