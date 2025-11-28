package io.github.anonymous.pepper_realtime.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
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
        // Recreating R.layout.dialog_youtube_player programmatically
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // Header Layout
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#202020"))
        }

        val titleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val videoTitle = TextView(context).apply {
            text = video.title
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val channelTitle = TextView(context).apply {
            text = video.channelTitle
            setTextColor(Color.LTGRAY)
            textSize = 14f
            maxLines = 1
        }

        titleLayout.addView(videoTitle)
        titleLayout.addView(channelTitle)

        val closeButton = Button(context).apply {
            text = "Schliessen"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.RED)
            setOnClickListener { closePlayer() }
        }

        headerLayout.addView(titleLayout)
        headerLayout.addView(closeButton)

        // WebView
        webView = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.BLACK)
        }

        dialogView.addView(headerLayout)
        dialogView.addView(webView)

        setupWebView(video.videoId)

        val builder = AlertDialog.Builder(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        builder.setView(dialogView)
        builder.setCancelable(false)

        dialog = builder.create()
        dialog?.show()

        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        try {
            webView?.onResume()
            webView?.resumeTimers()
        } catch (ignored: Exception) {
        }

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

        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val webSettings = wv.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = false // Important for mobile layout
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.mediaPlaybackRequiresUserGesture = false
        
        webSettings.userAgentString =
            "Mozilla/5.0 (Linux; Android 8.0; SM-G955U Build/R16NW) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.141 Mobile Safari/537.36"

        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        @Suppress("DEPRECATION")
        webSettings.databaseEnabled = false

        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(wv, true)
        } catch (ignored: Throwable) { }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return true // Keep everything in WebView
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                try {
                    request.grant(request.resources)
                } catch (e: Exception) { }
            }
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d(TAG, "WebView: ${consoleMessage?.message()}")
                return true
            }
        }

        wv.addJavascriptInterface(JsBridge(), "Android")
        wv.setBackgroundColor(Color.BLACK)

        // Simplified robust implementation: Direct iframe embed
        // Using 'enablejsapi=1' to allow controlling player if needed later
        // 'rel=0' to minimize related videos
        // 'autoplay=1' & 'mute=0' for immediate sound
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
                <style>
                    body, html { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
                    .video-container { position: relative; width: 100%; height: 100%; }
                    iframe { position: absolute; top: 0; left: 0; width: 100%; height: 100%; border: 0; }
                </style>
            </head>
            <body>
                <div class="video-container">
                    <iframe 
                        src="https://www.youtube.com/embed/$videoId?autoplay=1&rel=0&modestbranding=1&playsinline=1&controls=1&fs=0&enablejsapi=1" 
                        allow="autoplay; encrypted-media" 
                        allowfullscreen>
                    </iframe>
                </div>
                <script>
                    // Simple auto-unmute helper
                    var tag = document.createElement('script');
                    tag.src = "https://www.youtube.com/iframe_api";
                    var firstScriptTag = document.getElementsByTagName('script')[0];
                    firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                    var player;
                    function onYouTubeIframeAPIReady() {
                        player = new YT.Player(document.querySelector('iframe'), {
                            events: {
                                'onReady': onPlayerReady
                            }
                        });
                    }
                    function onPlayerReady(event) {
                        event.target.setVolume(100);
                        event.target.unMute();
                        event.target.playVideo();
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        wv.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null)
    }

    fun closePlayer() {
        Log.i(TAG, "Closing YouTube player")
        webView?.let { wv ->
            try {
                wv.onPause()
                wv.pauseTimers()
            } catch (ignored: Exception) { }
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }
        
        try {
            dialog?.dismiss()
            dialog = null
        } catch (ignored: Exception) { }

        try {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        } catch (ignored: Exception) { }

        eventListener?.onPlayerClosed()
    }
}
