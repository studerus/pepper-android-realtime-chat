package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.ui.ChatMessage

/**
 * Composable for rendering user and robot text message bubbles.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.sender == ChatMessage.Sender.USER
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.8f
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isUser) ChatColors.UserBubble else ChatColors.RobotBubble
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.message,
                color = if (isUser) ChatColors.UserBubbleText else ChatColors.RobotBubbleText,
                fontSize = 24.sp,
                textAlign = if (isUser) TextAlign.End else TextAlign.Start
            )
        }
    }
}

/**
 * Composable for rendering AI thinking trace bubbles (Google Live API).
 * Displayed with italic text and a distinct style to differentiate from regular messages.
 */
@Composable
fun ThinkingBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.8f
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF5F5F5))  // Light gray background
                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text(
                text = message.message,
                color = Color(0xFF666666),  // Darker gray text
                fontSize = 20.sp,  // Slightly smaller than regular messages
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Start
            )
        }
    }
}

