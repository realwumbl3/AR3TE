package com.ar3te

import android.app.Presentation
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Base64
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.ar3te.discovery.DiscoveredMachine
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

enum class ExternalDisplayState {
    IDLE, REMOTE_SCREEN
}

private const val REMOTE_TAG = "RemoteScreenView"
private const val H264_TAG = "H264StreamController"
private const val VIDEO_TIMESTAMP_BYTES = 8
private const val VIDEO_HEADER_BYTES = 1 + VIDEO_TIMESTAMP_BYTES
private const val ENABLE_DEBUG_LOGS = false

private fun debugLog(tag: String, message: String) {
    if (!ENABLE_DEBUG_LOGS) {
        return
    }
    Log.d(tag, message)
    println("$tag: $message")
}

private fun debugError(tag: String, message: String, error: Throwable? = null) {
    if (error != null) {
        Log.e(tag, message, error)
        println("$tag: $message\n${error.stackTraceToString()}")
    } else {
        Log.e(tag, message)
        println("$tag: $message")
    }
}

private fun parseTimestampedVideoPacket(data: ByteArray): Pair<ByteArray, Long?> {
    if (data.size <= VIDEO_HEADER_BYTES) {
        return data.copyOfRange(1, data.size) to null
    }
    val captureMs = ByteBuffer.wrap(data, 1, VIDEO_TIMESTAMP_BYTES).long
    if (!isPlausibleWallClockMs(captureMs)) {
        return data.copyOfRange(1, data.size) to null
    }
    val payload = data.copyOfRange(VIDEO_HEADER_BYTES, data.size)
    return payload to captureMs
}

private fun isPlausibleWallClockMs(value: Long): Boolean {
    // Reject H.264 start codes and other garbage; accept real epoch-ms timestamps (~2020+).
    return value >= 1_577_836_800_000L
}

data class RemoteCursorState(
    val visible: Boolean,
    val x: Float,
    val y: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val bitmap: Bitmap? = null,
    val hotX: Int = 0,
    val hotY: Int = 0
)

private const val GLASSES_MODEL_ASSET = "models/polygonal_ar_glasses.json"

private data class GlassesWireframeModel(
    val scale: Float,
    val perspective: Float,
    val parts: List<GlassesWireframePart>
)

private data class GlassesWireframePart(
    val name: String,
    val color: String,
    val strokeWidth: Float,
    val closed: Boolean,
    val points: List<WirePoint3D>
)

private data class WirePoint3D(
    val x: Float,
    val y: Float,
    val z: Float
)

class ExternalDisplayPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore = store

    var displayState by mutableStateOf(ExternalDisplayState.IDLE)
    var activeMachine by mutableStateOf<DiscoveredMachine?>(null)
    var monitorIndex by mutableIntStateOf(1)
    var is3DofEnabled by mutableStateOf(false)
    var onStatsUpdated: ((Int, Double, String?, Int) -> Unit)? = null
    var onCaptureMethodUpdated: ((String) -> Unit)? = null
    var onAudioStateUpdated: ((String) -> Unit)? = null
    var onTaskCountUpdated: ((Int) -> Unit)? = null
    var onSendMessage: ((String) -> Unit)? = null
    var onRemoteCursorReceived: ((Float, Float, Int, Int) -> Unit)? = null
    
    private var _localCursorX by mutableStateOf(0f)
    var localCursorX: Float
        get() = _localCursorX
        set(value) { _localCursorX = value }

    private var _localCursorY by mutableStateOf(0f)
    var localCursorY: Float
        get() = _localCursorY
        set(value) { _localCursorY = value }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@ExternalDisplayPresentation)
            setViewTreeSavedStateRegistryOwner(this@ExternalDisplayPresentation)
            setViewTreeViewModelStoreOwner(this@ExternalDisplayPresentation)
            setContent {
                ExternalDisplayScreen(
                    displayState, 
                    activeMachine, 
                    monitorIndex, 
                    is3DofEnabled,
                    onStatsUpdated, 
                    onCaptureMethodUpdated, 
                    onAudioStateUpdated,
                    onTaskCountUpdated,
                    { onSendMessage = it },
                    { x, y, w, h -> onRemoteCursorReceived?.invoke(x, y, w, h) },
                    localCursorX,
                    localCursorY
                )
            }
        }
        
        setContentView(composeView)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onStop() {
        super.onStop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }
}

@Composable
fun ExternalDisplayScreen(
    state: ExternalDisplayState,
    machine: DiscoveredMachine? = null,
    monitorIndex: Int = 1,
    is3DofEnabled: Boolean = false,
    onStatsUpdated: ((Int, Double, String?, Int) -> Unit)? = null,
    onCaptureMethodUpdated: ((String) -> Unit)? = null,
    onAudioStateUpdated: ((String) -> Unit)? = null,
    onTaskCountUpdated: ((Int) -> Unit)? = null,
    onClientReady: ((String) -> Unit) -> Unit = {},
    onRemoteCursorReceived: (Float, Float, Int, Int) -> Unit = { _, _, _, _ -> },
    localCursorX: Float = 0f,
    localCursorY: Float = 0f
) {
    when (state) {
        ExternalDisplayState.IDLE -> IdleScreen()
        ExternalDisplayState.REMOTE_SCREEN -> key(machine?.host) {
            RemoteScreenView(
                machine, 
                monitorIndex, 
                is3DofEnabled,
                onStatsUpdated, 
                onCaptureMethodUpdated, 
                onAudioStateUpdated,
                onTaskCountUpdated,
                onClientReady, 
                onRemoteCursorReceived,
                localCursorX,
                localCursorY
            )
        }
    }
}

@Composable
fun IdleScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "READY TO CONNECT",
                color = Color.DarkGray,
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Select a computer on your phone to start sharing",
                color = Color.DarkGray,
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}

