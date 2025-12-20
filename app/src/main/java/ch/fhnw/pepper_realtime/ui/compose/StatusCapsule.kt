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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Status capsule showing the robot's current state.
 * 
 * This component only shows robot status (Listening, Thinking, Speaking).
 * Mute control is handled by the separate MicrophoneButton.
 * 
 * Tap behavior:
 * - During SPEAKING: Interrupts the robot (stops speech)
 * - Other states: No action
 * 
 * @param statusText The current status text to display
 * @param isSpeaking True when robot is speaking (shows stop icon)
 * @param isListening True when robot is listening (blue style)
 * @param onClick Called when tapped (only active during speaking)
 */
@Composable
fun StatusCapsule(
    statusText: String,
    isSpeaking: Boolean,
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Only show if there is meaningful status
    val isVisible = statusText.isNotEmpty()
    
    // Determine container color based on state
    val containerColor = when {
        isSpeaking -> Color(0xFFFFF3E0) // Light orange for speaking (interruptible)
        isListening -> Color(0xFFE3F2FD) // Light blue for listening
        else -> Color.White // Neutral for thinking/other
    }
    
    val contentColor = when {
        isSpeaking -> Color(0xFFE65100) // Orange text for speaking
        isListening -> ChatColors.Primary // Blue text for listening
        else -> ChatColors.RobotBubbleText // Gray for other states
    }
    
    val borderColor = when {
        isSpeaking -> Color(0xFFFFCC80) // Orange border for speaking
        isListening -> ChatColors.Primary.copy(alpha = 0.5f)
        else -> Color(0xFFE0E0E0)
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 250.dp) // Reverted to original width
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(40.dp))
                .clip(RoundedCornerShape(40.dp))
                .background(containerColor)
                .border(1.dp, borderColor, RoundedCornerShape(40.dp))
                .clickable(enabled = isSpeaking, onClick = onClick)
                .padding(horizontal = 32.dp, vertical = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Icon removed as "Tap to interrupt" is explicit text
                
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
