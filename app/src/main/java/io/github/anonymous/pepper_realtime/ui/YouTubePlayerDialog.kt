package io.github.anonymous.pepper_realtime.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.TextView
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.service.YouTubeSearchService

@Suppress("SpellCheckingInspection")
class YouTubePlayerDialog(
    private val context: Context,
    private val eventListener: PlayerEventListener?
) {

    interface PlayerEventListener {
        fun onPlayerOpened()
        fun onPlayerClosed()
    }

    companion object {
        private const val TAG = "YouTubePlayerDialog"
    }

    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var audioManager: AudioManager? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    init {
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (ignored: Exception) {
        }
    }

    // Bridge to receive events from WebView JS
    private class JsBridge {
        @Suppress("unused")
        @JavascriptInterface
        fun onReady() {
            Log.d(TAG, "JS onReady")
        }

        @Suppress("unused")
        @JavascriptInterface
        fun onState(state: String) {
            Log.d(TAG, "JS onState: $state")
        }

        @Suppress("unused")
        @JavascriptInterface
        fun onError(error: String) {
            Log.e(TAG, "JS onError: $error")
        }
    }

    fun playVideo(video: YouTubeSearchService.YouTubeVideo?) {
        if (video == null) {
            Log.e(TAG, "Cannot play null video")
            return
        }

        Log.i(TAG, "Opening YouTube player for: ${video.title}")

        // Create and show dialog
        createDialog(video)

        // Notify listener
        eventListener?.onPlayerOpened()
    }

    private fun createDialog(video: YouTubeSearchService.YouTubeVideo) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_youtube_player, null)

        // Initialize UI elements
        val videoTitle: TextView = dialogView.findViewById(R.id.youtube_video_title)
        val channelTitle: TextView = dialogView.findViewById(R.id.youtube_channel_title)
        val closeButton: Button = dialogView.findViewById(R.id.youtube_close_button)
        webView = dialogView.findViewById(R.id.youtube_webview)

        // Set video information
        videoTitle.text = video.title
        channelTitle.text = video.channelTitle

        // Setup WebView
        setupWebView(video.videoId)

        // Setup close button
        closeButton.setOnClickListener { closePlayer() }

        // Create dialog
        val builder = AlertDialog.Builder(context)
        builder.setView(dialogView)
        builder.setCancelable(false) // Force use of close button

        dialog = builder.create()
        dialog?.show()

        // Make dialog full-screen
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        try {
            webView?.onResume()
            webView?.resumeTimers()
        } catch (ignored: Exception) {
        }

        // Request transient audio focus for media playback
        try {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        } catch (ignored: Exception) {
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(videoId: String) {
        val wv = webView ?: return

        // Prefer hardware acceleration for HTML5 video playback
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Configure WebView for YouTube embed
        val webSettings = wv.settings
        webSettings.javaScriptEnabled = true
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.mediaPlaybackRequiresUserGesture = false // Allow autoplay
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Use a modern mobile user agent for better YouTube compatibility on old WebView
        webSettings.userAgentString =
            "Mozilla/5.0 (Linux; Android 8.0; SM-G955U Build/R16NW) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.141 Mobile Safari/537.36"

        // Pepper-specific optimizations to prevent OpenGL errors
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.loadsImagesAutomatically = true
        webSettings.blockNetworkImage = false
        webSettings.blockNetworkLoads = false

        // Disable problematic features for embedded systems
        webSettings.setGeolocationEnabled(false)
        webSettings.databaseEnabled = false

        // Accept cookies (and third-party cookies) for YouTube playback
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(wv, true)
        } catch (ignored: Throwable) {
        }

        // Set WebView client to handle navigation
        wv.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                Log.d(TAG, "WebView attempting to load: $url")
                // Keep navigation within WebView for YouTube embed
                return !(url.contains("youtube.com") || url.contains("youtu.be"))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString() ?: ""
                return !(url.contains("youtube.com") || url.contains("youtu.be"))
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(TAG, "YouTube embed loaded: $url")
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                @Suppress("DEPRECATION")
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "WebView error: $errorCode - $description for URL: $failingUrl")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                try {
                    val url = request?.url?.toString() ?: ""
                    val desc = error?.description ?: ""
                    Log.e(TAG, "WebView error: $desc for URL: $url")
                } catch (ignored: Exception) {
                }
            }
        }

        // Add WebChromeClient for better YouTube support
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "WebView console: ${message.message()} [${message.sourceId()}:${message.lineNumber()}]")
                return true // Indicate that the message was handled
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                try {
                    request.grant(request.resources)
                    Log.d(TAG, "Granted WebView permission request")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to grant WebView permissions", e)
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                Log.d(TAG, "WebView custom view shown")
                super.onShowCustomView(view, callback)
            }

            override fun onHideCustomView() {
                Log.d(TAG, "WebView custom view hidden")
                super.onHideCustomView()
            }
        }

        // JS bridge for debug and fallback control
        try {
            wv.addJavascriptInterface(JsBridge(), "Android")
        } catch (ignored: Throwable) {
        }

        // Set black background to prevent flashing
        wv.setBackgroundColor(Color.BLACK)

        // Load YouTube via IFrame API to improve autoplay compatibility
        val html = """
            <!DOCTYPE html>
            <html><head><meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1'>
            <style>html,body,#player{margin:0;padding:0;background:#000;height:100%;width:100%;overflow:hidden}</style>
            </head><body>
            <div id='player'></div>
            <script>
              window.onerror = function(msg, url, line, col, error){
                console.log('window.onerror: '+msg+' @'+url+':'+line); return false; };
              window.addEventListener('message', function(e){ try{ console.log('postMessage: '+JSON.stringify(e.data)); }catch(err){} });
              var player;
              function onYouTubeIframeAPIReady(){
                player = new YT.Player('player', {
                  host: 'https://www.youtube-nocookie.com',
                  videoId: '$videoId',
                  playerVars: {
                    autoplay: 1, playsinline: 1, rel: 0, modestbranding: 1, fs: 0, mute: 1, origin: 'https://www.youtube.com'
                  },
                  events: {
                    onReady: function(e){ try { Android.onReady(); e.target.mute(); e.target.playVideo(); console.log('YT ready'); } catch(err){ console.log('onReady error: '+err); } },
                    onStateChange: function(e){ try { Android.onState(''+e.data); console.log('YT state: '+e.data); } catch(err){} },
                    onError: function(e){ try { Android.onError(''+e.data); console.log('YT error: '+e.data); } catch(err){} }
                  }
                });
              }
              setTimeout(function(){ try { var s = (player && player.getPlayerState) ? player.getPlayerState() : -2; if (s !== 1) { console.log('Fallback redirect to m.youtube'); location.replace('https://m.youtube.com/watch?v=$videoId&autoplay=1'); } } catch(err) { console.log('fallback error: '+err); } }, 4000);
            </script>
            <script src='https://www.youtube.com/iframe_api'></script>
            </body></html>
        """.trimIndent()

        wv.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null)
    }

    fun closePlayer() {
        Log.i(TAG, "Closing YouTube player")

        // Stop WebView
        webView?.let { wv ->
            try {
                wv.onPause()
                wv.pauseTimers()
            } catch (ignored: Exception) {
            }
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.clearCache(true)
            wv.destroy()
        }

        // Release audio focus
        try {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        } catch (ignored: Exception) {
        }

        // Close dialog
        dialog?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }

        // Notify listener
        eventListener?.onPlayerClosed()
    }
}

