
package com.example.pepper_test2;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.Promise;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.QiSDK;
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.appbar.MaterialToolbar;
import com.microsoft.cognitiveservices.speech.SpeechConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;

import okhttp3.Response;
import android.media.AudioManager;
import android.content.Context;
import android.view.Window;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Color;
import android.widget.LinearLayout;

public class ChatActivity extends AppCompatActivity implements RobotLifecycleCallbacks {

    private static final String TAG = "ChatActivity";

    private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 2;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 3;

    private QiContext qiContext;
    private DrawerLayout drawerLayout;
    private TextView statusTextView;
    private RecyclerView chatRecyclerView;
    private ChatMessageAdapter chatAdapter;
    private final List<ChatMessage> messageList = new ArrayList<>();
    private LinearLayout warmupIndicatorLayout;

    private FloatingActionButton fabInterrupt;
    private ApiKeyManager keyManager;
    private SpeechRecognizerManager sttManager;
    // Optimized threading - using specialized thread pools instead of single executors
    private final OptimizedThreadManager threadManager = OptimizedThreadManager.getInstance();

    // WebSocket and audio playback
    private RealtimeSessionManager sessionManager;
    private RealtimeEventHandler eventHandler;
    private TurnManager turnManager;
    private String lastAssistantItemId = null;
    private final List<String> sessionImagePaths = new ArrayList<>();
    private OptimizedAudioPlayer audioPlayer;
    private volatile boolean hasActiveResponse = false;
    private volatile String currentResponseId = null;
    private volatile String cancelledResponseId = null;
    private Promise<Void> connectionPromise;
    private volatile boolean expectingFinalAnswerAfterToolCall = false;
    private final GestureController gestureController = new GestureController();
    private volatile boolean isWarmingUp = false;
    
    // Mute state management for pause/resume functionality
    private volatile boolean isMuted = false;

    private VisionService visionService;

    private ToolExecutor toolExecutor;
    private boolean hasFocusInitialized = false;

    // Settings UI
    private SettingsManager settingsManager;

    private WebSocketMessageHandler webSocketHandler;


