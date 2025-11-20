package io.github.anonymous.pepper_realtime.controller;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

/**
 * Controls system audio volume.
 */
public class AudioVolumeController {
    private static final String TAG = "AudioVolumeController";

    /**
     * Set media volume as a percentage (0-100)
     */
    public void setVolume(Context context, int percentage) {
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                double volume = percentage / 100.0;
                int targetVol = Math.max(0, Math.min(maxVol, (int) Math.round(volume * maxVol)));
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                Log.d(TAG, "Set volume to " + percentage + "% (hardware level: " + targetVol + "/" + maxVol + ")");
            }
        } catch (Exception e) {
            Log.w(TAG, "Setting media volume failed", e);
        }
    }
}
