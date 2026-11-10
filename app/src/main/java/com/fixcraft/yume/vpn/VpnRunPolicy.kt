package com.fixcraft.yume.vpn

import com.fixcraft.yume.core.YumeStatusCode

/**
 * The decisions a VPN run makes, without Android or the library. The service
 * supplies the facts and acts on the answers. These functions only decide.
 * Network handles are generic so tests need no `android.net.Network`.
 */
internal object VpnRunPolicy {
    /** What the service knows about one network under the VPN. */
    data class NetworkFacts(val validated: Boolean, val unmetered: Boolean)

    /**
     * The network Android is expected to prefer: a validated one first, and
     * among those an unmetered one, which is how Wi-Fi wins over cellular.
     * Between equals the network known first stays chosen.
     */
    fun <N> choose(known: Map<N, NetworkFacts>): N? =
        known.entries.maxWithOrNull(
            compareBy<Map.Entry<N, NetworkFacts>> { it.value.validated }.thenBy { it.value.unmetered },
        )?.key

    /** Addresses in the order to try them: the family the network has first. */
    fun <A> order(addresses: List<A>, networkHasIpv4: Boolean, isIpv4: (A) -> Boolean): List<A> {
        val (ipv4, ipv6) = addresses.partition(isIpv4)
        return if (networkHasIpv4) ipv4 + ipv6 else ipv6 + ipv4
    }

    /**
     * Whether a session that moved to a metered network should move back:
     * only while it is active, and only to a different, unmetered network.
     * Android prefers that network, so this happens at most once per return.
     */
    fun <N> moveToUnmetered(
        active: Boolean,
        sessionNetwork: N?,
        sessionNetworkUnmetered: Boolean,
        preferred: N?,
        preferredUnmetered: Boolean,
    ): Boolean = active && sessionNetwork != null && !sessionNetworkUnmetered &&
        preferred != null && preferred != sessionNetwork && preferredUnmetered

    /**
     * Whether to look the server's name up again while the session is down:
     * only for a name this run resolved itself, once per outage, after a
     * network change or repeated failures.
     */
    fun recheckAddress(
        active: Boolean,
        resolvedHere: Boolean,
        alreadyChecked: Boolean,
        networkChanged: Boolean,
        failedAttempts: Long,
        afterFailures: Int,
    ): Boolean = !active && resolvedHere && !alreadyChecked &&
        (networkChanged || failedAttempts >= afterFailures)

    /** How a refused start is shown. */
    fun failureKind(status: Int): VpnFailure = when (status) {
        YumeStatusCode.PERMISSION_DENIED -> VpnFailure.Refused
        YumeStatusCode.INVALID_ARGUMENT,
        YumeStatusCode.UNSUPPORTED,
        YumeStatusCode.PARSE_ERROR,
        YumeStatusCode.INCOMPATIBLE,
        -> VpnFailure.Configuration

        else -> VpnFailure.Unreachable
    }

    /**
     * How an endpoint that stopped by itself after starting is shown. An
     * incompatible stop is a server refusing the kit's limits, which
     * retrying does not change. Any other stop is the device or library.
     */
    fun stoppedKind(lastFailure: Int): VpnFailure =
        if (lastFailure == YumeStatusCode.INCOMPATIBLE) VpnFailure.Configuration else VpnFailure.Device

    /**
     * Whether a failed start ends the run. A refusal or a bad configuration
     * does not improve by retrying. Otherwise a run keeps trying when Android
     * started it (nobody is watching) or once it had connected.
     */
    fun startFailureEndsRun(kind: VpnFailure, keepTrying: Boolean): Boolean =
        kind == VpnFailure.Refused || kind == VpnFailure.Configuration || !keepTrying

    /** The next wait between attempts: doubled, up to [longestMs]. */
    fun nextRetryMs(waitMs: Long, longestMs: Long): Long = (waitMs * 2).coerceAtMost(longestMs)

    /**
     * Whether a run that ended because the data is locked should keep its kit
     * and ask for the password: only a restart Android made, and only while
     * the data is still locked.
     */
    fun lockedRestart(patient: Boolean, kind: VpnFailure, unlocked: Boolean): Boolean =
        patient && kind == VpnFailure.KitUnavailable && !unlocked

    /**
     * The resolvers the interface gets. IPv6 resolvers only when the tunnel
     * carries IPv6, since Android blocks IPv6 otherwise, and the defaults when
     * nothing usable is left.
     */
    fun usableDnsServers(configured: List<String>, carryIpv6: Boolean, defaults: List<String>): List<String> =
        configured.filter { carryIpv6 || !it.contains(':') }.ifEmpty { defaults }
}
