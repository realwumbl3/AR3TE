package com.ar3te.discovery

object DiscoveryProtocol {
    const val PORT = 45678
    const val DISCOVER_MESSAGE = "AR3TE_DISCOVER"
    const val SERVICE_TYPE = "ar3te"
    const val BROADCAST_INTERVAL_MS = 2_000L
    const val STALE_TIMEOUT_MS = 10_000L
}
