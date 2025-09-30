package io.github.hrilab.pepper_realtime;

import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.media.AudioManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.annotation.SuppressLint;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;

@SuppressWarnings("SpellCheckingInspection")
public class YouTubePlayerDialog {
    
    public interface PlayerEventListener {
        void onPlayerOpened();
        void onPlayerClosed();
    }

    private static final String TAG = "YouTubePlayerDialog";
    
    private final Context context;
    private final PlayerEventListener eventListener;
    private AlertDialog dialog;
    private WebView webView;
    private AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {};
    
    // UI elements
    // Only keep WebView as a field (used in closePlayer)

    // Bridge to receive events from WebView JS
    private static class JsBridge {
        @SuppressWarnings("unused")
        @JavascriptInterface public void onReady() { Log.d(TAG, "JS onReady"); }
        @SuppressWarnings("unused")
        @JavascriptInterface public void onState(String state) { Log.d(TAG, "JS onState: " + state); }
        @SuppressWarnings("unused")
        @JavascriptInterface public void onError(String error) { Log.e(TAG, "JS onError: " + error); }
    }

    public YouTubePlayerDialog(Context context, PlayerEventListener eventListener) {
        this.context = context;
        this.eventListener = eventListener;
        try { this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE); } catch (Exception ignored) {}
    }

    public void playVideo(YouTubeSearchService.YouTubeVideo video) {
        if (video == null) {
            Log.e(TAG, "Cannot play null video");
            return;
        }
        
        Log.i(TAG, "Opening YouTube player for: " + video.getTitle());
        
        // Create and show dialog
        createDialog(video);
        
        // Notify listener
        if (eventListener != null) {
            eventListener.onPlayerOpened();
        }
    }

    private void createDialog(YouTubeSearchService.YouTubeVideo video) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_youtube_player, null);
        
        // Initialize UI elements
        TextView videoTitle = dialogView.findViewById(R.id.youtube_video_title);
        TextView channelTitle = dialogView.findViewById(R.id.youtube_channel_title);
        Button closeButton = dialogView.findViewById(R.id.youtube_close_button);
        webView = dialogView.findViewById(R.id.youtube_webview);
        
        // Set video information
        videoTitle.setText(video.getTitle());
        channelTitle.setText(video.getChannelTitle());
        
        // Setup WebView
        setupWebView(video.getVideoId());
        
        // Setup close button
        closeButton.setOnClickListener(v -> closePlayer());
        
        // Create dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(dialogView);
        builder.setCancelable(false); // Force use of close button
        
        dialog = builder.create();
        dialog.show();
        
        // Make dialog full-screen
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        try {
            webView.onResume();
            webView.resumeTimers();
        } catch (Exception ignored) {}

        // Request transient audio focus for media playback
        try {
            if (audioManager != null) {
                audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception ignored) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(String videoId) {
        // Prefer hardware acceleration for HTML5 video playback
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        
        // Configure WebView for YouTube embed
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        webSettings.setMediaPlaybackRequiresUserGesture(false); // Allow autoplay
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Use a modern mobile user agent for better YouTube compatibility on old WebView
        webSettings.setUserAgentString("Mozilla/5.0 (Linux; Android 8.0; SM-G955U Build/R16NW) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.141 Mobile Safari/537.36");
        
        // Pepper-specific optimizations to prevent OpenGL errors
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // setRenderPriority is deprecated on modern WebView; omit for compatibility
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setBlockNetworkImage(false);
        webSettings.setBlockNetworkLoads(false);
        
        // Disable problematic features for embedded systems
        webSettings.setGeolocationEnabled(false);
        webSettings.setDatabaseEnabled(false);

        // Accept cookies (and third-party cookies) for YouTube playback
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        } catch (Throwable ignored) {}
        
        // Set WebView client to handle navigation
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(TAG, "WebView attempting to load: " + url);
                // Keep navigation within WebView for YouTube embed
                return !(url.contains("youtube.com") || url.contains("youtu.be"));
            }
            
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl() != null ? request.getUrl().toString() : "";
                return !(url.contains("youtube.com") || url.contains("youtu.be"));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "YouTube embed loaded: " + url);
            }
            
            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Log.e(TAG, "WebView error: " + errorCode + " - " + description + " for URL: " + failingUrl);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                try {
                    String url = request != null && request.getUrl() != null ? request.getUrl().toString() : "";
                    CharSequence desc = error != null ? error.getDescription() : "";
                    Log.e(TAG, "WebView error: " + desc + " for URL: " + url);
                } catch (Exception ignored) {}
            }
        });
        
        // Add WebChromeClient for better YouTube support
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                Log.d(TAG, "WebView console: " + message.message() + " [" + message.sourceId() + ":" + message.lineNumber() + "]");
                return true; // Indicate that the message was handled
            }
            
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                try {
                    request.grant(request.getResources());
                    Log.d(TAG, "Granted WebView permission request");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to grant WebView permissions", e);
                }
            }
            
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                Log.d(TAG, "WebView custom view shown");
                super.onShowCustomView(view, callback);
            }
            
            @Override
            public void onHideCustomView() {
                Log.d(TAG, "WebView custom view hidden");
                super.onHideCustomView();
            }
        });

        // JS bridge for debug and fallback control
        try { webView.addJavascriptInterface(new JsBridge(), "Android"); } catch (Throwable ignored) {}
        
        // Set black background to prevent flashing
        webView.setBackgroundColor(android.graphics.Color.BLACK);
        
        // Load YouTube via IFrame API to improve autoplay compatibility
        String html = "" +
                "<!DOCTYPE html>" +
                "<html><head><meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1'>" +
                "<style>html,body,#player{margin:0;padding:0;background:#000;height:100%;width:100%;overflow:hidden}</style>" +
                "</head><body>" +
                "<div id='player'></div>" +
                "<script>" +
                "  window.onerror = function(msg, url, line, col, error){" +
                "    console.log('window.onerror: '+msg+' @'+url+':'+line); return false; };" +
                "  window.addEventListener('message', function(e){ try{ console.log('postMessage: '+JSON.stringify(e.data)); }catch(err){} });" +
                "  var player;" +
                "  function onYouTubeIframeAPIReady(){" +
                "    player = new YT.Player('player', {" +
                "      host: 'https://www.youtube-nocookie.com'," +
                "      videoId: '" + videoId + "'," +
                "      playerVars: {" +
                "        autoplay: 1, playsinline: 1, rel: 0, modestbranding: 1, fs: 0, mute: 1, origin: 'https://www.youtube.com'" +
                "      }," +
                "      events: {" +
                "        onReady: function(e){ try { Android.onReady(); e.target.mute(); e.target.playVideo(); console.log('YT ready'); } catch(err){ console.log('onReady error: '+err); } }," +
                "        onStateChange: function(e){ try { Android.onState(''+e.data); console.log('YT state: '+e.data); } catch(err){} }," +
                "        onError: function(e){ try { Android.onError(''+e.data); console.log('YT error: '+e.data); } catch(err){} }" +
                "      }" +
                "    });" +
                "  }" +
                // Fallback: if not playing within 4s, redirect to m.youtube
                "  setTimeout(function(){ try { var s = (player && player.getPlayerState) ? player.getPlayerState() : -2; if (s !== 1) { console.log('Fallback redirect to m.youtube'); location.replace('https://m.youtube.com/watch?v=" + videoId + "&autoplay=1'); } } catch(err) { console.log('fallback error: '+err); } }, 4000);" +
                "</script>" +
                "<script src='https://www.youtube.com/iframe_api'></script>" +
                "</body></html>";
        webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null);
    }

    public void closePlayer() {
        Log.i(TAG, "Closing YouTube player");
        
        // Stop WebView
        if (webView != null) {
            try { webView.onPause(); webView.pauseTimers(); } catch (Exception ignored) {}
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.clearCache(true);
            webView.destroy();
        }

        // Release audio focus
        try {
            if (audioManager != null) {
                audioManager.abandonAudioFocus(audioFocusChangeListener);
            }
        } catch (Exception ignored) {}
        
        // Close dialog
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        
        // Notify listener
        if (eventListener != null) {
            eventListener.onPlayerClosed();
        }
    }

    // isShowing() removed as it was unused
}
