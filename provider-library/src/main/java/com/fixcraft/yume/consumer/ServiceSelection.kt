package com.fixcraft.yume.consumer

import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderCodec
import org.json.JSONObject

/** Public binding, never a credential or proof of a live authenticated session. */
data class ServiceSelection(
    val provider: String, val epoch: String, val device: String,
    val service: String, val server: String, val grant: String,
    val identity: String, val serverKey: String, val host: String, val port: Int,
    val label: String, val role: String,
) {
    val id: String get() = "provider:$epoch:$grant"

    fun encode(): String = JSONObject().put("provider", provider).put("epoch", epoch).put("device", device)
        .put("service", service).put("server", server).put("grant", grant).put("identity", identity)
        .put("serverKey", serverKey).put("host", host).put("port", port).put("label", label).put("role", role).toString()

    fun matches(lease: ProviderClient.Lease, expectedRole: String): Boolean {
        val current = lease.grant
        val edge = lease.server ?: return false
        return provider == current.request.provider && epoch == lease.epoch && device == current.request.deviceId &&
            service == current.request.serviceId && server == current.request.serverId && edge.id == server &&
            role == expectedRole && role in setOf("vpn", "relay") && current.request.role == role && grant == current.grantId &&
            identity == current.clientIdentity && serverKey == edge.serverKeySha256 && host == edge.host && port == edge.port
    }

    companion object {
        fun decode(text: String): ServiceSelection {
            require(text.length <= 4096)
            val json = JSONObject(text)
            require(json.length() == 12)
            val result = ServiceSelection(json.getString("provider"), json.getString("epoch"), json.getString("device"),
                json.getString("service"), json.getString("server"), json.getString("grant"), json.getString("identity"),
                json.getString("serverKey"), json.getString("host"), json.getInt("port"), json.getString("label"), json.getString("role"))
            require(result.role in setOf("vpn", "relay"))
            ProviderCodec.origin(result.provider)
            java.util.UUID.fromString(result.epoch)
            listOf(result.device, result.service, result.server, result.grant).forEach(ProviderCodec::identifier)
            require(Regex("[0-9a-f]{64}").matches(result.identity) && Regex("[0-9a-f]{64}").matches(result.serverKey))
            ProviderCodec.dnsHost(result.host)
            require(result.port in 1..65535 && result.label.toByteArray(Charsets.UTF_8).size in 1..160)
            return result
        }

        fun from(entry: ProviderClient.Enrollment): ServiceSelection {
            val grant = entry.grant ?: throw ProviderCodec.Refusal("state")
            val edge = entry.server ?: throw ProviderCodec.Refusal("binding")
            return ServiceSelection(entry.provider, entry.epoch, grant.request.deviceId, grant.request.serviceId,
                grant.request.serverId, grant.grantId, grant.clientIdentity, edge.serverKeySha256, edge.host, edge.port, edge.label, grant.request.role)
        }
    }
}

/** Both independent lease clocks and their high-water marks are required. */
internal class LeaseDeadline(private val expiresAt: Long, val elapsedDeadline: Long) {
    private var previousSeconds: Long? = null
    private var previousElapsed: Long? = null
    private var baselineSeconds: Long? = null
    private var baselineElapsed: Long? = null

    @Synchronized fun valid(seconds: Long?, elapsed: Long): Boolean {
        if (seconds == null || seconds >= expiresAt || elapsed >= elapsedDeadline) return false
        val oldSeconds = previousSeconds
        val oldElapsed = previousElapsed
        if (oldSeconds != null && oldElapsed != null && (seconds < oldSeconds || elapsed < oldElapsed)) return false
        val anchorSeconds = baselineSeconds
        val anchorElapsed = baselineElapsed
        if (anchorSeconds != null && anchorElapsed != null) {
            val utcProgress = (seconds - anchorSeconds) * 1000
            val elapsedProgress = elapsed - anchorElapsed
            if (utcProgress + 2000 < elapsedProgress) return false
            if (utcProgress > elapsedProgress) { baselineSeconds = seconds; baselineElapsed = elapsed }
        } else { baselineSeconds = seconds; baselineElapsed = elapsed }
        previousSeconds = seconds
        previousElapsed = elapsed
        return true
    }
}
