package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.manager.ApiKeyManager
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import ch.fhnw.pepper_realtime.ui.compose.games.DrawingCanvasDialog
import ch.fhnw.pepper_realtime.ui.compose.games.MemoryGameDialog
import ch.fhnw.pepper_realtime.ui.compose.games.MelodyPlayerDialog
import ch.fhnw.pepper_realtime.ui.compose.games.QuizDialog
import ch.fhnw.pepper_realtime.ui.compose.games.TicTacToeDialog
import ch.fhnw.pepper_realtime.ui.compose.settings.SettingsScreen
import ch.fhnw.pepper_realtime.ui.settings.SettingsViewModel
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
    keyManager: ApiKeyManager,
    toolRegistry: ToolRegistry,
    onNewChat: () -> Unit,
    onExit: () -> Unit,
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
    val partialSpeechResult by viewModel.partialSpeechResult.collectAsStateWithLifecycle()
    val isWarmingUp by viewModel.isWarmingUp.collectAsStateWithLifecycle()
    val navigationState by viewModel.navigationState.collectAsStateWithLifecycle()
    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()
    val quizState by viewModel.quizState.collectAsStateWithLifecycle()
    val ticTacToeState by viewModel.ticTacToeState.collectAsStateWithLifecycle()
    val memoryState by viewModel.memoryGameState.collectAsStateWithLifecycle()
    val drawingState by viewModel.drawingGameState.collectAsStateWithLifecycle()
    val melodyPlayerState by viewModel.melodyPlayerState.collectAsStateWithLifecycle()
    
    // Local State for Image Overlay
    var overlayImageUrl by remember { mutableStateOf<String?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.exit_confirmation_title)) },
            text = { Text(stringResource(R.string.exit_confirmation_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        onExit()
                    }
                ) {
                    Text(stringResource(R.string.exit_confirmation_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(stringResource(R.string.exit_confirmation_no))
                }
            }
        )
    }

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
                        toolRegistry = toolRegistry,
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
                    modifier = Modifier.shadow(4.dp),
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.White,
                        titleContentColor = Color(0xFF1F2937),
                        actionIconContentColor = Color(0xFF1F2937),
                        navigationIconContentColor = Color(0xFF1F2937)
                    ),
                    actions = {
                        IconButton(onClick = { viewModel.toggleNavigationOverlay() }) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = stringResource(R.string.content_desc_navigation_status),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(onClick = { viewModel.toggleDashboard() }) {
                            Icon(
                                Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.content_desc_perception_dashboard),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(onClick = onNewChat) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.content_desc_new_chat),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                            settingsViewModel.beginEditing()
                        }) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = stringResource(R.string.content_desc_settings),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(onClick = { showExitDialog = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = stringResource(R.string.content_desc_exit),
                                modifier = Modifier.size(32.dp)
                            )
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
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                // Main Chat Content (with innerPadding for TopAppBar)
                ChatScreen(
                    messages = messages,
                    partialSpeechResult = partialSpeechResult,
                    onImageClick = { url -> overlayImageUrl = url },
                    modifier = Modifier.padding(innerPadding),
                    bottomPadding = 90.dp // Extra space for StatusCapsule to prevent overlap
                )

                // Status Capsule - positioned at true bottom edge (outside innerPadding)
                StatusCapsule(
                    statusText = statusText,
                    isMuted = isMuted,
                    isListening = statusText.contains("Listening", ignoreCase = true) || statusText.contains("Zuhören", ignoreCase = true),
                    onClick = onStatusClick,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                )

                // ---------------- Overlays & Dialogs ----------------

                // 1. Dashboard
                DashboardOverlay(
                    state = dashboardState,
                    onClose = { viewModel.hideDashboard() }
                )

                // 2. Navigation / Map
                NavigationOverlay(
                    state = navigationState,
                    onClose = { viewModel.hideNavigationOverlay() }
                )

                // 3. Games
                // Quiz
                if (quizState.isVisible) {
                    QuizDialog(
                        question = quizState.question,
                        options = quizState.options,
                        correctAnswer = quizState.correctAnswer,
                        onAnswered = { selectedOption ->
                            viewModel.onQuizAnswerSelected(selectedOption)
                        },
                        onDismiss = {
                            viewModel.dismissQuiz()
                        }
                    )
                }
                
                // TicTacToe
                if (ticTacToeState.isVisible) {
                    TicTacToeDialog(
                        gameState = viewModel.ticTacToeGameState,
                        onUserMove = { pos -> viewModel.onTicTacToeUserMove(pos) },
                        onDismiss = { viewModel.dismissTicTacToeGame() }
                    )
                }

                // Memory
                if (memoryState.isVisible) {
                    MemoryGameDialog(
                        state = memoryState,
                        onCardClick = { id -> viewModel.onMemoryCardClick(id) },
                        onDismiss = { viewModel.dismissMemoryGame() }
                    )
                }

                // Drawing Canvas
                if (drawingState.isVisible) {
                    DrawingCanvasDialog(
                        state = drawingState,
                        onDrawingChanged = { bitmap -> viewModel.onDrawingChanged(bitmap) },
                        onClear = { viewModel.clearDrawingCanvas() },
                        onDismiss = { viewModel.dismissDrawingGame() }
                    )
                }

                // Melody Player
                if (melodyPlayerState.isVisible) {
                    MelodyPlayerDialog(
                        state = melodyPlayerState,
                        onDismiss = { viewModel.dismissMelodyPlayer() }
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
                
                // 5. Warmup Indicator (shown when isWarmingUp is true)
                if (isWarmingUp) {
                     Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.8f))
                            .clickable(enabled = false) {}, // Block interaction
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = ChatColors.Primary)
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
