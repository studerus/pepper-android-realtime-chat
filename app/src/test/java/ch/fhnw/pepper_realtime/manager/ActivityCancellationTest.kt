package ch.fhnw.pepper_realtime.manager

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityCancellationTest {
    @Test
    fun `close event attributes action to user rather than assistant`() {
        val message = ActivityCancellation.message("the drawing canvas")
        assertTrue(message.contains("The user manually closed the drawing canvas"))
        assertTrue(message.contains("You did not close it"))
        assertTrue(message.contains("second person"))
        assertFalse(message.contains("Stop this activity"))
    }

    @Test
    fun `drawing close notifies once after cleanup and cancels pending image`() = runTest {
        val manager = DrawingGameManager()
        manager.setCoroutineScope(this)
        var closed = 0
        var images = 0
        manager.setImageSendCallback { _, _ -> images++; true }
        manager.startGame("House") {
            assertFalse(manager.state.value.isVisible)
            closed++
        }
        manager.onDrawingChanged { error("Closed drawing must not be rendered") }
        manager.cancelGame()
        manager.cancelGame()
        advanceUntilIdle()
        assertEquals(1, closed)
        assertEquals(0, images)
        assertFalse(manager.state.value.hasUnsavedChanges)
    }

    @Test
    fun `drawing cleanup stays silent and next drawing can be closed`() {
        val manager = DrawingGameManager()
        var closed = 0
        manager.startGame(null) { closed++ }
        manager.dismissGame()
        manager.cancelGame()
        assertEquals(0, closed)
        manager.startGame(null) { closed++ }
        manager.cancelGame()
        assertEquals(1, closed)
    }

    @Test
    fun `memory cancellation requests one response and stops pending work`() = runTest {
        val manager = MemoryGameManager()
        manager.setCoroutineScope(this)
        val updates = mutableListOf<Pair<String, Boolean>>()
        manager.startGame("easy") { text, respond -> updates.add(text to respond) }
        val cards = manager.state.value.cards
        manager.onCardClick(cards.first().id)
        manager.onCardClick(cards.first { it.symbol != cards.first().symbol }.id)
        updates.clear()
        manager.cancelGame()
        manager.cancelGame()
        advanceUntilIdle()
        assertFalse(manager.state.value.isVisible)
        assertTrue(manager.state.value.cards.isEmpty())
        assertEquals(1, updates.size)
        assertTrue(updates.single().first.contains("Memory"))
        assertTrue(updates.single().second)
    }

    @Test
    fun `completed memory is not reported as cancelled`() {
        val manager = MemoryGameManager()
        val updates = mutableListOf<String>()
        manager.startGame("easy") { text, _ -> updates.add(text) }
        manager.state.value.cards.groupBy { it.symbol }.values.forEach { pair ->
            pair.forEach { manager.onCardClick(it.id) }
        }
        updates.clear()
        manager.cancelGame()
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `tic tac toe cancellation requests one response and rejects later moves`() {
        val manager = TicTacToeGameManager()
        val updates = mutableListOf<Pair<String, Boolean>>()
        manager.startGame { text, respond -> updates.add(text to respond) }
        manager.cancelGame()
        manager.cancelGame()
        manager.onUserMove(0)
        assertEquals(1, updates.size)
        assertTrue(updates.single().first.contains("Tic Tac Toe"))
        assertTrue(updates.single().second)
        assertFalse(manager.makeAIMove(4).success)
        assertFalse(manager.isGameActive())
    }

    @Test
    fun `completed tic tac toe is not reported as cancelled`() {
        val manager = TicTacToeGameManager()
        val updates = mutableListOf<String>()
        manager.startGame { text, _ -> updates.add(text) }
        manager.onUserMove(0)
        manager.makeAIMove(3)
        manager.onUserMove(1)
        manager.makeAIMove(4)
        manager.onUserMove(2)
        assertTrue(manager.gameState.isGameOver)
        updates.clear()
        manager.cancelGame()
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `quiz cancellation notifies once and prevents stale answers`() {
        val manager = QuizGameManager()
        var cancellations = 0
        var answers = 0
        manager.showQuiz("Q", listOf("A", "B"), "A", { cancellations++ }) { _, _ -> answers++ }
        manager.cancelQuiz()
        manager.cancelQuiz()
        manager.onAnswerSelected("A")
        assertEquals(1, cancellations)
        assertEquals(0, answers)
        assertFalse(manager.state.value.isVisible)
    }

    @Test
    fun `answered or automatically dismissed quiz does not notify cancellation`() {
        val manager = QuizGameManager()
        var cancellations = 0
        var answers = 0
        repeat(2) { iteration ->
            manager.showQuiz("Q", listOf("A", "B"), "A", { cancellations++ }) { _, _ -> answers++ }
            manager.onAnswerSelected("A")
            manager.onAnswerSelected("A")
            if (iteration == 0) manager.dismissQuiz() else manager.cancelQuiz()
        }
        assertEquals(0, cancellations)
        assertEquals(2, answers)
    }

    @Test
    fun `programmatic game cleanup does not notify cancellation`() {
        val memory = MemoryGameManager()
        val ticTacToe = TicTacToeGameManager()
        val updates = mutableListOf<String>()
        memory.startGame("easy") { text, _ -> updates.add(text) }
        ticTacToe.startGame { text, _ -> updates.add(text) }
        updates.clear()
        memory.dismissGame()
        ticTacToe.dismissGame()
        assertTrue(updates.isEmpty())
    }
}
