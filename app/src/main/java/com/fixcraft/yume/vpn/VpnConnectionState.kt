package com.fixcraft.yume.vpn

import com.fixcraft.yume.BuildConfig

enum class VpnPhase {
    Idle,
    PermissionRequired,
    Connecting,
    BootstrapReady,
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
    val detail: String = "Add a server, assign an auth key, then grant VPN permission to begin.",
    val clientPlatform: String = "android",
    val clientVariant: String = "android_vpn",
    val clientVersion: String = BuildConfig.VERSION_NAME,
    val endpointLabel: String? = null,
    val sessionDnsServers: List<String> = emptyList(),
    val sessionMtu: Int? = null,
    val upstreamNetworkLabel: String? = null,
    val controlSocketProtected: Boolean = false,
    val vpnInterfaceEstablished: Boolean = false,
    val trafficRoutedThroughYume: Boolean = false,
    val requestedProtection: String = BuildConfig.YUME_PROTECTION_POLICY,
    val transportSummary: String = "TLS 1.3 protected control path",
    val innerSummary: String = "Requested at highest available level once the Android Yume engine is active",
    val hopSummary: String = "Requested at highest available level once the Android Yume engine is active",
    val veritySummary: String = "Local anonym CA selected",
    val routingSummary: String = BuildConfig.YUME_ROUTING_MODE,
    val yumeProtocolVersion: String = BuildConfig.YUME_PROTOCOL_VERSION,
    val basefwxVersion: String = BuildConfig.BASEFWX_ENGINE_VERSION,
    val serverMode: String? = null,
    val serverVersion: String? = null,
    val peerCertificateFingerprint: String? = null,
    val exitIp: String? = null,
    val relaySummary: String = "Relay is idle until Yume starts the authenticated gateway",
    val localProxyAddress: String? = null,
    val latestLifecycleState: String? = null,
    val latestLifecycleMessage: String? = null,
    val latestLifecycleAckMs: Long? = null,
    val eventLog: List<VpnLogEntry> = emptyList(),
) {
    val briefStatus: String
        get() = when (phase) {
            VpnPhase.Idle -> "Idle"
            VpnPhase.PermissionRequired -> "Permission needed"
            VpnPhase.Connecting -> "Connecting"
            VpnPhase.BootstrapReady -> "Bootstrap ready"
            VpnPhase.Connected -> when {
                trafficRoutedThroughYume -> "Protected"
                localProxyAddress != null -> "Proxy ready"
                else -> "Connected"
            }
            VpnPhase.Disconnecting -> "Disconnecting"
            VpnPhase.Error -> "Error"
        }
}
