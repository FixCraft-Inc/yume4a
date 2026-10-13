package com.fixcraft.yume.vpn

import com.fixcraft.yume.core.YumeStatus

enum class VpnPhase {
    Idle,
    PermissionRequired,
    /** The first session of this run is being established. */
    Connecting,
    Connected,
    /** A session was lost. The library is replacing it, and traffic waits. */
    Reconnecting,
    Disconnecting,
    Error,
}

/** Why a run ended or could not start. The screen says it in the user's language. */
enum class VpnFailure {
    None,
    /** Android did not let this application run a VPN. */
    VpnPermission,
    /** The kit is gone, or the application's data is locked. */
    KitUnavailable,
    /** The server's name did not resolve on the network under the VPN. */
    Resolve,
    /** The server could not be reached, or stopped answering. */
    Unreachable,
    /** The server refused this kit's keys. */
    Refused,
    /** The kit's configuration is not one this library runs. */
    Configuration,
    /** Android's VPN interface could not be created or failed. */
    Device,
}

enum class UpstreamKind { Wifi, Cellular, Ethernet, Other }

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

/**
 * What the VPN service is doing. Only facts are kept here: the screens turn
 * them into words, and nothing below claims more than the library reported.
 */
data class VpnConnectionState(
    val phase: VpnPhase = VpnPhase.Idle,
    /** Identifies this service run for local benchmark attribution. */
    val runId: String? = null,
    /** Monotonic endpoint replacement number within [runId]. */
    val endpointId: Long? = null,
    /** Android's network handle used for the current transport attempt. */
    val underlyingNetworkHandle: Long? = null,
    val kitId: String? = null,
    val kitName: String? = null,
    val endpointLabel: String? = null,
    val failure: VpnFailure = VpnFailure.None,
    /** The library's own words for the failure, in English. */
    val failureDetail: String = "",
    val waitingForNetwork: Boolean = false,
    val upstream: UpstreamKind? = null,
    /** The address the transport dials, after resolving the kit's host. */
    val serverAddress: String? = null,
    /** The latest snapshot of the running endpoint. */
    val status: YumeStatus? = null,
    val dnsServers: List<String> = emptyList(),
    val mtu: Int = 0,
    val carriesIpv6: Boolean = false,
    val eventLog: List<VpnLogEntry> = emptyList(),
) {
    val isActive: Boolean
        get() = phase == VpnPhase.Connecting || phase == VpnPhase.Connected ||
            phase == VpnPhase.Reconnecting || phase == VpnPhase.Disconnecting
}
