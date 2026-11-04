package ch.fhnw.pepper_realtime.network

import ch.fhnw.pepper_realtime.manager.MemoryGameManager
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GeminiLiveSessionTest {
    private val settings = mock<SettingsRepository>()
    private val socket = mock<WebSocket>()
    private val session = RealtimeSessionManager()
    private val messages = mutableListOf<JSONObject>()

    @Before
    fun setUp() {
        whenever(settings.model).thenReturn(GEMINI_38_LIVE_MODEL)
        whenever(settings.systemPrompt).thenReturn("")
        whenever(settings.preferredConversationLanguage).thenReturn("en")
        whenever(settings.enabledTools).thenReturn(emptySet())
        whenever(socket.send(any<String>())).thenAnswer {
            messages.add(JSONObject(it.getArgument<String>(0)))
            true
        }
        setField("settingsRepository", settings)
        setField("webSocket", socket)
        setField("connectionState", RealtimeSessionManager.ConnectionState.CONNECTED)
    }

    @Test
    fun `3_8 input transcription uses preference as hint without restricting output language`() {
        for ((code, hint) in listOf("gsw" to "de-CH", "de" to "de", "en" to "en", "es" to "es")) {
            whenever(settings.preferredConversationLanguage).thenReturn(code)
            val setup = setupPayload()
            assertEquals(listOf(hint), setup.getJSONObject("inputAudioTranscription").getJSONArray("languageCodes").toList())
            assertEquals(0, setup.getJSONObject("outputAudioTranscription").length())
            assertFalse(setup.getJSONObject("generationConfig").getJSONObject("speechConfig").has("languageCode"))
        }
    }

    @Test
    fun `no preference and older Gemini models retain automatic transcription language detection`() {
        whenever(settings.preferredConversationLanguage).thenReturn("none")
        assertEquals(0, setupPayload().getJSONObject("inputAudioTranscription").length())
        whenever(settings.preferredConversationLanguage).thenReturn("gsw")
        for (model in listOf("models/gemini-3.1-flash-live-preview", "models/gemini-2.5-flash-native-audio-preview-12-2025")) {
            whenever(settings.model).thenReturn(model)
            assertEquals(0, setupPayload().getJSONObject("inputAudioTranscription").length())
        }
    }

    @Test
    fun `Gemini setup includes conversation preference without changing saved prompt`() {
        whenever(settings.systemPrompt).thenReturn("Custom role")
        whenever(settings.preferredConversationLanguage).thenReturn("gsw")
        val prompt = setupPayload().getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(prompt.startsWith("Custom role\n\n"))
        assertTrue(prompt.contains("Speak Swiss German"))
        assertTrue(prompt.contains("Change language only if the user explicitly asks you to"))
        assertEquals("Custom role", settings.systemPrompt)
    }

    @Test
    fun `OpenAI Azure and xai session instructions include conversation preference`() {
        val registry = mock<ToolRegistry>()
        whenever(registry.buildToolsDefinitionForAzure(any(), any())).thenReturn(JSONArray())
        setField("toolRegistry", registry)
        setField("toolContext", mock<ToolContext>())
        whenever(settings.voice).thenReturn("ara")
        whenever(settings.model).thenReturn("gpt-realtime")
        whenever(settings.preferredConversationLanguage).thenReturn("fr")
        val method = RealtimeSessionManager::class.java
            .getDeclaredMethod("createSessionUpdatePayload", String::class.java).apply { isAccessible = true }
        for (provider in listOf(RealtimeApiProvider.OPENAI_DIRECT, RealtimeApiProvider.AZURE_OPENAI, RealtimeApiProvider.XAI)) {
            whenever(settings.apiProviderEnum).thenReturn(provider)
            val payload = method.invoke(session, "test") as JSONObject
            assertTrue(payload.getJSONObject("session").getString("instructions")
                .contains("Speak French throughout the conversation"))
        }
    }

    @Test
    fun `memory reveals and mismatches are silent while matches and completion request speech`() {
        val game = MemoryGameManager()
        val update: (String, Boolean) -> Unit = { text, respond ->
            assertTrue(session.sendGoogleTextMessage(text, respond))
        }
        game.startGame("easy", update)
        val cards = game.state.value.cards
        game.onCardClick(cards.first().id)
        game.onCardClick(cards.first { it.symbol != cards.first().symbol }.id)
        assertEquals(3, messages.size)
        assertTrue(messages.all { !it.getJSONObject("clientContent").getBoolean("turnComplete") })
        assertTrue(lastText().contains("two different cards"))
        game.dismissGame()

        messages.clear()
        game.startGame("easy", update)
        val pairs = game.state.value.cards.groupBy { it.symbol }.values
        pairs.forEach { pair ->
            game.onCardClick(pair[0].id)
            assertFalse(messages.last().getJSONObject("clientContent").getBoolean("turnComplete"))
            game.onCardClick(pair[1].id)
            assertTrue(messages.last().getJSONObject("clientContent").getBoolean("turnComplete"))
        }
        assertEquals(9, messages.size)
        assertTrue(lastText().contains("GAME COMPLETED"))
        assertFalse(game.state.value.isGameActive)
        game.dismissGame()
    }

    @Test
    fun `memory cancellation sends context and requests a Gemini response once`() {
        val game = MemoryGameManager()
        game.startGame("easy") { text, respond -> session.sendGoogleTextMessage(text, respond) }
        messages.clear()
        game.cancelGame()
        game.cancelGame()
        assertEquals(1, messages.size)
        assertTrue(messages.single().getJSONObject("clientContent").getBoolean("turnComplete"))
        assertTrue(lastText().contains("manually closed"))
    }

    @Test
    fun `3_1 still skips silent updates and sends realtime text`() {
        whenever(settings.model).thenReturn("models/gemini-3.1-flash-live-preview")
        assertTrue(session.sendGoogleTextMessage("silent", false))
        assertTrue(messages.isEmpty())
        assertTrue(session.sendGoogleTextMessage("hello", true))
        assertEquals("hello", messages.single().getJSONObject("realtimeInput").getString("text"))
    }

    @Test
    fun `3_8 setup omits thinking config even with saved thinking settings`() {
        whenever(settings.googleThinkingLevel).thenReturn("high")
        whenever(settings.googleThinkingBudget).thenReturn(1024)
        val setup = setupPayload()
        assertEquals(GEMINI_38_LIVE_MODEL, setup.getString("model"))
        assertFalse(setup.getJSONObject("generationConfig").has("thinkingConfig"))
        whenever(settings.model).thenReturn("models/gemini-3.1-flash-live-preview")
        assertEquals("high", setupPayload().getJSONObject("generationConfig")
            .getJSONObject("thinkingConfig").getString("thinkingLevel"))
        whenever(settings.model).thenReturn("models/gemini-2.5-flash-native-audio-preview-12-2025")
        assertEquals(1024, setupPayload().getJSONObject("generationConfig")
            .getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    }

    @Test
    fun `3_8 preserves silent tool scheduling`() {
        assertTrue(session.sendGoogleToolResult("call1", "analyze_vision", "{}", "SILENT"))
        val response = messages.single().getJSONObject("toolResponse")
            .getJSONArray("functionResponses").getJSONObject(0).getJSONObject("response")
        assertEquals("SILENT", response.getString("scheduling"))
    }

    @Test
    fun `vision image is returned once as a function response part on 3_1 and 3_8`() {
        val imageData = "aW1hZ2U="
        val parts = JSONArray().put(JSONObject().put("inlineData", JSONObject()
            .put("mimeType", "image/jpeg").put("data", imageData)))
        for (model in listOf("models/gemini-3.1-flash-live-preview", GEMINI_38_LIVE_MODEL)) {
            whenever(settings.model).thenReturn(model)
            messages.clear()
            assertTrue(RealtimeApiProvider.supportsGoogleImageToolResponse(model))
            assertTrue(session.sendGoogleToolResult("vision-call", "analyze_vision",
                """{"description":"Analyze the attached photo."}""", parts = parts))
            val message = messages.single()
            assertEquals(setOf("toolResponse"), message.keySet())
            val response = message.getJSONObject("toolResponse")
                .getJSONArray("functionResponses").getJSONObject(0)
            assertEquals("vision-call", response.getString("id"))
            assertEquals("analyze_vision", response.getString("name"))
            val image = response.getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData")
            assertEquals("image/jpeg", image.getString("mimeType"))
            assertEquals(imageData, image.getString("data"))
            val metadata = response.getJSONObject("response")
            assertFalse(metadata.has("scheduling"))
            assertFalse(metadata.toString().contains(imageData))
            assertEquals("Analyze the attached photo.", metadata.getJSONObject("result").getString("description"))
        }
        assertFalse(RealtimeApiProvider.supportsGoogleImageToolResponse("models/gemini-2.5-flash-native-audio-preview-12-2025"))
    }

    @Test
    fun `vision errors remain text only tool responses`() {
        assertTrue(session.sendGoogleToolResult("failed-call", "analyze_vision",
            """{"success":false,"error":"Camera unavailable"}"""))
        val response = messages.single().getJSONObject("toolResponse")
            .getJSONArray("functionResponses").getJSONObject(0)
        assertFalse(response.has("parts"))
        assertEquals("failed-call", response.getString("id"))
        assertFalse(response.getJSONObject("response").getBoolean("success"))
    }

    private fun lastText(): String = messages.last().getJSONObject("clientContent")
        .getJSONArray("turns").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")

    private fun setupPayload(): JSONObject = RealtimeSessionManager::class.java
        .getDeclaredMethod("createGoogleSetupPayload").apply { isAccessible = true }
        .invoke(session).let { (it as JSONObject).getJSONObject("setup") }

    private fun setField(name: String, value: Any) {
        RealtimeSessionManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(session, value)
        }
    }
}
