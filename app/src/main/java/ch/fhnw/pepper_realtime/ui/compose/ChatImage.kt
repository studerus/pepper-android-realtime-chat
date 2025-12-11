package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.ui.ChatMessage
import java.io.File

/**
 * Composable for rendering image messages using Coil.
 */
@Composable
fun ChatImage(
    message: ChatMessage,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.8f
    val imagePath = message.imagePath
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Text message (if any)
        if (message.message.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ChatColors.RobotBubble)
                    .padding(12.dp)
            ) {
                Text(
                    text = message.message,
                    color = ChatColors.RobotBubbleText,
                    fontSize = 18.sp
                )
            }
        }
        
        // Image (if path exists and file is valid)
        if (imagePath != null && File(imagePath).exists()) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = stringResource(R.string.content_desc_chat_image),
                modifier = Modifier
                    .padding(top = if (message.message.isNotEmpty()) 6.dp else 0.dp)
                    .size(240.dp) // Slightly larger
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(12.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onImageClick(imagePath) },
                contentScale = ContentScale.Crop // Crop usually looks better for thumbnails than Fit
            )
        }
    }
}

