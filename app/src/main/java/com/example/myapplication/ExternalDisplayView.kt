package com.example.myapplication

import android.app.Presentation
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import com.example.myapplication.discovery.DiscoveredMachine
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
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

private fun debugLog(tag: String, message: String) {
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
    var onStatsUpdated: ((Int, Double, String?) -> Unit)? = null
    var onCaptureMethodUpdated: ((String) -> Unit)? = null
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
    onStatsUpdated: ((Int, Double, String?) -> Unit)? = null,
    onCaptureMethodUpdated: ((String) -> Unit)? = null,
    onClientReady: ((String) -> Unit) -> Unit = {},
    onRemoteCursorReceived: (Float, Float, Int, Int) -> Unit = { _, _, _, _ -> },
    localCursorX: Float = 0f,
    localCursorY: Float = 0f
) {
    when (state) {
        ExternalDisplayState.IDLE -> IdleScreen()
        ExternalDisplayState.REMOTE_SCREEN -> key(machine?.host, monitorIndex) {
            RemoteScreenView(
                machine, 
                monitorIndex, 
                is3DofEnabled,
                onStatsUpdated, 
                onCaptureMethodUpdated, 
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
    onStatsUpdated: ((Int, Double, String?) -> Unit)? = null,
    onCaptureMethodUpdated: ((String) -> Unit)? = null,
    onClientReady: ((String) -> Unit) -> Unit = {},
    onRemoteCursorReceived: (Float, Float, Int, Int) -> Unit = { _, _, _, _ -> },
    localCursorX: Float = 0f,
    localCursorY: Float = 0f
) {
    val scope = rememberCoroutineScope()
    var client by remember(machine, monitorIndex) { mutableStateOf<WebSocketClient?>(null) }
    
    LaunchedEffect(client) {
        onClientReady { msg ->
            client?.let {
                if (it.isOpen) {
                    it.send(msg)
                }
            }
        }
    }
    var videoConfig by remember(machine, monitorIndex) { mutableStateOf<H264StreamConfig?>(null) }
    var captureMethod by remember(machine, monitorIndex) { mutableStateOf<String?>(null) }
    var cursor by remember(machine, monitorIndex) { mutableStateOf<RemoteCursorState?>(null) }
    var viewSize by remember(machine, monitorIndex) { mutableStateOf(IntSize.Zero) }
    val decoderController = remember(machine, monitorIndex) { H264StreamController(scope) }

    DisposableEffect(machine, monitorIndex) {
        if (machine == null) {
            onDispose {}
        } else {
            debugLog(REMOTE_TAG, "Connecting to ws://${machine.host}:${machine.wsPort}")
            val frameChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)

            var frameCount = 0
            var byteCount = 0L
            var lastFpsTime = System.currentTimeMillis()

            val decodeJob = scope.launch(Dispatchers.Default) {
                for (data in frameChannel) {
                    if (!isActive) break
                    decoderController.queueFrame(data)
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsTime >= 1000) {
                        val elapsedSeconds = (now - lastFpsTime) / 1000.0
                        val fps = frameCount
                        val megabytesPerSecond = (byteCount / (1024.0 * 1024.0)) / elapsedSeconds
                        withContext(Dispatchers.Main) {
                            onStatsUpdated?.invoke(fps, megabytesPerSecond, captureMethod)
                        }
                        debugLog(REMOTE_TAG, "Stream stats: fps=$fps MBps=$megabytesPerSecond queuedBytes=$byteCount")
                        frameCount = 0
                        byteCount = 0
                        lastFpsTime = now
                    }
                }
            }

            val uri = URI("ws://${machine.host}:${machine.wsPort}")
            val wsClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    debugLog(REMOTE_TAG, "Connected")
                    send(JSONObject().apply {
                        put("type", "set_monitor")
                        put("value", monitorIndex)
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
                                scope.launch(Dispatchers.Main) {
                                    videoConfig = null
                                    captureMethod = null
                                    cursor = null
                                    decoderController.resetStream()
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
                        byteCount += size
                        val data = ByteArray(size)
                        it.get(data)
                        if (frameCount == 0) {
                            debugLog(REMOTE_TAG, "First video payload received: $size bytes")
                        }
                        frameChannel.trySend(data)
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
                decodeJob.cancel()
                frameChannel.close()
                decoderController.release()
                client = null
            }
        }
    }

    key(machine?.host, monitorIndex) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (videoConfig == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = machine?.name?.uppercase() ?: "REMOTE PC",
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewSize = it }
                ) {
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                debugLog(REMOTE_TAG, "SurfaceView created")
                                holder.addCallback(decoderController)
                                decoderController.bindSurface(holder)
                            }
                        },
                        update = { view ->
                            debugLog(REMOTE_TAG, "SurfaceView updated")
                            decoderController.bindSurface(view.holder)
                        },
                        modifier = Modifier.fillMaxSize()
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

    Image(
        bitmap = cursorBitmap.asImageBitmap(),
        contentDescription = "Remote cursor",
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .offset { IntOffset(cursorLeft.roundToInt(), cursorTop.roundToInt()) }
            .size(
                width = with(density) { cursorWidth.toDp() },
                height = with(density) { cursorHeight.toDp() }
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
    private val frameQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val pendingFrames = ArrayDeque<ByteArray>()
    private val codecLock = Any()
    private var pendingConfig: H264StreamConfig? = null
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var drainJob: Job? = null
    private var inputJob: Job? = null
    private var nextPtsUs = 0L
    private var frameDurationUs = 16_666L
    @Volatile
    private var waitingForKeyframe = true

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

    fun queueFrame(frame: ByteArray) {
        if (waitingForKeyframe && !containsIdr(frame)) {
            return
        }
        if (waitingForKeyframe) {
            waitingForKeyframe = false
            debugLog(H264_TAG, "Keyframe received, starting decode queue")
        }
        val firstQueued = synchronized(codecLock) { pendingFrames.isEmpty() }
        if (firstQueued) {
            debugLog(H264_TAG, "queueFrame: first queued packet=${frame.size} bytes")
        }
        frameQueue.trySend(frame)
    }

    fun bindSurface(holder: SurfaceHolder) {
        synchronized(codecLock) {
            surface = holder.surface
        }
        val surface = holder.surface
        debugLog(H264_TAG, "bindSurface: surface=${surface != null} valid=${surface?.isValid == true}")
        maybeStartCodec()
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
        }
        stopCodec()
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
            }

            val decoder = MediaCodec.createDecoderByType("video/avc")
            decoder.configure(format, targetSurface, null, 0)
            decoder.start()
            synchronized(codecLock) {
                codec = decoder
                nextPtsUs = 0L
            }
            debugLog(H264_TAG, "Decoder started")

            drainJob?.cancel()
            drainJob = scope.launch(Dispatchers.Default) {
                while (isActive && isCurrentCodec(decoder)) {
                    drainOutput(decoder)
                    kotlinx.coroutines.delay(4)
                }
            }

            inputJob?.cancel()
            inputJob = scope.launch(Dispatchers.Default) {
                for (frame in frameQueue) {
                    if (!isActive || !isCurrentCodec(decoder)) break
                    synchronized(codecLock) {
                        pendingFrames.addLast(frame)
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
                debugLog(H264_TAG, "dequeueInputBuffer returned $inputIndex, requeue frame=${frame.size}")
                synchronized(codecLock) {
                    pendingFrames.addFirst(frame)
                }
                return
            }

            val inputBuffer = decoder.getInputBuffer(inputIndex)
            if (inputBuffer == null) {
                debugLog(H264_TAG, "inputBuffer null, requeue frame=${frame.size}")
                synchronized(codecLock) {
                    pendingFrames.addFirst(frame)
                }
                return
            }

            inputBuffer.clear()
            inputBuffer.put(frame)
            decoder.queueInputBuffer(inputIndex, 0, frame.size, nextPtsUs, 0)
            debugLog(H264_TAG, "queued frame=${frame.size} ptsUs=$nextPtsUs")
            nextPtsUs += frameDurationUs
        }
    }

    private fun drainOutput(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (isCurrentCodec(decoder)) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
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
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            debugLog(H264_TAG, "Dropping codec config output buffer")
                        } else if (info.size > 0) {
                            debugLog(H264_TAG, "Rendered frame size=${info.size} ptsUs=${info.presentationTimeUs} flags=${info.flags}")
                        }
                        decoder.releaseOutputBuffer(outputIndex, true)
                    }
                }
            }
        }
    }

    private fun stopCodec() {
        val decoder = synchronized(codecLock) {
            val current = codec
            codec = null
            pendingFrames.clear()
            nextPtsUs = 0L
            waitingForKeyframe = true
            current
        }
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

}
