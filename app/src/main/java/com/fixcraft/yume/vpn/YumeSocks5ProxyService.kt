package com.fixcraft.yume.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fixcraft.yume.MainActivity
import com.fixcraft.yume.R
import com.fixcraft.yume.data.AppSettingsStore
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.ServerProfileStore
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.data.Socks5BindMode
import com.fixcraft.yume.data.Socks5ProxySettings
import com.fixcraft.yume.i18n.AppLocaleManager
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.transport.YumeBootstrapClient
import com.fixcraft.yume.transport.YumeBootstrapSession
import com.fixcraft.yume.transport.YumeBootstrapSessionPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class YumeSocks5ProxyService : Service() {
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        val message = throwable.message ?: throwable.javaClass.simpleName
        YumeVpnRuntime.appendLog(VpnLogLevel.Error, "Unhandled SOCKS5 proxy coroutine failure: $message")
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceExceptionHandler)
    private val profileStore by lazy { ServerProfileStore.get(applicationContext) }
    private val appSettingsStore by lazy { AppSettingsStore.get(applicationContext) }
    private val secureMaterialStore by lazy { SecureMaterialStore.get(applicationContext) }
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private fun strings() = appStrings(this, appSettingsStore.settings.value.language)

    private var connectJob: Job? = null
    private var disconnectJob: Job? = null
    private var bootstrapSession: YumeBootstrapSession? = null
    private var bootstrapSessionPool: YumeBootstrapSessionPool? = null
    private var socksProxy: YumeSocks5Proxy? = null
    private var trafficTickerJob: Job? = null
    private var activeProfile: ServerProfile? = null
    @Volatile
    private var localProxyAddress: String? = null
    @Volatile
    private var shutdownRequested = false
    @Volatile
    private var inFlightSocket: Socket? = null
    @Volatile
    private var activeRuntimeConfig: Socks5ProxyRuntimeConfig? = null
    @Volatile
    private var lastNotificationRefreshElapsedMs: Long = 0L
    private val lifecycleMutex = Mutex()
    private val sessionGeneration = AtomicLong(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                val authKeyPassword = intent.getStringExtra(EXTRA_AUTH_KEY_PASSWORD)
                if (profileId.isNullOrBlank()) {
                    fail("Missing SOCKS5 profile")
                } else {
                    shutdownRequested = false
                    sessionGeneration.incrementAndGet()
                    connectJob?.cancel()
                    connectJob = serviceScope.launch {
                        connect(profileId, authKeyPassword)
                    }
                }
            }

            ACTION_DISCONNECT -> {
                disconnect("SOCKS5 proxy disconnected")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        connectJob?.cancel()
        connectJob = null
        disconnectJob?.cancel()
        disconnectJob = null
        stopTrafficTicker()
        disconnectResources()
        activeProfile = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun connect(profileId: String, authKeyPassword: String?) {
        val generation = sessionGeneration.get()
        val profile = profileStore.getProfile(profileId)
        if (profile == null) {
            fail("Selected server profile no longer exists")
            return
        }
        val appSettings = appSettingsStore.settings.value
        val proxySettings = appSettings.socks5ProxySettings
        val runtimeConfig = Socks5ProxyTechniqueEngine.runtimeConfig(
            appSettings.performanceProfile,
            proxySettings,
        )
        activeRuntimeConfig = runtimeConfig
        ensureSessionActive(generation)
        activeProfile = profile

        val caSummary = profile.anonymCaMaterialId.takeIf(String::isNotBlank)
            ?.let(secureMaterialStore::getSummary)
        if (profile.requireAnonymProof && caSummary == null) {
            fail("Required operator authorization needs an available operator CA", profile.id)
            return
        }
        if (profile.anonymCaMaterialId.isNotBlank() && caSummary == null) {
            fail("The operator CA configured for this server is unavailable", profile.id)
            return
        }
        if (profile.authKeyMaterialId.isBlank()) {
            fail("This server does not have an auth key assigned yet", profile.id)
            return
        }
        val authKeySummary = secureMaterialStore.getSummary(profile.authKeyMaterialId)
        if (authKeySummary == null) {
            fail("The auth key configured for this server is unavailable", profile.id)
            return
        }
        val credentials = runCatching {
            profile.anonymCaMaterialId.takeIf(String::isNotBlank)
                ?.let(secureMaterialStore::readMaterialText).orEmpty() to
                secureMaterialStore.readAuthKeyPem(profile.authKeyMaterialId, authKeyPassword)
        }.getOrElse { throwable ->
            fail(throwable.message ?: "Failed to load local authentication material", profile.id)
            return
        }
        val anonymCaPem = credentials.first
        val authKeyPem = credentials.second

        YumeVpnRuntime.reset(
            VpnConnectionState(
                phase = VpnPhase.Connecting,
                activeProfileId = profile.id,
                headline = strings().connectingHeadline,
                detail = strings().loadingKeysFor(profile.endpointLabel),
                endpointLabel = profile.endpointLabel,
                sessionDnsServers = profile.dnsServers,
                sessionMtu = profile.mtu,
                upstreamNetworkLabel = describeUpstreamNetwork(),
                veritySummary = caSummary?.let { strings().usingAnonymCa(it.displayName) }
                    ?: "System TLS trust; no operator CA selected",
                routingSummary = runtimeConfig.summary,
            )
        )
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Technique: ${runtimeConfig.summary}.")
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Starting Yume SOCKS5-only session for ${profile.name} (${profile.endpointLabel}).",
        )
        startInForeground(profile, strings().authenticatingNotification)

        lifecycleMutex.withLock {
            disconnectResources()
        }

        try {
            ensureSessionActive(generation)
            val tunnelCount = proxySettings.normalizedTunnelCount
            bootstrapSession = openBootstrapSession(
                profile = profile,
                anonymCaPem = anonymCaPem,
                authKeyPem = authKeyPem,
                reason = "Opening authenticated Yume control path (primary 1/$tunnelCount)",
                runtimeConfig = runtimeConfig,
                generation = generation,
            )
            ensureSessionActive(generation)

            val pool = YumeBootstrapSessionPool()
            pool.add(bootstrapSession!!)
            if (tunnelCount > 1) {
                openAdditionalTunnels(
                    pool = pool,
                    profile = profile,
                    anonymCaPem = anonymCaPem,
                    authKeyPem = authKeyPem,
                    runtimeConfig = runtimeConfig,
                    tunnelCount = tunnelCount,
                    generation = generation,
                )
            }
            bootstrapSessionPool = pool
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "SOCKS5 tunnel pool ready: ${pool.liveCount}/${pool.size} live tunnels.",
            )

            val proxyAddress = startSocksProxy(runtimeConfig)
            localProxyAddress = proxyAddress
            YumeVpnRuntime.update { state ->
                state.copy(
                    phase = VpnPhase.Connected,
                    headline = strings().protectedNotification,
                    detail = "SOCKS5 proxy ready at $proxyAddress",
                    localProxyAddress = proxyAddress,
                    trafficRoutedThroughYume = false,
                    upstreamNetworkLabel = describeUpstreamNetwork(),
                )
            }
            updateForeground(profile, "SOCKS5 proxy ready at $proxyAddress")
            startTrafficTicker()
            var nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
            var consecutivePingFailures = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                ensureSessionActive(generation)
                delay(1_500)
                val liveSession = bootstrapSession
                if (liveSession == null || !liveSession.isAlive) {
                    val closeDetail = liveSession?.closeReason?.takeIf { it.isNotBlank() } ?: "unknown reason"
                    YumeVpnRuntime.appendLog(
                        VpnLogLevel.Warn,
                        "Authenticated Yume control path lost ($closeDetail). Rebuilding the tunnel pool.",
                    )
                    rebuildPool(
                        profile = profile,
                        anonymCaPem = anonymCaPem,
                        authKeyPem = authKeyPem,
                        runtimeConfig = runtimeConfig,
                        tunnelCount = proxySettings.normalizedTunnelCount,
                        reason = "primary lost ($closeDetail)",
                        generation = generation,
                    )
                    ensureSessionActive(generation)
                    nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
                    consecutivePingFailures = 0
                    continue
                }
                if (System.currentTimeMillis() >= nextPingAtMs) {
                    try {
                        liveSession.ping(timeoutMs = PRE_ROUTING_PING_TIMEOUT_MS)
                        consecutivePingFailures = 0
                    } catch (ex: Exception) {
                        consecutivePingFailures += 1
                        val pingDetail = ex.message ?: "connection lost"
                        if (consecutivePingFailures < KEEPALIVE_PING_FAILURES_BEFORE_RECONNECT) {
                            YumeVpnRuntime.appendLog(
                                VpnLogLevel.Warn,
                                "Yume PING failed ($consecutivePingFailures/$KEEPALIVE_PING_FAILURES_BEFORE_RECONNECT): " +
                                    "$pingDetail. Will retry before re-authenticating.",
                            )
                            nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
                            continue
                        }
                        YumeVpnRuntime.appendLog(
                            VpnLogLevel.Warn,
                            "Yume PING failed $consecutivePingFailures times in a row: $pingDetail. Rebuilding the tunnel pool.",
                        )
                        rebuildPool(
                            profile = profile,
                            anonymCaPem = anonymCaPem,
                            authKeyPem = authKeyPem,
                            runtimeConfig = runtimeConfig,
                            tunnelCount = proxySettings.normalizedTunnelCount,
                            reason = "keepalive failed",
                            generation = generation,
                        )
                        ensureSessionActive(generation)
                        consecutivePingFailures = 0
                    }
                    nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
                }
                maybeRefreshForeground(profile)
                YumeVpnRuntime.update { state ->
                    state.copy(upstreamNetworkLabel = describeUpstreamNetwork())
                }
            }
        } catch (_: CancellationException) {
            if (!shutdownRequested && generation == sessionGeneration.get()) {
                lifecycleMutex.withLock {
                    disconnectResources()
                }
            }
        } catch (ex: Exception) {
            if (shutdownRequested || generation != sessionGeneration.get()) {
                disconnectResources()
                return
            }
            fail(ex.message ?: "SOCKS5 proxy startup failed", profileId)
        }
    }

    private suspend fun openBootstrapSession(
        profile: ServerProfile,
        anonymCaPem: String,
        authKeyPem: String,
        reason: String,
        runtimeConfig: Socks5ProxyRuntimeConfig,
        generation: Long,
    ): YumeBootstrapSession {
        ensureSessionActive(generation)
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "$reason on ${describeUpstreamNetwork()}.")
        val session = try {
            YumeBootstrapClient.connect(
                appContext = this,
                profile = profile,
                anonymCaPem = anonymCaPem,
                authKeyPem = authKeyPem,
                rawSocketFactory = {
                    ensureSessionActive(generation)
                    // No `protect()`: this service does not own a VPN
                    // interface, so the platform routes the control
                    // socket as ordinary app traffic.
                    val socket = Socket().apply {
                        keepAlive = true
                        tcpNoDelay = true
                    }
                    inFlightSocket = socket
                    socket.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = CONNECT_TIMEOUT_MS
                    socket
                },
                requireAnonymProof = profile.requireAnonymProof,
                transportTuning = runtimeConfig.toBootstrapTuning(),
                log = { message -> YumeVpnRuntime.appendLog(VpnLogLevel.Info, message) },
            )
        } finally {
            inFlightSocket = null
        }
        ensureSessionActive(generation)
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Authenticated control path is active over ${describeUpstreamNetwork()} (${session.bootstrap.tlsProtocol}).",
        )
        return session
    }

    private suspend fun rebuildPool(
        profile: ServerProfile,
        anonymCaPem: String,
        authKeyPem: String,
        runtimeConfig: Socks5ProxyRuntimeConfig,
        tunnelCount: Int,
        reason: String,
        generation: Long,
    ) {
        // Partial rebuild: drop only the dead tunnels, keep the live
        // ones serving traffic, then open enough fresh ones to refill
        // to tunnelCount. The previous "close everything, reopen
        // everything" path triggered 4× simultaneous TCP resets on
        // the server side (one RST per tunnel) and a thundering herd
        // of heavy-Argon2 re-auths that often had at least one fail —
        // and any single failure cascaded into the whole pool being
        // empty for several seconds while reconnect retried. Now an
        // isolated tunnel death only triggers an isolated rebuild.
        val pool = bootstrapSessionPool
        if (pool == null) {
            // First-time path or full disconnect: full rebuild from
            // scratch. This is also the recovery path when the entire
            // pool died at once.
            return rebuildPoolFresh(
                profile, anonymCaPem, authKeyPem,
                runtimeConfig, tunnelCount, reason, generation,
            )
        }
        val dead = pool.removeDead()
        if (dead.isNotEmpty()) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Tunnel pool: dropping ${dead.size} dead tunnel(s) ($reason). ${pool.liveCount}/$tunnelCount remain.",
            )
            for (corpse in dead) {
                runCatching { corpse.close() }
            }
        }
        val missing = tunnelCount - pool.size
        if (missing <= 0) {
            return
        }
        // Open the missing tunnels in parallel. Live tunnels keep
        // serving traffic while these handshakes run; SOCKS5 streams
        // route to whichever tunnel is alive via pickForStream().
        coroutineScope {
            val tasks = (1..missing).map { idx ->
                async {
                    ensureSessionActive(generation)
                    runCatching {
                        openBootstrapSession(
                            profile = profile,
                            anonymCaPem = anonymCaPem,
                            authKeyPem = authKeyPem,
                            reason = "Refilling tunnel pool ($reason) — replacement ${idx}/$missing",
                            runtimeConfig = runtimeConfig,
                            generation = generation,
                        )
                    }
                }
            }
            val results = tasks.awaitAll()
            for ((idx, result) in results.withIndex()) {
                result.fold(
                    onSuccess = { session ->
                        pool.add(session)
                        if (bootstrapSession == null || bootstrapSession?.isAlive != true) {
                            bootstrapSession = session
                        }
                    },
                    onFailure = { throwable ->
                        YumeVpnRuntime.appendLog(
                            VpnLogLevel.Warn,
                            "Tunnel pool refill ${idx + 1}/$missing failed: ${throwable.message ?: throwable.javaClass.simpleName}. " +
                                "Live tunnels continue serving traffic.",
                        )
                    },
                )
            }
        }
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Tunnel pool refilled: ${pool.liveCount}/${pool.size} live tunnels.",
        )
    }

    private suspend fun rebuildPoolFresh(
        profile: ServerProfile,
        anonymCaPem: String,
        authKeyPem: String,
        runtimeConfig: Socks5ProxyRuntimeConfig,
        tunnelCount: Int,
        reason: String,
        generation: Long,
    ) {
        val oldPool = bootstrapSessionPool
        bootstrapSessionPool = null
        bootstrapSession = null
        runCatching { oldPool?.closeAll() }
        val primary = openBootstrapSession(
            profile = profile,
            anonymCaPem = anonymCaPem,
            authKeyPem = authKeyPem,
            reason = "Rebuilding tunnel pool from scratch ($reason) — primary 1/$tunnelCount",
            runtimeConfig = runtimeConfig,
            generation = generation,
        )
        bootstrapSession = primary
        val pool = YumeBootstrapSessionPool()
        pool.add(primary)
        if (tunnelCount > 1) {
            openAdditionalTunnels(
                pool = pool,
                profile = profile,
                anonymCaPem = anonymCaPem,
                authKeyPem = authKeyPem,
                runtimeConfig = runtimeConfig,
                tunnelCount = tunnelCount,
                generation = generation,
            )
        }
        bootstrapSessionPool = pool
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "SOCKS5 tunnel pool rebuilt fresh: ${pool.liveCount}/${pool.size} live tunnels.",
        )
    }

    private suspend fun openAdditionalTunnels(
        pool: YumeBootstrapSessionPool,
        profile: ServerProfile,
        anonymCaPem: String,
        authKeyPem: String,
        runtimeConfig: Socks5ProxyRuntimeConfig,
        tunnelCount: Int,
        generation: Long,
    ) {
        // Open the secondaries in parallel — each TLS handshake takes
        // ~200-500 ms; serialising them would stall connect by N*RTT.
        // We tolerate individual failures: a partial pool (e.g. 3/4
        // live) still gives a multi-tunnel win over the single-tunnel
        // baseline. The primary, already in the pool, is the
        // backstop.
        coroutineScope {
            val tasks = (2..tunnelCount).map { index ->
                async {
                    ensureSessionActive(generation)
                    runCatching {
                        openBootstrapSession(
                            profile = profile,
                            anonymCaPem = anonymCaPem,
                            authKeyPem = authKeyPem,
                            reason = "Opening additional Yume tunnel ($index/$tunnelCount)",
                            runtimeConfig = runtimeConfig,
                            generation = generation,
                        )
                    }
                }
            }
            val results = tasks.awaitAll()
            for ((idx, result) in results.withIndex()) {
                val tunnelLabel = "tunnel ${idx + 2}/$tunnelCount"
                result.fold(
                    onSuccess = { session ->
                        pool.add(session)
                        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Added $tunnelLabel to the pool.")
                    },
                    onFailure = { throwable ->
                        YumeVpnRuntime.appendLog(
                            VpnLogLevel.Warn,
                            "Failed to open $tunnelLabel: ${throwable.message ?: throwable.javaClass.simpleName}. " +
                                "Continuing with whatever tunnels did come up.",
                        )
                    },
                )
            }
        }
    }

    private suspend fun startSocksProxy(runtimeConfig: Socks5ProxyRuntimeConfig): String {
        val proxy = socksProxy ?: YumeSocks5Proxy(
            scope = serviceScope,
            runtimeConfig = runtimeConfig.bridgeTuning,
            // Per-stream pool pick: every CONNECT / UDP-ASSOC pulls a
            // fresh least-loaded session, spreading the SOCKS load
            // across N parallel TLS tunnels. Falls back to the
            // primary if the pool is empty (e.g. very early during
            // connect, before pool is wired).
            sessionProvider = {
                bootstrapSessionPool?.pickForStream() ?: bootstrapSession
            },
            dnsServersProvider = { activeProfile?.dnsServers.orEmpty() },
            ipv4OnlyMode = true,
            forceTcpFallbackUdp443Provider = { true },
            log = { level, message -> YumeVpnRuntime.appendLog(level, message) },
        ).also { socksProxy = it }
        val bindHost = resolveBindHost(runtimeConfig.proxySettings)
        // No fallback to port 0 here: the user picked a specific port to
        // expose to other apps/LAN, so silently moving it would surface
        // as "why does my browser say 'connection refused'?". Fail loudly
        // instead.
        val address = proxy.start(
            preferredPort = runtimeConfig.proxySettings.normalizedPort,
            bindHost = bindHost,
            portFallback = false,
        )
        return "${address.address.hostAddress}:${address.port}"
    }

    private fun resolveBindHost(settings: Socks5ProxySettings): String {
        val customBindAddress = settings.normalizedCustomBindAddress
        if (customBindAddress.isNotBlank()) {
            return customBindAddress
        }
        return when (settings.bindMode) {
            Socks5BindMode.Loopback -> YumeSocks5Proxy.LOOPBACK_BIND_HOST
            Socks5BindMode.Wildcard -> YumeSocks5Proxy.WILDCARD_BIND_HOST
            Socks5BindMode.Wifi -> resolveWifiBindHost() ?: run {
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Warn,
                    "Could not determine a Wi-Fi interface address; binding SOCKS5 proxy to loopback instead.",
                )
                YumeSocks5Proxy.LOOPBACK_BIND_HOST
            }
        }
    }

    private fun resolveWifiBindHost(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            val name = iface.name.orEmpty().lowercase()
            // Common Wi-Fi interface names across Android variants: wlan0, wlan1,
            // p2p-wlan0-0, etc. Skip cellular (rmnet/ccmni) and the VPN tun.
            if (!name.startsWith("wlan")) continue
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    private fun disconnect(message: String) {
        if (disconnectJob?.isActive == true) {
            return
        }
        shutdownRequested = true
        sessionGeneration.incrementAndGet()
        runCatching { inFlightSocket?.close() }
        inFlightSocket = null
        if (connectJob != null || bootstrapSession != null || socksProxy != null) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, message)
            YumeVpnRuntime.update { current ->
                current.copy(
                    phase = VpnPhase.Disconnecting,
                    headline = strings().disconnectingHeadline,
                    detail = message,
                    upstreamNetworkLabel = describeUpstreamNetwork(),
                )
            }
        }
        connectJob?.cancel()

        disconnectJob = serviceScope.launch {
            lifecycleMutex.withLock {
                disconnectResources()
            }
            activeProfile = null
            YumeVpnRuntime.update(
                VpnConnectionState(
                    phase = VpnPhase.Idle,
                    headline = strings().disconnectedHeadline,
                    detail = message,
                    upstreamNetworkLabel = describeUpstreamNetwork(),
                )
            )
            withContext(Dispatchers.Main.immediate) {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                runCatching { stopSelf() }
            }
            disconnectJob = null
        }
    }

    private fun disconnectResources() {
        stopTrafficTicker()
        val session = bootstrapSession
        val pool = bootstrapSessionPool
        bootstrapSession = null
        bootstrapSessionPool = null
        runCatching { socksProxy?.stop() }
        // closeAll() iterates every session in the pool, including the
        // primary that bootstrapSession aliased. Closing twice is
        // safe (close is idempotent on YumeBootstrapSession), so the
        // belt-and-braces session?.close() below covers the case
        // where the pool was never built (early failure).
        runCatching { pool?.closeAll() }
        runCatching { session?.close() }
        runCatching { inFlightSocket?.close() }
        inFlightSocket = null
        socksProxy = null
        localProxyAddress = null
        activeRuntimeConfig = null
    }

    private fun startTrafficTicker() {
        trafficTickerJob?.cancel()
        YumeVpnRuntime.resetTrafficHistory()
        trafficTickerJob = serviceScope.launch {
            while (true) {
                currentCoroutineContext().ensureActive()
                socksProxy?.stats()?.let { stats ->
                    YumeVpnRuntime.pushTrafficSample(stats.rxBytes, stats.txBytes)
                }
                delay(1_000)
            }
        }
    }

    private fun stopTrafficTicker() {
        trafficTickerJob?.cancel()
        trafficTickerJob = null
        YumeVpnRuntime.resetTrafficHistory()
    }

    private fun fail(message: String, profileId: String? = null) {
        YumeVpnRuntime.appendLog(VpnLogLevel.Error, message)
        YumeVpnRuntime.update {
            VpnConnectionState(
                phase = VpnPhase.Error,
                activeProfileId = profileId,
                headline = strings().notificationError,
                detail = message,
            )
        }
        serviceScope.launch {
            lifecycleMutex.withLock {
                disconnectResources()
            }
            withContext(Dispatchers.Main.immediate) {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                runCatching { stopSelf() }
            }
        }
    }

    private fun ensureSessionActive(generation: Long) {
        if (shutdownRequested || generation != sessionGeneration.get()) {
            throw CancellationException("SOCKS5 session replaced or disconnected")
        }
    }

    private fun describeUpstreamNetwork(): String {
        val network: Network = connectivityManager.activeNetwork ?: return strings().unavailable
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return strings().unavailable
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Network"
        }
    }

    private fun startInForeground(profile: ServerProfile, status: String) {
        ensureNotificationChannel()
        val notification = buildNotification(profile, status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForeground(profile: ServerProfile, status: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        lastNotificationRefreshElapsedMs = SystemClock.elapsedRealtime()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(profile, status))
    }

    private fun maybeRefreshForeground(profile: ServerProfile) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastNotificationRefreshElapsedMs < NOTIFICATION_REFRESH_INTERVAL_MS) {
            return
        }
        val proxyLabel = localProxyAddress?.let { "SOCKS5 proxy ready at $it" }
            ?: strings().protectedNotification
        updateForeground(profile, proxyLabel)
    }

    private fun buildNotification(profile: ServerProfile, status: String): Notification {
        val localizedContext = AppLocaleManager.current(this)
        val text = strings()
        val bindLabel = activeRuntimeConfig?.proxySettings?.let { settings ->
            settings.normalizedCustomBindAddress.ifBlank {
                when (settings.bindMode) {
                    Socks5BindMode.Loopback -> "127.0.0.1"
                    Socks5BindMode.Wifi -> text.socks5BindWifiLabel
                    Socks5BindMode.Wildcard -> "0.0.0.0"
                }
            }
        } ?: "127.0.0.1"
        val expandedText = buildList {
            add(status)
            localProxyAddress?.let { add("SOCKS5: $it") }
            add("${text.socks5BindAddressLabel}: $bindLabel")
        }.joinToString("\n")

        val disconnectIntent = Intent(this, YumeSocks5ProxyService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            3,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val configureIntent = PendingIntent.getActivity(
            this,
            4,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(localizedContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(AppLocaleManager.getString(localizedContext, R.string.notification_title, profile.name))
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setShowWhen(false)
            .setContentIntent(configureIntent)
            .addAction(0, AppLocaleManager.getString(localizedContext, R.string.disconnect), disconnectPendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        val localizedContext = AppLocaleManager.current(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            AppLocaleManager.getString(localizedContext, R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = AppLocaleManager.getString(localizedContext, R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_CONNECT = "com.fixcraft.yume.action.SOCKS5_CONNECT"
        private const val ACTION_DISCONNECT = "com.fixcraft.yume.action.SOCKS5_DISCONNECT"
        private const val EXTRA_PROFILE_ID = "extra_profile_id"
        private const val EXTRA_AUTH_KEY_PASSWORD = "extra_auth_key_password"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val KEEPALIVE_PING_INTERVAL_MS = 15_000L
        private const val PRE_ROUTING_PING_TIMEOUT_MS = 6_000
        private const val KEEPALIVE_PING_FAILURES_BEFORE_RECONNECT = 2
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 2_500L
        private const val NOTIFICATION_ID = 1108
        private const val NOTIFICATION_CHANNEL_ID = "yume_socks5_service"

        fun startConnect(context: Context, profileId: String, authKeyPassword: String? = null) {
            val intent = Intent(context, YumeSocks5ProxyService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_AUTH_KEY_PASSWORD, authKeyPassword)
            }
            context.startForegroundService(intent)
        }

        fun startDisconnect(context: Context) {
            val intent = Intent(context, YumeSocks5ProxyService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
