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
    val anonymCaMaterialId: String = "",
    val authKeyMaterialId: String = "",
    val notes: String = "",
    // Complete shared-core TLS/H2 transport fixture. The registry currently
    // exposes Chrome; future Firefox/other fixtures are added in one place.
    val tlsStealthProfile: String = DEFAULT_TLS_STEALTH_PROFILE,
    // YUME 2.0 admission and inner-crypto material. ServerProfileStore is
    // backed by the application vault; plaintext exists only while a session
    // is being configured for the native client.
    val innerPsk: String = "",
    val tlsPinSha256: String = "",
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
        .put("inner_psk", innerPsk)
        .put("tls_pin_sha256", tlsPinSha256)

    companion object {
        const val DEFAULT_MTU = 1500
        const val DEFAULT_TLS_STEALTH_PROFILE = "chrome"
        val TLS_STEALTH_PROFILE_OPTIONS: List<String>
            get() = TransportProfileRegistry.ids
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
            anonymCaMaterialId = if (json.has("anonym_ca_material_id")) {
                json.optString("anonym_ca_material_id").trim()
            } else {
                SecureMaterialStore.DEFAULT_ANONYM_CA_ID
            },
            authKeyMaterialId = json.optString("auth_key_material_id"),
            notes = json.optString("notes"),
            tlsStealthProfile = TransportProfileRegistry.normalize(
                json.optString("tls_stealth_profile"),
            ),
            innerPsk = json.optString("inner_psk"),
            tlsPinSha256 = json.optString("tls_pin_sha256"),
        )
    }
}
