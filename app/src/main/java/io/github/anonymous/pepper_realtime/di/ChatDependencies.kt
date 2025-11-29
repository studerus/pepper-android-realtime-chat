package io.github.anonymous.pepper_realtime.di

import io.github.anonymous.pepper_realtime.controller.AudioInputController
import io.github.anonymous.pepper_realtime.controller.ChatInterruptController
import io.github.anonymous.pepper_realtime.controller.ChatSessionController
import io.github.anonymous.pepper_realtime.controller.ChatTurnListener
import io.github.anonymous.pepper_realtime.manager.AudioPlayer
import io.github.anonymous.pepper_realtime.manager.TurnManager
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager

/**
 * Groups all chat/conversation-related dependencies.
 * Includes session management, audio handling, and turn management.
 */
data class ChatDependencies(
    val sessionController: ChatSessionController,
    val sessionManager: RealtimeSessionManager,
    val turnManager: TurnManager,
    val audioInputController: AudioInputController,
    val audioPlayer: AudioPlayer,
    val interruptController: ChatInterruptController,
    val eventHandler: RealtimeEventHandler,
    val turnListener: ChatTurnListener
)

