package com.example.myapplication.discovery

data class DiscoveredMachine(
    val name: String,
    val host: String,
    val port: Int,
    val lastSeenMs: Long,
    val wsPort: Int = 45679,
)
