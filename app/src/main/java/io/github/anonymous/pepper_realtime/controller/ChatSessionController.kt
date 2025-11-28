package io.github.anonymous.pepper_realtime.controller

import android.util.Log
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.di.ApplicationScope
import io.github.anonymous.pepper_realtime.di.IoDispatcher
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.manager.AudioPlayer
import io.github.anonymous.pepper_realtime.manager.SessionImageManager
import io.github.anonymous.pepper_realtime.manager.SettingsRepository
import io.github.anonymous.pepper_realtime.manager.TurnManager
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager
import io.github.anonymous.pepper_realtime.network.WebSocketConnectionCallback
import io.github.anonymous.pepper_realtime.tools.interfaces.RealtimeMessageSender
import io.github.anonymous.pepper_realtime.ui.ChatMessage
import io.github.anonymous.pepper_realtime.ui.ChatViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Response
import okio.ByteString
import javax.inject.Inject

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
    private val sessionImageManager: SessionImageManager
) : RealtimeMessageSender {

    companion object {
        private const val TAG = "ChatSessionController"
    }

    private var connectionCallback: WebSocketConnectionCallback? = null
    private var isRestarting = false // Flag to suppress side effects during restart

    init {
        setupSessionManagerListeners()
        setupAudioPlayerListener()
    }

    override fun sendMessageToRealtimeAPI(text: String, requestResponse: Boolean, allowInterrupt: Boolean) {
        if (!sessionManager.isConnected) {
            Log.e(TAG, "WebSocket is not connected.")
            if (requestResponse) {
                viewModel.addMessage(
                    ChatMessage(
                        viewModel.getApplication<android.app.Application>().getString(R.string.error_not_connected),
                        ChatMessage.Sender.ROBOT
                    )
                )
            }
            return
        }

        if (allowInterrupt && requestResponse && turnManager?.state == TurnManager.State.SPEAKING) {
            // Simple explicit interrupt via controller if playing
            if (viewModel.isAudioPlaying.value == true || audioPlayer.isPlaying()) {
                interruptController.interruptSpeech()
            }
        }

        // Capture for use in lambda
        val shouldRequestResponse = requestResponse

        applicationScope.launch(ioDispatcher) {
            try {
                // Set THINKING state inside the network task to ensure it only happens
                // when the task actually runs
                if (shouldRequestResponse) {
                    turnManager?.setState(TurnManager.State.THINKING)
                }

                val sentItem = sessionManager.sendUserTextMessage(text)
                if (!sentItem) {
                    Log.e(TAG, "Failed to send message - WebSocket connection broken")
                    viewModel.addMessage(
                        ChatMessage(
                            viewModel.getApplication<android.app.Application>().getString(R.string.error_connection_lost_message),
                            ChatMessage.Sender.ROBOT
                        )
                    )
                    turnManager?.setState(TurnManager.State.LISTENING)
                    return@launch
                }

                if (requestResponse) {
                    if (viewModel.isResponseGenerating.value == true) {
                        interruptController.interruptSpeech()
                        delay(50)
                    } else if (viewModel.isAudioPlaying.value == true && allowInterrupt) {
                        audioPlayer.interruptNow()
                        viewModel.setAudioPlaying(false)
                    }

                    viewModel.setResponseGenerating(true)
                    val sentResponse = sessionManager.requestResponse()
                    if (!sentResponse) {
                        viewModel.setResponseGenerating(false)
                        Log.e(TAG, "Failed to send response request")
                        viewModel.addMessage(
                            ChatMessage(
                                viewModel.getApplication<android.app.Application>().getString(R.string.error_connection_lost_response),
                                ChatMessage.Sender.ROBOT
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in sendMessageToRealtimeAPI", e)
                if (shouldRequestResponse) {
                    viewModel.setResponseGenerating(false)
                    viewModel.addMessage(
                        ChatMessage(
                            viewModel.getApplication<android.app.Application>().getString(R.string.error_processing_message),
                            ChatMessage.Sender.ROBOT
                        )
                    )
                    turnManager?.setState(TurnManager.State.LISTENING)
                }
            }
        }
    }

    fun sendToolResult(callId: String, result: String) {
        if (!sessionManager.isConnected) return
        try {
            viewModel.isExpectingFinalAnswerAfterToolCall = true
            val sentTool = sessionManager.sendToolResult(callId, result)
            if (!sentTool) {
                Log.e(TAG, "Failed to send tool result")
                return
            }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error sending tool result", e)
            viewModel.setResponseGenerating(false)
        }
    }

    fun startNewSession() {
        Log.i(TAG, "Starting new session...")
        isRestarting = true
        if (audioInputController.isMuted) {
            audioInputController.resetMuteState()
        }
        viewModel.lastChatBubbleResponseId = null

        viewModel.setStatusText(viewModel.getApplication<android.app.Application>().getString(R.string.status_starting_new_session))
        viewModel.clearMessages()

        applicationScope.launch(ioDispatcher) {
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

            disconnectWebSocketGracefully()
            delay(500)

            connectWebSocket(object : WebSocketConnectionCallback {
                override fun onSuccess() {
                    isRestarting = false
                    Log.i(TAG, "New session started successfully.")
                    if (!settingsRepository.isUsingRealtimeAudioInput) {
                        // Azure Speech Mode - perform warmup like initial startup
                        Log.i(TAG, "Azure Speech mode - starting STT warmup...")
                        viewModel.setWarmingUp(true)
                        audioInputController.startWarmup()
                        applicationScope.launch(ioDispatcher) {
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
                        // Realtime API Mode
                        // Just set LISTENING state - ChatTurnListener will trigger audio start
                        turnManager?.setState(TurnManager.State.LISTENING)
                    }
                }

                override fun onError(error: Throwable) {
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
            })
        }
    }

    fun connectWebSocket(callback: WebSocketConnectionCallback?) {
        if (sessionManager.isConnected) {
            callback?.onSuccess()
            return
        }

        this.connectionCallback = callback

        try {
            val provider = settingsRepository.apiProviderEnum
            val selectedModel = settingsRepository.model
            val azureEndpoint = keyManager.azureOpenAiEndpoint

            val url = provider.getWebSocketUrl(azureEndpoint, selectedModel)

            val headers = HashMap<String, String>()
            if (provider.isAzureProvider()) {
                headers["api-key"] = keyManager.azureOpenAiKey
            } else {
                headers["Authorization"] = provider.getAuthorizationHeader(keyManager.azureOpenAiKey, keyManager.openAiApiKey)
            }
            if (selectedModel != "gpt-realtime") {
                headers["OpenAI-Beta"] = "realtime=v1"
            }

            sessionManager.connect(url, headers)

        } catch (e: Exception) {
            Log.e(TAG, "Error generating WebSocket connection parameters", e)
            callback?.onError(e)
        }
    }

    fun disconnectWebSocket() {
        Log.i(TAG, "Disconnecting WebSocket...")
        sessionManager.close(1000, "User initiated disconnect")
    }

    fun disconnectWebSocketGracefully() {
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

    private fun setupSessionManagerListeners() {
        sessionManager.setSessionConfigCallback { success, error ->
            if (success) {
                Log.i(TAG, "Session configured successfully - completing connection promise")
                completeConnectionPromise()
            } else {
                Log.e(TAG, "Session configuration failed: $error")
                failConnectionPromise("Session config failed: $error")
            }
        }
        sessionManager.listener = object : RealtimeSessionManager.Listener {
            override fun onOpen(response: Response) {
                Log.i(TAG, "WebSocket onOpen() - configuring initial session")
                sessionManager.configureInitialSession()
            }

            override fun onTextMessage(text: String) {
                eventHandler.handle(text)
            }

            override fun onBinaryMessage(bytes: ByteString) {
                // Handle audio input buffer if needed
            }

            override fun onClosing(code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                failConnectionPromise("Connection closed: $reason")
            }

            override fun onFailure(t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure: ${t.message}")
                failConnectionPromise("Connection failed: ${t.message}")
            }
        }
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

    fun failConnectionPromise(message: String) {
        connectionCallback?.onError(Exception(message))
        connectionCallback = null
    }

    fun completeConnectionPromise() {
        connectionCallback?.onSuccess()
        connectionCallback = null
    }
}
