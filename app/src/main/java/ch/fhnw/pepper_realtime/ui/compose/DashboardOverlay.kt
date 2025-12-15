package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.data.PerceptionData
import ch.fhnw.pepper_realtime.service.LocalFaceRecognitionService
import ch.fhnw.pepper_realtime.ui.DashboardState
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest

private object DashboardColors {
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

private val ColWeights = listOf(0.12f, 0.12f, 0.08f, 0.1f, 0.1f, 0.1f, 0.1f, 0.14f, 0.14f)

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
 * Human Perception Dashboard with integrated Face Management.
 * Uses tabs to switch between Live View and Known Faces.
 */
@Composable
fun DashboardOverlay(
    state: DashboardState,
    faceState: FaceManagementState,
    faceService: LocalFaceRecognitionService,
    onClose: () -> Unit,
    onRefreshFaces: () -> Unit,
    onRegisterFace: (String) -> Unit,
    onDeleteFace: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
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
                    .heightIn(max = 450.dp)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DashboardColors.Background),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header with Title and Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = DashboardColors.TextDark,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "People & Faces",
                                color = DashboardColors.TextDark,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Row {
                            // Action buttons based on current tab
                            if (selectedTab == 1) {
                                // Refresh button for Faces tab
                                IconButton(onClick = onRefreshFaces, enabled = !faceState.isLoading) {
                                    if (faceState.isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Refresh",
                                            tint = DashboardColors.TextDark
                                        )
                                    }
                                }
                                // Add button for Faces tab
                                IconButton(onClick = { showAddDialog = true }) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add Face",
                                        tint = DashboardColors.AccentBlue,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            // Close button
                            IconButton(onClick = onClose) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.content_desc_close),
                                    tint = DashboardColors.TextDark,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }

                    // Tab Row
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = DashboardColors.Background,
                        contentColor = DashboardColors.TabSelected,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Visibility,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Live View")
                                }
                            },
                            selectedContentColor = DashboardColors.TabSelected,
                            unselectedContentColor = DashboardColors.TabUnselected
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { 
                                selectedTab = 1
                                onRefreshFaces()
                            },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Face,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Known Faces (${faceState.faces.size})")
                                }
                            },
                            selectedContentColor = DashboardColors.TabSelected,
                            unselectedContentColor = DashboardColors.TabUnselected
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tab Content
                    when (selectedTab) {
                        0 -> LiveViewContent(
                            humans = state.humans,
                            lastUpdate = state.lastUpdate
                        )
                        1 -> KnownFacesContent(
                            faceState = faceState,
                            faceService = faceService,
                            onDelete = { faceToDelete = it }
                        )
                    }
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

// ============== TAB 1: LIVE VIEW ==============

@Composable
private fun LiveViewContent(
    humans: List<PerceptionData.HumanInfo>,
    lastUpdate: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Table Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DashboardColors.HeaderBackground, RoundedCornerShape(4.dp))
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val displayHeaders = listOf(
                stringResource(R.string.dashboard_header_picture),
                stringResource(R.string.dashboard_header_demographics),
                stringResource(R.string.dashboard_header_distance),
                stringResource(R.string.dashboard_header_emotion),
                stringResource(R.string.dashboard_header_pleasure),
                stringResource(R.string.dashboard_header_excitement),
                stringResource(R.string.dashboard_header_smile),
                stringResource(R.string.dashboard_header_attention),
                stringResource(R.string.dashboard_header_engagement)
            )
            
            displayHeaders.forEachIndexed { index, title ->
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = DashboardColors.TextLight,
                    modifier = Modifier.weight(ColWeights[index]),
                    textAlign = if (index > 1) TextAlign.Center else TextAlign.Start
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Humans List
        if (humans.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_humans_detected), color = DashboardColors.TextLight, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(humans) { human ->
                    HumanDetectionItem(human)
                }
            }
        }
        
        // Footer
        Text(
            text = stringResource(R.string.last_update_format, lastUpdate),
            color = DashboardColors.TextLight,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

@Composable
private fun HumanDetectionItem(human: PerceptionData.HumanInfo) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DashboardColors.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, DashboardColors.BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Picture
                Box(modifier = Modifier.weight(ColWeights[0]), contentAlignment = Alignment.CenterStart) {
                    if (human.facePicture != null) {
                        Image(
                            bitmap = human.facePicture!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.content_desc_face),
                            modifier = Modifier.size(50.dp).background(Color.LightGray)
                        )
                    } else {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(50.dp), tint = Color.Gray)
                    }
                }
                
                // Demographics (with recognized name if available)
                Column(modifier = Modifier.weight(ColWeights[1])) {
                    human.recognizedName?.let { name ->
                        Text(
                            text = "👤 $name",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = DashboardColors.SuccessGreen
                        )
                    }
                    Text(
                        text = human.getDemographics(),
                        fontSize = if (human.recognizedName != null) 12.sp else 14.sp,
                        fontWeight = if (human.recognizedName != null) FontWeight.Normal else FontWeight.Bold,
                        color = DashboardColors.TextDark
                    )
                }
                // Distance
                Text(human.getDistanceString(), fontSize = 14.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[2]))
                // Emotion
                Text(human.getBasicEmotionDisplay(), fontSize = 14.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[3]))
                // Pleasure
                Text(human.getPleasureStateDisplay(), fontSize = 14.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[4]))
                // Excitement
                Text(human.getExcitementStateDisplay(), fontSize = 14.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[5]))
                // Smile
                Text(human.getSmileStateDisplay(), fontSize = 14.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[6]))
                // Attention
                Text(human.getAttentionLevel(), fontSize = 13.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[7]))
                // Engagement
                Text(human.getEngagementLevel(), fontSize = 13.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[8]))
            }
        }
    }
}

// ============== TAB 2: KNOWN FACES ==============

@Composable
private fun KnownFacesContent(
    faceState: FaceManagementState,
    faceService: LocalFaceRecognitionService,
    onDelete: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            !faceState.isServerAvailable -> {
                ServerUnavailableMessage(pepperIp = faceService.getPepperHeadIp())
            }
            faceState.error != null -> {
                ErrorMessage(error = faceState.error)
            }
            faceState.isLoading -> {
                LoadingIndicator()
            }
            faceState.faces.isEmpty() -> {
                EmptyFacesMessage()
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(faceState.faces) { face ->
                        FaceListItem(
                            face = face,
                            imageUrl = faceService.getFaceImageUrl(face.name),
                            onDelete = { onDelete(face.name) }
                        )
                    }
                }
                
                // Footer
                Text(
                    text = "${faceState.faces.size} registered face(s)",
                    color = DashboardColors.TextLight,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
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
        colors = CardDefaults.cardColors(containerColor = DashboardColors.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, DashboardColors.BorderColor),
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
                    .background(DashboardColors.HeaderBackground),
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
                            tint = DashboardColors.TextLight,
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
                    color = DashboardColors.TextDark
                )
                Text(
                    text = "${face.count} encoding(s)",
                    fontSize = 12.sp,
                    color = DashboardColors.TextLight
                )
            }
            
            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${face.name}",
                    tint = DashboardColors.DeleteRed
                )
            }
        }
    }
}

// ============== HELPER COMPONENTS ==============

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
private fun ErrorMessage(error: String) {
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
            color = DashboardColors.TextLight,
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
