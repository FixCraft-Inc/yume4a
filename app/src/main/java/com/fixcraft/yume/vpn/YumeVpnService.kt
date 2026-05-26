package com.fixcraft.yume.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.MainActivity
import com.fixcraft.yume.R
import com.fixcraft.yume.data.AppSettings
import com.fixcraft.yume.data.AppSettingsStore
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.ServerProfileStore
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.data.canonicalLabel
import com.fixcraft.yume.geo.CountryLookup
import com.fixcraft.yume.i18n.AppLocaleManager
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.tunnel.YumeTunnelBridge
import com.fixcraft.yume.transport.YumeBootstrapClient
import com.fixcraft.yume.transport.YumeBootstrapException
import com.fixcraft.yume.transport.YumeBootstrapSession
import com.fixcraft.yume.transport.YumeNativeTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

class YumeVpnService : VpnService() {
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        val message = throwable.message ?: throwable.javaClass.simpleName
        YumeVpnRuntime.appendLog(VpnLogLevel.Error, "Unhandled VPN coroutine failure: $message")
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceExceptionHandler)
    private val profileStore by lazy { ServerProfileStore.get(applicationContext) }
    private val appSettingsStore by lazy { AppSettingsStore.get(applicationContext) }
    private val secureMaterialStore by lazy { SecureMaterialStore.get(applicationContext) }
    private val tun2SocksBridge by lazy { YumeTunnelBridge() }
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private fun strings() = appStrings(this, appSettingsStore.settings.value.language)

    private var connectJob: Job? = null
    private var disconnectJob: Job? = null
    // Pushes a TrafficSample into YumeVpnRuntime every second while the
    // VPN is up, so the home-screen chart has fresh data without
    // depending on the UI being foregrounded.
    private var trafficTickerJob: Job? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var bootstrapSession: YumeBootstrapSession? = null
    private var socksProxy: YumeSocks5Proxy? = null
    private var nativeTransport: YumeNativeTransport? = null
    private var activeProfile: ServerProfile? = null
    @Volatile
    private var localProxyAddress: String? = null
    @Volatile
    private var upstreamNetwork: Network? = null
    @Volatile
    private var networkCallbackRegistered = false
    @Volatile
    private var lastLoggedUpstreamHandle: Long = Long.MIN_VALUE
    @Volatile
    private var lastLoggedUpstreamLabel: String? = null
    @Volatile
    private var shutdownRequested = false
    @Volatile
    private var inFlightSocket: Socket? = null
    @Volatile
    private var tun2SocksActive = false
    @Volatile
    private var activeTun2SocksConfig: Tun2SocksRuntimeConfig? = null
    @Volatile
    private var activeTechniqueDataPlane: TechniqueDataPlane? = null
    @Volatile
    private var warmupAllowedProbeHosts: Set<String> = emptySet()
    @Volatile
    private var warmupAllowedProbeAddresses: Set<String> = emptySet()
    @Volatile
    private var protectedSinceEpochMs: Long = 0L
    @Volatile
    private var lastNotificationRefreshElapsedMs: Long = 0L
    @Volatile
    private var latestWarmupTrafficObservation: WarmupTrafficObservation? = null
    @Volatile
    private var yumeAppExcludedFromVpnPath = false
    private val lifecycleMutex = Mutex()
    private val sessionGeneration = AtomicLong(0)

    private data class RouteVerificationResult(
        val exitIp: String?,
        val systemTrafficVerified: Boolean,
        val source: String,
    )

    private data class WarmupTrafficObservation(
        val host: String,
        val port: Int,
        val warmupAllowed: Boolean,
        val observedAtElapsedMs: Long,
    )

    private data class ResolvedProbeTarget(
        val host: String,
        val path: String,
        val port: Int,
        val connectHosts: List<String>,
    )

    private data class TechniqueBootstrapResult(
        val dataPlane: TechniqueDataPlane,
        val proxyAddress: String,
    )

    private val upstreamCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleUpstreamUpdate(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            handleUpstreamUpdate(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            if (upstreamNetwork == network) {
                upstreamNetwork = connectivityManager.activeNetwork
                updateUnderlyingNetworks()
                val replacement = upstreamNetwork
                if (replacement == null) {
                    YumeVpnRuntime.appendLog(VpnLogLevel.Warn, "Upstream network lost. Waiting for Wi-Fi or cellular.")
                    runCatching { bootstrapSession?.close() }
                    bootstrapSession = null
                    YumeVpnRuntime.update { state ->
                        if (state.activeProfileId == null || state.phase == VpnPhase.Disconnecting) {
                            state
                        } else {
                            state.copy(
                                phase = VpnPhase.Connecting,
                                headline = strings().upstreamLostHeadline,
                                detail = strings().upstreamLostDetail,
                                upstreamNetworkLabel = strings().unavailable,
                                controlSocketProtected = false,
                                localProxyAddress = this@YumeVpnService.localProxyAddress,
                            )
                        }
                    }
                    activeProfile?.let { profile ->
                        updateForeground(profile, strings().waitingForNetworkHeadline)
                    }
                } else {
                    handleUpstreamUpdate(replacement)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        upstreamNetwork = connectivityManager.activeNetwork
        updateUnderlyingNetworks()
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(upstreamCallback)
            networkCallbackRegistered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                val authKeyPassword = intent.getStringExtra(EXTRA_AUTH_KEY_PASSWORD)
                if (profileId.isNullOrBlank()) {
                    fail("Missing VPN profile")
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
                disconnect("VPN disconnected")
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        disconnect("VPN permission revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        connectJob?.cancel()
        connectJob = null
        disconnectJob?.cancel()
        disconnectJob = null
        stopTrafficTicker()
        disconnectResources()
        activeProfile = null
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(upstreamCallback) }
            networkCallbackRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTrafficTicker() {
        trafficTickerJob?.cancel()
        YumeVpnRuntime.resetTrafficHistory()
        trafficTickerJob = serviceScope.launch {
            while (isActive) {
                val stats = readActiveTechniqueStats()
                if (stats != null) {
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

    private suspend fun connect(profileId: String, authKeyPassword: String?) {
        val generation = sessionGeneration.get()
        val profile = profileStore.getProfile(profileId)
        if (profile == null) {
            fail("Selected server profile no longer exists")
            return
        }
        val appSettings = appSettingsStore.settings.value
        val techniqueEngine = resolveTechniqueEngine(appSettings)
        val runtimeConfig = techniqueEngine.runtimeConfig(appSettings.performanceProfile)
        val tun2SocksConfig = effectiveTun2SocksConfig(runtimeConfig)
        activeTun2SocksConfig = tun2SocksConfig
        ensureSessionActive(generation)
        activeProfile = profile
        val caSummary = secureMaterialStore.getSummary(profile.anonymCaMaterialId)
        if (caSummary == null) {
            fail("The anonym CA configured for this server is unavailable", profile.id)
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
            secureMaterialStore.readMaterialText(profile.anonymCaMaterialId) to
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
                veritySummary = strings().usingAnonymCa(caSummary.displayName),
            )
        )
        lastLoggedUpstreamHandle = Long.MIN_VALUE
        lastLoggedUpstreamLabel = null
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Technique: ${runtimeConfig.summary}.")
        if (appSettings.connectionTechnique != techniqueEngine.technique) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Requested technique ${appSettings.connectionTechnique.canonicalLabel()} is not available in this build; using ${techniqueEngine.technique.canonicalLabel()} instead.",
            )
        }
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Starting Yume Android session for ${profile.name} (${profile.endpointLabel}).")
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Requested protection policy: ${YumeVpnRuntime.state.value.requestedProtection}.")
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Loaded anonym CA: ${caSummary.displayName}.")
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Loaded auth key: ${authKeySummary.displayName}.")
        startInForeground(profile, strings().authenticatingNotification)

        lifecycleMutex.withLock {
            disconnectResources()
        }

        try {
            ensureSessionActive(generation)
            bootstrapSession = reconnectBootstrapSession(
                profile = profile,
                anonymCaPem = anonymCaPem,
                authKeyPem = authKeyPem,
                reason = "Opening authenticated Yume control path",
                generation = generation,
            )
            ensureSessionActive(generation)
            var session = requireNotNull(bootstrapSession)
            var bootstrap = session.bootstrap
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Server ${bootstrap.serverVersion} mode ${bootstrap.serverMode}; ${bootstrap.verity.summary}.",
            )
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Selected protection target: ${buildRequestedProtectionSummary(profile, bootstrap)}.",
            )
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Effective protection for this Yume session: ${buildEffectiveProtectionSummary(profile, bootstrap)}.",
            )
            ensureSessionActive(generation)
            val initialRelayProbe = session to probeExitIp()
            session = initialRelayProbe.first
            bootstrapSession = session
            bootstrap = session.bootstrap
            val exitIpResult = initialRelayProbe.second
            val relayExitIp = requireVerifiedRelayCanary(session, bootstrap, exitIpResult)
            ensureSessionActive(generation)
            awaitPreRoutingStability(session, generation)
            val systemProbeTargets = prepareSystemProbeTargets()

            if (tunInterface == null) {
                tunInterface = buildVpnInterface(profile)
                    ?: throw IOException("Android denied the VPN interface")
                updateUnderlyingNetworks()
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Info,
                    "Android VPN interface up (IPv4-only). Local DNS=$MAPDNS_ADDRESS, upstream=${effectiveVpnDnsServers(profile).joinToString(", ")}, MTU=${profile.mtu}. IPv6 is not captured yet.",
                )
            }

            // Now create technique data plane with access to TUN interface
            val techniqueBootstrap = createTechniqueDataPlane(
                techniqueEngine = techniqueEngine,
                runtimeConfig = runtimeConfig,
                profile = profile,
            )
            val dataPlane = techniqueBootstrap.dataPlane
            val proxyAddress = techniqueBootstrap.proxyAddress
            activeTechniqueDataPlane = dataPlane
            val verifiedExitIpResult = Result.success(relayExitIp)
            markRelayReady(profile, bootstrap, verifiedExitIpResult, proxyAddress)
            // Keep arbitrary app traffic out of the freshly-created TUN until
            // the startup probe has exercised the same path. Without this,
            // background traffic can race the probe and make startup look alive
            // while the routed path is still unproven.
            dataPlane.setVerificationMode(true)
            dataPlane.start()
            val initialVerification = runCatching {
                verifySystemTrafficFlow(
                    relayExitIpResult = verifiedExitIpResult,
                    proxyAddress = proxyAddress,
                    resolvedProbeTargets = systemProbeTargets,
                    generation = generation,
                )
            }.getOrElse { throwable ->
                val reason = throwable.message ?: throwable.javaClass.simpleName
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Warn,
                    "Startup route verification failed before traffic release: $reason.",
                )
                RouteVerificationResult(
                    exitIp = relayExitIp,
                    systemTrafficVerified = false,
                    source = "startup route verification",
                )
            }
            dataPlane.setVerificationMode(false)
            markTrafficFlowing(profile, bootstrap, verifiedExitIpResult, initialVerification, proxyAddress)
            updateForeground(profile, strings().protectedNotification)
            var nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
            var consecutivePingFailures = 0
            var nextNotificationRefreshAtMs = SystemClock.elapsedRealtime() + NOTIFICATION_REFRESH_INTERVAL_MS
            var nextRouteStatsLogAtMs = SystemClock.elapsedRealtime() + ROUTE_STATS_LOG_INTERVAL_MS

            while (true) {
                currentCoroutineContext().ensureActive()
                ensureSessionActive(generation)
                delay(1_500)
                requireTunnelBridgeRunning("keeping the Android VPN active")
                val nowElapsed = SystemClock.elapsedRealtime()
                if (nowElapsed >= nextNotificationRefreshAtMs) {
                    maybeRefreshForeground(profile)
                    nextNotificationRefreshAtMs = nowElapsed + NOTIFICATION_REFRESH_INTERVAL_MS
                }
                if (nowElapsed >= nextRouteStatsLogAtMs) {
                    logRouteStatsSnapshot("active")
                    nextRouteStatsLogAtMs = nowElapsed + ROUTE_STATS_LOG_INTERVAL_MS
                }
                val network = upstreamNetwork ?: connectivityManager.activeNetwork
                if (network == null) {
                    setTechniqueVerificationMode(true)
                    waitForUpstreamNetwork(profile)
                    ensureSessionActive(generation)
                    bootstrapSession = reconnectBootstrapSession(
                        profile = profile,
                        anonymCaPem = anonymCaPem,
                        authKeyPem = authKeyPem,
                        reason = "Upstream network returned",
                        generation = generation,
                    )
                    ensureSessionActive(generation)
                    session = requireNotNull(bootstrapSession)
                    bootstrap = session.bootstrap
                    val relayExitIp = requireVerifiedRelayCanary(session, bootstrap, probeExitIp())
                    handoffToTraffic(profile, bootstrap, session, relayExitIp, generation)
                    nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
                    continue
                }
                val liveSession = bootstrapSession
                if (liveSession == null || !liveSession.isAlive) {
                    val closeDetail = liveSession?.closeReason?.takeIf { it.isNotBlank() } ?: "unknown reason"
                    setTechniqueVerificationMode(true)
                    bootstrapSession = reconnectBootstrapSession(
                        profile = profile,
                        anonymCaPem = anonymCaPem,
                        authKeyPem = authKeyPem,
                        reason = "Authenticated Yume control path lost ($closeDetail)",
                        generation = generation,
                    )
                    ensureSessionActive(generation)
                    session = requireNotNull(bootstrapSession)
                    bootstrap = session.bootstrap
                    val relayExitIp = requireVerifiedRelayCanary(session, bootstrap, probeExitIp())
                    handoffToTraffic(profile, bootstrap, session, relayExitIp, generation)
                    nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
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
                            "Yume PING failed $consecutivePingFailures times in a row: $pingDetail. Re-authenticating.",
                        )
                        setTechniqueVerificationMode(true)
                        bootstrapSession = reconnectBootstrapSession(
                            profile = profile,
                            anonymCaPem = anonymCaPem,
                            authKeyPem = authKeyPem,
                            reason = "Yume keepalive failed",
                            generation = generation,
                        )
                        ensureSessionActive(generation)
                        session = requireNotNull(bootstrapSession)
                        bootstrap = session.bootstrap
                        val relayExitIp = requireVerifiedRelayCanary(session, bootstrap, probeExitIp())
                        handoffToTraffic(profile, bootstrap, session, relayExitIp, generation)
                        consecutivePingFailures = 0
                    }
                    nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
                }
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
            fail(ex.message ?: "VPN startup failed", profileId)
        }
    }

    private fun disconnect(message: String) {
        if (disconnectJob?.isActive == true || YumeVpnRuntime.state.value.phase == VpnPhase.Disconnecting) {
            return
        }
        shutdownRequested = true
        sessionGeneration.incrementAndGet()
        runCatching { inFlightSocket?.close() }
        inFlightSocket = null
        if (connectJob != null || tunInterface != null || bootstrapSession != null || tun2SocksActive) {
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
        if (connectJob != null) {
            connectJob?.cancel()
        }
        stopTrafficTicker()

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
        val dataPlane = activeTechniqueDataPlane
        val session = bootstrapSession
        activeTechniqueDataPlane = null
        bootstrapSession = null

        // Tear down upper layers first (data plane + SOCKS gateway) before the
        // session they depend on; any teardown error here is unrecoverable, so
        // swallowing it just stops one shutdown step from cascading.
        runCatching { dataPlane?.setVerificationMode(false) }
        runCatching { socksProxy?.setStartupWarmupMode(false) }
        runCatching { socksProxy?.stop() }
        runCatching { dataPlane?.stop() }
        runCatching { configureNativeDirectTransport(enabled = false) }
        runCatching { session?.close() }
        runCatching { inFlightSocket?.close() }
        runCatching { tunInterface?.close() }

        inFlightSocket = null
        tunInterface = null
        socksProxy = null
        localProxyAddress = null
        tun2SocksActive = false
        activeTun2SocksConfig = null
        warmupAllowedProbeHosts = emptySet()
        warmupAllowedProbeAddresses = emptySet()
        protectedSinceEpochMs = 0L
        lastNotificationRefreshElapsedMs = 0L
        latestWarmupTrafficObservation = null
        yumeAppExcludedFromVpnPath = false
        lastLoggedUpstreamHandle = Long.MIN_VALUE
        lastLoggedUpstreamLabel = null
    }

    private fun configureNativeDirectTransport(enabled: Boolean) {
        if (!enabled) {
            runCatching { nativeTransport?.closeInternal() }
            tun2SocksBridge.setNativeDirectEnabled(false)
            tun2SocksBridge.detachNativeTransport()
            nativeTransport = null
            return
        }

        val runtimeConfig = currentTun2SocksConfig()
        val transport = nativeTransport ?: YumeNativeTransport(
            sessionProvider = { bootstrapSession },
            maxConcurrentTcpStreams = runtimeConfig.maxActiveTcpRelays,
            relayAcquireTimeoutMs = runtimeConfig.relayAcquireTimeoutMs,
            maxBufferedTcpBytes = runtimeConfig.tcpStreamBufferBytes,
            maxBufferedUdpBytes = runtimeConfig.udpStreamBufferBytes,
            warmupTcpDestinationAllowedProvider = ::isWarmupProbeDestinationAllowed,
            protectedTcpDestinationBlockedProvider = { host, port ->
                val protected = currentProtectedDestinations()
                if (port != protected.port) {
                    false
                } else {
                    val normalizedHost = normalizeProtectedHost(host)
                    normalizedHost in protected.hosts ||
                        normalizeIpv4Literal(host)?.let { literal ->
                            literal in protected.addresses
                        } == true ||
                        runCatching { resolveProtectedAddresses(host) }
                            .getOrDefault(emptySet())
                            .any(protected.addresses::contains)
                }
            },
            mappedHostResolver = { host -> lookupMappedTunnelHost(host) },
            log = { level, message -> YumeVpnRuntime.appendLog(level, message) },
        ).also { nativeTransport = it }

        tun2SocksBridge.attachNativeTransport(transport)
        if (!tun2SocksBridge.hasNativeTransport()) {
            tun2SocksBridge.detachNativeTransport()
            nativeTransport = null
            error("The native-direct transport could not be registered with the tunnel backend")
        }
        transport.setWarmupMode(warmupAllowedProbeHosts.isNotEmpty() || warmupAllowedProbeAddresses.isNotEmpty())
        tun2SocksBridge.setNativeDirectEnabled(true)
    }

    private fun fail(message: String, profileId: String? = null) {
        shutdownRequested = true
        sessionGeneration.incrementAndGet()
        connectJob = null
        disconnectJob?.cancel()
        disconnectJob = null
        serviceScope.launch {
            runCatching { lifecycleMutex.withLock { disconnectResources() } }
                .onFailure { YumeVpnRuntime.appendLog(VpnLogLevel.Error, "Disconnect during fail() did not complete cleanly: ${it.message}") }
        }
        YumeVpnRuntime.appendLog(VpnLogLevel.Error, message)
        YumeVpnRuntime.update { previous ->
            previous.copy(
                phase = VpnPhase.Error,
                activeProfileId = profileId,
                headline = strings().connectionFailedHeadline,
                detail = message,
                upstreamNetworkLabel = describeUpstreamNetwork(),
            )
        }
        activeProfile = null
        serviceScope.launch {
            withContext(Dispatchers.Main.immediate) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun reconnectBootstrapSession(
        profile: ServerProfile,
        anonymCaPem: String,
        authKeyPem: String,
        reason: String,
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
                    createProtectedSocket(profile).also { socket ->
                        inFlightSocket = socket
                    }
                },
                requireAnonymProof = profile.requireAnonymProof,
                transportTuning = currentTun2SocksConfig().toBootstrapTuning(),
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

    private suspend fun createProtectedSocket(profile: ServerProfile): Socket {
        waitForUpstreamNetwork(profile)
        if (shutdownRequested) {
            throw CancellationException("VPN disconnect requested")
        }
        val candidates = preferIpv4Addresses(resolveUpstreamAddresses(profile.host, "the protected control path"))
        var lastFailure: Throwable? = null
        for (address in candidates) {
            currentCoroutineContext().ensureActive()
            if (shutdownRequested) {
                throw CancellationException("VPN disconnect requested")
            }
            val socket = Socket().apply {
                keepAlive = true
                tcpNoDelay = true
            }
            if (!protect(socket)) {
                runCatching { socket.close() }
                throw IOException("Failed to protect the control socket from the VPN")
            }
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Protected the control socket from the VPN path.")
            val endpoint = normalizeResolvedAddress(address) ?: profile.host
            if (isIpv4Address(address)) {
                YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Selected IPv4 control endpoint $endpoint for ${profile.host}.")
            } else {
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Warn,
                    "No IPv4 control endpoint was available for ${profile.host}. Falling back to IPv6 $endpoint.",
                )
            }
            try {
                socket.connect(InetSocketAddress(address, profile.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = CONNECT_TIMEOUT_MS
                return socket
            } catch (throwable: Throwable) {
                lastFailure = throwable
                runCatching { socket.close() }
            }
        }
        throw IOException(
            "Failed to connect the protected control socket to ${profile.host}:${profile.port}: ${lastFailure?.message ?: "unknown error"}",
            lastFailure,
        )
    }

    private suspend fun waitForUpstreamNetwork(profile: ServerProfile) {
        var waitingLogged = false
        while ((upstreamNetwork ?: connectivityManager.activeNetwork) == null) {
            currentCoroutineContext().ensureActive()
            if (!waitingLogged) {
                waitingLogged = true
                YumeVpnRuntime.appendLog(VpnLogLevel.Warn, "No upstream network is available. Waiting for Wi-Fi or cellular.")
                YumeVpnRuntime.update { state ->
                    state.copy(
                        phase = VpnPhase.Connecting,
                        headline = strings().waitingForNetworkHeadline,
                        detail = strings().waitingForNetworkDetail,
                        upstreamNetworkLabel = strings().unavailable,
                        controlSocketProtected = false,
                    )
                }
                updateForeground(profile, strings().waitingForNetworkHeadline)
            }
            delay(1_000)
        }
    }

    private suspend fun requireVerifiedRelayCanary(
        session: YumeBootstrapSession,
        @Suppress("UNUSED_PARAMETER") bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap,
        relayExitIpResult: Result<String>,
    ): String {
        val exitIp = relayExitIpResult.getOrElse { throwable ->
            val detail = describeRelayProbeFailure(session, throwable)
            throw YumeBootstrapException(
                "Failed to verify app relay traffic before starting the Android tunnel: $detail",
                throwable,
            )
        }.trim()
        if (!session.isAlive) {
            val detail = describeRelayProbeFailure(
                session,
                YumeBootstrapException("The authenticated Yume session became unavailable during the relay canary"),
            )
            throw YumeBootstrapException(
                "Authenticated Yume relay session became unavailable before Android tunnel startup: $detail",
            )
        }
        return exitIp
    }

    private fun buildVpnInterface(profile: ServerProfile): ParcelFileDescriptor? {
        val dnsServers = vpnInterfaceDnsServers()
        val ignoredDnsServers = profile.dnsServers.filterNot(::isIpv4AddressLiteral)
        if (ignoredDnsServers.isNotEmpty()) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Ignoring IPv6 DNS servers on Android until Yume ships native IPv6 relay support: ${ignoredDnsServers.joinToString(", ")}.",
            )
        }
        val builder = Builder()
            .setSession("Yume ${profile.name}")
            .setConfigureIntent(buildConfigureIntent())
            .setBlocking(false)
            .setMtu(profile.mtu)
            .addAddress(VPN_IPV4_ADDRESS, VPN_IPV4_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
        if (CAPTURE_IPV6_IN_ANDROID_VPN) {
            builder
                .addAddress(VPN_IPV6_ADDRESS, 128)
                .addRoute("::", 0)
        }
        dnsServers.forEach(builder::addDnsServer)
        builder.allowFamily(OsConstants.AF_INET)
        if (CAPTURE_IPV6_IN_ANDROID_VPN) {
            builder.allowFamily(OsConstants.AF_INET6)
        }
        applyVpnBypassPolicy(builder)
        return builder.establish()
    }

    private suspend fun awaitPreRoutingStability(
        session: YumeBootstrapSession,
        generation: Long,
    ) {
        val runtimeConfig = currentTun2SocksConfig()
        ensureSessionActive(generation)
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Holding the authenticated Yume session briefly before creating Android routing.",
        )
        YumeVpnRuntime.update { state ->
            if (state.phase == VpnPhase.Disconnecting || state.phase == VpnPhase.Idle || state.phase == VpnPhase.Error) {
                state
            } else {
                state.copy(
                    phase = VpnPhase.Verifying,
                    headline = strings().verifyingRouteHeadline,
                    detail = strings().verifyingRouteDetail,
                    controlSocketProtected = true,
                    localProxyAddress = localProxyAddress,
                )
            }
        }
        delay(runtimeConfig.preRoutingStabilityDelayMs)
        ensureSessionActive(generation)
        session.ping(timeoutMs = PRE_ROUTING_PING_TIMEOUT_MS)
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Authenticated Yume control path passed the pre-routing stability check.",
        )
    }

    private suspend fun ensureTun2SocksRunning(profile: ServerProfile, proxyAddress: String) {
        val proxyHost = proxyAddress.substringBeforeLast(":", LOOPBACK_HOST)
        val proxyPort = proxyAddress.substringAfterLast(":").toIntOrNull()
            ?: throw IOException("Invalid local SOCKS5 address: $proxyAddress")
        ensureTunBridgeRunning(
            profile = profile,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            routeLabel = proxyAddress,
            activationLog = "Systemwide TUN routing is active through tun2socks and the local Yume SOCKS5 gateway at $proxyAddress.",
        )
    }

    private suspend fun ensureNativeDirectTunnelRunning(profile: ServerProfile) {
        ensureTunBridgeRunning(
            profile = profile,
            proxyHost = LOOPBACK_HOST,
            proxyPort = NATIVE_DIRECT_PLACEHOLDER_PORT,
            routeLabel = NATIVE_DIRECT_ROUTE_LABEL,
            activationLog = "Systemwide TUN routing is active through the native-direct Yume tunnel backend with direct Yume TCP streams, DNS mapping, and no local SOCKS5 gateway.",
        )
    }

    private suspend fun ensureTunBridgeRunning(
        profile: ServerProfile,
        proxyHost: String,
        proxyPort: Int,
        routeLabel: String,
        activationLog: String,
    ) {
        if (tun2SocksActive) {
            return
        }
        val tun = tunInterface ?: throw IOException("Android VPN interface is unavailable")
        val configFile = writeTun2SocksConfig(profile, proxyHost, proxyPort)
        // The native start can throw IllegalStateException if the previous
        // tunnel worker hasn't finished shutting down yet (the JNI gives up
        // after ~2s of waiting). On a fast disconnect-then-reconnect, that
        // race fires routinely. Retry a few times with backoff so the user
        // doesn't see a startup error from a transient async-shutdown window.
        var startAttempt = 0
        var lastFailure: Throwable? = null
        while (startAttempt < TUNNEL_BACKEND_START_MAX_ATTEMPTS) {
            startAttempt += 1
            val detachedFd = ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd()
            try {
                tun2SocksBridge.startTunnel(configFile.absolutePath, detachedFd)
                lastFailure = null
                break
            } catch (throwable: Throwable) {
                runCatching { ParcelFileDescriptor.adoptFd(detachedFd).close() }
                lastFailure = throwable
                if (throwable is IllegalStateException &&
                    startAttempt < TUNNEL_BACKEND_START_MAX_ATTEMPTS
                ) {
                    YumeVpnRuntime.appendLog(
                        VpnLogLevel.Warn,
                        "Android tunnel backend startup race ($startAttempt/$TUNNEL_BACKEND_START_MAX_ATTEMPTS): ${throwable.message ?: "previous worker still stopping"}. Retrying.",
                    )
                    delay(TUNNEL_BACKEND_START_RETRY_DELAY_MS)
                    continue
                }
                throw throwable
            }
        }
        lastFailure?.let { throw it }
        delay(TUNNEL_BACKEND_START_GRACE_MS)
        if (!runCatching { tun2SocksBridge.isTunnelRunning() }.getOrDefault(false)) {
            throw IOException("Android tunnel backend exited during startup")
        }
        tun2SocksActive = true
        localProxyAddress = routeLabel
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, activationLog)
        logRouteStatsSnapshot("startup")
    }

    private suspend fun createTechniqueDataPlane(
        techniqueEngine: TunnelTechniqueEngine,
        runtimeConfig: TunnelRuntimeConfig,
        profile: ServerProfile,
    ): TechniqueBootstrapResult {
        return when (runtimeConfig) {
            is NativeDirectRuntimeConfig -> {
                configureNativeDirectTransport(enabled = true)
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Info,
                    "Native direct beta: TCP routes straight through Yume, IPv4-only, non-DNS UDP is dropped so apps fall back to TCP, no local SOCKS5 gateway.",
                )

                val proxyAddress = NATIVE_DIRECT_ROUTE_LABEL
                val packetEngine = NativeDirectTunnelPacketEngine(
                    queueCapacity = runtimeConfig.nativeQueueCapacity,
                    tcpFlowWindowBytes = runtimeConfig.tcpFlowWindowBytes,
                    udpFlowBufferBytes = runtimeConfig.udpFlowBufferBytes,
                    startBridge = { ensureNativeDirectTunnelRunning(profile) },
                    stopBridge = {
                        runCatching { tun2SocksBridge.stopTunnel() }
                        tun2SocksActive = false
                    },
                    setBridgeVerificationMode = ::setProxyWarmupMode,
                    statsReader = ::readActiveTechniqueStats,
                )

                TechniqueBootstrapResult(
                    dataPlane = NativeDirectTechniqueDataPlane(runtimeConfig, packetEngine),
                    proxyAddress = proxyAddress,
                )
            }

            is Tun2SocksRuntimeConfig -> {
                configureNativeDirectTransport(enabled = false)
                val proxyAddress = ensureSocksProxyStarted()
                TechniqueBootstrapResult(
                    dataPlane = techniqueEngine.createDataPlane(
                        runtimeConfig = runtimeConfig,
                        start = { ensureTun2SocksRunning(profile, proxyAddress) },
                        stop = {
                            runCatching { tun2SocksBridge.stopTunnel() }
                            tun2SocksActive = false
                        },
                        setVerificationMode = ::setProxyWarmupMode,
                        stats = ::readActiveTechniqueStats,
                    ),
                    proxyAddress = proxyAddress,
                )
            }

            is Socks5ProxyRuntimeConfig -> {
                // SOCKS5-only is dispatched to YumeSocks5ProxyService at
                // the MainViewModel layer; if it ever reaches the VPN
                // service (e.g. a stale Intent after a settings change),
                // fail loudly rather than silently bringing up TUN.
                error(
                    "SOCKS5-only technique is owned by YumeSocks5ProxyService — " +
                        "YumeVpnService should not receive it. Disconnect and reconnect."
                )
            }
        }
    }

    private fun writeTun2SocksConfig(profile: ServerProfile, proxyHost: String, proxyPort: Int): File {
        val runtimeConfig = currentTun2SocksConfig()
        val dir = File(noBackupFilesDir, "tun2socks").apply { mkdirs() }
        val configFile = File(dir, "yume-tun2socks.yml")
        val config = buildString {
            appendLine("tunnel:")
            appendLine("  name: tun0")
            appendLine("  mtu: ${profile.mtu}")
            appendLine("  ipv4: $VPN_IPV4_ADDRESS")
            if (CAPTURE_IPV6_IN_ANDROID_VPN) {
                appendLine("  ipv6: '$VPN_IPV6_ADDRESS'")
            }
            appendLine()
            appendLine("socks5:")
            appendLine("  port: $proxyPort")
            appendLine("  address: $proxyHost")
            appendLine("  udp: 'udp'")
            appendLine()
            appendLine("mapdns:")
            appendLine("  address: $MAPDNS_ADDRESS")
            appendLine("  port: 53")
            appendLine("  network: $MAPDNS_NETWORK")
            appendLine("  netmask: $MAPDNS_NETMASK")
            appendLine("  cache-size: $MAPDNS_CACHE_SIZE")
            appendLine()
            appendLine("misc:")
            appendLine("  log-file: stderr")
            appendLine("  log-level: warn")
            appendLine("  tcp-buffer-size: ${runtimeConfig.routeTcpBufferBytes}")
            appendLine("  connect-timeout: ${runtimeConfig.tun2SocksConnectTimeoutMs}")
            appendLine("  tcp-read-write-timeout: ${runtimeConfig.tun2SocksTcpReadWriteTimeoutMs}")
            appendLine("  udp-read-write-timeout: ${runtimeConfig.tun2SocksUdpReadWriteTimeoutMs}")
        }
        configFile.writeText(config)
        return configFile
    }

    private suspend fun verifySystemTrafficFlow(
        relayExitIpResult: Result<String>,
        proxyAddress: String,
        resolvedProbeTargets: List<ResolvedProbeTarget>,
        generation: Long,
    ): RouteVerificationResult {
        ensureSessionActive(generation)
        delay(currentTunnelStartupDelayMs())
        requireTunnelBridgeRunning("verifying routed Android traffic")
        val expectedRelayExitIp = relayExitIpResult.getOrNull()?.trim().orEmpty().ifBlank { null }
        var verificationSource = if (yumeAppExcludedFromVpnPath) {
            "local Yume relay path"
        } else {
            "Android TUN self-probe"
        }
        var probeResult = if (yumeAppExcludedFromVpnPath) {
            runSelfExcludedRouteProbe(proxyAddress, resolvedProbeTargets, expectedRelayExitIp)
        } else {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Routed Android traffic probe timeout budget: connect ${currentSystemProbeConnectTimeoutMs()}ms, read ${currentSystemProbeReadTimeoutMs()}ms.",
            )
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Running an in-app routed Android traffic probe through Yume.")
            probeSystemExitIp(resolvedProbeTargets)
        }
        if (!yumeAppExcludedFromVpnPath &&
            probeResult.isFailure &&
            activeTechniqueDataPlane?.technique == ConnectionTechnique.Tun2SocksStable
        ) {
            val reason = probeResult.exceptionOrNull()?.message ?: "probe inconclusive"
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Android TUN self-probe failed before a verified SOCKS CONNECT ($reason). Checking the local Yume SOCKS gateway as a diagnostic only.",
            )
            val localProxyProbe = runSelfExcludedRouteProbe(proxyAddress, resolvedProbeTargets, expectedRelayExitIp)
            if (localProxyProbe.isSuccess) {
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Info,
                    "Local Yume SOCKS gateway still works after the TUN self-probe failed. The remaining fault is in Android TUN routing or the route backend, not the authenticated Yume relay.",
                )
            } else {
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Warn,
                    "Local Yume SOCKS gateway fallback also failed: ${localProxyProbe.exceptionOrNull()?.message ?: "probe inconclusive"}.",
                )
            }
        }
        val routedExitIp = probeResult.getOrNull()?.trim()?.ifBlank { null } ?: expectedRelayExitIp
        val verified = probeResult.isSuccess && routedExitIp != null &&
            (expectedRelayExitIp == null || routedExitIp.equals(expectedRelayExitIp, ignoreCase = true))
        if (!verified) {
            val reason = probeResult.exceptionOrNull()?.message ?: "probe inconclusive"
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "System traffic probe did not complete ($reason). Leaving the VPN up so apps can route through Yume; verify with a browser.",
            )
        } else {
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Verified Yume traffic via $verificationSource. Exit IP: $routedExitIp.")
        }
        return RouteVerificationResult(
            exitIp = routedExitIp,
            systemTrafficVerified = verified,
            source = verificationSource,
        )
    }

    private suspend fun runSelfExcludedRouteProbe(
        proxyAddress: String,
        resolvedProbeTargets: List<ResolvedProbeTarget>,
        expectedRelayExitIp: String?,
    ): Result<String> {
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            if (yumeAppExcludedFromVpnPath) {
                "The Yume app process is excluded from the Android VPN path to avoid routing recursion. Validating the local Yume relay path instead of using an app-owned routed probe."
            } else {
                "Validating the local Yume relay path as a fallback after the app-owned routed probe failed."
            },
        )
        if (activeTechniqueDataPlane?.technique == ConnectionTechnique.Tun2SocksStable) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Running a local SOCKS5 relay probe through Yume before releasing Android traffic warm-up.",
            )
            return probeExitIpViaLocalProxy(proxyAddress, resolvedProbeTargets)
        }
        return expectedRelayExitIp?.let {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Native-direct verification uses the authenticated relay canary because the VPN service UID is intentionally outside the Android VPN path. Exit IP: $it.",
            )
            Result.success(it)
        } ?: Result.failure(IOException("native-direct relay canary is unavailable"))
    }

    private fun currentTunnelStartupDelayMs(): Long {
        return when (activeTechniqueDataPlane?.technique) {
            ConnectionTechnique.NativeDirectBeta -> 100L
            else -> TUN2SOCKS_STARTUP_DELAY_MS
        }
    }

    private suspend fun handoffToTraffic(
        profile: ServerProfile,
        bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap,
        session: YumeBootstrapSession,
        relayExitIp: String,
        generation: Long,
    ) {
        val verifiedRelayExitIpResult = Result.success(relayExitIp)
        val routeLabel = currentTrafficPathLabel()
        val systemProbeTargets = prepareSystemProbeTargets()
        setTechniqueVerificationMode(true)
        val initialVerification = runCatching {
            verifySystemTrafficFlow(
                relayExitIpResult = verifiedRelayExitIpResult,
                proxyAddress = routeLabel,
                resolvedProbeTargets = systemProbeTargets,
                generation = generation,
            )
        }.getOrElse { throwable ->
            val reason = throwable.message ?: throwable.javaClass.simpleName
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Route verification after reconnect failed before traffic release: $reason.",
            )
            RouteVerificationResult(
                exitIp = relayExitIp,
                systemTrafficVerified = false,
                source = "reconnect route verification",
            )
        }
        setTechniqueVerificationMode(false)
        markTrafficFlowing(profile, bootstrap, verifiedRelayExitIpResult, initialVerification, routeLabel)
    }

    private fun markTrafficFlowing(
        profile: ServerProfile,
        bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap,
        relayExitIpResult: Result<String>,
        verification: RouteVerificationResult,
        proxyAddress: String,
    ) {
        val appRelayIp = relayExitIpResult.getOrNull()?.trim().orEmpty().ifBlank { null }
        val effectiveExitIp = verification.exitIp ?: appRelayIp
        if (protectedSinceEpochMs == 0L) {
            protectedSinceEpochMs = System.currentTimeMillis()
        }
        val relaySummary = buildString {
            if (verification.systemTrafficVerified) {
                append("Yume traffic verified through ${verification.source}: ${verification.exitIp ?: "unknown"}.")
            } else {
                append("Yume relay is active; ${verification.source} did not produce a matching exit IP.")
            }
            if (appRelayIp != null && appRelayIp != verification.exitIp) {
                append(" Control-path relay canary: $appRelayIp.")
            }
        }
        startTrafficTicker()
        YumeVpnRuntime.update { current ->
            current.copy(
                phase = VpnPhase.Connected,
                activeProfileId = profile.id,
                headline = strings().protectedHeadline,
                detail = strings().protectedDetail,
                endpointLabel = profile.endpointLabel,
                sessionDnsServers = effectiveVpnDnsServers(profile),
                sessionMtu = profile.mtu,
                upstreamNetworkLabel = describeUpstreamNetwork(),
                controlSocketProtected = true,
                vpnInterfaceEstablished = tunInterface != null,
                trafficRoutedThroughYume = true,
                requestedProtection = buildEffectiveProtectionSummary(profile, bootstrap),
                transportSummary = buildTransportSummary(profile, bootstrap),
                innerSummary = buildInnerSummary(bootstrap),
                hopSummary = buildHopSummary(bootstrap),
                veritySummary = bootstrap.verity.summary,
                routingSummary = buildRoutingSummary(),
                yumeProtocolVersion = BuildConfig.YUME_PROTOCOL_VERSION,
                basefwxVersion = BuildConfig.BASEFWX_ENGINE_VERSION,
                serverMode = bootstrap.serverMode,
                serverVersion = bootstrap.serverVersion,
                peerCertificateFingerprint = bootstrap.verity.peerFingerprintSha256,
                exitIp = effectiveExitIp,
                relaySummary = relaySummary,
                localProxyAddress = proxyAddress,
            )
        }
    }

    private fun markRelayReady(
        profile: ServerProfile,
        bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap,
        relayExitIpResult: Result<String>,
        proxyAddress: String,
    ) {
        val relayExitIp = relayExitIpResult.getOrNull()?.trim().orEmpty().ifBlank { null }
        val relaySummary = relayExitIp?.let { "Verified app relay traffic via Yume. Exit IP: $it." }
            ?: "Authenticated control path is up, but the relay canary failed."
        val detail = if (tun2SocksActive || tunInterface != null) {
            "Tunnel is up. Verifying traffic."
        } else {
            "Relay is ready. Waiting to start routing."
        }
        YumeVpnRuntime.update { current ->
            current.copy(
                phase = VpnPhase.Verifying,
                activeProfileId = profile.id,
                headline = strings().verifyingRouteHeadline,
                detail = detail,
                endpointLabel = profile.endpointLabel,
                sessionDnsServers = effectiveVpnDnsServers(profile),
                sessionMtu = profile.mtu,
                upstreamNetworkLabel = describeUpstreamNetwork(),
                controlSocketProtected = true,
                vpnInterfaceEstablished = tunInterface != null,
                trafficRoutedThroughYume = false,
                requestedProtection = buildEffectiveProtectionSummary(profile, bootstrap),
                transportSummary = buildTransportSummary(profile, bootstrap),
                innerSummary = buildInnerSummary(bootstrap),
                hopSummary = buildHopSummary(bootstrap),
                veritySummary = bootstrap.verity.summary,
                routingSummary = "Android is verifying routed IPv4 traffic against the authenticated relay canary. IPv6 is not captured until native IPv6 relay support is available.",
                yumeProtocolVersion = BuildConfig.YUME_PROTOCOL_VERSION,
                basefwxVersion = BuildConfig.BASEFWX_ENGINE_VERSION,
                serverMode = bootstrap.serverMode,
                serverVersion = bootstrap.serverVersion,
                peerCertificateFingerprint = bootstrap.verity.peerFingerprintSha256,
                exitIp = relayExitIp,
                relaySummary = relaySummary,
                localProxyAddress = proxyAddress,
            )
        }
    }

    private fun buildEffectiveProtectionSummary(
        profile: ServerProfile?,
        bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap,
    ): String {
        val port = profile?.port ?: 443
        if (!bootstrap.innerActive) {
            return "TLS over $port${httpsMaskingSuffix(profile)}"
        }
        val innerTarget = when {
            bootstrap.innerDual -> "Inner dual"
            bootstrap.innerMode.equals("heavy", ignoreCase = true) -> "Inner heavy"
            bootstrap.innerMode.equals("light", ignoreCase = true) -> "Inner light"
            else -> "Inner"
        }
        val kdf = bootstrap.innerKdf?.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        } ?: if (bootstrap.capArgon2) "Argon2" else if (bootstrap.capPbkdf2) "PBKDF2" else "server default KDF"
        return if (bootstrap.hopEnabled) {
            "$innerTarget + hop over $port ($kdf)${httpsMaskingSuffix(profile)}"
        } else {
            "$innerTarget over $port ($kdf)${httpsMaskingSuffix(profile)}"
        }
    }

    private suspend fun prepareSystemProbeTargets(): List<ResolvedProbeTarget> {
        val resolvedTargets = mutableListOf<ResolvedProbeTarget>()
        for ((host, path, port) in RELAY_PROBE_TARGETS) {
            currentCoroutineContext().ensureActive()
            val connectHosts = try {
                resolveProbeConnectHosts(host)
            } catch (throwable: Throwable) {
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Warn,
                    "Failed to resolve the Android routed probe target $host: ${throwable.message ?: throwable.javaClass.simpleName}.",
                )
                continue
            }
            if (connectHosts.isNotEmpty()) {
                resolvedTargets += ResolvedProbeTarget(host, path, port, connectHosts)
            }
        }
        warmupAllowedProbeHosts = resolvedTargets.mapTo(linkedSetOf()) { normalizeProtectedHost(it.host) }
        warmupAllowedProbeAddresses = resolvedTargets.flatMapTo(linkedSetOf()) { target ->
            target.connectHosts.map(::normalizeProtectedHost)
        }
        if (resolvedTargets.isEmpty()) {
            throw IOException("failed to resolve an IPv4 address for any Android routed probe target")
        }
        return resolvedTargets
    }

    private suspend fun probeSystemExitIp(
        resolvedTargets: List<ResolvedProbeTarget>,
    ): Result<String> {
        var lastFailure: Throwable? = null
        var attempts = 0
        for (target in resolvedTargets) {
            for (connectHost in target.connectHosts) {
                currentCoroutineContext().ensureActive()
                if (attempts >= MAX_SYSTEM_PROBE_CONNECT_ATTEMPTS) {
                    return Result.failure(
                        lastFailure ?: IOException("system traffic probe failed after $attempts attempts")
                    )
                }
                attempts += 1
                try {
                    return Result.success(
                        runSystemProbeRequest(
                            connectHost = connectHost,
                            hostHeader = target.host,
                            path = target.path,
                            port = target.port,
                        ),
                    )
                } catch (throwable: Throwable) {
                    lastFailure = IOException(
                        "system traffic probe via ${target.host} [$connectHost] failed: ${throwable.message ?: throwable.javaClass.simpleName}",
                        throwable,
                    )
                    YumeVpnRuntime.appendLog(
                        VpnLogLevel.Warn,
                        lastFailure?.message ?: "System traffic probe attempt failed.",
                    )
                }
            }
        }
        return Result.failure(lastFailure ?: IOException("system traffic probe failed"))
    }

    private suspend fun runSystemProbeRequest(
        connectHost: String,
        hostHeader: String,
        path: String,
        port: Int,
    ): String {
        currentCoroutineContext().ensureActive()
        val socket = Socket()
        inFlightSocket = socket
        val connectTimeoutMs = currentSystemProbeConnectTimeoutMs()
        val readTimeoutMs = currentSystemProbeReadTimeoutMs()
        return try {
            socket.use {
                it.tcpNoDelay = true
                it.keepAlive = true
                it.connect(InetSocketAddress(connectHost, port), connectTimeoutMs)
                currentCoroutineContext().ensureActive()
                it.soTimeout = readTimeoutMs
                it.getOutputStream().write(
                    (
                        "GET $path HTTP/1.1\r\n" +
                            "Host: $hostHeader\r\n" +
                            "User-Agent: YumeAndroid/${BuildConfig.VERSION_NAME}\r\n" +
                            "Accept: text/plain\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                )
                it.getOutputStream().flush()
                val response = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = it.getInputStream().read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read > 0) {
                        response.write(buffer, 0, read)
                        if (response.size() > MAX_SYSTEM_PROBE_RESPONSE_BYTES) {
                            throw IOException("HTTP probe response exceeded ${MAX_SYSTEM_PROBE_RESPONSE_BYTES} bytes")
                        }
                        tryParseProbeHttpBody(response.toByteArray())?.let { return it }
                    }
                }
                tryParseProbeHttpBody(response.toByteArray())
                    ?: throw IOException("incomplete HTTP response")
            }
        } finally {
            if (inFlightSocket === socket) {
                inFlightSocket = null
            }
        }
    }

    private suspend fun probeExitIpViaLocalProxy(
        proxyAddress: String,
        resolvedTargets: List<ResolvedProbeTarget>,
    ): Result<String> {
        val proxyEndpoint = parseLoopbackProxyAddress(proxyAddress)
            ?: return Result.failure(IOException("Invalid local SOCKS5 address: $proxyAddress"))
        var lastFailure: Throwable? = null
        for (target in resolvedTargets) {
            currentCoroutineContext().ensureActive()
            try {
                return Result.success(
                    runLocalProxyProbeRequest(
                        proxyEndpoint = proxyEndpoint,
                        hostHeader = target.host,
                        path = target.path,
                        port = target.port,
                    ),
                )
            } catch (throwable: Throwable) {
                lastFailure = IOException(
                    "local Yume proxy probe via ${target.host} failed: ${throwable.message ?: throwable.javaClass.simpleName}",
                    throwable,
                )
            }
        }
        return Result.failure(lastFailure ?: IOException("local Yume proxy probe failed"))
    }

    private suspend fun runLocalProxyProbeRequest(
        proxyEndpoint: InetSocketAddress,
        hostHeader: String,
        path: String,
        port: Int,
    ): String {
        currentCoroutineContext().ensureActive()
        val socket = Socket(Proxy(Proxy.Type.SOCKS, proxyEndpoint))
        inFlightSocket = socket
        val connectTimeoutMs = currentSystemProbeConnectTimeoutMs()
        val readTimeoutMs = currentSystemProbeReadTimeoutMs()
        return try {
            socket.use {
                it.tcpNoDelay = true
                it.keepAlive = true
                it.connect(InetSocketAddress.createUnresolved(hostHeader, port), connectTimeoutMs)
                currentCoroutineContext().ensureActive()
                it.soTimeout = readTimeoutMs
                it.getOutputStream().write(
                    (
                        "GET $path HTTP/1.1\r\n" +
                            "Host: $hostHeader\r\n" +
                            "User-Agent: YumeAndroid/${BuildConfig.VERSION_NAME}\r\n" +
                            "Accept: text/plain\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                )
                it.getOutputStream().flush()
                val response = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = it.getInputStream().read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read > 0) {
                        response.write(buffer, 0, read)
                        if (response.size() > MAX_SYSTEM_PROBE_RESPONSE_BYTES) {
                            throw IOException("HTTP probe response exceeded ${MAX_SYSTEM_PROBE_RESPONSE_BYTES} bytes")
                        }
                        tryParseProbeHttpBody(response.toByteArray())?.let { return it }
                    }
                }
                tryParseProbeHttpBody(response.toByteArray())
                    ?: throw IOException("incomplete HTTP response")
            }
        } finally {
            if (inFlightSocket === socket) {
                inFlightSocket = null
            }
        }
    }

    private fun currentSystemProbeConnectTimeoutMs(): Int {
        val runtimeConfig = currentTun2SocksConfig()
        return maxOf(
            CONNECT_TIMEOUT_MS,
            runtimeConfig.tun2SocksConnectTimeoutMs + SYSTEM_PROBE_CONNECT_HEADROOM_MS,
        )
    }

    private fun currentSystemProbeReadTimeoutMs(): Int {
        val runtimeConfig = currentTun2SocksConfig()
        return maxOf(
            CONNECT_TIMEOUT_MS,
            runtimeConfig.tun2SocksConnectTimeoutMs + SYSTEM_PROBE_READ_HEADROOM_MS,
        )
    }

    private fun tryParseProbeHttpBody(responseBytes: ByteArray): String? {
        if (responseBytes.isEmpty()) {
            return null
        }
        val response = responseBytes.toString(Charsets.UTF_8)
        val separator = "\r\n\r\n"
        val separatorIndex = response.indexOf(separator)
        if (separatorIndex < 0) {
            val trimmed = response.trim()
            return trimmed.takeIf(::looksLikeIpLiteral)
        }
        val statusLine = response.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains(" 200 ")) {
            throw IOException(statusLine.ifBlank { "unexpected HTTP status" })
        }
        val headerBlock = response.substring(0, separatorIndex)
        val bodyOffset = separatorIndex + separator.length
        val contentLength = headerBlock.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
        if (contentLength != null) {
            if (responseBytes.size < bodyOffset + contentLength) {
                return null
            }
            return responseBytes
                .copyOfRange(bodyOffset, bodyOffset + contentLength)
                .toString(Charsets.UTF_8)
                .trim()
        }
        val body = response.substring(bodyOffset).trim()
        return body.ifEmpty { null }
    }

    private fun resolveProbeConnectHosts(host: String): List<String> {
        val connectHosts = preferIpv4Addresses(resolveUpstreamAddresses(host, "the routed probe"))
            .filter(::isIpv4Address)
            .mapNotNull(::normalizeResolvedAddress)
            .distinct()
        if (connectHosts.isEmpty()) {
            throw IOException("failed to resolve an IPv4 address for $host for the routed probe")
        }
        return connectHosts
    }

    private fun resolveProbeUpstreamNetwork(): Network? {
        upstreamNetwork?.let { return it }
        return connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun parseLoopbackProxyAddress(proxyAddress: String): InetSocketAddress? {
        val host = proxyAddress.substringBeforeLast(":", "").trim()
        val port = proxyAddress.substringAfterLast(":", "").trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) {
            return null
        }
        return InetSocketAddress(host, port)
    }

    private fun androidClientName(profile: ServerProfile): String {
        val raw = "android-${profile.name}"
        val sanitized = buildString {
            raw.lowercase(Locale.ROOT).forEach { char ->
                append(
                    when {
                        char.isLetterOrDigit() -> char
                        char == '-' -> char
                        else -> '-'
                    }
                )
            }
        }.trim('-')
        return sanitized.ifBlank { "android-vpn" }.take(48)
    }

    private fun buildRequestedProtectionSummary(
        profile: ServerProfile?,
        bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap,
    ): String {
        val port = profile?.port ?: 443
        if (!bootstrap.innerSupported || !bootstrap.capPq) {
            return "TLS over $port${httpsMaskingSuffix(profile)}"
        }
        val innerTarget = when {
            bootstrap.innerDual -> "Inner dual"
            bootstrap.innerMode.equals("heavy", ignoreCase = true) -> "Inner heavy"
            bootstrap.innerMode.equals("light", ignoreCase = true) -> "Inner light"
            else -> "Inner"
        }
        val kdfTarget = when {
            bootstrap.capArgon2 -> "Argon2"
            bootstrap.capPbkdf2 -> "PBKDF2"
            else -> "server default KDF"
        }
        return if (bootstrap.hopEnabled) {
            "$innerTarget + hop over $port ($kdfTarget)${httpsMaskingSuffix(profile)}"
        } else {
            "$innerTarget over $port ($kdfTarget)${httpsMaskingSuffix(profile)}"
        }
    }

    private fun buildTransportSummary(
        profile: ServerProfile,
        bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap,
    ): String {
        val parts = buildList {
            add(bootstrap.tlsProtocol)
            if (profile.obfuscation) {
                add("HTTPS masking")
            }
            add("AUTH")
        }
        return "${parts.joinToString(" + ")} (${bootstrap.tlsCipherSuite})"
    }

    private fun httpsMaskingSuffix(profile: ServerProfile?): String {
        return if (profile?.obfuscation == true) " + HTTPS masking" else ""
    }

    private fun buildInnerSummary(bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap): String {
        if (!bootstrap.innerSupported || !bootstrap.capPq) {
            return "Inner off"
        }
        if (!bootstrap.innerActive) {
            return "Inner available"
        }
        return when {
            bootstrap.innerDual -> "Inner dual"
            bootstrap.innerMode.equals("heavy", ignoreCase = true) -> "Inner heavy"
            bootstrap.innerMode.equals("light", ignoreCase = true) -> "Inner light"
            bootstrap.innerMode.isNotBlank() -> "Inner ${bootstrap.innerMode.lowercase(Locale.US)}"
            else -> "Inner on"
        }
    }

    private fun buildHopSummary(bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap): String {
        if (!bootstrap.hopEnabled) {
            return "Hop off"
        }
        if (bootstrap.hopIntervalMs <= 0) {
            return "Hop on"
        }
        val hz = 1000.0 / bootstrap.hopIntervalMs.toDouble()
        return String.format(
            java.util.Locale.US,
            "Hop %.2f Hz",
            hz,
        )
    }

    private suspend fun probeExitIp(): Result<String> {
        val session = bootstrapSession ?: return Result.failure(IllegalStateException("No authenticated Yume session"))
        var lastFailure: Throwable? = null
        for ((host, path, port) in RELAY_PROBE_TARGETS) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Running a Yume relay canary via $host$path.")
            try {
                val exitIp = session.probeExitIp(host = host, path = path, port = port)
                YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Verified app relay traffic via Yume. Exit IP: ${exitIp.trim()}.")
                return Result.success(exitIp)
            } catch (throwable: Throwable) {
                val detail = describeRelayProbeFailure(session, throwable)
                YumeVpnRuntime.appendLog(VpnLogLevel.Warn, "Relay canary via $host$path failed: $detail.")
                lastFailure = YumeBootstrapException(detail, throwable)
                if (!session.isAlive) {
                    break
                }
            }
        }
        return Result.failure(lastFailure ?: IOException("relay traffic probe failed"))
    }

    private fun describeRelayProbeFailure(session: YumeBootstrapSession, throwable: Throwable): String {
        val failure = throwable.message?.trim().orEmpty().ifEmpty { throwable.javaClass.simpleName }
        val closeReason = session.closeReason?.trim().orEmpty()
        return when {
            closeReason.isBlank() -> failure
            failure.contains(closeReason) -> failure
            failure.isBlank() -> closeReason
            else -> "$failure (session: $closeReason)"
        }
    }

    private fun looksLikeIpLiteral(value: String): Boolean {
        if (value.isBlank() || value.length > 64) {
            return false
        }
        return value.all { char ->
            char.isDigit() ||
                char == '.' ||
                char == ':' ||
                char in 'a'..'f' ||
                char in 'A'..'F'
        }
    }

    private fun summarizeProbeResponse(value: String): String? {
        val summary = value
            .replace(Regex("\\s+"), " ")
            .take(96)
            .trim()
        return summary.ifEmpty { null }
    }

    private suspend fun ensureSocksProxyStarted(): String {
        val existingAddress = localProxyAddress
        if (existingAddress != null && socksProxy != null) {
            return existingAddress
        }
        val proxy = socksProxy ?: YumeSocks5Proxy(
            scope = serviceScope,
            runtimeConfig = currentTun2SocksConfig(),
            sessionProvider = { bootstrapSession },
            dnsServersProvider = { activeProfile?.let(::effectiveVpnDnsServers).orEmpty() },
            ipv4OnlyMode = true,
            forceTcpFallbackUdp443Provider = { true },
            protectedDestinationsProvider = { currentProtectedDestinations() },
            protectedAddressResolver = { host ->
                runCatching { resolveProtectedAddresses(host) }.getOrDefault(emptySet())
            },
            warmupTcpDestinationAllowedProvider = ::isWarmupProbeDestinationAllowed,
            onWarmupTcpConnectObserved = ::recordWarmupTrafficObservation,
            mappedHostResolver = { host -> lookupMappedTunnelHost(host) },
            log = { level, message -> YumeVpnRuntime.appendLog(level, message) },
        ).also { socksProxy = it }
        val address = proxy.start()
        val label = "${address.address.hostAddress}:${address.port}"
        localProxyAddress = label
        return label
    }

    private suspend fun currentTrafficPathLabel(): String {
        return if (activeTechniqueDataPlane?.technique == ConnectionTechnique.NativeDirectBeta) {
            NATIVE_DIRECT_ROUTE_LABEL
        } else {
            ensureSocksProxyStarted()
        }
    }

    private fun buildRoutingSummary(): String {
        return when (activeTechniqueDataPlane?.technique) {
            ConnectionTechnique.NativeDirectBeta ->
                "Systemwide Android VpnService IPv4 routing through the native-direct Yume tunnel backend with direct Yume TCP and UDP streams. IPv6 is not captured until native IPv6 relay support is available."

            else ->
                "${BuildConfig.YUME_ROUTING_MODE} (IPv4 relay; IPv6 not captured until native IPv6 relay support is available)"
        }
    }

    private fun currentProtectedDestinations(): YumeSocks5Proxy.ProtectedDestinations {
        val profile = activeProfile ?: return YumeSocks5Proxy.ProtectedDestinations()
        val protectedHosts = linkedSetOf<String>()
        protectedHosts += normalizeProtectedHost(profile.host)
        protectedHosts += normalizeProtectedHost(profile.serverName)

        val protectedAddresses = linkedSetOf<String>()
        for (host in protectedHosts) {
            if (host.isEmpty()) {
                continue
            }
            val resolved = runCatching { resolveProtectedAddresses(host) }.getOrNull().orEmpty()
            protectedAddresses += resolved
        }

        return YumeSocks5Proxy.ProtectedDestinations(
            port = profile.port,
            hosts = protectedHosts.filterTo(linkedSetOf()) { it.isNotEmpty() },
            addresses = protectedAddresses,
        )
    }

    private fun resolveProtectedAddresses(host: String): Set<String> {
        return resolveUpstreamAddresses(host, "protected route exclusion")
            .mapNotNull(::normalizeResolvedAddress)
            .toSet()
    }

    private fun normalizeProtectedHost(host: String): String {
        return host
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')
            .lowercase(Locale.US)
    }

    private fun recordWarmupTrafficObservation(host: String, port: Int, warmupAllowed: Boolean) {
        val normalizedHost = normalizeProtectedHost(host)
        if (normalizedHost.isEmpty()) {
            return
        }
        val candidate = WarmupTrafficObservation(
            host = normalizedHost,
            port = port,
            warmupAllowed = warmupAllowed,
            observedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        val current = latestWarmupTrafficObservation
        latestWarmupTrafficObservation = when {
            current == null -> candidate
            !current.warmupAllowed -> current
            !candidate.warmupAllowed -> candidate
            else -> candidate
        }
    }

    private fun recentWarmupBypassObservation(): WarmupTrafficObservation? {
        val observation = latestWarmupTrafficObservation ?: return null
        if (observation.warmupAllowed) {
            return null
        }
        val ageMs = SystemClock.elapsedRealtime() - observation.observedAtElapsedMs
        return observation.takeIf { ageMs <= WARMUP_BYPASS_OBSERVATION_MAX_AGE_MS }
    }

    private fun isWarmupProbeDestinationAllowed(host: String, port: Int): Boolean {
        if (port !in STARTUP_WARMUP_PROBE_TCP_PORTS) {
            return false
        }
        val normalizedHost = normalizeProtectedHost(host)
        if (normalizedHost.isEmpty()) {
            return false
        }
        return normalizedHost in warmupAllowedProbeHosts ||
            normalizedHost in warmupAllowedProbeAddresses
    }

    private fun lookupMappedTunnelHost(host: String): String? {
        return runCatching { tun2SocksBridge.lookupMappedHost(host) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun applyVpnBypassPolicy(builder: Builder) {
        yumeAppExcludedFromVpnPath = false
        val mustExcludeSelf = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        val excludeSelf = EXCLUDE_YUME_APP_FROM_ANDROID_VPN || mustExcludeSelf
        if (excludeSelf) {
            val selfExcluded = runCatching {
                builder.addDisallowedApplication(packageName)
            }.isSuccess
            yumeAppExcludedFromVpnPath = selfExcluded
            if (selfExcluded) {
                val reason = if (mustExcludeSelf && !EXCLUDE_YUME_APP_FROM_ANDROID_VPN) {
                    "because this Android version cannot exclude the active Yume server route directly"
                } else {
                    "so control, local proxy, and native tunnel sockets cannot recurse through the TUN interface"
                }
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Info,
                    "Excluding the Yume app process from the Android VPN path $reason.",
                )
            } else {
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Warn,
                    "Android did not allow excluding the Yume app process from the VPN path. Control sockets remain protected individually.",
                )
            }
        } else {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Keeping the Yume app inside the Android VPN path so the startup probe exercises the same TUN capture path as other apps. Control sockets are protected individually and the active server IPv4 routes are excluded explicitly.",
            )
        }

        val protectedIpv4Addresses = currentProtectedDestinations().addresses
            .filterTo(linkedSetOf()) { address -> isIpv4AddressLiteral(address) }
        if (protectedIpv4Addresses.isEmpty()) {
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Android cannot exclude the active Yume server IPv4 routes on this OS version. Proxy-side loop guards remain active.",
            )
            return
        }
        val excluded = linkedSetOf<String>()
        for (address in protectedIpv4Addresses) {
            val success = runCatching {
                val inetAddress = InetAddress.getByName(address)
                builder.excludeRoute(IpPrefix(inetAddress, 32))
            }.isSuccess
            if (success) {
                excluded += address
            }
        }
        if (excluded.isNotEmpty()) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Excluding active Yume server IPv4 routes from the Android VPN path: ${excluded.joinToString(", ")}.",
            )
        }
    }

    private fun effectiveVpnDnsServers(profile: ServerProfile): List<String> {
        return profile.dnsServers
            .mapNotNull(::normalizeIpv4Literal)
            .distinct()
            .ifEmpty { ServerProfile.DEFAULT_DNS_SERVERS }
    }

    private fun vpnInterfaceDnsServers(): List<String> {
        return listOf(MAPDNS_ADDRESS)
    }

    private fun resolveUpstreamAddresses(host: String, purpose: String): List<InetAddress> {
        val network = resolveProbeUpstreamNetwork()
        val resolvedAddresses = try {
            when (network) {
                null -> InetAddress.getAllByName(host).toList()
                else -> network.getAllByName(host).toList()
            }
        } catch (throwable: Throwable) {
            val source = network?.let { "upstream network ${describeNetwork(it)}" } ?: "default resolver"
            throw IOException(
                "failed to resolve $host for $purpose on $source: ${throwable.message ?: throwable.javaClass.simpleName}",
                throwable,
            )
        }
        val uniqueAddresses = linkedMapOf<String, InetAddress>()
        for (address in resolvedAddresses) {
            val normalized = normalizeResolvedAddress(address) ?: continue
            uniqueAddresses.putIfAbsent(normalized, address)
        }
        val addresses = uniqueAddresses.values.toList()
        if (addresses.isEmpty()) {
            throw IOException("failed to resolve $host for $purpose")
        }
        return addresses
    }

    private fun preferIpv4Addresses(addresses: List<InetAddress>): List<InetAddress> {
        val ipv4 = addresses.filter(::isIpv4Address)
        return if (ipv4.isEmpty()) {
            addresses
        } else {
            ipv4 + addresses.filterNot(::isIpv4Address)
        }
    }

    private fun normalizeResolvedAddress(address: InetAddress): String? {
        return address.hostAddress
            ?.substringBefore('%')
            ?.lowercase(Locale.US)
    }

    private fun normalizeIpv4Literal(address: String): String? {
        val inetAddress = runCatching { InetAddress.getByName(address.trim()) }.getOrNull() ?: return null
        if (!isIpv4Address(inetAddress)) {
            return null
        }
        return normalizeResolvedAddress(inetAddress)
    }

    private fun isIpv4Address(address: InetAddress): Boolean = address is Inet4Address || address.address.size == 4

    private fun isIpv4AddressLiteral(address: String): Boolean = normalizeIpv4Literal(address) != null

    private fun setProxyWarmupMode(enabled: Boolean) {
        if (enabled) {
            latestWarmupTrafficObservation = null
        } else {
            warmupAllowedProbeHosts = emptySet()
            warmupAllowedProbeAddresses = emptySet()
            latestWarmupTrafficObservation = null
        }
        socksProxy?.setStartupWarmupMode(enabled)
        nativeTransport?.setWarmupMode(enabled)
    }

    private fun setTechniqueVerificationMode(enabled: Boolean) {
        activeTechniqueDataPlane?.setVerificationMode(enabled) ?: setProxyWarmupMode(enabled)
    }

    private fun requireTunnelBridgeRunning(context: String) {
        if (!tun2SocksActive) {
            return
        }
        val running = runCatching { tun2SocksBridge.isTunnelRunning() }.getOrDefault(false)
        if (!running) {
            throw IOException("Android tunnel backend stopped unexpectedly while $context")
        }
    }

    private fun updateUnderlyingNetworks() {
        if (tunInterface == null) {
            return
        }
        val networks = upstreamNetwork?.let { arrayOf(it) }
        runCatching { setUnderlyingNetworks(networks) }
    }

    private fun handleUpstreamUpdate(
        network: Network,
        networkCapabilities: NetworkCapabilities? = null,
    ) {
        val capabilities = networkCapabilities ?: connectivityManager.getNetworkCapabilities(network) ?: return
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return
        }
        upstreamNetwork = network
        updateUnderlyingNetworks()
        onUpstreamChanged(network, capabilities)
    }

    private fun onUpstreamChanged(network: Network, capabilities: NetworkCapabilities) {
        if (YumeVpnRuntime.state.value.activeProfileId == null) {
            return
        }
        val label = describeNetwork(capabilities)
        val handle = network.networkHandle
        if (handle == lastLoggedUpstreamHandle && label == lastLoggedUpstreamLabel) {
            return
        }
        lastLoggedUpstreamHandle = handle
        lastLoggedUpstreamLabel = label
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Upstream network: $label.")
        YumeVpnRuntime.update { state ->
            state.copy(upstreamNetworkLabel = describeUpstreamNetwork())
        }
    }

    private fun describeNetwork(network: Network): String {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "Unknown"
        return describeNetwork(capabilities)
    }

    private fun describeNetwork(capabilities: NetworkCapabilities): String {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> strings().wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> strings().cellular
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> strings().ethernet
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> strings().vpn
            else -> strings().otherNetwork
        }
    }

    private fun describeUpstreamNetwork(): String {
        val network = upstreamNetwork ?: connectivityManager.activeNetwork ?: return strings().unavailable
        return describeNetwork(network)
    }

    private fun resolveTechniqueEngine(settings: AppSettings): TunnelTechniqueEngine {
        return when (settings.connectionTechnique) {
            ConnectionTechnique.Tun2SocksStable -> Tun2SocksTechniqueEngine
            ConnectionTechnique.NativeDirectBeta -> {
                if (BuildConfig.ENABLE_NATIVE_DIRECT_BETA) {
                    NativeDirectTechniqueEngine
                } else {
                    Tun2SocksTechniqueEngine
                }
            }
            // SOCKS5-only is routed to YumeSocks5ProxyService at the
            // MainViewModel dispatch layer. If it ever lands here (e.g.
            // because a system-restart re-invokes this service after a
            // crash), fall back to the stable VPN technique rather than
            // crashing on a non-exhaustive `when`.
            ConnectionTechnique.Socks5ProxyOnly -> Tun2SocksTechniqueEngine
        }
    }

    private fun effectiveTun2SocksConfig(runtimeConfig: TunnelRuntimeConfig): Tun2SocksRuntimeConfig {
        return when (runtimeConfig) {
            is Tun2SocksRuntimeConfig -> runtimeConfig
            is NativeDirectRuntimeConfig -> runtimeConfig.bridgeTuning
            is Socks5ProxyRuntimeConfig -> runtimeConfig.bridgeTuning
        }
    }

    private fun readActiveTechniqueStats(): TechniqueStats? {
        if (!tun2SocksActive) {
            return null
        }
        val values = runCatching { tun2SocksBridge.getStats() }.getOrNull() ?: return null
        if (values.size < 4) {
            return null
        }
        return TechniqueStats(
            txPackets = values[0],
            txBytes = values[1],
            rxPackets = values[2],
            rxBytes = values[3],
        )
    }

    private fun logRouteStatsSnapshot(reason: String) {
        val stats = readActiveTechniqueStats()
        if (stats == null) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Android route stats ($reason): tunnel backend is not active.",
            )
            return
        }
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Android route stats ($reason): tun->yume packets=${stats.txPackets} bytes=${stats.txBytes}; yume->tun packets=${stats.rxPackets} bytes=${stats.rxBytes}.",
        )
    }

    private fun currentTun2SocksConfig(): Tun2SocksRuntimeConfig {
        return activeTun2SocksConfig
            ?: (Tun2SocksTechniqueEngine.runtimeConfig(PerformanceProfile.Balanced) as Tun2SocksRuntimeConfig)
    }

    private fun ensureSessionActive(generation: Long) {
        if (shutdownRequested || generation != sessionGeneration.get()) {
            throw CancellationException("VPN session replaced or disconnected")
        }
    }

    private fun buildConfigureIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startInForeground(profile: ServerProfile, status: String) {
        ensureNotificationChannel()
        val notification = buildNotification(profile, status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
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

    private fun buildNotification(profile: ServerProfile, status: String): Notification {
        val localizedContext = AppLocaleManager.current(this)
        val state = YumeVpnRuntime.state.value
        val strings = strings()
        val countryMatch = CountryLookup.lookup(localizedContext, state.exitIp, appSettingsStore.settings.value.language)
        val location = countryMatch?.let { strings.notificationLocation(it.flagEmoji, it.displayName) }
        val primaryText = when {
            state.phase == VpnPhase.Connected && state.trafficRoutedThroughYume && location != null -> {
                strings.notificationProtectedIn(location)
            }
            state.phase == VpnPhase.Connected && state.trafficRoutedThroughYume -> strings.notificationConnected
            state.phase == VpnPhase.Verifying -> strings.notificationVerifying
            state.phase == VpnPhase.Connecting -> status.ifBlank { strings.notificationConnecting }
            state.phase == VpnPhase.Disconnecting -> strings.notificationDisconnecting
            state.phase == VpnPhase.Error -> state.headline.ifBlank { strings.notificationError }
            else -> status
        }
        val stats = activeTechniqueDataPlane?.stats()
        val trafficLine = stats
            ?.takeIf { it.rxBytes > 0L || it.txBytes > 0L }
            ?.let { strings.notificationTrafficTotals(formatTrafficBytes(it.rxBytes), formatTrafficBytes(it.txBytes)) }
        val exitLine = state.exitIp?.takeIf { it.isNotBlank() }?.let { "${strings.exitIpLabel}: $it" }
        val expandedText = buildList {
            add(primaryText)
            trafficLine?.let(::add)
            exitLine?.let(::add)
        }.joinToString("\n")
        val disconnectIntent = Intent(this, YumeVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            2,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(localizedContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(AppLocaleManager.getString(localizedContext, R.string.notification_title, profile.name))
            .setContentText(primaryText)
            .setSubText(trafficLine)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setShowWhen(protectedSinceEpochMs > 0L)
            .setWhen(if (protectedSinceEpochMs > 0L) protectedSinceEpochMs else System.currentTimeMillis())
            .setUsesChronometer(protectedSinceEpochMs > 0L)
            .setContentIntent(buildConfigureIntent())
            .addAction(0, AppLocaleManager.getString(localizedContext, R.string.disconnect), disconnectPendingIntent)
            .build()
    }

    private fun maybeRefreshForeground(profile: ServerProfile) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastNotificationRefreshElapsedMs < NOTIFICATION_REFRESH_INTERVAL_MS) {
            return
        }
        val state = YumeVpnRuntime.state.value
        val status = when {
            state.phase == VpnPhase.Connected && state.trafficRoutedThroughYume -> strings().protectedNotification
            state.phase == VpnPhase.Verifying -> strings().verifyingRouteHeadline
            state.phase == VpnPhase.Connecting -> state.headline
            else -> return
        }
        updateForeground(profile, status)
    }

    private fun formatTrafficBytes(bytes: Long): String {
        if (bytes <= 0L) {
            return "0 B"
        }
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val formatted = if (value >= 100 || unitIndex == 0) {
            value.toLong().toString()
        } else if (value >= 10) {
            String.format(Locale.ROOT, "%.1f", value)
        } else {
            String.format(Locale.ROOT, "%.2f", value)
        }.trimEnd('0').trimEnd('.')
        return "$formatted ${units[unitIndex]}"
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
        private const val ACTION_CONNECT = "com.fixcraft.yume.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.fixcraft.yume.action.DISCONNECT"
        private const val EXTRA_PROFILE_ID = "extra_profile_id"
        private const val EXTRA_AUTH_KEY_PASSWORD = "extra_auth_key_password"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val MAX_SYSTEM_PROBE_RESPONSE_BYTES = 32 * 1024
        private const val EXCLUDE_YUME_APP_FROM_ANDROID_VPN = false
        private const val CAPTURE_IPV6_IN_ANDROID_VPN = false
        private const val VPN_IPV4_PREFIX_LENGTH = 24
        private const val KEEPALIVE_PING_INTERVAL_MS = 15_000L
        private const val PRE_ROUTING_PING_TIMEOUT_MS = 6_000
        // How many consecutive PING failures we tolerate before we
        // consider the session dead and re-auth. One stuck PING under
        // upload backpressure (TCP send buffer full → writer blocked
        // in output.write) used to nuke the session mid-speed-test;
        // re-auth then dropped active SOCKS streams and surfaced as
        // "server sort error" / 0.3 Mbps stalls. Two consecutive misses
        // (~36 s of silence) is still well below any sane idle limit
        // and gives the writer time to drain the kernel buffer.
        private const val KEEPALIVE_PING_FAILURES_BEFORE_RECONNECT = 2
        private const val TUN2SOCKS_STARTUP_DELAY_MS = 200L
        private const val TUNNEL_BACKEND_START_GRACE_MS = 80L
        private const val TUNNEL_BACKEND_START_MAX_ATTEMPTS = 4
        private const val TUNNEL_BACKEND_START_RETRY_DELAY_MS = 500L
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 2_500L
        private const val ROUTE_STATS_LOG_INTERVAL_MS = 10_000L
        private const val SYSTEM_PROBE_CONNECT_HEADROOM_MS = 0
        private const val SYSTEM_PROBE_READ_HEADROOM_MS = 0
        private const val MAX_SYSTEM_PROBE_CONNECT_ATTEMPTS = 2
        private const val WARMUP_BYPASS_OBSERVATION_MAX_AGE_MS = 30_000L
        private const val NATIVE_DIRECT_PLACEHOLDER_PORT = 9
        private const val NATIVE_DIRECT_ROUTE_LABEL = "native-direct"
        private val RELAY_PROBE_TARGETS = listOf(
            Triple("ifconfig.me", "/ip", 80),
            Triple("api.ipify.org", "/", 80),
            Triple("icanhazip.com", "/", 80),
        )
        private val STARTUP_WARMUP_PROBE_TCP_PORTS = setOf(80, 443)
        private const val VPN_IPV4_ADDRESS = "10.77.0.2"
        private const val VPN_IPV6_ADDRESS = "fd77:77::2"
        private const val MAPDNS_ADDRESS = "198.18.0.2"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
        private const val MAPDNS_CACHE_SIZE = 10_000
        private const val NOTIFICATION_ID = 1107
        private const val NOTIFICATION_CHANNEL_ID = "yume_vpn_service"

        fun startConnect(context: Context, profileId: String, authKeyPassword: String? = null) {
            val intent = Intent(context, YumeVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_AUTH_KEY_PASSWORD, authKeyPassword)
            }
            context.startForegroundService(intent)
        }

        fun startDisconnect(context: Context) {
            val intent = Intent(context, YumeVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
