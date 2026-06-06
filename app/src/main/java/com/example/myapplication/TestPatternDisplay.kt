package com.example.myapplication

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.example.myapplication.discovery.DiscoveredMachine
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntSize
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
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
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

enum class TestPattern {
    COLOR_BARS, GRID, SOLID_RED, SOLID_GREEN, SOLID_BLUE, REMOTE_SCREEN
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

class TestPatternPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore = store

    var pattern by mutableStateOf(TestPattern.COLOR_BARS)
    var activeMachine by mutableStateOf<DiscoveredMachine?>(null)
    var monitorIndex by mutableIntStateOf(1)
    var onStatsUpdated: ((Int, Int) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@TestPatternPresentation)
            setViewTreeSavedStateRegistryOwner(this@TestPatternPresentation)
            setViewTreeViewModelStoreOwner(this@TestPatternPresentation)
            setContent {
                TestPatternScreen(pattern, activeMachine, monitorIndex, onStatsUpdated)
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
fun TestPatternScreen(pattern: TestPattern, machine: DiscoveredMachine? = null, monitorIndex: Int = 1, onStatsUpdated: ((Int, Int) -> Unit)? = null) {
    when (pattern) {
        TestPattern.COLOR_BARS -> ColorBars()
        TestPattern.GRID -> GridPattern()
        TestPattern.SOLID_RED -> Box(Modifier.fillMaxSize().background(Color.Red))
        TestPattern.SOLID_GREEN -> Box(Modifier.fillMaxSize().background(Color.Green))
        TestPattern.SOLID_BLUE -> Box(Modifier.fillMaxSize().background(Color.Blue))
        TestPattern.REMOTE_SCREEN -> RemoteScreenView(machine, monitorIndex, onStatsUpdated)
    }
}

@Composable
fun RemoteScreenView(machine: DiscoveredMachine?, monitorIndex: Int, onStatsUpdated: ((Int, Int) -> Unit)? = null) {
    var bitmap by remember(machine) { mutableStateOf<Bitmap?>(null) }
    var cursor by remember(machine) { mutableStateOf<RemoteCursorState?>(null) }
    val scope = rememberCoroutineScope()
    
    var client by remember(machine) { mutableStateOf<WebSocketClient?>(null) }

    DisposableEffect(machine) {
        if (machine == null) {
            onDispose {}
        } else {
            Log.d("RemoteScreenView", "Connecting to ws://${machine.host}:${machine.wsPort}")
            val frameChannel = Channel<ByteArray>(capacity = Channel.CONFLATED)
            
            var frameCount = 0
            var byteCount = 0L
            var lastFpsTime = System.currentTimeMillis()

            val decodeJob = scope.launch(Dispatchers.Default) {
                for (data in frameChannel) {
                    if (!isActive) break
                    val fetched = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (fetched != null) {
                        withContext(Dispatchers.Main) {
                            bitmap = fetched
                            frameCount++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsTime >= 1000) {
                                val fps = frameCount
                                val kbps = ((byteCount * 8) / 1024).toInt()
                                onStatsUpdated?.invoke(fps, kbps)
                                frameCount = 0
                                byteCount = 0
                                lastFpsTime = now
                            }
                        }
                    }
                }
            }

            val uri = URI("ws://${machine.host}:${machine.wsPort}")
            val wsClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d("RemoteScreenView", "Connected")
                    send(JSONObject().apply {
                        put("type", "set_monitor")
                        put("value", monitorIndex)
                    }.toString())
                }

                override fun onMessage(message: String?) {
                    if (message.isNullOrBlank()) return
                    try {
                        val json = JSONObject(message)
                        if (json.optString("type") == "cursor") {
                            val visible = json.optBoolean("visible", true)
                            val nextCursor = if (visible) {
                                val base64 = json.optString("img")
                                val cursorBitmap = if (!base64.isNullOrEmpty()) {
                                    try {
                                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    } catch (_: Exception) { null }
                                } else {
                                    cursor?.bitmap
                                }

                                RemoteCursorState(
                                    visible = true,
                                    x = json.optDouble("x").toFloat(),
                                    y = json.optDouble("y").toFloat(),
                                    sourceWidth = json.optInt("w", 1),
                                    sourceHeight = json.optInt("h", 1),
                                    bitmap = cursorBitmap,
                                    hotX = if (json.has("hx")) json.optInt("hx") else (cursor?.hotX ?: 0),
                                    hotY = if (json.has("hy")) json.optInt("hy") else (cursor?.hotY ?: 0)
                                )
                            } else {
                                null
                            }
                            scope.launch(Dispatchers.Main) {
                                cursor = nextCursor
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                override fun onMessage(bytes: ByteBuffer?) {
                    bytes?.let {
                        val size = it.remaining()
                        byteCount += size
                        val data = ByteArray(size)
                        it.get(data)
                        frameChannel.trySend(data)
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d("RemoteScreenView", "Closed: $reason")
                }

                override fun onError(ex: Exception?) {
                    Log.e("RemoteScreenView", "Socket Error", ex)
                }
            }
            
            wsClient.connect()
            client = wsClient

            onDispose {
                Log.d("RemoteScreenView", "Disconnecting")
                wsClient.close()
                decodeJob.cancel()
                frameChannel.close()
                client = null
            }
        }
    }

    LaunchedEffect(monitorIndex) {
        client?.let {
            if (it.isOpen) {
                it.send(JSONObject().apply {
                    put("type", "set_monitor")
                    put("value", monitorIndex)
                }.toString())
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = "Remote Screen",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                val currentCursor = cursor
                if (currentCursor != null && currentCursor.visible) {
                    CursorOverlay(
                        bitmapWidth = currentBitmap.width,
                        bitmapHeight = currentBitmap.height,
                        sourceWidth = currentCursor.sourceWidth,
                        sourceHeight = currentCursor.sourceHeight,
                        cursorX = currentCursor.x,
                        cursorY = currentCursor.y,
                        cursorBitmap = currentCursor.bitmap,
                        hotX = currentCursor.hotX,
                        hotY = currentCursor.hotY
                    )
                }
            }
        } else {
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
        }
    }
}

@Composable
private fun CursorOverlay(
    bitmapWidth: Int,
    bitmapHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    cursorX: Float,
    cursorY: Float,
    cursorBitmap: Bitmap?,
    hotX: Int,
    hotY: Int
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (size.width <= 0 || size.height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return@Canvas
        }

        val fitScale = minOf(
            size.width / bitmapWidth.toFloat(),
            size.height / bitmapHeight.toFloat()
        )
        val displayedWidth = bitmapWidth * fitScale
        val displayedHeight = bitmapHeight * fitScale
        val offsetX = (size.width - displayedWidth) / 2f
        val offsetY = (size.height - displayedHeight) / 2f

        val pointerX = offsetX + (cursorX / sourceWidth) * displayedWidth
        val pointerY = offsetY + (cursorY / sourceHeight) * displayedHeight

        if (cursorBitmap != null) {
            // Draw the actual Windows cursor
            val cursorImage = cursorBitmap.asImageBitmap()
            // We need to scale the cursor too, but usually cursors are small and 1:1 is fine
            // or we scale it slightly to match display density if needed.
            // For now, let's draw it centered on the hotspot.
            
            // Adjust for hotspot
            val drawX = pointerX - (hotX * fitScale)
            val drawY = pointerY - (hotY * fitScale)

            drawImage(
                image = cursorImage,
                topLeft = Offset(drawX, drawY)
            )
        } else {
            // Fallback high-visibility crosshair
            val dotRadius = 12f
            drawCircle(
                color = Color.Red,
                radius = dotRadius,
                center = Offset(pointerX, pointerY)
            )
            drawCircle(
                color = Color.Yellow,
                radius = dotRadius + 4f,
                center = Offset(pointerX, pointerY),
                style = Stroke(width = 2f)
            )
        }
    }
}

@Composable
fun ColorBars() {
    val colors = listOf(
        Color.White, Color.Yellow, Color.Cyan, Color.Green,
        Color.Magenta, Color.Red, Color.Blue, Color.Black
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val barWidth = size.width / colors.size
        colors.forEachIndexed { index, color ->
            drawRect(
                color = color,
                topLeft = Offset(index * barWidth, 0f),
                size = size.copy(width = barWidth)
            )
        }
    }
}

@Composable
fun GridPattern() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 50f
        for (x in 0..size.width.toInt() step step.toInt()) {
            drawLine(
                color = Color.Gray,
                start = Offset(x.toFloat(), 0f),
                end = Offset(x.toFloat(), size.height),
                strokeWidth = 1f
            )
        }
        for (y in 0..size.height.toInt() step step.toInt()) {
            drawLine(
                color = Color.Gray,
                start = Offset(0f, y.toFloat()),
                end = Offset(size.width, y.toFloat()),
                strokeWidth = 1f
            )
        }
    }
}
