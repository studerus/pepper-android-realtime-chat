package ch.fhnw.pepper_realtime.ui.compose.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.service.LocalFaceRecognitionService

/**
 * Tab 4: Settings tab content for configuring perception parameters.
 * Settings are applied immediately when changed (no Apply button needed).
 */
@Composable
internal fun PerceptionSettingsContent(
    settingsState: PerceptionSettingsState,
    onUpdateSettings: (LocalFaceRecognitionService.PerceptionSettings) -> Unit
) {
    var localSettings by remember(settingsState.settings) { 
        mutableStateOf(settingsState.settings) 
    }
    
    // Helper to update and send settings immediately
    fun updateAndSend(newSettings: LocalFaceRecognitionService.PerceptionSettings) {
        localSettings = newSettings
        onUpdateSettings(newSettings)
    }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Loading indicator
        if (settingsState.isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
        
        // Error message
        settingsState.error?.let { error ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        color = DashboardColors.DeleteRed,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }
        
        // Detection Settings Section
        item {
            SettingsSection(title = "Detection") {
                SettingsSliderInstant(
                    label = "Detection Confidence",
                    value = localSettings.detectionThreshold,
                    onValueChange = { localSettings = localSettings.copy(detectionThreshold = it) },
                    onValueChangeFinished = { updateAndSend(localSettings) },
                    valueRange = 0.5f..0.99f,
                    valueFormat = { "%.2f".format(it) },
                    description = "Min confidence (filters blur/noise)"
                )
            }
        }
        
        // Recognition Settings Section
        item {
            SettingsSection(title = "Recognition") {
                SettingsSliderInstant(
                    label = "Recognition Threshold",
                    value = localSettings.recognitionThreshold,
                    onValueChange = { localSettings = localSettings.copy(recognitionThreshold = it) },
                    onValueChangeFinished = { updateAndSend(localSettings) },
                    valueRange = 0.3f..0.9f,
                    valueFormat = { "%.2f".format(it) },
                    description = "Lower = stricter matching"
                )
                
                SettingsSliderInstant(
                    label = "Recognition Cooldown",
                    value = localSettings.recognitionCooldownMs.toFloat(),
                    onValueChange = { localSettings = localSettings.copy(recognitionCooldownMs = it.toInt()) },
                    onValueChangeFinished = { updateAndSend(localSettings) },
                    valueRange = 1000f..10000f,
                    valueFormat = { "${it.toInt()}ms" },
                    description = "Time between recognition attempts"
                )
            }
        }
        
        // Tracker Settings Section
        item {
            SettingsSection(title = "Tracker") {
                SettingsSliderInstant(
                    label = "Max Angle Distance",
                    value = localSettings.maxAngleDistance,
                    onValueChange = { localSettings = localSettings.copy(maxAngleDistance = it) },
                    onValueChangeFinished = { updateAndSend(localSettings) },
                    valueRange = 5f..30f,
                    valueFormat = { "${it.toInt()}°" },
                    description = "Max movement between frames to match same person"
                )
                
                SettingsSliderInstant(
                    label = "Track Timeout",
                    value = localSettings.trackTimeoutMs.toFloat(),
                    onValueChange = { localSettings = localSettings.copy(trackTimeoutMs = it.toInt()) },
                    onValueChangeFinished = { updateAndSend(localSettings) },
                    valueRange = 1000f..10000f,
                    valueFormat = { "${it.toInt()}ms" },
                    description = "Time before removing lost tracks"
                )
                
                SettingsSliderInstant(
                    label = "Confirm Count",
                    value = localSettings.confirmCount.toFloat(),
                    onValueChange = { localSettings = localSettings.copy(confirmCount = it.toInt()) },
                    onValueChangeFinished = { updateAndSend(localSettings) },
                    valueRange = 1f..10f,
                    valueFormat = { "${it.toInt()}" },
                    description = "Detections needed before track is confirmed"
                )
                
                SettingsSliderInstant(
                    label = "Lost Buffer",
                    value = localSettings.lostBufferMs.toFloat(),
                    onValueChange = { localSettings = localSettings.copy(lostBufferMs = it.toInt()) },
                    onValueChangeFinished = { updateAndSend(localSettings) },
                    valueRange = 500f..10000f,
                    valueFormat = { "${it.toInt()}ms" },
                    description = "How long lost tracks stay for recovery"
                )
                
                SettingsSliderInstant(
                    label = "World Match Distance",
                    value = localSettings.worldMatchThresholdM,
                    onValueChange = { localSettings = localSettings.copy(worldMatchThresholdM = it) },
                    onValueChangeFinished = { updateAndSend(localSettings) },
                    valueRange = 0.3f..2.0f,
                    valueFormat = { "%.1fm".format(it) },
                    description = "Max 3D distance for track matching"
                )
            }
        }
        
        // Gaze Detection Settings
        item {
            SettingsSection(title = "Gaze Detection") {
                SettingsSliderInstant(
                    label = "Gaze Center Tolerance",
                    value = localSettings.gazeCenterTolerance,
                    onValueChange = { localSettings = localSettings.copy(gazeCenterTolerance = it) },
                    onValueChangeFinished = { updateAndSend(localSettings) },
                    valueRange = 0.05f..0.5f,
                    valueFormat = { "%.2f".format(it) },
                    description = "How off-center is still 'looking at robot'"
                )
            }
        }
        
        // Performance Settings
        item {
            SettingsSection(title = "Performance") {
                SettingsSliderInstant(
                    label = "Cycle Delay",
                    value = localSettings.updateIntervalMs.toFloat(),
                    onValueChange = { localSettings = localSettings.copy(updateIntervalMs = it.toInt()) },
                    onValueChangeFinished = { updateAndSend(localSettings) },
                    valueRange = 0f..1000f,
                    valueFormat = { "${it.toInt()}ms" },
                    description = "Pause between tracking cycles (0 = max speed, more CPU/heat)"
                )
            }
        }
        
        // Camera Settings
        item {
            SettingsSection(title = "Camera") {
                CameraResolutionSelector(
                    selectedResolution = localSettings.cameraResolution,
                    onResolutionChange = { 
                        updateAndSend(localSettings.copy(cameraResolution = it))
                    }
                )
            }
        }
        
        // Info text and reset button
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text(
                    text = "Settings are applied automatically when changed",
                    fontSize = 12.sp,
                    color = DashboardColors.TextLight,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = {
                        // Reset to defaults
                        val defaults = LocalFaceRecognitionService.PerceptionSettings()
                        localSettings = defaults
                        updateAndSend(defaults)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DashboardColors.CardBackground,
                        contentColor = DashboardColors.TextLight
                    ),
                    border = BorderStroke(1.dp, DashboardColors.TextLight.copy(alpha = 0.3f))
                ) {
                    Text("Reset to Defaults")
                }
            }
        }
    }
}