@Composable
fun RemoteScreenView(
    machine: DiscoveredMachine?,
    monitorIndex: Int,
    is3DofEnabled: Boolean,
    onStatsUpdated: ((Int, Double, String?, Int) -> Unit)? = null,
    onCaptureMethodUpdated: ((String) -> Unit)? = null,
    onAudioStateUpdated: ((String) -> Unit)? = null,
    onTaskCountUpdated: ((Int) -> Unit)? = null,
    onClientReady: ((String) -> Unit) -> Unit = {},
    onRemoteCursorReceived: (Float, Float, Int, Int) -> Unit = { _, _, _, _ -> },
    localCursorX: Float = 0f,
    localCursorY: Float = 0f
) {
    val scope = rememberCoroutineScope()
    val machineHost = machine?.host
    var client by remember(machineHost) { mutableStateOf<WebSocketClient?>(null) }
    
    LaunchedEffect(client) {
        onClientReady { msg -> 
            client?.let {
                if (it.isOpen) {
                    it.send(msg)
                }
            }
        }
    }
    LaunchedEffect(client, monitorIndex) {
        client?.let {
            if (it.isOpen) {
                it.send(JSONObject().apply {
                    put("type", "set_monitor")
                    put("value", monitorIndex)
                }.toString())
            }
        }
    }
    var videoConfig by remember(machineHost) { mutableStateOf<H264StreamConfig?>(null) }
    var captureMethod by remember(machineHost) { mutableStateOf<String?>(null) }
    var cursor by remember(machineHost) { mutableStateOf<RemoteCursorState?>(null) }
    var viewSize by remember(machineHost) { mutableStateOf(IntSize.Zero) }
    val decoderController = remember(machineHost) { H264StreamController(scope) }
    val audioController = remember(machineHost) { PcmAudioController(scope) }

    DisposableEffect(machineHost) {
        if (machine == null) {
            onDispose {}
        } else {
            debugLog(REMOTE_TAG, "Connecting to ws://${machine.host}:${machine.wsPort}")

            var frameCount = 0
            var byteCount = 0L
            var latencyEma = 0.0
            var hasLatency = false
            var clockOffsetMs = 0L
            var hasClockOffset = false
            val pendingPings = mutableMapOf<Long, Long>()

            val statsJob = scope.launch(Dispatchers.Default) {
                while (isActive) {
                    kotlinx.coroutines.delay(1000)
                    val fps = frameCount
                    val megabytesPerSecond = byteCount / (1024.0 * 1024.0)
                    val latencyMs = if (hasLatency) latencyEma.roundToInt() else -1
                    withContext(Dispatchers.Main) {
                        onStatsUpdated?.invoke(fps, megabytesPerSecond, captureMethod, latencyMs)
                    }
                    debugLog(REMOTE_TAG, "Stream stats: fps=$fps MBps=$megabytesPerSecond latencyMs=$latencyMs")
                    frameCount = 0
                    byteCount = 0
                }
            }

            val pingJob = scope.launch(Dispatchers.Default) {
                var pingId = 0L
                while (isActive) {
                    kotlinx.coroutines.delay(2000)
                    val client = client
                    if (client?.isOpen != true) {
                        continue
                    }
                    val sentAt = System.currentTimeMillis()
                    pingId += 1
                    pendingPings[pingId] = sentAt
                    client.send(JSONObject().apply {
                        put("type", "ping")
                        put("id", pingId)
                        put("t", sentAt)
                    }.toString())
                }
            }

            decoderController.clockOffsetMs = { clockOffsetMs }
            decoderController.onLatencySample = { sample ->
                latencyEma = if (!hasLatency) {
                    sample
                } else {
                    latencyEma * 0.85 + sample * 0.15
                }
                hasLatency = true
            }

            val uri = URI("ws://${machine.host}:${machine.wsPort}")
            val wsClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    debugLog(REMOTE_TAG, "Connected")
                    send(JSONObject().apply {
                        put("type", "set_monitor")
                        put("value", monitorIndex)
                    }.toString())
                    val sentAt = System.currentTimeMillis()
                    pendingPings[1L] = sentAt
                    send(JSONObject().apply {
                        put("type", "ping")
                        put("id", 1L)
                        put("t", sentAt)
                    }.toString())
                }

                override fun onMessage(message: String?) {
                    if (message.isNullOrBlank()) return
                    try {
                        val json = JSONObject(message)
                        when (json.optString("type")) {
                            "stream_reset" -> {
                                val resetMonitor = json.optInt("monitor", monitorIndex)
                                debugLog(REMOTE_TAG, "stream_reset received for monitor $resetMonitor")
                                hasLatency = false
                                latencyEma = 0.0
                                hasClockOffset = false
                                clockOffsetMs = 0L
                                pendingPings.clear()
                                scope.launch(Dispatchers.Main) {
                                    videoConfig = null
                                    captureMethod = null
                                    cursor = null
                                    decoderController.resetStream()
                                    onStatsUpdated?.invoke(0, 0.0, captureMethod, -1)
                                }
                            }
                            "capture_status" -> {
                                val method = json.optString("method", "").takeIf { it.isNotBlank() }
                                if (method != null) {
                                    scope.launch(Dispatchers.Main) {
                                        captureMethod = method
                                        onCaptureMethodUpdated?.invoke(method)
                                    }
                                }
                            }
                            "task_count" -> {
                                val count = json.optInt("count", 0)
                                if (count > 0) {
                                    scope.launch(Dispatchers.Main) {
                                        onTaskCountUpdated?.invoke(count)
                                    }
                                }
                            }
                            "audio_reset" -> {
                                debugLog(REMOTE_TAG, "audio_reset received")
                                scope.launch(Dispatchers.Main) {
                                    audioController.resetStream()
                                    onAudioStateUpdated?.invoke("Desktop audio reconnecting")
                                }
                            }
                            "pong" -> {
                                val pingId = json.optLong("id", -1)
                                val sentAt = pendingPings.remove(pingId) ?: return
                                val serverNow = json.optLong("server_now", -1)
                                if (serverNow < 0L) {
                                    return
                                }
                                val rtt = (System.currentTimeMillis() - sentAt).coerceAtLeast(0L)
                                val offsetEstimate = serverNow - sentAt - (rtt / 2)
                                clockOffsetMs = if (!hasClockOffset) {
                                    offsetEstimate
                                } else {
                                    ((clockOffsetMs * 3) + offsetEstimate) / 4
                                }
                                hasClockOffset = true
                            }
                            "audio_status" -> {
                                val state = json.optString("state", "unknown")
                                val messageText = json.optString("message", state)
                                debugLog(REMOTE_TAG, "audio_status: $state $messageText")
                                scope.launch(Dispatchers.Main) {
                                    onAudioStateUpdated?.invoke(messageText)
                                }
                            }
                            "audio_config" -> {
                                try {
                                    val nextConfig = RemoteAudioConfig(
                                        sampleRate = json.optInt("sample_rate", 48000),
                                        channels = json.optInt("channels", 2),
                                        frameDurationMs = json.optInt("frame_duration_ms", 20),
                                        sampleFormat = json.optString("sample_format", "s16le")
                                    )
                                    debugLog(
                                        REMOTE_TAG,
                                        "audio_config received: ${nextConfig.sampleRate}Hz ${nextConfig.channels}ch format=${nextConfig.sampleFormat} frame=${nextConfig.frameDurationMs}ms"
                                    )
                                    scope.launch(Dispatchers.Main) {
                                        audioController.configure(nextConfig)
                                        onAudioStateUpdated?.invoke("Desktop audio connected")
                                    }
                                } catch (e: Exception) {
                                    debugError(REMOTE_TAG, "Failed to parse audio_config", e)
                                }
                            }
                            "cursor" -> {
                                val visible = json.optBoolean("visible", true)
                                val nextCursor = if (visible) {
                                    val cursorBitmap = if (json.has("img")) {
                                        try {
                                            val cursorBytes = Base64.decode(json.getString("img"), Base64.DEFAULT)
                                            BitmapFactory.decodeByteArray(cursorBytes, 0, cursorBytes.size)
                                        } catch (_: Exception) {
                                            cursor?.bitmap
                                        }
                                    } else {
                                        cursor?.bitmap
                                    }

                                    RemoteCursorState(
                                        visible = true,
                                        x = json.optDouble("x").toFloat(),
                                        y = json.optDouble("y").toFloat(),
                                        sourceWidth = json.optInt("w", videoConfig?.width ?: 1),
                                        sourceHeight = json.optInt("h", videoConfig?.height ?: 1),
                                        bitmap = cursorBitmap,
                                        hotX = if (json.has("hx")) json.optInt("hx") else (cursor?.hotX ?: 0),
                                        hotY = if (json.has("hy")) json.optInt("hy") else (cursor?.hotY ?: 0)
                                    )
                                } else {
                                    // Even if invisible, keep the last known state but set visible=false
                                    cursor?.copy(visible = false)
                                }
                                nextCursor?.let {
                                    onRemoteCursorReceived(it.x, it.y, it.sourceWidth, it.sourceHeight)
                                }
                                scope.launch(Dispatchers.Main) {
                                    cursor = nextCursor
                                }
                            }
                            "video_config" -> {
                                val sps = Base64.decode(json.getString("sps"), Base64.DEFAULT)
                                val pps = Base64.decode(json.getString("pps"), Base64.DEFAULT)
                                val avcc = if (json.has("config") && !json.isNull("config")) {
                                    Base64.decode(json.getString("config"), Base64.DEFAULT)
                                } else {
                                    null
                                }
                                val nextConfig = H264StreamConfig(
                                    width = json.getInt("width"),
                                    height = json.getInt("height"),
                                    fps = json.optInt("fps", 60),
                                    encoder = json.optString("encoder", "unknown"),
                                    captureMethod = json.optString("capture_method", captureMethod ?: "Loading..."),
                                    sps = sps,
                                    pps = pps,
                                    avcc = avcc
                                )
                                debugLog(
                                    REMOTE_TAG,
                                    "video_config received: ${nextConfig.width}x${nextConfig.height} fps=${nextConfig.fps} encoder=${nextConfig.encoder} capture=${nextConfig.captureMethod} sps=${sps.size} pps=${pps.size} avcc=${avcc?.size ?: 0}"
                                )
                                scope.launch(Dispatchers.Main) {
                                    videoConfig = nextConfig
                                    captureMethod = nextConfig.captureMethod
                                    onCaptureMethodUpdated?.invoke(nextConfig.captureMethod)
                                    decoderController.configure(nextConfig)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        debugError(REMOTE_TAG, "Failed to parse control message", e)
                    }
                }

                override fun onMessage(bytes: ByteBuffer?) {
                    bytes?.let {
                        val size = it.remaining()
                        val data = ByteArray(size)
                        it.get(data)
                        if (data.isNotEmpty()) {
                            when (data[0].toInt() and 0xff) {
                                1 -> {
                                    val (payload, captureMs) = parseTimestampedVideoPacket(data)
                                    byteCount += payload.size
                                    frameCount++
                                    if (captureMs != null) {
                                        decoderController.queueFrame(payload, captureMs)
                                    } else {
                                        decoderController.queueFrame(payload)
                                    }
                                }
                                2 -> {
                                    val payload = data.copyOfRange(1, data.size)
                                    audioController.queuePacket(payload)
                                }
                                else -> {
                                    byteCount += size
                                    frameCount++
                                    if (frameCount == 1) {
                                        debugLog(REMOTE_TAG, "First legacy video payload received: $size bytes")
                                    }
                                    decoderController.queueFrame(data)
                                }
                            }
                        }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    debugLog(REMOTE_TAG, "Closed: code=$code remote=$remote reason=$reason")
                }

                override fun onError(ex: Exception?) {
                    debugError(REMOTE_TAG, "Socket Error", ex)
                }
            }
            
            wsClient.connect()
            client = wsClient

            onDispose {
                debugLog(REMOTE_TAG, "Disconnecting")
                wsClient.close()
                statsJob.cancel()
                pingJob.cancel()
                decoderController.release()
                audioController.release()
                client = null
                onStatsUpdated?.invoke(0, 0.0, null, -1)
            }
        }
    }

    key(machine?.host) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (videoConfig == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = machine?.name?.uppercase() ?: "AR3TE HOST",
                        color = Color.White,
                        style = MaterialTheme.typography.displayMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (machine == null) "NO MACHINE SELECTED" else "CONNECTING TO MONITOR $monitorIndex...",
                        color = Color.Green,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewSize = it }
                ) {
                    val streamAspect = videoConfig!!.width.toFloat() / videoConfig!!.height.toFloat()
                    val containerAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else streamAspect
                    val videoModifier = if (containerAspect > streamAspect) {
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(streamAspect)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(streamAspect)
                    }
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                keepScreenOn = true
                                holder.setFixedSize(videoConfig!!.width, videoConfig!!.height)
                                holder.addCallback(decoderController)
                                decoderController.bindSurface(holder)
                            }
                        },
                        update = { view ->
                            view.holder.setFixedSize(videoConfig!!.width, videoConfig!!.height)
                            decoderController.bindSurface(view.holder)
                        },
                        modifier = videoModifier.align(Alignment.Center)
                    )
                    CursorOverlay(cursor, viewSize, localCursorX, localCursorY)
                    
                    if (is3DofEnabled) {
                        ThreeDofVisualizer()
                    }
                }
            }
        }
    }
}

