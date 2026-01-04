package ch.fhnw.pepper_realtime.ui.compose.games

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
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
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.ui.DrawingGameState

private object DrawingColors {
    val Background = Color.White
    val CanvasBorder = Color(0xFFCCCCCC)
    val HeaderBackground = Color(0xFF1F2937)
    val HeaderText = Color.White
    val SentIndicator = Color(0xFF4CAF50)
    val UnsavedIndicator = Color(0xFFFF9800)
    val ClearButton = Color(0xFFFF9800)
    val CloseButton = Color(0xFFF44336)
    
    // Available colors for drawing
    val PaletteColors = listOf(
        Color.Black,
        Color(0xFF424242),      // Dark Gray
        Color(0xFFF44336),      // Red
        Color(0xFFE91E63),      // Pink
        Color(0xFF9C27B0),      // Purple
        Color(0xFF2196F3),      // Blue
        Color(0xFF00BCD4),      // Cyan
        Color(0xFF4CAF50),      // Green
        Color(0xFFFFEB3B),      // Yellow
        Color(0xFFFF9800),      // Orange
        Color(0xFF795548),      // Brown
    )
    
    // Available stroke widths
    val StrokeWidths = listOf(4f, 8f, 14f, 22f)
}

/**
 * Data class to represent a single stroke path
 */
private data class StrokePath(
    val path: Path = Path(),
    val color: Color = Color.Black,
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
    
    // Tool selection state
    var selectedColor by remember { mutableStateOf(DrawingColors.PaletteColors[0]) }
    var selectedStrokeWidth by remember { mutableStateOf(DrawingColors.StrokeWidths[1]) } // Default 8f
    
    // Canvas size for bitmap creation
    var canvasWidth by remember { mutableIntStateOf(0) }
    var canvasHeight by remember { mutableIntStateOf(0) }

    // Fullscreen overlay with semi-transparent background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        // Main card - with top padding to avoid TopAppBar overlap
        Card(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.92f)
                .padding(top = 48.dp) // Space for TopAppBar
                .clickable(enabled = false) {}, // Prevent click-through
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Combined Header with Tools
                DrawingHeaderWithTools(
                    state = state,
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it },
                    selectedStrokeWidth = selectedStrokeWidth,
                    onStrokeWidthSelected = { selectedStrokeWidth = it },
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
                        .padding(12.dp)
                        .background(DrawingColors.CanvasBorder, RoundedCornerShape(8.dp))
                        .padding(2.dp)
                        .background(DrawingColors.Background, RoundedCornerShape(6.dp))
                        .clipToBounds() // Prevent drawing outside bounds
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds() // Also clip the canvas itself
                            .pointerInput(selectedColor, selectedStrokeWidth) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        currentPath = StrokePath(
                                            color = selectedColor,
                                            strokeWidth = selectedStrokeWidth
                                        ).apply {
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
private fun DrawingHeaderWithTools(
    state: DrawingGameState,
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    selectedStrokeWidth: Float,
    onStrokeWidthSelected: (Float) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DrawingColors.HeaderBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title (compact)
        Column {
            Text(
                text = stringResource(R.string.drawing_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = DrawingColors.HeaderText
            )
            state.topic?.let { topic ->
                Text(
                    text = topic,
                    fontSize = 11.sp,
                    color = DrawingColors.HeaderText.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        }
        
        // Color Palette
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            DrawingColors.PaletteColors.forEach { color ->
                ColorButton(
                    color = color,
                    isSelected = color == selectedColor,
                    onClick = { onColorSelected(color) }
                )
            }
        }
        
        // Stroke Width Selector
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawingColors.StrokeWidths.forEach { width ->
                StrokeWidthButton(
                    strokeWidth = width,
                    isSelected = width == selectedStrokeWidth,
                    selectedColor = selectedColor,
                    onClick = { onStrokeWidthSelected(width) }
                )
            }
        }

        // Buttons
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Clear Button (icon only)
            IconButton(
                onClick = onClear,
                modifier = Modifier
                    .size(40.dp)
                    .background(DrawingColors.ClearButton, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.drawing_clear),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Close Button (icon only)
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .background(DrawingColors.CloseButton, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close_button_label),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun ColorButton(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(
                color = color,
                shape = CircleShape
            )
            .then(
                if (isSelected) {
                    Modifier
                        .border(3.dp, Color.White, CircleShape)
                        .border(1.dp, Color.Gray, CircleShape)
                } else {
                    Modifier.border(1.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
                }
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun StrokeWidthButton(
    strokeWidth: Float,
    isSelected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                color = if (isSelected) selectedColor.copy(alpha = 0.2f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, selectedColor, RoundedCornerShape(6.dp))
                } else {
                    Modifier.border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size((strokeWidth / 1.5f).dp)
                .background(selectedColor, CircleShape)
        )
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
        // Convert Compose Color to Android Color
        paint.color = android.graphics.Color.argb(
            (stroke.color.alpha * 255).toInt(),
            (stroke.color.red * 255).toInt(),
            (stroke.color.green * 255).toInt(),
            (stroke.color.blue * 255).toInt()
        )
        paint.strokeWidth = stroke.strokeWidth
        canvas.drawPath(stroke.path.asAndroidPath(), paint)
    }
    
    return bitmap
}

