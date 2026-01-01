package ch.fhnw.pepper_realtime.ui.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.fhnw.pepper_realtime.manager.ApiKeyManager
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.network.RealtimeApiProvider
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import ch.fhnw.pepper_realtime.ui.compose.ChatTheme
import ch.fhnw.pepper_realtime.ui.settings.SettingsViewModel

/**
 * Main Settings Screen composable that replaces view_settings.xml
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    apiKeyManager: ApiKeyManager,
    toolRegistry: ToolRegistry,
    onSettingsChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Collect settings state from ViewModel
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    
    // Local state for text fields - changes are applied immediately to ViewModel (batched)
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var transcriptionLanguage by remember(settings.transcriptionLanguage) { mutableStateOf(settings.transcriptionLanguage) }
    var transcriptionPrompt by remember(settings.transcriptionPrompt) { mutableStateOf(settings.transcriptionPrompt) }
    var idleTimeoutText by remember(settings.idleTimeout) { mutableStateOf(settings.idleTimeout?.toString() ?: "") }
    var enabledTools by remember(settings.enabledTools) { mutableStateOf(settings.enabledTools) }
    
    // Apply text field changes immediately when they change (ViewModel batches them)
    LaunchedEffect(systemPrompt) { viewModel.setSystemPrompt(systemPrompt) }
    LaunchedEffect(transcriptionLanguage) { viewModel.setTranscriptionLanguage(transcriptionLanguage) }
    LaunchedEffect(transcriptionPrompt) { viewModel.setTranscriptionPrompt(transcriptionPrompt) }
    LaunchedEffect(idleTimeoutText) { idleTimeoutText.toIntOrNull()?.let { viewModel.setIdleTimeout(it) } }
    LaunchedEffect(enabledTools) { viewModel.setEnabledTools(enabledTools) }
    
    // Derived values
    val isRealtimeMode = settings.audioInputMode == SettingsRepository.MODE_REALTIME_API
    val isServerVad = settings.turnDetectionType == "server_vad"
    val isXaiProvider = settings.apiProvider == RealtimeApiProvider.XAI.name
    val isGoogleProvider = settings.apiProvider == RealtimeApiProvider.GOOGLE_GEMINI.name
    
    // Options - dynamic based on provider
    val openAiModels = listOf("gpt-realtime", "gpt-realtime-mini", "gpt-4o-realtime-preview", "gpt-4o-mini-realtime-preview")
    val xaiModels = listOf("Grok Voice Agent")
    // Google Live API requires models/ prefix for BidiGenerateContent
    // Include fallback model for debugging
    val googleModels = listOf(
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "models/gemini-2.0-flash-live-001"  // Fallback - known to work
    )
    val models = when {
        isGoogleProvider -> googleModels
        isXaiProvider -> xaiModels
        else -> openAiModels
    }
    // Voice options with descriptions for each provider
    val openAiVoices = linkedMapOf(
        "alloy" to "Neutral, balanced",
        "ash" to "Clear, precise",
        "ballad" to "Melodic, smooth",
        "cedar" to "Natural, clear",
        "coral" to "Warm, friendly",
        "echo" to "Resonant, deep",
        "marin" to "Natural, clear",
        "sage" to "Calm, thoughtful",
        "shimmer" to "Bright, energetic",
        "verse" to "Versatile, expressive"
    )
    val xaiVoices = linkedMapOf(
        "Ara" to "Female, warm, friendly",
        "Rex" to "Male, confident, clear",
        "Sal" to "Neutral, smooth, balanced",
        "Eve" to "Female, energetic, upbeat",
        "Leo" to "Male, authoritative, strong"
    )
    val googleVoices = linkedMapOf(
        "Zephyr" to "Bright",
        "Puck" to "Upbeat",
        "Charon" to "Informative",
        "Kore" to "Firm",
        "Fenrir" to "Excitable",
        "Leda" to "Youthful",
        "Orus" to "Firm",
        "Aoede" to "Breezy",
        "Callirrhoe" to "Easy-going",
        "Autonoe" to "Bright",
        "Enceladus" to "Breathy",
        "Iapetus" to "Clear",
        "Umbriel" to "Easy-going",
        "Algieba" to "Smooth",
        "Despina" to "Smooth",
        "Erinome" to "Clear",
        "Algenib" to "Gravelly",
        "Rasalgethi" to "Informative",
        "Laomedeia" to "Upbeat",
        "Achernar" to "Soft",
        "Alnilam" to "Firm",
        "Schedar" to "Even",
        "Gacrux" to "Mature",
        "Pulcherrima" to "Forward",
        "Achird" to "Friendly",
        "Zubenelgenubi" to "Casual",
        "Vindemiatrix" to "Gentle",
        "Sadachbia" to "Lively",
        "Sadaltager" to "Knowledgeable",
        "Sulafat" to "Warm"
    )
    // Dynamic voice map based on provider
    val voices = when {
        isGoogleProvider -> googleVoices
        isXaiProvider -> xaiVoices
        else -> openAiVoices
    }
    val configuredProviders = remember { apiKeyManager.getConfiguredProviders() }
    val languages = SettingsRepository.getAvailableLanguages()
    val audioInputModes = listOf("Direct Audio", "Azure Speech")
    val transcriptionModels = listOf("whisper-1", "gpt-4o-mini-transcribe", "gpt-4o-transcribe", "gpt-4o-transcribe-diarize")
    val turnDetectionTypes = listOf("Server VAD", "Semantic VAD")
    val eagernessLevels = listOf("Auto (Medium)", "Low (Max 8s)", "Medium (Max 4s)", "High (Max 2s)")
    val noiseReductionTypes = listOf("Off", "Near Field", "Far Field")
    
    // Helper to get provider display name
    fun getProviderDisplayName(providerName: String): String {
        return try {
            RealtimeApiProvider.valueOf(providerName).getDisplayName()
        } catch (e: IllegalArgumentException) {
            providerName
        }
    }
    
    // Helper to get provider enum name from display name
    fun getProviderEnumName(displayName: String): String {
        return configuredProviders.find { it.getDisplayName() == displayName }?.name ?: displayName
    }
    
    // Note: Text field changes are now applied immediately via LaunchedEffect above
    // commitChanges() is called from MainScreen when drawer closes
    
    ChatTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "⚙️ Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Volume - apply immediately (at top for quick access)
            SettingsSlider(
                label = "Volume",
                value = settings.volume.toFloat(),
                onValueChange = { viewModel.setVolume(it.toInt()) },
                valueRange = 0f..100f,
                valueDisplay = "${settings.volume}%"
            )
            
            // System Prompt
            ExpandableSettingsCard(title = "System Prompt") {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    placeholder = { Text("e.g., You are a helpful assistant.", color = Color.Gray) },
                    singleLine = false,
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Model - apply immediately
            SettingsDropdown(
                label = "Model",
                options = models,
                selectedOption = settings.model,
                onOptionSelected = { viewModel.setModel(it) }
            )
            
            // API Provider - apply immediately, show display names
            if (configuredProviders.isNotEmpty()) {
                SettingsSegmentedButton(
                    label = "API Provider",
                    options = configuredProviders.map { it.getDisplayName() },
                    selectedOption = getProviderDisplayName(settings.apiProvider),
                    onOptionSelected = { displayName ->
                        val enumName = getProviderEnumName(displayName)
                        val isNewProviderXai = enumName == RealtimeApiProvider.XAI.name
                        val isNewProviderGoogle = enumName == RealtimeApiProvider.GOOGLE_GEMINI.name
                        viewModel.setApiProvider(enumName)
                        
                        // Auto-switch model if current model is not available for new provider
                        val newModels = when {
                            isNewProviderGoogle -> googleModels
                            isNewProviderXai -> xaiModels
                            else -> openAiModels
                        }
                        if (settings.model !in newModels) {
                            viewModel.setModel(newModels.first())
                        }
                        
                        // Auto-switch voice if current voice is not available for new provider
                        val newVoices = when {
                            isNewProviderGoogle -> googleVoices
                            isNewProviderXai -> xaiVoices
                            else -> openAiVoices
                        }
                        if (settings.voice !in newVoices.keys) {
                            viewModel.setVoice(newVoices.keys.first())
                        }
                    }
                )
            }
            
            // Voice - apply immediately (with descriptions)
            SettingsVoiceDropdown(
                label = "Voice",
                voices = voices,
                selectedVoice = settings.voice,
                onVoiceSelected = { viewModel.setVoice(it) }
            )
            
            // Speed and Temperature - not supported by Google Live API
            if (!isGoogleProvider) {
                SettingsSlider(
                    label = "Speech Speed",
                    value = settings.speedProgress.toFloat(),
                    onValueChange = { viewModel.setSpeedProgress(it.toInt()) },
                    valueRange = 25f..150f,
                    valueDisplay = "%.2fx".format(settings.speedProgress / 100f)
                )
                
                SettingsSlider(
                    label = "Temperature",
                    value = settings.temperatureProgress.toFloat(),
                    onValueChange = { viewModel.setTemperatureProgress(it.toInt()) },
                    valueRange = 0f..100f,
                    valueDisplay = "%.2f".format(0.6f + (settings.temperatureProgress / 100f) * 0.6f)
                )
            }
            
            // Audio Input Mode - apply immediately
            SettingsSegmentedButton(
                label = "Audio Input",
                options = audioInputModes,
                selectedOption = if (isRealtimeMode) audioInputModes[0] else audioInputModes[1],
                onOptionSelected = { 
                    val newMode = if (it == audioInputModes[0]) 
                        SettingsRepository.MODE_REALTIME_API 
                    else 
                        SettingsRepository.MODE_AZURE_SPEECH
                    viewModel.setAudioInputMode(newMode)
                }
            )
            
            // Azure Speech Settings (shown when Azure mode selected)
            if (!isRealtimeMode) {
                SettingsSectionHeader(title = "Azure Speech Settings")
                
                SettingsDropdown(
                    label = "Speech Language",
                    options = languages.map { it.displayName },
                    selectedOption = languages.find { it.code == settings.language }?.displayName ?: "",
                    onOptionSelected = { name ->
                        languages.find { it.displayName == name }?.let { 
                            viewModel.setLanguage(it.code)
                        }
                    }
                )
                
                SettingsSlider(
                    label = "Silence Timeout (ms)",
                    value = settings.silenceTimeout.toFloat(),
                    onValueChange = { viewModel.setSilenceTimeout(it.toInt()) },
                    valueRange = 0f..2000f,
                    valueDisplay = "${settings.silenceTimeout}ms"
                )
                
                val confidencePercent = (settings.confidenceThreshold * 100).toInt()
                SettingsSlider(
                    label = "ASR Confidence Threshold",
                    value = confidencePercent.toFloat(),
                    onValueChange = { viewModel.setConfidenceThreshold(it.toInt() / 100f) },
                    valueRange = 0f..100f,
                    valueDisplay = "$confidencePercent%"
                )
            }
            
            // Realtime API Settings (shown when Realtime mode selected)
            if (isRealtimeMode) {
                if (isGoogleProvider) {
                    // Google Live API Settings
                    SettingsSectionHeader(title = "Google Live API Settings")
                    
                    // VAD Sensitivity settings
                    val sensitivityOptions = listOf("LOW", "HIGH")
                    
                    SettingsSegmentedButton(
                        label = "Start of Speech Sensitivity",
                        options = sensitivityOptions,
                        selectedOption = settings.googleStartSensitivity,
                        onOptionSelected = { viewModel.setGoogleStartSensitivity(it) }
                    )
                    
                    SettingsSegmentedButton(
                        label = "End of Speech Sensitivity",
                        options = sensitivityOptions,
                        selectedOption = settings.googleEndSensitivity,
                        onOptionSelected = { viewModel.setGoogleEndSensitivity(it) }
                    )
                    
                    SettingsSlider(
                        label = "Prefix Padding",
                        value = settings.googlePrefixPaddingMs.toFloat(),
                        onValueChange = { viewModel.setGooglePrefixPaddingMs(it.toInt()) },
                        valueRange = 0f..500f,
                        valueDisplay = "${settings.googlePrefixPaddingMs} ms"
                    )
                    
                    SettingsSlider(
                        label = "Silence Duration",
                        value = settings.googleSilenceDurationMs.toFloat(),
                        onValueChange = { viewModel.setGoogleSilenceDurationMs(it.toInt()) },
                        valueRange = 100f..2000f,
                        valueDisplay = "${settings.googleSilenceDurationMs} ms"
                    )
                    
                    SettingsSlider(
                        label = "Thinking Budget",
                        value = settings.googleThinkingBudget.toFloat(),
                        onValueChange = { viewModel.setGoogleThinkingBudget(it.toInt()) },
                        valueRange = 0f..8192f,
                        valueDisplay = if (settings.googleThinkingBudget == 0) "Off" else "${settings.googleThinkingBudget} tokens"
                    )
                    
                    // Affective Dialog - currently not supported by v1alpha API
                    // SettingsSwitch(
                    //     label = "Affective Dialog",
                    //     description = "Enables emotional speech output",
                    //     checked = settings.googleAffectiveDialog,
                    //     onCheckedChange = { viewModel.setGoogleAffectiveDialog(it) }
                    // )
                    
                    SettingsSwitch(
                        label = "Proactive Audio",
                        description = "Allow Gemini to decide not to respond when content is not relevant",
                        checked = settings.googleProactiveAudio,
                        onCheckedChange = { viewModel.setGoogleProactiveAudio(it) }
                    )
                } else {
                    // OpenAI Realtime API Settings
                    SettingsSectionHeader(title = "Voice API Settings")
                    
                    SettingsDropdown(
                        label = "Transcription Model",
                        options = transcriptionModels,
                        selectedOption = settings.transcriptionModel,
                        onOptionSelected = { viewModel.setTranscriptionModel(it) }
                    )
                    
                    SettingsTextField(
                        label = "Transcription Language (ISO-639-1)",
                        value = transcriptionLanguage,
                        onValueChange = { transcriptionLanguage = it },
                        hint = "e.g., de, en, fr"
                    )
                    
                    SettingsTextField(
                        label = "Transcription Prompt (Optional)",
                        value = transcriptionPrompt,
                        onValueChange = { transcriptionPrompt = it },
                        hint = "Keywords or guidance text",
                        singleLine = false,
                        minLines = 2
                    )
                    
                    SettingsSegmentedButton(
                        label = "Turn Detection Type",
                        options = turnDetectionTypes,
                        selectedOption = if (isServerVad) turnDetectionTypes[0] else turnDetectionTypes[1],
                        onOptionSelected = { 
                            val newType = if (it == turnDetectionTypes[0]) "server_vad" else "semantic_vad"
                            viewModel.setTurnDetectionType(newType)
                        }
                    )
                    
                    // Server VAD settings
                    if (isServerVad) {
                        SettingsSlider(
                            label = "VAD Activation Threshold",
                            value = settings.vadThreshold,
                            onValueChange = { viewModel.setVadThreshold(it) },
                            valueRange = 0f..1f,
                            valueDisplay = "%.2f".format(settings.vadThreshold)
                        )
                        
                        SettingsSlider(
                            label = "Prefix Padding",
                            value = settings.prefixPadding.toFloat(),
                            onValueChange = { viewModel.setPrefixPadding(it.toInt()) },
                            valueRange = 0f..1000f,
                            valueDisplay = "${settings.prefixPadding} ms"
                        )
                        
                        SettingsSlider(
                            label = "Silence Duration",
                            value = settings.silenceDuration.toFloat(),
                            onValueChange = { viewModel.setSilenceDuration(it.toInt()) },
                            valueRange = 200f..2000f,
                            valueDisplay = "${settings.silenceDuration} ms"
                        )
                        
                        SettingsTextField(
                            label = "Idle Timeout (ms, Optional)",
                            value = idleTimeoutText,
                            onValueChange = { idleTimeoutText = it },
                            hint = "Milliseconds - leave empty to disable"
                        )
                    } else {
                        // Semantic VAD settings
                        SettingsDropdown(
                            label = "Eagerness",
                            options = eagernessLevels,
                            selectedOption = when (settings.eagerness) {
                                "low" -> eagernessLevels[1]
                                "medium" -> eagernessLevels[2]
                                "high" -> eagernessLevels[3]
                                else -> eagernessLevels[0]
                            },
                            onOptionSelected = { 
                                val newEagerness = when (it) {
                                    eagernessLevels[1] -> "low"
                                    eagernessLevels[2] -> "medium"
                                    eagernessLevels[3] -> "high"
                                    else -> "auto"
                                }
                                viewModel.setEagerness(newEagerness)
                            }
                        )
                    }
                    
                    SettingsSegmentedButton(
                        label = "Noise Reduction",
                        options = noiseReductionTypes,
                        selectedOption = when (settings.noiseReduction) {
                            "near_field" -> noiseReductionTypes[1]
                            "far_field" -> noiseReductionTypes[2]
                            else -> noiseReductionTypes[0]
                        },
                        onOptionSelected = { 
                            val newReduction = when (it) {
                                noiseReductionTypes[1] -> "near_field"
                                noiseReductionTypes[2] -> "far_field"
                                else -> "off"
                            }
                            viewModel.setNoiseReduction(newReduction)
                        }
                    )
                }
            }
            
            // Function Calls Section
            SettingsSectionHeader(title = "Function Calls")
            
            Text(
                text = "Select which tools the AI can use during conversations",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            ToolsSection(
                enabledTools = enabledTools,
                onToolToggled = { toolName, enabled ->
                    enabledTools = if (enabled) {
                        enabledTools + toolName
                    } else {
                        enabledTools - toolName
                    }
                },
                apiKeyManager = apiKeyManager,
                toolRegistry = toolRegistry
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ToolsSection(
    enabledTools: Set<String>,
    onToolToggled: (String, Boolean) -> Unit,
    apiKeyManager: ApiKeyManager,
    toolRegistry: ToolRegistry
) {
    val toolNames = remember(toolRegistry) { toolRegistry.getAllToolNames() }
    
    Column {
        toolNames.forEach { toolName ->
            val tool = remember(toolName) { toolRegistry.getOrCreateTool(toolName) }
            val description = remember(tool) {
                tool?.getDefinition()?.optString("description", "No description available")
                    ?: "Tool not available"
            }
            
            val (apiKeyRequired, isApiKeyAvailable) = remember(toolName) {
                when (toolName) {
                    "analyze_vision" -> "Groq" to apiKeyManager.isVisionAnalysisAvailable()
                    "search_internet" -> "Tavily" to apiKeyManager.isInternetSearchAvailable()
                    "get_weather" -> "OpenWeatherMap" to apiKeyManager.isWeatherAvailable()
                    "play_youtube_video" -> "YouTube" to apiKeyManager.isYouTubeAvailable()
                    else -> null to true
                }
            }
            
            ToolSettingItem(
                toolName = toolName,
                description = description,
                isEnabled = enabledTools.contains(toolName),
                onToggle = { onToolToggled(toolName, it) },
                apiKeyRequired = apiKeyRequired,
                isApiKeyAvailable = isApiKeyAvailable
            )
        }
    }
}
