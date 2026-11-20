package ch.fhnw.pepper_realtime.manager

import android.os.SystemClock
import android.util.Log
import ch.fhnw.pepper_realtime.ui.DrawingGameState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DrawingGameManager internal constructor(private val nowMs: () -> Long) {
    @Inject constructor() : this(SystemClock::uptimeMillis)

    private companion object {
        const val PAUSE_MS = 800L
        const val PREVIEW_MS = 2000L
    }

    private val _state = MutableStateFlow(DrawingGameState())
    val state = _state.asStateFlow()

    private var imageSendCallback: ((String, String) -> Boolean)? = null
    private var closeCallback: (() -> Unit)? = null
    private var coroutineScope: CoroutineScope? = null
    private var worker: Job? = null
    private var lastEdit: Long? = null
    private var nextPreview = 0L
    private var pending: (suspend () -> String)? = null
    private var lastSentImage: String? = null

    fun setCoroutineScope(scope: CoroutineScope) { coroutineScope = scope }

    fun setImageSendCallback(callback: (base64: String, mime: String) -> Boolean) {
        imageSendCallback = callback
    }

    fun startGame(topic: String?, onClosed: () -> Unit = {}): Boolean {
        cancelPending()
        lastSentImage = null
        closeCallback = onClosed
        _state.value = DrawingGameState(isVisible = true, topic = topic?.takeIf { it.isNotBlank() })
        return true
    }

    // Main-thread only. One worker coalesces edits, including edits made during encoding.
    fun onDrawingChanged(snapshot: suspend () -> String) {
        if (!state.value.isVisible) return
        val now = nowMs()
        val previous = lastEdit
        if (previous == null || now - previous >= PAUSE_MS) nextPreview = now + PREVIEW_MS
        lastEdit = now
        pending = snapshot
        if (!state.value.hasUnsavedChanges) _state.update { it.copy(hasUnsavedChanges = true) }
        if (worker?.isActive == true) return
        worker = coroutineScope?.launch {
            while (pending != null) {
                val wait = minOf(requireNotNull(lastEdit) + PAUSE_MS, nextPreview) - nowMs()
                if (wait > 0) {
                    delay(wait)
                    continue
                }
                val capture = pending ?: break
                pending = null
                nextPreview = nowMs() + PREVIEW_MS
                try {
                    val image = capture()
                    ensureActive()
                    if (image == lastSentImage) {
                        _state.update { it.copy(hasUnsavedChanges = pending != null) }
                    } else if (imageSendCallback?.invoke(image, "image/png") == true) {
                        lastSentImage = image
                        _state.update { it.copy(hasUnsavedChanges = pending != null, lastSentTimestamp = System.currentTimeMillis()) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("DrawingGameManager", "Cannot send drawing snapshot", e)
                }
            }
        }
    }

    fun clearCanvas(blank: suspend () -> String) {
        cancelPending()
        _state.update { it.copy(hasUnsavedChanges = false) }
        if (lastSentImage != null) onDrawingChanged(blank)
    }

    private fun cancelPending() {
        worker?.cancel()
        worker = null
        pending = null
        lastEdit = null
    }

    fun cancelGame() {
        val wasVisible = state.value.isVisible
        val callback = closeCallback
        dismissGame()
        if (wasVisible) callback?.invoke()
    }

    fun dismissGame() {
        cancelPending()
        lastSentImage = null
        _state.value = DrawingGameState()
        closeCallback = null
    }
}
