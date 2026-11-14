package ch.fhnw.pepper_realtime.controller

import android.util.Log
import android.os.Handler
import android.os.Looper
import ch.fhnw.pepper_realtime.tools.ChatWorkRegistry
import ch.fhnw.pepper_realtime.tools.OperationScope
import ch.fhnw.pepper_realtime.manager.NavigationServiceManager
import kotlinx.coroutines.CancellationException
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.di.ApplicationScope
import ch.fhnw.pepper_realtime.di.IoDispatcher
import ch.fhnw.pepper_realtime.manager.ApiKeyManager
import ch.fhnw.pepper_realtime.manager.AudioPlayer
import ch.fhnw.pepper_realtime.manager.SessionImageManager
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.network.RealtimeApiProvider
import ch.fhnw.pepper_realtime.network.RealtimeEventHandler
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import ch.fhnw.pepper_realtime.network.WebSocketConnectionCallback
import ch.fhnw.pepper_realtime.tools.interfaces.RealtimeMessageSender
import ch.fhnw.pepper_realtime.ui.ChatMessage
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Response
import okio.ByteString
import javax.inject.Inject

@ActivityScoped
class ChatSessionController @Inject constructor(
    private val viewModel: ChatViewModel,
    private val sessionManager: RealtimeSessionManager,
    private val settingsRepository: SettingsRepository,
    private val keyManager: ApiKeyManager,
    private val audioInputController: AudioInputController,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val gestureController: GestureController,
    private val turnManager: TurnManager?,
    private val interruptController: ChatInterruptController,
    private val audioPlayer: AudioPlayer,
    private val eventHandler: RealtimeEventHandler,
    private val sessionImageManager: SessionImageManager,
    private val workRegistry: ChatWorkRegistry,
    private val navigationServiceManager: NavigationServiceManager
) : RealtimeMessageSender {

    companion object {
        private const val TAG = "ChatSessionController"
    }

    // @Volatile ensures visibility across threads (connectWebSocket runs on different thread than WebSocket callbacks)
    @Volatile
    private var connectionCallback: WebSocketConnectionCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var restartOperation: OperationScope? = null
    @Volatile private var isRestarting = false // Flag to suppress side effects during restart

    init {
        setupSessionManagerListeners()
        setupAudioPlayerListener()
    }

    override fun sendMessageToRealtimeAPI(text: String, requestResponse: Boolean, allowInterrupt: Boolean) {
        val owner = workRegistry.current?.lifetime ?: return
        sendScopedMessage(text, requestResponse, allowInterrupt, owner)
    }

    override fun sendScopedMessage(text: String, requestResponse: Boolean, allowInterrupt: Boolean, operation: OperationScope) {
        val session = workRegistry.current ?: return
        operation.launchIn(applicationScope, ioDispatcher) {
            try {
                var waitForInterrupt = false
                val isGoogle = settingsRepository.apiProviderEnum.isGoogleProvider()
                val sent = operation.guard { session.lifetime.guard sessionGuard@{
                    if (!sessionManager.isConnected) return@sessionGuard false
                    if (allowInterrupt && requestResponse && turnManager?.state == TurnManager.State.SPEAKING &&
                        (viewModel.isAudioPlaying.value == true || audioPlayer.isPlaying())) interruptController.interruptSpeech()
                    if (requestResponse) turnManager?.setState(TurnManager.State.THINKING)
                    val sentItem = if (isGoogle) sessionManager.sendGoogleTextMessage(text, triggerResponse = requestResponse)
                        else sessionManager.sendUserTextMessage(text)
                    if (sentItem && requestResponse && viewModel.isResponseGenerating.value == true) {
                        interruptController.interruptSpeech()
                        waitForInterrupt = true
                    } else if (sentItem && requestResponse && viewModel.isAudioPlaying.value == true && allowInterrupt) {
                        audioPlayer.interruptNow()
                        viewModel.setAudioPlaying(false)
                    }
                    if (!sentItem) {
                        viewModel.addMessage(ChatMessage(viewModel.getApplication<android.app.Application>()
                            .getString(R.string.error_connection_lost_message), ChatMessage.Sender.ROBOT))
                        turnManager?.setState(TurnManager.State.LISTENING)
                    }
                    sentItem
                } }
                if (!sent) return@launchIn
                if (waitForInterrupt) delay(50)
                operation.runIfActive { session.lifetime.runIfActive {
                    if (requestResponse) {
                        viewModel.setResponseGenerating(true)
                        if (!isGoogle && !sessionManager.requestResponse()) {
                            viewModel.setResponseGenerating(false)
                            viewModel.addMessage(ChatMessage(viewModel.getApplication<android.app.Application>()
                                .getString(R.string.error_connection_lost_response), ChatMessage.Sender.ROBOT))
                        }
                    }
                } }
            } catch (_: CancellationException) {
                // Disconnection/restart invalidates both queued sends and delayed response requests.
            } catch (error: Exception) {
                operation.runIfActive { session.lifetime.runIfActive {
                    Log.e(TAG, "Exception sending chat message", error)
                    if (requestResponse) {
                        viewModel.setResponseGenerating(false)
                        viewModel.addMessage(ChatMessage(viewModel.getApplication<android.app.Application>()
                            .getString(R.string.error_processing_message), ChatMessage.Sender.ROBOT))
                        turnManager?.setState(TurnManager.State.LISTENING)
                    }
                } }
            }
        }
    }

    fun sendToolResult(callId: String, result: String, toolName: String? = null, requestResponse: Boolean = true) {
        if (!sessionManager.isConnected) return
        try {
            viewModel.isExpectingFinalAnswerAfterToolCall = requestResponse
            
            val isGoogle = settingsRepository.apiProviderEnum.isGoogleProvider()
            
            val sentTool = if (isGoogle && toolName != null) {
                // Google uses different format and continues automatically
                // For analyze_vision, use SILENT scheduling to avoid double response
                val scheduling = if (toolName == "analyze_vision") "SILENT" else null
                sessionManager.sendGoogleToolResult(callId, toolName, result, scheduling)
            } else {
                sessionManager.sendToolResult(callId, result)
            }
            
            if (!sentTool) {
                Log.e(TAG, "Failed to send tool result")
                return
            }
            
            // Google Live API continues generation automatically after toolResponse
            // OpenAI needs explicit response.create
            // Skip if requestResponse=false (tool already announced, no follow-up needed)
            if (!isGoogle && requestResponse) {
                viewModel.setResponseGenerating(true)
                val sentToolResponse = sessionManager.requestResponse()
                if (!sentToolResponse) {
                    viewModel.setResponseGenerating(false)
                    Log.e(TAG, "Failed to send tool response request")
                    return
                }
                if (turnManager != null && turnManager.state != TurnManager.State.SPEAKING) {
                    turnManager.setState(TurnManager.State.THINKING)
                }
            } else if (!requestResponse) {
                // No response requested - transition to LISTENING after audio finishes
                // Don't set responseGenerating=true since we're not expecting a response
                Log.d(TAG, "Tool completed without requesting response, will return to LISTENING after audio")
            } else {
                // Google: generating continues automatically
                viewModel.setResponseGenerating(true)
                if (turnManager != null && turnManager.state != TurnManager.State.SPEAKING) {
                    turnManager.setState(TurnManager.State.THINKING)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending tool result", e)
            viewModel.setResponseGenerating(false)
        }
    }

    fun startNewSession() {
        Log.i(TAG, "Starting new session...")
        disconnectWebSocketGracefully()
        val restart = OperationScope()
        restartOperation = restart
        isRestarting = true
        if (audioInputController.isMuted) {
            audioInputController.resetMuteState()
        }
        viewModel.lastChatBubbleResponseId = null

        viewModel.setStatusText(viewModel.getApplication<android.app.Application>().getString(R.string.status_starting_new_session))
        viewModel.clearMessages()

        restart.launchIn(applicationScope, ioDispatcher) {
            // Delete images in background
            launch { sessionImageManager.deleteAllImages() }
            
            audioInputController.cleanupForRestart()
            gestureController.stopNow()
            
            // Stop and clear any ongoing audio playback immediately
            audioPlayer.interruptNow()
            
            turnManager?.setState(TurnManager.State.IDLE)

            // Reset flags via ViewModel
            viewModel.setResponseGenerating(false)
            viewModel.setAudioPlaying(false)
            viewModel.currentResponseId = null
            viewModel.cancelledResponseId = null
            viewModel.lastChatBubbleResponseId = null
            viewModel.isExpectingFinalAnswerAfterToolCall = false

            delay(500)
            restart.ensureActive()

            connectWebSocket(object : WebSocketConnectionCallback {
                override fun onSuccess() {
                    if (!restart.isActive) return
                    isRestarting = false
                    Log.i(TAG, "New session started successfully.")
                    if (!settingsRepository.isUsingRealtimeAudioInput) {
                        // Azure Speech Mode - perform warmup like initial startup
                        Log.i(TAG, "Azure Speech mode - starting STT warmup...")
                        viewModel.setWarmingUp(true)
                        audioInputController.startWarmup()
                        restart.launchIn(applicationScope, ioDispatcher) {
                            try {
                                audioInputController.setupSpeechRecognizer()
                                // Don't set LISTENING state here - it will be set by
                                // ChatSpeechListener.onStarted() after warmup completes
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to setup Azure Speech after session restart", e)
                                viewModel.setWarmingUp(false)
                                viewModel.setStatusText(
                                    viewModel.getApplication<android.app.Application>().getString(R.string.status_recognizer_not_ready)
                                )
                            }
                        }
                    } else {
                        // Realtime API Mode - set LISTENING state and update status text
                        turnManager?.setState(TurnManager.State.LISTENING)
                        viewModel.setStatusText(
                            viewModel.getApplication<android.app.Application>().getString(R.string.status_listening)
                        )
                    }
                }

                override fun onError(error: Throwable) {
                    if (!restart.isActive) return
                    isRestarting = false
                    if (error.message?.contains("WebSocket closed before session was updated") == true) {
                        Log.w(TAG, "Harmless race condition during new session start")
                    } else {
                        Log.e(TAG, "Failed to start new session", error)
                        viewModel.addMessage(
                            ChatMessage(
                                viewModel.getApplication<android.app.Application>().getString(R.string.new_session_error),
                                ChatMessage.Sender.ROBOT
                            )
                        )
                        viewModel.setStatusText(
                            viewModel.getApplication<android.app.Application>().getString(R.string.error_connection_failed_short)
                        )
                    }
                }
            }, restart)
        }
    }

    @Synchronized
    fun connectWebSocket(callback: WebSocketConnectionCallback?, attempt: OperationScope? = null) {
        if (attempt?.isActive == false) return
        if (sessionManager.isConnected) {
            callback?.onSuccess()
            return
        }

        val session = workRegistry.begin()
        setupSessionManagerListeners(session)
        clearChatActivities(session)
        this.connectionCallback = callback

        try {
            val provider = settingsRepository.apiProviderEnum
            val selectedModel = settingsRepository.model
            val azureEndpoint = keyManager.azureOpenAiEndpoint

            // Google uses API key in URL query param, others use headers
            val url = provider.getWebSocketUrl(
                azureEndpoint, 
                selectedModel, 
                if (provider.isGoogleProvider()) keyManager.googleApiKey else null
            )

            // Set up event handler for the provider type
            eventHandler.isGoogleProvider = provider.isGoogleProvider()

            // Build headers (Google doesn't need auth headers - key is in URL)
            val headers = HashMap<String, String>()
            if (provider.requiresAuthHeader()) {
                if (provider.isAzureProvider()) {
                    headers["api-key"] = keyManager.azureOpenAiKey
                } else {
                    val headerName = provider.getAuthHeaderName()
                    val headerValue = provider.getAuthorizationHeader(
                        keyManager.azureOpenAiKey, 
                        keyManager.openAiApiKey, 
                        keyManager.xaiApiKey
                    )
                    if (headerName.isNotEmpty() && headerValue.isNotEmpty()) {
                        headers[headerName] = headerValue
                    }
                }
                // Add OpenAI-Beta header only for legacy preview models.
                // GA models (e.g., gpt-realtime, gpt-realtime-mini, gpt-realtime-1.5, gpt-realtime-2,
                // gpt-realtime-2.1, gpt-realtime-2.1-mini) must not send this header.
                if (
                    provider == RealtimeApiProvider.OPENAI_DIRECT &&
                    !RealtimeApiProvider.isOpenAiGaRealtimeModel(selectedModel)
                ) {
                    headers["OpenAI-Beta"] = "realtime=v1"
                }
            }

            val headersOrNull = if (headers.isEmpty()) null else headers
            sessionManager.connect(url, headersOrNull)

        } catch (e: Exception) {
            Log.e(TAG, "Error generating WebSocket connection parameters", e)
            workRegistry.end()
            callback?.onError(e)
        }
    }

    @Synchronized
    fun disconnectWebSocket() {
        stopChatWork()
        Log.i(TAG, "Disconnecting WebSocket...")
        sessionManager.close(1000, "User initiated disconnect")
    }

    @Synchronized
    fun disconnectWebSocketGracefully() {
        stopChatWork()
        Log.i(TAG, "Gracefully disconnecting WebSocket...")
        this.connectionCallback = null

        val isGenerating = viewModel.isResponseGenerating.value == true
        val isPlaying = viewModel.isAudioPlaying.value == true
        val currentId = viewModel.currentResponseId

        if ((isGenerating || isPlaying) && currentId != null) {
            Log.d(TAG, "Cancelling active response before disconnect")
            viewModel.cancelledResponseId = currentId
            viewModel.setResponseGenerating(false)
            viewModel.setAudioPlaying(false)
            viewModel.currentResponseId = null
        }
        sessionManager.close(1000, "Provider switch")
    }

    /**
     * Send a user image to the API context without requesting a response.
     * Used by DrawingGame to silently add drawings to the conversation context.
     * @param base64 Base64-encoded image data
     * @param mime MIME type (e.g., "image/png")
     * @return true if sent successfully
     */
    fun sendUserImageToContext(base64: String, mime: String): Boolean {
        if (!sessionManager.isConnected) {
            Log.e(TAG, "Cannot send image - WebSocket not connected")
            return false
        }
        
        val isGoogle = settingsRepository.apiProviderEnum.isGoogleProvider()
        return if (isGoogle) {
            // Google Live API: use realtimeInput.media (streaming format, no response triggered)
            // This is like sending a video frame - adds to context silently
            sessionManager.sendGoogleMediaFrame(base64, mime)
        } else {
            // OpenAI Realtime API: use conversation.item.create (no response.create = context only)
            sessionManager.sendUserImageMessage(base64, mime)
        }
    }

    @Synchronized
    fun stopChatWork() {
        restartOperation?.cancel()
        restartOperation = null
        workRegistry.end()
        try { navigationServiceManager.cancelAllActions() } catch (error: Exception) { Log.w(TAG, "Navigation cleanup failed", error) }
        try { gestureController.stopNow() } catch (error: Exception) { Log.w(TAG, "Gesture cleanup failed", error) }
        clearChatActivities(null)
    }

    private fun clearChatActivities(expected: ChatWorkRegistry.Session?) {
        val clear = Runnable {
            if (workRegistry.current === expected) viewModel.stopChatActivities()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) clear.run() else mainHandler.post(clear)
    }

    private fun inSession(session: ChatWorkRegistry.Session?, action: () -> Unit) = synchronized(this) {
        if (session != null && workRegistry.current === session && session.lifetime.isActive) action()
    }

    private fun setupSessionManagerListeners(session: ChatWorkRegistry.Session? = null) {
        sessionManager.setSessionConfigCallback { success, error ->
            inSession(session) {
                if (success) {
                    Log.i(TAG, "Session configured successfully - completing connection promise")
                    completeConnectionPromise()
                } else {
                    Log.e(TAG, "Session configuration failed: $error")
                    failConnectionPromise("Session config failed: $error")
                }
            }
        }
        sessionManager.listener = object : RealtimeSessionManager.Listener {
            override fun onOpen(response: Response) {
                Log.i(TAG, "WebSocket onOpen() - configuring initial session")
                inSession(session) { sessionManager.configureInitialSession() }
            }

            override fun onTextMessage(text: String) {
                inSession(session) { eventHandler.handle(text) }
            }

            override fun onBinaryMessage(bytes: ByteString) {
                // Google Live API sends binary messages - decode as text JSON
                val text = bytes.utf8()
                if (text.startsWith("{") || text.startsWith("[")) {
                    inSession(session) { eventHandler.handle(text) }
                }
            }

            override fun onClosing(code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                inSession(session) { handleConnectionLost(reason, code) }
            }

            override fun onFailure(t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure: ${t.message}")
                inSession(session) { handleConnectionLost(t.message ?: "Unknown error", null) }
            }
        }
    }

    @Synchronized
    private fun handleConnectionLost(reason: String, code: Int?) {
        stopChatWork()
        // Stop audio input to prevent endless loop of failed sends
        audioInputController.cleanupForRestart()
        
        // Stop any ongoing playback
        audioPlayer.interruptNow()
        viewModel.setAudioPlaying(false)
        viewModel.setResponseGenerating(false)
        
        // Set turn manager to IDLE
        turnManager?.setState(TurnManager.State.IDLE)
        
        // Show error message for unexpected disconnects (not during restart or explicit close)
        if (!isRestarting && code != 1000) {
            val errorMsg = if (code == 1007) {
                // Protocol error - likely invalid message format
                viewModel.getApplication<android.app.Application>().getString(R.string.error_connection_protocol, reason)
            } else {
                viewModel.getApplication<android.app.Application>().getString(R.string.error_connection_lost)
            }
            viewModel.addMessage(ChatMessage(errorMsg, ChatMessage.Sender.ROBOT))
            viewModel.setStatusText(
                viewModel.getApplication<android.app.Application>().getString(R.string.error_disconnected)
            )
        }
        
        failConnectionPromise("Connection lost: $reason")
    }

    private fun setupAudioPlayerListener() {
        audioPlayer.setListener(object : AudioPlayer.Listener {
            override fun onPlaybackStarted() {
                if (!isRestarting) {
                    turnManager?.setState(TurnManager.State.SPEAKING)
                }
            }

            override fun onPlaybackFinished() {
                viewModel.setAudioPlaying(false)
                if (turnManager != null && !isRestarting) {
                    if (!viewModel.isExpectingFinalAnswerAfterToolCall && viewModel.isResponseGenerating.value != true) {
                        turnManager.setState(TurnManager.State.LISTENING)
                    } else {
                        turnManager.setState(TurnManager.State.THINKING)
                    }
                }
            }
        })
    }

    @Synchronized
    fun failConnectionPromise(message: String) {
        // Only fail if we're not in the middle of restarting (to prevent old connection's onClosed from clearing new callback)
        if (!isRestarting) {
            connectionCallback?.onError(Exception(message))
            connectionCallback = null
        }
    }

    @Synchronized
    fun completeConnectionPromise() {
        connectionCallback?.onSuccess()
        connectionCallback = null
    }
}
