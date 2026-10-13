package com.fixcraft.yume.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The networks under the VPN: every network with internet access that is not
 * itself a VPN. The transport's socket is protected, so the system routes it
 * over its default of these. This class only tells which one that is likely
 * to be, for resolving the server's name on it and for showing its kind.
 */
internal class UnderlyingNetworks(
    private val manager: ConnectivityManager,
    private val onChanged: () -> Unit,
) {
    private val lock = Any()
    private val known = LinkedHashMap<Network, NetworkCapabilities>()
    private var chosen: Network? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(lock) { known[network] = capabilities }
            reconsider()
        }

        override fun onLost(network: Network) {
            synchronized(lock) { known.remove(network) }
            reconsider()
        }
    }

    fun register() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        registered = runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess
    }

    fun unregister() {
        if (!registered) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        registered = false
        synchronized(lock) {
            known.clear()
            chosen = null
        }
    }

    fun current(): Network? = synchronized(lock) { chosen }

    /** Whether the network is known and unmetered, as Wi-Fi and Ethernet usually are. */
    fun isUnmetered(network: Network): Boolean = synchronized(lock) {
        known[network]?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    fun kind(): UpstreamKind? {
        val capabilities = synchronized(lock) { chosen?.let(known::get) } ?: return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpstreamKind.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpstreamKind.Cellular
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> UpstreamKind.Ethernet
            else -> UpstreamKind.Other
        }
    }

    /** Suspends until a network is there. Cancelling the caller ends the wait. */
    suspend fun await(): Network {
        while (true) {
            current()?.let { return it }
            delay(250)
        }
    }

    /**
     * The server's addresses in the order to try them: the family the
     * network has an address of first.
     */
    fun order(network: Network, addresses: List<InetAddress>): List<InetAddress> {
        val hasIpv4 = manager.getLinkProperties(network)?.linkAddresses
            ?.any { it.address is Inet4Address } ?: true
        return VpnRunPolicy.order(addresses, hasIpv4) { it is Inet4Address }
    }

    // Android's preference order, as VpnRunPolicy.choose states it.
    private fun reconsider() {
        val changed = synchronized(lock) {
            val best = VpnRunPolicy.choose(
                known.mapValues { (_, capabilities) ->
                    VpnRunPolicy.NetworkFacts(
                        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    )
                },
            )
            val differs = best != chosen
            chosen = best
            differs
        }
        if (changed) onChanged()
    }
}
