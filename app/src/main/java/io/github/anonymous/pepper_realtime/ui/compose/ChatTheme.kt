package io.github.anonymous.pepper_realtime.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Colors matching existing XML theme
object ChatColors {
    val UserBubble = Color(0xFF007AFF)
    val UserBubbleText = Color.White
    val RobotBubble = Color(0xFFE5E5EA)  // Light gray, distinct from background
    val RobotBubbleText = Color.Black
    val Background = Color(0xFFF0F0F0)
    
    // Function call colors
    val FunctionCallBackground = Color(0xFFF8FAFC)
    val FunctionCallTitle = Color(0xFF1E3A8A)
    val FunctionCallSummary = Color(0xFF64748B)
    val FunctionCallStatusSuccess = Color(0xFF10B981)
    val FunctionCallStatusPending = Color(0xFFF59E0B)
    val FunctionCallIcon = Color(0xFF3B82F6)
    val FunctionCallLabel = Color(0xFF374151)
    val FunctionCallCode = Color(0xFF1F2937)
    val CodeBackground = Color(0xFFF8FAFC)
    
    // Primary theme colors - Professional Blue
    val Primary = Color(0xFF1E40AF)
    val PrimaryVariant = Color(0xFF1E3A8A)
}

private val LightColorScheme = lightColorScheme(
    primary = ChatColors.Primary,
    secondary = ChatColors.FunctionCallIcon,
    background = ChatColors.Background,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black
)

@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}