@Composable
fun ThreeDofVisualizer() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tracker = remember { RayNeoGlassesTracker.getInstance(context) }
    val glassesModel = remember(context) {
        runCatching { loadGlassesWireframeModel(context) }
            .getOrElse { defaultGlassesWireframeModel() }
    }
    var yawDeg by remember { mutableStateOf(0f) }
    var pitchDeg by remember { mutableStateOf(0f) }
    var rollDeg by remember { mutableStateOf(0f) }
    var quat by remember { mutableStateOf(floatArrayOf(1f, 0f, 0f, 0f)) }
    var vizObject by remember { mutableStateOf(RayNeoGlassesTracker.VizObject.AXIS) }
    var sensorName by remember { mutableStateOf("Detecting...") }

    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val e = tracker.eulerDeg
            yawDeg = e[0]
            pitchDeg = e[1]
            rollDeg = e[2]
            quat = tracker.displayQuat.copyOf()
            vizObject = tracker.vizObject
            sensorName = tracker.status
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        when (vizObject) {
            RayNeoGlassesTracker.VizObject.AXIS -> Axis3DGizmo(
                quat = quat,
                modifier = Modifier
                    .fillMaxSize(0.92f)
                    .padding(16.dp)
            )
            RayNeoGlassesTracker.VizObject.GLASSES -> Glasses3DGizmo(
                quat = quat,
                model = glassesModel,
                modifier = Modifier
                    .fillMaxSize(0.92f)
                    .padding(16.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                sensorName.uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Y ${String.format(Locale.US, "%.1f", yawDeg)}°  " +
                    "P ${String.format(Locale.US, "%.1f", pitchDeg)}°  " +
                    "R ${String.format(Locale.US, "%.1f", rollDeg)}°",
                color = Color.Yellow,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "X→right  Y→up  Z→forward (nose)",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun Axis3DGizmo(
    quat: FloatArray,
    modifier: Modifier = Modifier
) {
    val axisX = Color(0xFFEF5350)
    val axisY = Color(0xFF66BB6A)
    val axisZ = Color(0xFF42A5F5)

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arm = size.minDimension * 0.44f
        val strokeWidth = size.minDimension * 0.028f

        fun tip(x: Float, y: Float, z: Float): Offset {
            val r = RayNeoGlassesTracker.rotateVectorByQuat(x, y, z, quat)
            return Offset(cx + r[0], cy - r[1])
        }

        val origin = Offset(cx, cy)
        val xTip = tip(arm, 0f, 0f)
        val yTip = tip(0f, arm, 0f)
        val zTip = tip(0f, 0f, arm)

        drawLine(axisX, origin, xTip, strokeWidth, StrokeCap.Round)
        drawLine(axisY, origin, yTip, strokeWidth, StrokeCap.Round)
        drawLine(axisZ, origin, zTip, strokeWidth, StrokeCap.Round)

        val dotR = strokeWidth * 0.85f
        drawCircle(Color.White, dotR, origin)
        drawCircle(axisX, dotR * 0.7f, xTip)
        drawCircle(axisY, dotR * 0.7f, yTip)
        drawCircle(axisZ, dotR * 0.7f, zTip)
    }
}

@Composable
private fun Glasses3DGizmo(
    quat: FloatArray,
    model: GlassesWireframeModel,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = size.minDimension * model.scale
        val perspective = size.minDimension * model.perspective

        fun project(p: WirePoint3D): Offset {
            val rotated = RayNeoGlassesTracker.rotateVectorByQuat(p.x, p.y, p.z, quat)
            val depth = (perspective - rotated[2] * scale * 0.15f).coerceAtLeast(scale * 0.55f)
            val factor = perspective / depth
            return Offset(
                cx + rotated[0] * scale * factor,
                cy - rotated[1] * scale * factor
            )
        }

        fun drawLoop(points: List<WirePoint3D>, color: Color, strokeWidth: Float, closed: Boolean) {
            if (points.size < 2) return
            val stop = if (closed) points.size else points.size - 1
            for (i in 0 until stop) {
                val a = project(points[i])
                val b = project(points[(i + 1) % points.size])
                drawLine(color, a, b, strokeWidth, StrokeCap.Round)
            }
        }

        fun partColor(name: String): Color {
            return when (name) {
                "frame" -> Color(0xFFF5F7FA)
                "accent" -> Color(0xFF8EE6FF)
                "lens" -> Color(0xFF4FC3F7)
                else -> Color.White
            }
        }

        model.parts.forEach { part ->
            drawLoop(part.points, partColor(part.color), size.minDimension * part.strokeWidth, part.closed)
        }

        drawCircle(Color.White.copy(alpha = 0.10f), size.minDimension * 0.12f, Offset(cx, cy))
    }
}

private fun loadGlassesWireframeModel(context: Context): GlassesWireframeModel {
    val raw = context.assets.open(GLASSES_MODEL_ASSET).bufferedReader().use { it.readText() }
    val json = JSONObject(raw)
    val partsJson = json.getJSONArray("parts")

    val parts = buildList {
        for (i in 0 until partsJson.length()) {
            val partJson = partsJson.getJSONObject(i)
            val pointsJson = partJson.getJSONArray("points")
            val points = buildList {
                for (j in 0 until pointsJson.length()) {
                    val p = pointsJson.getJSONArray(j)
                    add(
                        WirePoint3D(
                            x = p.getDouble(0).toFloat(),
                            y = p.getDouble(1).toFloat(),
                            z = p.getDouble(2).toFloat()
                        )
                    )
                }
            }
            add(
                GlassesWireframePart(
                    name = partJson.optString("name", "part_$i"),
                    color = partJson.optString("color", "frame"),
                    strokeWidth = partJson.optDouble("stroke", 0.02).toFloat(),
                    closed = partJson.optBoolean("closed", false),
                    points = points
                )
            )
        }
    }

    return GlassesWireframeModel(
        scale = json.optDouble("scale", 0.16).toFloat(),
        perspective = json.optDouble("perspective", 2.8).toFloat(),
        parts = parts
    )
}

private fun defaultGlassesWireframeModel(): GlassesWireframeModel {
    return GlassesWireframeModel(
        scale = 0.16f,
        perspective = 2.8f,
        parts = listOf(
            GlassesWireframePart(
                name = "frame_left",
                color = "frame",
                strokeWidth = 0.021f,
                closed = true,
                points = listOf(
                    WirePoint3D(-2.45f, 0.88f, 0.12f),
                    WirePoint3D(-2.85f, 0.42f, 0.18f),
                    WirePoint3D(-2.95f, 0.02f, 0.20f),
                    WirePoint3D(-2.85f, -0.44f, 0.18f),
                    WirePoint3D(-2.45f, -0.90f, 0.12f),
                    WirePoint3D(-1.75f, -0.80f, -0.04f),
                    WirePoint3D(-1.44f, -0.42f, -0.10f),
                    WirePoint3D(-1.38f, 0.00f, -0.12f),
                    WirePoint3D(-1.44f, 0.42f, -0.10f),
                    WirePoint3D(-1.75f, 0.80f, -0.04f)
                )
            ),
            GlassesWireframePart(
                name = "frame_right",
                color = "frame",
                strokeWidth = 0.021f,
                closed = true,
                points = listOf(
                    WirePoint3D(2.45f, 0.88f, 0.12f),
                    WirePoint3D(2.85f, 0.42f, 0.18f),
                    WirePoint3D(2.95f, 0.02f, 0.20f),
                    WirePoint3D(2.85f, -0.44f, 0.18f),
                    WirePoint3D(2.45f, -0.90f, 0.12f),
                    WirePoint3D(1.75f, -0.80f, -0.04f),
                    WirePoint3D(1.44f, -0.42f, -0.10f),
                    WirePoint3D(1.38f, 0.00f, -0.12f),
                    WirePoint3D(1.44f, 0.42f, -0.10f),
                    WirePoint3D(1.75f, 0.80f, -0.04f)
                )
            ),
            GlassesWireframePart(
                name = "bridge_main",
                color = "frame",
                strokeWidth = 0.018f,
                closed = false,
                points = listOf(
                    WirePoint3D(-1.38f, 0.00f, -0.12f),
                    WirePoint3D(-0.86f, 0.06f, -0.10f),
                    WirePoint3D(-0.30f, 0.18f, -0.08f),
                    WirePoint3D(0.30f, 0.18f, -0.08f),
                    WirePoint3D(0.86f, 0.06f, -0.10f),
                    WirePoint3D(1.38f, 0.00f, -0.12f)
                )
            ),
            GlassesWireframePart(
                name = "bridge_lower",
                color = "frame",
                strokeWidth = 0.010f,
                closed = false,
                points = listOf(
                    WirePoint3D(-0.28f, -0.12f, -0.08f),
                    WirePoint3D(0.28f, -0.12f, -0.08f)
                )
            ),
            GlassesWireframePart(
                name = "temple_left",
                color = "accent",
                strokeWidth = 0.012f,
                closed = false,
                points = listOf(
                    WirePoint3D(-2.85f, 0.42f, 0.18f),
                    WirePoint3D(-3.10f, 0.42f, 0.05f),
                    WirePoint3D(-3.45f, 0.34f, -0.10f)
                )
            ),
            GlassesWireframePart(
                name = "temple_left_lower",
                color = "accent",
                strokeWidth = 0.010f,
                closed = false,
                points = listOf(
                    WirePoint3D(-2.85f, -0.44f, 0.18f),
                    WirePoint3D(-3.00f, -0.28f, 0.02f),
                    WirePoint3D(-3.20f, -0.16f, -0.18f)
                )
            ),
            GlassesWireframePart(
                name = "temple_right",
                color = "accent",
                strokeWidth = 0.012f,
                closed = false,
                points = listOf(
                    WirePoint3D(2.85f, 0.42f, 0.18f),
                    WirePoint3D(3.10f, 0.42f, 0.05f),
                    WirePoint3D(3.45f, 0.34f, -0.10f)
                )
            ),
            GlassesWireframePart(
                name = "temple_right_lower",
                color = "accent",
                strokeWidth = 0.010f,
                closed = false,
                points = listOf(
                    WirePoint3D(2.85f, -0.44f, 0.18f),
                    WirePoint3D(3.00f, -0.28f, 0.02f),
                    WirePoint3D(3.20f, -0.16f, -0.18f)
                )
            ),
            GlassesWireframePart(
                name = "lens_left",
                color = "lens",
                strokeWidth = 0.008f,
                closed = false,
                points = listOf(
                    WirePoint3D(-1.78f, 0.42f, 0.04f),
                    WirePoint3D(-1.78f, -0.42f, 0.04f)
                )
            ),
            GlassesWireframePart(
                name = "lens_right",
                color = "lens",
                strokeWidth = 0.008f,
                closed = false,
                points = listOf(
                    WirePoint3D(1.78f, 0.42f, 0.04f),
                    WirePoint3D(1.78f, -0.42f, 0.04f)
                )
            )
        )
    )
}

@Composable
private fun CursorOverlay(cursor: RemoteCursorState?, viewSize: IntSize, localX: Float, localY: Float) {
    val cursorBitmap = cursor?.bitmap
    if (cursorBitmap == null || viewSize.width <= 0 || viewSize.height <= 0) {
        return
    }
    // Host visibility flag logic
    if (!cursor.visible) {
        // We could hide the local cursor here, but keeping it visible makes it easier 
        // for the user to find their place after the host hides it.
    }
    val density = LocalDensity.current

    val sourceWidth = maxOf(1, cursor.sourceWidth)
    val sourceHeight = maxOf(1, cursor.sourceHeight)
    val scale = minOf(viewSize.width.toFloat() / sourceWidth, viewSize.height.toFloat() / sourceHeight)
    val drawnWidth = sourceWidth * scale
    val drawnHeight = sourceHeight * scale
    val offsetX = (viewSize.width - drawnWidth) / 2f
    val offsetY = (viewSize.height - drawnHeight) / 2f
    val cursorLeft = offsetX + (localX - cursor.hotX) * scale
    val cursorTop = offsetY + (localY - cursor.hotY) * scale
    val cursorWidth = (cursorBitmap.width * scale).coerceAtLeast(1f)
    val cursorHeight = (cursorBitmap.height * scale).coerceAtLeast(1f)
    val leftPx = kotlin.math.floor(cursorLeft).toInt()
    val topPx = kotlin.math.floor(cursorTop).toInt()
    val rightPx = kotlin.math.ceil(cursorLeft + cursorWidth).toInt()
    val bottomPx = kotlin.math.ceil(cursorTop + cursorHeight).toInt()
    val drawWidthPx = (rightPx - leftPx).coerceAtLeast(1)
    val drawHeightPx = (bottomPx - topPx).coerceAtLeast(1)

    val scaledCursorBitmap = remember(cursorBitmap, drawWidthPx, drawHeightPx) {
        cursorBitmap.setHasMipMap(true)
        Bitmap.createScaledBitmap(cursorBitmap, drawWidthPx, drawHeightPx, true)
    }

    Image(
        painter = BitmapPainter(
            image = scaledCursorBitmap.asImageBitmap(),
            filterQuality = FilterQuality.None
        ),
        contentDescription = "Remote cursor",
        modifier = Modifier
            .offset { IntOffset(leftPx, topPx) }
            .size(
                width = with(density) { drawWidthPx.toDp() },
                height = with(density) { drawHeightPx.toDp() }
            )
    )
}

data class H264StreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val encoder: String,
    val captureMethod: String,
    val sps: ByteArray,
    val pps: ByteArray,
    val avcc: ByteArray? = null
)

