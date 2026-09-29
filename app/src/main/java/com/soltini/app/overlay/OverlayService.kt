package com.soltini.app.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.soltini.app.MainActivity
import com.soltini.app.R
import com.soltini.app.bridge.AriaNativeBridge
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * DraggableFrameLayout — intercepts touch events BEFORE they reach the WebView child.
 * This is the key fix: the WebView normally consumes all touch events, preventing the
 * parent FrameLayout's OnTouchListener from ever firing. By overriding
 * onInterceptTouchEvent we can steal drag gestures while still forwarding taps to the WebView.
 */
class DraggableFrameLayout(context: Context) : FrameLayout(context) {

    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private var initialDownX = 0f
    private var initialDownY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        if (!isDragging) {
            onLongPress?.invoke()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialDownX = ev.rawX
                initialDownY = ev.rawY
                lastX = ev.rawX
                lastY = ev.rawY
                isDragging = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDx = abs(ev.rawX - initialDownX)
                val totalDy = abs(ev.rawY - initialDownY)
                if (totalDx > slop || totalDy > slop) {
                    isDragging = true
                    handler.removeCallbacks(longPressRunnable)
                    return true // Intercept touch events — steal gesture from child
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
            }
        }
        return isDragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialDownX = ev.rawX
                initialDownY = ev.rawY
                lastX = ev.rawX
                lastY = ev.rawY
                isDragging = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDx = abs(ev.rawX - initialDownX)
                val totalDy = abs(ev.rawY - initialDownY)
                if (totalDx > slop || totalDy > slop) {
                    if (!isDragging) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    val dx = ev.rawX - lastX
                    val dy = ev.rawY - lastY
                    lastX = ev.rawX
                    lastY = ev.rawY
                    onDrag?.invoke(dx, dy)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val totalDx = abs(ev.rawX - initialDownX)
                val totalDy = abs(ev.rawY - initialDownY)
                val isClick = !isDragging && (totalDx <= slop && totalDy <= slop)

                if (isClick) {
                    android.util.Log.d("DraggableFrameLayout", "Touch gesture identified as CLICK (totalDx=$totalDx, totalDy=$totalDy)")
                    onTap?.invoke()
                } else {
                    android.util.Log.d("DraggableFrameLayout", "Touch gesture identified as DRAG (totalDx=$totalDx, totalDy=$totalDy)")
                    onDragEnd?.invoke()
                }
                isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (isDragging) {
                    onDragEnd?.invoke()
                }
                isDragging = false
            }
        }
        return true
    }
}

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: DraggableFrameLayout
    private lateinit var webView: WebView
    private lateinit var errorTextView: TextView
    private lateinit var ringIndicatorView: RingIndicatorView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridge = AriaNativeBridge(this)

    private var voiceService: com.soltini.app.services.BackgroundVoiceService? = null
    private var isBound = false

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(className: android.content.ComponentName, service: IBinder) {
            val binder = service as com.soltini.app.services.BackgroundVoiceService.LocalBinder
            voiceService = binder.getService()
            isBound = true
            // OverlayService is often the first client to bind after a START_STICKY restart.
            // If BackgroundVoiceService is running but its WebSocket is still Disconnected,
            // trigger connect() here so voice is live before the user even opens the app.
            val svc = voiceService
            if (svc != null && !svc.isSleeping.value &&
                svc.connectionState.value == com.soltini.app.network.ConnectionState.Disconnected) {
                svc.connect()
            }
        }
        override fun onServiceDisconnected(arg0: android.content.ComponentName) {
            isBound = false
            voiceService = null
        }
    }

    companion object {
        const val CHANNEL_ID = "AriaOverlayChannel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.soltini.app.STOP_OVERLAY"
        const val ACTION_TOGGLE_BANKING_PAUSE = "com.soltini.app.TOGGLE_BANKING_PAUSE"

        @SuppressLint("StaticFieldLeak")
        private var instance: OverlayService? = null

        fun getInstance(): OverlayService? = instance

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.stopService(intent)
        }
    }

    @Volatile
    private var isHiddenForBanking = false

    fun isHiddenForBanking(): Boolean = isHiddenForBanking

    fun setHiddenForBanking(hidden: Boolean) {
        if (isHiddenForBanking == hidden) return
        isHiddenForBanking = hidden
        android.util.Log.i("OverlayService", "setHiddenForBanking: $hidden")
        mainHandler.post {
            if (::overlayView.isInitialized && ::windowManager.isInitialized) {
                try {
                    overlayView.visibility = if (hidden) View.GONE else View.VISIBLE
                    if (hidden) {
                        layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                    windowManager.updateViewLayout(overlayView, layoutParams)
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Failed to update layout params for banking hide: ${e.message}")
                }
            }
        }
        updateNotification()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            val specialUse = 1073741824 // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            startForeground(NOTIFICATION_ID, createNotification(), specialUse)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        Intent(this, com.soltini.app.services.BackgroundVoiceService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        // Safety check: only add overlay if permission is granted
        if (Settings.canDrawOverlays(this)) {
            setupOverlay()
        } else {
            android.util.Log.e("OverlayService", "SYSTEM_ALERT_WINDOW permission not granted — overlay skipped")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_BANKING_PAUSE -> {
                setHiddenForBanking(!isHiddenForBanking)
                return START_STICKY
            }
        }
        return START_STICKY
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Use our custom DraggableFrameLayout instead of plain FrameLayout
        overlayView = DraggableFrameLayout(this)

        // ── WebView setup ────────────────────────────────────────────────────────
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                domStorageEnabled = true
                // Enable hardware acceleration for Three.js / WebGL
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
            }
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            visibility = View.GONE // Disabled 3D display as per user request to avoid errors
            onPause()
            pauseTimers()
            addJavascriptInterface(bridge, "AriaNative")
            // WebViewClient to suppress error pages
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    android.util.Log.d("OverlayService", "WebView page finished: $url")
                }
                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    android.util.Log.e("OverlayService", "WebView error: ${error?.description} for ${request?.url}")
                }
            }
            loadUrl("file:///android_asset/avatar/index.html")
        }

        // ── Ring indicator ────────────────────────────────────────────────────
        ringIndicatorView = RingIndicatorView(this)

        // ── Fallback removed (using RingIndicatorView as primary orb) ──

        // ── Error TextView ────────────────────────────────────────────────────
        errorTextView = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.RED)
            setBackgroundColor(Color.parseColor("#CC000000"))
            textSize = 10f
            setPadding(8, 8, 8, 8)
        }


        // Add children
        overlayView.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val ringMargin = (8 * resources.displayMetrics.density).toInt()
        val ringParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { setMargins(ringMargin, ringMargin, ringMargin, ringMargin) }
        overlayView.addView(ringIndicatorView, ringParams)

        val errorParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }
        overlayView.addView(errorTextView, errorParams)

        // ── Window layout params ──────────────────────────────────────────────
        val widthPx  = (120 * resources.displayMetrics.density).toInt()
        val heightPx = (120 * resources.displayMetrics.density).toInt()

        layoutParams = WindowManager.LayoutParams(
            widthPx, heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_FOCUSABLE allows touch pass-through to background apps
            // FLAG_LAYOUT_NO_LIMITS lets it go to screen edges
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            val screenWidth = resources.displayMetrics.widthPixels
            gravity = Gravity.BOTTOM or Gravity.START
            x = (screenWidth / 2) - (widthPx / 2)
            y = (60 * resources.displayMetrics.density).toInt() // margin from bottom
        }

        // ── Drag callbacks ────────────────────────────────────────────────────
        overlayView.onDrag = { dx, dy ->
            layoutParams.x = (layoutParams.x + dx).toInt()
            layoutParams.y = (layoutParams.y - dy).toInt() // Inverted because y is from BOTTOM
            try {
                windowManager.updateViewLayout(overlayView, layoutParams)
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "updateViewLayout failed: ${e.message}")
            }
        }

        overlayView.onDragEnd = {
            // Do nothing, let the user place it anywhere they want (even middle of screen)
        }

        overlayView.onTap = {
            onAvatarTapped()
        }

        overlayView.onLongPress = {
            // Snap to nearest edge fully on long press too
            snapToEdge(0)
        }

        // ── Add to window ─────────────────────────────────────────────────────
        try {
            windowManager.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "addView failed: ${e.message}")
            stopSelf()
        }
    }

    fun onModelReady() {
        mainHandler.post {
            // Nothing to hide anymore, orb stays visible
        }
    }

    fun showError(errorText: String) {
        mainHandler.post {
            errorTextView.visibility = View.VISIBLE
            errorTextView.text = errorText
            // Auto hide after 10 seconds unless it's a persistent crash
            mainHandler.postDelayed({
                if (errorTextView.text == errorText) {
                    errorTextView.visibility = View.GONE
                }
            }, 10000)
        }
    }

    fun onAvatarTapped() {
        val svc = voiceService
        if (svc != null) {
            if (svc.isSleeping.value) {
                svc.wakeUp()
                android.util.Log.d("OverlayService", "Avatar tapped — waking up from sleep.")
            } else if (svc.connectionState.value == com.soltini.app.network.ConnectionState.Disconnected || 
                       svc.connectionState.value == com.soltini.app.network.ConnectionState.Error) {
                svc.connect()
                android.util.Log.d("OverlayService", "Avatar tapped — reconnecting broken connection.")
            } else {
                android.util.Log.d("OverlayService", "Avatar tapped while active — reserved for future action.")
            }
        } else {
            android.util.Log.d("OverlayService", "Avatar tapped but BackgroundVoiceService not bound yet.")
        }
    }

    private fun snapToEdge(marginDp: Int = 16) {
        val screenWidth = resources.displayMetrics.widthPixels
        val margin = (marginDp * resources.displayMetrics.density).toInt()
        val centerX = layoutParams.x + (layoutParams.width / 2)
        layoutParams.x = if (centerX < screenWidth / 2) margin else screenWidth - layoutParams.width - margin
        try {
            windowManager.updateViewLayout(overlayView, layoutParams)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Aria Overlay Service", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE_BANKING_PAUSE }
        val togglePendingIntent = PendingIntent.getService(
            this, 2, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleTitle = if (isHiddenForBanking) "Resume Myra" else "Pause (Banking)"
        val contentText = if (isHiddenForBanking) "Paused for Banking app safety" else "Myra is running"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Myra")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, toggleTitle, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun setRingState(state: RingState) {
        mainHandler.post { ringIndicatorView.state = state }
    }

    fun setTalking(isTalking: Boolean) {
        mainHandler.post {
            webView.evaluateJavascript(
                "window.Aria && window.Aria.setTalking && window.Aria.setTalking($isTalking)", null
            )
        }
    }

    fun setMouthAmplitude(value: Float) {
        mainHandler.post {
            ringIndicatorView.amplitude = value
            webView.evaluateJavascript(
                "window.Aria && window.Aria.setMouthAmplitude && window.Aria.setMouthAmplitude($value)", null
            )
        }
    }

    fun setEmotion(tag: String) {
        mainHandler.post {
            webView.evaluateJavascript(
                "window.Aria && window.Aria.setEmotion && window.Aria.setEmotion('$tag')", null
            )
        }
    }

    fun setListening(isListening: Boolean) {
        mainHandler.post {
            webView.evaluateJavascript(
                "window.Aria && window.Aria.setListening && window.Aria.setListening($isListening)", null
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        if (::windowManager.isInitialized && ::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (e: Exception) { /* ignore */ }
        }
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
