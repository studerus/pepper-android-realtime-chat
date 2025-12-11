package ch.fhnw.pepper_realtime.tools.entertainment

import android.util.Log
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.ui.ChatActivity
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tool for playing simple melodies using synthesized sine waves.
 * Opens a visual overlay while playing and allows user cancellation.
 * Mutes the microphone during playback to avoid interference.
 * Blocks until melody finishes or user cancels.
 */
class PlayMelodyTool : Tool {

    override fun getName(): String = "play_melody"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", """
                Plays a simple melody on the robot using synthesized sine waves.
                Use this to sing Happy Birthday or play simple tunes.
                Format is a comma-separated string of 'Note:DurationMs'.
                Notes: C3 to C6 (e.g., C4, D#4, Bb4). Use 'R' for rest/pause.
                
                HAPPY BIRTHDAY (full melody):
                "G4:300, G4:300, A4:600, G4:600, C5:600, B4:1200, R:300, G4:300, G4:300, A4:600, G4:600, D5:600, C5:1200, R:300, G4:300, G4:300, G5:600, E5:600, C5:600, B4:600, A4:1200, R:300, F5:300, F5:300, E5:600, C5:600, D5:600, C5:1200"
            """.trimIndent())

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("melody", JSONObject()
                        .put("type", "string")
                        .put("description", "The melody string to play (e.g. 'C4:400, D4:400')"))
                })
                put("required", org.json.JSONArray().put("melody"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val melody = args.optString("melody")
        if (melody.isEmpty()) {
            return JSONObject().put("error", "No melody provided.").toString()
        }

        val activity = context.activity as? ChatActivity
        if (activity == null || !context.hasUi()) {
            return JSONObject().put("error", "No UI available").toString()
        }

        Log.i(TAG, "Starting melody player: $melody")

        // Mute microphone while melody is playing
        context.toolHost.muteMicrophone()

        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(1)
        val wasCancelled = AtomicBoolean(false)
        var started = false

        activity.runOnUiThread {
            started = activity.viewModel.startMelodyPlayer(melody) { cancelled ->
                // This callback is called when melody finishes or is dismissed
                wasCancelled.set(cancelled)
                context.toolHost.unmuteMicrophone()
                context.toolHost.refreshChatMessages()
                finishLatch.countDown()
            }
            startLatch.countDown()
        }

        // Wait for UI thread to start the player
        startLatch.await(2, TimeUnit.SECONDS)

        if (!started) {
            // If failed to start, unmute microphone
            context.toolHost.unmuteMicrophone()
            return JSONObject().put("error", "Could not start melody player").toString()
        }

        // Wait for melody to finish or be cancelled (max 5 minutes for long melodies)
        val finished = finishLatch.await(5, TimeUnit.MINUTES)

        return if (finished) {
            if (wasCancelled.get()) {
                JSONObject()
                    .put("status", "Melody was stopped by user.")
                    .put("info", "The user cancelled the melody before it finished.")
                    .toString()
            } else {
                JSONObject()
                    .put("status", "Melody played successfully.")
                    .toString()
            }
        } else {
            // Timeout - shouldn't happen with normal melodies
            JSONObject()
                .put("status", "Melody playback timed out.")
                .toString()
        }
    }

    companion object {
        private const val TAG = "PlayMelodyTool"
    }
}
