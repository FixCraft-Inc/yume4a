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
import org.json.JSONArray
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

    /**
     * The server a person chose for this enrollment. Display data from a catalog,
     * not authority: [prepare] binds a grant only to a fresh catalog's entry.
     */
    data class Choice(val service: String, val server: ProviderCodec.Server)

    /** A replaced grant that is still live at the provider until revoked or expired. */
    private class Retired(val grantId: String, val expiresAt: Long)

    /** A usable grant, and the refusal of a due renewal that could not replace it yet. */
    class Prepared(val grant: ProviderCodec.Grant, val renewalRefusal: String?)

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
        // The idempotency body of an unanswered grant request. Only an explicit
        // retry reuses it, and a definitive answer clears it.
        var pendingRequest: ProviderCodec.GrantRequest? = null,
        var choice: Choice? = null,
        val retired: MutableList<Retired> = mutableListOf(),
    )
    private val platform = platform.applicationContext
    private val lanes = ConcurrentHashMap<String, Lane>()
    private val transportDeadlines = ConcurrentHashMap<String, Pair<String, Long>>()
    private val apiDeadlines = ConcurrentHashMap<String, Pair<String, Long>>()
    private val timeLock = Any()
    private var lastTime: ProviderClock.Reading? = null
    @Volatile private var closed = false

    /** Trusted time, or null while there is none. A regression is refused as `stale`, which stops connections. */
    private fun reading(): ProviderClock.Reading? = synchronized(timeLock) {
        val reading = clock.now() ?: return null
        val previous = lastTime
        if (previous != null && (reading.seconds < previous.seconds || reading.elapsedMillis < previous.elapsedMillis ||
                (reading.seconds - previous.seconds) * 1000 + 2000 < reading.elapsedMillis - previous.elapsedMillis)) {
            throw ProviderCodec.Refusal("stale")
        }
        lastTime = reading
        reading
    }

    /** Missing trusted time is the local `clock_unavailable`, which stops nothing by itself. */
    private fun now(): ProviderClock.Reading = reading() ?: throw ProviderCodec.Refusal("clock_unavailable")

    /**
     * Establishes trusted time before an operation that talks to [provider], in
     * an operation of its own, so a regression found here stops connections too.
     */
    private suspend fun requireTime(provider: String) = operation(provider) { _, _ -> establishTime(provider) }

    /** Without trusted time, fetches [provider]'s descriptor, whose reply anchors a provider-response clock. */
    private suspend fun establishTime(provider: String) {
        if (reading() != null) return
        http.request(provider, descriptorEndpoint(provider)).fill(0)
        if (reading() == null) throw ProviderCodec.Refusal("clock_unavailable")
    }

    init {
        // Lets the shared clock keep its anchor across process restarts in this boot.
        AndroidProviderClock.attach(this.platform)
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

    suspend fun beginAuthorization(provider: String): Intent {
        requireTime(provider)
        return operation(provider) { lane, generation ->
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

    suspend fun catalog(provider: String): ProviderCodec.Catalog {
        requireTime(provider)
        return operation(provider) { lane, generation ->
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
    }

    /**
     * Records [server] of [service] in [selected] as this enrollment's chosen
     * server. Local only: nothing is requested until [prepare].
     */
    suspend fun choose(provider: String, selected: ProviderCodec.Catalog, service: String, server: String) =
        operation(provider) { lane, generation ->
            snapshot(provider) { record, epoch, bundles ->
                if (selected.provider != provider || selected.deviceId != record.context.deviceId ||
                    selected.audience != record.context.audience) throw ProviderCodec.Refusal("binding")
                val entry = selected.services.firstOrNull { it.id == service && it.role == record.context.role }
                    ?.servers?.firstOrNull { it.id == server } ?: throw ProviderCodec.Refusal("not_entitled")
                record.choice = Choice(service, entry)
                persist(record, epoch, bundles, lane, generation)
            }
        }

    /**
     * Makes the chosen server usable and returns its grant. A current grant for
     * that server is returned without the network until [ProviderCodec.Grant.renewAt], or,
     * when [renew] is false, until shortly before it expires: a connection
     * starts on it at once and renews later through its own tunnel, so a
     * blocked provider API does not stop it. Otherwise, and for any other
     * server, a fresh grant is issued while the current one stays usable. When
     * a due renewal fails for a transient reason and the current grant is still
     * live, that grant is returned with the refusal, so the caller keeps its
     * connection and tries again later.
     *
     * A device-bound provider delivers the opening code to the bound device
     * itself. [openingCode] supplies it only for anonymous providers, whose
     * code arrives through a separately authenticated channel.
     *
     * A connection that starts or renews with no trusted time and no provider
     * in reach uses the held grant, with the refusal `clock_unavailable`, unless
     * the trusted time last saved with it shows it expired. The node enforces
     * its expiry (docs/PROVIDER_CLIENT.md, "Trusted time").
     */
    suspend fun prepare(provider: String, renew: Boolean, openingCode: suspend (ProviderCodec.Grant) -> CharArray): Prepared {
        try { requireTime(provider) } catch (refused: ProviderCodec.Refusal) {
            if (refused.code !in UNTIMED) throw refused
            return Prepared(heldGrant(provider) ?: throw refused, "clock_unavailable")
        }
        val (choice, current) = operation(provider) { _, _ ->
            snapshot(provider) { record, _, _ ->
                val choice = record.choice ?: throw ProviderCodec.Refusal("state")
                choice to record.grant?.takeIf { grant ->
                    !record.suspended && now().seconds < grant.expiresAt &&
                        grant.request.serviceId == choice.service && grant.request.serverId == choice.server.id
                }
            }
        }
        val seconds = now().seconds
        if (current != null && (seconds < current.renewAt || (!renew && seconds < current.expiresAt - CONNECT_MARGIN_SECONDS))) {
            return Prepared(current, null)
        }
        return try {
            Prepared(issue(provider, catalog(provider), choice.service, choice.server.id, openingCode), null)
        } catch (refused: ProviderCodec.Refusal) {
            if (current == null || refused.code !in RENEWAL_TRANSIENT || now().seconds >= current.expiresAt) throw refused
            Prepared(current, refused.code)
        }
    }

    /** The chosen server's grant, unless the trusted time last saved with it is past its expiry. */
    private suspend fun heldGrant(provider: String): ProviderCodec.Grant? = operation(provider) { _, _ ->
        snapshot(provider, timed = false) { record, _, _ ->
            val choice = record.choice ?: throw ProviderCodec.Refusal("state")
            record.grant?.takeIf { grant ->
                !record.suspended && record.savedAt < grant.expiresAt &&
                    grant.request.serviceId == choice.service && grant.request.serverId == choice.server.id
            }
        }
    }

    /**
     * Issues and stores a fresh grant for one server. Earlier grants stay usable
     * until this one's kit is validated and stored, and are then retired for
     * [releaseRetired]. A failure leaves the earlier grant in place.
     */
    private suspend fun issue(provider: String, selected: ProviderCodec.Catalog, service: String, server: String,
                              openingCode: suspend (ProviderCodec.Grant) -> CharArray): ProviderCodec.Grant =
        operation(provider) { lane, generation ->
            snapshot(provider) { record, epoch, bundles ->
                revalidate(record, epoch, bundles, lane, generation)
                // An unanswered request for this server is repeated exactly, so the
                // provider returns its grant rather than issuing another.
                val request = record.pendingRequest?.takeIf { it.serviceId == service && it.serverId == server && it.previousGrant == null }
                    ?: ProviderCodec.GrantRequest(provider, record.context.deviceId, ProviderAuthorization.randomIdentifier(),
                        service, server, record.context.role, null)
                val edge = ProviderCodec.request(request, selected, record.context, now().seconds)
                // Each request below can learn that the sign-in ended.
                suspend fun <T> account(call: suspend () -> T): T = signedIn(record, epoch, lane, generation, call)
                val credential = credential(record, epoch, bundles, lane, generation)
                // A lost response must be retried with the same body, even after a restart.
                if (record.pendingRequest != request) {
                    record.pendingRequest = request
                    persist(record, epoch, bundles, lane, generation)
                }
                // A definitive answer ends this request. Anything else keeps it for an explicit retry.
                suspend fun <T> answered(issued: ProviderCodec.Grant?, call: suspend () -> T): T = try { call() }
                    catch (refused: ProviderCodec.Refusal) {
                        if (refused.code in DEFINITIVE && record.pendingRequest == request) {
                            record.pendingRequest = null
                            issued?.let { retire(record, it.grantId, it.expiresAt) }
                            persist(record, epoch, bundles, lane, generation)
                        }
                        throw refused
                    }
                val grant = answered(null) {
                    val body = ProviderCodec.encode(request.fields())
                    val raw = try { account { api(credential, "${record.context.audience}/grants", body, ProviderCodec.METADATA_LIMIT) } }
                        finally { body.fill(0) }
                    try { ProviderCodec.grant(raw, request, null, now().seconds) } finally { raw.fill(0) }
                }
                // The provider's answers about this grant can end it. A local validation
                // failure, such as a mistyped opening code, keeps it for a corrected retry.
                val sealed = answered(grant) { account { api(credential, "${record.context.audience}/grants/${grant.grantId}/kit", null,
                    grant.kitSize, "application/octet-stream", record.context.deviceId) } }
                try {
                    val code = if (ProviderCodec.DEVICE_BOUND in record.descriptor.required) {
                        answered(grant) { account { deliveredCode(record, credential, grant) } }
                    } else openingCode(grant)
                    try {
                        kits.validate(sealed, code, grant, edge).use { kit ->
                            if (now().seconds >= grant.expiresAt) throw ProviderCodec.Refusal("stale")
                            record.grant?.takeIf { it.grantId != grant.grantId }?.let { retire(record, it.grantId, it.expiresAt) }
                            record.grant = grant
                            record.server = edge
                            // Refresh the choice's catalog data, unless the person chose another server meanwhile.
                            if (record.choice?.service == service && record.choice?.server?.id == server) record.choice = Choice(service, edge)
                            record.suspended = false
                            record.pendingRequest = null
                            persist(record, epoch, mapOf(grant.grantId to kit.bundle), lane, generation)
                            transportDeadlines[provider] = grant.grantId to
                                (now().elapsedMillis + (grant.expiresAt - now().seconds) * 1000)
                        }
                    } finally { code.fill('\u0000') }
                } finally { sealed.fill(0) }
                grant
            }
        }

    /**
     * Revokes retired grants that have not expired. One the provider confirms,
     * or answers as already ended, is forgotten. One that cannot be reached
     * stays for a later attempt until its own expiry closes it anyway.
     */
    suspend fun releaseRetired(provider: String): Boolean {
        requireTime(provider)
        return operation(provider) { lane, generation ->
            snapshot(provider) { record, epoch, bundles ->
                val current = now().seconds
                record.retired.removeAll { it.expiresAt <= current }
                if (record.retired.isEmpty()) return@snapshot true
                val credential = try { credential(record, epoch, bundles, lane, generation) }
                    catch (refused: ProviderCodec.Refusal) {
                        // A provider closes a sign-in's grants when it ends that sign-in.
                        if (refused.code == "unauthorized") record.retired.clear()
                        persist(record, epoch, bundles, lane, generation)
                        return@snapshot record.retired.isEmpty()
                    }
                val closed = withTimeoutOrNull(RELEASE_MILLIS) {
                    record.retired.filter { retired ->
                        val fields = base(record) + mapOf("request_id" to ProviderAuthorization.randomIdentifier(), "grant_id" to retired.grantId)
                        val body = ProviderCodec.encode(fields)
                        try {
                            val reply = api(credential, "${record.context.audience}/revoke", body)
                            try { ProviderCodec.decode(reply) == fields + ("status" to "revoked") } finally { reply.fill(0) }
                        } catch (refused: ProviderCodec.Refusal) { refused.code == "revoked" }
                        finally { body.fill(0) }
                    }.map { it.grantId }.toSet()
                }.orEmpty()
                record.retired.removeAll { it.grantId in closed }
                persist(record, epoch, bundles, lane, generation)
                record.retired.isEmpty()
            }
        }
    }

    private fun retire(record: Record, grantId: String, expiresAt: Long) {
        if (record.retired.none { it.grantId == grantId }) record.retired += Retired(grantId, expiresAt)
        // The provider's own expiry ends the oldest ones if a long outage fills the list.
        while (record.retired.size > MAX_RETIRED) record.retired.removeAt(0)
    }

    /**
     * Explicit acquisition rechecks a lease. The consumer must stop at its returned
     * deadline. Without trusted time the last trusted time saved with the grant
     * stands for the present, which can only make the deadline late, and the node
     * enforces the grant's expiry itself.
     */
    suspend fun lease(provider: String): Lease = operation(provider) { lane, generation ->
        snapshot(provider, timed = false) { record, epoch, bundles ->
            val current = reading()
            val seconds = current?.seconds ?: record.savedAt
            val elapsed = current?.elapsedMillis ?: clock.elapsedMillis()
            val grant = record.grant ?: throw ProviderCodec.Refusal("state")
            val deadline = transportDeadlines.compute(provider) { _, previous ->
                if (previous?.first == grant.grantId) previous else grant.grantId to
                    (elapsed + maxOf(0, grant.expiresAt - seconds) * 1000)
            }!!
            if (record.suspended || seconds >= grant.expiresAt || elapsed >= deadline.second) {
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
                          val authorized: Boolean = false, val choice: Choice? = null)

    fun enrollments(): List<Enrollment> = store.origins().map { provider ->
        val epoch = store.epoch(provider) ?: throw ProviderCodec.Refusal("storage")
        val snapshot = store.read(provider, epoch) ?: throw ProviderCodec.Refusal("storage")
        snapshot.use {
            val record = decode(it.metadata)
            if (record.context.provider != provider) throw ProviderCodec.Refusal("binding")
            Enrollment(provider, epoch.value, record.descriptor.label, record.context.acceptedIssuer,
                record.grant, record.server, record.suspended,
                if (record.descriptor.auth == null) record.deviceCredential != null else record.account != null,
                record.choice)
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
            // A browser-authorized token without its device key cannot authenticate,
            // and nothing is proven to the provider without trusted time. Removal
            // never waits for the provider's lane, so this runs outside it.
            val timed = try { establishTime(provider); true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            val credential = token?.takeIf { timed && (account == null || key != null) }?.let { Credential(ProviderBearer(provider, it), key) }
            val live = listOfNotNull(record.grant?.grantId) + record.retired.map { it.grantId }
            if (credential != null) live.forEach { grantId ->
                val fields = base(record) + mapOf("request_id" to ProviderAuthorization.randomIdentifier(), "grant_id" to grantId)
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
            } else if (live.isNotEmpty()) confirmed = false
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

    private fun descriptorEndpoint(provider: String) = "$provider/.well-known/yume-provider"

    private suspend fun descriptor(context: ProviderCodec.Context): ProviderCodec.Descriptor {
        val raw = http.request(context.provider, descriptorEndpoint(context.provider))
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
     * account and its grants are all forgotten and their connections stop. The
     * enrollment then reads signed out. Its chosen server stays, and after
     * signing in again the next [prepare] issues a fresh grant for it.
     */
    private suspend fun <T> signedIn(record: Record, epoch: ProviderStore.Epoch, lane: Lane, generation: Long,
                                     request: suspend () -> T): T = try { request() }
        catch (refused: ProviderCodec.Refusal) {
            if (refused.code == "unauthorized" && record.descriptor.auth != null) {
                record.account = null
                record.grant = null
                record.server = null
                record.pendingRequest = null
                record.retired.clear()
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
            } catch (refused: ProviderCodec.Refusal) {
                // A refresh that never reached the issuer left its single-use token unspent.
                if (refused.unsent) {
                    record.account = account
                    persist(record, epoch, bundles, lane, generation)
                }
                throw refused
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

    /** [timed] false admits a read without trusted time. A rollback is refused either way. */
    private suspend fun <T> snapshot(provider: String, timed: Boolean = true,
                                     block: suspend (Record, ProviderStore.Epoch, Map<String, ByteArray>) -> T): T {
        val epoch = store.epoch(provider) ?: throw ProviderCodec.Refusal("state")
        val snapshot = store.read(provider, epoch) ?: throw ProviderCodec.Refusal("state")
        snapshot.use {
            val record = decode(snapshot.metadata)
            val current = if (timed) now() else reading()
            if (record.context.provider != provider || (current != null && current.seconds < record.savedAt)) {
                throw ProviderCodec.Refusal("stale")
            }
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
            .put("server", record.server?.let(::serverJson) ?: JSONObject.NULL)
            .put("pending", record.pendingRequest?.let { JSONObject(it.fields()) } ?: JSONObject.NULL)
            .put("choice", record.choice?.let { choice -> JSONObject().put("service", choice.service)
                .put("server", serverJson(choice.server)) } ?: JSONObject.NULL)
            .put("retired", JSONArray(record.retired.map { JSONObject().put("grant", it.grantId).put("expires", it.expiresAt) }))
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
        }
        json.optJSONArray("retired")?.let { retired ->
            for (index in 0 until minOf(retired.length(), MAX_RETIRED)) {
                val entry = retired.getJSONObject(index)
                record.retired += Retired(ProviderCodec.identifier(entry.getString("grant")), entry.getLong("expires"))
            }
        }
        if (!json.isNull("grant")) {
            val g = json.getJSONObject("grant")
            val request = ProviderCodec.GrantRequest(context.provider, context.deviceId, g.getString("request_id"),
                g.getString("service_id"), g.getString("server_id"), context.role,
                if (g.isNull("previous_grant")) null else g.getString("previous_grant"))
            record.grant = ProviderCodec.Grant(request, g.getString("grant_id"), g.getLong("generation"), g.getLong("issued_at"),
                g.getLong("refresh_after"), g.getLong("expires_at"), g.getString("client_identity"), g.getString("kit_sha256"), g.getInt("kit_size"))
        }
        json.optJSONObject("server")?.let { record.server = server(it) }
        record.choice = json.optJSONObject("choice")?.let { Choice(it.getString("service"), server(it.getJSONObject("server"))) }
            // A record installed before choices existed chose its installed server.
            ?: record.grant?.let { grant -> record.server?.let { Choice(grant.request.serviceId, it) } }
        return record
    }

    private fun serverJson(server: ProviderCodec.Server): JSONObject = JSONObject().put("id", server.id)
        .put("label", server.label).put("host", server.host).put("port", server.port)
        .put("key", server.serverKeySha256).put("country", server.country ?: JSONObject.NULL)

    private fun server(json: JSONObject) = ProviderCodec.Server(json.getString("id"), json.getString("label"),
        json.getString("host"), json.getInt("port"), json.getString("key"), if (json.isNull("country")) null else json.getString("country"))

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
        /** Answers that end a grant request: repeating its body cannot succeed. */
        val DEFINITIVE = setOf("revoked", "not_entitled", "binding", "malformed", "unsupported")
        /** Renewal failures that leave a live grant worth keeping until the next attempt. */
        val RENEWAL_TRANSIENT = setOf("unavailable", "rate_limited", "conflict", "stale", "clock_unavailable")
        /** Failures to establish trusted time after which a connection uses its held grant. */
        val UNTIMED = setOf("unavailable", "rate_limited", "clock_unavailable")
        /** Retired grants waiting for revocation. Each expires within an hour regardless. */
        const val MAX_RETIRED = 8
        const val RELEASE_MILLIS = 15_000L
        /** A connection that starts on a grant needs at least this long before it ends. */
        const val CONNECT_MARGIN_SECONDS = 120L
    }
}
