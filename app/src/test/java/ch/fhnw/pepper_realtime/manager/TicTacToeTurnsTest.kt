package ch.fhnw.pepper_realtime.manager

import org.junit.Assert.*
import org.junit.Test

class TicTacToeTurnsTest {
    @Test fun `invalid moves do not change turns or send game updates`() {
        val manager = TicTacToeGameManager()
        var updates = 0
        manager.startGame { _, _ -> updates++ }
        assertFalse(manager.makeAIMove(1).success)
        manager.onUserMove(0)
        manager.onUserMove(2)
        assertFalse(manager.makeAIMove(0).success)
        assertTrue(manager.makeAIMove(3).success)
        assertFalse(manager.makeAIMove(4).success)
        assertEquals("X--O-----", manager.gameState.getBoardString())
        assertEquals(1, updates)
        manager.onUserMove(1)
        assertTrue(manager.makeAIMove(4).success)
        manager.onUserMove(2)
        assertTrue(manager.gameState.isGameOver)
        assertFalse(manager.makeAIMove(5).success)
        manager.onUserMove(6)
        assertEquals("XXXOO----", manager.gameState.getBoardString())
        assertEquals(3, updates)
    }
}
