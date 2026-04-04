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
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import hev.htproxy.TProxyService
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.MainActivity
import com.fixcraft.yume.R
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.ServerProfileStore
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.transport.YumeBootstrapClient
import com.fixcraft.yume.transport.YumeBootstrapException
import com.fixcraft.yume.transport.YumeBootstrapSession
import com.fixcraft.yume.transport.YumeLifecycleAck
import com.fixcraft.yume.transport.YumePresenceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
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
    private val secureMaterialStore by lazy { SecureMaterialStore.get(applicationContext) }
    private val tun2SocksBridge by lazy { TProxyService() }
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private var connectJob: Job? = null
    private var disconnectJob: Job? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var bootstrapSession: YumeBootstrapSession? = null
    private var socksProxy: YumeSocks5Proxy? = null
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
    private var detachedTunFd: Int = -1
    @Volatile
    private var currentPresence: YumePresenceInfo? = null
    @Volatile
    private var lifecycleUnsupportedLogged = false
    @Volatile
    private var metadataChannelEnabled = false
    @Volatile
    private var hopCompatibilityMode = false
    @Volatile
    private var innerCompatibilityMode = false
    @Volatile
    private var selfExcludedFromVpnPath = false
    private val lifecycleMutex = Mutex()
    private val sessionGeneration = AtomicLong(0)

    private data class RouteVerificationResult(
        val exitIp: String?,
        val systemTrafficVerified: Boolean,
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
                                headline = "Upstream lost",
                                detail = "Wi-Fi or cellular disappeared. Yume is waiting to rebuild the authenticated control path.",
                                upstreamNetworkLabel = "Unavailable",
                                controlSocketProtected = false,
                                localProxyAddress = this@YumeVpnService.localProxyAddress,
                            )
                        }
                    }
                    activeProfile?.let { profile ->
                        updateForeground(profile, "Waiting for network")
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
                if (profileId.isNullOrBlank()) {
                    fail("Missing VPN profile")
                } else {
                    shutdownRequested = false
                    sessionGeneration.incrementAndGet()
                    connectJob?.cancel()
                    connectJob = serviceScope.launch {
                        connect(profileId)
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
        disconnectResources()
        activeProfile = null
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(upstreamCallback) }
            networkCallbackRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun connect(profileId: String) {
        val generation = sessionGeneration.get()
        val profile = profileStore.getProfile(profileId)
        if (profile == null) {
            fail("Selected server profile no longer exists")
            return
        }
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
                secureMaterialStore.readMaterialText(profile.authKeyMaterialId)
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
                headline = "Connecting",
                detail = "Loading ${authKeySummary.displayName} and ${caSummary.displayName}, then opening an authenticated Yume control path to ${profile.endpointLabel}.",
                endpointLabel = profile.endpointLabel,
                sessionDnsServers = profile.dnsServers,
                sessionMtu = profile.mtu,
                upstreamNetworkLabel = describeUpstreamNetwork(),
                veritySummary = "Using ${caSummary.displayName}",
            )
        )
        lastLoggedUpstreamHandle = Long.MIN_VALUE
        lastLoggedUpstreamLabel = null
        lifecycleUnsupportedLogged = false
        metadataChannelEnabled = false
        hopCompatibilityMode = ANDROID_HOP_COMPATIBILITY_DEFAULT
        innerCompatibilityMode = ANDROID_INNER_COMPATIBILITY_DEFAULT
        selfExcludedFromVpnPath = false
        currentPresence = null
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Starting Yume Android session for ${profile.name} (${profile.endpointLabel}).")
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Requested protection policy: ${YumeVpnRuntime.state.value.requestedProtection}.")
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Loaded anonym CA: ${caSummary.displayName}.")
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Loaded auth key: ${authKeySummary.displayName}.")
        startInForeground(profile, "Authenticating to Yume over 443")

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
            session = registerMetadataChannel(
                session = session,
                profile = profile,
                anonymCaPem = anonymCaPem,
                authKeyPem = authKeyPem,
                generation = generation,
            )
            bootstrapSession = session
            var bootstrap = session.bootstrap
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Server ${bootstrap.serverVersion} mode ${bootstrap.serverMode}; ${bootstrap.verity.summary}.",
            )
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Selected protection target: ${buildRequestedProtectionSummary(bootstrap)}.",
            )
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Effective protection for this Yume session: ${buildEffectiveProtectionSummary(bootstrap)}.",
            )
            ensureSessionActive(generation)
            val initialRelayProbe = probeRelayCanaryWithCompatibilityFallback(
                session = session,
                profile = profile,
                anonymCaPem = anonymCaPem,
                authKeyPem = authKeyPem,
                generation = generation,
            )
            session = initialRelayProbe.first
            bootstrapSession = session
            bootstrap = session.bootstrap
            val exitIpResult = initialRelayProbe.second
            val relayExitIp = requireVerifiedRelayCanary(session, bootstrap, exitIpResult)
            ensureSessionActive(generation)
            awaitPreRoutingStability(session, generation)
            val proxyAddress = ensureSocksProxyStarted()
            val verifiedExitIpResult = Result.success(relayExitIp)
            markRelayReady(profile, bootstrap, verifiedExitIpResult, proxyAddress)

            if (tunInterface == null) {
                tunInterface = buildVpnInterface(profile)
                    ?: throw IOException("Android denied the VPN interface")
                updateUnderlyingNetworks()
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Info,
                    "Android VPN interface established in dual-stack fail-closed mode with local mapped DNS $MAPDNS_ADDRESS, remote resolvers ${effectiveVpnDnsServers(profile).joinToString(", ")}, and MTU ${profile.mtu}.",
                )
            }
            setProxyWarmupMode(true)
            ensureTun2SocksRunning(profile, proxyAddress)
            markRelayReady(profile, bootstrap, exitIpResult, proxyAddress)
            val routedExitIp = verifySystemTrafficFlow(
                profile = profile,
                bootstrap = bootstrap,
                relayExitIpResult = verifiedExitIpResult,
                proxyAddress = proxyAddress,
                session = session,
                generation = generation,
            )
            awaitPostRoutingTrafficRamp(session, generation)
            setProxyWarmupMode(false)
            markTrafficFlowing(profile, bootstrap, verifiedExitIpResult, routedExitIp, proxyAddress)
            updateForeground(profile, "Protected via Yume")
            var nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS

            while (true) {
                currentCoroutineContext().ensureActive()
                ensureSessionActive(generation)
                delay(1_500)
                val network = upstreamNetwork ?: connectivityManager.activeNetwork
                if (network == null) {
                    setProxyWarmupMode(true)
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
                    session = registerMetadataChannel(
                        session = session,
                        profile = profile,
                        anonymCaPem = anonymCaPem,
                        authKeyPem = authKeyPem,
                        generation = generation,
                    )
                    bootstrapSession = session
                    bootstrap = session.bootstrap
                    val relayProbe = probeRelayCanaryWithCompatibilityFallback(
                        session = session,
                        profile = profile,
                        anonymCaPem = anonymCaPem,
                        authKeyPem = authKeyPem,
                        generation = generation,
                    )
                    session = relayProbe.first
                    bootstrapSession = session
                    bootstrap = session.bootstrap
                    val relayExitIpResult = relayProbe.second
                    val relayExitIp = requireVerifiedRelayCanary(session, bootstrap, relayExitIpResult)
                    awaitPreRoutingStability(session, generation)
                    val verifiedRelayExitIpResult = Result.success(relayExitIp)
                    markRelayReady(profile, bootstrap, verifiedRelayExitIpResult, ensureSocksProxyStarted())
                    setProxyWarmupMode(true)
                    verifySystemTrafficFlow(
                        profile = profile,
                        bootstrap = bootstrap,
                        relayExitIpResult = verifiedRelayExitIpResult,
                        proxyAddress = ensureSocksProxyStarted(),
                        session = session,
                        generation = generation,
                    ).also { routedExitIp ->
                        awaitPostRoutingTrafficRamp(session, generation)
                        setProxyWarmupMode(false)
                        markTrafficFlowing(profile, bootstrap, verifiedRelayExitIpResult, routedExitIp, ensureSocksProxyStarted())
                    }
                    nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
                    continue
                }
                val liveSession = bootstrapSession
                if (liveSession == null || !liveSession.isAlive) {
                    val closeDetail = liveSession?.closeReason?.takeIf { it.isNotBlank() } ?: "unknown reason"
                    val reopenedWithoutInner = maybeEnableInnerCompatibilityForCorruption(liveSession, closeDetail)
                    setProxyWarmupMode(true)
                    bootstrapSession = reconnectBootstrapSession(
                        profile = profile,
                        anonymCaPem = anonymCaPem,
                        authKeyPem = authKeyPem,
                        reason = if (reopenedWithoutInner) {
                            "Reopening authenticated Yume control path with inner crypto disabled after Android corruption"
                        } else {
                            "Authenticated Yume control path lost ($closeDetail)"
                        },
                        generation = generation,
                    )
                    ensureSessionActive(generation)
                    session = requireNotNull(bootstrapSession)
                    session = registerMetadataChannel(
                        session = session,
                        profile = profile,
                        anonymCaPem = anonymCaPem,
                        authKeyPem = authKeyPem,
                        generation = generation,
                    )
                    bootstrapSession = session
                    bootstrap = session.bootstrap
                    val relayProbe = probeRelayCanaryWithCompatibilityFallback(
                        session = session,
                        profile = profile,
                        anonymCaPem = anonymCaPem,
                        authKeyPem = authKeyPem,
                        generation = generation,
                    )
                    session = relayProbe.first
                    bootstrapSession = session
                    bootstrap = session.bootstrap
                    val relayExitIpResult = relayProbe.second
                    val relayExitIp = requireVerifiedRelayCanary(session, bootstrap, relayExitIpResult)
                    awaitPreRoutingStability(session, generation)
                    val verifiedRelayExitIpResult = Result.success(relayExitIp)
                    markRelayReady(profile, bootstrap, verifiedRelayExitIpResult, ensureSocksProxyStarted())
                    setProxyWarmupMode(true)
                    verifySystemTrafficFlow(
                        profile = profile,
                        bootstrap = bootstrap,
                        relayExitIpResult = verifiedRelayExitIpResult,
                        proxyAddress = ensureSocksProxyStarted(),
                        session = session,
                        generation = generation,
                    ).also { routedExitIp ->
                        awaitPostRoutingTrafficRamp(session, generation)
                        setProxyWarmupMode(false)
                        markTrafficFlowing(profile, bootstrap, verifiedRelayExitIpResult, routedExitIp, ensureSocksProxyStarted())
                    }
                    nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
                    continue
                }
                if (System.currentTimeMillis() >= nextPingAtMs) {
                    try {
                        liveSession.ping()
                    } catch (ex: Exception) {
                        val pingDetail = ex.message ?: "connection lost"
                        val reopenedWithoutInner = maybeEnableInnerCompatibilityForCorruption(liveSession, pingDetail)
                        YumeVpnRuntime.appendLog(
                            VpnLogLevel.Warn,
                            "Yume PING failed: $pingDetail. Re-authenticating.",
                        )
                        setProxyWarmupMode(true)
                        bootstrapSession = reconnectBootstrapSession(
                            profile = profile,
                            anonymCaPem = anonymCaPem,
                            authKeyPem = authKeyPem,
                            reason = if (reopenedWithoutInner) {
                                "Reopening authenticated Yume control path with inner crypto disabled after keepalive corruption"
                            } else {
                                "Yume keepalive failed"
                            },
                            generation = generation,
                        )
                        ensureSessionActive(generation)
                        session = requireNotNull(bootstrapSession)
                        session = registerMetadataChannel(
                            session = session,
                            profile = profile,
                            anonymCaPem = anonymCaPem,
                            authKeyPem = authKeyPem,
                            generation = generation,
                        )
                        bootstrapSession = session
                        bootstrap = session.bootstrap
                        val relayProbe = probeRelayCanaryWithCompatibilityFallback(
                            session = session,
                            profile = profile,
                            anonymCaPem = anonymCaPem,
                            authKeyPem = authKeyPem,
                            generation = generation,
                        )
                        session = relayProbe.first
                        bootstrapSession = session
                        bootstrap = session.bootstrap
                        val relayExitIpResult = relayProbe.second
                        val relayExitIp = requireVerifiedRelayCanary(session, bootstrap, relayExitIpResult)
                        awaitPreRoutingStability(session, generation)
                        val verifiedRelayExitIpResult = Result.success(relayExitIp)
                        markRelayReady(profile, bootstrap, verifiedRelayExitIpResult, ensureSocksProxyStarted())
                        setProxyWarmupMode(true)
                        verifySystemTrafficFlow(
                            profile = profile,
                            bootstrap = bootstrap,
                            relayExitIpResult = verifiedRelayExitIpResult,
                            proxyAddress = ensureSocksProxyStarted(),
                            session = session,
                            generation = generation,
                        ).also { routedExitIp ->
                            awaitPostRoutingTrafficRamp(session, generation)
                            setProxyWarmupMode(false)
                            markTrafficFlowing(profile, bootstrap, verifiedRelayExitIpResult, routedExitIp, ensureSocksProxyStarted())
                        }
                    }
                    nextPingAtMs = System.currentTimeMillis() + KEEPALIVE_PING_INTERVAL_MS
                }
                YumeVpnRuntime.update { state ->
                    state.copy(upstreamNetworkLabel = describeUpstreamNetwork())
                }
            }
        } catch (_: CancellationException) {
            disconnectResources()
        } catch (ex: Exception) {
            if (shutdownRequested || generation != sessionGeneration.get()) {
                disconnectResources()
                return
            }
            val session = bootstrapSession
            if (session != null && session.isAlive) {
                runCatching {
                    sendLifecycleUpdate(
                        session = session,
                        state = "error",
                        message = "error, disconnect",
                        detail = ex.message ?: "vpn startup failed",
                        effectiveProtection = buildEffectiveProtectionSummary(session.bootstrap),
                        errorCode = "vpn_start_failed",
                        timeoutMs = 1_200,
                    )
                }
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
        val currentState = YumeVpnRuntime.state.value
        val activeProfileId = currentState.activeProfileId
        if (connectJob != null || tunInterface != null || bootstrapSession != null || tun2SocksActive) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, message)
            YumeVpnRuntime.update(
                currentState.copy(
                    phase = VpnPhase.Disconnecting,
                    headline = "Disconnecting",
                    detail = message,
                    upstreamNetworkLabel = describeUpstreamNetwork(),
                )
            )
        }
        
        // Cancel and wait for connect job to complete before proceeding
        if (connectJob != null) {
            connectJob?.cancel()
            // Note: We intentionally do NOT join here synchronously as we're on the main handler thread.
            // The connectJob will be properly cleaned up in the disconnectJob finally block.
        }
        
        val session = bootstrapSession
        disconnectJob = serviceScope.launch {
            try {
                // Send lifecycle update with strict timeout to prevent hanging
                if (session != null && session.isAlive) {
                    try {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                sendLifecycleUpdate(
                                    session = session,
                                    state = "disconnecting",
                                    message = "im disconnecting",
                                    detail = message,
                                    effectiveProtection = buildEffectiveProtectionSummary(session.bootstrap),
                                    trafficVerified = currentState.trafficRoutedThroughYume,
                                    exitIp = currentState.exitIp.orEmpty(),
                                    timeoutMs = 500,  // Reduced from 1200ms to prevent disconnect hangs
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Lifecycle update cancelled during disconnect")
                        // Continue with disconnect even if cancelled
                    } catch (e: Throwable) {
                        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Lifecycle update failed during disconnect: ${e.javaClass.simpleName}")
                        // Continue with disconnect even if lifecycle update fails
                    }
                }
            } finally {
                // Always cleanup resources, even if lifecycle update fails
                lifecycleMutex.withLock {
                    disconnectResources()
                }
                activeProfile = null
                currentPresence = null
                YumeVpnRuntime.update(
                    VpnConnectionState(
                        phase = VpnPhase.Idle,
                        headline = "Disconnected",
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
    }

    private fun disconnectResources() {
        // Cleanup in proper order: stop active components first, then close file descriptors
        
        // 1. Stop SOCKS5 proxy (this should happen BEFORE stopping tun2socks)
        runCatching { socksProxy?.setStartupWarmupMode(false) }
        runCatching { socksProxy?.stop() }
        
        // 2. Stop TUN2SOCKS bridge (only after proxy is stopped)
        runCatching { tun2SocksBridge.TProxyStopService() }
        
        // 3. Close bootstrap session
        runCatching { bootstrapSession?.close() }
        
        // 4. Close socket if still open
        runCatching { inFlightSocket?.close() }
        
        // 5. Close TUN interface file descriptor
        runCatching { tunInterface?.close() }
        
        // 6. Clear all references
        detachedTunFd = -1
        inFlightSocket = null
        tun2SocksActive = false
        tunInterface = null
        bootstrapSession = null
        socksProxy = null
        localProxyAddress = null
        hopCompatibilityMode = ANDROID_HOP_COMPATIBILITY_DEFAULT
        innerCompatibilityMode = ANDROID_INNER_COMPATIBILITY_DEFAULT
        selfExcludedFromVpnPath = false
        lastLoggedUpstreamHandle = Long.MIN_VALUE
        lastLoggedUpstreamLabel = null
    }

    private fun fail(message: String, profileId: String? = null) {
        shutdownRequested = true
        sessionGeneration.incrementAndGet()
        connectJob = null
        disconnectJob?.cancel()
        disconnectJob = null
        
        // Use try-finally to ensure proper lock release even if disconnectResources fails
        serviceScope.launch {
            try {
                lifecycleMutex.withLock {
                    disconnectResources()
                }
            } catch (e: Throwable) {
                YumeVpnRuntime.appendLog(VpnLogLevel.Error, "Error during disconnect in fail(): ${e.message}")
                // Even if lock fails, still try to disconnect
                runCatching { disconnectResources() }
            }
        }
        
        currentPresence = null
        YumeVpnRuntime.appendLog(VpnLogLevel.Error, message)
        YumeVpnRuntime.update(
            VpnConnectionState(
                phase = VpnPhase.Error,
                activeProfileId = profileId,
                headline = "Connection failed",
                detail = message,
                upstreamNetworkLabel = describeUpstreamNetwork(),
                requestedProtection = YumeVpnRuntime.state.value.requestedProtection,
                transportSummary = YumeVpnRuntime.state.value.transportSummary,
                innerSummary = YumeVpnRuntime.state.value.innerSummary,
                hopSummary = YumeVpnRuntime.state.value.hopSummary,
                veritySummary = YumeVpnRuntime.state.value.veritySummary,
                routingSummary = YumeVpnRuntime.state.value.routingSummary,
                yumeProtocolVersion = YumeVpnRuntime.state.value.yumeProtocolVersion,
                basefwxVersion = YumeVpnRuntime.state.value.basefwxVersion,
                serverMode = YumeVpnRuntime.state.value.serverMode,
                serverVersion = YumeVpnRuntime.state.value.serverVersion,
                peerCertificateFingerprint = YumeVpnRuntime.state.value.peerCertificateFingerprint,
                exitIp = YumeVpnRuntime.state.value.exitIp,
                relaySummary = YumeVpnRuntime.state.value.relaySummary,
                localProxyAddress = YumeVpnRuntime.state.value.localProxyAddress,
            )
        )
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
                preferHopRequested = !hopCompatibilityMode,
                allowInnerProtection = !innerCompatibilityMode,
                log = { message -> YumeVpnRuntime.appendLog(VpnLogLevel.Info, message) },
            )
        } catch (throwable: Exception) {
            if (shouldRetryWithHop(throwable)) {
                hopCompatibilityMode = false
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Warn,
                    "Server requires inner hopping for ${profile.endpointLabel}. Reconnecting with hopping enabled for this Android Yume session.",
                )
                ensureSessionActive(generation)
                return reconnectBootstrapSession(
                    profile = profile,
                    anonymCaPem = anonymCaPem,
                    authKeyPem = authKeyPem,
                    reason = "Reopening authenticated Yume control path with inner hopping enabled",
                    generation = generation,
                )
            }
            throw throwable
        } finally {
            inFlightSocket = null
        }
        ensureSessionActive(generation)
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Authenticated control path is active over ${describeUpstreamNetwork()} (${session.bootstrap.tlsProtocol}).",
        )
        if (hopCompatibilityMode) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Android compatibility mode is active: inner crypto hopping is disabled for this Yume session.",
            )
        }
        if (innerCompatibilityMode) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Android compatibility mode is active: session-side inner crypto is disabled for this Yume session after a prior corruption-style reset.",
            )
        }
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
                        headline = "Waiting for network",
                        detail = "Wi-Fi or cellular is unavailable, so Yume cannot rebuild the protected control path yet.",
                        upstreamNetworkLabel = "Unavailable",
                        controlSocketProtected = false,
                    )
                }
                updateForeground(profile, "Waiting for network")
            }
            delay(1_000)
        }
    }

    private suspend fun announcePresence(
        session: YumeBootstrapSession,
        profile: ServerProfile,
    ): YumePresenceInfo {
        val preferredName = androidClientName(profile)
        val response = session.sendControlRequest(
            JSONObject()
                .put("cmd", "presence.announce")
                .put("endpoint_kind", "client")
                .put("preferred_name", preferredName)
                .put("hostname", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                .put("client_platform", "android")
                .put("client_variant", "android_vpn")
                .put("client_version", BuildConfig.VERSION_NAME)
                .put("relay_mode", "untrusted")
                .put("allow_chat", false)
                .put("allow_file", false)
                .put("allow_bytes", false)
                .put("allow_inbound_admin", false)
                .put("allow_outbound_admin", false),
            timeoutMs = 2_000,
        )
        if (!response.optBoolean("ok")) {
            throw YumeBootstrapException(response.optString("error", "presence announce failed"))
        }
        val endpoint = response.optJSONObject("endpoint")
            ?: throw YumeBootstrapException("The Yume server did not return presence endpoint info")
        val info = YumePresenceInfo(
            endpointId = endpoint.optString("endpoint_id").trim(),
            displayName = endpoint.optString("display_name").trim().ifBlank { preferredName },
            serverId = response.optString("server_id").trim(),
            serverName = response.optString("server_name").trim(),
        )
        YumeVpnRuntime.update { state ->
            state.copy(
                clientPlatform = "android",
                clientVariant = "android_vpn",
                clientVersion = BuildConfig.VERSION_NAME,
            )
        }
        return info
    }

    private suspend fun registerMetadataChannel(
        session: YumeBootstrapSession,
        profile: ServerProfile,
        anonymCaPem: String,
        authKeyPem: String,
        generation: Long,
    ): YumeBootstrapSession {
        if (!metadataChannelEnabled) {
            currentPresence = syntheticPresence(profile)
            return session
        }
        return try {
            currentPresence = announcePresence(session, profile)
            session
        } catch (throwable: Exception) {
            if (shouldRetryWithoutHop(session, throwable)) {
                val reopened = reopenWithHopCompatibility(
                    profile = profile,
                    anonymCaPem = anonymCaPem,
                    authKeyPem = authKeyPem,
                    generation = generation,
                    reason = "Presence announce closed the hopped Yume session",
                )
                return registerMetadataChannel(
                    session = reopened,
                    profile = profile,
                    anonymCaPem = anonymCaPem,
                    authKeyPem = authKeyPem,
                    generation = generation,
                )
            }
            metadataChannelEnabled = false
            currentPresence = syntheticPresence(profile)
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Presence announce failed: ${throwable.message ?: "unknown error"}. Reopening the Yume session without the metadata channel.",
            )
            reopenWithoutMetadataChannel(
                session = session,
                profile = profile,
                anonymCaPem = anonymCaPem,
                authKeyPem = authKeyPem,
                generation = generation,
                reason = "Reopening authenticated Yume control path without metadata channel",
            )
        }
    }

    private fun syntheticPresence(profile: ServerProfile): YumePresenceInfo {
        return YumePresenceInfo(
            endpointId = "",
            displayName = androidClientName(profile),
            serverId = "",
            serverName = profile.serverName,
        )
    }

    private suspend fun sendLifecycleUpdate(
        session: YumeBootstrapSession,
        state: String,
        message: String,
        detail: String = "",
        effectiveProtection: String = "",
        trafficVerified: Boolean = false,
        exitIp: String = "",
        errorCode: String = "",
        timeoutMs: Int = 1_500,
    ): YumeLifecycleAck? {
        if (!metadataChannelEnabled) {
            return null
        }
        val response = try {
            session.sendControlRequest(
                JSONObject()
                    .put("cmd", "client.lifecycle")
                    .put("state", state)
                    .put("message", message)
                    .put("detail", detail)
                    .put("client_platform", "android")
                    .put("client_variant", "android_vpn")
                    .put("client_version", BuildConfig.VERSION_NAME)
                    .put("effective_protection", effectiveProtection)
                    .put("traffic_verified", trafficVerified)
                    .put("exit_ip", exitIp)
                    .put("error_code", errorCode),
                timeoutMs = timeoutMs,
            )
        } catch (throwable: Exception) {
            metadataChannelEnabled = false
            activeProfile?.let { profile ->
                currentPresence = syntheticPresence(profile)
            }
            logLifecycleUnsupportedOnce(throwable.message ?: "lifecycle request failed")
            return null
        }
        if (!response.optBoolean("ok")) {
            val error = response.optString("error", "lifecycle update failed")
            if (error == "unknown control command") {
                logLifecycleUnsupportedOnce(error)
                return null
            }
            throw YumeBootstrapException(error)
        }
        val ack = YumeLifecycleAck(
            acceptedState = response.optString("accepted_state").trim().ifBlank { state },
            serverTimeMs = response.optLong("server_time_ms", 0L),
        )
        YumeVpnRuntime.update { current ->
            current.copy(
                latestLifecycleState = ack.acceptedState,
                latestLifecycleMessage = message,
                latestLifecycleAckMs = ack.serverTimeMs,
            )
        }
        return ack
    }

    private fun logLifecycleUnsupportedOnce(reason: String) {
        if (lifecycleUnsupportedLogged) {
            return
        }
        lifecycleUnsupportedLogged = true
        YumeVpnRuntime.appendLog(VpnLogLevel.Warn, "Server lifecycle notifications unavailable: $reason.")
    }

    private suspend fun requireVerifiedRelayCanary(
        session: YumeBootstrapSession,
        bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap,
        relayExitIpResult: Result<String>,
    ): String {
        val exitIp = relayExitIpResult.getOrElse { throwable ->
            val detail = describeRelayProbeFailure(session, throwable)
            runCatching {
                sendLifecycleUpdate(
                    session = session,
                    state = "error",
                    message = "error, disconnect",
                    detail = detail,
                    effectiveProtection = buildEffectiveProtectionSummary(bootstrap),
                    errorCode = "relay_probe_failed",
                    timeoutMs = 1_200,
                )
            }
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
            runCatching {
                sendLifecycleUpdate(
                    session = session,
                    state = "error",
                    message = "error, disconnect",
                    detail = detail,
                    effectiveProtection = buildEffectiveProtectionSummary(bootstrap),
                    errorCode = "relay_session_unavailable",
                    timeoutMs = 1_200,
                )
            }
            throw YumeBootstrapException(
                "Authenticated Yume relay session became unavailable before Android tunnel startup: $detail",
            )
        }
        return exitIp
    }

    private suspend fun probeRelayCanaryWithCompatibilityFallback(
        session: YumeBootstrapSession,
        profile: ServerProfile,
        anonymCaPem: String,
        authKeyPem: String,
        generation: Long,
    ): Pair<YumeBootstrapSession, Result<String>> {
        var liveSession = session
        var relayExitIpResult = probeExitIp()
        if (shouldRetryWithoutHop(liveSession, relayExitIpResult.exceptionOrNull())) {
            liveSession = reopenWithHopCompatibility(
                profile = profile,
                anonymCaPem = anonymCaPem,
                authKeyPem = authKeyPem,
                generation = generation,
                reason = "Relay canary closed the hopped Yume session",
            )
            liveSession = registerMetadataChannel(
                session = liveSession,
                profile = profile,
                anonymCaPem = anonymCaPem,
                authKeyPem = authKeyPem,
                generation = generation,
            )
            bootstrapSession = liveSession
            relayExitIpResult = probeExitIp()
        }
        if (shouldRetryFreshSession(liveSession, relayExitIpResult.exceptionOrNull())) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Relay canary failed on the current Yume session. Reopening a fresh authenticated Yume session and retrying the relay canary without the metadata channel.",
            )
            liveSession = reopenWithoutMetadataChannel(
                session = liveSession,
                profile = profile,
                anonymCaPem = anonymCaPem,
                authKeyPem = authKeyPem,
                generation = generation,
                reason = "Reopening authenticated Yume control path after relay canary failure",
            )
            bootstrapSession = liveSession
            relayExitIpResult = probeExitIp()
        }
        return liveSession to relayExitIpResult
    }

    private fun shouldRetryWithoutHop(
        session: YumeBootstrapSession,
        throwable: Throwable?,
    ): Boolean {
        if (hopCompatibilityMode || !session.bootstrap.hopEnabled) {
            return false
        }
        val detail = buildRelayRetryDetail(session, throwable)
        return detail.contains("reached eof while reading yume frame") ||
            detail.contains("bad record mac") ||
            detail.contains("sslv3_alert_bad_record_mac") ||
            detail.contains("stream truncated") ||
            detail.contains("protocol error") ||
            detail.contains("the authenticated yume session is unavailable") ||
            detail.contains("failed to open a yume tcp stream")
    }

    private fun shouldRetryFreshSession(
        session: YumeBootstrapSession,
        throwable: Throwable?,
    ): Boolean {
        val detail = buildRelayRetryDetail(session, throwable)
        return detail.contains("reached eof while reading yume frame") ||
            detail.contains("the authenticated yume session is unavailable") ||
            detail.contains("failed to open a yume tcp stream")
    }

    private fun shouldRetryWithHop(
        throwable: Throwable?,
    ): Boolean {
        if (!hopCompatibilityMode) {
            return false
        }
        val detail = buildThrowableDetail(throwable)
        return detail.contains("requires hopping")
    }

    private fun buildRelayRetryDetail(
        session: YumeBootstrapSession,
        throwable: Throwable?,
    ): String {
        val parts = linkedSetOf<String>()
        generateSequence(throwable) { current -> current.cause }
            .mapNotNull { current -> current.message?.trim()?.takeIf { it.isNotEmpty() } }
            .forEach(parts::add)
        session.closeReason?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        return parts.joinToString(" ").lowercase()
    }

    private fun buildThrowableDetail(throwable: Throwable?): String {
        return generateSequence(throwable) { current -> current.cause }
            .mapNotNull { current -> current.message?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString(" ")
            .lowercase(Locale.US)
    }

    private suspend fun reopenWithHopCompatibility(
        profile: ServerProfile,
        anonymCaPem: String,
        authKeyPem: String,
        generation: Long,
        reason: String,
    ): YumeBootstrapSession {
        hopCompatibilityMode = true
        runCatching { bootstrapSession?.close() }
        bootstrapSession = null
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Warn,
            "$reason. Reconnecting once with inner hopping disabled for Android compatibility.",
        )
        ensureSessionActive(generation)
        return reconnectBootstrapSession(
            profile = profile,
            anonymCaPem = anonymCaPem,
            authKeyPem = authKeyPem,
            reason = "Reopening authenticated Yume control path with inner hopping disabled",
            generation = generation,
        )
    }

    private suspend fun reopenWithoutMetadataChannel(
        session: YumeBootstrapSession,
        profile: ServerProfile,
        anonymCaPem: String,
        authKeyPem: String,
        generation: Long,
        reason: String,
    ): YumeBootstrapSession {
        metadataChannelEnabled = false
        currentPresence = syntheticPresence(profile)
        runCatching { session.close() }
        bootstrapSession = null
        ensureSessionActive(generation)
        return reconnectBootstrapSession(
            profile = profile,
            anonymCaPem = anonymCaPem,
            authKeyPem = authKeyPem,
            reason = reason,
            generation = generation,
        )
    }

    private fun buildVpnInterface(profile: ServerProfile): ParcelFileDescriptor? {
        val dnsServers = vpnInterfaceDnsServers()
        val ignoredDnsServers = profile.dnsServers.filterNot(::isIpv4AddressLiteral)
        if (ignoredDnsServers.isNotEmpty()) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Ignoring IPv6 DNS servers on Android until Yume ships native IPv6 relay support. IPv6 stays captured and fail-closed: ${ignoredDnsServers.joinToString(", ")}.",
            )
        }
        val builder = Builder()
            .setSession("Yume ${profile.name}")
            .setConfigureIntent(buildConfigureIntent())
            .setBlocking(false)
            .setMtu(profile.mtu)
            .addAddress(VPN_IPV4_ADDRESS, 32)
            .addAddress(VPN_IPV6_ADDRESS, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        dnsServers.forEach(builder::addDnsServer)
        builder.allowFamily(OsConstants.AF_INET)
        builder.allowFamily(OsConstants.AF_INET6)
        applyVpnBypassPolicy(builder)
        return builder.establish()
    }

    private suspend fun awaitPreRoutingStability(
        session: YumeBootstrapSession,
        generation: Long,
    ) {
        ensureSessionActive(generation)
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Holding the authenticated Yume session briefly before enabling full Android routing.",
        )
        YumeVpnRuntime.update { state ->
            if (state.phase == VpnPhase.Disconnecting || state.phase == VpnPhase.Idle || state.phase == VpnPhase.Error) {
                state
            } else {
                state.copy(
                    phase = VpnPhase.BootstrapReady,
                    headline = "Verifying stability",
                    detail = "Authenticated Yume relay traffic is ready. Waiting briefly before enabling full Android routing.",
                    controlSocketProtected = true,
                    localProxyAddress = localProxyAddress,
                )
            }
        }
        delay(PRE_ROUTING_STABILITY_DELAY_MS)
        ensureSessionActive(generation)
        session.ping(timeoutMs = PRE_ROUTING_PING_TIMEOUT_MS)
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Authenticated Yume control path passed the pre-routing stability check.",
        )
    }

    private suspend fun ensureTun2SocksRunning(profile: ServerProfile, proxyAddress: String) {
        if (tun2SocksActive) {
            return
        }
        val tun = tunInterface ?: throw IOException("Android VPN interface is unavailable")
        val proxyHost = proxyAddress.substringBeforeLast(":", LOOPBACK_HOST)
        val proxyPort = proxyAddress.substringAfterLast(":").toIntOrNull()
            ?: throw IOException("Invalid local SOCKS5 address: $proxyAddress")
        val configFile = writeTun2SocksConfig(profile, proxyHost, proxyPort)
        val detachedFd = ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd()
        detachedTunFd = detachedFd
        try {
            tun2SocksBridge.TProxyStartService(configFile.absolutePath, detachedFd)
        } catch (throwable: Throwable) {
            detachedTunFd = -1
            runCatching { ParcelFileDescriptor.adoptFd(detachedFd).close() }
            throw throwable
        }
        tun2SocksActive = true
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Systemwide TUN routing is active through tun2socks and the local Yume SOCKS5 gateway at $proxyAddress.",
        )
    }

    private fun writeTun2SocksConfig(profile: ServerProfile, proxyHost: String, proxyPort: Int): File {
        val dir = File(noBackupFilesDir, "tun2socks").apply { mkdirs() }
        val configFile = File(dir, "yume-tun2socks.yml")
        val config = buildString {
            appendLine("tunnel:")
            appendLine("  name: tun0")
            appendLine("  mtu: ${profile.mtu}")
            appendLine("  ipv4: $VPN_IPV4_ADDRESS")
            appendLine("  ipv6: '$VPN_IPV6_ADDRESS'")
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
            appendLine("  connect-timeout: 5000")
            appendLine("  tcp-read-write-timeout: 120000")
            appendLine("  udp-read-write-timeout: 15000")
        }
        configFile.writeText(config)
        return configFile
    }

    private suspend fun verifySystemTrafficFlow(
        profile: ServerProfile,
        bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap,
        relayExitIpResult: Result<String>,
        proxyAddress: String,
        session: YumeBootstrapSession,
        generation: Long,
    ): RouteVerificationResult {
        ensureSessionActive(generation)
        delay(TUN2SOCKS_STARTUP_DELAY_MS)
        val relayExitIp = relayExitIpResult.getOrNull()?.trim().orEmpty()
        if (selfExcludedFromVpnPath) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Yume is excluding its own package from the Android VPN path for stability. Skipping the in-app routed socket probe and relying on the authenticated relay canary plus a live control-path keepalive.",
            )
            session.ping(timeoutMs = PRE_ROUTING_PING_TIMEOUT_MS)
            sendLifecycleUpdate(
                session = session,
                state = "traffic_flowing",
                message = "success, traffic flowing",
                detail = "android vpn tunnel is active; routed verification uses relay canaries because yume is excluded from its own vpn path",
                effectiveProtection = buildEffectiveProtectionSummary(bootstrap),
                trafficVerified = false,
                exitIp = relayExitIp,
            )
            return RouteVerificationResult(
                exitIp = relayExitIp.ifBlank { null },
                systemTrafficVerified = false,
            )
        }
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Running a systemwide Android traffic probe through Yume.")
        val routedExitIp = probeSystemExitIp().getOrElse { throwable ->
            sendLifecycleUpdate(
                session = session,
                state = "error",
                message = "error, disconnect",
                detail = throwable.message ?: "system traffic probe failed",
                effectiveProtection = buildEffectiveProtectionSummary(bootstrap),
                errorCode = "route_probe_failed",
                timeoutMs = 1_200,
            )
            throw YumeBootstrapException(
                "Failed to verify systemwide Yume routing: ${throwable.message ?: "unknown error"}",
                throwable,
            )
        }.trim()
        val expectedRelayExitIp = relayExitIpResult.getOrNull()?.trim().orEmpty().ifBlank { null }
        if (expectedRelayExitIp != null && !routedExitIp.equals(expectedRelayExitIp, ignoreCase = true)) {
            throw YumeBootstrapException(
                "Systemwide Yume routing bypassed the tunnel: expected exit IP $expectedRelayExitIp but observed $routedExitIp",
            )
        }
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Verified system traffic via Yume. Exit IP: $routedExitIp.")
        sendLifecycleUpdate(
            session = session,
            state = "traffic_flowing",
            message = "success, traffic flowing",
            detail = "systemwide android vpn routing is active",
            effectiveProtection = buildEffectiveProtectionSummary(bootstrap),
            trafficVerified = true,
            exitIp = routedExitIp,
        )
        return RouteVerificationResult(
            exitIp = routedExitIp,
            systemTrafficVerified = true,
        )
    }

    private suspend fun awaitPostRoutingTrafficRamp(
        session: YumeBootstrapSession,
        generation: Long,
    ) {
        ensureSessionActive(generation)
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Keeping Android traffic in warm-up briefly so the first post-connect burst does not overload the authenticated Yume session.",
        )
        delay(POST_ROUTING_TRAFFIC_RAMP_DELAY_MS / 2)
        ensureSessionActive(generation)
        session.ping(timeoutMs = PRE_ROUTING_PING_TIMEOUT_MS)
        delay(POST_ROUTING_TRAFFIC_RAMP_DELAY_MS - (POST_ROUTING_TRAFFIC_RAMP_DELAY_MS / 2))
        ensureSessionActive(generation)
        session.ping(timeoutMs = PRE_ROUTING_PING_TIMEOUT_MS)
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
        val relaySummary = if (verification.systemTrafficVerified && verification.exitIp != null) {
            buildString {
                append("System traffic verified through Yume: ${verification.exitIp}.")
                if (appRelayIp != null && appRelayIp != verification.exitIp) {
                    append(" Control-path relay canary: $appRelayIp.")
                }
            }
        } else {
            buildString {
                append("Relay canary is stable")
                if (appRelayIp != null) {
                    append(" with Yume exit IP $appRelayIp")
                }
                append(". The Yume app is excluded from its own VPN path for control-socket stability, so routed verification does not use an in-app raw socket.")
            }
        }
        YumeVpnRuntime.update { current ->
            current.copy(
                phase = VpnPhase.Connected,
                activeProfileId = profile.id,
                headline = "Protected",
                detail = if (verification.systemTrafficVerified) {
                    "Systemwide Android VPN traffic is flowing through Yume ${bootstrap.serverVersion} with ${bootstrap.verity.summary}."
                } else {
                    "Android VPN traffic is flowing through Yume ${bootstrap.serverVersion} with ${bootstrap.verity.summary}. The Yume app itself stays outside the tunnel so the authenticated control path remains stable."
                },
                endpointLabel = profile.endpointLabel,
                sessionDnsServers = effectiveVpnDnsServers(profile),
                sessionMtu = profile.mtu,
                upstreamNetworkLabel = describeUpstreamNetwork(),
                controlSocketProtected = true,
                vpnInterfaceEstablished = tunInterface != null,
                trafficRoutedThroughYume = true,
                requestedProtection = buildEffectiveProtectionSummary(bootstrap),
                transportSummary = "${bootstrap.tlsProtocol} + AUTH (${bootstrap.tlsCipherSuite})",
                innerSummary = buildInnerSummary(bootstrap),
                hopSummary = buildHopSummary(bootstrap),
                veritySummary = bootstrap.verity.summary,
                routingSummary = if (verification.systemTrafficVerified) {
                    "${BuildConfig.YUME_ROUTING_MODE} (IPv4 relay, IPv6 fail-closed)"
                } else {
                    "${BuildConfig.YUME_ROUTING_MODE} (IPv4 relay, IPv6 fail-closed; Yume app excluded from the VPN path for control stability)"
                },
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
            "The Android VPN tunnel is up and the local Yume gateway at $proxyAddress is being verified for routed traffic."
        } else {
            "Authenticated Yume relay traffic is ready at $proxyAddress while Android finishes tunnel startup."
        }
        YumeVpnRuntime.update { current ->
            current.copy(
                phase = VpnPhase.BootstrapReady,
                activeProfileId = profile.id,
                headline = "Relay ready",
                detail = detail,
                endpointLabel = profile.endpointLabel,
                sessionDnsServers = effectiveVpnDnsServers(profile),
                sessionMtu = profile.mtu,
                upstreamNetworkLabel = describeUpstreamNetwork(),
                controlSocketProtected = true,
                vpnInterfaceEstablished = tunInterface != null,
                trafficRoutedThroughYume = false,
                requestedProtection = buildEffectiveProtectionSummary(bootstrap),
                transportSummary = "${bootstrap.tlsProtocol} + AUTH (${bootstrap.tlsCipherSuite})",
                innerSummary = buildInnerSummary(bootstrap),
                hopSummary = buildHopSummary(bootstrap),
                veritySummary = bootstrap.verity.summary,
                routingSummary = "Local Yume SOCKS5 gateway $proxyAddress is ready while Android verifies routed traffic with IPv6 held fail-closed.",
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

    private fun maybeEnableInnerCompatibilityForCorruption(
        session: YumeBootstrapSession?,
        detail: String,
    ): Boolean {
        if (session == null || innerCompatibilityMode || !session.bootstrap.innerActive) {
            return false
        }
        if (!isCorruptionStyleControlLoss(detail)) {
            return false
        }
        innerCompatibilityMode = true
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Warn,
            "Authenticated Yume control path showed Android corruption symptoms ($detail). Reopening with session-side inner crypto disabled to keep the VPN stable.",
        )
        return true
    }

    private fun isCorruptionStyleControlLoss(detail: String): Boolean {
        val normalized = detail.trim().lowercase(Locale.US)
        if (normalized.isEmpty()) {
            return false
        }
        return normalized.contains("bad_record_mac") ||
            normalized.contains("bad record mac") ||
            normalized.contains("failure in ssl library") ||
            normalized.contains("ssl/tls read error") ||
            normalized.contains("ssl/tls write error") ||
            normalized.contains("frame validation failed") ||
            normalized.contains("invalid frame type") ||
            normalized.contains("frame too large") ||
            normalized.contains("ssl stream may be corrupted") ||
            normalized.contains("likely corrupted")
    }

    private fun buildEffectiveProtectionSummary(bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap): String {
        if (!bootstrap.innerActive) {
            return "TLS over 443"
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
        return if (bootstrap.hopEnabled && !hopCompatibilityMode) {
            "$innerTarget + hop over 443 ($kdf)"
        } else {
            "$innerTarget over 443 ($kdf)"
        }
    }

    private suspend fun probeSystemExitIp(): Result<String> {
        var lastFailure: Throwable? = null
        for ((host, path, port) in RELAY_PROBE_TARGETS) {
            val connectHosts = try {
                resolveProbeConnectHosts(host)
            } catch (throwable: Throwable) {
                lastFailure = throwable
                continue
            }
            for (connectHost in connectHosts) {
                try {
                    return Result.success(
                        runSystemProbeRequest(
                            connectHost = connectHost,
                            hostHeader = host,
                            path = path,
                            port = port,
                        ),
                    )
                } catch (throwable: Throwable) {
                    lastFailure = IOException(
                        "system traffic probe via $host [$connectHost] failed: ${throwable.message ?: throwable.javaClass.simpleName}",
                        throwable,
                    )
                }
            }
        }
        return Result.failure(lastFailure ?: IOException("system traffic probe failed"))
    }

    private fun runSystemProbeRequest(
        connectHost: String,
        hostHeader: String,
        path: String,
        port: Int,
    ): String {
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(connectHost, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socket.getOutputStream().write(
                (
                    "GET $path HTTP/1.1\r\n" +
                        "Host: $hostHeader\r\n" +
                        "User-Agent: YumeAndroid/${BuildConfig.VERSION_NAME}\r\n" +
                        "Accept: text/plain\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            socket.getOutputStream().flush()
            val response = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = socket.getInputStream().read(buffer)
                if (read < 0) {
                    break
                }
                if (read > 0) {
                    response.write(buffer, 0, read)
                }
            }
            parseHttpBody(response.toString(Charsets.UTF_8.name()))
        }
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

    private fun parseHttpBody(response: String): String {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) {
            throw IOException("empty HTTP response")
        }
        val separator = "\r\n\r\n"
        val index = response.indexOf(separator)
        if (index < 0) {
            if (looksLikeIpLiteral(trimmed)) {
                return trimmed
            }
            val preview = summarizeProbeResponse(trimmed)
            throw IOException(
                if (preview == null) {
                    "invalid HTTP response"
                } else {
                    "invalid HTTP response: $preview"
                },
            )
        }
        val statusLine = response.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains(" 200 ")) {
            throw IOException(statusLine.ifBlank { "unexpected HTTP status" })
        }
        return response.substring(index + separator.length).trim()
    }

    private fun androidClientName(profile: ServerProfile): String {
        val raw = "android-${profile.name}"
        val sanitized = buildString {
            raw.lowercase().forEach { char ->
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

    private fun buildRequestedProtectionSummary(bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap): String {
        if (innerCompatibilityMode || !bootstrap.innerSupported || !bootstrap.capPq) {
            return "TLS over 443"
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
        return if (bootstrap.hopEnabled && !hopCompatibilityMode) {
            "$innerTarget + hop over 443 ($kdfTarget)"
        } else {
            "$innerTarget over 443 ($kdfTarget)"
        }
    }

    private fun buildInnerSummary(bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap): String {
        if (!bootstrap.innerSupported || !bootstrap.capPq) {
            return "Server does not support PQ inner crypto"
        }
        val mode = when {
            bootstrap.innerDual -> "dual"
            bootstrap.innerMode.isNotBlank() -> bootstrap.innerMode
            else -> "available"
        }
        val kdf = when {
            bootstrap.capArgon2 -> "Argon2 preferred"
            bootstrap.capPbkdf2 -> "PBKDF2 fallback"
            else -> "server default KDF"
        }
        val active = if (bootstrap.innerActive) {
            "active for this Android Yume session"
        } else {
            "supported by the server but not active for this session"
        }
        return "Supports $mode inner crypto; $kdf; $active"
    }

    private fun buildHopSummary(bootstrap: com.fixcraft.yume.transport.YumeServerBootstrap): String {
        if (hopCompatibilityMode) {
            return "Server hop disabled for this Android compatibility session"
        }
        if (!bootstrap.hopEnabled) {
            return "Server hopping is off"
        }
        if (bootstrap.hopIntervalMs <= 0) {
            return "Server hopping is enabled"
        }
        val hz = 1000.0 / bootstrap.hopIntervalMs.toDouble()
        return String.format(
            java.util.Locale.US,
            "Server hop ON - %.2fHz | active for this Android Yume session",
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
            sessionProvider = { bootstrapSession },
            dnsServersProvider = { activeProfile?.let(::effectiveVpnDnsServers).orEmpty() },
            ipv4OnlyMode = true,
            forceTcpFallbackUdp443Provider = { true },
            protectedDestinationsProvider = { currentProtectedDestinations() },
            protectedAddressResolver = { host ->
                runCatching { resolveProtectedAddresses(host) }.getOrDefault(emptySet())
            },
            log = { level, message -> YumeVpnRuntime.appendLog(level, message) },
        ).also { socksProxy = it }
        val address = proxy.start()
        val label = "${address.address.hostAddress}:${address.port}"
        localProxyAddress = label
        return label
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

    private fun applyVpnBypassPolicy(builder: Builder) {
        selfExcludedFromVpnPath = false
        if (ANDROID_SELF_EXCLUDED_FROM_VPN_DEFAULT) {
            val excluded = runCatching {
                builder.addDisallowedApplication(packageName)
            }.isSuccess
            if (excluded) {
                selfExcludedFromVpnPath = true
                YumeVpnRuntime.appendLog(
                    VpnLogLevel.Info,
                    "Excluding the Yume app from its own Android VPN path so the authenticated control socket and local gateway stay outside the tunnel.",
                )
                return
            }
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Android refused to exclude the Yume app from its own VPN path. Falling back to protected-socket routing inside the tunnel.",
            )
        }
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Info,
            "Keeping the Yume app inside the Android VPN path. Control sockets are protected individually and the active server IPv4 routes are excluded explicitly.",
        )

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
        socksProxy?.setStartupWarmupMode(enabled)
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
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
    }

    private fun describeUpstreamNetwork(): String {
        val network = upstreamNetwork ?: connectivityManager.activeNetwork ?: return "Unavailable"
        return describeNetwork(network)
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(profile, status))
    }

    private fun buildNotification(profile: ServerProfile, status: String): Notification {
        val disconnectIntent = Intent(this, YumeVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            2,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title, profile.name))
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(buildConfigureIntent())
            .addAction(0, getString(R.string.disconnect), disconnectPendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_CONNECT = "com.fixcraft.yume.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.fixcraft.yume.action.DISCONNECT"
        private const val EXTRA_PROFILE_ID = "extra_profile_id"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 8_000
        // Start Android on the same full-protection path as the desktop client now that the
        // original transport/session corruption fixes are in place. Compatibility mode remains
        // available as an automatic fallback after a real corruption-style reset.
        private const val ANDROID_HOP_COMPATIBILITY_DEFAULT = false
        private const val ANDROID_INNER_COMPATIBILITY_DEFAULT = false
        // Excluding the app from its own VPN path avoids Android hairpinning the control socket
        // and the local SOCKS gateway back through the tunnel under load.
        private const val ANDROID_SELF_EXCLUDED_FROM_VPN_DEFAULT = true
        private const val KEEPALIVE_PING_INTERVAL_MS = 15_000L
        private const val PRE_ROUTING_STABILITY_DELAY_MS = 1_500L
        private const val PRE_ROUTING_PING_TIMEOUT_MS = 3_000
        private const val POST_ROUTING_TRAFFIC_RAMP_DELAY_MS = 8_000L
        private const val TUN2SOCKS_STARTUP_DELAY_MS = 800L
        private val RELAY_PROBE_TARGETS = listOf(
            Triple("ifconfig.me", "/ip", 80),
            Triple("api.ipify.org", "/", 80),
            Triple("icanhazip.com", "/", 80),
        )
        private const val VPN_IPV4_ADDRESS = "10.77.0.2"
        private const val VPN_IPV6_ADDRESS = "fd77:77::2"
        private const val MAPDNS_ADDRESS = "240.0.0.2"
        private const val MAPDNS_NETWORK = "240.0.0.0"
        private const val MAPDNS_NETMASK = "240.0.0.0"
        private const val MAPDNS_CACHE_SIZE = 10_000
        private const val NOTIFICATION_ID = 1107
        private const val NOTIFICATION_CHANNEL_ID = "yume_vpn_service"

        fun startConnect(context: Context, profileId: String) {
            val intent = Intent(context, YumeVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PROFILE_ID, profileId)
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
