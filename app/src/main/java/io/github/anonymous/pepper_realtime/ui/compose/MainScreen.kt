package io.github.anonymous.pepper_realtime.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.manager.DashboardManager
import io.github.anonymous.pepper_realtime.manager.MapUiManager
import io.github.anonymous.pepper_realtime.manager.QuizDialogManager
import io.github.anonymous.pepper_realtime.tools.games.MemoryGameManager
import io.github.anonymous.pepper_realtime.tools.games.TicTacToeGameManager
import io.github.anonymous.pepper_realtime.ui.ChatViewModel
import io.github.anonymous.pepper_realtime.ui.compose.games.MemoryGameDialog
import io.github.anonymous.pepper_realtime.ui.compose.games.QuizDialog
import io.github.anonymous.pepper_realtime.ui.compose.games.TicTacToeDialog
import io.github.anonymous.pepper_realtime.ui.compose.settings.SettingsScreen
import io.github.anonymous.pepper_realtime.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
    keyManager: ApiKeyManager,
    onNewChat: () -> Unit,
    onInterrupt: () -> Unit,
    onStatusClick: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Observe ViewModel states using StateFlow
    val isInterruptVisible by viewModel.isInterruptFabVisible.collectAsStateWithLifecycle()
    val statusText by viewModel.statusText.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val messages by viewModel.messageList.collectAsStateWithLifecycle()
    
    // Local State for Image Overlay
    var overlayImageUrl by remember { mutableStateOf<String?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(360.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        apiKeyManager = keyManager,
                        onSettingsChanged = {}
                    )
                }
            }
        },
        gesturesEnabled = true
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.main_title)) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color(0xFF1E40AF),
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    actions = {
                        IconButton(onClick = { MapUiManager.toggle() }) {
                            Icon(Icons.Default.LocationOn, contentDescription = stringResource(R.string.content_desc_navigation_status))
                        }
                        IconButton(onClick = { DashboardManager.toggleDashboard() }) {
                            Icon(Icons.Default.Visibility, contentDescription = stringResource(R.string.content_desc_perception_dashboard))
                        }
                        IconButton(onClick = onNewChat) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.content_desc_new_chat))
                        }
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                            settingsViewModel.beginEditing()
                        }) {
                            Icon(Icons.Default.Build, contentDescription = stringResource(R.string.content_desc_settings))
                        }
                    }
                )
            },
            floatingActionButton = {
                if (isInterruptVisible) {
                    FloatingActionButton(
                        onClick = onInterrupt,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.content_desc_interrupt))
                    }
                }
            },
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isMuted) Color(0xFFE57373)
                            else Color(0xFFDDDDDD)
                        )
                        .clickable(onClick = onStatusClick)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = statusText,
                        color = Color.Black,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                // Main Chat Content
                ChatScreen(
                    messages = messages,
                    onImageClick = { url -> overlayImageUrl = url }
                )

                // ---------------- Overlays & Dialogs ----------------

                // 1. Dashboard
                val dashboardState = DashboardManager.state
                DashboardOverlay(
                    state = dashboardState,
                    onClose = { DashboardManager.hideDashboard() }
                )

                // 2. Navigation / Map
                val navigationState = MapUiManager.state
                NavigationOverlay(
                    state = navigationState,
                    onClose = { MapUiManager.hide() }
                )

                // 3. Games
                // Quiz
                val quizState = QuizDialogManager.quizState
                if (quizState.isVisible) {
                    QuizDialog(
                        question = quizState.question,
                        options = quizState.options,
                        correctAnswer = quizState.correctAnswer,
                        onAnswered = { selectedOption ->
                            QuizDialogManager.onAnswerSelected(selectedOption)
                        },
                        onDismiss = {
                            QuizDialogManager.dismissQuiz()
                        }
                    )
                }
                
                // TicTacToe
                val ticTacToeState = TicTacToeGameManager.ticTacToeState
                if (ticTacToeState.isVisible) {
                    TicTacToeDialog(
                        gameState = ticTacToeState.gameState,
                        onUserMove = { pos -> TicTacToeGameManager.onUserMove(pos) },
                        onDismiss = { TicTacToeGameManager.dismissGame() }
                    )
                }

                // Memory
                val memoryState = MemoryGameManager.gameState
                if (memoryState.isVisible) {
                    MemoryGameDialog(
                        state = memoryState,
                        onCardClick = { id -> MemoryGameManager.onCardClick(id) },
                        onDismiss = { MemoryGameManager.dismissGame() }
                    )
                }

                // 4. Image Overlay (Full Screen)
                overlayImageUrl?.let { url ->
                    Dialog(
                        onDismissRequest = { overlayImageUrl = null },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.9f))
                                .clickable { overlayImageUrl = null }
                        ) {
                            AsyncImage(
                                model = url,
                                contentDescription = stringResource(R.string.content_desc_full_screen_image),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                            IconButton(
                                onClick = { overlayImageUrl = null },
                                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.content_desc_close), tint = Color.White)
                            }
                        }
                    }
                }
                
                // 5. Warmup Indicator (Simple overlay if needed, based on status text)
                if (statusText.contains("Please wait", ignoreCase = true) || statusText.contains("Initializing", ignoreCase = true)) {
                     Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.8f))
                            .clickable(enabled = false) {}, // Block interaction
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.please_wait),
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.Black
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Handle settings drawer logic
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.isOpen) {
            settingsViewModel.beginEditing()
        } else if (drawerState.isClosed) {
            settingsViewModel.commitChanges()
        }
    }
}
