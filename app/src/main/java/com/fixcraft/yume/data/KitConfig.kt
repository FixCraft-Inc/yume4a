package com.fixcraft.yume.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

/** What a kit's `yume.json` says about where and how it connects. */
data class KitDescription(
    val host: String,
    val port: Int,
    /** An address the kit's owner fixed. The app then resolves nothing. */
    val connectAddress: String?,
    /** The byte-stream service TCP connections open, as the kit's SOCKS5 adapter does. */
    val streamService: String,
    /** The packet service UDP uses, or null when the kit carries none. */
    val packetService: String?,
    val usesCircuits: Boolean,
    val usesProxy: Boolean,
)

class KitConfigException(message: String) : Exception(message)

/**
 * Reads a kit's schema-1 client document and prepares it for the phone.
 *
 * libyume's strict parser decides whether the document is valid. This only
 * finds the members the application needs and changes the three that differ
 * on a phone. Services and limits pass through untouched, so the server sees
 * what a `yume` client of the same kit offers.
 */
object KitConfig {
    fun describe(config: ByteArray): KitDescription {
        val root = parse(config)
        val endpoint = root.optJSONObject("endpoint") ?: throw KitConfigException("the kit names no endpoint")
        val host = endpoint.optString("host")
        val port = endpoint.optInt("port", 0)
        if (host.isBlank() || port !in 1..65535) throw KitConfigException("the kit names no server")

        val services = root.optJSONArray("services") ?: JSONArray()
        // JSON order is part of the deterministic fallback when an adapter
        // does not name a service explicitly.
        val kinds = LinkedHashMap<String, String>()
        for (index in 0 until services.length()) {
            val service = services.optJSONObject(index) ?: continue
            kinds[service.optString("name")] = service.optString("kind")
        }
        // The kit's SOCKS5 adapter names the services a client of this kit
        // uses for TCP and UDP. A kit without one offers its first of each.
        var stream: String? = null
        var packet: String? = null
        val adapters = root.optJSONArray("adapters") ?: JSONArray()
        for (index in 0 until adapters.length()) {
            val adapter = adapters.optJSONObject(index) ?: continue
            if (adapter.optString("kind") != "socks5") continue
            stream = adapter.optString("service").takeIf { kinds[it] == "stream" }
            packet = adapter.optString("udp_service").takeIf { kinds[it] == "packet" }
            break
        }
        if (stream == null) {
            stream = kinds.entries.firstOrNull { it.value == "stream" }?.key
            packet = packet ?: kinds.entries.firstOrNull { it.value == "packet" }?.key
        }
        if (stream.isNullOrBlank()) throw KitConfigException("the kit offers no service for connections")

        return KitDescription(
            host = host,
            port = port,
            connectAddress = endpoint.optString("connect_address").takeIf { it.isNotBlank() },
            streamService = stream,
            packetService = packet,
            usesCircuits = root.has("circuits"),
            usesProxy = endpoint.has("socks5_proxy"),
        )
    }

    /**
     * The document a phone runs: no local adapters, since the VPN's device
     * takes their place, no control socket, and the server's address as this
     * application resolved it on the network under the VPN.
     */
    fun forDevice(config: ByteArray, connectAddress: String?): ByteArray {
        val root = parse(config)
        root.put("adapters", JSONArray())
        root.remove("control")
        if (connectAddress != null) {
            val endpoint = root.optJSONObject("endpoint") ?: throw KitConfigException("the kit names no endpoint")
            endpoint.put("connect_address", connectAddress)
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /** Replaces only the four shared tuning members, retaining every other member. */
    fun withLimits(config: ByteArray, limits: TuningLimits): ByteArray {
        val root = parse(config)
        val existing = root.optJSONObject("limits") ?: JSONObject()
        limits.toJson().keys().forEach { key -> existing.put(key, limits.toJson().get(key)) }
        root.put("limits", existing)
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * True for an IPv4 or IPv6 literal, which needs no lookup. The same text
     * rules as libyume's IsIpLiteral (src/config/v1/config.cpp): no leading
     * zero in an IPv4 part, which a resolver reads as octal, no brackets and
     * no zone. Parsed here without any platform resolver, which on Android
     * would look a malformed literal up as a name.
     */
    fun isAddressLiteral(host: String): Boolean = isIpv4(host) || isIpv6(host)

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 &&
                (part.length == 1 || part[0] != '0') &&
                part.all { it in '0'..'9' } && part.toInt() <= 255
        }
    }

    private fun isIpv6(value: String): Boolean {
        if (!value.contains(':')) return false
        val compression = value.indexOf("::")
        if (compression < 0) return ipv6Units(value, allowEmbeddedIpv4 = true) == 8
        if (value.indexOf("::", compression + 2) >= 0) return false
        val left = ipv6Units(value.substring(0, compression), allowEmbeddedIpv4 = false) ?: return false
        val right = ipv6Units(value.substring(compression + 2), allowEmbeddedIpv4 = true) ?: return false
        return left + right < 8
    }

    /** The 16-bit units in a colon-separated part, or null when malformed. */
    private fun ipv6Units(part: String, allowEmbeddedIpv4: Boolean): Int? {
        if (part.isEmpty()) return 0
        val groups = part.split(':')
        var units = 0
        groups.forEachIndexed { index, group ->
            if (group.isEmpty()) return null
            if (group.contains('.')) {
                if (!allowEmbeddedIpv4 || index != groups.lastIndex || !isIpv4(group)) return null
                units += 2
            } else {
                if (group.length > 4 || !group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                units += 1
            }
        }
        return units
    }

    private fun parse(config: ByteArray): JSONObject = try {
        JSONObject(String(config, StandardCharsets.UTF_8))
    } catch (error: JSONException) {
        throw KitConfigException("the kit's yume.json is not a JSON object")
    }
}
