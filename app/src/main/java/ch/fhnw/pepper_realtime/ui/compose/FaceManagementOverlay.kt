package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.service.LocalFaceRecognitionService
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest

/**
 * Colors for Face Management overlay - matches DashboardOverlay style
 */
private object FaceManagementColors {
    val Background = Color(0xFFF9FAFB)
    val HeaderBackground = Color(0xFFE5E7EB)
    val TextDark = Color(0xFF1F2937)
    val TextLight = Color.Gray
    val CardBackground = Color.White
    val BorderColor = Color(0xFFE5E7EB)
    val AccentBlue = Color(0xFF1E40AF)
    val DeleteRed = Color(0xFFDC2626)
    val SuccessGreen = Color(0xFF10B981)
}

/**
 * State for Face Management overlay
 */
data class FaceManagementState(
    val isVisible: Boolean = false,
    val faces: List<LocalFaceRecognitionService.RegisteredFace> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isServerAvailable: Boolean = true
)

/**
 * Face Management Overlay for registering and managing known faces.
 * Communicates with the local face recognition server on Pepper's head.
 */
@Composable
fun FaceManagementOverlay(
    state: FaceManagementState,
    faceService: LocalFaceRecognitionService,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onRegisterFace: (String) -> Unit,
    onDeleteFace: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var faceToDelete by remember { mutableStateOf<String?>(null) }

    if (state.isVisible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.TopCenter
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .heightIn(max = 500.dp)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = FaceManagementColors.Background),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header
                    FaceManagementHeader(
                        isLoading = state.isLoading,
                        onRefresh = onRefresh,
                        onAdd = { showAddDialog = true },
                        onClose = onClose
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Server status
                    if (!state.isServerAvailable) {
                        ServerUnavailableMessage(pepperIp = faceService.getPepperHeadIp())
                    } else if (state.error != null) {
                        ErrorMessage(error = state.error)
                    } else if (state.isLoading) {
                        LoadingIndicator()
                    } else if (state.faces.isEmpty()) {
                        EmptyFacesMessage()
                    } else {
                        // Faces list
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            items(state.faces) { face ->
                                FaceListItem(
                                    face = face,
                                    imageUrl = faceService.getFaceImageUrl(face.name),
                                    onDelete = { faceToDelete = face.name }
                                )
                            }
                        }
                    }

                    // Footer with count
                    Text(
                        text = "${state.faces.size} registered face(s)",
                        color = FaceManagementColors.TextLight,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        }

        // Add Face Dialog
        if (showAddDialog) {
            AddFaceDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { name ->
                    onRegisterFace(name)
                    showAddDialog = false
                }
            )
        }

        // Delete Confirmation Dialog
        faceToDelete?.let { name ->
            DeleteConfirmationDialog(
                name = name,
                onDismiss = { faceToDelete = null },
                onConfirm = {
                    onDeleteFace(name)
                    faceToDelete = null
                }
            )
        }
    }
}

@Composable
private fun FaceManagementHeader(
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Face,
                contentDescription = null,
                tint = FaceManagementColors.AccentBlue,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Face Management",
                color = FaceManagementColors.TextDark,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Row {
            // Refresh button
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = FaceManagementColors.TextDark
                    )
                }
            }
            
            // Add button
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Face",
                    tint = FaceManagementColors.AccentBlue,
                    modifier = Modifier.size(28.dp)
                )
            }
            
            // Close button
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = FaceManagementColors.TextDark,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun FaceListItem(
    face: LocalFaceRecognitionService.RegisteredFace,
    imageUrl: String,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FaceManagementColors.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, FaceManagementColors.BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Face thumbnail
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Face of ${face.name}",
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(FaceManagementColors.HeaderBackground),
                contentScale = ContentScale.Crop,
                loading = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                },
                error = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = FaceManagementColors.TextLight,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                },
                success = {
                    SubcomposeAsyncImageContent()
                }
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Name and info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = face.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = FaceManagementColors.TextDark
                )
                Text(
                    text = "${face.count} encoding(s)",
                    fontSize = 12.sp,
                    color = FaceManagementColors.TextLight
                )
            }
            
            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${face.name}",
                    tint = FaceManagementColors.DeleteRed
                )
            }
        }
    }
}

@Composable
private fun ServerUnavailableMessage(pepperIp: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = null,
            tint = FaceManagementColors.DeleteRed,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Face Recognition Server Unavailable",
            color = FaceManagementColors.DeleteRed,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Cannot connect to $pepperIp:5000\nMake sure the server is running on Pepper's head.",
            color = FaceManagementColors.TextLight,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorMessage(error: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Error: $error",
            color = FaceManagementColors.DeleteRed,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun LoadingIndicator() {
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
            color = FaceManagementColors.TextLight,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun EmptyFacesMessage() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            tint = FaceManagementColors.TextLight,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No Faces Registered",
            color = FaceManagementColors.TextDark,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Tap + to register a new face.\nThe person should look at Pepper's camera.",
            color = FaceManagementColors.TextLight,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AddFaceDialog(
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
                    color = FaceManagementColors.TextLight
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
private fun DeleteConfirmationDialog(
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
                    containerColor = FaceManagementColors.DeleteRed
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

