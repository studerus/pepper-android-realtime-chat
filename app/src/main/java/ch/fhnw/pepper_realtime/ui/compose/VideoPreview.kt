package ch.fhnw.pepper_realtime.ui.compose

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Small camera preview overlay showing the current video stream frame.
 * Positioned in the corner of the screen when video streaming is active.
 *
 * Features:
 * - Semi-transparent, non-intrusive
 * - Shows what the camera sees
 * - Can be minimized by tapping
 * - Recording indicator border
 */
@Composable
fun VideoPreview(
    frame: Bitmap?,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isMinimized by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = isVisible && frame != null && !isMinimized,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 90.dp)
                .shadow(8.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 2.dp,
                    color = Color(0xFFE53935), // Red recording indicator
                    shape = RoundedCornerShape(8.dp)
                )
                .background(Color.Black)
                .clickable { isMinimized = true }
        ) {
            frame?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Camera Preview",
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Recording indicator dot
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFE53935))
            )

            // Close button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Preview",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    // Minimized indicator - small dot to restore preview
    AnimatedVisibility(
        visible = isVisible && isMinimized,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF2E7D32))
                .border(2.dp, Color.White, RoundedCornerShape(50))
                .clickable { isMinimized = false },
            contentAlignment = Alignment.Center
        ) {
            // Pulsing dot for recording indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
            )
        }
    }
}

