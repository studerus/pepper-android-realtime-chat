package ch.fhnw.pepper_realtime.ui.compose.games

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ch.fhnw.pepper_realtime.ui.compose.ChatColors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Quiz colors
 */
private object QuizColors {
    val InitialBackground = Color.White
    val Stroke = Color(0xFFBDBDBD)
    val CorrectGreen = Color(0xFF4CAF50)
    val IncorrectRed = Color(0xFFF44336)
    val TextOnColored = Color.White
    val TextDefault = Color.Black
}

/**
 * State for tracking answer selection
 */
private sealed class AnswerState {
    object NotAnswered : AnswerState()
    data class Answered(val selectedIndex: Int, val isCorrect: Boolean) : AnswerState()
}

/**
 * Full-screen Quiz Dialog using Jetpack Compose
 */
@Composable
fun QuizDialog(
    question: String,
    options: List<String>,
    correctAnswer: String,
    onAnswered: (selectedOption: String) -> Unit,
    onDismiss: () -> Unit
) {
    var answerState by remember { mutableStateOf<AnswerState>(AnswerState.NotAnswered) }
    val correctIndex = options.indexOf(correctAnswer)
    
    // Auto-dismiss after answering
    LaunchedEffect(answerState) {
        if (answerState is AnswerState.Answered) {
            delay(4000)
            onDismiss()
        }
    }
    
    // Fullscreen overlay instead of Dialog to preserve immersive mode
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Question
            Text(
                text = question,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 42.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
            
            // Options Grid (2x2)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuizOptionButton(
                        text = options.getOrNull(0) ?: "",
                        index = 0,
                        answerState = answerState,
                        correctIndex = correctIndex,
                        enabled = answerState is AnswerState.NotAnswered,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val isCorrect = options[0] == correctAnswer
                            answerState = AnswerState.Answered(0, isCorrect)
                            onAnswered(options[0])
                        }
                    )
                    QuizOptionButton(
                        text = options.getOrNull(1) ?: "",
                        index = 1,
                        answerState = answerState,
                        correctIndex = correctIndex,
                        enabled = answerState is AnswerState.NotAnswered,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val isCorrect = options[1] == correctAnswer
                            answerState = AnswerState.Answered(1, isCorrect)
                            onAnswered(options[1])
                        }
                    )
                }
                
                // Row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuizOptionButton(
                        text = options.getOrNull(2) ?: "",
                        index = 2,
                        answerState = answerState,
                        correctIndex = correctIndex,
                        enabled = answerState is AnswerState.NotAnswered,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val isCorrect = options[2] == correctAnswer
                            answerState = AnswerState.Answered(2, isCorrect)
                            onAnswered(options[2])
                        }
                    )
                    QuizOptionButton(
                        text = options.getOrNull(3) ?: "",
                        index = 3,
                        answerState = answerState,
                        correctIndex = correctIndex,
                        enabled = answerState is AnswerState.NotAnswered,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val isCorrect = options[3] == correctAnswer
                            answerState = AnswerState.Answered(3, isCorrect)
                            onAnswered(options[3])
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuizOptionButton(
    text: String,
    index: Int,
    answerState: AnswerState,
    correctIndex: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            answerState is AnswerState.NotAnswered -> QuizColors.InitialBackground
            answerState is AnswerState.Answered && answerState.selectedIndex == index -> {
                if (answerState.isCorrect) QuizColors.CorrectGreen else QuizColors.IncorrectRed
            }
            answerState is AnswerState.Answered && index == correctIndex -> QuizColors.CorrectGreen
            else -> QuizColors.InitialBackground
        },
        label = "buttonColor"
    )
    
    val textColor = when {
        answerState is AnswerState.NotAnswered -> QuizColors.TextDefault
        answerState is AnswerState.Answered && answerState.selectedIndex == index -> QuizColors.TextOnColored
        answerState is AnswerState.Answered && index == correctIndex -> QuizColors.TextOnColored
        else -> QuizColors.TextDefault
    }
    
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = backgroundColor,
            contentColor = textColor,
            disabledContainerColor = backgroundColor,
            disabledContentColor = textColor
        ),
        border = BorderStroke(1.dp, QuizColors.Stroke)
    ) {
        Text(
            text = text,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
            lineHeight = 34.sp
        )
    }
}

