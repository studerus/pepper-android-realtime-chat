package io.github.anonymous.pepper_realtime.ui.compose

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import io.github.anonymous.pepper_realtime.ui.ChatMessage

/**
 * Main chat screen composable using LazyColumn for efficient list rendering.
 * Replaces the RecyclerView + ChatMessageAdapter implementation.
 */
@Composable
fun ChatScreen(
    messagesLiveData: LiveData<List<ChatMessage>>,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by messagesLiveData.observeAsState(emptyList())
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    ChatTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(ChatColors.Background)
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
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

