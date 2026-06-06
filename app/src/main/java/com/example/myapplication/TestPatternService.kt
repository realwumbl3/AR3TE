package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myapplication.discovery.DiscoveredMachine

class TestPatternService : LifecycleService() {

    private var presentation: TestPatternPresentation? = null
    private lateinit var displayManager: DisplayManager
    
    private var _currentPattern by mutableStateOf(TestPattern.COLOR_BARS)
    var currentPattern: TestPattern
        get() = _currentPattern
        set(value) {
            _currentPattern = value
            presentation?.pattern = value
        }

    private var _activeMachine by mutableStateOf<DiscoveredMachine?>(null)
    var activeMachine: DiscoveredMachine?
        get() = _activeMachine
        set(value) {
            _activeMachine = value
            presentation?.activeMachine = value
        }

    private var _monitorIndex by mutableIntStateOf(1)
    var monitorIndex: Int
        get() = _monitorIndex
        set(value) {
            _monitorIndex = value
            presentation?.monitorIndex = value
        }

    private var _currentFps by mutableIntStateOf(0)
    var currentFps: Int
        get() = _currentFps
        private set(value) {
            _currentFps = value
        }

    private var _currentKbps by mutableIntStateOf(0)
    var currentKbps: Int
        get() = _currentKbps
        private set(value) {
            _currentKbps = value
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { updatePresentation() }
        override fun onDisplayRemoved(displayId: Int) { updatePresentation() }
        override fun onDisplayChanged(displayId: Int) { updatePresentation() }
    }

    inner class LocalBinder : Binder() {
        fun getService(): TestPatternService = this@TestPatternService
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
        if (displays.isNotEmpty()) {
            val display = displays[0]
            if (presentation == null || presentation?.display != display) {
                presentation?.dismiss()
                presentation = TestPatternPresentation(this, display).apply {
                    pattern = _currentPattern
                    activeMachine = _activeMachine
                    monitorIndex = _monitorIndex
                    onStatsUpdated = { f, k -> 
                        currentFps = f
                        currentKbps = k
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
                "Test Pattern Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Test Pattern Active")
            .setContentText("Drawing test pattern on external display")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        presentation?.dismiss()
        presentation = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "test_pattern_channel"
    }
}
