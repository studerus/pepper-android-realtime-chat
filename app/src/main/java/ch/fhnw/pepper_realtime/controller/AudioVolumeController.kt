package ch.fhnw.pepper_realtime.controller

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Controls system audio volume.
 */
class AudioVolumeController {

    companion object {
        private const val TAG = "AudioVolumeController"
    }

    /**
     * Set media volume as a percentage (0-100)
     */
    fun setVolume(context: Context, percentage: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val volume = percentage / 100.0
                val targetVol = max(0, min(maxVol, (volume * maxVol).roundToInt()))
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                Log.d(TAG, "Set volume to $percentage% (hardware level: $targetVol/$maxVol)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Setting media volume failed", e)
        }
    }
}

