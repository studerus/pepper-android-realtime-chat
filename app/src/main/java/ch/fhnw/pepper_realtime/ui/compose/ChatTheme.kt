package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Colors matching existing XML theme
object ChatColors {
    // Harmonized Clean Tech Colors
    val UserBubble = Color(0xFF1E40AF) // professional_blue
    val UserBubbleText = Color.White
    val RobotBubble = Color(0xFFE5E5EA) // Light Gray (iOS style) for contrast
    val RobotBubbleText = Color(0xFF1F2937) // Dark Grey
    val Background = Color(0xFFF3F4F6) // Cool Gray 100
    
    // Function call colors
    val FunctionCallBackground = Color(0xFFFFFFFF) // White cards
    val FunctionCallTitle = Color(0xFF1E3A8A)
    val FunctionCallSummary = Color(0xFF64748B)
    val FunctionCallStatusSuccess = Color(0xFF10B981)
    val FunctionCallStatusPending = Color(0xFFF59E0B)
    val FunctionCallIcon = Color(0xFF3B82F6)
    val FunctionCallLabel = Color(0xFF374151)
    val FunctionCallCode = Color(0xFF1F2937)
    val CodeBackground = Color(0xFFF3F4F6) // Matches background
    
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

