package com.example.pepper_test2;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("BusyWait") // Thread.sleep necessary for real-time audio synchronization
public class AudioPlayer {
    public interface Listener {
        void onPlaybackStarted();
        void onPlaybackFinished();
    }

    private static final String TAG = "AudioPlayer";

    private final List<byte[]> audioBuffer = new ArrayList<>();
    private volatile boolean isPlaying = false;
    private volatile boolean isResponseDone = false;
    private AudioTrack audioTrack;
    private Listener listener;
    private int sampleRateHz = 24000; // expected default
    private final int channels = 1;
    private final int bytesPerSample = 2; // pcm16

    // carry-over buffer for frame alignment
    private byte[] carry = null;

    // Track frames written to ensure proper drain
    private long totalFramesWritten = 0;
    private Thread playThread = null;

    public AudioPlayer() { initializeAudioTrack(); }

    public void setListener(Listener listener) { this.listener = listener; }
    public boolean isPlaying() { return isPlaying; }

    public void setSampleRate(int hz) {
        if (hz > 0 && hz != sampleRateHz) {
            sampleRateHz = hz;
            release();
            initializeAudioTrack();
        }
    }

    public void addChunk(byte[] data) {
        if (data == null || data.length == 0) return;
        synchronized (audioBuffer) { audioBuffer.add(data); }
    }

    public void startIfNeeded() {
        if (isPlaying) return;
        if (!hasSufficientBuffer()) return;
        isPlaying = true;
        if (listener != null) listener.onPlaybackStarted();
        Thread t = new Thread(this::playLoop, "audio-play-loop");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        playThread = t;
        t.start();
    }

    public void markResponseDone() { isResponseDone = true; }

	public int getEstimatedPlaybackPositionMs() {
		try {
			if (audioTrack == null) return 0;
			int frames = audioTrack.getPlaybackHeadPosition();
			return (int) Math.round(frames * 1000.0 / sampleRateHz);
		} catch (Exception ignored) {
			return 0;
		}
	}

	public void interruptNow() {
		try {
			isResponseDone = true;
			synchronized (audioBuffer) { audioBuffer.clear(); }
			carry = null;
			if (audioTrack != null) {
				if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    try { audioTrack.pause(); } catch (Exception ignored) {}
                    try { audioTrack.flush(); } catch (Exception ignored) {}
                    try { audioTrack.stop(); } catch (Exception ignored) {}
				}
			}
            // Wait briefly for playback thread to finish to avoid tail audio
            Thread t = playThread;
            if (t != null && t != Thread.currentThread()) {
                try { t.join(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
		} catch (Exception ignored) {}
	}

    public void release() {
        try {
            if (audioTrack != null) {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    try { audioTrack.stop(); } catch (Exception ignored) {}
                }
                audioTrack.release();
                audioTrack = null;
            }
        } catch (Exception ignored) {}
    }

    private void initializeAudioTrack() {
        try {
            int internalBufferMs = 400; // target internal buffer size
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO; // Always mono since channels = 1
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioTrack.getMinBufferSize(sampleRateHz, channelConfig, audioFormat);
            int bufferSize = Math.max(minBuf, (int)(sampleRateHz * bytesPerSample * channels * (internalBufferMs / 1000.0))); // ~400ms
            audioTrack = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(channelConfig)
                            .build(),
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            Log.i(TAG, "AudioTrack initialized. rate=" + sampleRateHz + "Hz, buf=" + bufferSize);
            carry = null;
            totalFramesWritten = 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AudioTrack", e);
        }
    }

    private boolean hasSufficientBuffer() {
        int startBufferMs = 200; // increased start buffer to ~200ms
        int needed = (int)((sampleRateHz * bytesPerSample * channels) * (startBufferMs / 1000.0));
        int available = 0;
        synchronized (audioBuffer) {
            for (byte[] b : audioBuffer) available += b.length;
        }
        return available >= needed;
    }

    private void playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not ready.");
            isPlaying = false;
            return;
        }
        try {
            audioTrack.play();
            int frameBytes = frameBytes10ms(); // write in 10ms frames
            while (true) {
                byte[] data = null;
                synchronized (audioBuffer) {
                    if (!audioBuffer.isEmpty()) data = audioBuffer.remove(0);
                }
                if (data != null) {
                    // prepend carry if exists
                    byte[] toWrite;
                    int len;
                    if (carry != null && carry.length > 0) {
                        toWrite = new byte[carry.length + data.length];
                        System.arraycopy(carry, 0, toWrite, 0, carry.length);
                        System.arraycopy(data, 0, toWrite, carry.length, data.length);
                        carry = null;
                        len = toWrite.length;
                    } else {
                        toWrite = data;
                        len = data.length;
                    }
                    int offset = 0;
                    // write in aligned frames
                    while (len - offset >= frameBytes) {
                        int written = audioTrack.write(toWrite, offset, frameBytes);
                        if (written < 0) {
                            // Brief sleep for audio underflow recovery - necessary for real-time audio
                            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                        } else {
                            offset += written;
                            totalFramesWritten += written / (long)(bytesPerSample * channels);
                        }
                    }
                    // keep leftover as carry
                    int remaining = len - offset;
                    if (remaining > 0) {
                        carry = new byte[remaining];
                        System.arraycopy(toWrite, offset, carry, 0, remaining);
                    }
                } else {
                    if (isResponseDone) break;
                    // Wait for more audio data - necessary for streaming audio playback
                    try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
            // flush carry if present before drain/stop
            if (carry != null && carry.length > 0) {
                int written = audioTrack.write(carry, 0, carry.length);
                if (written > 0) {
                    totalFramesWritten += written / (long)(bytesPerSample * channels);
                }
                carry = null;
            }
            // Precise drain: wait until playback head catches up to the last written frame
            try {
                int targetFrames = (totalFramesWritten > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) totalFramesWritten;
                long startWait = System.nanoTime();
                while (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                        && audioTrack.getPlaybackHeadPosition() < targetFrames) {
                    // Wait for audio drain completion - necessary for precise audio timing
                    Thread.sleep(10);
                    // Safety timeout ~2 seconds to avoid infinite loop in edge cases
                    if ((System.nanoTime() - startWait) > 2_000_000_000L) {
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Drain wait interrupted", e);
            }
            audioTrack.stop();
        } catch (Exception e) {
            Log.e(TAG, "Playback error", e);
        } finally {
            isPlaying = false;
            isResponseDone = false;
            synchronized (audioBuffer) { audioBuffer.clear(); }
            carry = null;
            totalFramesWritten = 0;
            if (listener != null) listener.onPlaybackFinished();
        }
    }

    private int frameBytes10ms() {
        int samplesPer10ms = sampleRateHz / 100; // e.g., 240 @ 24kHz
        return samplesPer10ms * bytesPerSample * channels; // e.g., 240 * 2 * 1 = 480 bytes
    }
}
