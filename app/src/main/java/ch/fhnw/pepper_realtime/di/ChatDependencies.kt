package ch.fhnw.pepper_realtime.di

import ch.fhnw.pepper_realtime.controller.AudioInputController
import ch.fhnw.pepper_realtime.controller.ChatInterruptController
import ch.fhnw.pepper_realtime.controller.ChatSessionController
import ch.fhnw.pepper_realtime.controller.ChatTurnListener
import ch.fhnw.pepper_realtime.manager.AudioPlayer
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.network.RealtimeEventHandler
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager

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

