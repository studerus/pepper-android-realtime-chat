package ch.fhnw.pepper_realtime.ui.compose.games

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.tools.games.MemoryCard
import ch.fhnw.pepper_realtime.ui.MemoryGameInternalState

private object MemoryColors {
    val CardFaceDown = Color(0xFF1E40AF) // Professional Blue
    val CardFaceUp = Color.White
    val TextOnBlue = Color.White
    val TextOnWhite = Color.Black
    val Background = Color(0xFFF5F5F5)
}

/**
 * Memory Game rendered as a fullscreen overlay (not Dialog) to preserve immersive mode.
 */
@Composable
fun MemoryGameDialog(
    state: MemoryGameInternalState,
    onCardClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Fullscreen overlay instead of Dialog to preserve immersive mode
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MemoryColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            MemoryGameHeader(state, onDismiss)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Grid
            Box(modifier = Modifier.weight(1f)) {
                MemoryGameGrid(
                    cards = state.cards,
                    onCardClick = onCardClick
                )
            }
        }
    }
}

@Composable
private fun MemoryGameHeader(
    state: MemoryGameInternalState,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Stats
        Column {
            Text(
                text = stringResource(R.string.memory_time_label, state.timeString),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.memory_moves_format, state.moves),
                    fontSize = 16.sp
                )
                Text(
                    text = stringResource(R.string.memory_pairs_format, state.matchedPairs, state.totalPairs),
                    fontSize = 16.sp
                )
            }
        }
        
        // Close Button
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) {
            Text(stringResource(R.string.close_button_label))
        }
    }
}

@Composable
private fun MemoryGameGrid(
    cards: List<MemoryCard>,
    onCardClick: (Int) -> Unit
) {
    // Dynamic columns/rows based on count
    val (columns, rows) = when {
        cards.size <= 8 -> 4 to 2 // Easy (4 pairs = 8 cards) -> 4x2
        cards.size <= 16 -> 4 to 4 // Medium (8 pairs = 16 cards) -> 4x4
        else -> 6 to 4 // Hard (12 pairs = 24 cards) -> 6x4
    }

    @Suppress("UnusedBoxWithConstraintsScope") // maxHeight is used below
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val spacing = 8.dp
        // Calculate cell height to fit available space
        // total height = rows * cellHeight + (rows - 1) * spacing
        // cellHeight = (total height - spacing) / rows
        val totalVerticalSpacing = spacing * (rows - 1)
        val availableHeight = maxHeight - totalVerticalSpacing
        val cellHeight = availableHeight / rows
        
        // Ensure minimum height
        val actualCellHeight = maxOf(60.dp, cellHeight)

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            userScrollEnabled = false, // Try to prevent scrolling by fitting content
            modifier = Modifier.fillMaxSize()
        ) {
            items(cards) { card ->
                MemoryCardView(
                    card = card, 
                    onClick = { onCardClick(card.id) },
                    modifier = Modifier.height(actualCellHeight)
                )
            }
        }
    }
}

@Composable
private fun MemoryCardView(
    card: MemoryCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (card.isFlipped || card.isMatched) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "cardFlip"
    )

    val isFaceUp = rotation >= 90f

    // Calculate dynamic font size based on card size
    @Suppress("UnusedBoxWithConstraintsScope") // maxWidth/maxHeight used for fontSize
    BoxWithConstraints(
        modifier = modifier
            .padding(4.dp) // More padding for shadow
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
            .shadow(
                elevation = 6.dp, 
                shape = RoundedCornerShape(8.dp),
                clip = false
            )
            .background(
                color = if (isFaceUp || card.isMatched) MemoryColors.CardFaceUp else MemoryColors.CardFaceDown,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(enabled = !card.isMatched && !card.isFlipped) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // Calculate font size: ~50% of the smaller dimension (width or height)
        val minDim = minOf(maxWidth, maxHeight)
        val fontSize = (minDim.value * 0.5f).sp

        if (isFaceUp) {
            // Face Up Content (flipped back for correct orientation)
            Box(modifier = Modifier.graphicsLayer { rotationY = 180f }) {
                Text(
                    text = card.symbol,
                    fontSize = fontSize,
                    textAlign = TextAlign.Center,
                    color = MemoryColors.TextOnWhite
                )
            }
        } else {
            // Face Down Content - Empty blue card
        }
    }
}

