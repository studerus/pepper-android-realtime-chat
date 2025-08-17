package com.example.pepper_test2;

import android.util.Log;

import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SpeechRecognizerManager {
    public interface Listener {
        void onRecognizing(String partialText);
        void onRecognized(String text);
        void onError(Exception ex);
    }

    private static final String TAG = "SpeechRecMgr";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void initialize(String key, String region, String language, int silenceTimeoutMs) {
        executor.submit(() -> {
            try {
                SpeechConfig cfg = SpeechConfig.fromSubscription(key, region);
                cfg.setSpeechRecognitionLanguage(language);
                cfg.setProperty("Speech_SegmentationSilenceTimeoutMs", String.valueOf(silenceTimeoutMs));

                if (recognizer != null) {
                    try { recognizer.stopContinuousRecognitionAsync().get(); } catch (Exception ignored) {}
                    try { recognizer.close(); } catch (Exception ignored) {}
                }
                recognizer = new SpeechRecognizer(cfg);

                recognizer.recognizing.addEventListener((s, e) -> {
                    if (listener != null && e != null) {
                        try { listener.onRecognizing(e.getResult().getText()); } catch (Exception ignored) {}
                    }
                });
                recognizer.recognized.addEventListener((s, e) -> {
                    try {
                        if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                            String text = e.getResult().getText();
                            if (listener != null && text != null && !text.isEmpty()) listener.onRecognized(text);
                        }
                    } catch (Exception ex) {
                        if (listener != null) listener.onError(ex);
                    }
                });
                Log.i(TAG, "Recognizer initialized for " + language + ", silence=" + silenceTimeoutMs + "ms");
            } catch (Exception ex) {
                Log.e(TAG, "Initialize failed", ex);
                if (listener != null) listener.onError(ex);
            }
        });
    }

    public void startContinuous() {
        executor.submit(() -> {
            try { if (recognizer != null) recognizer.startContinuousRecognitionAsync().get(); } catch (Exception ex) { if (listener != null) listener.onError(ex); }
        });
    }

    public void stopContinuous() {
        executor.submit(() -> {
            try { if (recognizer != null) recognizer.stopContinuousRecognitionAsync().get(); } catch (Exception ex) { if (listener != null) listener.onError(ex); }
        });
    }



    public void shutdown() {
        try {
            if (recognizer != null) {
                try { recognizer.stopContinuousRecognitionAsync().get(); } catch (Exception ignored) {}
                try { recognizer.close(); } catch (Exception ignored) {}
                recognizer = null;
            }
        } catch (Exception ignored) {}
        executor.shutdown();
    }
}
