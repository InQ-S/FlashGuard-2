package com.flashguard.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.flashguard.app.MainActivity
import com.flashguard.app.R
import com.flashguard.app.data.ConfigStore
import com.flashguard.app.vision.WhiteFlashDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

data class ServiceStatus(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val currentAlpha: Float = 0f,
    val lastWhiteRatioPct: Float = 0f,
    val triggerCount: Int = 0
)

class FlashGuardService : Service() {

    companion object {
        const val ACTION_START = "com.flashguard.app.ACTION_START"
        const val ACTION_STOP = "com.flashguard.app.ACTION_STOP"
        const val ACTION_TOGGLE_PAUSE = "com.flashguard.app.ACTION_TOGGLE_PAUSE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "flashguard_service_channel"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_INTERVAL_MS = 33L

        private val _status = MutableStateFlow(ServiceStatus())
        val status: StateFlow<ServiceStatus> = _status.asStateFlow()
    }

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var mainHandler: Handler
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var overlayView: View? = null
    private var floatingButton: TextView? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private val detector = WhiteFlashDetector()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var configObserverJob: Job? = null

    @Volatile
    private var currentOverlayAlpha: Float = 0f
    @Volatile
    private var isPaused: Boolean = false
    @Volatile
    private var lastSampleTimeMs: Long = 0L

