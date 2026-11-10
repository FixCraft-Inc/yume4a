package com.fixcraft.yume.consumer

import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderCodec
import org.json.JSONObject

/**
 * The route a person selected: one provider enrollment, joined with the server
 * chosen for it. Neither is authority. [ServiceController.prepare] turns a route
 * into a lease-bound [ServiceSelection] when a connection starts. Only provider,
 * epoch and role are stored. The choice is read from the enrollment each time.
 */
data class ServiceRoute(val provider: String, val epoch: String, val role: String, val choice: ProviderClient.Choice?) {
    val id: String get() = "provider:$epoch"

    fun encode(): String = JSONObject().put("provider", provider).put("epoch", epoch).put("role", role).toString()

    companion object {
        /** Null for any other stored shape, which names no route the person can use. */
        fun decode(text: String): ServiceRoute? = try {
            require(text.length <= 1024)
            val json = JSONObject(text)
            require(json.length() == 3)
            val route = ServiceRoute(json.getString("provider"), json.getString("epoch"), json.getString("role"), null)
            require(route.role in setOf("vpn", "relay"))
            ProviderCodec.origin(route.provider)
            java.util.UUID.fromString(route.epoch)
            route
        } catch (_: IllegalArgumentException) { null }
        catch (_: org.json.JSONException) { null }
        catch (_: ProviderCodec.Refusal) { null }
    }
}

/**
 * Public binding of an acquired grant, never a credential or proof of a live
 * authenticated session. It lives only for a run: what is stored is the [ServiceRoute].
 */
data class ServiceSelection(
    val provider: String, val epoch: String, val device: String,
    val service: String, val server: String, val grant: String,
    val identity: String, val serverKey: String, val host: String, val port: Int,
    val label: String, val role: String,
    // Presentation only, never part of [matches]: the provider's name and the server's declared country.
    val providerLabel: String = "", val country: String? = null,
) {
    val id: String get() = "provider:$epoch:$grant"

    fun matches(lease: ProviderClient.Lease, expectedRole: String): Boolean {
        val current = lease.grant
        val edge = lease.server ?: return false
        return provider == current.request.provider && epoch == lease.epoch && device == current.request.deviceId &&
            service == current.request.serviceId && server == current.request.serverId && edge.id == server &&
            role == expectedRole && role in setOf("vpn", "relay") && current.request.role == role && grant == current.grantId &&
            identity == current.clientIdentity && serverKey == edge.serverKeySha256 && host == edge.host && port == edge.port
    }

    companion object {
        fun from(entry: ProviderClient.Enrollment): ServiceSelection {
            val grant = entry.grant ?: throw ProviderCodec.Refusal("state")
            val edge = entry.server ?: throw ProviderCodec.Refusal("binding")
            return ServiceSelection(entry.provider, entry.epoch, grant.request.deviceId, grant.request.serviceId,
                grant.request.serverId, grant.grantId, grant.clientIdentity, edge.serverKeySha256, edge.host, edge.port, edge.label, grant.request.role,
                entry.label, edge.country)
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
