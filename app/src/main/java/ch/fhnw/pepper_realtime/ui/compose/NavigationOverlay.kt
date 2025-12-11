package ch.fhnw.pepper_realtime.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.data.MapGraphInfo
import ch.fhnw.pepper_realtime.data.SavedLocation
import ch.fhnw.pepper_realtime.ui.MapState
import ch.fhnw.pepper_realtime.ui.NavigationUiState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NavigationOverlay(
    state: NavigationUiState,
    onClose: () -> Unit
) {
    if (!state.isVisible) return

    Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp) // Fixed height for the map view
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color(0xFF1F2937)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.nav_overlay_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFF1F2937)
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.content_desc_close),
                            tint = Color(0xFF1F2937)
                        )
                    }
                }

                // Status Info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    StatusRow(stringResource(R.string.nav_overlay_map_label), state.mapStatus)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusRow(stringResource(R.string.nav_overlay_localization_label), state.localizationStatus)
                }

                // Map Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp)
                        .background(Color.DarkGray, RoundedCornerShape(8.dp))
                        .padding(2.dp) // Border effect
                ) {
                    MapCanvas(
                        mapBitmap = state.mapBitmap,
                        mapGfx = state.mapGfx,
                        locations = state.savedLocations,
                        mapState = state.mapState
                    )
                }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

@Composable
fun MapCanvas(
    mapBitmap: Bitmap?,
    mapGfx: MapGraphInfo?,
    locations: List<SavedLocation>,
    mapState: MapState
) {
    val textMeasurer = rememberTextMeasurer()
    
    // Resolve strings outside DrawScope
    val noMapText = stringResource(R.string.nav_status_no_map)
    val localizingText = stringResource(R.string.nav_status_localizing)
    val locFailedText = stringResource(R.string.nav_status_localization_failed)
    val mapLoadedText = stringResource(R.string.nav_status_map_loaded_not_localized)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // 1. Draw Map Bitmap
        if (mapBitmap != null) {
            val imageBitmap = mapBitmap.asImageBitmap()
            // Scale bitmap to fit canvas while maintaining aspect ratio
            val scaleX = canvasWidth / imageBitmap.width
            val scaleY = canvasHeight / imageBitmap.height
            val scale = minOf(scaleX, scaleY)

            val drawWidth = imageBitmap.width * scale
            val drawHeight = imageBitmap.height * scale
            
            val offsetX = (canvasWidth - drawWidth) / 2
            val offsetY = (canvasHeight - drawHeight) / 2

            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                dstSize = IntSize(drawWidth.toInt(), drawHeight.toInt())
            )

            // 2. Draw Locations (only if we have map info)
            if (mapGfx != null) {
                locations.forEach { loc ->
                    val xMap = loc.translation[0].toFloat()
                    val yMap = loc.translation[1].toFloat()

                    val mapScale = mapGfx.scale
                    val theta = mapGfx.theta
                    val xOrigin = mapGfx.x
                    val yOrigin = mapGfx.y

                    // Convert map (meters) to bitmap pixel coordinates
                    // Formula from original MapPreviewView.kt (pre-Compose)
                    val xImgRaw = (1f / mapScale * (cos(theta.toDouble()).toFloat() * (xMap - xOrigin) + sin(theta.toDouble()).toFloat() * (yMap - yOrigin)))
                    val yImgRaw = (1f / mapScale * (sin(theta.toDouble()).toFloat() * (xMap - xOrigin) - cos(theta.toDouble()).toFloat() * (yMap - yOrigin)))

                    // Transform bitmap pixel coordinates to Canvas coordinates
                    val xCanvas = offsetX + (xImgRaw * scale)
                    val yCanvas = offsetY + (yImgRaw * scale)

                    // Draw Point
                    drawCircle(
                        color = Color.Cyan,
                        radius = 6.dp.toPx(),
                        center = Offset(xCanvas, yCanvas)
                    )

                    // Draw Label
                    val textLayoutResult = textMeasurer.measure(
                        text = loc.name, // Use name instead of label
                        style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    )
                    
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(xCanvas + 10f, yCanvas - 10f)
                    )
                }
            }

        } else {
            // No Map
            drawRect(color = Color.DarkGray)
            val textResult = textMeasurer.measure(noMapText, TextStyle(color = Color.White))
            drawText(
                textResult,
                topLeft = Offset(
                    (canvasWidth - textResult.size.width) / 2,
                    (canvasHeight - textResult.size.height) / 2
                )
            )
        }
        
        // Draw Status Overlay on Canvas if needed
        if (mapState != MapState.LOCALIZED && mapState != MapState.NO_MAP) {
            val statusText = when (mapState) {
                MapState.LOCALIZING -> localizingText
                MapState.LOCALIZATION_FAILED -> locFailedText
                MapState.MAP_LOADED_NOT_LOCALIZED -> mapLoadedText
                else -> ""
            }
            if (statusText.isNotEmpty()) {
                 val textResult = textMeasurer.measure(
                    text = statusText,
                    style = TextStyle(color = Color.Yellow, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                )
                 drawText(
                    textResult,
                    topLeft = Offset(
                        (canvasWidth - textResult.size.width) / 2,
                        (canvasHeight - 50.dp.toPx()) // Bottom center
                    )
                )
            }
        }
    }
}
