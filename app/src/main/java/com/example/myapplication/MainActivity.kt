package com.example.myapplication

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.discovery.DiscoveredMachine
import com.example.myapplication.discovery.MachineDiscovery
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var testPatternService: TestPatternService? = null
    private var isBound by mutableStateOf(false)
    private var currentPattern by mutableStateOf(TestPattern.COLOR_BARS)
    private var discoveredMachines by mutableStateOf<List<DiscoveredMachine>>(emptyList())
    private var activeMachine by mutableStateOf<DiscoveredMachine?>(null)

    private lateinit var machineDiscovery: MachineDiscovery

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TestPatternService.LocalBinder
            testPatternService = binder.getService()
            isBound = true
            currentPattern = testPatternService?.currentPattern ?: TestPattern.COLOR_BARS
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            testPatternService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* Handle result if needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

        val intent = Intent(this, TestPatternService::class.java)
        startService(intent)
        bindService(intent, connection, BIND_AUTO_CREATE)

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val machine = activeMachine
                    if (machine == null) {
                        HomeScreen(
                            discoveredMachines = discoveredMachines,
                            selectedPattern = currentPattern,
                            onPatternSelected = { pattern ->
                                currentPattern = pattern
                                testPatternService?.currentPattern = pattern
                            },
                            onMachineSelected = { 
                                activeMachine = it
                                testPatternService?.activeMachine = it
                                testPatternService?.currentPattern = TestPattern.REMOTE_SCREEN
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        ScreenSharingScreen(
                            machine = machine,
                            currentFps = testPatternService?.currentFps ?: 0,
                            currentKbps = testPatternService?.currentKbps ?: 0,
                            onStop = { 
                                activeMachine = null
                                testPatternService?.activeMachine = null
                                testPatternService?.currentPattern = currentPattern
                            },
                            onMonitorSwitch = { index ->
                                testPatternService?.monitorIndex = index
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        machineDiscovery.stop()
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

@Composable
fun HomeScreen(
    discoveredMachines: List<DiscoveredMachine>,
    selectedPattern: TestPattern,
    onPatternSelected: (TestPattern) -> Unit,
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

        Spacer(modifier = Modifier.height(24.dp))
        DebugControls(
            selectedPattern = selectedPattern,
            onPatternSelected = onPatternSelected
        )
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
    onStop: () -> Unit,
    onMonitorSwitch: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var monitorIndex by remember { mutableIntStateOf(1) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Screen Sharing Active",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Connected to: ${machine.name} (${machine.host})",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Stats for Nerds Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "Stats for Nerds", style = MaterialTheme.typography.labelLarge)
                Text(text = "Stream FPS: $currentFps", color = if (currentFps > 100) Color.Green else Color.Unspecified, style = MaterialTheme.typography.bodySmall)
                Text(text = "Bitrate: $currentKbps kbps", color = if (currentKbps > 5000) Color.Yellow else Color.Unspecified, style = MaterialTheme.typography.bodySmall)
                Text(text = "Target: 60Hz (DXGI capture)", style = MaterialTheme.typography.bodySmall)
                Text(text = "Connection: WebSocket (Binary)", style = MaterialTheme.typography.bodySmall)
                Text(text = "Encoder: H.264 GPU low-latency", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Displaying Monitor $monitorIndex",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { 
                monitorIndex = if (monitorIndex == 1) 2 else 1
                onMonitorSwitch(monitorIndex)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Switch Monitor")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Stop Sharing")
        }
    }
}

@Composable
fun DebugControls(
    selectedPattern: TestPattern,
    onPatternSelected: (TestPattern) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "External Display Debug Controls", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        TestPattern.entries.forEach { pattern ->
            Button(
                onClick = { onPatternSelected(pattern) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                enabled = selectedPattern != pattern
            ) {
                Text(text = pattern.name.replace("_", " "))
            }
        }
    }
}
