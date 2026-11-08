package com.fixcraft.yume.consumer

import android.content.Context
import android.content.Intent
import com.fixcraft.yume.provider.AndroidProviderClock
import com.fixcraft.yume.provider.ProviderCallbacks
import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderClock
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.provider.ProviderRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Presentation data is separate from the provider client's authenticated vault
 * records. [authorized] means local API credentials exist. [server] is the
 * installed server's catalog entry, for display only. From [renewAfter] (UTC
 * seconds), installing the same server again renews its grant. Before it,
 * that install is refused as `stale`.
 */
data class ServiceEntry(val provider: String, val label: String, val issuer: String?,
                        val selection: ServiceSelection?, val expiresAt: Long?, val suspended: Boolean,
                        val authorized: Boolean = false, val server: ProviderCodec.Server? = null,
                        val renewAfter: Long? = null)
data class CodePrompt(val id: String, val provider: String, val grant: String)
data class ServiceUiState(
    val entries: List<ServiceEntry> = emptyList(),
    val catalogs: Map<String, ProviderCodec.Catalog> = emptyMap(),
    val selection: ServiceSelection? = null,
    val manual: Boolean = false,
    val loaded: Boolean = false,
    val busy: Set<String> = emptySet(),
    val authorizing: String? = null,
    val codePrompt: CodePrompt? = null,
    val error: String? = null,
    val removed: Boolean = false,
    val browserRegistered: Boolean = false,
)

/**
 * One process owner. Consumer adapters retain application context only. Closes client/store/callback
 * registration on explicit teardown, and never persists a PKCE attempt or opening code.
 * Android may kill the process without any teardown callback.
 */
