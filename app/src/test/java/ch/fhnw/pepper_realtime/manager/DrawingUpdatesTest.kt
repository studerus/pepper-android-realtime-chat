package ch.fhnw.pepper_realtime.manager

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrawingUpdatesTest {
    private fun TestScope.game(sent: MutableList<Pair<Long, String>>) =
        DrawingGameManager { testScheduler.currentTime }.also { game ->
            game.setCoroutineScope(this)
            game.setImageSendCallback { image, mime ->
                assertEquals("image/png", mime)
                sent.add(testScheduler.currentTime to image)
                true
            }
            game.startGame(null)
        }

    @Test fun `picking up another line resets the pause without rendering the earlier line`() = runTest {
        val sent = mutableListOf<Pair<Long, String>>()
        val game = game(sent)
        game.onDrawingChanged { error("Superseded image rendered") }
        advanceTimeBy(450)
        game.onDrawingChanged { "two lines" }
        advanceTimeBy(799)
        runCurrent()
        assertTrue(sent.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(1250L to "two lines"), sent)
        assertFalse(game.state.value.hasUnsavedChanges)
    }

    @Test fun `continuous movement sends intermediate images and a final paused image only once`() = runTest {
        val sent = mutableListOf<Pair<Long, String>>()
        val game = game(sent)
        for (i in 0..17) {
            game.onDrawingChanged { "point $i" }
            advanceTimeBy(250)
            runCurrent()
        }
        advanceUntilIdle()
        assertEquals(listOf(2000L to "point 7", 4000L to "point 15", 5050L to "point 17"), sent)
        game.onDrawingChanged { "point 17" }
        advanceUntilIdle()
        assertEquals(3, sent.size)
    }

    @Test fun `edits during encoding stay dirty until the latest image is sent`() = runTest {
        val sent = mutableListOf<Pair<Long, String>>()
        val game = game(sent)
        game.onDrawingChanged { delay(300); "earlier" }
        advanceTimeBy(850)
        game.onDrawingChanged { "latest" }
        advanceTimeBy(300)
        assertTrue(game.state.value.hasUnsavedChanges)
        advanceUntilIdle()
        assertEquals(listOf(1100L to "earlier", 1650L to "latest"), sent)
        assertFalse(game.state.value.hasUnsavedChanges)
    }

    @Test fun `clear cancels in-flight work and replaces a previously sent image with blank`() = runTest {
        val sent = mutableListOf<Pair<Long, String>>()
        val game = game(sent)
        game.onDrawingChanged { "first" }
        advanceUntilIdle()
        game.onDrawingChanged { delay(1000); "discarded" }
        advanceTimeBy(900)
        game.clearCanvas { "blank" }
        advanceUntilIdle()
        assertEquals(listOf("first", "blank"), sent.map { it.second })
        game.onDrawingChanged { delay(1000); "old game" }
        advanceTimeBy(900)
        game.startGame("new game")
        advanceUntilIdle()
        assertEquals(2, sent.size)
    }
}
