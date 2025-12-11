package ch.fhnw.pepper_realtime.ui

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
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.service.YouTubeSearchService

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
        // Use XML layout like in the old working version
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

        // Prefer hardware acceleration for HTML5 video playback (RESTORED OLD BEHAVIOR)
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
        
        // Remove "wv" from User-Agent to make YouTube think this is a real Chrome browser
        // This prevents "Sign in to prove you're not a bot" checks
        webSettings.userAgentString = webSettings.userAgentString.replace("; wv", "")

        // Pepper-specific optimizations to prevent OpenGL errors
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.loadsImagesAutomatically = true
        webSettings.blockNetworkImage = false
        webSettings.blockNetworkLoads = false

        // Disable problematic features for embedded systems
        webSettings.setGeolocationEnabled(false)
        @Suppress("DEPRECATION")
        webSettings.databaseEnabled = false

        // Accept cookies (and third-party cookies) for YouTube playback
        // Set WebView client to handle navigation
        wv.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                Log.d(TAG, "WebView attempting to load: $url")
                // Keep navigation within WebView for YouTube
                return !(url.contains("youtube.com") || url.contains("youtu.be") || url.contains("googlevideo.com"))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString() ?: ""
                return !(url.contains("youtube.com") || url.contains("youtu.be") || url.contains("googlevideo.com"))
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(TAG, "YouTube page loaded: $url")
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
                return true
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

        // Set black background to prevent flashing
        wv.setBackgroundColor(Color.BLACK)

        // Load mobile YouTube directly - most reliable method
        // IFrame embedding is blocked by YouTube (Error 152), so we skip the wrapper and go straight to m.youtube.com
        val mobileUrl = "https://m.youtube.com/watch?v=$videoId&autoplay=1"
        Log.i(TAG, "Loading mobile YouTube directly: $mobileUrl")
        wv.loadUrl(mobileUrl)
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
