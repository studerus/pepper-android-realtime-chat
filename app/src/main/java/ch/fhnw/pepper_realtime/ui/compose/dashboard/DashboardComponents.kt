package ch.fhnw.pepper_realtime.ui.compose.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.service.LocalFaceRecognitionService

// ==================== Colors & Constants ====================

internal object DashboardColors {
    val Background = Color(0xFFF9FAFB)
    val HeaderBackground = Color(0xFFE5E7EB)
    val TextDark = Color(0xFF1F2937)
    val TextLight = Color.Gray
    val CardBackground = Color.White
    val BorderColor = Color(0xFFE5E7EB)
    val AccentBlue = Color(0xFF1E40AF)
    val DeleteRed = Color(0xFFDC2626)
    val SuccessGreen = Color(0xFF059669)
    val TabSelected = Color(0xFF1E40AF)
    val TabUnselected = Color(0xFF6B7280)
}

// Columns: Name, Distance, Position, Gaze, Seen
internal val ColWeights = listOf(0.25f, 0.15f, 0.20f, 0.20f, 0.20f)

// ==================== State Classes ====================

/**
 * State for Face Management (integrated into Dashboard)
 */
data class FaceManagementState(
    val faces: List<LocalFaceRecognitionService.RegisteredFace> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isServerAvailable: Boolean = true
)

/**
 * State for Perception Settings
 */
data class PerceptionSettingsState(
    val settings: LocalFaceRecognitionService.PerceptionSettings = LocalFaceRecognitionService.PerceptionSettings(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null
)

// ==================== Helper Components ====================

@Composable
internal fun ServerUnavailableMessage(pepperIp: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = null,
            tint = DashboardColors.DeleteRed,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Face Recognition Server Unavailable",
            color = DashboardColors.DeleteRed,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Cannot connect to $pepperIp:5000\nMake sure the server is running on Pepper's head.",
            color = DashboardColors.TextLight,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun ErrorMessage(error: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Error: $error",
            color = DashboardColors.DeleteRed,
            fontSize = 14.sp
        )
    }
}

@Composable
internal fun LoadingIndicator() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Loading... (starting server if needed)",
            color = DashboardColors.TextLight,
            fontSize = 14.sp
        )
    }
}

@Composable
internal fun EmptyFacesMessage() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            tint = DashboardColors.TextLight,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No Faces Registered",
            color = DashboardColors.TextDark,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Tap + to register a new face.\nThe person should look at Pepper's camera.",
            color = DashboardColors.TextLight,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ==================== Dialogs ====================

@Composable
internal fun AddFaceDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isCapturing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Register New Face")
        },
        text = {
            Column {
                Text(
                    text = "The person should look at Pepper's camera. Enter their name below.",
                    fontSize = 14.sp,
                    color = DashboardColors.TextLight
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !isCapturing,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        isCapturing = true
                        onConfirm(name.trim())
                    }
                },
                enabled = name.isNotBlank() && !isCapturing
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Capture & Register")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCapturing) {
                Text("Cancel")
            }
        }
    )
}

@Composable
internal fun DeleteConfirmationDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Delete Face")
        },
        text = {
            Text("Are you sure you want to delete \"$name\" from the face database?")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DashboardColors.DeleteRed
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==================== Settings Components ====================

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DashboardColors.CardBackground),
        border = BorderStroke(1.dp, DashboardColors.BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = DashboardColors.AccentBlue
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
internal fun SettingsSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueFormat: (Float) -> String,
    description: String
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
            Text(
                text = valueFormat(value),
                fontSize = 14.sp,
                color = DashboardColors.AccentBlue,
                fontWeight = FontWeight.Bold
            )
        }
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = DashboardColors.AccentBlue,
                activeTrackColor = DashboardColors.AccentBlue,
                inactiveTrackColor = DashboardColors.HeaderBackground
            )
        )
        
        Text(
            text = description,
            fontSize = 12.sp,
            color = DashboardColors.TextLight
        )
    }
}

/**
 * Settings slider that applies changes immediately when released.
 */
@Composable
internal fun SettingsSliderInstant(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueFormat: (Float) -> String,
    description: String
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
            Text(
                text = valueFormat(value),
                fontSize = 14.sp,
                color = DashboardColors.AccentBlue,
                fontWeight = FontWeight.Bold
            )
        }
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = DashboardColors.AccentBlue,
                activeTrackColor = DashboardColors.AccentBlue,
                inactiveTrackColor = DashboardColors.HeaderBackground
            )
        )
        
        Text(
            text = description,
            fontSize = 12.sp,
            color = DashboardColors.TextLight
        )
    }
}

/**
 * Camera resolution selector with three options.
 */
@Composable
internal fun CameraResolutionSelector(
    selectedResolution: Int,
    onResolutionChange: (Int) -> Unit
) {
    val resolutions = listOf(
        0 to "QQVGA (160×120) - Close Range (<1m)",
        1 to "QVGA (320×240) - Standard (~2m)",
        2 to "VGA (640×480) - Long Range (>2m)"
    )
    
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Camera Resolution",
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        resolutions.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onResolutionChange(value) }
                    .background(
                        if (selectedResolution == value) 
                            DashboardColors.AccentBlue.copy(alpha = 0.1f) 
                        else 
                            Color.Transparent
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedResolution == value,
                    onClick = { onResolutionChange(value) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = DashboardColors.AccentBlue
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    fontSize = 14.sp,
                    color = if (selectedResolution == value) 
                        DashboardColors.AccentBlue 
                    else 
                        DashboardColors.TextDark
                )
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = "Higher resolution extends detection range but slows down processing. Recognition quality is always high (VGA).",
            fontSize = 12.sp,
            color = DashboardColors.TextLight
        )
    }
}
