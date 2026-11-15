package ch.fhnw.pepper_realtime.manager

import android.media.AudioTrack
import org.junit.*
import org.junit.Assert.*
import org.mockito.kotlin.*
import java.io.ByteArrayOutputStream

class AudioPlayerTest {
    private val track = mock<AudioTrack>()
    private val listener = mock<AudioPlayer.Listener>()
    private val work = ArrayDeque<Runnable>()
    private val written = ByteArrayOutputStream()
    private lateinit var player: AudioPlayer
    private var head = 0
    private var writeLimit = Int.MAX_VALUE
    private var marker: AudioTrack.OnPlaybackPositionUpdateListener? = null
    private var tracksCreated = 0

    @Before fun setUp() {
        whenever(track.state).thenReturn(AudioTrack.STATE_INITIALIZED)
        whenever(track.playbackHeadPosition).thenAnswer { head }
        whenever(track.write(any<ByteArray>(), any(), any(), any())).thenAnswer {
            val bytes = it.getArgument<ByteArray>(0)
            val offset = it.getArgument<Int>(1)
            val count = minOf(it.getArgument<Int>(2), writeLimit)
            written.write(bytes, offset, count)
            head += count / 2
            count
        }
        doAnswer { head = 0; null }.whenever(track).stop()
        doAnswer { marker = it.getArgument(0); null }.whenever(track).setPlaybackPositionUpdateListener(any())
        whenever(track.setNotificationMarkerPosition(any())).thenAnswer { marker?.onMarkerReached(track); 0 }
        player = AudioPlayer({ tracksCreated++; track }, { task -> work.add(task); Thread() })
        player.setListener(listener)
    }
    @After fun tearDown() { player.release() }
    private fun data(bytes: Int) = ByteArray(bytes) { (it % 127).toByte() }
    private fun runPlayback() { while (work.isNotEmpty()) work.removeFirst().run() }

    @Test fun `completed short response starts below normal buffering threshold`() {
        val input = data(480) // 10ms at 24kHz, below the 60ms start threshold.
        player.addChunk(input); player.startIfNeeded()
        assertFalse(player.isPlaying()); assertTrue(work.isEmpty())
        player.markResponseDone(); runPlayback()
        assertArrayEquals(input, written.toByteArray())
        assertFalse(player.isPlaying())
        verify(listener).onPlaybackStarted(); verify(listener).onPlaybackFinished()
    }

    @Test fun `empty response never starts playback`() {
        player.markResponseDone()
        assertTrue(work.isEmpty())
        verifyNoInteractions(listener)
    }

    @Test(timeout = 5000) fun `end of stream preserves tail already buffered by the playback thread`() {
        val input = data(3000)
        var observedFullFrame = 0
        whenever(track.playbackHeadPosition).thenAnswer {
            // Observe the first frame once at submission and once on the next consumer pass.
            if (written.size() == 1920 && ++observedFullFrame == 2) player.markResponseDone()
            0
        }
        player.addChunk(input); player.startIfNeeded(); runPlayback()
        assertArrayEquals(input, written.toByteArray())
        verify(listener).onPlaybackFinished()
    }

    @Test(timeout = 5000) fun `new response boundary cannot erase the preceding unplayed tail`() {
        val first = data(3000); val next = data(200)
        var observedFullFrame = 0
        whenever(track.playbackHeadPosition).thenAnswer {
            if (written.size() == 1920 && ++observedFullFrame == 2) {
                player.onResponseBoundary(); player.addChunk(next); player.markResponseDone()
            }
            0
        }
        player.addChunk(first); player.startIfNeeded(); runPlayback()
        assertArrayEquals(first + next, written.toByteArray())
    }

    @Test fun `partial native writes preserve the entire final tail`() {
        writeLimit = 2
        val input = data(100)
        player.addChunk(input); player.markResponseDone(); runPlayback()
        assertArrayEquals(input, written.toByteArray())
        verify(track, times(50)).write(any<ByteArray>(), any(), any(), any())
    }

    @Test(timeout = 5000) fun `response boundary resets interruption position without dropping PCM`() {
        val input = data(4000)
        var writes = 0
        whenever(track.write(any<ByteArray>(), any(), any(), any())).thenAnswer {
            val count = it.getArgument<Int>(2)
            written.write(it.getArgument<ByteArray>(0), it.getArgument(1), count)
            head += count / 2
            if (++writes == 2) {
                player.onResponseBoundary()
                assertEquals(0, player.getEstimatedPlaybackPositionMs())
                player.markResponseDone()
            }
            count
        }
        player.addChunk(input); player.startIfNeeded(); runPlayback()
        assertArrayEquals(input, written.toByteArray())
    }

    @Test fun `response queued during drain survives previous playback cleanup`() {
        val first = data(100); val next = data(200)
        var markers = 0
        whenever(track.setNotificationMarkerPosition(any())).thenAnswer {
            if (++markers == 1) { player.onResponseBoundary(); player.addChunk(next); player.markResponseDone() }
            marker?.onMarkerReached(track)
            0
        }
        player.addChunk(first); player.markResponseDone(); runPlayback()
        assertArrayEquals(first + next, written.toByteArray())
        assertFalse(player.isPlaying())
    }

    @Test fun `write failure resolves playback and allows a later response`() {
        whenever(track.write(any<ByteArray>(), any(), any(), any())).thenReturn(AudioTrack.ERROR_DEAD_OBJECT)
        player.addChunk(data(100)); player.markResponseDone(); runPlayback()
        assertFalse(player.isPlaying()); verify(listener).onPlaybackFinished()
        whenever(track.write(any<ByteArray>(), any(), any(), any())).thenAnswer { it.getArgument<Int>(2) }
        player.onResponseBoundary(); player.addChunk(data(100)); player.markResponseDone(); runPlayback()
        verify(listener, times(2)).onPlaybackFinished()
        assertEquals(2, tracksCreated)
    }

    @Test(timeout = 5000) fun `zero progress writes terminate rather than spinning forever`() {
        whenever(track.write(any<ByteArray>(), any(), any(), any())).thenReturn(0)
        player.addChunk(data(100)); player.markResponseDone(); runPlayback()
        assertFalse(player.isPlaying())
        verify(track, times(5)).write(any<ByteArray>(), any(), any(), any())
        verify(listener).onPlaybackFinished()
    }

    @Test fun `unusable playback device resets busy state and delivers completion`() {
        whenever(track.state).thenReturn(AudioTrack.STATE_UNINITIALIZED)
        player.addChunk(data(100)); player.markResponseDone(); runPlayback()
        assertFalse(player.isPlaying())
        verify(listener).onPlaybackFinished()
    }

    @Test fun `explicit interrupt discards queued audio`() {
        player.addChunk(data(3000)); player.startIfNeeded()
        player.interruptNow(); runPlayback()
        assertEquals(0, written.size())
        assertFalse(player.isPlaying())
        verify(listener).onPlaybackFinished()
    }
}
