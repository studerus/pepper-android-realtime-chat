package ch.fhnw.pepper_realtime.ui.compose.games

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.ui.DrawingGameState

private object DrawingColors {
    val Background = Color.White
    val CanvasBorder = Color(0xFFCCCCCC)
    val StrokeColor = Color.Black
    val HeaderBackground = Color(0xFFF5F5F5)
    val SentIndicator = Color(0xFF4CAF50)
    val UnsavedIndicator = Color(0xFFFF9800)
}

/**
 * Data class to represent a single stroke path
 */
private data class StrokePath(
    val path: Path = Path(),
    val color: Color = DrawingColors.StrokeColor,
    val strokeWidth: Float = 8f
)

/**
 * Drawing Canvas Dialog for the drawing game.
 * Fullscreen dialog with touch-based drawing canvas.
 */
@Composable
fun DrawingCanvasDialog(
    state: DrawingGameState,
    onDrawingChanged: (Bitmap) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    // Drawing state
    var paths by remember { mutableStateOf(listOf<StrokePath>()) }
    var currentPath by remember { mutableStateOf<StrokePath?>(null) }
    
    // Canvas size for bitmap creation
    var canvasWidth by remember { mutableIntStateOf(0) }
    var canvasHeight by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DrawingColors.HeaderBackground
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                DrawingHeader(
                    state = state,
                    onClear = {
                        paths = emptyList()
                        currentPath = null
                        onClear()
                    },
                    onDismiss = onDismiss
                )

                // Canvas Area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .background(DrawingColors.Background, RoundedCornerShape(8.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        currentPath = StrokePath().apply {
                                            path.moveTo(offset.x, offset.y)
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentPath?.let { stroke ->
                                            val newPath = Path().apply {
                                                addPath(stroke.path)
                                                lineTo(change.position.x, change.position.y)
                                            }
                                            currentPath = stroke.copy(path = newPath)
                                        }
                                    },
                                    onDragEnd = {
                                        currentPath?.let { stroke ->
                                            paths = paths + stroke
                                            currentPath = null
                                            
                                            // Create bitmap and notify
                                            if (canvasWidth > 0 && canvasHeight > 0) {
                                                val bitmap = createBitmapFromPaths(
                                                    paths,
                                                    canvasWidth,
                                                    canvasHeight
                                                )
                                                onDrawingChanged(bitmap)
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        currentPath = null
                                    }
                                )
                            }
                    ) {
                        // Store canvas dimensions
                        canvasWidth = size.width.toInt()
                        canvasHeight = size.height.toInt()

                        // Draw completed paths
                        paths.forEach { stroke ->
                            drawPath(
                                path = stroke.path,
                                color = stroke.color,
                                style = Stroke(
                                    width = stroke.strokeWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }

                        // Draw current path
                        currentPath?.let { stroke ->
                            drawPath(
                                path = stroke.path,
                                color = stroke.color,
                                style = Stroke(
                                    width = stroke.strokeWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    // Status indicator
                    StatusIndicator(
                        hasUnsavedChanges = state.hasUnsavedChanges,
                        lastSentTimestamp = state.lastSentTimestamp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawingHeader(
    state: DrawingGameState,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DrawingColors.HeaderBackground)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Title and topic
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.drawing_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            state.topic?.let { topic ->
                Text(
                    text = topic,
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        }

        // Buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Clear Button
            OutlinedButton(onClick = onClear) {
                Text(stringResource(R.string.drawing_clear))
            }

            // Close Button
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text(stringResource(R.string.close_button_label))
            }
        }
    }
}

@Composable
private fun StatusIndicator(
    hasUnsavedChanges: Boolean,
    lastSentTimestamp: Long,
    modifier: Modifier = Modifier
) {
    val statusText: String
    val statusColor: Color

    when {
        hasUnsavedChanges -> {
            statusText = stringResource(R.string.drawing_status_drawing)
            statusColor = DrawingColors.UnsavedIndicator
        }
        lastSentTimestamp > 0 -> {
            statusText = stringResource(R.string.drawing_status_sent)
            statusColor = DrawingColors.SentIndicator
        }
        else -> {
            statusText = stringResource(R.string.drawing_status_ready)
            statusColor = Color.Gray
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = statusColor.copy(alpha = 0.2f)
    ) {
        Text(
            text = statusText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = statusColor,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Creates a bitmap from the drawn paths.
 */
private fun createBitmapFromPaths(
    paths: List<StrokePath>,
    width: Int,
    height: Int
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    
    // Fill with white background
    canvas.drawColor(android.graphics.Color.WHITE)
    
    // Draw all paths
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    
    paths.forEach { stroke ->
        paint.color = android.graphics.Color.BLACK
        paint.strokeWidth = stroke.strokeWidth
        canvas.drawPath(stroke.path.asAndroidPath(), paint)
    }
    
    return bitmap
}

