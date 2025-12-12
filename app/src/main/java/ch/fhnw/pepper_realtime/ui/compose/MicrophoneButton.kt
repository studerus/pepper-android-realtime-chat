package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.fhnw.pepper_realtime.R

/**
 * Microphone state for visual representation.
 * This is separate from the technical microphone state.
 */
enum class MicrophoneVisualState {
    /** Microphone is active and listening (LISTENING state + user wants mic on) */
    ACTIVE,
    /** Microphone is paused but will activate when robot is done (SPEAKING/THINKING + user wants mic on) */
    PAUSED,
    /** User has muted the microphone (user wants mic off) */
    MUTED
}

private object MicButtonColors {
    // Active state - blue, filled
    val ActiveBackground = Color(0xFF1976D2)
    val ActiveIcon = Color.White
    val ActiveBorder = Color(0xFF1565C0)
    
    // Paused state - gray, outlined
    val PausedBackground = Color(0xFFF5F5F5)
    val PausedIcon = Color(0xFF757575)
    val PausedBorder = Color(0xFFBDBDBD)
    
    // Muted state - red, with slash
    val MutedBackground = Color(0xFFFFEBEE)
    val MutedIcon = Color(0xFFD32F2F)
    val MutedBorder = Color(0xFFEF9A9A)
}

/**
 * Dedicated microphone button for mute/unmute control.
 * 
 * Three visual states:
 * 1. ACTIVE: Blue filled - microphone is on and listening
 * 2. PAUSED: Gray outlined - microphone will activate when robot finishes
 * 3. MUTED: Red with slash - microphone is off
 * 
 * @param userWantsMicOn User's desired microphone state
 * @param isRobotSpeakingOrThinking True when robot is in SPEAKING or THINKING state
 * @param onClick Called when button is tapped to toggle mute intent
 */
@Composable
fun MicrophoneButton(
    userWantsMicOn: Boolean,
    isRobotSpeakingOrThinking: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visualState = when {
        !userWantsMicOn -> MicrophoneVisualState.MUTED
        isRobotSpeakingOrThinking -> MicrophoneVisualState.PAUSED
        else -> MicrophoneVisualState.ACTIVE
    }
    
    val backgroundColor by animateColorAsState(
        targetValue = when (visualState) {
            MicrophoneVisualState.ACTIVE -> MicButtonColors.ActiveBackground
            MicrophoneVisualState.PAUSED -> MicButtonColors.PausedBackground
            MicrophoneVisualState.MUTED -> MicButtonColors.MutedBackground
        },
        animationSpec = tween(200),
        label = "mic_bg_color"
    )
    
    val iconColor by animateColorAsState(
        targetValue = when (visualState) {
            MicrophoneVisualState.ACTIVE -> MicButtonColors.ActiveIcon
            MicrophoneVisualState.PAUSED -> MicButtonColors.PausedIcon
            MicrophoneVisualState.MUTED -> MicButtonColors.MutedIcon
        },
        animationSpec = tween(200),
        label = "mic_icon_color"
    )
    
    val borderColor by animateColorAsState(
        targetValue = when (visualState) {
            MicrophoneVisualState.ACTIVE -> MicButtonColors.ActiveBorder
            MicrophoneVisualState.PAUSED -> MicButtonColors.PausedBorder
            MicrophoneVisualState.MUTED -> MicButtonColors.MutedBorder
        },
        animationSpec = tween(200),
        label = "mic_border_color"
    )
    
    val icon = if (userWantsMicOn) Icons.Default.Mic else Icons.Default.MicOff
    
    val contentDescription = when (visualState) {
        MicrophoneVisualState.ACTIVE -> stringResource(R.string.mic_button_active)
        MicrophoneVisualState.PAUSED -> stringResource(R.string.mic_button_paused)
        MicrophoneVisualState.MUTED -> stringResource(R.string.mic_button_muted)
    }
    
    Box(
        modifier = modifier
            .size(48.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(2.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

