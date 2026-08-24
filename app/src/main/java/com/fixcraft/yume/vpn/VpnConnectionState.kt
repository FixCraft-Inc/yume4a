package com.fixcraft.yume.vpn

import com.fixcraft.yume.BuildConfig

enum class VpnPhase {
    Idle,
    PermissionRequired,
    Connecting,
    Verifying,
    Connected,
    Disconnecting,
    Error,
}

enum class VpnLogLevel {
    Info,
    Warn,
    Error,
}

data class VpnLogEntry(
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val level: VpnLogLevel,
    val message: String,
)

data class VpnConnectionState(
    val phase: VpnPhase = VpnPhase.Idle,
    val activeProfileId: String? = null,
    val headline: String = "Disconnected",
    val detail: String = "",
    val clientPlatform: String = "android",
    val clientVariant: String = "android_vpn",
    val clientVersion: String = BuildConfig.VERSION_NAME,
    val endpointLabel: String? = null,
    val sessionDnsServers: List<String> = emptyList(),
    val sessionIpv4: String? = null,
    val sessionMtu: Int? = null,
    val upstreamNetworkLabel: String? = null,
    val controlSocketProtected: Boolean = false,
    val vpnInterfaceEstablished: Boolean = false,
    val trafficRoutedThroughYume: Boolean = false,
    val requestedProtection: String = BuildConfig.YUME_PROTECTION_POLICY,
    val transportSummary: String = "TLS 1.3 + HTTP/2 WebSocket carrier",
    val innerSummary: String = "Negotiated at connect",
    val ratchetSummary: String = "Negotiated at connect",
    val veritySummary: String = "Not checked",
    val yumeProtocolVersion: String = BuildConfig.YUME_PROTOCOL_VERSION,
    val basefwxVersion: String = BuildConfig.BASEFWX_ENGINE_VERSION,
    val serverMode: String? = null,
    val serverVersion: String? = null,
    val peerCertificateFingerprint: String? = null,
    val exitIp: String? = null,
    val activeRouteLabel: String? = null,
    val eventLog: List<VpnLogEntry> = emptyList(),
) {
    val briefStatus: String
        get() = when (phase) {
            VpnPhase.Idle -> "Idle"
            VpnPhase.PermissionRequired -> "Permission needed"
            VpnPhase.Connecting -> "Connecting"
            VpnPhase.Verifying -> "Verifying"
            VpnPhase.Connected -> when {
                trafficRoutedThroughYume -> "Protected"
                activeRouteLabel != null -> "Route ready"
                else -> "Connected"
            }
            VpnPhase.Disconnecting -> "Disconnecting"
            VpnPhase.Error -> "Error"
        }
}
