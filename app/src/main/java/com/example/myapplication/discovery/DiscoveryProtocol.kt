package com.example.myapplication.discovery

object DiscoveryProtocol {
    const val PORT = 45678
    const val DISCOVER_MESSAGE = "RDESK_DISCOVER"
    const val SERVICE_TYPE = "rdesk"
    const val BROADCAST_INTERVAL_MS = 2_000L
    const val STALE_TIMEOUT_MS = 10_000L
}