class ServiceController<S : AutoCloseable>(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val registration: ProviderRegistration?,
    private val role: String,
    private val adapter: ServiceAdapter<S>,
    private val clock: ProviderClock = AndroidProviderClock,
    private val createClient: (ProviderClient.Connections) -> ProviderClient,
) : AutoCloseable {
    init { require(role in setOf("vpn", "relay")) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Any()
    private val authority = Any()
    private val selectionLock = Any()
    // Finish cancellation before the next lease captures its client generation.
    private val selectionLane = Mutex()
    private val jobsLock = Any()
    private val selectionGeneration = AtomicLong()
    @Volatile private var closed = false
    private var client: ProviderClient? = null
    private val revisions = mutableMapOf<String, Long>()
    private val permits = mutableSetOf<RunPermit>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val codes = ConcurrentHashMap<String, CodeSlot>()
    private val codePromptLock = Mutex()
    private val browserLane = Mutex()
    // Guarded by lifecycle, including cancellation between client return and UI publication.
    private var browserProvider: String? = null
    private var browserGeneration = 0L
    private var browserJob: Job? = null
    private val stateFlow = MutableStateFlow(ServiceUiState(browserRegistered = registration != null))
    val state = stateFlow.asStateFlow()
    private val callbacks = ProviderCallbacks.attach { uri ->
        perform("callback") {
            browserLane.withLock {
                val owner = currentCoroutineContext()[Job]
                val attempt = synchronized(lifecycle) {
                    browserProvider?.let { provider ->
                        browserJob = owner
                        provider to browserGeneration
                    }
                }
                try { client().callback(uri) }
                finally { attempt?.let { (provider, revision) -> finishBrowser(provider, revision) } }
                reloadNow()
                // A completed sign-in continues to the entitled server list.
                attempt?.let { (provider, _) -> loadCatalog(provider) }
            }
        }
    }

    private fun client(): ProviderClient = synchronized(lifecycle) {
        check(!closed)
        client ?: run {
            val connections = ProviderClient.Connections(::invalidate)
            val value = createClient(connections)
            value.also { client = it }
        }
    }

    fun reload() = perform("local") { reloadNow() }

    /** Always-on startup reads the stored authenticated choice. A locked/corrupt
     * provider choice must never become a manual fallback, and one whose provider
     * was removed is returned as stored so that [open] refuses it. */
    fun selectedForRestart(): ServiceSelection? = synchronized(selectionLock) {
        if (!adapter.ensureReady()) throw ProviderCodec.Refusal("storage")
        readSelection()
    }

    private fun readSelection(): ServiceSelection? {
        val raw = adapter.readSelection()
        return try {
            if (raw == null || raw.contentEquals(MANUAL)) null
            else ServiceSelection.decode(String(raw, Charsets.UTF_8))
        } finally { raw?.fill(0) }
    }

    private fun reloadNow() {
        val revision = selectionGeneration.get()
        if (!adapter.ensureReady()) throw ProviderCodec.Refusal("storage")
        val enrollments = client().enrollments()
        val entries = enrollments.map { entry ->
            ServiceEntry(entry.provider, entry.label, entry.issuer,
                if (entry.grant != null && entry.server != null) ServiceSelection.from(entry) else null,
                entry.grant?.expiresAt, entry.suspended, entry.authorized, entry.server, entry.grant?.refreshAfter)
        }
        synchronized(selectionLock) {
            if (selectionGeneration.get() != revision) return
            val stored = readSelection()
            // A choice whose provider was removed, or whose grant was replaced or
            // forgotten, names no usable route. It stays stored, so restart still
            // refuses it, but it is neither shown as the route nor turned into
            // manual use. The user chooses again.
            val live = stored?.takeIf { selection ->
                enrollments.any { it.provider == selection.provider && it.epoch == selection.epoch &&
                    it.grant?.grantId == selection.grant }
            }
            stateFlow.update { it.copy(entries = entries, selection = live, manual = stored == null, loaded = true) }
        }
    }

    /** A failed step may already have saved a change, such as a refused sign-in. Show it. */
    private fun reloadAfterFailure() {
        try { reloadNow() } catch (_: Exception) { /* The step's own error is already published. */ }
    }

    fun add(provider: String, issuer: String?) = perform(provider) {
        client().add(provider, issuer, role)
        reloadNow()
    }

    suspend fun authorize(provider: String): Intent = browserLane.withLock {
        var revision: Long? = null
        try {
            if (registration == null) throw ProviderCodec.Refusal("unsupported")
            ProviderCodec.origin(provider)
            val owner = currentCoroutineContext()[Job]
            val admitted = synchronized(lifecycle) {
                check(!closed)
                if (browserProvider != null) throw ProviderCodec.Refusal("state")
                browserProvider = provider
                browserJob = owner
                ++browserGeneration
            }
            revision = admitted
            val intent = client().beginAuthorization(provider)
            synchronized(lifecycle) {
                if (closed || browserProvider != provider || browserGeneration != admitted)
                    throw ProviderCodec.Refusal("cancelled")
                browserJob = null
                stateFlow.update { it.copy(authorizing = provider, error = null) }
            }
            intent
        } catch (error: Throwable) {
            revision?.let { finishBrowser(provider, it) }
            if (error is ProviderCodec.Refusal) stateFlow.update { it.copy(error = error.code) }
            throw error
        }
    }

    private fun finishBrowser(provider: String, revision: Long) = synchronized(lifecycle) {
        if (browserProvider != provider || browserGeneration != revision) return@synchronized
        browserProvider = null
        browserJob = null
        client?.cancel(provider)
        stateFlow.update { if (it.authorizing == provider) it.copy(authorizing = null) else it }
    }

    fun browserLaunchFailed(provider: String) {
        cancel(provider)
        stateFlow.update { it.copy(error = "unavailable") }
    }

    fun catalog(provider: String) = perform(provider) { loadCatalog(provider) }

    private suspend fun loadCatalog(provider: String) {
        val catalog = client().catalog(provider)
        stateFlow.update { it.copy(catalogs = it.catalogs + (provider to catalog)) }
        reloadNow()
    }

    fun install(provider: String, catalog: ProviderCodec.Catalog, service: String, server: String) {
        val revision = selectionGeneration.incrementAndGet()
        perform(provider) {
            client().install(provider, catalog, service, server) { grant ->
                codePromptLock.withLock {
                    val prompt = CodePrompt(UUID.randomUUID().toString(), provider, grant.grantId)
                    val slot = CodeSlot()
                    check(codes.putIfAbsent(prompt.id, slot) == null)
                    try {
                        stateFlow.update { it.copy(codePrompt = prompt) }
                        withTimeout(300_000) { slot.signal.await() }
                        slot.take() ?: throw ProviderCodec.Refusal("cancelled")
                    } finally {
                        codes.remove(prompt.id, slot)
                        slot.close()
                        stateFlow.update { if (it.codePrompt?.id == prompt.id) it.copy(codePrompt = null) else it }
                    }
                }
            }
            reloadNow()
            val selected = state.value.entries.single { it.provider == provider }.selection
                ?: throw ProviderCodec.Refusal("state")
            selectNow(selected, revision)
        }
    }

    fun submitCode(id: String, code: CharArray) {
        val slot = codes[id]
        if (slot == null) code.fill('\u0000') else slot.submit(code)
    }

    fun cancel(provider: String) {
        synchronized(lifecycle) {
            if (browserProvider == provider) {
                browserProvider = null
                browserGeneration++
                browserJob?.cancel()
                browserJob = null
            }
            client?.cancel(provider)
            stateFlow.update { if (it.authorizing == provider) it.copy(authorizing = null) else it }
        }
        jobs[provider]?.cancel()
    }

    fun remove(provider: String) {
        // Invalidate before even waiting for vault/network work. remove does not wait
        // on a provisioning lane or user prompt, so it also works during installation.
        invalidate(provider)
        selectionGeneration.incrementAndGet()
        cancel(provider)
        perform("remove:$provider") {
            client().remove(provider)
            stateFlow.update { it.copy(catalogs = it.catalogs - provider, removed = true) }
            reloadNow()
        }
    }

    fun select(selection: ServiceSelection) = synchronized(selectionLock) {
        val revision = selectionGeneration.incrementAndGet()
        perform("selection", replace = true) {
            selectionLane.withLock { selectNow(selection, revision) }
        }
    }

    private suspend fun selectNow(selection: ServiceSelection, revision: Long) {
        client().lease(selection.provider).use { if (!selection.matches(it, role)) throw ProviderCodec.Refusal("binding") }
        synchronized(selectionLock) {
            if (selectionGeneration.get() != revision) throw ProviderCodec.Refusal("cancelled")
            saveSelection(selection.encode().toByteArray(Charsets.UTF_8))
            stateFlow.update { it.copy(selection = selection, manual = false, loaded = true) }
        }
    }

    /** Called after an explicit manual selection/import. Never changes settings. */
    fun selectManual() = synchronized(selectionLock) {
        selectionGeneration.incrementAndGet()
        saveSelection(MANUAL.copyOf())
        stateFlow.update { it.copy(selection = null, manual = true, loaded = true, error = null) }
    }

    private fun saveSelection(bytes: ByteArray) {
        try {
            if (!adapter.ensureReady()) throw ProviderCodec.Refusal("storage")
            adapter.writeSelection(bytes)
        } finally { bytes.fill(0) }
    }

    /** Captures authority independently of later UI selection. No manual-store copy. */
    suspend fun open(selection: ServiceSelection): OpenedService {
        val revision = synchronized(authority) { revisions[selection.provider] ?: 0 }
        val lease = client().lease(selection.provider)
        try {
            if (!selection.matches(lease, role)) throw ProviderCodec.Refusal("binding")
            val permit = RunPermit(selection.provider, LeaseDeadline(lease.expiresAt, lease.elapsedDeadline))
            synchronized(authority) {
                if (closed || (revisions[selection.provider] ?: 0) != revision) throw ProviderCodec.Refusal("cancelled")
                permits += permit
            }
            try {
                if (!permit.valid()) throw ProviderCodec.Refusal("stale")
                val session = adapter.openSession(lease.bundle)
                try {
                    if (!permit.valid()) throw ProviderCodec.Refusal("revoked")
                    return OpenedService(session, permit)
                } catch (error: Throwable) { session.close(); throw error }
            } catch (error: Throwable) { permit.close(); throw error }
        } finally { lease.close() }
    }

    inner class RunPermit internal constructor(val provider: String, private val deadline: LeaseDeadline) : AutoCloseable {
        private val blocked = AtomicBoolean(false)
        fun valid(): Boolean {
            val reading = clock.now()
            return !blocked.get() && deadline.valid(reading?.seconds, reading?.elapsedMillis ?: android.os.SystemClock.elapsedRealtime()) &&
                adapter.isUnlocked()
        }
        fun remainingMillis(): Long = maxOf(0, deadline.elapsedDeadline - android.os.SystemClock.elapsedRealtime())
        internal fun invalidate() { blocked.set(true) }
        override fun close() { invalidate(); synchronized(authority) { permits.remove(this) } }
    }
    inner class OpenedService(val session: S, val permit: RunPermit) : AutoCloseable {
        override fun close() { try { session.close() } finally { permit.close() } }
    }

    private fun invalidate(provider: String) = synchronized(authority) {
        revisions[provider] = (revisions[provider] ?: 0) + 1
        permits.filter { it.provider == provider }.forEach { it.invalidate() }
    }

    private fun perform(key: String, replace: Boolean = false, action: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]!!
            synchronized(jobsLock) {
                if (jobs[key] !== owner) return@launch
                stateFlow.update { it.copy(busy = it.busy + key, error = null, removed = false) }
            }
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (refused: ProviderCodec.Refusal) {
                synchronized(jobsLock) {
                    if (jobs[key] === owner) stateFlow.update { it.copy(error = refused.code) }
                }
                reloadAfterFailure()
            }
            catch (_: Exception) {
                synchronized(jobsLock) {
                    if (jobs[key] === owner) stateFlow.update { it.copy(error = "storage") }
                }
                reloadAfterFailure()
            }
            finally {
                synchronized(jobsLock) {
                    if (jobs.remove(key, owner)) stateFlow.update { it.copy(busy = it.busy - key) }
                }
            }
        }
        synchronized(jobsLock) {
            if (replace) {
                jobs.put(key, job)?.cancel()
                job.start()
            } else if (jobs.putIfAbsent(key, job) == null) job.start() else job.cancel()
        }
    }

    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            callbacks.close()
            browserProvider = null
            browserGeneration++
            browserJob?.cancel()
            browserJob = null
            scope.cancel()
            codes.values.forEach { it.close() }
            codes.clear()
            synchronized(authority) { permits.forEach { it.invalidate() }; permits.clear() }
            client?.close()
            client = null
        }
    }

    private class CodeSlot : AutoCloseable {
        val signal = CompletableDeferred<Unit>()
        private var code: CharArray? = null
        private var closed = false
        @Synchronized fun submit(value: CharArray) {
            if (closed || code != null || value.size !in 25..64) { value.fill('\u0000'); return }
            code = value
            signal.complete(Unit)
        }
        @Synchronized fun take(): CharArray? = code.also { code = null; closed = true }
        @Synchronized override fun close() { closed = true; code?.fill('\u0000'); code = null; signal.cancel() }
    }

    companion object {
        private val MANUAL = "manual".toByteArray(Charsets.US_ASCII)
    }
}