class H264StreamController(
    private val scope: kotlinx.coroutines.CoroutineScope
) : SurfaceHolder.Callback {
    private data class TimestampedFrame(val data: ByteArray, val captureMs: Long?)
    private val frameQueue = Channel<TimestampedFrame>(Channel.UNLIMITED)
    private val pendingFrames = ArrayDeque<TimestampedFrame>()
    private val inflightCaptureTimes = ArrayDeque<Long?>()
    private val codecLock = Any()
    private val outputLock = Any()
    private val readyOutputs = ArrayDeque<ReadyOutput>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingConfig: H264StreamConfig? = null
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var drainJob: Job? = null
    private var inputJob: Job? = null
    private var nextPtsUs = 0L
    private var frameDurationUs = 16_666L
    @Volatile
    private var frameCallbackPosted = false
    @Volatile
    private var waitingForKeyframe = true
    private var renderCount = 0
    private var renderStatsLastMs = 0L
    private var renderGapSumMs = 0.0
    private var renderGapMaxMs = 0.0
    private var renderGapMinMs = Double.POSITIVE_INFINITY
    private var renderGapCount = 0
    private var lastRenderMs = 0L
    var clockOffsetMs: () -> Long = { 0L }
    var onLatencySample: ((Double) -> Unit)? = null

    fun configure(config: H264StreamConfig) {
        debugLog(H264_TAG, "configure: ${config.width}x${config.height} fps=${config.fps} encoder=${config.encoder}")
        synchronized(codecLock) {
            pendingConfig = config
            waitingForKeyframe = true
        }
        frameDurationUs = (1_000_000L / maxOf(1, config.fps)).coerceAtLeast(1L)
        if (hasCodec()) {
            debugLog(H264_TAG, "Restarting decoder for new stream config")
            stopCodec()
        }
        maybeStartCodec()
    }

    fun queueFrame(frame: ByteArray, captureMs: Long? = null) {
        if (waitingForKeyframe && !containsIdr(frame)) {
            return
        }
        if (waitingForKeyframe) {
            waitingForKeyframe = false
            debugLog(H264_TAG, "Keyframe received, starting decode queue")
        }
        frameQueue.trySend(TimestampedFrame(frame, captureMs))
    }

    fun bindSurface(holder: SurfaceHolder) {
        synchronized(codecLock) {
            surface = holder.surface
        }
        debugLog(H264_TAG, "bindSurface: surface=${holder.surface != null} valid=${holder.surface?.isValid == true}")
        maybeStartCodec()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        debugLog(H264_TAG, "surfaceCreated")
        bindSurface(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        debugLog(H264_TAG, "surfaceChanged: format=$format size=${width}x$height")
        bindSurface(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        debugLog(H264_TAG, "surfaceDestroyed")
        synchronized(codecLock) {
            surface = null
        }
        stopCodec()
    }

    fun release() {
        debugLog(H264_TAG, "release")
        drainJob?.cancel()
        inputJob?.cancel()
        drainJob = null
        inputJob = null
        frameQueue.close()
        stopCodec()
    }

    fun resetStream() {
        debugLog(H264_TAG, "resetStream")
        synchronized(codecLock) {
            pendingConfig = null
            waitingForKeyframe = true
            inflightCaptureTimes.clear()
        }
        stopCodec()
    }

    private fun maybeStartCodec() {
        val config = synchronized(codecLock) { pendingConfig } ?: return
        val targetSurface = synchronized(codecLock) { surface } ?: return
        if (hasCodec()) {
            return
        }

        try {
            debugLog(H264_TAG, "Starting decoder on surface=${targetSurface.isValid}")
            val format = MediaFormat.createVideoFormat("video/avc", config.width, config.height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(config.sps)))
                setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(config.pps)))
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, config.width * config.height * 4)
                setInteger("priority", 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                } else {
                    setInteger("low-latency", 1)
                }
            }

            val decoder = MediaCodec.createDecoderByType("video/avc")
            decoder.configure(format, targetSurface, null, 0)
            decoder.start()
            decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            synchronized(codecLock) {
                codec = decoder
                nextPtsUs = 0L
                resetRenderStats()
            }
            clearReadyOutputs(null)
            debugLog(H264_TAG, "Decoder started")

            drainJob?.cancel()
            drainJob = scope.launch(Dispatchers.Default) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                while (isActive && isCurrentCodec(decoder)) {
                    if (!drainOutput(decoder)) {
                        kotlinx.coroutines.delay(1)
                    }
                }
            }

            inputJob?.cancel()
            inputJob = scope.launch(Dispatchers.Default) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                for (item in frameQueue) {
                    if (!isActive || !isCurrentCodec(decoder)) break
                    synchronized(codecLock) {
                        pendingFrames.addLast(item)
                    }
                    feedQueuedFrames(decoder)
                }
            }
        } catch (e: Exception) {
            debugError(H264_TAG, "Failed to start decoder", e)
            stopCodec()
        }
    }

    private fun feedQueuedFrames(decoder: MediaCodec) {
        while (isCurrentCodec(decoder)) {
            val frame = synchronized(codecLock) {
                if (pendingFrames.isEmpty()) {
                    null
                } else {
                    pendingFrames.removeFirst()
                }
            } ?: return
            val inputIndex = decoder.dequeueInputBuffer(5_000)
            if (inputIndex < 0) {
                synchronized(codecLock) {
                    pendingFrames.addFirst(frame)
                }
                return
            }

            val inputBuffer = decoder.getInputBuffer(inputIndex)
            if (inputBuffer == null) {
                synchronized(codecLock) {
                    pendingFrames.addFirst(frame)
                }
                return
            }

            inputBuffer.clear()
            inputBuffer.put(frame.data)
            val ptsUs = synchronized(codecLock) {
                val pts = nextPtsUs
                nextPtsUs += frameDurationUs
                pts
            }
            synchronized(codecLock) {
                inflightCaptureTimes.addLast(frame.captureMs)
            }
            decoder.queueInputBuffer(inputIndex, 0, frame.data.size, ptsUs, 0)
        }
    }

    private fun enqueueReadyOutput(bufferIndex: Int) {
        val captureMs = synchronized(codecLock) {
            if (inflightCaptureTimes.isEmpty()) {
                null
            } else {
                inflightCaptureTimes.removeFirst()
            }
        }
        synchronized(outputLock) {
            readyOutputs.addLast(ReadyOutput(bufferIndex, captureMs))
        }
        requestVsyncRelease()
    }

    private fun requestVsyncRelease() {
        if (frameCallbackPosted) {
            return
        }
        frameCallbackPosted = true
        mainHandler.post {
            val decoder = synchronized(codecLock) { codec }
            if (decoder == null) {
                frameCallbackPosted = false
                return@post
            }
            Choreographer.getInstance().postFrameCallback(vsyncFrameCallback)
        }
    }

    private val vsyncFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameCallbackPosted = false
        val decoder = synchronized(codecLock) { codec } ?: return@FrameCallback
        if (!isCurrentCodec(decoder)) {
            return@FrameCallback
        }

        val outputs = synchronized(outputLock) {
            val backlog = readyOutputs.size
            val releaseCount = if (backlog > MAX_READY_FRAMES) 2 else 1
            buildList {
                repeat(releaseCount.coerceAtMost(readyOutputs.size)) {
                    readyOutputs.pollFirst()?.let { add(it) }
                }
            }
        }

        outputs.forEachIndexed { index, ready ->
            noteRender(ready.captureMs)
            val presentTimeNs = if (index == 0 && outputs.size == 1) {
                frameTimeNanos
            } else {
                System.nanoTime()
            }
            decoder.releaseOutputBuffer(ready.bufferIndex, presentTimeNs)
        }

        synchronized(outputLock) {
            if (readyOutputs.isNotEmpty()) {
                requestVsyncRelease()
            }
        }
    }

    private fun clearReadyOutputs(decoder: MediaCodec?) {
        frameCallbackPosted = false
        val pending = synchronized(outputLock) {
            val copy = readyOutputs.toList()
            readyOutputs.clear()
            copy
        }
        pending.forEach { ready ->
            try {
                decoder?.releaseOutputBuffer(ready.bufferIndex, false)
            } catch (_: Exception) {
            }
        }
    }

    private fun drainOutput(decoder: MediaCodec): Boolean {
        val info = MediaCodec.BufferInfo()
        var rendered = false
        while (isCurrentCodec(decoder)) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return rendered
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    debugLog(H264_TAG, "Output format changed: ${decoder.outputFormat}")
                    continue
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    debugLog(H264_TAG, "Output buffers changed")
                    continue
                }
                else -> {
                    if (outputIndex >= 0) {
                        val shouldRender = info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        if (shouldRender) {
                            enqueueReadyOutput(outputIndex)
                            rendered = true
                        } else {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        }
        return rendered
    }

    private fun noteRender(captureMs: Long?) {
        captureMs?.let {
            val sample = (System.currentTimeMillis() + clockOffsetMs() - it).toDouble().coerceAtLeast(0.0)
            onLatencySample?.invoke(sample)
        }
        if (!ENABLE_DEBUG_LOGS) {
            return
        }
        val nowMs = System.currentTimeMillis()
        if (lastRenderMs > 0L) {
            val gapMs = (nowMs - lastRenderMs).toDouble()
            renderGapSumMs += gapMs
            renderGapCount += 1
            renderGapMaxMs = maxOf(renderGapMaxMs, gapMs)
            renderGapMinMs = minOf(renderGapMinMs, gapMs)
        }
        lastRenderMs = nowMs
        renderCount += 1
        if (nowMs - renderStatsLastMs >= 1000) {
            val avgGap = if (renderGapCount > 0) renderGapSumMs / renderGapCount else 0.0
            debugLog(
                H264_TAG,
                "render pacing: fps=$renderCount avgGapMs=${"%.1f".format(Locale.US, avgGap)} " +
                    "minGapMs=${if (renderGapMinMs.isInfinite()) "n/a" else "%.1f".format(Locale.US, renderGapMinMs)} " +
                    "maxGapMs=${"%.1f".format(Locale.US, renderGapMaxMs)} " +
                    "inputDepth=${pendingFrames.size} readyDepth=${readyOutputs.size}"
            )
            renderCount = 0
            renderStatsLastMs = nowMs
            renderGapSumMs = 0.0
            renderGapMaxMs = 0.0
            renderGapMinMs = Double.POSITIVE_INFINITY
            renderGapCount = 0
        }
    }

    private fun resetRenderStats() {
        renderCount = 0
        renderStatsLastMs = System.currentTimeMillis()
        renderGapSumMs = 0.0
        renderGapMaxMs = 0.0
        renderGapMinMs = Double.POSITIVE_INFINITY
        renderGapCount = 0
        lastRenderMs = 0L
    }

    private fun stopCodec() {
        val decoder = synchronized(codecLock) {
            val current = codec
            codec = null
            pendingFrames.clear()
            inflightCaptureTimes.clear()
            nextPtsUs = 0L
            waitingForKeyframe = true
            resetRenderStats()
            current
        }
        clearReadyOutputs(decoder)
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        try {
            decoder?.release()
        } catch (_: Exception) {
        }
        debugLog(H264_TAG, "Decoder stopped")
    }

    private fun hasCodec(): Boolean = synchronized(codecLock) { codec != null }

    private fun isCurrentCodec(decoder: MediaCodec): Boolean = synchronized(codecLock) { codec === decoder }

    private fun withStartCode(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data
        }
        return byteArrayOf(0, 0, 0, 1) + data
    }

    private fun containsIdr(data: ByteArray): Boolean {
        var i = 0
        while (i + 4 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val start = when {
                    data[i + 2] == 1.toByte() -> i + 3
                    i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> i + 4
                    else -> -1
                }
                if (start > 0 && start < data.size) {
                    val nalType = data[start].toInt() and 0x1f
                    if (nalType == 5) {
                        return true
                    }
                }
            }
            i++
        }
        return false
    }

    private data class ReadyOutput(val bufferIndex: Int, val captureMs: Long? = null)

    companion object {
        private const val MAX_READY_FRAMES = 2
    }
}

