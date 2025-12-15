package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.ui.ChatMessage

private object EventColors {
    val Background = Color(0xFFFFF3E0)  // Warm orange background
    val Title = Color(0xFFE65100)       // Deep orange
    val Icon = Color(0xFFF57C00)        // Orange
    val Summary = Color(0xFF795548)     // Brown
    val Label = Color(0xFF6D4C41)       // Dark brown
    val Code = Color(0xFF4E342E)        // Very dark brown
    val CodeBackground = Color(0xFFFFE0B2) // Light orange
}

/**
 * Composable for rendering event trigger messages with expandable details.
 */
@Composable
fun EventTriggerCard(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(message.isExpanded) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "expand_rotation"
    )
    
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxCardWidth = screenWidth * 0.6f
    
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
            colors = CardDefaults.cardColors(containerColor = EventColors.Background)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Event icon
                    Text(
                        text = getEventIcon(message.eventType),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    
                    // Rule name
                    Text(
                        text = message.eventRuleName ?: "Event Trigger",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = EventColors.Title,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // Action type badge
                    Text(
                        text = getActionIcon(message.eventActionType),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    
                    // Expand icon
                    Icon(
                        painter = painterResource(id = R.drawable.ic_expand_more),
                        contentDescription = stringResource(R.string.content_desc_expand),
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(rotationAngle),
                        tint = EventColors.Icon
                    )
                }
                
                // Summary
                Row(
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = generateEventSummary(message),
                        fontSize = 12.sp,
                        color = EventColors.Summary
                    )
                }
                
                // Expandable details
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        // Event type
                        Text(
                            text = "Event:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = EventColors.Label
                        )
                        Text(
                            text = formatEventType(message.eventType),
                            fontSize = 11.sp,
                            color = EventColors.Summary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        
                        // Action type
                        Text(
                            text = "Action:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = EventColors.Label
                        )
                        Text(
                            text = formatActionType(message.eventActionType),
                            fontSize = 11.sp,
                            color = EventColors.Summary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        
                        // Text sent to AI
                        Text(
                            text = "Message sent to AI:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = EventColors.Label
                        )
                        
                        Text(
                            text = message.eventResolvedText ?: "—",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = EventColors.Code,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                                .background(
                                    EventColors.CodeBackground,
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

private fun getEventIcon(eventType: String?): String {
    return when (eventType) {
        "PERSON_RECOGNIZED" -> "👤"
        "PERSON_APPEARED" -> "👋"
        "PERSON_DISAPPEARED" -> "🚶"
        "PERSON_LOOKING" -> "👀"
        "PERSON_STOPPED_LOOKING" -> "🙈"
        "PERSON_APPROACHED_CLOSE" -> "🤝"
        "PERSON_APPROACHED_INTERACTION" -> "📍"
        else -> "⚡"
    }
}

private fun getActionIcon(actionType: String?): String {
    return when (actionType) {
        "INTERRUPT_AND_RESPOND" -> "🔔"
        "APPEND_AND_RESPOND" -> "💬"
        "SILENT_UPDATE" -> "🔇"
        else -> "📤"
    }
}

private fun formatEventType(eventType: String?): String {
    return when (eventType) {
        "PERSON_RECOGNIZED" -> "Person Recognized"
        "PERSON_APPEARED" -> "Person Appeared"
        "PERSON_DISAPPEARED" -> "Person Disappeared"
        "PERSON_LOOKING" -> "Person Looking at Robot"
        "PERSON_STOPPED_LOOKING" -> "Person Stopped Looking"
        "PERSON_APPROACHED_CLOSE" -> "Person Approached Close (< 1.5m)"
        "PERSON_APPROACHED_INTERACTION" -> "Person Approached Interaction Range (< 3m)"
        else -> eventType ?: "Unknown"
    }
}

private fun formatActionType(actionType: String?): String {
    return when (actionType) {
        "INTERRUPT_AND_RESPOND" -> "Interrupt & Respond"
        "APPEND_AND_RESPOND" -> "Append & Respond"
        "SILENT_UPDATE" -> "Silent Update"
        else -> actionType ?: "Unknown"
    }
}

private fun generateEventSummary(message: ChatMessage): String {
    val personPart = message.eventPersonName?.let { "\"$it\"" } ?: "person"
    
    return when (message.eventType) {
        "PERSON_RECOGNIZED" -> "Recognized $personPart"
        "PERSON_APPEARED" -> "New $personPart detected"
        "PERSON_DISAPPEARED" -> "$personPart left"
        "PERSON_LOOKING" -> "$personPart is looking"
        "PERSON_STOPPED_LOOKING" -> "$personPart looked away"
        "PERSON_APPROACHED_CLOSE" -> "$personPart came close"
        "PERSON_APPROACHED_INTERACTION" -> "$personPart in range"
        else -> "Event triggered"
    }
}

