package com.fixcraft.yume.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 443,
    val sni: String = "",
    val obfuscation: Boolean = false,
    val obfsSecret: String = "",
    val dnsServers: List<String> = DEFAULT_DNS_SERVERS,
    val mtu: Int = DEFAULT_MTU,
    val requireAnonymProof: Boolean = true,
    val anonymCaMaterialId: String = SecureMaterialStore.DEFAULT_ANONYM_CA_ID,
    val authKeyMaterialId: String = "",
    val notes: String = "",
    // TLS stealth profile this client should mimic on the obfs
    // carrier handshake. Currently affects the User-Agent yume sends
    // in the carrier HEADERS frame; full JA3-matching cipher list +
    // extension order is on the post-1.x Android roadmap (requires
    // Conscrypt-specific socket-level tuning that JSSE doesn't
    // expose). Valid values: "chrome", "firefox", "safari", "edge".
    // Empty string = "chrome" (default).
    val tlsStealthProfile: String = DEFAULT_TLS_STEALTH_PROFILE,
) {
    val endpointLabel: String
        get() = if (port == 443) host else "$host:$port"

    val serverName: String
        get() = sni.ifBlank { host }

    val dnsSummary: String
        get() = dnsServers.joinToString(", ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("sni", sni)
        .put("obfuscation", obfuscation)
        .put("obfs_secret", obfsSecret)
        .put("dns_servers", JSONArray().apply { dnsServers.forEach(::put) })
        .put("mtu", mtu)
        .put("require_anonym_proof", requireAnonymProof)
        .put("anonym_ca_material_id", anonymCaMaterialId)
        .put("auth_key_material_id", authKeyMaterialId)
        .put("notes", notes)
        .put("tls_stealth_profile", tlsStealthProfile)

    companion object {
        const val DEFAULT_MTU = 1500
        const val DEFAULT_TLS_STEALTH_PROFILE = "chrome"
        val TLS_STEALTH_PROFILE_OPTIONS: List<String> =
            listOf("chrome", "firefox", "safari", "edge")
        val DEFAULT_DNS_SERVERS: List<String> = listOf("1.1.1.1", "9.9.9.9")

        fun fromJson(json: JSONObject): ServerProfile = ServerProfile(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name"),
            host = json.optString("host"),
            port = json.optInt("port", 443).coerceIn(1, 65535),
            sni = json.optString("sni"),
            obfuscation = json.optBoolean("obfuscation", false),
            obfsSecret = json.optString("obfs_secret"),
            dnsServers = buildList {
                val dnsArray = json.optJSONArray("dns_servers")
                if (dnsArray != null) {
                    for (index in 0 until dnsArray.length()) {
                        val value = dnsArray.optString(index).trim()
                        if (value.isNotEmpty()) {
                            add(value)
                        }
                    }
                }
            }.ifEmpty { DEFAULT_DNS_SERVERS },
            mtu = json.optInt("mtu", DEFAULT_MTU).coerceIn(1280, 1500),
            requireAnonymProof = json.optBoolean("require_anonym_proof", true),
            anonymCaMaterialId = json.optString("anonym_ca_material_id").ifBlank { SecureMaterialStore.DEFAULT_ANONYM_CA_ID },
            authKeyMaterialId = json.optString("auth_key_material_id"),
            notes = json.optString("notes"),
            tlsStealthProfile = json.optString("tls_stealth_profile")
                .ifBlank { DEFAULT_TLS_STEALTH_PROFILE }
                .lowercase()
                .let { if (it in TLS_STEALTH_PROFILE_OPTIONS) it else DEFAULT_TLS_STEALTH_PROFILE },
        )
    }
}