    private var lastCaptureW: Int = 0
    private var lastCaptureH: Int = 0
    private var isCurrentlyTriggered: Boolean = false
    private var lastTriggeredAtMs: Long = 0L
    private var triggerCount: Int = 0
    private var displayListenerRegistered: Boolean = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && virtualDisplay != null) {
                updateCaptureOrientationIfNeeded()
            }
        }
    }

    private val fadeRunnable = object : Runnable {
        override fun run() {
            val config = ConfigStore.config.value
            val now = System.currentTimeMillis()
            val targetMaxAlpha = (config.overlayOpacityPct / 100f).coerceIn(0.1f, 0.92f)

            if (isPaused) {
                setOverlayAlpha(0f)
                return
            }

            val holding = isCurrentlyTriggered || (now - lastTriggeredAtMs) < config.minHoldMs
            if (holding) {
                setOverlayAlpha(targetMaxAlpha)
                mainHandler.postDelayed(this, 16L)
            } else {
                val fadeDuration = config.fadeOutMs.coerceAtLeast(100).toFloat()
                val step = (16f / fadeDuration) * targetMaxAlpha
                val next = (currentOverlayAlpha - step).coerceAtLeast(0f)
                setOverlayAlpha(next)
                if (next > 0f) {
                    mainHandler.postDelayed(this, 16L)
                }
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { stopSelfService() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        mainHandler = Handler(Looper.getMainLooper())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfService()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                togglePause()
                return START_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null || !Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundCompat()
                setupOverlay()
                observeConfigForFloatingControl()
                startProjection(resultCode, resultData)
                _status.value = _status.value.copy(isRunning = true, isPaused = false)
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (virtualDisplay != null) {
            updateCaptureOrientationIfNeeded()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cleanup()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FlashGuardService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FlashGuardService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isPaused) "일시정지됨 (탭하여 재개)" else "야간 섬광 감지 작동 중"
        val pauseLabel = if (isPaused) "재개" else "일시정지"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("FlashGuard 눈부심 방지")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_vector)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, pauseLabel, pauseIntent).build())
            .addAction(Notification.Action.Builder(null, "종료", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FlashGuard 화면 보호 서비스",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "게임 중 백색 섬광 감지를 위한 상주 알림"
        }
        manager.createNotificationChannel(channel)
    }

    private fun setupOverlay() {
        if (overlayView != null) return
        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        windowManager.addView(view, params)
        overlayView = view
    }

    private fun observeConfigForFloatingControl() {
        configObserverJob?.cancel()
        configObserverJob = serviceScope.launch {
            ConfigStore.config.collect { cfg ->
                if (cfg.showFloatingControl) {
                    showFloatingButton()
                } else {
                    removeFloatingButton()
                }
            }
        }
    }

    private fun showFloatingButton() {
        if (floatingButton != null || !Settings.canDrawOverlays(this)) return

        val btn = TextView(this).apply {
            text = if (isPaused) "FG OFF" else "FG ON"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(24, 14, 24, 14)
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.argb(170, 24, 30, 44))
                setStroke(2, Color.argb(190, 245, 158, 11))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(btn, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePause()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(btn, params)
        floatingButton = btn
        floatingParams = params
    }

    private fun clampFloatingButtonToScreen(screenW: Int, screenH: Int) {
        val btn = floatingButton ?: return
        val params = floatingParams ?: return
        val maxX = (screenW - 140).coerceAtLeast(24)
        val maxY = (screenH - 120).coerceAtLeast(24)
        params.x = params.x.coerceIn(12, maxX)
        params.y = params.y.coerceIn(12, maxY)
        runCatching { windowManager.updateViewLayout(btn, params) }
    }

    private fun removeFloatingButton() {
        floatingButton?.let {
            runCatching { windowManager.removeView(it) }
        }
        floatingButton = null
        floatingParams = null
    }

    private fun updateFloatingButtonLabel() {
        floatingButton?.apply {
            text = if (isPaused) "FG OFF" else "FG ON"
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(if (isPaused) Color.argb(160, 50, 55, 68) else Color.argb(170, 24, 30, 44))
                setStroke(
                    2,
                    if (isPaused) Color.argb(160, 148, 163, 184) else Color.argb(210, 245, 158, 11)
                )
            }
        }
    }

    private fun togglePause() {
        isPaused = !isPaused
        if (isPaused) {
            isCurrentlyTriggered = false
            mainHandler.removeCallbacks(fadeRunnable)
            setOverlayAlpha(0f)
        }
        updateFloatingButtonLabel()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification())
        _status.value = _status.value.copy(isPaused = isPaused)
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        val thread = HandlerThread("FlashGuardCapture").apply { start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        projection.registerCallback(projectionCallback, mainHandler)
        mediaProjection = projection

        val (screenW, screenH, densityDpi) = getScreenDimensions()
        val (captureW, captureH) = computeSampleSize(screenW, screenH)
        lastCaptureW = captureW
        lastCaptureH = captureH

        val reader = createImageReader(captureW, captureH)
        imageReader = reader

        // Android 14+에서는 VirtualDisplay를 단 1회만 생성하고, 회전 시 resize + setSurface로 갱신해야 감지가 끊기지 않음
        virtualDisplay = projection.createVirtualDisplay(
            "FlashGuardDisplay",
            captureW,
            captureH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )

        if (!displayListenerRegistered) {
            displayManager.registerDisplayListener(displayListener, mainHandler)
            displayListenerRegistered = true
        }
    }

    private fun updateCaptureOrientationIfNeeded() {
        val display = virtualDisplay ?: return
        val (screenW, screenH, densityDpi) = getScreenDimensions()
        val (captureW, captureH) = computeSampleSize(screenW, screenH)

        if (captureW == lastCaptureW && captureH == lastCaptureH) return
        lastCaptureW = captureW
        lastCaptureH = captureH

        val oldReader = imageReader
        val newReader = createImageReader(captureW, captureH)
        imageReader = newReader

        runCatching {
            display.setSurface(newReader.surface)
            display.resize(captureW, captureH, densityDpi)
        }

        if (oldReader != null) {
            mainHandler.postDelayed({ runCatching { oldReader.close() } }, 150L)
        }

        clampFloatingButtonToScreen(screenW, screenH)
    }

    private fun computeSampleSize(screenW: Int, screenH: Int): Pair<Int, Int> {
        val scale = 80f / maxOf(screenW, screenH).toFloat()
        val w = (screenW * scale).toInt().coerceAtLeast(16)
        val h = (screenH * scale).toInt().coerceAtLeast(16)
        return Pair(w, h)
    }

    private fun createImageReader(captureW: Int, captureH: Int): ImageReader {
        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ imgReader ->
            val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (isPaused || now - lastSampleTimeMs < SAMPLE_INTERVAL_MS) {
                    return@setOnImageAvailableListener
                }
                lastSampleTimeMs = now

                val cfg = ConfigStore.config.value
                val result = detector.analyze(
                    image = image,
                    config = cfg,
                    currentOverlayAlpha = currentOverlayAlpha,
                    wasTriggered = isCurrentlyTriggered
                )

                mainHandler.post {
                    handleDetectionResult(result.whiteRatioPct, result.isTriggered)
                }
            } finally {
                image.close()
            }
        }, captureHandler)
        return reader
    }

    private fun handleDetectionResult(whiteRatioPct: Float, triggered: Boolean) {
        if (isPaused) return

        val wasTriggered = isCurrentlyTriggered
        isCurrentlyTriggered = triggered

        if (triggered) {
            lastTriggeredAtMs = System.currentTimeMillis()
            if (!wasTriggered) {
                triggerCount++
            }
            val targetAlpha = (ConfigStore.config.value.overlayOpacityPct / 100f).coerceIn(0.1f, 0.92f)
            setOverlayAlpha(targetAlpha)
            mainHandler.removeCallbacks(fadeRunnable)
            mainHandler.postDelayed(fadeRunnable, 16L)
        }

        _status.value = _status.value.copy(
            lastWhiteRatioPct = whiteRatioPct,
            triggerCount = triggerCount
        )
    }

    private fun setOverlayAlpha(alpha: Float) {
        currentOverlayAlpha = alpha
        overlayView?.alpha = alpha
        _status.value = _status.value.copy(currentAlpha = alpha)
    }

    private fun getScreenDimensions(): Triple<Int, Int, Int> {
        val densityDpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), densityDpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, densityDpi)
        }
    }

    private fun stopSelfService() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        if (displayListenerRegistered) {
            runCatching { displayManager.unregisterDisplayListener(displayListener) }
            displayListenerRegistered = false
        }
        mainHandler.removeCallbacks(fadeRunnable)
        configObserverJob?.cancel()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        removeFloatingButton()
        lastCaptureW = 0
        lastCaptureH = 0
        _status.value = ServiceStatus()
    }
}
