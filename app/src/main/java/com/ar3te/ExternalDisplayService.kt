package com.ar3te

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ar3te.discovery.DiscoveredMachine

class ExternalDisplayService : LifecycleService() {

    private var presentation: ExternalDisplayPresentation? = null
    private lateinit var displayManager: DisplayManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var inAppPreviewSender: ((String) -> Unit)? = null
    
    private var _currentState by mutableStateOf(ExternalDisplayState.IDLE)
    var currentState: ExternalDisplayState
        get() = _currentState
        set(value) {
            _currentState = value
            presentation?.displayState = value
        }

    private var _activeMachine by mutableStateOf<DiscoveredMachine?>(null)
    var activeMachine: DiscoveredMachine?
        get() = _activeMachine
        set(value) {
            _activeMachine = value
            presentation?.activeMachine = value
            updateWakeLock(value != null)
        }

    private fun updateWakeLock(acquire: Boolean) {
        if (acquire) {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AR3TE:StreamingWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } else {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private var _monitorIndex by mutableIntStateOf(1)
    var monitorIndex: Int
        get() = _monitorIndex
        set(value) {
            _monitorIndex = value
            presentation?.monitorIndex = value
        }

    private var _is3DofEnabled by mutableStateOf(false)
    var is3DofEnabled: Boolean
        get() = _is3DofEnabled
        set(value) {
            _is3DofEnabled = value
            presentation?.is3DofEnabled = value
        }

    private var _currentFps by mutableIntStateOf(0)
    var currentFps: Int
        get() = _currentFps
        private set(value) {
            _currentFps = value
        }

    private var _currentMegabytesPerSecond by mutableStateOf(0.0)
    var currentMegabytesPerSecond: Double
        get() = _currentMegabytesPerSecond
        private set(value) {
            _currentMegabytesPerSecond = value
        }

    private var _currentLatencyMs by mutableIntStateOf(-1)
    var currentLatencyMs: Int
        get() = _currentLatencyMs
        private set(value) {
            _currentLatencyMs = value
        }

    private var _currentCaptureMethod by mutableStateOf("Loading...")
    var currentCaptureMethod: String
        get() = _currentCaptureMethod
        private set(value) {
            _currentCaptureMethod = value
        }

    private var _currentAudioState by mutableStateOf("Loading...")
    var currentAudioState: String
        get() = _currentAudioState
        private set(value) {
            _currentAudioState = value
        }

    private var _openTaskCount by mutableIntStateOf(0)
    var openTaskCount: Int
        get() = _openTaskCount
        private set(value) {
            _openTaskCount = value
        }

    var hasPresentationDisplay by mutableStateOf(false)
        private set

    var taskCountListener: ((Int) -> Unit)? = null

    var localCursorX by mutableStateOf(0f)
    var localCursorY by mutableStateOf(0f)
    var lastLocalMoveTime by mutableStateOf(0L)
    var remoteWidth by mutableIntStateOf(1920)
    var remoteHeight by mutableIntStateOf(1080)

    fun updateLocalCursor(dx: Float, dy: Float) {
        localCursorX = (localCursorX + dx).coerceIn(0f, remoteWidth.toFloat())
        localCursorY = (localCursorY + dy).coerceIn(0f, remoteHeight.toFloat())
        lastLocalMoveTime = System.currentTimeMillis()
        
        presentation?.localCursorX = localCursorX
        presentation?.localCursorY = localCursorY
        
        sendRemoteMessage(org.json.JSONObject().apply {
            put("type", "mouse_move_abs")
            put("x", localCursorX.toInt())
            put("y", localCursorY.toInt())
        }.toString())
    }

    fun syncLocalCursorFromRemote(x: Float, y: Float, w: Int, h: Int) {
        val now = System.currentTimeMillis()
        remoteWidth = w
        remoteHeight = h
        if (now - lastLocalMoveTime > 2000) {
            localCursorX = x
            localCursorY = y
            presentation?.localCursorX = x
            presentation?.localCursorY = y
        }
    }

    fun sendRemoteMessage(message: String) {
        presentation?.onSendMessage?.invoke(message) ?: inAppPreviewSender?.invoke(message)
    }

    fun setInAppPreviewSender(sender: ((String) -> Unit)?) {
        inAppPreviewSender = sender
    }

    fun updateStreamStats(fps: Int, megabytesPerSecond: Double, captureMethod: String?, latencyMs: Int) {
        currentFps = fps
        currentMegabytesPerSecond = megabytesPerSecond
        currentLatencyMs = latencyMs
        if (!captureMethod.isNullOrBlank()) {
            currentCaptureMethod = captureMethod
        }
    }

    fun updateCaptureMethod(method: String) {
        currentCaptureMethod = method
    }

    fun updateAudioState(state: String) {
        currentAudioState = state
    }

    fun updateTaskCount(count: Int) {
        openTaskCount = count
        taskCountListener?.invoke(count)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { updatePresentation() }
        override fun onDisplayRemoved(displayId: Int) { updatePresentation() }
        override fun onDisplayChanged(displayId: Int) { updatePresentation() }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ExternalDisplayService = this@ExternalDisplayService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        updatePresentation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun updatePresentation() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        hasPresentationDisplay = displays.isNotEmpty()
        if (displays.isNotEmpty()) {
            val display = displays[0]
            if (presentation == null || presentation?.display != display) {
                presentation?.dismiss()
                presentation = ExternalDisplayPresentation(this, display).apply {
                    displayState = _currentState
                    activeMachine = _activeMachine
                    monitorIndex = _monitorIndex
                    is3DofEnabled = _is3DofEnabled
                    localCursorX = this@ExternalDisplayService.localCursorX
                    localCursorY = this@ExternalDisplayService.localCursorY
                    onStatsUpdated = { f, megabytesPerSecond, method, latencyMs ->
                        updateStreamStats(f, megabytesPerSecond, method, latencyMs)
                    }
                    onCaptureMethodUpdated = { method ->
                        updateCaptureMethod(method)
                    }
                    onAudioStateUpdated = { state ->
                        updateAudioState(state)
                    }
                    onTaskCountUpdated = { count ->
                        updateTaskCount(count)
                    }
                    onRemoteCursorReceived = { x, y, w, h ->
                        syncLocalCursorFromRemote(x, y, w, h)
                    }
                    show()
                }
            }
        } else {
            presentation?.dismiss()
            presentation = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AR3TE Display Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentText("Manage the AR3TE connection from the app")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        updateWakeLock(false)
        displayManager.unregisterDisplayListener(displayListener)
        presentation?.dismiss()
        presentation = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "external_display_channel"
    }
}