    // A simple helper class to hold language display name and code
    // This class is now private to SettingsManager, so it's removed from here.

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_chat);

        keyManager = new ApiKeyManager(this);

        drawerLayout = findViewById(R.id.drawer_layout);
        statusTextView = findViewById(R.id.statusTextView);
        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        warmupIndicatorLayout = findViewById(R.id.warmup_indicator_layout);

        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        setSupportActionBar(topAppBar);


        chatAdapter = new ChatMessageAdapter(messageList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        chatRecyclerView.setLayoutManager(layoutManager);
        chatRecyclerView.setAdapter(chatAdapter);

        fabInterrupt = findViewById(R.id.fab_interrupt);
        fabInterrupt.setOnClickListener(v -> {
            try {
                if (sessionManager == null || !sessionManager.isConnected()) return;
                // 1) Cancel further generation first (send only if there is an active response)
                if (hasActiveResponse) {
                    JSONObject cancel = new JSONObject();
                    cancel.put("type", "response.cancel");
                    sessionManager.send(cancel.toString());
                    // mark the current response as cancelled to ignore trailing chunks
                    cancelledResponseId = currentResponseId;
                }

                // 2) Truncate current assistant item if we have its id
                if (lastAssistantItemId != null) {
                    int playedMs = audioPlayer != null ? Math.max(0, audioPlayer.getEstimatedPlaybackPositionMs()) : 0;
                    JSONObject truncate = new JSONObject();
                    truncate.put("type", "conversation.item.truncate");
                    truncate.put("item_id", lastAssistantItemId);
                    truncate.put("content_index", 0);
                    truncate.put("audio_end_ms", playedMs);
                    sessionManager.send(truncate.toString());
                }

                // 3) Stop local audio and gestures; immediately switch to Listening
                if (audioPlayer != null) audioPlayer.interruptNow();
                gestureController.stopNow();
                if (turnManager != null) turnManager.setState(TurnManager.State.LISTENING);
            } catch (Exception e) {
                Log.e(TAG, "Interrupt failed", e);
            }
        });

        // Tap-to-interrupt/mute/unmute on status bar
        statusTextView.setOnClickListener(v -> {
            try {
                if (turnManager == null) {
                    Log.w(TAG, "Status bar clicked but turnManager is null");
                    return;
                }
                
                TurnManager.State currentState = turnManager.getState();
                Log.i(TAG, "Status bar clicked - Current state: " + currentState + ", isMuted: " + isMuted);
                
                if (currentState == TurnManager.State.SPEAKING) {
                    // Robot is speaking -> interrupt and mute
                    if (hasActiveResponse || (audioPlayer != null && audioPlayer.isPlaying())) {
                        Log.i(TAG, "Interrupting and muting...");
                        interruptAndMute();
                    }
                } else if (isMuted) {
                    // Currently muted -> unmute and start listening
                    Log.i(TAG, "Unmuting...");
                    unmute();
                } else if (currentState == TurnManager.State.LISTENING) {
                    // Currently listening -> mute
                    Log.i(TAG, "Muting...");
                    mute();
                }
            } catch (Exception e) {
                Log.e(TAG, "Status bar click handler failed", e);
            }
        });

        // Ensure realtime session manager is ready
        if (sessionManager == null) {
            sessionManager = new RealtimeSessionManager();
            sessionManager.setListener(new RealtimeSessionManager.Listener() {
                @Override public void onOpen(Response response) {
                    ChatActivity.this.sendInitialSessionConfig();
                }
                @Override public void onTextMessage(String text) { if (eventHandler != null) eventHandler.handle(text); else webSocketHandler.handleMessage(text); }
                @Override public void onBinaryMessage(okio.ByteString bytes) { /* ignore */ }
                @Override public void onClosing(int code, String reason) { }
                @Override public void onClosed(int code, String reason) {
                    if (connectionPromise != null && !connectionPromise.getFuture().isDone()) {
                        connectionPromise.setError("WebSocket closed before session was updated.");
                        connectionPromise = null;
                    }
                }
                @Override public void onFailure(Throwable t, Response response) {
                    Log.e(TAG, "WebSocket Failure: " + t.getMessage(), t);
                    if (connectionPromise != null && !connectionPromise.getFuture().isDone()) {
                        connectionPromise.setError("WebSocket Failure: " + t.getMessage());
                        connectionPromise = null;
                    }
                }
            });
        }

        // WebSocket client initialization handled by RealtimeSessionManager

        setupSettingsMenu();
        setupWebSocketHandler();
        audioPlayer = new OptimizedAudioPlayer();
        turnManager = new TurnManager(new TurnManager.Callbacks() {
            @Override public void onEnterListening() { 
                runOnUiThread(() -> { 
                    if (!isMuted) {
                        statusTextView.setText(getString(R.string.status_listening)); 
                        startContinuousRecognition(); 
                    }
                    findViewById(R.id.fab_interrupt).setVisibility(View.GONE); 
                }); 
            }
            @Override public void onEnterThinking() {
                // Physically stop the mic so nothing is recognized during THINKING
                stopContinuousRecognition();
                runOnUiThread(() -> { statusTextView.setText(getString(R.string.status_thinking)); findViewById(R.id.fab_interrupt).setVisibility(View.GONE); });
            }
            @Override public void onEnterSpeaking() { stopContinuousRecognition(); startExplainGesturesLoop(); runOnUiThread(() -> findViewById(R.id.fab_interrupt).setVisibility(View.GONE)); }
            @Override public void onExitSpeaking() { 
                gestureController.stopNow(); 
                runOnUiThread(() -> { 
                    if (!isMuted) {
                        statusTextView.setText(getString(R.string.status_listening)); 
                        startContinuousRecognition(); 
                    }
                    findViewById(R.id.fab_interrupt).setVisibility(View.GONE); 
                }); 
            }
        });
        toolExecutor = new ToolExecutor(this, (question, options, correct) -> 
            runOnUiThread(() -> showQuizDialog(question, options, correct)));
        audioPlayer.setListener(new OptimizedAudioPlayer.Listener() {
            @Override public void onPlaybackStarted() {
                turnManager.setState(TurnManager.State.SPEAKING);
            }
            @Override public void onPlaybackFinished() {
                turnManager.setState(TurnManager.State.LISTENING);
            }
        });

        QiSDK.register(this, this);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST_CODE);
        } else {
            // Pre-initialize the recognizer if permission is already granted
            initializeSpeechRecognizer();
        }

        // Request camera permission for vision analysis
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    @SuppressLint("NullableProblems")
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeSpeechRecognizer();
            } else {
                runOnUiThread(() -> statusTextView.setText(getString(R.string.error_microphone_permission_denied)));
            }
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Camera permission granted - vision analysis now available");
            } else {
                Log.w(TAG, "Camera permission denied - vision analysis will not work");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (sttManager != null) {
            sttManager.shutdown();
        }
        
        // Shutdown optimized thread manager
        threadManager.shutdown();
        
        // Clean up services and prevent memory leaks
        cleanupServices();
        
        // Clean up network resources
        OptimizedHttpClientManager.getInstance().shutdown();
        
        // Clean up any session images
        deleteSessionImages();
        disconnectWebSocket(); // Disconnect WebSocket connection
        releaseAudioTrack(); // Release AudioTrack
        QiSDK.unregister(this, this);
        super.onDestroy();
    }
    
    /**
     * Clean up services and listeners to prevent memory leaks
     */
    private void cleanupServices() {
        try {
            // Clear optimized audio player listener to prevent callback leaks
            if (audioPlayer != null) {
                audioPlayer.setListener(null);
            }
            
            // Clear session manager listener
            if (sessionManager != null) {
                sessionManager.setListener(null);
            }
            
            // Clear STT manager listener
            if (sttManager != null) {
                sttManager.setListener(null);
            }
            
            // Clear vision service references
            visionService = null;
            
            // Clear settings manager listener
            if (settingsManager != null) {
                settingsManager.setListener(null);
            }
            
            // Clear tool executor reference
            if (toolExecutor != null) {
                toolExecutor.setQiContext(null);
            }
            
            // Clear event handler
            eventHandler = null;
            
            Log.i(TAG, "Services cleaned up successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error during service cleanup", e);
        }
    }


    @Override
    public void onRobotFocusGained(QiContext qiContext) {
        this.qiContext = qiContext;
        if (toolExecutor != null) toolExecutor.setQiContext(qiContext);
        // Only perform initialization on first focus gain
        if (!hasFocusInitialized) {
            hasFocusInitialized = true;
            isWarmingUp = true;
            isMuted = false; // Reset mute state on initial startup

            // Set initial volume from settings
            applyVolume(settingsManager.getVolume());

            // Show warmup indicator before starting async operations
            showWarmupIndicator();

            // Asynchronous initialization
            Future<Void> connectFuture = connectWebSocket();
            Future<Void> warmupFuture = warmupSpeechRecognizer();

            Future.waitAll(connectFuture, warmupFuture).thenConsume(future -> {
                // Hide warmup indicator after all setup is complete, regardless of outcome
                hideWarmupIndicator();
                isWarmingUp = false;

                if (future.hasError()) {
                    Log.e(TAG, "Error during initial setup (ws or warmup)", future.getError());
                    runOnUiThread(() -> {
                        addMessage(getString(R.string.setup_error_during, future.getError().getMessage()), ChatMessage.Sender.ROBOT);
                        statusTextView.setText(getString(R.string.error_connection_failed));
                    });
                } else {
                    Log.i(TAG, "Initial Setup complete. Starting recognition now.");
                    // This will correctly set the status to "Listening" after warmup.
                    if (turnManager != null) turnManager.setState(TurnManager.State.LISTENING);
                }
            });
        }
    }

    @Override
    public void onRobotFocusLost() {
        if (toolExecutor != null) toolExecutor.setQiContext(null);
        // STT cleanup handled by sttManager
        this.qiContext = null;
    }

    @Override
    public void onRobotFocusRefused(String reason) {
        Log.e(TAG, "Robot focus refused: " + reason);
    }

    private void setupWebSocketHandler() {
        webSocketHandler = new WebSocketMessageHandler();

        webSocketHandler.registerHandler("session.updated", message -> {
            Log.i(TAG, "Session configured successfully.");
            // Do not change status to "Ready" if the app is still in the initial startup/warmup phase.
            if (!isWarmingUp) {
                runOnUiThread(() -> statusTextView.setText(getString(R.string.status_ready)));
            }
            if (connectionPromise != null && !connectionPromise.getFuture().isDone()) {
                connectionPromise.setValue(null);
                connectionPromise = null;
            }
        });

        webSocketHandler.registerHandler("response.audio_transcript.delta", message -> {
            String delta = message.getString("delta");
            runOnUiThread(() -> {
                String respId = message.optString("response_id");
                if (Objects.equals(respId, cancelledResponseId)) {
                    return; // drop transcript of cancelled response
                }
                CharSequence current = statusTextView.getText();
                if (current == null || current.length() == 0 || !current.toString().startsWith("Speaking — tap to interrupt: ")) {
                    statusTextView.setText(getString(R.string.status_speaking_tap_to_interrupt));
                }
                statusTextView.append(delta);

                boolean createNewMessage = false;
                if (expectingFinalAnswerAfterToolCall) {
                    createNewMessage = true;
                    expectingFinalAnswerAfterToolCall = false; // Reset flag
                } else if (messageList.isEmpty() || messageList.get(messageList.size() - 1).getSender() != ChatMessage.Sender.ROBOT) {
                    createNewMessage = true;
                }

                if (createNewMessage) {
                    messageList.add(new ChatMessage(delta, ChatMessage.Sender.ROBOT));
                    chatAdapter.notifyItemInserted(messageList.size() - 1);
                    chatRecyclerView.scrollToPosition(messageList.size() - 1);
                } else {
                    ChatMessage lastMessage = messageList.get(messageList.size() - 1);
                    lastMessage.setMessage(lastMessage.getMessage() + delta);
                    chatAdapter.notifyItemChanged(messageList.size() - 1);
                }
            });
        });

        webSocketHandler.registerHandler("response.audio.delta", message -> {
            String base64Audio = message.getString("delta");
            String respId = message.optString("response_id");
            currentResponseId = respId;
            if (Objects.equals(respId, cancelledResponseId)) {
                return; // drop cancelled response chunks
            }
            byte[] audioData = android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT);
            audioPlayer.addChunk(audioData);
            audioPlayer.startIfNeeded();
        });

        webSocketHandler.registerHandler("response.audio.done", message -> {
            Log.i(TAG, "Audio stream complete signaled by server.");
            audioPlayer.markResponseDone();
        });

        webSocketHandler.registerHandler("response.done", this::handleResponseDone);

        webSocketHandler.registerHandler("error", message -> {
            JSONObject errorObject = message.getJSONObject("error");
            String errorCode = errorObject.optString("code", "Unknown");
            String errorMessage = errorObject.optString("message", "An unknown error occurred.");
            Log.e(TAG, "WebSocket Error Received - Code: " + errorCode + ", Message: " + errorMessage);
            runOnUiThread(() -> {
                addMessage(getString(R.string.server_error_prefix, errorMessage), ChatMessage.Sender.ROBOT);
                statusTextView.setText(getString(R.string.error_code_prefix, errorCode));
            });
            if (connectionPromise != null && !connectionPromise.getFuture().isDone()) {
                connectionPromise.setError("Server returned an error during setup: " + errorMessage);
                connectionPromise = null;
            }
        });
    }

    private void setupSettingsMenu() {
        NavigationView navigationView = findViewById(R.id.navigation_view);
        settingsManager = new SettingsManager(this, navigationView);
        settingsManager.setListener(new SettingsManager.SettingsListener() {
            @Override
            public void onSettingsChanged() {
                // This covers model, voice, prompt, and temperature changes
                Log.i(TAG, "Core settings changed. Starting new session.");
                startNewSession();
            }

            @Override
            public void onRecognizerSettingsChanged() {
                // This covers language and silence timeout
                Log.i(TAG, "Recognizer settings changed. Re-initializing speech recognizer.");
                    runOnUiThread(() -> statusTextView.setText(getString(R.string.status_updating_recognizer)));
                    stopContinuousRecognition();
                    initializeSpeechRecognizer().thenConsume(future -> {
                        if (future.hasError()) {
                        Log.e(TAG, "Failed to re-initialize recognizer", future.getError());
                            runOnUiThread(() -> statusTextView.setText(getString(R.string.error_updating_settings)));
                        } else {
                            Log.i(TAG, "Recognizer re-initialized. Restarting recognition.");
                            startContinuousRecognition();
                        }
                    });
                }

            @Override
            public void onVolumeChanged(int volume) {
                applyVolume(volume);
            }
        });

        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                settingsManager.onDrawerClosed();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.chat_toolbar_menu, menu);
        return true;
    }

    @Override
    @SuppressLint("NullableProblems")
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_new_chat) {
            startNewSession();
            return true;
        } else if (itemId == R.id.action_settings) {
            drawerLayout.openDrawer(GravityCompat.END);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    private void applyVolume(int percentage) {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                double volume = percentage / 100.0;
                int targetVol = Math.max(0, Math.min(maxVol, (int) Math.round(volume * maxVol)));
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                Log.i(TAG, "Media volume set to " + percentage + "% (raw: " + targetVol + "/" + maxVol + ")");
            }
        } catch (Exception e) {
            Log.w(TAG, "Setting media volume failed", e);
        }
    }

    private void startNewSession() {
        // Reset mute state for new session
        isMuted = false;
        
        // Update UI immediately
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.status_starting_new_session));
            // Conversation history managed by Realtime API
            messageList.clear();
            //noinspection NotifyDataSetChanged
            chatAdapter.notifyDataSetChanged();
        });

        // Perform disconnect and reconnect asynchronously on network thread
        threadManager.executeNetwork(() -> {
            // Clean up session-scoped images on I/O thread
            threadManager.executeIO(this::deleteSessionImages);
            // 1. Disconnect the old session
            disconnectWebSocket();

            // Brief pause to ensure resources are released
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // 2. Connect to start a new session
            Future<Void> connectFuture = connectWebSocket();

            // 3. Update UI based on reconnection result
            connectFuture.thenConsume(future -> {
                if (future.hasError()) {
                    Log.e(TAG, "Failed to start new session", future.getError());
                    runOnUiThread(() -> {
                        addMessage(getString(R.string.new_session_error), ChatMessage.Sender.ROBOT);
                        statusTextView.setText(getString(R.string.error_connection_failed_short));
                    });
                } else {
                    Log.i(TAG, "New session started successfully. Ready to listen.");
                    // The "Ready" message is handled by the "session.updated" event.
                    // We can now start listening for user input.
                    startContinuousRecognition();
                }
            });
        });
    }

    private Future<Void> warmupSpeechRecognizer() {
        Promise<Void> promise = new Promise<>();
        threadManager.executeAudio(() -> {
            try {
                if (sttManager == null) sttManager = new SpeechRecognizerManager();
                if (visionService == null) visionService = new VisionService(this);
                if (sessionManager == null) sessionManager = new RealtimeSessionManager();
                if (eventHandler == null) eventHandler = new RealtimeEventHandler(new RealtimeEventHandler.Listener() {
                    @Override public void onSessionUpdated(JSONObject session) {
                        Log.i(TAG, "Session configured successfully.");
                        // Try to read output_audio_format.sample_rate_hz to align AudioTrack
                        try {
                            if (session != null && session.has("output_audio_format")) {
                                JSONObject fmt = session.optJSONObject("output_audio_format");
                                if (fmt != null) {
                                    int sr = fmt.optInt("sample_rate_hz", 0);
                                    if (sr > 0 && audioPlayer != null) {
                                        Log.i(TAG, "Applying session sample rate: " + sr);
                                        audioPlayer.setSampleRate(sr);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to apply session sample rate", e);
                        }
                        // Do not change status to "Ready" if the app is still in the initial startup/warmup phase.
                        if (!isWarmingUp) {
                            runOnUiThread(() -> statusTextView.setText(getString(R.string.status_ready)));
                        }
                        if (connectionPromise != null && !connectionPromise.getFuture().isDone()) {
                            connectionPromise.setValue(null);
                            connectionPromise = null;
                        }
                    }
                    @Override public void onAudioTranscriptDelta(String delta, String responseId) {
                        runOnUiThread(() -> {
                            if (Objects.equals(responseId, cancelledResponseId)) {
                                return; // drop transcript of cancelled response
                            }
                            // Set prefix exactly once when the very first delta of a response arrives
                            CharSequence current = statusTextView.getText();
                            if (current == null || current.length() == 0 || !current.toString().startsWith("Speaking — tap to interrupt: ")) {
                                statusTextView.setText(getString(R.string.status_speaking_tap_to_interrupt));
                            }
                            statusTextView.append(delta);
                            boolean needNew = expectingFinalAnswerAfterToolCall
                                    || messageList.isEmpty()
                                    || messageList.get(messageList.size() - 1).getSender() != ChatMessage.Sender.ROBOT;
                            if (needNew) {
                                addMessage(delta, ChatMessage.Sender.ROBOT);
                                expectingFinalAnswerAfterToolCall = false;
                            } else {
                                ChatMessage last = messageList.get(messageList.size() - 1);
                                last.setMessage(last.getMessage() + delta);
                                chatAdapter.notifyItemChanged(messageList.size() - 1);
                            }
                        });
                    }
                    @Override public void onAudioDelta(byte[] pcm16, String responseId) {
                        // As soon as the first audio chunk arrives, switch to SPEAKING to prevent mic feedback
                        if (!audioPlayer.isPlaying()) {
                            turnManager.setState(TurnManager.State.SPEAKING);
                        }
                        if (responseId != null) currentResponseId = responseId;
                        if (Objects.equals(responseId, cancelledResponseId)) {
                            return; // drop cancelled response chunks
                        }
                hasActiveResponse = true;
                        audioPlayer.addChunk(pcm16);
                        audioPlayer.startIfNeeded();
                    }
                    @Override public void onAudioDone() {

                        audioPlayer.markResponseDone();
                hasActiveResponse = false;
                    }
                    @Override public void onResponseDone(JSONObject response) {
                        try {
                            // Reuse existing logic by directly passing the raw event
                            // By composing a synthetic JSON and using the fallback
                            JSONObject wrapper = new JSONObject();
                            wrapper.put("type", "response.done");
                            wrapper.put("response", response);
                            handleWebSocketTextMessage(wrapper.toString());
            } catch (Exception e) {
                            Log.e(TAG, "onResponseDone handling failed", e);
                        }
                    }
                    @Override public void onAssistantItemAdded(String itemId) {
                        try {
                            lastAssistantItemId = itemId;
                        } catch (Exception ignored) {}
                    }
                    @Override public void onError(JSONObject error) {
                        String code = error != null ? error.optString("code", "Unknown") : "Unknown";
                        String msg = error != null ? error.optString("message", "An unknown error occurred.") : "";
                        Log.e(TAG, "WebSocket Error Received - Code: " + code + ", Message: " + msg);
                        runOnUiThread(() -> {
                            addMessage(getString(R.string.server_error_prefix, msg), ChatMessage.Sender.ROBOT);
                            statusTextView.setText(getString(R.string.error_generic, code));
                        });
                        if (connectionPromise != null && !connectionPromise.getFuture().isDone()) {
                            connectionPromise.setError("Server returned an error during setup: " + msg);
                            connectionPromise = null;
                        }
                    }
                    @Override public void onUnknown(String type, JSONObject raw) {
                        // Fallback: use existing handlers
                        if (raw != null) handleWebSocketTextMessage(raw.toString());
                    }
                });
                sttManager.setListener(new SpeechRecognizerManager.Listener() {
                    @Override public void onRecognizing(String partialText) {
                        runOnUiThread(() -> {
                    if (statusTextView.getText().toString().startsWith("Listening")) {
                                statusTextView.setText(getString(R.string.status_listening_partial, partialText));
                            }
                        });
                    }
                    @Override public void onRecognized(String text) {
                        if (text != null && !text.isEmpty()) {
                            // Gate STT: only accept in LISTENING state, prevents echo during tool follow-up responses
                            if (turnManager != null && turnManager.getState() != TurnManager.State.LISTENING) {
                                Log.i(TAG, "Ignoring STT result because state=" + turnManager.getState());
                    return;
                }
                            stopContinuousRecognition();
                            runOnUiThread(() -> {
                                addMessage(text, ChatMessage.Sender.USER);
                                sendTextToAzure(text);
                            });
                        }
                    }
                    @Override public void onError(Exception ex) {
                        Log.e(TAG, "STT error", ex);
                        runOnUiThread(() -> statusTextView.setText(getString(R.string.error_generic, ex.getMessage())));
                    }
                });
                String langCode = settingsManager.getLanguage();
                int silenceTimeout = settingsManager.getSilenceTimeout();
                sttManager.initialize(keyManager.getAzureSpeechKey(), keyManager.getAzureSpeechRegion(), langCode, silenceTimeout);

                // Perform the actual warmup and wait for it to complete.
                sttManager.warmup();

                Log.i(TAG, "Speech Recognizer initialized and warmed up.");
                promise.setValue(null);
            } catch (Exception ex) {
                Log.e(TAG, "STT Init/Warmup failed", ex);
                runOnUiThread(() -> statusTextView.setText(getString(R.string.error_generic, ex.getMessage())));
                promise.setError(ex.getMessage());
            }
        });
        return promise.getFuture();
    }

    private Future<Void> initializeSpeechRecognizer() {
        Promise<Void> promise = new Promise<>();
        threadManager.executeAudio(() -> {
            try {
                // Get selected language from settings
                String langCode = settingsManager.getLanguage();
                int silenceTimeout = settingsManager.getSilenceTimeout();
                
                SpeechConfig speechConfig = SpeechConfig.fromSubscription(keyManager.getAzureSpeechKey(), keyManager.getAzureSpeechRegion());
                speechConfig.setSpeechRecognitionLanguage(langCode);
                speechConfig.setProperty("Speech_SegmentationSilenceTimeoutMs", String.valueOf(silenceTimeout));

                // Close existing recognizer before creating a new one
                if (sttManager != null) {
                    sttManager.shutdown();
                }
                sttManager = new SpeechRecognizerManager();
                sttManager.setListener(new SpeechRecognizerManager.Listener() {
                    @Override public void onRecognizing(String partialText) {
                        runOnUiThread(() -> {
                    if (statusTextView.getText().toString().startsWith("Listening")) {
                                statusTextView.setText(getString(R.string.status_listening_partial, partialText));
                            }
                        });
                    }
                    @Override public void onRecognized(String text) {
                        if (text != null && !text.isEmpty()) {
                            stopContinuousRecognition();
                            runOnUiThread(() -> {
                                addMessage(text, ChatMessage.Sender.USER);
                                sendTextToAzure(text);
                            });
                        }
                    }
                    @Override public void onError(Exception ex) {
                        Log.e(TAG, "STT error", ex);
                        runOnUiThread(() -> statusTextView.setText(getString(R.string.error_generic, ex.getMessage())));
                    }
                });
                sttManager.initialize(keyManager.getAzureSpeechKey(), keyManager.getAzureSpeechRegion(), langCode, silenceTimeout);
                 Log.i(TAG, "Speech Recognizer initialized for language: " + langCode + ", silence timeout: " + silenceTimeout + "ms");
                 promise.setValue(null);

            } catch (Exception ex) {
                Log.e(TAG, "STT Init failed", ex);
                runOnUiThread(() -> statusTextView.setText(getString(R.string.error_generic, ex.getMessage())));
                promise.setError(ex.getMessage());
            }
        });
        return promise.getFuture();
    }

    private void startContinuousRecognition() {
        if (sttManager == null) {
            Log.w(TAG, "Speech recognizer not initialized yet, cannot start recognition.");
            runOnUiThread(()-> statusTextView.setText(getString(R.string.status_recognizer_not_ready)));
            return;
        }
        threadManager.executeAudio(() -> {
            try {
                sttManager.startContinuous();
                runOnUiThread(() -> statusTextView.setText(getString(R.string.status_listening)));
                Log.i(TAG, "Continuous recognition started.");
            } catch (Exception ex) {
                Log.e(TAG, "startContinuousRecognition failed", ex);
                runOnUiThread(() -> statusTextView.setText(getString(R.string.error_starting_listener, ex.getMessage())));
            }
        });
    }

    private void stopContinuousRecognition() {
        if (sttManager != null) {
            threadManager.executeAudio(() -> {
                try {
                    sttManager.stopContinuous();
                    Log.i(TAG, "Continuous recognition stopped.");
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping speech recognizer for speech", e);
                }
            });
        }
    }

    private void sendTextToAzure(String text) {
        if (sessionManager == null || !sessionManager.isConnected()) {
            Log.e(TAG, "WebSocket is not connected. Cannot send message.");
            runOnUiThread(() -> addMessage(getString(R.string.error_not_connected), ChatMessage.Sender.ROBOT));
            return;
        }

        Log.d(TAG, "Step 1: Send user message to conversation...");
        if (turnManager != null && turnManager.getState() != TurnManager.State.SPEAKING) {
            turnManager.setState(TurnManager.State.THINKING);
        }
        // Reset response state for new response
    
        try {
            // Step 1: Send the user message to add it to the server-side history.
            JSONObject createItemPayload = new JSONObject();
            createItemPayload.put("type", "conversation.item.create");
    
            JSONObject item = new JSONObject();
            item.put("type", "message");
            item.put("role", "user");
    
            JSONArray contentArray = new JSONArray();
            JSONObject content = new JSONObject();
            content.put("type", "input_text");
            content.put("text", text);
            contentArray.put(content);
    
            item.put("content", contentArray);
            createItemPayload.put("item", item);
    
            sessionManager.send(createItemPayload.toString());
            Log.d(TAG, "Sent conversation.item.create: " + createItemPayload);
    
            // User message added to server-side conversation history
    
            // Step 2: Request a response from the model.
            JSONObject createResponsePayload = new JSONObject();
            createResponsePayload.put("type", "response.create");
            
            JSONObject responseDetails = new JSONObject();
            responseDetails.put("modalities", new JSONArray().put("audio").put("text"));
            // NO 'input' or 'prompt' here! The model uses the server-side history.
            createResponsePayload.put("response", responseDetails);
    
            sessionManager.send(createResponsePayload.toString());
            Log.d(TAG, "Sent response.create: " + createResponsePayload);

        } catch (Exception e) {
            Log.e(TAG, "Failed to create or send WebSocket message", e);
        }
    }

    // tools definition moved to ToolRegistry

    private void handleWebSocketTextMessage(String text) {
        webSocketHandler.handleMessage(text);
    }

    private void handleResponseDone(JSONObject message) {
                    Log.i(TAG, "Full response received. Processing final output.");
                    try {
                        JSONObject responseObject = message.getJSONObject("response");
                        JSONArray outputArray = responseObject.optJSONArray("output");

                        if (outputArray == null || outputArray.length() == 0) {
                            Log.i(TAG, "Response.done with no output. Finishing turn.");
                            if (!audioPlayer.isPlaying()) {
                                gestureController.stopNow();
                                if (turnManager != null) turnManager.setState(TurnManager.State.LISTENING);
                            }
                return;
                        }

                        List<JSONObject> functionCalls = new ArrayList<>();
                        List<JSONObject> messageItems = new ArrayList<>();

                        for (int i = 0; i < outputArray.length(); i++) {
                            JSONObject out = outputArray.getJSONObject(i);
                            String outType = out.optString("type");
                            if ("function_call".equals(outType)) {
                                functionCalls.add(out);
                            } else if ("message".equals(outType)) {
                                messageItems.add(out);
                            }
                        }

                        if (!functionCalls.isEmpty()) {
                            for (JSONObject fc : functionCalls) {
                                String toolName = fc.getString("name");
                                String callId = fc.getString("call_id");
                                String argsString = fc.getString("arguments");
                                JSONObject args = new JSONObject(argsString);

                                final String fToolName = toolName;
                                final String fArgsString = args.toString();
                                runOnUiThread(() -> addMessage(getString(R.string.function_call_prefix, fToolName, fArgsString), ChatMessage.Sender.ROBOT));

                                if ("analyze_vision".equals(toolName)) {
                                    startAnalyzeVisionFlow(callId, args.optString("prompt", ""));
                                    } else {
                                    String result = toolExecutor.execute(toolName, args);
                                    final String fResult = result;
                                    runOnUiThread(() -> addMessage(getString(R.string.function_result_prefix, fResult), ChatMessage.Sender.ROBOT));
                                    sendToolResult(callId, result);
                                }
                                expectingFinalAnswerAfterToolCall = true;
                            }
                        }

                        if (!messageItems.isEmpty()) {
                            try {
                                JSONObject firstMsg = messageItems.get(0);
                                JSONObject assistantMessage = new JSONObject();
                                assistantMessage.put("role", "assistant");
                                assistantMessage.put("content", firstMsg.optJSONArray("content"));
                                Log.d(TAG, "Final assistant message added to local history.");
                                } catch (Exception e) {
                                Log.e(TAG, "Could not save final assistant message to history", e);
                            }
                        }
                                } catch (Exception e) {
                        Log.e(TAG, "Error processing response.done message. Attempting to recover.", e);
                        if (!audioPlayer.isPlaying()) {
                            gestureController.stopNow();
                            if (turnManager != null) turnManager.setState(TurnManager.State.LISTENING);
                        }
                    }
    }

    private void sendToolResult(String callId, String result) {
        if (sessionManager == null || !sessionManager.isConnected()) return;
        try {
            // Mark that a follow-up answer is expected; mic will only be paused when audio actually starts
            expectingFinalAnswerAfterToolCall = true;
 
            JSONObject toolResultPayload = new JSONObject();
            toolResultPayload.put("type", "conversation.item.create");
            
            JSONObject item = new JSONObject();
            item.put("type", "function_call_output");
            item.put("call_id", callId);
            item.put("output", result);
            
            toolResultPayload.put("item", item);
            
            sessionManager.send(toolResultPayload.toString());
            Log.d(TAG, "Sent tool result: " + toolResultPayload);
 
            // After sending the tool result, we must ask for a new response
            JSONObject createResponsePayload = new JSONObject();
            createResponsePayload.put("type", "response.create");
            sessionManager.send(createResponsePayload.toString());
            Log.d(TAG, "Requested new response after tool call.");
 
        } catch (Exception e) {
            Log.e(TAG, "Error sending tool result", e);
        }
    }
    


    private void disconnectWebSocket() {
        if (sessionManager != null) {
            sessionManager.close(1000, "User initiated disconnect");
        }
        // It's better not to shut down the client, as we want to reuse it.
        Log.i(TAG, "WebSocket connection closed.");
    }
    
    private void showQuizDialog(final String question, final String[] options, final String correctAnswer) {
        if (isFinishing()) { Log.w(TAG, "Not showing quiz dialog because activity is finishing."); return; }
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_quiz, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.QuizDialog);
        builder.setView(dialogView);
        builder.setCancelable(false);
        
        // Get references to the UI elements
        TextView questionTextView = dialogView.findViewById(R.id.quiz_question_textview);
        Button option1Button = dialogView.findViewById(R.id.quiz_option_1);
        Button option2Button = dialogView.findViewById(R.id.quiz_option_2);
        Button option3Button = dialogView.findViewById(R.id.quiz_option_3);
        Button option4Button = dialogView.findViewById(R.id.quiz_option_4);
        List<Button> buttons = new ArrayList<>();
        buttons.add(option1Button);
        buttons.add(option2Button);
        buttons.add(option3Button);
        buttons.add(option4Button);

        // Set question and button texts
        questionTextView.setText(question);
        for (int i = 0; i < buttons.size() && i < options.length; i++) {
            buttons.get(i).setText(options[i]);
        }

        final AlertDialog dialog = builder.create();

        // Set onClick listeners for each button
        for (final Button button : buttons) {
            button.setOnClickListener(v -> {
                // NEW: Interrupt robot if it's currently speaking
                if (turnManager != null && turnManager.getState() == TurnManager.State.SPEAKING) {
                    if (hasActiveResponse || (audioPlayer != null && audioPlayer.isPlaying())) {
                        fabInterrupt.performClick();
                    }
                }

                String selectedOption = button.getText().toString();
                boolean isCorrect = selectedOption.equals(correctAnswer);

                // Disable all buttons to prevent multiple answers
                for (Button b : buttons) {
                    b.setEnabled(false);
                }

                // Color the buttons based on the answer
                if (isCorrect) {
                    int green = ContextCompat.getColor(this, R.color.correct_green);
                    button.setBackgroundTintList(ColorStateList.valueOf(green));
                    button.setTextColor(Color.WHITE);
                } else {
                    int red = ContextCompat.getColor(this, R.color.incorrect_red);
                    button.setBackgroundTintList(ColorStateList.valueOf(red));
                    button.setTextColor(Color.WHITE);
                    for (Button b : buttons) {
                        if (b.getText().toString().equals(correctAnswer)) {
                            int green = ContextCompat.getColor(this, R.color.correct_green);
                            b.setBackgroundTintList(ColorStateList.valueOf(green));
                            b.setTextColor(Color.WHITE);
                        }
                    }
                }

                // Send feedback to the model
                String feedbackMessage = getString(R.string.quiz_feedback_format, question, selectedOption);
                stopContinuousRecognition();
                addMessage(feedbackMessage, ChatMessage.Sender.USER);
                sendTextToAzure(feedbackMessage);

                // Close the dialog after a delay
                new Handler(Looper.getMainLooper()).postDelayed(dialog::dismiss, 4000); // 4 seconds delay
            });
        }
        
        dialog.show();
        // Make dialog full-screen
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }



    // Wrapper to initiate WebSocket connection via sessionManager and wait for session.updated
    private Future<Void> connectWebSocket() {
        connectionPromise = new Promise<>();
        if (sessionManager == null) {
            sessionManager = new RealtimeSessionManager();
            sessionManager.setListener(new RealtimeSessionManager.Listener() {
                @Override public void onOpen(Response response) { ChatActivity.this.sendInitialSessionConfig(); }
                @Override public void onTextMessage(String text) { if (eventHandler != null) eventHandler.handle(text); else handleWebSocketTextMessage(text); }
                @Override public void onBinaryMessage(okio.ByteString bytes) { }
                @Override public void onClosing(int code, String reason) { }
                @Override public void onClosed(int code, String reason) {
                    if (connectionPromise != null && !connectionPromise.getFuture().isDone()) {
                        connectionPromise.setError("WebSocket closed before session was updated.");
                        connectionPromise = null;
                    }
                }
                @Override public void onFailure(Throwable t, Response response) {
                    Log.e(TAG, "WebSocket Failure: " + t.getMessage(), t);
                    if (connectionPromise != null && !connectionPromise.getFuture().isDone()) {
                        connectionPromise.setError("WebSocket Failure: " + t.getMessage());
                        connectionPromise = null;
                    }
                }
            });
        }
        if (sessionManager.isConnected()) {
            connectionPromise.setValue(null);
            return connectionPromise.getFuture();
        }
        String apiVersion = "2024-10-01-preview";
        String model = settingsManager.getModel();
        String url = String.format(Locale.US, "wss://%s/openai/realtime?api-version=%s&deployment=%s",
                keyManager.getAzureOpenAiEndpoint(), apiVersion, model);
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        headers.put("api-key", keyManager.getAzureOpenAiKey());
        headers.put("OpenAI-Beta", "realtime=v1");
        Log.d(TAG, "Connecting to WebSocket URL: " + url);
        sessionManager.connect(url, headers);
        return connectionPromise.getFuture();
    }

    private void releaseAudioTrack() {
        if (audioPlayer != null) audioPlayer.release();
        try { gestureController.shutdown(); } catch (Exception ignored) {}
        Log.i(TAG, "AudioTrack released.");
    }

    private void startExplainGesturesLoop() {
        if (qiContext == null) return;
        gestureController.start(qiContext,
                () -> turnManager != null && turnManager.getState() == TurnManager.State.SPEAKING && qiContext != null,
                this::getRandomExplainAnimationResId);
    }

    private Integer getRandomExplainAnimationResId() {
        int[] ids = new int[] {
                R.raw.explain_01,
                R.raw.explain_02,
                R.raw.explain_03,
                R.raw.explain_04,
                R.raw.explain_05,
                R.raw.explain_06,
                R.raw.explain_07,
                R.raw.explain_08,
                R.raw.explain_09,
                R.raw.explain_10,
                R.raw.explain_11
        };
        java.util.Random rnd = new java.util.Random();
        return ids[rnd.nextInt(ids.length)];
    }







    private void addMessage(String text, ChatMessage.Sender sender) {
        runOnUiThread(() -> {
            messageList.add(new ChatMessage(text, sender));
            chatAdapter.notifyItemInserted(messageList.size() - 1);
            chatRecyclerView.scrollToPosition(messageList.size() - 1);
             if (sender == ChatMessage.Sender.USER) {
                 statusTextView.setText(getString(R.string.status_thinking));
             }
        });
    }



    private void addImageMessage(String imagePath) {
        runOnUiThread(() -> {
            ChatMessage msg = new ChatMessage("", imagePath, ChatMessage.Sender.ROBOT);
            messageList.add(msg);
            chatAdapter.notifyItemInserted(messageList.size() - 1);
            chatRecyclerView.scrollToPosition(messageList.size() - 1);
        });
    }

    private void startAnalyzeVisionFlow(String callId, String prompt) {
        String apiKey = keyManager.getGroqApiKey();
        runOnUiThread(() -> statusTextView.setText(getString(R.string.status_analyzing_vision)));
        visionService.startAnalyze(prompt, apiKey, new VisionService.Callback() {
            @Override public void onResult(String resultJson) {
                try {
                    // Append context for the realtime response so it speaks in "Du" form
                    org.json.JSONObject obj = new org.json.JSONObject(resultJson);
                    String desc = obj.optString("description", "");
                    String context = getString(R.string.vision_context_suffix);
                    if (!desc.isEmpty()) obj.put("description", desc + context);
                    String adjusted = obj.toString();
                    sendToolResult(callId, adjusted);
                    runOnUiThread(() -> addMessage(getString(R.string.function_result_prefix, adjusted), ChatMessage.Sender.ROBOT));
                } catch (Exception e) {
                    // Fallback: if parsing fails, send original string
                    sendToolResult(callId, resultJson);
                    runOnUiThread(() -> addMessage(getString(R.string.function_result_prefix, resultJson), ChatMessage.Sender.ROBOT));
                }
            }
            @Override public void onError(String errorMessage) {
                sendToolResultSafelyError(callId, errorMessage);
            }
            @Override public void onInfo(String message) {
                runOnUiThread(() -> addMessage(message, ChatMessage.Sender.ROBOT));
                }
            @Override public void onPhotoCaptured(String path) {
                // Only show image in chat, without additional text
                addImageMessage(path);
                synchronized (sessionImagePaths) { sessionImagePaths.add(path); }
            }
        });
        }
    // Camera2 moved into VisionService

    private void sendToolResultSafelyError(String callId, String errorMsg) {
        try {
            JSONObject err = new JSONObject();
            err.put("error", errorMsg);
            if (callId != null) sendToolResult(callId, err.toString());
            runOnUiThread(() -> addMessage(getString(R.string.function_result_error_prefix, errorMsg), ChatMessage.Sender.ROBOT));
        } catch (Exception ignored) {}
    }

    // Sends full initial session.update (voice, temperature, audio format, turn_detection, instructions, tools)
    private void sendInitialSessionConfig() {
        if (sessionManager == null || !sessionManager.isConnected()) return;
        try {
            String voice = settingsManager.getVoice();
            float temperature = settingsManager.getTemperature();
            String systemPrompt = settingsManager.getSystemPrompt();

            JSONObject payload = new JSONObject();
            payload.put("type", "session.update");

            JSONObject sessionConfig = new JSONObject();
            sessionConfig.put("voice", voice);
            sessionConfig.put("temperature", temperature);
            sessionConfig.put("output_audio_format", "pcm16");
            sessionConfig.put("turn_detection", JSONObject.NULL);
            sessionConfig.put("instructions", systemPrompt);
            sessionConfig.put("tools", ToolRegistry.buildToolsDefinitionForAzure(this));

            payload.put("session", sessionConfig);

            sessionManager.send(payload.toString());
            Log.d(TAG, "Sent initial session.update: " + payload);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send initial session.update", e);
        }
    }

    private void deleteSessionImages() {
        synchronized (sessionImagePaths) {
            for (String p : sessionImagePaths) {
                try {
                    if (p != null) {
                        java.io.File f = new java.io.File(p);
                        if (f.exists()) {
                            boolean ok = f.delete();
                            if (!ok) { Log.w(TAG, "Could not delete session image: " + p); }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error deleting session image: " + p, e);
                }
            }
            sessionImagePaths.clear();
        }
    }

    private void showWarmupIndicator() {
        runOnUiThread(() -> {
            warmupIndicatorLayout.setVisibility(View.VISIBLE);
            statusTextView.setText(getString(R.string.status_warming_up));
        });
    }

    private void hideWarmupIndicator() {
        runOnUiThread(() -> {
            warmupIndicatorLayout.setVisibility(View.GONE);
            // The status will be set to Listening by the TurnManager, so no need to set it here.
        });
    }
    
    /**
     * Interrupts any active response and mutes the microphone
     */
    private void interruptAndMute() {
        try {
            // Perform the same interrupt logic as the FAB button
            if (sessionManager != null && sessionManager.isConnected()) {
                // 1) Cancel further generation first (send only if there is an active response)
                if (hasActiveResponse) {
                    JSONObject cancel = new JSONObject();
                    cancel.put("type", "response.cancel");
                    sessionManager.send(cancel.toString());
                    // mark the current response as cancelled to ignore trailing chunks
                    cancelledResponseId = currentResponseId;
                }

                // 2) Truncate current assistant item if we have its id
                if (lastAssistantItemId != null) {
                    int playedMs = audioPlayer != null ? Math.max(0, audioPlayer.getEstimatedPlaybackPositionMs()) : 0;
                    JSONObject truncate = new JSONObject();
                    truncate.put("type", "conversation.item.truncate");
                    truncate.put("item_id", lastAssistantItemId);
                    truncate.put("content_index", 0);
                    truncate.put("audio_end_ms", playedMs);
                    sessionManager.send(truncate.toString());
                }

                // 3) Stop local audio and gestures
                if (audioPlayer != null) audioPlayer.interruptNow();
                gestureController.stopNow();
            }
            
            // 4) Set muted state instead of going directly to listening
            mute();
        } catch (Exception e) {
            Log.e(TAG, "Interrupt and mute failed", e);
        }
    }
    
    /**
     * Mutes the microphone and updates UI
     */
    private void mute() {
        isMuted = true;
        stopContinuousRecognition();
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.status_muted_tap_to_unmute));
            findViewById(R.id.fab_interrupt).setVisibility(View.GONE);
        });
        Log.i(TAG, "Microphone muted - tap status to unmute");
    }
    
    /**
     * Unmutes the microphone and resumes listening
     */
    private void unmute() {
        isMuted = false;
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.status_listening));
            findViewById(R.id.fab_interrupt).setVisibility(View.GONE);
        });
        startContinuousRecognition();
        if (turnManager != null) {
            turnManager.setState(TurnManager.State.LISTENING);
        }
        Log.i(TAG, "Microphone unmuted - resuming listening");
    }
}


