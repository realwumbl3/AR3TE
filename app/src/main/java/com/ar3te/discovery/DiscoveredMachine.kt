package com.ar3te.discovery

data class DiscoveredMachine(
    val name: String,
    val host: String,
    val port: Int,
    val lastSeenMs: Long,
    val wsPort: Int = 45679,
    val cursorWsPort: Int = 45680,
)
