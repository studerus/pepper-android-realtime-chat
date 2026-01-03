package ch.fhnw.pepper_realtime.ui

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import ch.fhnw.pepper_realtime.manager.DashboardManager
import ch.fhnw.pepper_realtime.manager.DrawingGameManager
import ch.fhnw.pepper_realtime.manager.EventRulesManager
import ch.fhnw.pepper_realtime.manager.FaceManager
import ch.fhnw.pepper_realtime.manager.MelodyManager
import ch.fhnw.pepper_realtime.manager.MemoryGameManager
import ch.fhnw.pepper_realtime.manager.NavigationManager
import ch.fhnw.pepper_realtime.manager.QuizGameManager
import ch.fhnw.pepper_realtime.manager.TicTacToeGameManager
import ch.fhnw.pepper_realtime.service.EventRuleEngine
import ch.fhnw.pepper_realtime.service.LocalFaceRecognitionService
import ch.fhnw.pepper_realtime.service.PerceptionWebSocketClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.whenever

/**
 * Unit tests for ChatViewModel.
 * Tests core functionality before refactoring to ensure no regressions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class ChatViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Mock
    private lateinit var mockApplication: android.app.Application

    @Mock
    private lateinit var ticTacToeGameManager: TicTacToeGameManager

    @Mock
    private lateinit var memoryGameManager: MemoryGameManager

    @Mock
    private lateinit var quizGameManager: QuizGameManager

    @Mock
    private lateinit var drawingGameManager: DrawingGameManager

    @Mock
    private lateinit var localFaceRecognitionService: LocalFaceRecognitionService

    @Mock
    private lateinit var perceptionWebSocketClient: PerceptionWebSocketClient

    @Mock
    private lateinit var eventRuleEngine: EventRuleEngine

    @Mock
    private lateinit var rulePersistence: ch.fhnw.pepper_realtime.data.RulePersistence

    // Use real managers instead of mocks (they manage their own StateFlow)
    private val melodyManager = MelodyManager()
    private val navigationManager = NavigationManager()
    private val dashboardManager = DashboardManager()
    private lateinit var eventRulesManager: EventRulesManager
    private lateinit var faceManager: FaceManager

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock rulePersistence to return empty list
        whenever(rulePersistence.loadRules()).thenReturn(emptyList())
        
        // Create managers with mocked dependencies
        eventRulesManager = EventRulesManager(eventRuleEngine, rulePersistence)
        faceManager = FaceManager(localFaceRecognitionService)

        viewModel = ChatViewModel(
            application = mockApplication,
            ticTacToeGameManager = ticTacToeGameManager,
            memoryGameManager = memoryGameManager,
            quizGameManager = quizGameManager,
            drawingGameManager = drawingGameManager,
            localFaceRecognitionService = localFaceRecognitionService,
            perceptionWebSocketClient = perceptionWebSocketClient,
            melodyManager = melodyManager,
            navigationManager = navigationManager,
            dashboardManager = dashboardManager,
            eventRulesManager = eventRulesManager,
            faceManager = faceManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Message Management Tests ==========

    @Test
    fun `addMessage adds message to list`() {
        val message = ChatMessage("Hello", ChatMessage.Sender.USER)

        viewModel.addMessage(message)

        assertEquals(1, viewModel.messageList.value.size)
        assertEquals("Hello", viewModel.messageList.value[0].message)
        assertEquals(ChatMessage.Sender.USER, viewModel.messageList.value[0].sender)
    }

    @Test
    fun `addMessage preserves message order`() {
        viewModel.addMessage(ChatMessage("First", ChatMessage.Sender.USER))
        viewModel.addMessage(ChatMessage("Second", ChatMessage.Sender.ROBOT))
        viewModel.addMessage(ChatMessage("Third", ChatMessage.Sender.USER))

        assertEquals(3, viewModel.messageList.value.size)
        assertEquals("First", viewModel.messageList.value[0].message)
        assertEquals("Second", viewModel.messageList.value[1].message)
        assertEquals("Third", viewModel.messageList.value[2].message)
    }

    @Test
    fun `appendToLastRobotMessage appends text to last robot message`() {
        viewModel.addMessage(ChatMessage("Hello", ChatMessage.Sender.ROBOT))

        viewModel.appendToLastRobotMessage(" World")

        assertEquals("Hello World", viewModel.messageList.value[0].message)
    }

    @Test
    fun `appendToLastRobotMessage does not append to user message`() {
        viewModel.addMessage(ChatMessage("User message", ChatMessage.Sender.USER))

        viewModel.appendToLastRobotMessage(" appended")

        // Should not modify user messages
        assertEquals("User message", viewModel.messageList.value[0].message)
    }

    @Test
    fun `appendToLastUserMessage appends text to last user message`() {
        viewModel.addMessage(ChatMessage("Hello", ChatMessage.Sender.USER))

        viewModel.appendToLastUserMessage(" World")

        assertEquals("Hello World", viewModel.messageList.value[0].message)
    }

    @Test
    fun `clearMessages removes all messages`() {
        viewModel.addMessage(ChatMessage("First", ChatMessage.Sender.USER))
        viewModel.addMessage(ChatMessage("Second", ChatMessage.Sender.ROBOT))

        viewModel.clearMessages()

        assertTrue(viewModel.messageList.value.isEmpty())
    }

    @Test
    fun `clearMessages also clears lastAssistantItemId`() {
        viewModel.lastAssistantItemId = "some-id"

        viewModel.clearMessages()

        assertNull(viewModel.lastAssistantItemId)
    }

    @Test
    fun `updateLastRobotMessage replaces text`() {
        viewModel.addMessage(ChatMessage("Old text", ChatMessage.Sender.ROBOT))

        viewModel.updateLastRobotMessage("New text")

        assertEquals("New text", viewModel.messageList.value[0].message)
    }

    // ========== State Management Tests ==========

    @Test
    fun `initial state is correct`() {
        assertFalse(viewModel.isWarmingUp.value)
        assertFalse(viewModel.isResponseGenerating.value)
        assertFalse(viewModel.isAudioPlaying.value)
        assertFalse(viewModel.isConnected.value)
        assertFalse(viewModel.isMuted.value)
        assertTrue(viewModel.userWantsMicOn.value)
        assertEquals("", viewModel.statusText.value)
    }

    @Test
    fun `setStatusText updates status`() {
        viewModel.setStatusText("Listening...")

        assertEquals("Listening...", viewModel.statusText.value)
    }

    @Test
    fun `setConnected updates connection state`() {
        viewModel.setConnected(true)

        assertTrue(viewModel.isConnected.value)

        viewModel.setConnected(false)

        assertFalse(viewModel.isConnected.value)
    }

    @Test
    fun `setMuted updates mute state`() {
        viewModel.setMuted(true)

        assertTrue(viewModel.isMuted.value)

        viewModel.setMuted(false)

        assertFalse(viewModel.isMuted.value)
    }

    @Test
    fun `setUserWantsMicOn updates user preference`() {
        viewModel.setUserWantsMicOn(false)

        assertFalse(viewModel.userWantsMicOn.value)

        viewModel.setUserWantsMicOn(true)

        assertTrue(viewModel.userWantsMicOn.value)
    }

    @Test
    fun `setWarmingUp updates warming state`() {
        viewModel.setWarmingUp(true)

        assertTrue(viewModel.isWarmingUp.value)
    }

    @Test
    fun `setResponseGenerating updates generating state`() {
        viewModel.setResponseGenerating(true)

        assertTrue(viewModel.isResponseGenerating.value)
    }

    @Test
    fun `setAudioPlaying updates audio playing state`() {
        viewModel.setAudioPlaying(true)

        assertTrue(viewModel.isAudioPlaying.value)
    }

    // ========== Navigation State Tests ==========

    @Test
    fun `showNavigationOverlay sets visible to true`() {
        viewModel.showNavigationOverlay()

        assertTrue(viewModel.navigationState.value.isVisible)
    }

    @Test
    fun `hideNavigationOverlay sets visible to false`() {
        viewModel.showNavigationOverlay()
        viewModel.hideNavigationOverlay()

        assertFalse(viewModel.navigationState.value.isVisible)
    }

    @Test
    fun `toggleNavigationOverlay toggles visibility`() {
        assertFalse(viewModel.navigationState.value.isVisible)

        viewModel.toggleNavigationOverlay()
        assertTrue(viewModel.navigationState.value.isVisible)

        viewModel.toggleNavigationOverlay()
        assertFalse(viewModel.navigationState.value.isVisible)
    }

    @Test
    fun `setLocalizationStatus updates status`() {
        viewModel.setLocalizationStatus("Localized")

        assertEquals("Localized", viewModel.navigationState.value.localizationStatus)
    }

    // ========== Dashboard State Tests ==========

    @Test
    fun `showDashboard sets visible and monitoring`() {
        viewModel.showDashboard()

        assertTrue(viewModel.dashboardState.value.isVisible)
        assertTrue(viewModel.dashboardState.value.isMonitoring)
    }

    @Test
    fun `hideDashboard clears visible and monitoring`() {
        viewModel.showDashboard()
        viewModel.hideDashboard()

        assertFalse(viewModel.dashboardState.value.isVisible)
        assertFalse(viewModel.dashboardState.value.isMonitoring)
    }

    @Test
    fun `toggleDashboard toggles visibility`() {
        assertFalse(viewModel.dashboardState.value.isVisible)

        viewModel.toggleDashboard()
        assertTrue(viewModel.dashboardState.value.isVisible)

        viewModel.toggleDashboard()
        assertFalse(viewModel.dashboardState.value.isVisible)
    }

    @Test
    fun `resetDashboard clears all dashboard state`() {
        viewModel.showDashboard()
        viewModel.resetDashboard()

        assertFalse(viewModel.dashboardState.value.isVisible)
        assertTrue(viewModel.dashboardState.value.humans.isEmpty())
    }

    // ========== Melody Player Tests ==========

    @Test
    fun `melody player initial state is not visible`() {
        assertFalse(viewModel.melodyPlayerState.value.isVisible)
        assertFalse(viewModel.melodyPlayerState.value.isPlaying)
    }

    @Test
    fun `dismissMelodyPlayer hides player`() {
        // We can't easily test startMelodyPlayer without mocking ToneGenerator coroutines
        // but we can test dismiss behavior
        viewModel.dismissMelodyPlayer()

        assertFalse(viewModel.melodyPlayerState.value.isVisible)
        assertFalse(viewModel.melodyPlayerState.value.isPlaying)
    }

    // ========== Response State Tests ==========

    @Test
    fun `currentResponseId can be set and retrieved`() {
        viewModel.currentResponseId = "response-123"

        assertEquals("response-123", viewModel.currentResponseId)
    }

    @Test
    fun `cancelledResponseId can be set and retrieved`() {
        viewModel.cancelledResponseId = "cancelled-456"

        assertEquals("cancelled-456", viewModel.cancelledResponseId)
    }

    @Test
    fun `isExpectingFinalAnswerAfterToolCall defaults to false`() {
        assertFalse(viewModel.isExpectingFinalAnswerAfterToolCall)
    }

    @Test
    fun `isExpectingFinalAnswerAfterToolCall can be toggled`() {
        viewModel.isExpectingFinalAnswerAfterToolCall = true
        assertTrue(viewModel.isExpectingFinalAnswerAfterToolCall)

        viewModel.isExpectingFinalAnswerAfterToolCall = false
        assertFalse(viewModel.isExpectingFinalAnswerAfterToolCall)
    }

    // ========== Video Streaming Tests ==========

    @Test
    fun `video stream initial state is inactive`() {
        assertFalse(viewModel.isVideoStreamActive.value)
        assertNull(viewModel.videoPreviewFrame.value)
    }

    @Test
    fun `setVideoStreamActive updates state`() {
        viewModel.setVideoStreamActive(true)

        assertTrue(viewModel.isVideoStreamActive.value)
    }

    // ========== Event Rules State Tests ==========

    @Test
    fun `event rules initial visibility is false`() {
        assertFalse(viewModel.eventRulesState.value.isVisible)
    }

    @Test
    fun `showEventRules sets visible to true`() {
        viewModel.showEventRules()

        assertTrue(viewModel.eventRulesState.value.isVisible)
    }

    @Test
    fun `hideEventRules sets visible to false`() {
        viewModel.showEventRules()
        viewModel.hideEventRules()

        assertFalse(viewModel.eventRulesState.value.isVisible)
    }

    @Test
    fun `toggleEventRules toggles visibility`() {
        viewModel.toggleEventRules()
        assertTrue(viewModel.eventRulesState.value.isVisible)

        viewModel.toggleEventRules()
        assertFalse(viewModel.eventRulesState.value.isVisible)
    }

    // ========== Partial Speech Result Tests ==========

    @Test
    fun `partial speech result initial state is null`() {
        assertNull(viewModel.partialSpeechResult.value)
    }

    @Test
    fun `setPartialSpeechResult updates state`() {
        viewModel.setPartialSpeechResult("Hello")

        assertEquals("Hello", viewModel.partialSpeechResult.value)
    }

    @Test
    fun `setPartialSpeechResult can be cleared with null`() {
        viewModel.setPartialSpeechResult("Hello")
        viewModel.setPartialSpeechResult(null)

        assertNull(viewModel.partialSpeechResult.value)
    }

    // ========== Google Audio Flag Tests ==========

    @Test
    fun `ignoreGoogleAudioUntilNextTurn initial state is false`() {
        assertFalse(viewModel.ignoreGoogleAudioUntilNextTurn.value)
    }

    @Test
    fun `setIgnoreGoogleAudio updates flag`() {
        viewModel.setIgnoreGoogleAudio(true)

        assertTrue(viewModel.ignoreGoogleAudioUntilNextTurn.value)

        viewModel.setIgnoreGoogleAudio(false)

        assertFalse(viewModel.ignoreGoogleAudioUntilNextTurn.value)
    }
}
