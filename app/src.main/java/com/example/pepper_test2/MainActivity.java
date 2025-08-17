package com.example.pepper_test2;

import android.os.Bundle;
import android.widget.TextView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;

import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.QiSDK;
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks;
import com.aldebaran.qi.sdk.builder.AnimateBuilder;
import com.aldebaran.qi.sdk.builder.AnimationBuilder;
import com.aldebaran.qi.sdk.builder.SayBuilder;
import com.aldebaran.qi.sdk.object.actuation.Animate;
import com.aldebaran.qi.sdk.object.actuation.Animation;
import com.aldebaran.qi.sdk.object.conversation.Say;
import com.aldebaran.qi.sdk.object.locale.Language;
import com.aldebaran.qi.sdk.object.locale.Region;
import com.aldebaran.qi.sdk.object.locale.Locale;
import com.aldebaran.qi.sdk.design.activity.RobotActivity;
import com.aldebaran.qi.Future;

import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;
import com.microsoft.cognitiveservices.speech.ResultReason;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends RobotActivity implements RobotLifecycleCallbacks {

    private static final String TAG = "MainActivity";
    // !!! WICHTIG: Ersetze diese Platzhalter durch deine echten Azure-Zugangsdaten !!!
    private static final String SPEECH_KEY = "1U0lo13SeJrizOLXh4m1u1pFKTmEDdRM8ISc2nqB83rudnMhZBfXJQQJ99BAACI8hq2XJ3w3AAAYACOGCH40";
    private static final String SPEECH_REGION = "switzerlandnorth";
    private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 1;

    private QiContext qiContext = null;
    private Future<Void> currentAction = null;
    private Locale englishLocale = new Locale(Language.ENGLISH, Region.UNITED_STATES);
    
    private TextView recognizedTextView;
    private SpeechRecognizer speechRecognizer;
    private ExecutorService executorService = Executors.newCachedThreadPool();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        QiSDK.register(this, this);

        recognizedTextView = findViewById(R.id.recognizedTextView);

        findViewById(R.id.button_hello).setOnClickListener(v -> saySomething("Hello! How are you?"));
        findViewById(R.id.button_animate).setOnClickListener(v -> playAnimation(R.raw.hello_01));
        findViewById(R.id.button_joke).setOnClickListener(v -> saySomething("Why didn't the teddy bear eat his cake? He was already stuffed!"));
        findViewById(R.id.button_dance).setOnClickListener(v -> playAnimation(R.raw.funny_01));
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onDestroy() {
        if (currentAction != null) {
            currentAction.cancel(true);
        }
        if (speechRecognizer != null) {
            executorService.submit(() -> {
                try {
                    speechRecognizer.stopContinuousRecognitionAsync().get();
                    speechRecognizer.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping speech recognizer", e);
                }
            });
            executorService.shutdown();
        }
        QiSDK.unregister(this, this);
        super.onDestroy();
    }

    @Override
    public void onRobotFocusGained(QiContext qiContext) {
        this.qiContext = qiContext;
        
        SayBuilder.with(qiContext)
                .withText("Hello, I'm ready. I am listening now.")
                .withLocale(englishLocale)
                .buildAsync()
                .andThenCompose(say -> {
                    currentAction = say.async().run();
                    return currentAction;
                }).andThenConsume(future -> startContinuousRecognition());
    }

    @Override
    public void onRobotFocusLost() {
        this.qiContext = null;
        if (currentAction != null) {
            currentAction.cancel(true);
        }
        if (speechRecognizer != null) {
            executorService.submit(() -> {
                try {
                    speechRecognizer.stopContinuousRecognitionAsync().get();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping speech recognizer on focus lost", e);
                }
            });
        }
    }

    @Override
    public void onRobotFocusRefused(String reason) {
        Log.e(TAG, "Robot focus refused: " + reason);
    }

    private void startContinuousRecognition() {
        executorService.submit(() -> {
            try {
                SpeechConfig speechConfig = SpeechConfig.fromSubscription(SPEECH_KEY, SPEECH_REGION);
                speechConfig.setSpeechRecognitionLanguage("en-US");

                speechRecognizer = new SpeechRecognizer(speechConfig);

                speechRecognizer.recognizing.addEventListener((s, e) -> {
                    if (e.getResult().getReason() == ResultReason.RecognizingSpeech) {
                        Log.i(TAG, "Intermediate result: " + e.getResult().getText());
                        runOnUiThread(() -> recognizedTextView.setText(e.getResult().getText()));
                    }
                });

                speechRecognizer.recognized.addEventListener((s, e) -> {
                    if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                        Log.i(TAG, "Final result: " + e.getResult().getText());
                        // Hier können wir später den Text an die LLM-API senden
                    } else if (e.getResult().getReason() == ResultReason.NoMatch) {
                        Log.i(TAG, "No speech could be recognized.");
                    }
                });

                speechRecognizer.canceled.addEventListener((s, e) -> {
                    Log.e(TAG, "Canceled: Reason=" + e.getReason());
                    if (e.getErrorCode() != null) {
                        Log.e(TAG, "Canceled: ErrorCode=" + e.getErrorCode());
                        Log.e(TAG, "Canceled: ErrorDetails=" + e.getErrorDetails());
                    }
                });

                speechRecognizer.sessionStarted.addEventListener((s, e) -> Log.i(TAG, "Speech recognition session started."));
                speechRecognizer.sessionStopped.addEventListener((s, e) -> Log.i(TAG, "Speech recognition session stopped."));

                speechRecognizer.startContinuousRecognitionAsync().get();
                Log.i(TAG, "Continuous recognition started.");

            } catch (Exception ex) {
                Log.e(TAG, "Failed to initialize speech recognizer: " + ex.getMessage());
                runOnUiThread(() -> recognizedTextView.setText("Error: " + ex.getMessage()));
            }
        });
    }

    // ... (saySomething und playAnimation Methoden bleiben unverändert) ...
    private void saySomething(String text) {
        if (qiContext != null) {
            if (currentAction != null && !currentAction.isDone()) {
                currentAction.cancel(true);
            }
            currentAction = SayBuilder.with(qiContext).withText(text).withLocale(englishLocale).buildAsync().andThenCompose(say -> say.async().run());
            currentAction.thenConsume(future -> { if (future.hasError()) { Log.e(TAG, "Say action failed", future.getError()); }});
        }
    }
    private void playAnimation(int resource) {
        if (qiContext != null) {
            if (currentAction != null && !currentAction.isDone()) { currentAction.cancel(true); }
            Future<Animation> animationFuture = AnimationBuilder.with(qiContext).withResources(resource).buildAsync();
            currentAction = animationFuture.andThenCompose(animation -> AnimateBuilder.with(qiContext).withAnimation(animation).build().async().run());
            currentAction.thenConsume(future -> { if (future.hasError()) { Log.e(TAG, "Animation action failed", future.getError()); }});
        }
    }
}

