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
    val dnsServers: List<String> = DEFAULT_DNS_SERVERS,
    val mtu: Int = DEFAULT_MTU,
    val requireAnonymProof: Boolean = true,
    val anonymCaMaterialId: String = SecureMaterialStore.DEFAULT_ANONYM_CA_ID,
    val authKeyMaterialId: String = "",
    val notes: String = "",
) {
    val endpointLabel: String
        get() = "$host:$port"

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
        .put("dns_servers", JSONArray().apply { dnsServers.forEach(::put) })
        .put("mtu", mtu)
        .put("require_anonym_proof", requireAnonymProof)
        .put("anonym_ca_material_id", anonymCaMaterialId)
        .put("auth_key_material_id", authKeyMaterialId)
        .put("notes", notes)

    companion object {
        const val DEFAULT_MTU = 1500
        val DEFAULT_DNS_SERVERS: List<String> = listOf("1.1.1.1", "9.9.9.9")

        fun fromJson(json: JSONObject): ServerProfile = ServerProfile(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name"),
            host = json.optString("host"),
            port = json.optInt("port", 443).coerceIn(1, 65535),
            sni = json.optString("sni"),
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
        )
    }
}
