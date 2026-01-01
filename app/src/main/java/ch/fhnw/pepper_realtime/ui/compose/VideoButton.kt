package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
 * Visual state for the video button.
 */
enum class VideoVisualState {
    /** Video streaming is active - camera is capturing and sending frames */
    ACTIVE,
    /** Video is enabled but paused (e.g., during robot speech) */
    PAUSED,
    /** Video streaming is off */
    OFF
}

private object VideoButtonColors {
    // Active state - green, filled (streaming)
    val ActiveBackground = Color(0xFF2E7D32) // Green 800
    val ActiveIcon = Color.White
    val ActiveBorder = Color(0xFF1B5E20) // Green 900

    // Paused state - amber, outlined
    val PausedBackground = Color(0xFFFFF8E1) // Amber 50
    val PausedIcon = Color(0xFFF57C00) // Orange 700
    val PausedBorder = Color(0xFFFFB74D) // Orange 300

    // Off state - gray
    val OffBackground = Color(0xFFF5F5F5)
    val OffIcon = Color(0xFF757575)
    val OffBorder = Color(0xFFBDBDBD)
}

/**
 * Video streaming button for toggling continuous camera streaming.
 *
 * Three visual states:
 * 1. ACTIVE: Green filled - camera is streaming 1 FPS to Gemini
 * 2. PAUSED: Amber outlined - streaming paused temporarily
 * 3. OFF: Gray - video streaming disabled
 *
 * Only visible when Google provider is active.
 *
 * @param isStreaming Whether video is currently streaming
 * @param isPaused Whether streaming is paused (e.g., during robot speech)
 * @param onClick Called when button is tapped to toggle video
 */
@Composable
fun VideoButton(
    isStreaming: Boolean,
    isPaused: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visualState = when {
        isStreaming && !isPaused -> VideoVisualState.ACTIVE
        isStreaming && isPaused -> VideoVisualState.PAUSED
        else -> VideoVisualState.OFF
    }

    val backgroundColor by animateColorAsState(
        targetValue = when (visualState) {
            VideoVisualState.ACTIVE -> VideoButtonColors.ActiveBackground
            VideoVisualState.PAUSED -> VideoButtonColors.PausedBackground
            VideoVisualState.OFF -> VideoButtonColors.OffBackground
        },
        animationSpec = tween(200),
        label = "video_bg_color"
    )

    val iconColor by animateColorAsState(
        targetValue = when (visualState) {
            VideoVisualState.ACTIVE -> VideoButtonColors.ActiveIcon
            VideoVisualState.PAUSED -> VideoButtonColors.PausedIcon
            VideoVisualState.OFF -> VideoButtonColors.OffIcon
        },
        animationSpec = tween(200),
        label = "video_icon_color"
    )

    val borderColor by animateColorAsState(
        targetValue = when (visualState) {
            VideoVisualState.ACTIVE -> VideoButtonColors.ActiveBorder
            VideoVisualState.PAUSED -> VideoButtonColors.PausedBorder
            VideoVisualState.OFF -> VideoButtonColors.OffBorder
        },
        animationSpec = tween(200),
        label = "video_border_color"
    )

    // Pulsing animation for active streaming state
    val infiniteTransition = rememberInfiniteTransition(label = "video_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "video_pulse_alpha"
    )

    val icon = if (isStreaming) Icons.Default.Videocam else Icons.Default.VideocamOff

    val contentDescription = when (visualState) {
        VideoVisualState.ACTIVE -> stringResource(R.string.video_button_active)
        VideoVisualState.PAUSED -> stringResource(R.string.video_button_paused)
        VideoVisualState.OFF -> stringResource(R.string.video_button_off)
    }

    Box(
        modifier = modifier
            .size(60.dp) // Match MicrophoneButton size
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(
                if (visualState == VideoVisualState.ACTIVE)
                    backgroundColor.copy(alpha = pulseAlpha)
                else
                    backgroundColor
            )
            .border(2.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(28.dp)
        )
    }
}