data class RemoteAudioConfig(
    val sampleRate: Int,
    val channels: Int,
    val frameDurationMs: Int,
    val sampleFormat: String
)

class PcmAudioController(
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    private val packetQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val pendingPackets = ArrayDeque<ByteArray>()
    private val codecLock = Any()
    private var pendingConfig: RemoteAudioConfig? = null
    private var audioTrack: AudioTrack? = null
    private var inputJob: Job? = null
    private var outputJob: Job? = null

    fun configure(config: RemoteAudioConfig) {
        debugLog("RemoteAudio", "configure: ${config.sampleRate}Hz ${config.channels}ch format=${config.sampleFormat}")
        synchronized(codecLock) {
            pendingConfig = config
        }
        if (hasTrack()) {
            stopTrack()
        }
        maybeStartTrack()
    }

    fun queuePacket(packet: ByteArray) {
        if (packet.isEmpty()) {
            return
        }
        packetQueue.trySend(packet)
    }

    fun resetStream() {
        debugLog("RemoteAudio", "resetStream")
        synchronized(codecLock) {
            pendingConfig = null
        }
        stopTrack()
    }

    fun release() {
        debugLog("RemoteAudio", "release")
        inputJob?.cancel()
        outputJob?.cancel()
        inputJob = null
        outputJob = null
        packetQueue.close()
        stopTrack()
    }

    private fun maybeStartTrack() {
        val config = synchronized(codecLock) { pendingConfig } ?: return
        if (hasTrack()) {
            return
        }

        try {
            val channelMask = when (config.channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                else -> AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBufferSize = AudioTrack.getMinBufferSize(
                config.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, config.sampleRate * maxOf(1, config.channels) * 2 / 5, 4096)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()

            synchronized(codecLock) {
                audioTrack = track
            }
            track.play()
            debugLog("RemoteAudio", "AudioTrack started")

            inputJob?.cancel()
            inputJob = scope.launch(Dispatchers.Default) {
                for (packet in packetQueue) {
                    if (!isActive || !hasTrack()) break
                    synchronized(codecLock) {
                        pendingPackets.addLast(packet)
                    }
                    drainPackets()
                }
            }

            outputJob?.cancel()
            outputJob = scope.launch(Dispatchers.Default) {
                while (isActive && hasTrack()) {
                    drainPackets()
                    kotlinx.coroutines.delay(4)
                }
            }
        } catch (e: Exception) {
            debugError("RemoteAudio", "Failed to start audio output", e)
            stopTrack()
        }
    }

    private fun drainPackets() {
        val track = synchronized(codecLock) { audioTrack } ?: return
        while (true) {
            val packet = synchronized(codecLock) {
                if (pendingPackets.isEmpty()) null else pendingPackets.removeFirst()
            } ?: return
            try {
                var offset = 0
                while (offset < packet.size && hasTrack()) {
                    val written = track.write(packet, offset, packet.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) {
                        break
                    }
                    offset += written
                }
            } catch (e: Exception) {
                debugError("RemoteAudio", "Audio write failed", e)
                stopTrack()
                return
            }
        }
    }

    private fun stopTrack() {
        val track = synchronized(codecLock) {
            val currentTrack = audioTrack
            audioTrack = null
            pendingPackets.clear()
            currentTrack
        }
        try {
            track?.pause()
        } catch (_: Exception) {
        }
        try {
            track?.flush()
        } catch (_: Exception) {
        }
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        debugLog("RemoteAudio", "AudioTrack stopped")
    }

    private fun hasTrack(): Boolean = synchronized(codecLock) { audioTrack != null }
}
