package ch.fhnw.pepper_realtime.manager

import android.media.AudioRecord
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import org.junit.*
import org.junit.Assert.*
import org.mockito.kotlin.*

class RealtimeAudioInputManagerTest {
    private val session = mock<RealtimeSessionManager>()
    private val recorders = ArrayDeque<AudioRecord>()
    private val work = ArrayDeque<Runnable>()
    private val errors = mutableListOf<String>()
    private val formats = mutableListOf<Pair<Int, Int>>()
    private val manager = RealtimeAudioInputManager(session, { rate, bytes ->
        formats.add(rate to bytes); recorders.removeFirst()
    }, { task -> work.add(task); Thread() }).apply { onCaptureError = { errors.add(it) } }

    @Before fun setUp() { whenever(session.isConnected).thenReturn(true) }
    @After fun tearDown() { manager.stop() }
    private fun recorder(): AudioRecord = mock<AudioRecord>().also {
        whenever(it.state).thenReturn(AudioRecord.STATE_INITIALIZED)
        whenever(it.recordingState).thenReturn(AudioRecord.RECORDSTATE_RECORDING)
        whenever(it.read(any<ByteArray>(), any(), any())).thenReturn(AudioRecord.ERROR_DEAD_OBJECT)
        recorders.add(it)
    }

    @Test fun `fatal read releases recorder and permits a new attempt`() {
        val old = recorder()
        assertTrue(manager.start())
        work.removeFirst().run()
        assertFalse(manager.isCapturing)
        assertEquals(1, errors.size)
        verify(old).stop(); verify(old).release()
        val next = recorder()
        assertTrue(manager.start())
        assertTrue(manager.isCapturing)
        verify(next).startRecording()
    }

    @Test fun `invalid and repeated read errors reset capture state`() {
        for (code in listOf(AudioRecord.ERROR_INVALID_OPERATION, AudioRecord.ERROR_BAD_VALUE, AudioRecord.ERROR)) {
            val recorder = recorder()
            whenever(recorder.read(any<ByteArray>(), any(), any())).thenReturn(code)
            assertTrue(manager.start()); work.removeFirst().run()
            assertFalse(manager.isCapturing)
            verify(recorder).release()
        }
        assertEquals(3, errors.size)
    }

    @Test fun `repeated read exceptions release resources`() {
        val recorder = recorder()
        whenever(recorder.read(any<ByteArray>(), any(), any())).thenThrow(IllegalStateException("lost recorder"))
        assertTrue(manager.start()); work.removeFirst().run()
        assertFalse(manager.isCapturing)
        verify(recorder, times(5)).read(any<ByteArray>(), any(), any())
        verify(recorder).release()
        assertEquals(1, errors.size)
    }

    @Test fun `failed initialization releases the unusable recorder`() {
        val recorder = recorder()
        whenever(recorder.state).thenReturn(AudioRecord.STATE_UNINITIALIZED)
        assertFalse(manager.start())
        assertFalse(manager.isCapturing)
        verify(recorder).release()
        assertTrue(work.isEmpty())
        recorder(); assertTrue(manager.start())
    }

    @Test fun `failed recording start releases the recorder and allows retry`() {
        val recorder = recorder()
        doThrow(IllegalStateException("start failed")).whenever(recorder).startRecording()
        assertFalse(manager.start()); verify(recorder).release()
        recorder(); assertTrue(manager.start())
    }

    @Test fun `release still runs when stopping the recorder throws`() {
        val recorder = recorder()
        doThrow(IllegalStateException("stop failed")).whenever(recorder).stop()
        assertTrue(manager.start()); manager.stop()
        work.removeFirst().run()
        verify(recorder, times(1)).release()
        assertFalse(manager.isCapturing)
        assertTrue(errors.isEmpty())
    }

    @Test fun `late old capture cleanup cannot release a newer recorder`() {
        val old = recorder(); assertTrue(manager.start())
        val oldWork = work.removeFirst()
        manager.stop()
        val next = recorder(); assertTrue(manager.start())
        oldWork.run()
        assertTrue(manager.isCapturing)
        verify(old, times(1)).release()
        verify(next, never()).stop(); verify(next, never()).release()
        assertTrue(errors.isEmpty())
    }

    @Test fun `stop during read prevents the final old chunk from being sent`() {
        val old = recorder()
        whenever(old.read(any<ByteArray>(), any(), any())).thenAnswer { manager.stop(); 100 }
        assertTrue(manager.start()); work.removeFirst().run()
        verify(session, never()).sendAudioChunk(any())
        verify(old, times(1)).release()
        assertTrue(errors.isEmpty())
    }

    @Test fun `capture retains its provider format through cleanup`() {
        recorder()
        manager.isGoogleProvider = true
        assertTrue(manager.start())
        manager.isGoogleProvider = false
        manager.stop()
        assertEquals(listOf(16000 to 3200), formats)
        verify(session).sendGoogleAudioStreamEnd()
        recorder(); assertTrue(manager.start())
        assertEquals(24000 to 4800, formats.last())
    }

    @Test fun `duplicate start does not allocate another recorder`() {
        val recorder = recorder()
        assertTrue(manager.start()); assertTrue(manager.start())
        assertEquals(1, work.size)
        verify(recorder, times(1)).startRecording()
    }
}
