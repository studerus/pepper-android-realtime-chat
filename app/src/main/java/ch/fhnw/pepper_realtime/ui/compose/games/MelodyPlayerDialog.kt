package ch.fhnw.pepper_realtime.ui.compose.games

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.ui.MelodyPlayerState

private object MelodyColors {
    val CardBackground = Color(0xFF16213E)
    val AccentPrimary = Color(0xFFE94560)
    val AccentSecondary = Color(0xFF0F3460)
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFFB0B0B0)
    val NoteHighlight = Color(0xFFFFD700)
}

/**
 * Melody Player Dialog with animated music visualization.
 * Shows while a melody is playing and allows cancellation.
 */
@Composable
fun MelodyPlayerDialog(
    state: MelodyPlayerState,
    onDismiss: () -> Unit
) {
    // Fullscreen overlay with centered card to preserve immersive mode
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentHeight()
                .clickable(enabled = false) {}, // Prevent click-through
            shape = RoundedCornerShape(24.dp),
            color = MelodyColors.CardBackground
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "🎵 Playing Melody",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MelodyColors.TextPrimary
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Animated Music Visualization
                MusicVisualization(
                    isPlaying = state.isPlaying,
                    currentNote = state.currentNote
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Current Note Display
                if (state.currentNote.isNotEmpty()) {
                    Text(
                        text = state.currentNote,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodyColors.NoteHighlight
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Progress Bar
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MelodyColors.AccentPrimary,
                    trackColor = MelodyColors.AccentSecondary,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    fontSize = 14.sp,
                    color = MelodyColors.TextSecondary
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Stop Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MelodyColors.AccentPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stop",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicVisualization(
    isPlaying: Boolean,
    currentNote: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "music_animation")
    
    // Create multiple animated bars
    val bars = listOf(
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(300, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar1"
        ),
        infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar2"
        ),
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(350, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar3"
        ),
        infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(250, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar4"
        ),
        infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(320, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar5"
        )
    )
    
    // Pulsing circle behind the bars
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    Box(
        modifier = Modifier
            .size(120.dp),
        contentAlignment = Alignment.Center
    ) {
        // Pulsing background circle
        if (isPlaying) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(pulseScale)
                    .background(
                        MelodyColors.AccentPrimary.copy(alpha = 0.2f),
                        CircleShape
                    )
            )
        }
        
        // Audio bars visualization
        Row(
            modifier = Modifier.height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            bars.forEach { animatedValue ->
                val height = if (isPlaying) animatedValue.value else 0.3f
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .fillMaxHeight(height)
                        .background(
                            MelodyColors.AccentPrimary,
                            RoundedCornerShape(6.dp)
                        )
                )
            }
        }
    }
}

