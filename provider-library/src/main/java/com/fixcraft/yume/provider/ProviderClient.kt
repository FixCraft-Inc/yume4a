package com.fixcraft.yume.provider

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.openid.appauth.TokenRequest
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Contract-1 enrollment owner. No VPN, screen or account-policy dependency.
 * One caller-owned instance owns the store and must be closed at app shutdown.
 * Native credential validation is mandatory and has no success fallback.
 */
class ProviderClient(
    platform: Context,
    private val registration: ProviderRegistration?,
    private val store: ProviderStore,
    private val kits: ProviderKitAdapter,
    private val clock: ProviderClock = AndroidProviderClock,
    private val connections: Connections,
    private val http: ProviderTransport = ProviderHttp(),
    private val authorization: ProviderAuthorization = ProviderAuthorization(clock),
    private val authorizationAccess: suspend () -> Unit = {},
    private val proofKeys: ProviderProofKeys = AndroidProviderProofKeys(),
) : AutoCloseable {
    fun interface Connections { fun stop(provider: String) }
    /** An API credential and, for browser-authorized providers, its bound proof key. */
    private class Credential(val bearer: ProviderBearer, val key: ProviderProofKey?) {
        override fun toString() = "Credential(redacted)"
    }
    private class Lane {
        val mutex = Mutex()
        val generation = AtomicLong()
        val publication = Any()
        @Volatile var job: Job? = null
    }
    private class Account(val tokens: ProviderCodec.Tokens, val accessUntil: Long, val refreshUntil: Long) {
        override fun toString() = "Account(redacted)"
    }
    private class Record(
        val context: ProviderCodec.Context,
        var descriptor: ProviderCodec.Descriptor,
        var savedAt: Long,
        var account: Account? = null,
        var deviceCredential: String? = null,
        var deviceUntil: Long = 0,
        var grant: ProviderCodec.Grant? = null,
        var server: ProviderCodec.Server? = null,
        var suspended: Boolean = false,
        var pendingRequest: ProviderCodec.GrantRequest? = null,
        var retries: Int = 0,
    )
    private val platform = platform.applicationContext
    private val lanes = ConcurrentHashMap<String, Lane>()
    private val transportDeadlines = ConcurrentHashMap<String, Pair<String, Long>>()
    private val apiDeadlines = ConcurrentHashMap<String, Pair<String, Long>>()
    private val timeLock = Any()
    private var lastTime: ProviderClock.Reading? = null
    @Volatile private var closed = false

    private fun now(): ProviderClock.Reading = synchronized(timeLock) {
        val reading = clock.now()
        val previous = lastTime
        if (reading == null || (previous != null && (reading.seconds < previous.seconds ||
                reading.elapsedMillis < previous.elapsedMillis ||
                (reading.seconds - previous.seconds) * 1000 + 2000 < reading.elapsedMillis - previous.elapsedMillis))) {
            throw ProviderCodec.Refusal("stale")
        }
        lastTime = reading
        reading
    }

    init {
        store.recover()
        // Keys of removed enrollments are unusable without their tokens. A
        // failed sweep leaves them for the next start and blocks nothing.
        try { proofKeys.retain(store.epochs()) } catch (_: Exception) { }
    }
    private fun lane(provider: String): Lane {
        if (closed) throw ProviderCodec.Refusal("state")
        ProviderCodec.origin(provider)
        return lanes.computeIfAbsent(provider) { Lane() }
    }

    /** Explicit consent includes issuer and role; no default provider is selected. */
    suspend fun add(provider: String, acceptedIssuer: String?, role: String = "vpn"): ProviderCodec.Descriptor =
        operation(provider) { lane, generation ->
            if (acceptedIssuer != null && registration == null) throw ProviderCodec.Refusal("unsupported")
            if (store.epoch(provider) != null) throw ProviderCodec.Refusal("state")
            val context = ProviderCodec.Context(provider, ProviderAuthorization.randomIdentifier(), role,
                registration?.clientId, registration?.callback, acceptedIssuer)
            val descriptor = descriptor(context)
            val current = now()
            val record = Record(context, descriptor, current.seconds)
            if (descriptor.auth == null) {
                record.deviceCredential = ProviderAuthorization.random(32)
                record.deviceUntil = current.seconds + 2592000
            }
            synchronized(lane.publication) {
                checkCurrent(lane, generation)
                val epoch = store.add(provider)
                persist(record, epoch, emptyMap(), lane, generation)
            }
            descriptor
        }

    suspend fun beginAuthorization(provider: String): Intent = operation(provider) { lane, generation ->
        val registration = registration ?: throw ProviderCodec.Refusal("unsupported")
        snapshot(provider) { record, epoch, bundles ->
            revalidate(record, epoch, bundles, lane, generation)
            requireDeviceBound(record)
            persist(record, epoch, bundles, lane, generation)
            synchronized(lane.publication) {
                checkCurrent(lane, generation)
                authorization.begin(record.context, record.descriptor, epoch.value, registration, platform, generation)
            }
        }
    }

    suspend fun callback(uri: String) {
        val exchange = authorization.consume(uri)
        exchange.use {
            operation(exchange.provider, exchange.generation) { lane, generation ->
                withAuthorizationAccess(exchange.deadline, ::now, authorizationAccess) {
                    snapshot(exchange.provider) { record, epoch, bundles ->
                        if (epoch.value != exchange.epoch || record.descriptor.auth?.tokenEndpoint != exchange.endpoint) {
                            throw ProviderCodec.Refusal("binding")
                        }
                        revalidate(record, epoch, bundles, lane, generation)
                        requireDeviceBound(record)
                        val auth = record.descriptor.auth ?: throw ProviderCodec.Refusal("state")
                        exchange.deadline.requireLive(now())
                        // Persist absence before using a single-use code. Process death or
                        // response loss requires another browser attempt, never replay.
                        record.account = null
                        persist(record, epoch, bundles, lane, generation)
                        exchange.deadline.requireLive(now())
                        // Each sign-in binds a new token family to a fresh device key.
                        val key = synchronized(lane.publication) {
                            checkCurrent(lane, generation)
                            proofKeys.create(epoch.value)
                        }
                        val proof = ProviderProof.create(key, "POST", exchange.endpoint, now().seconds)
                        val raw = http.request(auth.issuer, exchange.endpoint, exchange.body,
                            requestType = "application/x-www-form-urlencoded", proof = proof)
                        try {
                            val tokens = ProviderCodec.tokens(raw)
                            val current = now()
                            exchange.deadline.requireLive(current)
                            record.account = Account(tokens, current.seconds + tokens.expiresIn, current.seconds + tokens.refreshExpiresIn)
                            persist(record, epoch, bundles, lane, generation, exchange.deadline)
                        } finally { raw.fill(0) }
                    }
                }
            }
        }
    }

    suspend fun catalog(provider: String): ProviderCodec.Catalog = operation(provider) { lane, generation ->
        snapshot(provider) { record, epoch, bundles ->
            revalidate(record, epoch, bundles, lane, generation)
            val credential = credential(record, epoch, bundles, lane, generation)
            val request = ProviderCodec.encode(base(record))
            val raw = try {
                signedIn(record, epoch, lane, generation) {
                    api(credential, "${record.context.audience}/catalog", request, ProviderCodec.METADATA_LIMIT)
                }
            } finally { request.fill(0) }
            try { ProviderCodec.catalog(raw, record.descriptor, record.context, now().seconds) }
            finally { raw.fill(0) }
        }
    }

    /**
     * A device-bound provider delivers the opening code to the bound device
     * itself. [openingCode] supplies it only for anonymous providers, whose
     * code arrives through a separately authenticated channel.
     */
    suspend fun install(provider: String, selected: ProviderCodec.Catalog, service: String, server: String,
                        openingCode: suspend (ProviderCodec.Grant) -> CharArray): ProviderCodec.Grant =
        operation(provider) { lane, generation ->
            snapshot(provider) { record, epoch, bundles ->
                revalidate(record, epoch, bundles, lane, generation)
                // An expired grant cannot be renewed: the provider has closed it.
                // Ask for a fresh grant instead, on any entitled server.
                val previous = record.grant?.takeIf { now().seconds < it.expiresAt }
                if (previous != null && (previous.request.serviceId != service || previous.request.serverId != server)) {
                    throw ProviderCodec.Refusal("binding")
                }
                val pending = record.pendingRequest
                if (pending != null && (pending.serviceId != service || pending.serverId != server || record.retries >= 1)) {
                    throw ProviderCodec.Refusal("state")
                }
                val request = pending ?: ProviderCodec.GrantRequest(provider, record.context.deviceId, ProviderAuthorization.randomIdentifier(),
                    service, server, record.context.role, previous?.grantId)
                val edge = ProviderCodec.request(request, selected, record.context, now().seconds)
                if (previous != null && now().seconds < previous.refreshAfter) throw ProviderCodec.Refusal("stale")
                // Each request below can learn that the sign-in ended.
                suspend fun <T> account(call: suspend () -> T): T = signedIn(record, epoch, lane, generation, call)
                val credential = credential(record, epoch, bundles, lane, generation)
                // Remote rotation may succeed even if any later step fails. Persist
                // suspension before the request so restart cannot restore old authority.
                record.suspended = true
                record.pendingRequest = request
                record.retries = if (pending == null) 0 else record.retries + 1
                persist(record, epoch, bundles, lane, generation)
                connections.stop(provider)
                val body = ProviderCodec.encode(request.fields())
                val raw = try { account { api(credential, "${record.context.audience}/grants", body, ProviderCodec.METADATA_LIMIT) } }
                    finally { body.fill(0) }
                val grant = try { ProviderCodec.grant(raw, request, previous, now().seconds) } finally { raw.fill(0) }
                val sealed = account { api(credential, "${record.context.audience}/grants/${grant.grantId}/kit", null, grant.kitSize,
                    "application/octet-stream", record.context.deviceId) }
                try {
                    val code = if (ProviderCodec.DEVICE_BOUND in record.descriptor.required) {
                        account { deliveredCode(record, credential, grant) }
                    } else openingCode(grant)
                    try {
                        kits.validate(sealed, code, grant, edge).use { kit ->
                            if (now().seconds >= grant.expiresAt) throw ProviderCodec.Refusal("stale")
                            record.grant = grant
                            record.server = edge
                            record.suspended = false
                            record.pendingRequest = null
                            record.retries = 0
                            persist(record, epoch, mapOf(grant.grantId to kit.bundle), lane, generation)
                            transportDeadlines[provider] = grant.grantId to
                                (now().elapsedMillis + (grant.expiresAt - now().seconds) * 1000)
                        }
                    } finally { code.fill('\u0000') }
                } finally { sealed.fill(0) }
                grant
            }
        }

    /** Explicit acquisition rechecks a lease. The consumer must stop at its returned deadline. */
    suspend fun lease(provider: String): Lease = operation(provider) { lane, generation ->
        snapshot(provider) { record, epoch, bundles ->
            val current = now()
            val grant = record.grant ?: throw ProviderCodec.Refusal("state")
            val deadline = transportDeadlines.compute(provider) { _, previous ->
                if (previous?.first == grant.grantId) previous else grant.grantId to
                    (current.elapsedMillis + maxOf(0, grant.expiresAt - current.seconds) * 1000)
            }!!
            if (record.suspended || current.seconds >= grant.expiresAt || current.elapsedMillis >= deadline.second) {
                connections.stop(provider)
                throw ProviderCodec.Refusal("revoked")
            }
            synchronized(lane.publication) {
                checkCurrent(lane, generation)
                if (store.epoch(provider) != epoch) throw ProviderCodec.Refusal("cancelled")
                Lease(bundles.getValue(grant.grantId).copyOf(), grant.expiresAt, deadline.second,
                    epoch.value, grant, record.server)
            }
        }
    }
    class Lease(val bundle: ByteArray, val expiresAt: Long, val elapsedDeadline: Long,
                val epoch: String, val grant: ProviderCodec.Grant, val server: ProviderCodec.Server?) : AutoCloseable {
        override fun close() { bundle.fill(0) }
        override fun toString() = "Lease(redacted)"
    }

    /**
     * Authenticated local display state. Listing never contacts an operator.
     * [authorized] means local API credentials exist, not that they are still
     * accepted remotely.
     */
    data class Enrollment(val provider: String, val epoch: String, val label: String, val issuer: String?,
                          val grant: ProviderCodec.Grant?, val server: ProviderCodec.Server?, val suspended: Boolean,
                          val authorized: Boolean = false)

    fun enrollments(): List<Enrollment> = store.origins().map { provider ->
        val epoch = store.epoch(provider) ?: throw ProviderCodec.Refusal("storage")
        val snapshot = store.read(provider, epoch) ?: throw ProviderCodec.Refusal("storage")
        snapshot.use {
            val record = decode(it.metadata)
            if (record.context.provider != provider) throw ProviderCodec.Refusal("binding")
            Enrollment(provider, epoch.value, record.descriptor.label, record.context.acceptedIssuer,
                record.grant, record.server, record.suspended,
                if (record.descriptor.auth == null) record.deviceCredential != null else record.account != null)
        }
    }

    fun cancel(provider: String) {
        val lane = lane(provider)
        synchronized(lane.publication) {
            lane.generation.incrementAndGet()
            authorization.cancel(provider)
            lane.job?.cancel()
        }
    }

    /** Local deletion succeeds offline. No credential is kept for retry. */
    suspend fun remove(provider: String): Boolean {
        val lane = lane(provider)
        var removed: Record? = null
        var removedEpoch: String? = null
        var key: ProviderProofKey? = null
        var failure: Exception? = null
        try {
            synchronized(lane.publication) {
                lane.generation.incrementAndGet()
                authorization.cancel(provider)
                lane.job?.cancel()
                // Read only the selected provider. A locked vault has no credential
                // available for revocation, but its durable tombstone still wins.
                try {
                    store.epoch(provider)?.let { epoch ->
                        removedEpoch = epoch.value
                        store.read(provider, epoch)?.use { removed = decode(it.metadata) }
                        if (removed?.account != null) key = proofKeys.load(epoch.value)
                    }
                } catch (_: Exception) { /* Local removal must work while locked. */ }
                try { store.remove(provider) } catch (error: Exception) { failure = error }
                transportDeadlines.remove(provider)
                listOf("access", "refresh", "device").forEach { apiDeadlines.remove("$provider|$it") }
            }
        } finally { connections.stop(provider) }
        try {
            failure?.let { throw it }
            val record = removed ?: return false
            return revokeRemoved(provider, record, key)
        } finally {
            // Removal deletes the device key even when revocation was impossible.
            removedEpoch?.let { epoch -> try { proofKeys.delete(epoch) } catch (_: Exception) { } }
        }
    }

    // The only revocation material is this bounded, ephemeral local value.
    // Cancellation never persists it for a retry.
    private suspend fun revokeRemoved(provider: String, record: Record, key: ProviderProofKey?): Boolean =
        withTimeoutOrNull(30000) {
            var confirmed = true
            val account = record.account?.tokens?.accessToken
            val token = account ?: record.deviceCredential
            // A browser-authorized token without its device key cannot authenticate.
            val credential = token?.takeIf { account == null || key != null }?.let { Credential(ProviderBearer(provider, it), key) }
            if (record.grant != null && credential != null) {
                val fields = base(record) + mapOf("request_id" to ProviderAuthorization.randomIdentifier(), "grant_id" to record.grant!!.grantId)
                val body = ProviderCodec.encode(fields)
                try {
                    val reply = api(credential, "${record.context.audience}/revoke", body)
                    try {
                        val decoded = ProviderCodec.decode(reply)
                        if (decoded != fields + ("status" to "revoked")) confirmed = false
                    } finally { reply.fill(0) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { confirmed = false }
                finally { body.fill(0) }
            } else if (record.grant != null) confirmed = false
            val auth = record.descriptor.auth
            if (auth != null && record.account != null) {
                val body = ProviderAuthorization.form(mapOf("token" to record.account!!.tokens.refreshToken, "client_id" to auth.clientId))
                try { http.request(auth.issuer, auth.revocationEndpoint, body,
                    requestType = "application/x-www-form-urlencoded").fill(0) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { confirmed = false }
                finally { body.fill(0) }
            }
            confirmed
        } ?: false

    private suspend fun descriptor(context: ProviderCodec.Context): ProviderCodec.Descriptor {
        val raw = http.request(context.provider, "${context.provider}/.well-known/yume-provider")
        return try { ProviderCodec.descriptor(raw, context, now().seconds) } finally { raw.fill(0) }
    }

    private suspend fun revalidate(record: Record, epoch: ProviderStore.Epoch, bundles: Map<String, ByteArray>, lane: Lane, generation: Long) {
        try {
            val fresh = descriptor(record.context)
            if (fresh.auth != record.descriptor.auth) throw ProviderCodec.Refusal("binding")
            record.descriptor = fresh
        } catch (refused: ProviderCodec.Refusal) {
            // An outage or rate limit says nothing about the provider's identity.
            // The installed lease stays usable until its own deadline, as when the
            // account API is down. Any other refusal suspends the enrollment.
            if (refused.code !in TRANSIENT) {
                record.suspended = true
                connections.stop(record.context.provider)
                persist(record, epoch, bundles, lane, generation)
            }
            throw refused
        }
    }

    /**
     * Sends one request that the account's sign-in authenticates. A remote
     * `unauthorized` answer means the issuer or provider ended that sign-in,
     * for example through account-wide logout. Grants belong to the sign-in
     * that obtained them, and the provider closes them with it, so the local
     * account and grant are both forgotten and their connections stop. The
     * enrollment then reads signed out, and after signing in again the user
     * chooses a server for a fresh grant rather than renewing a closed one.
     */
    private suspend fun <T> signedIn(record: Record, epoch: ProviderStore.Epoch, lane: Lane, generation: Long,
                                     request: suspend () -> T): T = try { request() }
        catch (refused: ProviderCodec.Refusal) {
            if (refused.code == "unauthorized" && record.descriptor.auth != null) {
                record.account = null
                record.grant = null
                record.server = null
                record.pendingRequest = null
                record.retries = 0
                // Suspension guarded the closed grant. Nothing usable remains to guard.
                record.suspended = false
                transportDeadlines.remove(record.context.provider)
                connections.stop(record.context.provider)
                persist(record, epoch, emptyMap(), lane, generation)
            }
            throw refused
        }

    private suspend fun credential(record: Record, epoch: ProviderStore.Epoch, bundles: Map<String, ByteArray>,
                                   lane: Lane, generation: Long): Credential {
        val current = now()
        val auth = record.descriptor.auth
        if (auth == null) {
            if (record.deviceCredential == null || !validUntil(record.context.provider, "device", record.deviceCredential!!,
                    record.deviceUntil, current)) throw ProviderCodec.Refusal("unauthorized")
            return Credential(ProviderBearer(record.context.provider, record.deviceCredential!!), null)
        }
        requireDeviceBound(record)
        var account = record.account ?: throw ProviderCodec.Refusal("unauthorized")
        if (!validUntil(record.context.provider, "refresh", account.tokens.refreshToken, account.refreshUntil, current)) {
            throw ProviderCodec.Refusal("unauthorized")
        }
        // A lost or replaced device key ends this family locally. Sign-in creates another.
        val key = proofKeys.load(epoch.value) ?: run {
            record.account = null
            persist(record, epoch, bundles, lane, generation)
            throw ProviderCodec.Refusal("unauthorized")
        }
        if (!validUntil(record.context.provider, "access", account.tokens.accessToken, account.accessUntil, current)) {
            val tokenRequest = TokenRequest.Builder(ProviderAuthorization.configuration(auth), auth.clientId)
                .setGrantType("refresh_token").setRefreshToken(account.tokens.refreshToken)
                .setAdditionalParameters(mapOf("resource" to record.context.audience)).build()
            val body = ProviderAuthorization.form(tokenRequest.requestParameters + ("client_id" to auth.clientId))
            record.account = null
            persist(record, epoch, bundles, lane, generation)
            val proof = ProviderProof.create(key, "POST", auth.tokenEndpoint, now().seconds)
            val raw = try {
                signedIn(record, epoch, lane, generation) {
                    http.request(auth.issuer, auth.tokenEndpoint, body,
                        requestType = "application/x-www-form-urlencoded", proof = proof)
                }
            } finally { body.fill(0) }
            try {
                val tokens = ProviderCodec.tokens(raw, account.tokens)
                val refreshed = now()
                account = Account(tokens, refreshed.seconds + tokens.expiresIn, refreshed.seconds + tokens.refreshExpiresIn)
                record.account = account
                persist(record, epoch, bundles, lane, generation)
            } finally { raw.fill(0) }
        }
        return Credential(ProviderBearer(record.context.provider, account.tokens.accessToken), key)
    }

    /** One provider API request, with a fresh proof when the credential is device-bound. */
    private suspend fun api(credential: Credential, endpoint: String, body: ByteArray?,
                            limit: Int = ProviderCodec.SMALL_LIMIT, responseType: String = "application/json",
                            deviceHeader: String? = null): ByteArray {
        val bearer = credential.bearer
        val sent = if (deviceHeader == null) bearer else ProviderBearer(bearer.origin, bearer.value, deviceHeader)
        val proof = credential.key?.let { ProviderProof.create(it, if (body == null) "GET" else "POST", endpoint, now().seconds, bearer.value) }
        return http.request(bearer.origin, endpoint, body, sent, limit, responseType, proof = proof)
    }

    /** The bound device fetches the opening code of the grant its own family requested. */
    private suspend fun deliveredCode(record: Record, credential: Credential, grant: ProviderCodec.Grant): CharArray {
        val body = ProviderCodec.encode(base(record) + ("grant_id" to grant.grantId))
        val raw = try { api(credential, "${record.context.audience}/opening-code", body) } finally { body.fill(0) }
        return try { ProviderCodec.openingCode(raw, record.context, grant.grantId) } finally { raw.fill(0) }
    }

    /** Browser authorization requires device-bound tokens. A stored older descriptor must be refreshed first. */
    private fun requireDeviceBound(record: Record) {
        if (record.descriptor.auth != null && ProviderCodec.DEVICE_BOUND !in record.descriptor.required) {
            throw ProviderCodec.Refusal("unsupported")
        }
    }

    private fun validUntil(provider: String, kind: String, token: String, until: Long, current: ProviderClock.Reading): Boolean {
        if (current.seconds >= until) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val deadline = apiDeadlines.compute("$provider|$kind") { _, previous ->
            if (previous?.first == digest) previous else digest to
                (current.elapsedMillis + (until - current.seconds) * 1000)
        }!!
        return current.elapsedMillis < deadline.second
    }

    private fun base(record: Record) = mapOf("v" to 1L, "provider" to record.context.provider,
        "device_id" to record.context.deviceId, "audience" to record.context.audience)

    private suspend fun <T> operation(provider: String, expectedGeneration: Long? = null, block: suspend (Lane, Long) -> T): T {
        val lane = lane(provider)
        val admitted = expectedGeneration ?: lane.generation.get()
        return lane.mutex.withLock {
            val generation: Long
            val job = currentCoroutineContext()[Job]
            synchronized(lane.publication) {
                if (lane.generation.get() != admitted) throw ProviderCodec.Refusal("cancelled")
                generation = admitted
                lane.job = job
            }
            try {
                val result = block(lane, generation)
                try {
                    synchronized(lane.publication) { checkCurrent(lane, generation) }
                    result
                } catch (error: Throwable) {
                    (result as? AutoCloseable)?.close()
                    throw error
                }
            }
            catch (refused: ProviderCodec.Refusal) {
                if (refused.code == "stale") (transportDeadlines.keys + provider).toSet().forEach(connections::stop)
                throw refused
            } catch (cancelled: CancellationException) {
                synchronized(lane.publication) { lane.generation.incrementAndGet(); authorization.cancel(provider) }
                throw cancelled
            } finally { lane.job = null }
        }
    }

    private fun checkCurrent(lane: Lane, generation: Long) {
        if (closed || lane.generation.get() != generation || lane.job?.isActive == false) throw ProviderCodec.Refusal("cancelled")
    }

    private fun persist(record: Record, epoch: ProviderStore.Epoch, bundles: Map<String, ByteArray>, lane: Lane,
                        generation: Long, authorizationDeadline: ProviderAuthorizationDeadline? = null) {
        val current = now()
        authorizationDeadline?.requireLive(current)
        if (current.seconds < record.savedAt) throw ProviderCodec.Refusal("stale")
        record.savedAt = current.seconds
        val bytes = encode(record)
        try {
            synchronized(lane.publication) {
                checkCurrent(lane, generation)
                store.commit(record.context.provider, epoch, bytes, bundles) {
                    authorizationDeadline?.requireLive(now())
                    lane.generation.get() == generation
                }
            }
        } finally { bytes.fill(0) }
    }

    private suspend fun <T> snapshot(provider: String, block: suspend (Record, ProviderStore.Epoch, Map<String, ByteArray>) -> T): T {
        val epoch = store.epoch(provider) ?: throw ProviderCodec.Refusal("state")
        val snapshot = store.read(provider, epoch) ?: throw ProviderCodec.Refusal("state")
        snapshot.use {
            val record = decode(snapshot.metadata)
            if (record.context.provider != provider || now().seconds < record.savedAt) throw ProviderCodec.Refusal("stale")
            return block(record, epoch, snapshot.bundles)
        }
    }

    // These bytes are authenticated local metadata, not network input. Tokens
    // remain ordinary JVM strings because AppAuth's public API requires them.
    private fun encode(record: Record): ByteArray {
        val json = JSONObject().put("provider", record.context.provider).put("device", record.context.deviceId)
            .put("role", record.context.role).put("issuer", record.context.acceptedIssuer ?: JSONObject.NULL)
            .put("descriptor", JSONObject(record.descriptor.fields())).put("saved", record.savedAt)
            .put("deviceCredential", record.deviceCredential ?: JSONObject.NULL).put("deviceUntil", record.deviceUntil)
            .put("suspended", record.suspended).put("grant", record.grant?.let { JSONObject(it.fields()) } ?: JSONObject.NULL)
            .put("server", record.server?.let { server -> JSONObject().put("id", server.id).put("label", server.label)
                .put("host", server.host).put("port", server.port).put("key", server.serverKeySha256)
                .put("country", server.country ?: JSONObject.NULL) } ?: JSONObject.NULL)
            .put("pending", record.pendingRequest?.let { JSONObject(it.fields()) } ?: JSONObject.NULL).put("retries", record.retries)
        record.account?.let { account -> json.put("account", JSONObject().put("type", "DPoP").put("access", account.tokens.accessToken)
            .put("refresh", account.tokens.refreshToken).put("accessUntil", account.accessUntil).put("refreshUntil", account.refreshUntil)) }
        return json.toString().toByteArray(Charsets.UTF_8).also {
            if (it.size > 65536) { it.fill(0); throw ProviderCodec.Refusal("storage") }
        }
    }

    private fun decode(bytes: ByteArray): Record {
        if (bytes.size > 65536) throw ProviderCodec.Refusal("storage")
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val context = ProviderCodec.Context(json.getString("provider"), json.getString("device"), json.getString("role"),
            registration?.clientId, registration?.callback, if (json.isNull("issuer")) null else json.getString("issuer"))
        val saved = json.getLong("saved")
        val descriptorRaw = json.getJSONObject("descriptor").toString().toByteArray(Charsets.UTF_8)
        val descriptor = try { ProviderCodec.descriptor(descriptorRaw, context,
            minOf(saved, json.getJSONObject("descriptor").getLong("expires_at") - 1)) } finally { descriptorRaw.fill(0) }
        val record = Record(context, descriptor, saved, deviceCredential = if (json.isNull("deviceCredential")) null else json.getString("deviceCredential"),
            deviceUntil = json.getLong("deviceUntil"), suspended = json.getBoolean("suspended"))
        // Bearer tokens from before device binding are not restored. The
        // enrollment, its grant and local identity remain, and sign-in replaces them.
        if (json.has("account") && json.getJSONObject("account").optString("type") == "DPoP") {
            val account = json.getJSONObject("account")
            record.account = Account(ProviderCodec.Tokens(account.getString("access"), account.getString("refresh"), 0, 0),
                account.getLong("accessUntil"), account.getLong("refreshUntil"))
        }
        if (!json.isNull("pending")) {
            val pending = json.getJSONObject("pending")
            record.pendingRequest = ProviderCodec.GrantRequest(context.provider, context.deviceId, pending.getString("request_id"),
                pending.getString("service_id"), pending.getString("server_id"), context.role,
                if (pending.isNull("previous_grant")) null else pending.getString("previous_grant"))
            record.retries = json.getInt("retries")
        }
        if (!json.isNull("grant")) {
            val g = json.getJSONObject("grant")
            val request = ProviderCodec.GrantRequest(context.provider, context.deviceId, g.getString("request_id"),
                g.getString("service_id"), g.getString("server_id"), context.role,
                if (g.isNull("previous_grant")) null else g.getString("previous_grant"))
            record.grant = ProviderCodec.Grant(request, g.getString("grant_id"), g.getLong("generation"), g.getLong("issued_at"),
                g.getLong("refresh_after"), g.getLong("expires_at"), g.getString("client_identity"), g.getString("kit_sha256"), g.getInt("kit_size"))
        }
        json.optJSONObject("server")?.let { server ->
            record.server = ProviderCodec.Server(server.getString("id"), server.getString("label"),
                server.getString("host"), server.getInt("port"), server.getString("key"),
                if (server.isNull("country")) null else server.getString("country"))
        }
        return record
    }

    override fun close() {
        closed = true
        lanes.values.forEach { lane -> synchronized(lane.publication) { lane.generation.incrementAndGet(); lane.job?.cancel() } }
        authorization.close()
        http.close()
        store.close()
    }

    private companion object {
        /** Failures to reach a provider, and local stops, as opposed to its answers. */
        val TRANSIENT = setOf("unavailable", "rate_limited", "cancelled", "state")
    }
}
