package io.github.anonymous.pepper_realtime.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.ui.ChatMessage

/**
 * Composable for rendering function call messages with expandable details.
 */
@Composable
fun FunctionCallCard(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(message.isExpanded) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "expand_rotation"
    )
    
    val hasResult = message.functionResult != null
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxCardWidth = screenWidth * 0.6f  // Max 60% of screen width
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = maxCardWidth)
                .clickable {
                    isExpanded = !isExpanded
                    message.isExpanded = isExpanded
                },
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = ChatColors.FunctionCallBackground)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Function icon
                    Text(
                        text = getFunctionIcon(message.functionName),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    
                    // Function name
                    Text(
                        text = getFunctionDisplayName(message.functionName),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = ChatColors.FunctionCallTitle,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // Status
                    Text(
                        text = if (hasResult) "✅" else "⏳",
                        fontSize = 12.sp,
                        color = if (hasResult) ChatColors.FunctionCallStatusSuccess else ChatColors.FunctionCallStatusPending,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    
                    // Expand icon
                    Icon(
                        painter = painterResource(id = R.drawable.ic_expand_more),
                        contentDescription = stringResource(R.string.content_desc_expand),
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(rotationAngle),
                        tint = ChatColors.FunctionCallIcon
                    )
                }
                
                // Summary
                Text(
                    text = generateSummary(message.functionName, hasResult),
                    fontSize = 12.sp,
                    color = ChatColors.FunctionCallSummary,
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp)
                )
                
                // Expandable details
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        // Arguments
                        Text(
                            text = "Arguments:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = ChatColors.FunctionCallLabel
                        )
                        
                        Text(
                            text = formatJson(message.functionArgs),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = ChatColors.FunctionCallCode,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 6.dp)
                                .background(
                                    ChatColors.CodeBackground,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(6.dp)
                        )
                        
                        // Result (if available)
                        if (hasResult) {
                            Text(
                                text = "Result:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = ChatColors.FunctionCallLabel
                            )
                            
                            Text(
                                text = formatJson(message.functionResult),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = ChatColors.FunctionCallCode,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp)
                                    .background(
                                        ChatColors.CodeBackground,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getFunctionIcon(functionName: String?): String {
    return when (functionName) {
        "search_internet" -> "🌐"
        "get_weather" -> "🌤️"
        "analyze_vision" -> "👁️"
        "play_animation" -> "🤖"
        "get_current_datetime" -> "🕐"
        "get_random_joke" -> "😄"
        "present_quiz_question" -> "❓"
        "start_memory_game" -> "🧠"
        else -> "🔧"
    }
}

private fun getFunctionDisplayName(functionName: String?): String {
    return when (functionName) {
        "search_internet" -> "Internet Search"
        "get_weather" -> "Weather"
        "analyze_vision" -> "Vision Analysis"
        "play_animation" -> "Animation"
        "get_current_datetime" -> "Date/Time"
        "get_random_joke" -> "Random Joke"
        "present_quiz_question" -> "Quiz Question"
        "start_memory_game" -> "Memory Game"
        else -> functionName?.replace("_", " ") ?: "Unknown"
    }
}

private fun generateSummary(functionName: String?, hasResult: Boolean): String {
    return when (functionName) {
        "search_internet" -> if (hasResult) "Internet search completed" else "Searching internet..."
        "get_weather" -> if (hasResult) "Weather information retrieved" else "Getting weather..."
        "analyze_vision" -> if (hasResult) "Image analysis completed" else "Analyzing image..."
        "play_animation" -> if (hasResult) "Animation played" else "Playing animation..."
        else -> if (hasResult) "Function completed" else "Function executing..."
    }
}

private fun formatJson(json: String?): String {
    if (json.isNullOrEmpty()) return ""
    
    return json.replace(",", ",\n")
        .replace("{", "{\n  ")
        .replace("}", "\n}")
        .replace("\":", "\": ")
}

