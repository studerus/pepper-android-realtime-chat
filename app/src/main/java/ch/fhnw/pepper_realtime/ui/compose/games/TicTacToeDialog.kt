package ch.fhnw.pepper_realtime.ui.compose.games

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.tools.games.TicTacToeGame

/**
 * TicTacToe colors
 */
private object TicTacToeColors {
    val CellBackground = Color.White
    val GridBackground = Color(0xFFCCCCCC)
    val PlayerX = Color(0xFF1976D2)  // Blue for user
    val PlayerO = Color(0xFFD32F2F)  // Red for AI
    val CloseButton = Color(0xFFF44336)
    val CardBackground = Color.White
}

/**
 * TicTacToe game state for Compose.
 * Wraps TicTacToeGame engine and exposes observable state for Compose UI.
 */
class TicTacToeGameState {
    // Core game engine - single source of truth for game logic
    private val game = TicTacToeGame()
    
    // Observable state for Compose - updated after each move
    var board by mutableStateOf(IntArray(9))
        private set
    var currentPlayer by mutableIntStateOf(TicTacToeGame.PLAYER_X)
        private set
    var gameResult by mutableIntStateOf(TicTacToeGame.GAME_CONTINUE)
        private set
    
    fun isValidMove(position: Int): Boolean = game.isValidMove(position)
    
    fun makeMove(position: Int, player: Int) {
        if (!game.isValidMove(position)) return
        game.makeMove(position, player)
        syncStateFromEngine()
    }
    
    fun getBoardString(): String = game.getBoardString()
    
    val isGameOver: Boolean
        get() = game.isGameOver
    
    fun reset() {
        game.reset()
        syncStateFromEngine()
    }
    
    private fun syncStateFromEngine() {
        // Create new array to trigger Compose recomposition
        board = IntArray(9) { game.getBoardAt(it) }
        currentPlayer = game.currentPlayer
        gameResult = game.checkWinner()
    }
}

/**
 * TicTacToe Dialog using Jetpack Compose
 */
@Composable
fun TicTacToeDialog(
    gameState: TicTacToeGameState,
    onUserMove: (position: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val yourTurnText = stringResource(R.string.ttt_your_turn)
    val aiThinkingText = stringResource(R.string.ttt_ai_thinking)
    val youWonText = stringResource(R.string.ttt_you_won)
    val aiWonText = stringResource(R.string.ttt_ai_won)
    val drawText = stringResource(R.string.ttt_draw)
    val titleText = stringResource(R.string.tictactoe_title)
    
    // Update status message based on game state
    val statusText = remember(gameState.gameResult, gameState.currentPlayer) {
        when {
            gameState.gameResult == TicTacToeGame.X_WINS -> youWonText
            gameState.gameResult == TicTacToeGame.O_WINS -> aiWonText
            gameState.gameResult == TicTacToeGame.DRAW -> drawText
            gameState.currentPlayer == TicTacToeGame.PLAYER_X -> yourTurnText
            else -> aiThinkingText
        }
    }
    
    // Fullscreen overlay with centered card to preserve immersive mode
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .wrapContentSize()
                .clickable(enabled = false) {}, // Prevent click-through
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TicTacToeColors.CardBackground),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title Bar
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = titleText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TicTacToeColors.CloseButton
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.close_symbol),
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Status Text
                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Game Board
                TicTacToeBoard(
                    board = gameState.board,
                    enabled = !gameState.isGameOver && gameState.currentPlayer == TicTacToeGame.PLAYER_X,
                    onCellClick = { position ->
                        if (gameState.isValidMove(position)) {
                            onUserMove(position)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TicTacToeBoard(
    board: IntArray,
    enabled: Boolean,
    onCellClick: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .background(TicTacToeColors.GridBackground)
            .padding(2.dp)
    ) {
        Column {
            for (row in 0..2) {
                Row {
                    for (col in 0..2) {
                        val position = row * 3 + col
                        TicTacToeCell(
                            value = board[position],
                            enabled = enabled && board[position] == TicTacToeGame.EMPTY,
                            onClick = { onCellClick(position) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TicTacToeCell(
    value: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = TicTacToeColors.CellBackground,
        label = "cellBg"
    )
    
    val textColor = when (value) {
        TicTacToeGame.PLAYER_X -> TicTacToeColors.PlayerX
        TicTacToeGame.PLAYER_O -> TicTacToeColors.PlayerO
        else -> Color.Black
    }
    
    val text = when (value) {
        TicTacToeGame.PLAYER_X -> "X"
        TicTacToeGame.PLAYER_O -> "O"
        else -> ""
    }
    
    Box(
        modifier = Modifier
            .size(100.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

