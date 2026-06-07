package com.ar3te.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MachineDiscovery(
    private val scope: CoroutineScope,
    private val onMachinesUpdated: (List<DiscoveredMachine>) -> Unit,
) {
    private var job: Job? = null
    private val machines = linkedMapOf<String, DiscoveredMachine>()

    fun start() {
        if (job?.isActive == true) {
            return
        }

        job = scope.launch(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 500

                val request = DiscoveryProtocol.DISCOVER_MESSAGE.toByteArray()
                val broadcastAddress = InetAddress.getByName("255.255.255.255")

                while (isActive) {
                    try {
                        socket.send(
                            DatagramPacket(
                                request,
                                request.size,
                                broadcastAddress,
                                DiscoveryProtocol.PORT,
                            )
                        )
                    } catch (_: Exception) {
                    }

                    val deadline = System.currentTimeMillis() + DiscoveryProtocol.BROADCAST_INTERVAL_MS
                    while (isActive && System.currentTimeMillis() < deadline) {
                        val buffer = ByteArray(512)
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                            parseResponse(String(packet.data, 0, packet.length))?.let { machine ->
                                machines[machineKey(machine)] = machine
                            }
                        } catch (_: Exception) {
                        }
                    }

                    pruneStaleMachines()
                    publishMachines()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        machines.clear()
        scope.launch(Dispatchers.Main) {
            onMachinesUpdated(emptyList())
        }
    }

    private fun parseResponse(payload: String): DiscoveredMachine? {
        return try {
            val json = JSONObject(payload)
            if (json.optString("type") != DiscoveryProtocol.SERVICE_TYPE) {
                return null
            }

            val name = json.optString("name").trim()
            val host = json.optString("host").trim()
            val port = json.optInt("port", DiscoveryProtocol.PORT)
            val wsPort = json.optInt("wsPort", 45679)
            if (name.isEmpty() || host.isEmpty()) {
                return null
            }

            DiscoveredMachine(
                name = name,
                host = host,
                port = port,
                lastSeenMs = System.currentTimeMillis(),
                wsPort = wsPort,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun pruneStaleMachines() {
        val cutoff = System.currentTimeMillis() - DiscoveryProtocol.STALE_TIMEOUT_MS
        machines.entries.removeIf { (_, machine) -> machine.lastSeenMs < cutoff }
    }

    private suspend fun publishMachines() {
        val snapshot = machines.values
            .sortedBy { it.name.lowercase() }
            .toList()
        withContext(Dispatchers.Main) {
            onMachinesUpdated(snapshot)
        }
    }

    private fun machineKey(machine: DiscoveredMachine): String {
        return "${machine.name}|${machine.host}|${machine.port}"
    }
}
