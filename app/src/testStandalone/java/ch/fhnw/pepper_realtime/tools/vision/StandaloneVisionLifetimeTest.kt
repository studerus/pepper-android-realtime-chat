package ch.fhnw.pepper_realtime.tools.vision

import android.app.Activity
import ch.fhnw.pepper_realtime.service.VisionService
import ch.fhnw.pepper_realtime.tools.OperationScope
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class StandaloneVisionLifetimeTest {
    private val camera = mock<VisionService>()
    private val owner = OperationScope()
    private val activity = mock<Activity>()
    private val context = ToolContext(mock { on { getActivity() } doReturn activity }, null,
        mock(), mock(), mock(), mock(), mock(), mock(), mock(), null, owner)

    private fun runPhoto(timeoutMs: Long = 0, start: (VisionService.Callback) -> Unit): JSONObject {
        whenever(camera.isInitialized).thenReturn(true)
        doAnswer { start(it.getArgument(2)); Unit }.`when`(camera).startAnalyze(anyOrNull(), anyOrNull(), any())
        val result = AnalyzeVisionTool({ camera }, timeoutMs).execute(JSONObject(), context)
        verify(camera).close()
        return JSONObject(result)
    }

    @Test fun `success closes camera`() {
        assertTrue(runPhoto { it.onResult("""{"ok":true}""") }.getBoolean("ok"))
    }
    @Test fun `camera error closes camera`() {
        assertEquals("unavailable", runPhoto { it.onError("unavailable") }.getString("error"))
    }
    @Test fun `timeout closes camera`() {
        assertTrue(runPhoto {}.getString("error").contains("timed out"))
    }
    @Test(timeout = 5000) fun `cancellation wakes the waiter without waiting for timeout`() {
        assertTrue(runPhoto(60_000) { owner.cancel() }.getString("error").contains("cancelled"))
    }
}
