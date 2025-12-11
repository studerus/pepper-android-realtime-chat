package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.R

@Composable
fun StatusCapsule(
    statusText: String,
    isMuted: Boolean,
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Only show if there is meaningful status or if muted
    val isVisible = isMuted || isListening || statusText.isNotEmpty()
    
    // Determine container color based on state
    val containerColor = when {
        isMuted -> Color(0xFFFFEBEE) // Light Red for muted
        isListening -> Color(0xFFE3F2FD) // Light Blue for listening
        else -> Color.White
    }
    
    val contentColor = when {
        isMuted -> Color(0xFFD32F2F) // Red text
        isListening -> ChatColors.Primary // Blue text
        else -> ChatColors.RobotBubbleText
    }
    
    val borderColor = when {
        isMuted -> Color(0xFFEF9A9A)
        isListening -> ChatColors.Primary.copy(alpha = 0.5f)
        else -> Color(0xFFE0E0E0)
    }

    // Icon logic
    val icon = when {
        isMuted -> Icons.Default.MicOff
        isListening -> Icons.Default.Mic
        else -> null // No icon for generic status messages unless we want one
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.padding(bottom = 40.dp) // Lift from bottom
    ) {
        Box(
            modifier = Modifier
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(40.dp))
                .clip(RoundedCornerShape(40.dp))
                .background(containerColor)
                .border(1.dp, borderColor, RoundedCornerShape(40.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 32.dp, vertical = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                
                Text(
                    text = statusText,
                    color = contentColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
