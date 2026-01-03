package ch.fhnw.pepper_realtime.ui.compose.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.service.LocalFaceRecognitionService
import ch.fhnw.pepper_realtime.ui.DashboardState

/**
 * Human Perception Dashboard with integrated Face Management.
 * Uses tabs to switch between Live View, Radar View, Known Faces, and Settings.
 */
@Composable
fun DashboardOverlay(
    state: DashboardState,
    faceState: FaceManagementState,
    faceService: LocalFaceRecognitionService,
    settingsState: PerceptionSettingsState = PerceptionSettingsState(),
    onClose: () -> Unit,
    onRefreshFaces: () -> Unit,
    onRegisterFace: (String) -> Unit,
    onDeleteFace: (String) -> Unit,
    onUpdateSettings: (LocalFaceRecognitionService.PerceptionSettings) -> Unit = {},
    onRefreshSettings: () -> Unit = {}
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
                            if (selectedTab == 2) {
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
                                    Text("Live")
                                }
                            },
                            selectedContentColor = DashboardColors.TabSelected,
                            unselectedContentColor = DashboardColors.TabUnselected
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.RadioButtonChecked,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Radar")
                                }
                            },
                            selectedContentColor = DashboardColors.TabSelected,
                            unselectedContentColor = DashboardColors.TabUnselected
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { 
                                selectedTab = 2
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
                                    Text("Faces (${faceState.faces.size})")
                                }
                            },
                            selectedContentColor = DashboardColors.TabSelected,
                            unselectedContentColor = DashboardColors.TabUnselected
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { 
                                selectedTab = 3
                                onRefreshSettings()
                            },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Settings")
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
                        1 -> RadarViewContent(
                            humans = state.humans,
                            lastUpdate = state.lastUpdate
                        )
                        2 -> KnownFacesContent(
                            faceState = faceState,
                            faceService = faceService,
                            onAddAngle = { name -> onRegisterFace(name) },
                            onDelete = { faceToDelete = it },
                            onRefreshFaces = onRefreshFaces
                        )
                        3 -> PerceptionSettingsContent(
                            settingsState = settingsState,
                            onUpdateSettings = onUpdateSettings
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
