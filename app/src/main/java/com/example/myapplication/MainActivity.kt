package com.example.myapplication

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.discovery.DiscoveredMachine
import com.example.myapplication.discovery.MachineDiscovery
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private var externalDisplayService: ExternalDisplayService? = null
    private var isBound by mutableStateOf(false)
    private var discoveredMachines by mutableStateOf<List<DiscoveredMachine>>(emptyList())
    private var activeMachine by mutableStateOf<DiscoveredMachine?>(null)
    private var isPointerCaptured by mutableStateOf(false)
    private var is3DofEnabled by mutableStateOf(false)

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
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
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
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val machine = activeMachine
                    if (machine == null) {
                        HomeScreen(
                            discoveredMachines = discoveredMachines,
                            onMachineSelected = { 
                                activeMachine = it
                                externalDisplayService?.activeMachine = it
                                externalDisplayService?.currentState = ExternalDisplayState.REMOTE_SCREEN
                            },
                            modifier = Modifier.padding(innerPadding).imePadding()
                        )
                    } else {
                        ScreenSharingScreen(
                            machine = machine,
                            currentFps = externalDisplayService?.currentFps ?: 0,
                            currentKbps = externalDisplayService?.currentKbps ?: 0,
                            currentCaptureMethod = externalDisplayService?.currentCaptureMethod ?: "Loading...",
                            isPointerCaptured = isPointerCaptured,
                            is3DofEnabled = is3DofEnabled,
                            onStop = { 
                                activeMachine = null
                                externalDisplayService?.activeMachine = null
                                externalDisplayService?.currentState = ExternalDisplayState.IDLE
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
            text = "Nearby Computers",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (discoveredMachines.isEmpty()) {
            Text(
                text = "Scanning the local network...",
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
                text = "${machine.host}:${machine.port}",
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
    currentKbps: Int,
    currentCaptureMethod: String,
    isPointerCaptured: Boolean,
    is3DofEnabled: Boolean,
    onStop: () -> Unit,
    onMonitorSwitch: (Int) -> Unit,
    onSendMessage: (String) -> Unit,
    onMoveCursor: (Float, Float) -> Unit,
    onCapturedMouseEvent: (MotionEvent) -> Boolean,
    onCaptureToggle: (Boolean) -> Unit,
    on3DofToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var monitorIndex by remember { mutableIntStateOf(1) }
    var showDebugMenu by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var keyboardText by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
    val view = LocalView.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val currentCapturedMouseEvent by rememberUpdatedState(onCapturedMouseEvent)

    BackHandler(onBack = onStop)

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
                    text = "Screen Sharing",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = machine.name,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Button(onClick = { 
                focusRequester.requestFocus()
                keyboardController?.show()
            }) {
                Text("Kbd")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val root = view.rootView
                    root.isFocusable = true
                    root.isFocusableInTouchMode = true
                    root.requestFocus()
                }
                onCaptureToggle(!isPointerCaptured)
            }) {
                Text(if (isPointerCaptured) "Unlock" else "Lock")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Button(onClick = { showDebugMenu = !showDebugMenu }) {
                Text("Debug")
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
                    Text("Debug Menu", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = is3DofEnabled,
                            onCheckedChange = { on3DofToggle(it) }
                        )
                        Text("Enable 3DOF View (Rayneo Air 3S Pro)")
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
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "FPS: $currentFps | Bitrate: $currentKbps kbps", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.weight(1f))
                Text(text = currentCaptureMethod, style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                Text(
                    text = if (isPointerCaptured) "POINTER CAPTURED" else "VIRTUAL TRACKPAD",
                    color = Color.DarkGray,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { 
                monitorIndex = if (monitorIndex == 1) 2 else 1
                onMonitorSwitch(monitorIndex)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Switch Monitor")
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
        text = "3DOF: $status",
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
            Text("Reset orientation")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { vizObject = tracker.cycleVizObject() }) {
            Text(
                when (vizObject) {
                    RayNeoGlassesTracker.VizObject.AXIS -> "Viz: Axis"
                    RayNeoGlassesTracker.VizObject.GLASSES -> "Viz: Glasses"
                }
            )
        }
    }
    Text(
        text = "Reset re-zeros HUD. Hold still ~2s on connect for gyro bias calibration.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = "USB permission appears on this phone screen, not the glasses.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
