package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import ch.fhnw.pepper_realtime.ui.ChatMessage

/**
 * Main chat screen composable using LazyColumn for efficient list rendering.
 * Replaces the RecyclerView + ChatMessageAdapter implementation.
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    partialSpeechResult: String? = null,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val listState = rememberLazyListState()
    
    // Track last message state to differentiate between new messages, streaming updates, and other changes (like expansion)
    val lastMessage = messages.lastOrNull()
    val lastMessageId = lastMessage?.uuid
    // Combine text content and function args/result to detect any content changes (streaming)
    val lastContentLength = remember(lastMessage) {
        (lastMessage?.message?.length ?: 0) + 
        (lastMessage?.functionArgs?.length ?: 0) + 
        (lastMessage?.functionResult?.length ?: 0)
    }
    
    // Track previous ID to detect NEW messages
    var previousLastMessageId by remember { mutableStateOf<String?>(null) }
    
    // Check if user is currently at the bottom of the list
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf true
            
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf false
            
            val lastVisibleItem = visibleItems.last()
            // Check if last item is visible AND its bottom is close to viewport bottom
            val viewportEnd = layoutInfo.viewportEndOffset
            val itemBottom = lastVisibleItem.offset + lastVisibleItem.size
            
            lastVisibleItem.index == totalItems - 1 && itemBottom <= viewportEnd + 100 // 100px tolerance
        }
    }
    
    // Smart auto-scroll logic
    LaunchedEffect(lastMessageId, lastContentLength, partialSpeechResult) {
        if (messages.isEmpty() && partialSpeechResult == null) return@LaunchedEffect
        
        val isNewMessage = lastMessageId != previousLastMessageId
        val hasPartialResult = partialSpeechResult != null
        
        // Scroll condition:
        // 1. ALWAYS scroll for a NEW message
        // 2. Scroll for UPDATES (streaming) only if we were already at the bottom
        // 3. Scroll if partial result appears/updates
        if (isNewMessage || isAtBottom || hasPartialResult) {
            val lastIndex = messages.size + if (hasPartialResult) 0 else -1
            
            if (lastIndex >= 0) {
                if (isNewMessage || hasPartialResult) {
                    // For new messages or partial result, animate smoothly
                    delay(50) // Allow layout to calculate size
                    listState.animateScrollToItem(lastIndex)
                } else {
                    // For streaming updates (content growing), snap to bottom to keep it visible
                    listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
                }
            }
        }
        
        previousLastMessageId = lastMessageId
    }
    
    ChatTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(ChatColors.Background)
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 8.dp + bottomPadding,
                    start = 0.dp,
                    end = 0.dp
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = messages,
                    key = { message -> message.uuid }
                ) { message ->
                    ChatMessageItem(
                        message = message,
                        onImageClick = onImageClick
                    )
                }
                
                // Render streaming partial result
                if (!partialSpeechResult.isNullOrBlank()) {
                    item {
                        Box(modifier = Modifier.alpha(0.6f)) {
                            ChatMessageItem(
                                message = ChatMessage(partialSpeechResult, ChatMessage.Sender.USER),
                                onImageClick = {}
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders a single chat message based on its type.
 */
@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    onImageClick: (String) -> Unit
) {
    when (message.type) {
        ChatMessage.Type.FUNCTION_CALL -> {
            FunctionCallCard(message = message)
        }
        ChatMessage.Type.EVENT_TRIGGER -> {
            EventTriggerCard(message = message)
        }
        ChatMessage.Type.IMAGE_MESSAGE -> {
            ChatImage(
                message = message,
                onImageClick = onImageClick
            )
        }
        ChatMessage.Type.REGULAR_MESSAGE -> {
            // Only render if there's actual text content
            if (message.message.isNotEmpty()) {
                MessageBubble(message = message)
            }
        }
    }
}

