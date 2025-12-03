package io.github.anonymous.pepper_realtime.ui.compose.settings

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
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.manager.SettingsRepository
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider
import io.github.anonymous.pepper_realtime.tools.ToolRegistry
import io.github.anonymous.pepper_realtime.ui.compose.ChatTheme
import io.github.anonymous.pepper_realtime.ui.settings.SettingsViewModel

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
    
    // Local state for text fields (applied on drawer close)
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var transcriptionLanguage by remember(settings.transcriptionLanguage) { mutableStateOf(settings.transcriptionLanguage) }
    var transcriptionPrompt by remember(settings.transcriptionPrompt) { mutableStateOf(settings.transcriptionPrompt) }
    var idleTimeoutText by remember(settings.idleTimeout) { mutableStateOf(settings.idleTimeout?.toString() ?: "") }
    var enabledTools by remember(settings.enabledTools) { mutableStateOf(settings.enabledTools) }
    
    // Derived values
    val isRealtimeMode = settings.audioInputMode == SettingsRepository.MODE_REALTIME_API
    val isServerVad = settings.turnDetectionType == "server_vad"
    
    // Options
    val models = listOf("gpt-realtime", "gpt-realtime-mini", "gpt-4o-realtime-preview", "gpt-4o-mini-realtime-preview")
    val voices = listOf("alloy", "ash", "ballad", "cedar", "coral", "echo", "marin", "sage", "shimmer", "verse")
    val configuredProviders = remember { apiKeyManager.getConfiguredProviders() }
    val languages = SettingsRepository.getAvailableLanguages()
    val audioInputModes = listOf("Realtime API (Simple Setup)", "Azure Speech (Best for Dialects)")
    val transcriptionModels = listOf("whisper-1", "gpt-4o-mini-transcribe", "gpt-4o-transcribe", "gpt-4o-transcribe-diarize")
    val turnDetectionTypes = listOf("Server VAD (Volume-based)", "Semantic VAD (Context-aware)")
    val eagernessLevels = listOf("Auto (Medium)", "Low (Max 8s)", "Medium (Max 4s)", "High (Max 2s)")
    val noiseReductionTypes = listOf("Off", "Near Field (Headset)", "Far Field (Room Mic)")
    
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
    
    // Apply text-based changes when drawer closes (for text fields)
    DisposableEffect(Unit) {
        onDispose {
            viewModel.setSystemPrompt(systemPrompt)
            viewModel.setTranscriptionLanguage(transcriptionLanguage)
            viewModel.setTranscriptionPrompt(transcriptionPrompt)
            idleTimeoutText.toIntOrNull()?.let { viewModel.setIdleTimeout(it) }
            viewModel.setEnabledTools(enabledTools)
            onSettingsChanged()
        }
    }
    
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
                SettingsDropdown(
                    label = "API Provider",
                    options = configuredProviders.map { it.getDisplayName() },
                    selectedOption = getProviderDisplayName(settings.apiProvider),
                    onOptionSelected = { displayName ->
                        val enumName = getProviderEnumName(displayName)
                        viewModel.setApiProvider(enumName)
                    }
                )
            }
            
            // Voice - apply immediately
            SettingsDropdown(
                label = "Voice",
                options = voices,
                selectedOption = settings.voice,
                onOptionSelected = { viewModel.setVoice(it) }
            )
            
            // Speed - apply immediately
            SettingsSlider(
                label = "Speech Speed",
                value = settings.speedProgress.toFloat(),
                onValueChange = { viewModel.setSpeedProgress(it.toInt()) },
                valueRange = 25f..150f,
                valueDisplay = "%.2fx".format(settings.speedProgress / 100f)
            )
            
            // Temperature - apply immediately
            SettingsSlider(
                label = "Temperature",
                value = settings.temperatureProgress.toFloat(),
                onValueChange = { viewModel.setTemperatureProgress(it.toInt()) },
                valueRange = 0f..100f,
                valueDisplay = "%.2f".format(0.6f + (settings.temperatureProgress / 100f) * 0.6f)
            )
            
            // Audio Input Mode - apply immediately
            SettingsDropdown(
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
                SettingsSectionHeader(title = "Realtime API Settings")
                
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
                
                SettingsDropdown(
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
                
                SettingsDropdown(
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
