package com.ar3te

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.clip
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ar3te.discovery.DiscoveredMachine
import com.ar3te.discovery.MachineDiscovery
import com.ar3te.ui.theme.AR3TETheme
import kotlinx.coroutines.delay
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private var externalDisplayService: ExternalDisplayService? = null
    private var isBound by mutableStateOf(false)
    private var discoveredMachines by mutableStateOf<List<DiscoveredMachine>>(emptyList())
    private var activeMachine by mutableStateOf<DiscoveredMachine?>(null)
    private var isPointerCaptured by mutableStateOf(false)
    private var is3DofEnabled by mutableStateOf(false)
    private var openTaskCount by mutableIntStateOf(0)

    private lateinit var machineDiscovery: MachineDiscovery

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ExternalDisplayService.LocalBinder
            val srv = binder.getService()
            externalDisplayService = srv
            isBound = true
            
            srv.activeMachine?.let {
                activeMachine = it
            }
            srv.taskCountListener = { count ->
                openTaskCount = count
            }
            openTaskCount = srv.openTaskCount
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            externalDisplayService?.taskCountListener = null
            externalDisplayService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.setOnCapturedPointerListener { _, event ->
                activeMachine != null && isPointerCaptured && handleMouseMotionEvent(event)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        machineDiscovery = MachineDiscovery(lifecycleScope) { machines ->
            discoveredMachines = machines
        }
        machineDiscovery.start()

        val intent = Intent(this, ExternalDisplayService::class.java)
        startService(intent)
        bindService(intent, connection, BIND_AUTO_CREATE)

        handleUsbIntent(intent)

        setContent {
            AR3TETheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val machine = activeMachine
                    if (machine == null) {
                        HomeScreen(
                            discoveredMachines = discoveredMachines,
                            onMachineSelected = { 
                                activeMachine = it
                                externalDisplayService?.activeMachine = it
                                externalDisplayService?.currentState = ExternalDisplayState.REMOTE_SCREEN
                                externalDisplayService?.let { srv ->
                                    srv.taskCountListener = { count -> openTaskCount = count }
                                    openTaskCount = srv.openTaskCount
                                }
                            },
                            modifier = Modifier.padding(innerPadding).imePadding()
                        )
                    } else {
                        ScreenSharingScreen(
                            machine = machine,
                            currentFps = externalDisplayService?.currentFps ?: 0,
                            currentMegabytesPerSecond = externalDisplayService?.currentMegabytesPerSecond ?: 0.0,
                            currentLatencyMs = externalDisplayService?.currentLatencyMs ?: -1,
                            currentCaptureMethod = externalDisplayService?.currentCaptureMethod ?: stringResource(R.string.loading),
                            currentAudioState = externalDisplayService?.currentAudioState ?: stringResource(R.string.loading),
                            framePacingSamples = externalDisplayService?.framePacingSamples ?: emptyList(),
                            cursorPacingSamples = externalDisplayService?.cursorPacingSamples ?: emptyList(),
                            openTaskCount = openTaskCount,
                            isPointerCaptured = isPointerCaptured,
                            is3DofEnabled = is3DofEnabled,
                            streamMode = externalDisplayService?.streamMode ?: StreamMode.LOW_LATENCY,
                            lastInteractiveInputMs = externalDisplayService?.lastInteractiveInputMs ?: 0L,
                            showEmbeddedPreview = externalDisplayService?.hasPresentationDisplay != true,
                            previewCursorX = externalDisplayService?.localCursorX ?: 0f,
                            previewCursorY = externalDisplayService?.localCursorY ?: 0f,
                            previewLastLocalMoveTime = externalDisplayService?.lastLocalMoveTime ?: 0L,
                            onStop = { 
                                activeMachine = null
                                externalDisplayService?.activeMachine = null
                                externalDisplayService?.currentState = ExternalDisplayState.IDLE
                                externalDisplayService?.setInAppPreviewSender(null)
                                isPointerCaptured = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    window.decorView.releasePointerCapture()
                                }
                            },
                            onMonitorSwitch = { index ->
                                externalDisplayService?.monitorIndex = index
                            },
                            onSendMessage = { msg ->
                                externalDisplayService?.sendRemoteMessage(msg)
                            },
                            onMoveCursor = { dx, dy ->
                                externalDisplayService?.updateLocalCursor(dx, dy)
                            },
                            onPreviewClientReady = { sender ->
                                externalDisplayService?.setInAppPreviewSender(sender)
                            },
                            onPreviewStatsUpdated = { fps, megabytesPerSecond, captureMethod, latencyMs ->
                                externalDisplayService?.updateStreamStats(
                                    fps = fps,
                                    megabytesPerSecond = megabytesPerSecond,
                                    captureMethod = captureMethod,
                                    latencyMs = latencyMs
                                )
                            },
                            onPreviewCaptureMethodUpdated = { method ->
                                externalDisplayService?.updateCaptureMethod(method)
                            },
                            onPreviewAudioStateUpdated = { state ->
                                externalDisplayService?.updateAudioState(state)
                            },
                            onPreviewFramePacingUpdated = { samples ->
                                externalDisplayService?.updateFramePacing(samples)
                            },
                            onPreviewCursorPacingUpdated = { samples ->
                                externalDisplayService?.updateCursorPacing(samples)
                            },
                            onPreviewTaskCountUpdated = { count ->
                                externalDisplayService?.updateTaskCount(count)
                            },
                            onPreviewRemoteCursorReceived = { x, y, w, h ->
                                externalDisplayService?.syncLocalCursorFromRemote(x, y, w, h)
                            },
                            onCapturedMouseEvent = { event ->
                                activeMachine != null && isPointerCaptured && handleMouseMotionEvent(event)
                            },
                            onCaptureToggle = { enabled ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    if (enabled) {
                                        window.decorView.requestPointerCapture()
                                    } else {
                                        window.decorView.releasePointerCapture()
                                    }
                                }
                            },
                            on3DofToggle = { enabled ->
                                is3DofEnabled = enabled
                                externalDisplayService?.is3DofEnabled = enabled
                                val tracker = RayNeoGlassesTracker.getInstance(this)
                                if (enabled) {
                                    // USB permission dialog only appears on the phone Activity.
                                    tracker.ensureUsbAccess(this) { _ ->
                                        tracker.start()
                                    }
                                } else {
                                    tracker.stop()
                                }
                            },
                            onStreamModeChange = { mode ->
                                externalDisplayService?.streamMode = mode
                            },
                            modifier = Modifier.padding(innerPadding).imePadding()
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        Log.d("MainActivity", "USB device attached")
        if (is3DofEnabled) {
            val tracker = RayNeoGlassesTracker.getInstance(this)
            tracker.ensureUsbAccess(this) { tracker.start() }
        }
    }

    override fun onDestroy() {
        machineDiscovery.stop()
        RayNeoGlassesTracker.getInstance(this).stop()
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        Log.d("RemoteInput", "Pointer capture state: $hasCapture")
        isPointerCaptured = hasCapture
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (activeMachine != null) {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            val isUp = event.action == KeyEvent.ACTION_UP
            
            if (isDown || isUp) {
                val vk = androidKeyCodeToWindowsVk(event.keyCode)
                if (vk != 0) {
                    val type = if (isDown) "key_down" else "key_up"
                    externalDisplayService?.sendRemoteMessage(JSONObject().apply {
                        put("type", type)
                        put("vk", vk)
                    }.toString())
                    
                    if (event.keyCode != KeyEvent.KEYCODE_HOME) {
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return super.dispatchGenericMotionEvent(event)
    }

    private fun handleMouseMotionEvent(event: MotionEvent): Boolean {
        val isMouse = (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        val isRelativeMouse = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (event.source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        val isTouchpad = (event.source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
        if (!isMouse && !isRelativeMouse && !isTouchpad) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X).takeIf { it != 0f } ?: event.x
                val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y).takeIf { it != 0f } ?: event.y

                if (dx != 0f || dy != 0f) {
                    externalDisplayService?.updateLocalCursor(dx * 2.0f, dy * 2.0f)
                }

                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vScroll != 0f) {
                    externalDisplayService?.sendRemoteMessage(JSONObject().apply {
                        put("type", "mouse_wheel")
                        put("delta", vScroll * 120)
                    }.toString())
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val isDown = event.action == MotionEvent.ACTION_BUTTON_PRESS
                val buttons = listOf(
                    MotionEvent.BUTTON_PRIMARY to "l",
                    MotionEvent.BUTTON_SECONDARY to "r",
                    MotionEvent.BUTTON_TERTIARY to "m"
                )
                for ((mask, code) in buttons) {
                    if (event.actionButton == mask) {
                        val type = if (isDown) "mouse_down" else "mouse_up"
                        externalDisplayService?.sendRemoteMessage(JSONObject().apply {
                            put("type", type)
                            put("button", code)
                        }.toString())
                    }
                }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vScroll != 0f) {
                    externalDisplayService?.sendRemoteMessage(JSONObject().apply {
                        put("type", "mouse_wheel")
                        put("delta", vScroll * 120)
                    }.toString())
                    return true
                }
            }
        }

        return false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (activeMachine != null && (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            val isDown = event.action == MotionEvent.ACTION_DOWN
            val isUp = event.action == MotionEvent.ACTION_UP
            if (isDown || isUp) {
                val button = when {
                    event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 -> "l"
                    event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> "r"
                    event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> "m"
                    else -> "l"
                }
                val type = if (isDown) "mouse_down" else "mouse_up"
                externalDisplayService?.sendRemoteMessage(JSONObject().apply {
                    put("type", type)
                    put("button", button)
                }.toString())
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

@Composable
fun HomeScreen(
    discoveredMachines: List<DiscoveredMachine>,
    onMachineSelected: (DiscoveredMachine) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (discoveredMachines.isEmpty()) {
            Text(
                text = stringResource(R.string.home_scanning),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            discoveredMachines.forEach { machine ->
                MachineCard(machine = machine, onClick = { onMachineSelected(machine) })
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun MachineCard(machine: DiscoveredMachine, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = machine.name,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.machine_address, machine.host, machine.port),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ScreenSharingScreen(
    machine: DiscoveredMachine,
    currentFps: Int,
    currentMegabytesPerSecond: Double,
    currentLatencyMs: Int,
    currentCaptureMethod: String,
    currentAudioState: String,
    framePacingSamples: List<Float>,
    cursorPacingSamples: List<Float>,
    openTaskCount: Int,
    isPointerCaptured: Boolean,
    is3DofEnabled: Boolean,
    streamMode: StreamMode,
    lastInteractiveInputMs: Long,
    showEmbeddedPreview: Boolean,
    previewCursorX: Float,
    previewCursorY: Float,
    previewLastLocalMoveTime: Long,
    onStop: () -> Unit,
    onMonitorSwitch: (Int) -> Unit,
    onSendMessage: (String) -> Unit,
    onMoveCursor: (Float, Float) -> Unit,
    onPreviewClientReady: ((String) -> Unit) -> Unit,
    onPreviewStatsUpdated: (Int, Double, String?, Int) -> Unit,
    onPreviewCaptureMethodUpdated: (String) -> Unit,
    onPreviewAudioStateUpdated: (String) -> Unit,
    onPreviewFramePacingUpdated: (List<Float>) -> Unit,
    onPreviewCursorPacingUpdated: (List<Float>) -> Unit,
    onPreviewTaskCountUpdated: (Int) -> Unit,
    onPreviewRemoteCursorReceived: (Float, Float, Int, Int) -> Unit,
    onCapturedMouseEvent: (MotionEvent) -> Boolean,
    onCaptureToggle: (Boolean) -> Unit,
    on3DofToggle: (Boolean) -> Unit,
    onStreamModeChange: (StreamMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var monitorIndex by remember { mutableIntStateOf(1) }
    var showDebugMenu by remember { mutableStateOf(false) }
    var showFramePacingGraph by remember {
        mutableStateOf(AppPreferences.getShowFramePacingGraph(context))
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var keyboardText by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val currentCapturedMouseEvent by rememberUpdatedState(onCapturedMouseEvent)

    LaunchedEffect(showFramePacingGraph) {
        AppPreferences.setShowFramePacingGraph(context, showFramePacingGraph)
    }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    BackHandler(onBack = onStop)

    LaunchedEffect(Unit) {
        while (true) {
            onSendMessage(JSONObject().apply {
                put("type", "request_task_count")
            }.toString())
            delay(5000)
        }
    }

    LaunchedEffect(isPointerCaptured) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val root = view.rootView
            if (isPointerCaptured) {
                root.isFocusable = true
                root.isFocusableInTouchMode = true
                root.requestFocus()
                root.pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
            } else {
                root.pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW)
            }
        }
    }

    DisposableEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setOnCapturedPointerListener { _, event ->
                currentCapturedMouseEvent(event)
            }
            if (view.rootView != view) {
                view.rootView.setOnCapturedPointerListener { _, event ->
                    currentCapturedMouseEvent(event)
                }
            }
        }

        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                view.setOnCapturedPointerListener(null)
                if (view.rootView != view) {
                    view.rootView.setOnCapturedPointerListener(null)
                }
                view.releasePointerCapture()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.screen_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = machine.name,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            IconButton(onClick = {
                monitorIndex = if (monitorIndex == 1) 2 else 1
                onMonitorSwitch(monitorIndex)
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_monitor_switch),
                    contentDescription = stringResource(R.string.switch_monitor)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = {
                focusRequester.requestFocus()
                keyboardController?.show()
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard),
                    contentDescription = stringResource(R.string.show_keyboard)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val root = view.rootView
                    root.isFocusable = true
                    root.isFocusableInTouchMode = true
                    root.requestFocus()
                }
                onCaptureToggle(!isPointerCaptured)
            }) {
                Icon(
                    painter = painterResource(if (isPointerCaptured) R.drawable.ic_unlock else R.drawable.ic_lock),
                    contentDescription = stringResource(if (isPointerCaptured) R.string.unlock_pointer else R.string.lock_pointer)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = { showDebugMenu = !showDebugMenu }) {
                Icon(
                    painter = painterResource(R.drawable.ic_bug_report),
                    contentDescription = stringResource(R.string.debug)
                )
            }
        }

        if (showDebugMenu) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.debug_menu_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = is3DofEnabled,
                            onCheckedChange = { on3DofToggle(it) }
                        )
                        Text(stringResource(R.string.enable_3dof_view))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = showFramePacingGraph,
                            onCheckedChange = { showFramePacingGraph = it }
                        )
                        Text(stringResource(R.string.show_frame_pacing_graph))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.streaming_mode_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.streaming_mode_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamModeChip(
                            label = stringResource(R.string.streaming_mode_auto),
                            selected = streamMode == StreamMode.AUTO,
                            onClick = { onStreamModeChange(StreamMode.AUTO) }
                        )
                        StreamModeChip(
                            label = stringResource(R.string.streaming_mode_low_latency),
                            selected = streamMode == StreamMode.LOW_LATENCY,
                            onClick = { onStreamModeChange(StreamMode.LOW_LATENCY) }
                        )
                        StreamModeChip(
                            label = stringResource(R.string.streaming_mode_smooth_buffered),
                            selected = streamMode == StreamMode.SMOOTH_BUFFERED,
                            onClick = { onStreamModeChange(StreamMode.SMOOTH_BUFFERED) }
                        )
                    }
                    if (is3DofEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ThreeDofPhoneStatus()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Hidden TextField for keyboard input
        Box(modifier = Modifier
            .size(1.dp)
            .alpha(0.01f)
            .focusRequester(focusRequester)) {
            BasicTextField(
                value = keyboardText,
                onValueChange = { newValue ->
                    val newText = newValue.text
                    try {
                        if (newText.isEmpty()) {
                            onSendMessage(JSONObject().apply {
                                put("type", "key_down")
                                put("vk", 0x08)
                            }.toString())
                            onSendMessage(JSONObject().apply {
                                put("type", "key_up")
                                put("vk", 0x08)
                            }.toString())
                        } else if (newText.length > 1) {
                            val added = newText.substring(1)
                            for (char in added) {
                                val vk = charToVk(char)
                                if (vk != 0) {
                                    val isShift = char.isUpperCase() || "!@#$%^&*()_+{}|:\"<>?~".contains(char)
                                    onSendMessage(JSONObject().apply {
                                        put("type", "key_down")
                                        put("vk", vk)
                                        if (isShift) put("shift", true)
                                    }.toString())
                                    onSendMessage(JSONObject().apply {
                                        put("type", "key_up")
                                        put("vk", vk)
                                        if (isShift) put("shift", true)
                                    }.toString())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("RemoteInput", "Keyboard error", e)
                    }
                    keyboardText = TextFieldValue(" ", selection = TextRange(1))
                },
                modifier = Modifier.fillMaxSize(),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None
                )
            )
        }

        // Stats for Nerds Card (Collapsed)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val latencyLabel = if (currentLatencyMs >= 0) {
                        stringResource(R.string.latency_ms, currentLatencyMs)
                    } else {
                        stringResource(R.string.latency_unknown)
                    }
                    Text(
                        text = stringResource(
                            R.string.stats_text,
                            currentFps,
                            currentMegabytesPerSecond,
                            latencyLabel
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = currentCaptureMethod, style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = currentAudioState, style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (showEmbeddedPreview) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = RectangleShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black
                )
            ) {
                RemoteScreenView(
                    machine = machine,
                    monitorIndex = monitorIndex,
                    is3DofEnabled = is3DofEnabled,
                    onStatsUpdated = onPreviewStatsUpdated,
                    onCaptureMethodUpdated = onPreviewCaptureMethodUpdated,
                    onAudioStateUpdated = onPreviewAudioStateUpdated,
                    onFramePacingUpdated = onPreviewFramePacingUpdated,
                    onCursorPacingUpdated = onPreviewCursorPacingUpdated,
                    onTaskCountUpdated = onPreviewTaskCountUpdated,
                    streamMode = streamMode,
                    lastInteractiveInputMs = lastInteractiveInputMs,
                    onClientReady = onPreviewClientReady,
                    onRemoteCursorReceived = onPreviewRemoteCursorReceived,
                    localCursorX = previewCursorX,
                    localCursorY = previewCursorY,
                    lastLocalMoveTime = previewLastLocalMoveTime
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Trackpad Area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var isTwoFinger = false
                        var isDragging = false
                        var longPressTriggered = false
                        var lastTwoFingerY = 0f
                        var totalMoveDist = 0f
                        val startTime = System.currentTimeMillis()

                        while (true) {
                            val event = awaitPointerEvent()
                            val changes = event.changes
                            val currentTime = System.currentTimeMillis()

                            if (changes.size >= 2) {
                                isTwoFinger = true
                                val avgY = changes.map { it.position.y }.average().toFloat()
                                if (lastTwoFingerY != 0f) {
                                    val deltaY = avgY - lastTwoFingerY
                                    if (abs(deltaY) > 1f) {
                                        onSendMessage(JSONObject().apply {
                                            put("type", "mouse_wheel")
                                            put("delta", -deltaY * 5f)
                                        }.toString())
                                        isDragging = true
                                    }
                                }
                                lastTwoFingerY = avgY
                            } else if (changes.size == 1 && !isTwoFinger) {
                                val change = changes[0]
                                if (change.pressed) {
                                    val dragAmount = change.position - change.previousPosition
                                    totalMoveDist += dragAmount.getDistance()
                                    
                                    if (dragAmount.getDistance() > 0.5f) {
                                        onMoveCursor(dragAmount.x * 2f, dragAmount.y * 2f)
                                        if (totalMoveDist > 10f) {
                                            isDragging = true
                                        }
                                    }

                                    if (!longPressTriggered && !isDragging && 
                                        currentTime - startTime > 350) {
                                        longPressTriggered = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSendMessage(JSONObject().apply {
                                            put("type", "mouse_down")
                                            put("button", "l")
                                        }.toString())
                                    }
                                }
                            }

                            if (changes.all { it.changedToUp() }) {
                                if (!isDragging && !longPressTriggered) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (isTwoFinger) {
                                        // Two-finger tap -> Right Click
                                        onSendMessage(JSONObject().apply {
                                            put("type", "mouse_down")
                                            put("button", "r")
                                        }.toString())
                                        onSendMessage(JSONObject().apply {
                                            put("type", "mouse_up")
                                            put("button", "r")
                                        }.toString())
                                    } else {
                                        // Single-finger tap -> Left Click
                                        onSendMessage(JSONObject().apply {
                                            put("type", "mouse_down")
                                            put("button", "l")
                                        }.toString())
                                        onSendMessage(JSONObject().apply {
                                            put("type", "mouse_up")
                                            put("button", "l")
                                        }.toString())
                                    }
                                }
                                
                                if (longPressTriggered) {
                                    onSendMessage(JSONObject().apply {
                                        put("type", "mouse_up")
                                        put("button", "l")
                                    }.toString())
                                }
                                break
                            }
                            
                            changes.forEach { it.consume() }
                        }
                    }
                },
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.8f)
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (showFramePacingGraph) {
                    FramePacingGraph(
                        samples = framePacingSamples,
                        cursorSamples = cursorPacingSamples,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = if (isPointerCaptured) stringResource(R.string.trackpad_captured) else stringResource(R.string.trackpad_idle),
                    color = Color.DarkGray,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TaskSwitcherSlider(
            taskCount = openTaskCount,
            onSendMessage = onSendMessage
        )
    }
}

@Composable
private fun FramePacingGraph(
    samples: List<Float>,
    cursorSamples: List<Float>,
    modifier: Modifier = Modifier
) {
    val graphColor = Color.LightGray.copy(alpha = 0.95f)
    val cursorGraphColor = Color(0xFF42A5F5).copy(alpha = 0.95f)
    val guideColor = Color.Gray.copy(alpha = 0.45f)
    val targetColor = Color(0xFFBDBDBD).copy(alpha = 0.95f)
    val latestColor = Color.White.copy(alpha = 0.95f)
    val cursorLatestColor = Color(0xFF90CAF9).copy(alpha = 0.95f)
    val labelColor = Color.LightGray.copy(alpha = 0.9f)
    val startIndex = 0
    val middleIndex = if (samples.isEmpty()) 0 else (samples.lastIndex / 2)
    val endIndex = samples.lastIndex.coerceAtLeast(0)
    val baselineMs = 16.67f
    val latestMs = samples.lastOrNull() ?: baselineMs
    val avgMs = if (samples.isNotEmpty()) samples.average().toFloat() else baselineMs
    val minMs = samples.minOrNull() ?: baselineMs
    val maxSampleMs = maxOf(samples.maxOrNull() ?: baselineMs, cursorSamples.maxOrNull() ?: baselineMs)
    val maxMs = maxOf(baselineMs * 2f, maxSampleMs)

    BoxWithConstraints(modifier = modifier) {
        val graphHeight = maxHeight / 3
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(graphHeight)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.frame_pacing_stats, latestMs, avgMs, minMs, maxSampleMs),
                    color = labelColor,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "cursor ${cursorSamples.lastOrNull()?.let { "%.1f".format(it) } ?: "--"} ms",
                    color = cursorGraphColor,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.weight(1f)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                val graphTop = size.height * 0.1f
                val graphBottom = size.height * 0.82f
                val graphBodyHeight = (graphBottom - graphTop).coerceAtLeast(1f)
                fun toY(sample: Float): Float {
                    val normalized = sample.coerceIn(0f, maxMs) / maxMs
                    return graphBottom - (normalized * graphBodyHeight)
                }

                drawRect(
                    color = guideColor.copy(alpha = 0.16f),
                    topLeft = Offset.Zero,
                    size = size
                )
                drawLine(
                    color = guideColor,
                    start = Offset(0f, graphTop),
                    end = Offset(size.width, graphTop),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = guideColor,
                    start = Offset(0f, graphBottom),
                    end = Offset(size.width, graphBottom),
                    strokeWidth = 1.dp.toPx()
                )
                val baselineY = toY(baselineMs)
                drawLine(
                    color = targetColor,
                    start = Offset(0f, baselineY),
                    end = Offset(size.width, baselineY),
                    strokeWidth = 1.5.dp.toPx()
                )
                if (samples.size < 2) {
                    if (cursorSamples.size < 2) {
                        return@Canvas
                    }
                }
                if (samples.size >= 2) {
                    val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
                    var previous = Offset(x = 0f, y = toY(samples.first()))
                    for (i in 1 until samples.size) {
                        val current = Offset(
                            x = stepX * i,
                            y = toY(samples[i])
                        )
                        drawLine(
                            color = graphColor,
                            start = previous,
                            end = current,
                            strokeWidth = 2.5.dp.toPx()
                        )
                        previous = current
                    }
                    drawCircle(
                        color = latestColor,
                        radius = 3.dp.toPx(),
                        center = previous
                    )
                }
                if (cursorSamples.size >= 2) {
                    val cursorStepX = size.width / (cursorSamples.size - 1).coerceAtLeast(1)
                    var cursorPrevious = Offset(x = 0f, y = toY(cursorSamples.first()))
                    for (i in 1 until cursorSamples.size) {
                        val current = Offset(
                            x = cursorStepX * i,
                            y = toY(cursorSamples[i])
                        )
                        drawLine(
                            color = cursorGraphColor,
                            start = cursorPrevious,
                            end = current,
                            strokeWidth = 2.dp.toPx()
                        )
                        cursorPrevious = current
                    }
                    drawCircle(
                        color = cursorLatestColor,
                        radius = 2.5.dp.toPx(),
                        center = cursorPrevious
                    )
                }
            }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 4.dp, top = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.frame_pacing_y_label, maxMs),
                        color = labelColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.frame_pacing_y_label, baselineMs),
                        color = labelColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.frame_pacing_y_label, 0f),
                        color = labelColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .wrapContentHeight(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = startIndex.toString(),
                        color = labelColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = middleIndex.toString(),
                        color = labelColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = endIndex.toString(),
                        color = labelColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

private fun sendTaskSwitcher(onSendMessage: (String) -> Unit, action: String, steps: Int = 0) {
    onSendMessage(JSONObject().apply {
        put("type", "task_switcher")
        put("action", action)
        if (action == "step") put("steps", steps)
    }.toString())
}

private fun displayTickForOffset(tabOffset: Int, taskCount: Int): Int {
    if (taskCount < 2) return 0
    return ((tabOffset % taskCount) + taskCount) % taskCount
}

@Composable
private fun TaskSwitcherSegment(
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val inactive = Color.White.copy(alpha = 0.14f)
    val color by animateColorAsState(
        targetValue = if (active) primary else inactive,
        label = "taskSegment"
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(color)
    )
}

@Composable
private fun TaskSwitcherTrack(
    taskCount: Int,
    activeIndex: Int,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val trackBg = Color.White.copy(alpha = 0.14f)

    if (taskCount <= 12) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(taskCount) { index ->
                TaskSwitcherSegment(
                    active = index == activeIndex,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(12.dp)
        ) {
            val radius = size.height / 2f
            drawRoundRect(
                color = trackBg,
                size = size,
                cornerRadius = CornerRadius(radius, radius)
            )
            val fraction = activeIndex.toFloat() / (taskCount - 1).coerceAtLeast(1)
            val thumbRadius = radius * 0.85f
            val thumbX = (fraction * size.width).coerceIn(thumbRadius, size.width - thumbRadius)
            drawCircle(
                color = primary,
                radius = thumbRadius,
                center = Offset(thumbX, size.height / 2f)
            )
        }
    }
}

@Composable
private fun TaskSwitcherSlider(
    taskCount: Int,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveTaskCount = taskCount.coerceAtLeast(2)

    val haptic = LocalHapticFeedback.current
    val sendMessage by rememberUpdatedState(onSendMessage)
    val density = LocalDensity.current
    val verticalThresholdPx = with(density) { 48.dp.toPx() }
    val tickSpacingPx = with(density) { 24.dp.toPx() }
    var tabOffset by remember { mutableIntStateOf(0) }
    var gestureActive by remember { mutableStateOf(false) }
    var displayTaskCount by remember { mutableIntStateOf(effectiveTaskCount) }
    val switcherActive = remember { mutableStateOf(false) }
    val activeIndex = displayTickForOffset(tabOffset, displayTaskCount)

    LaunchedEffect(taskCount, gestureActive) {
        if (!gestureActive) {
            displayTaskCount = taskCount.coerceAtLeast(2)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (switcherActive.value) {
                sendTaskSwitcher(sendMessage, "cancel")
                switcherActive.value = false
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    var completedNormally = false
                    try {
                        val down = awaitFirstDown()
                        val pointerId = down.id
                        gestureActive = true
                        sendTaskSwitcher(sendMessage, "open")
                        switcherActive.value = true
                        tabOffset = 0
                        var accumulatedDx = 0f
                        var totalDx = 0f
                        var totalDy = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                            if (change.pressed) {
                                val dragAmount = change.position - change.previousPosition
                                totalDx += dragAmount.x
                                totalDy += dragAmount.y
                                accumulatedDx += dragAmount.x

                                var stepDelta = 0
                                while (accumulatedDx >= tickSpacingPx) {
                                    accumulatedDx -= tickSpacingPx
                                    tabOffset++
                                    stepDelta++
                                }
                                while (accumulatedDx <= -tickSpacingPx) {
                                    accumulatedDx += tickSpacingPx
                                    tabOffset--
                                    stepDelta--
                                }
                                if (stepDelta != 0) {
                                    sendTaskSwitcher(sendMessage, "step", stepDelta)
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }

                            if (change.changedToUp()) {
                                val dominantVertical =
                                    abs(totalDy) > abs(totalDx) && abs(totalDy) >= verticalThresholdPx
                                val endAction = when {
                                    dominantVertical && totalDy < 0f && tabOffset != 0 -> "dismiss"
                                    dominantVertical && totalDy < 0f -> "start_menu"
                                    tabOffset == 0 -> "cancel"
                                    else -> "commit"
                                }
                                sendTaskSwitcher(sendMessage, endAction)
                                switcherActive.value = false
                                tabOffset = 0
                                completedNormally = true
                                break
                            }

                            change.consume()
                        }
                    } finally {
                        if (!completedNormally && switcherActive.value) {
                            sendTaskSwitcher(sendMessage, "cancel")
                            switcherActive.value = false
                        }
                        gestureActive = false
                    }
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.8f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.task_switcher_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Text(
                    text = if (gestureActive) {
                        stringResource(R.string.task_switcher_position, activeIndex + 1, displayTaskCount)
                    } else {
                        stringResource(R.string.task_switcher_apps, displayTaskCount)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (gestureActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.45f)
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            TaskSwitcherTrack(
                taskCount = displayTaskCount,
                activeIndex = activeIndex
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.task_switcher_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

fun androidKeyCodeToWindowsVk(keyCode: Int): Int {
    return when (keyCode) {
        KeyEvent.KEYCODE_A -> 0x41
        KeyEvent.KEYCODE_B -> 0x42
        KeyEvent.KEYCODE_C -> 0x43
        KeyEvent.KEYCODE_D -> 0x44
        KeyEvent.KEYCODE_E -> 0x45
        KeyEvent.KEYCODE_F -> 0x46
        KeyEvent.KEYCODE_G -> 0x47
        KeyEvent.KEYCODE_H -> 0x48
        KeyEvent.KEYCODE_I -> 0x49
        KeyEvent.KEYCODE_J -> 0x4A
        KeyEvent.KEYCODE_K -> 0x4B
        KeyEvent.KEYCODE_L -> 0x4C
        KeyEvent.KEYCODE_M -> 0x4D
        KeyEvent.KEYCODE_N -> 0x4E
        KeyEvent.KEYCODE_O -> 0x4F
        KeyEvent.KEYCODE_P -> 0x50
        KeyEvent.KEYCODE_Q -> 0x51
        KeyEvent.KEYCODE_R -> 0x52
        KeyEvent.KEYCODE_S -> 0x53
        KeyEvent.KEYCODE_T -> 0x54
        KeyEvent.KEYCODE_U -> 0x55
        KeyEvent.KEYCODE_V -> 0x56
        KeyEvent.KEYCODE_W -> 0x57
        KeyEvent.KEYCODE_X -> 0x58
        KeyEvent.KEYCODE_Y -> 0x59
        KeyEvent.KEYCODE_Z -> 0x5A
        KeyEvent.KEYCODE_0 -> 0x30
        KeyEvent.KEYCODE_1 -> 0x31
        KeyEvent.KEYCODE_2 -> 0x32
        KeyEvent.KEYCODE_3 -> 0x33
        KeyEvent.KEYCODE_4 -> 0x34
        KeyEvent.KEYCODE_5 -> 0x35
        KeyEvent.KEYCODE_6 -> 0x36
        KeyEvent.KEYCODE_7 -> 0x37
        KeyEvent.KEYCODE_8 -> 0x38
        KeyEvent.KEYCODE_9 -> 0x39
        KeyEvent.KEYCODE_SPACE -> 0x20
        KeyEvent.KEYCODE_ENTER -> 0x0D
        KeyEvent.KEYCODE_DEL -> 0x08
        KeyEvent.KEYCODE_ESCAPE -> 0x1B
        KeyEvent.KEYCODE_TAB -> 0x09
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 0x10
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 0x11
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 0x12
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x14
        KeyEvent.KEYCODE_DPAD_UP -> 0x26
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x28
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x25
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27
        KeyEvent.KEYCODE_MOVE_HOME -> 0x24
        KeyEvent.KEYCODE_MOVE_END -> 0x23
        KeyEvent.KEYCODE_PAGE_UP -> 0x21
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x22
        KeyEvent.KEYCODE_INSERT -> 0x2D
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x2E
        KeyEvent.KEYCODE_GRAVE -> 0xC0
        KeyEvent.KEYCODE_MINUS -> 0xBD
        KeyEvent.KEYCODE_EQUALS -> 0xBB
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0xDB
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xDD
        KeyEvent.KEYCODE_BACKSLASH -> 0xDC
        KeyEvent.KEYCODE_SEMICOLON -> 0xBA
        KeyEvent.KEYCODE_APOSTROPHE -> 0xDE
        KeyEvent.KEYCODE_COMMA -> 0xBC
        KeyEvent.KEYCODE_PERIOD -> 0xBE
        KeyEvent.KEYCODE_SLASH -> 0xBF
        KeyEvent.KEYCODE_F1 -> 0x70
        KeyEvent.KEYCODE_F2 -> 0x71
        KeyEvent.KEYCODE_F3 -> 0x72
        KeyEvent.KEYCODE_F4 -> 0x73
        KeyEvent.KEYCODE_F5 -> 0x74
        KeyEvent.KEYCODE_F6 -> 0x75
        KeyEvent.KEYCODE_F7 -> 0x76
        KeyEvent.KEYCODE_F8 -> 0x77
        KeyEvent.KEYCODE_F9 -> 0x78
        KeyEvent.KEYCODE_F10 -> 0x79
        KeyEvent.KEYCODE_F11 -> 0x7A
        KeyEvent.KEYCODE_F12 -> 0x7B
        KeyEvent.KEYCODE_BACK -> 0x08
        else -> 0
    }
}

fun charToVk(char: Char): Int {
    val u = char.uppercaseChar()
    return when (u) {
        in 'A'..'Z' -> u.code
        in '0'..'9' -> u.code
        ')' -> 0x30
        '!' -> 0x31
        '@' -> 0x32
        '#' -> 0x33
        '$' -> 0x34
        '%' -> 0x35
        '^' -> 0x36
        '&' -> 0x37
        '*' -> 0x38
        '(' -> 0x39
        ' ' -> 0x20
        '\n' -> 0x0D
        '\t' -> 0x09
        ',', '<' -> 0xBC
        '.', '>' -> 0xBE
        '/', '?' -> 0xBF
        ';', ':' -> 0xBA
        '\'', '\"' -> 0xDE
        '[', '{' -> 0xDB
        ']', '}' -> 0xDD
        '\\', '|' -> 0xDC
        '-', '_' -> 0xBD
        '=', '+' -> 0xBB
        '`', '~' -> 0xC0
        else -> 0
    }
}

@Composable
private fun ThreeDofPhoneStatus() {
    val context = LocalContext.current
    val tracker = remember { RayNeoGlassesTracker.getInstance(context) }
    var status by remember { mutableStateOf(tracker.status) }
    var usbSummary by remember { mutableStateOf(tracker.usbDeviceSummary()) }
    var vizObject by remember { mutableStateOf(tracker.vizObject) }

    LaunchedEffect(Unit) {
        while (true) {
            status = tracker.status
            usbSummary = tracker.usbDeviceSummary()
            vizObject = tracker.vizObject
            delay(500)
        }
    }

    Text(
        text = stringResource(R.string.three_dof_status, status),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        text = usbSummary,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { tracker.reset() }) {
            Text(stringResource(R.string.reset_orientation))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { vizObject = tracker.cycleVizObject() }) {
            Text(
                when (vizObject) {
                    RayNeoGlassesTracker.VizObject.AXIS -> stringResource(R.string.viz_axis)
                    RayNeoGlassesTracker.VizObject.GLASSES -> stringResource(R.string.viz_glasses)
                }
            )
        }
    }
    Text(
        text = stringResource(R.string.reset_orientation_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = stringResource(R.string.usb_permission_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
