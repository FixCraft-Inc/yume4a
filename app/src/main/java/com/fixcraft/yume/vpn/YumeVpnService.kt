package com.fixcraft.yume.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fixcraft.yume.MainActivity
import com.fixcraft.yume.R
import com.fixcraft.yume.core.SocketProtector
import com.fixcraft.yume.core.YumeClient
import com.fixcraft.yume.core.YumeDevice
import com.fixcraft.yume.core.YumeException
import com.fixcraft.yume.core.YumeStatus
import com.fixcraft.yume.core.YumeStatusCode
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.AppSettings
import com.fixcraft.yume.data.AppSettingsStore
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.data.KitConfig
import com.fixcraft.yume.data.KitDescription
import com.fixcraft.yume.data.KitSession
import com.fixcraft.yume.data.KitStore
import com.fixcraft.yume.data.KitSummary
import com.fixcraft.yume.i18n.AppLocaleManager
import com.fixcraft.yume.i18n.AppStrings
import com.fixcraft.yume.i18n.appStrings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Android's side of a YUME connection: the VPN interface, its routes and
 * resolvers, the network under it, and the service's lifetime.
 *
 * libyume owns everything about the connection itself. This service gives it
 * the interface's descriptor and the server's address, protects its socket
 * from the VPN's own routes, and reads its status and messages. The library
 * replaces a lost session by itself and keeps the interface in place
 * meanwhile, so applications wait instead of leaving through another network.
 */
class YumeVpnService : VpnService() {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Error,
            "The VPN service failed unexpectedly: ${throwable.javaClass.simpleName}",
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val restartPreferences by lazy {
        getSharedPreferences(RESTART_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val networkChanged = AtomicBoolean(false)
    private val networks by lazy {
        UnderlyingNetworks(getSystemService(ConnectivityManager::class.java)) {
            networkChanged.set(true)
        }
    }
    private val client = AtomicReference<YumeClient?>(null)

    // The current run, and a stop in progress. A newer run replaces both, so
    // an older one that finishes later must not end the service under it.
    @Volatile
    private var runJob: Job? = null

    @Volatile
    private var stopJob: Job? = null

    @Volatile
    private var notificationTitle = ""

    private sealed interface MonitorEnd {
        data object AddressChanged : MonitorEnd
        data class EndpointStopped(val detail: String) : MonitorEnd
    }

    private class RunFailure(val kind: VpnFailure, val detail: String) : Exception(detail)

    override fun onCreate() {
        super.onCreate()
        // Nothing runs yet, so anything here was left by a process that died.
        KitStore.clearSessions(this)
        networks.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val kitId = intent.getStringExtra(EXTRA_KIT_ID).orEmpty()
                restartPreferences.edit().putString(KEY_KIT_ID, kitId).apply()
                begin(kitId, patient = false)
            }

            ACTION_DISCONNECT -> {
                restartPreferences.edit().remove(KEY_KIT_ID).apply()
                stop()
            }

            else -> {
                // Android restarted the service, or started it as the
                // always-on VPN. Only the kit's identifier was kept. A run
                // started this way keeps trying, since nobody is watching.
                val kitId = restartPreferences.getString(KEY_KIT_ID, null)
                    ?: selectedKitIfUnlocked()
                begin(kitId.orEmpty(), patient = true)
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        restartPreferences.edit().remove(KEY_KIT_ID).apply()
        stop()
        super.onRevoke()
    }

    override fun onDestroy() {
        // The run's cleanup is ordinary code in a finally block, so it still
        // happens after the scope is cancelled.
        runJob?.cancel()
        client.get()?.stop()
        networks.unregister()
        scope.cancel()
        super.onDestroy()
    }

    private fun selectedKitIfUnlocked(): String? =
        if (AppVaultManager.ensureReady(this)) KitStore.get(this).selectedId.value else null

    private fun strings(): AppStrings {
        val language = if (AppVaultManager.ensureReady(this)) {
            AppSettingsStore.get(this).settings.value.language
        } else {
            AppLanguage.SystemDefault
        }
        return appStrings(this, language)
    }

    private fun begin(kitId: String, patient: Boolean) {
        // Android requires the notification within seconds of the start.
        startInForeground(strings().notificationConnecting)
        val previous = runJob
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (previous != null) awaitEnd(previous)
            val failure = try {
                run(kitId, patient)
                null
            } catch (error: RunFailure) {
                error
            }
            if (failure != null) {
                YumeVpnRuntime.appendLog(VpnLogLevel.Error, "Stopped: ${failure.detail}")
                restartPreferences.edit().remove(KEY_KIT_ID).apply()
                YumeVpnRuntime.update {
                    VpnConnectionState(
                        phase = VpnPhase.Error,
                        kitId = it.kitId,
                        kitName = it.kitName,
                        endpointLabel = it.endpointLabel,
                        failure = failure.kind,
                        failureDetail = failure.detail,
                    )
                }
                if (runJob === coroutineContext[Job]) finishService()
            }
        }
        // A stop still waiting for the previous run would otherwise stop
        // this run's endpoint. This run waits for the previous one itself.
        stopJob?.cancel()
        stopJob = null
        runJob = job
        job.start()
    }

    private fun stop() {
        if (stopJob?.isActive == true) return
        val running = runJob
        YumeVpnRuntime.update {
            if (it.isActive) it.copy(phase = VpnPhase.Disconnecting) else it
        }
        stopJob = scope.launch {
            if (running != null) awaitEnd(running)
            // A connect that arrived meanwhile owns the service now.
            if (runJob !== running) return@launch
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Disconnected.")
            YumeVpnRuntime.update { VpnConnectionState() }
            YumeVpnRuntime.resetTrafficHistory()
            finishService()
        }
    }

    /**
     * Cancels a run and waits until its cleanup has finished, endpoint and
     * device closed. A cancelled job stops being active at once, while its
     * cleanup still runs. A start in progress returns as soon as the
     * endpoint is stopped, which may have to be asked again if the run had
     * not created its endpoint yet.
     */
    private suspend fun awaitEnd(job: Job) {
        while (!job.isCompleted) {
            job.cancel()
            client.get()?.stop()
            delay(STOP_POLL_MS)
        }
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * One run: from the kit to an interface that carries traffic, until it is
     * stopped. Returns when cancelled and throws [RunFailure] when it cannot
     * go on.
     */
    private suspend fun run(kitId: String, patient: Boolean) {
        if (!AppVaultManager.ensureReady(this)) {
            throw RunFailure(VpnFailure.KitUnavailable, "the application's data is locked")
        }
        val kitStore = KitStore.get(this)
        val kit = kitStore.get(kitId)
            ?: throw RunFailure(VpnFailure.KitUnavailable, "the selected kit is no longer stored")
        val settings = AppSettingsStore.get(this).settings.value
        notificationTitle = kit.name
        YumeVpnRuntime.resetTrafficHistory()
        YumeVpnRuntime.update {
            VpnConnectionState(
                phase = VpnPhase.Connecting,
                kitId = kit.id,
                kitName = kit.name,
                endpointLabel = kit.endpointLabel,
            )
        }
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Connecting to ${kit.name} (${kit.endpointLabel}).")

        val session = kitStore.openSession(kit.id)
            ?: throw RunFailure(VpnFailure.KitUnavailable, "the kit's files could not be read")
        var device: ParcelFileDescriptor? = null
        try {
            val dnsServers = usableDnsServers(settings)
            device = establish(kit, settings, dnsServers)
                ?: throw RunFailure(VpnFailure.VpnPermission, "Android did not create the VPN interface")
            YumeVpnRuntime.update {
                it.copy(dnsServers = dnsServers, mtu = TUN_MTU, carriesIpv6 = settings.carryIpv6)
            }
            serve(session, device, settings, patient)
        } finally {
            client.getAndSet(null)?.close()
            runCatching { device?.close() }
            session.close()
        }
    }

    /** Keeps an endpoint running on [device], replacing it when the server's address changes. */
    private suspend fun serve(
        session: KitSession,
        device: ParcelFileDescriptor,
        settings: AppSettings,
        patient: Boolean,
    ) {
        val description = session.description
        var everConnected = false
        var waitMs = FIRST_RETRY_MS
        var carriedRx = 0L
        var carriedTx = 0L
        while (true) {
            coroutineContext.ensureActive()
            val network = awaitNetwork()
            val keepTrying = patient || everConnected

            val address = try {
                serverAddress(description, network)
            } catch (error: IOException) {
                if (!keepTrying) throw RunFailure(VpnFailure.Resolve, error.message.orEmpty())
                YumeVpnRuntime.appendLog(VpnLogLevel.Warn, "${error.message}. Trying again.")
                waitMs = pause(waitMs)
                continue
            }

            val endpoint = try {
                YumeClient.create(
                    session.directory,
                    KitConfig.forDevice(session.config, address),
                    SocketProtector { descriptor -> protect(descriptor) },
                )
            } catch (error: YumeException) {
                throw RunFailure(VpnFailure.Configuration, describe(error))
            }
            client.set(endpoint)
            try {
                try {
                    endpoint.setDevice(
                        YumeDevice(
                            descriptor = device.fd,
                            mtu = TUN_MTU,
                            streamService = description.streamService,
                            packetService = description.packetService,
                            ipv4Address = TUN_IPV4_ADDRESS,
                            ipv4Peer = TUN_IPV4_PEER,
                            ipv6Address = TUN_IPV6_ADDRESS.takeIf { settings.carryIpv6 },
                            ipv6Peer = TUN_IPV6_PEER.takeIf { settings.carryIpv6 },
                        ),
                    )
                } catch (error: YumeException) {
                    throw RunFailure(VpnFailure.Device, describe(error))
                }
                YumeVpnRuntime.update {
                    it.copy(
                        phase = if (everConnected) VpnPhase.Reconnecting else VpnPhase.Connecting,
                        serverAddress = address,
                        waitingForNetwork = false,
                        upstream = networks.kind(),
                    )
                }
                refreshNotification()
                try {
                    endpoint.start(START_TIMEOUT_MS)
                } catch (error: YumeException) {
                    drainMessages(endpoint, 0L)
                    if (error.status == YumeStatusCode.CANCELLED) return
                    val kind = failureKind(error.status)
                    val final = kind == VpnFailure.Refused || kind == VpnFailure.Configuration
                    if (final || !keepTrying) throw RunFailure(kind, describe(error))
                    YumeVpnRuntime.appendLog(VpnLogLevel.Warn, "${describe(error)}. Trying again.")
                    waitMs = pause(waitMs)
                    continue
                }
                everConnected = true
                waitMs = FIRST_RETRY_MS
                when (val end = monitor(endpoint, description, address, carriedRx, carriedTx)) {
                    MonitorEnd.AddressChanged ->
                        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "The server's address changed. Reconnecting to it.")

                    is MonitorEnd.EndpointStopped ->
                        throw RunFailure(VpnFailure.Device, end.detail)
                }
                endpoint.status()?.let {
                    carriedRx += it.payloadBytesReceived
                    carriedTx += it.payloadBytesSent
                }
            } finally {
                client.compareAndSet(endpoint, null)
                endpoint.close()
            }
        }
    }

    /**
     * Publishes the endpoint's status and messages once a second. The
     * library reconnects on its own. This only tells it when the network
     * returns, and notices a server whose address is no longer the one dialled.
     */
    private suspend fun monitor(
        endpoint: YumeClient,
        description: KitDescription,
        address: String?,
        carriedRx: Long,
        carriedTx: Long,
    ): MonitorEnd {
        var lastMessage = 0L
        var lastSession = YumeStatus.SESSION_NONE
        var addressChecked = false
        networkChanged.set(false)
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Connected. Traffic now goes through YUME.")
        while (true) {
            coroutineContext.ensureActive()
            lastMessage = drainMessages(endpoint, lastMessage)
            val status = endpoint.status()
                ?: return MonitorEnd.EndpointStopped("the endpoint no longer reports its status")
            if (status.state != YumeStatus.STATE_RUNNING) {
                return MonitorEnd.EndpointStopped(
                    status.lastFailureMessage.ifBlank { "the VPN interface stopped" },
                )
            }
            val active = status.session == YumeStatus.SESSION_ACTIVE
            val networkEvent = networkChanged.getAndSet(false)
            if (networkEvent) {
                // A waiting attempt need not sit out the rest of its delay.
                endpoint.retryNow()
                addressChecked = false
            }
            if (active) addressChecked = false
            YumeVpnRuntime.pushTrafficSample(
                carriedRx + status.payloadBytesReceived,
                carriedTx + status.payloadBytesSent,
            )
            YumeVpnRuntime.update {
                it.copy(
                    phase = if (active) VpnPhase.Connected else VpnPhase.Reconnecting,
                    status = status,
                    upstream = networks.kind(),
                    waitingForNetwork = networks.current() == null,
                )
            }
            if (status.session != lastSession) {
                lastSession = status.session
                refreshNotification()
            }
            // After repeated failures, see whether the name now leads
            // somewhere else, as it does when a server moves or the new
            // network reaches it by another family.
            if (!active && address != null && description.connectAddress == null && !addressChecked &&
                (networkEvent || status.failedAttempts >= ADDRESS_CHECK_AFTER_FAILURES)
            ) {
                addressChecked = true
                val network = networks.current()
                if (network != null) {
                    val fresh = runCatching { resolve(description.host, network) }.getOrNull()
                    if (fresh != null && address !in fresh) return MonitorEnd.AddressChanged
                }
            }
            delay(STATUS_POLL_MS)
        }
    }

    /** Copies the endpoint's new message lines into the log. Returns the last line's number. */
    private fun drainMessages(endpoint: YumeClient, after: Long): Long {
        var last = after
        repeat(MAX_MESSAGES_PER_POLL) {
            val message = endpoint.readMessage(last) ?: return last
            if (message.seq == 0L) return last
            if (message.missed > 0) {
                YumeVpnRuntime.appendLog(VpnLogLevel.Warn, "${message.missed} earlier lines were not kept.")
            }
            val level = if (message.text.contains("failed")) VpnLogLevel.Warn else VpnLogLevel.Info
            YumeVpnRuntime.appendLog(level, message.text, message.timeUnixMs)
            last = message.seq
        }
        return last
    }

    private suspend fun awaitNetwork(): Network {
        networks.current()?.let { return it }
        YumeVpnRuntime.appendLog(VpnLogLevel.Warn, "No network. Waiting for Wi-Fi or mobile data.")
        YumeVpnRuntime.update { it.copy(waitingForNetwork = true, upstream = null) }
        refreshNotification()
        val network = networks.await()
        YumeVpnRuntime.update { it.copy(waitingForNetwork = false, upstream = networks.kind()) }
        return network
    }

    /**
     * The address the transport dials, or null when the kit names one
     * itself. A name is resolved on the network under the VPN, never through
     * the VPN, and the library still authenticates the kit's host name.
     */
    private suspend fun serverAddress(description: KitDescription, network: Network): String? {
        if (description.connectAddress != null || description.usesProxy) return null
        if (KitConfig.isAddressLiteral(description.host)) return null
        return resolve(description.host, network).first()
    }

    private suspend fun resolve(host: String, network: Network): List<String> {
        val addresses = try {
            withTimeout(RESOLVE_TIMEOUT_MS) { UnderlyingNetworkResolver.resolve(network, host) }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("The lookup of $host took too long")
        }
        return networks.order(network, addresses).mapNotNull { it.hostAddress?.substringBefore('%') }
            .ifEmpty { throw IOException("The lookup of $host returned no address") }
    }

    private suspend fun pause(waitMs: Long): Long {
        delay(waitMs)
        return (waitMs * 2).coerceAtMost(LONGEST_RETRY_MS)
    }

    private fun usableDnsServers(settings: AppSettings): List<String> {
        val usable = settings.dnsServers.filter { settings.carryIpv6 || !it.contains(':') }
        return usable.ifEmpty { AppSettings.DEFAULT_DNS_SERVERS }
    }

    /**
     * The interface: one address pair, every route, and the resolvers. With
     * no IPv6 address or route Android blocks IPv6 while the VPN is up, so it
     * cannot leave beside the tunnel. This application is not excluded,
     * because the bridge answers the device's connections from inside it.
     */
    private fun establish(
        kit: KitSummary,
        settings: AppSettings,
        dnsServers: List<String>,
    ): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(kit.name)
            .setMtu(TUN_MTU)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .setConfigureIntent(configureIntent())
        if (settings.carryIpv6) {
            builder.addAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX)
            builder.addRoute("::", 0)
        }
        dnsServers.forEach { builder.addDnsServer(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The VPN is as metered as the network under it, not always.
            builder.setMetered(false)
        }
        return try {
            builder.establish()
        } catch (error: RuntimeException) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Error,
                "Android refused the VPN interface: ${error.javaClass.simpleName}",
            )
            null
        }
    }

    private fun failureKind(status: Int): VpnFailure = when (status) {
        YumeStatusCode.PERMISSION_DENIED -> VpnFailure.Refused
        YumeStatusCode.INVALID_ARGUMENT,
        YumeStatusCode.UNSUPPORTED,
        STATUS_PARSE_ERROR,
        STATUS_INCOMPATIBLE,
        -> VpnFailure.Configuration

        else -> VpnFailure.Unreachable
    }

    private fun describe(error: YumeException): String {
        val text = error.detail.ifBlank { error.statusName }
        return if (error.pointer.isBlank()) text else "${error.pointer}: $text"
    }

    private fun configureIntent(): PendingIntent {
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

    private fun startInForeground(text: String) {
        ensureNotificationChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val strings = strings()
        val state = YumeVpnRuntime.state.value
        val text = when {
            state.waitingForNetwork -> strings.notificationWaitingForNetwork
            state.phase == VpnPhase.Connected -> strings.notificationConnected
            state.phase == VpnPhase.Reconnecting -> strings.notificationReconnecting
            state.phase == VpnPhase.Disconnecting -> strings.notificationDisconnecting
            else -> strings.notificationConnecting
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val localizedContext = AppLocaleManager.current(this)
        val disconnect = PendingIntent.getService(
            this,
            2,
            Intent(this, YumeVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = notificationTitle.takeIf { it.isNotBlank() }
            ?.let { AppLocaleManager.getString(localizedContext, R.string.notification_title, it) }
            ?: AppLocaleManager.getString(localizedContext, R.string.app_name)
        return NotificationCompat.Builder(localizedContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentIntent(configureIntent())
            .addAction(0, AppLocaleManager.getString(localizedContext, R.string.disconnect), disconnect)
            .build()
    }

    private fun ensureNotificationChannel() {
        val localizedContext = AppLocaleManager.current(this)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            AppLocaleManager.getString(localizedContext, R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = AppLocaleManager.getString(localizedContext, R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_CONNECT = "com.fixcraft.yume.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.fixcraft.yume.action.DISCONNECT"
        private const val EXTRA_KIT_ID = "extra_kit_id"
        private const val RESTART_PREFS_NAME = "yume_vpn_restart"
        private const val KEY_KIT_ID = "kit_id"
        private const val NOTIFICATION_ID = 1107
        private const val NOTIFICATION_CHANNEL_ID = "yume_vpn_service"

        // The interface's address and the bridge's, a pair in a prefix no
        // wider than the two. Large packets mean fewer of them between the
        // device's applications and the bridge. Both ends are on the phone.
        private const val TUN_MTU = 9000
        private const val TUN_IPV4_ADDRESS = "10.71.0.1"
        private const val TUN_IPV4_PEER = "10.71.0.2"
        private const val TUN_IPV4_PREFIX = 30
        private const val TUN_IPV6_ADDRESS = "fd71::1"
        private const val TUN_IPV6_PEER = "fd71::2"
        private const val TUN_IPV6_PREFIX = 126

        private const val START_TIMEOUT_MS = 30_000
        private const val RESOLVE_TIMEOUT_MS = 10_000L
        private const val STATUS_POLL_MS = 1_000L
        private const val STOP_POLL_MS = 100L
        private const val FIRST_RETRY_MS = 1_000L
        private const val LONGEST_RETRY_MS = 30_000L
        private const val ADDRESS_CHECK_AFTER_FAILURES = 3
        private const val MAX_MESSAGES_PER_POLL = 64
        private const val STATUS_PARSE_ERROR = -9
        private const val STATUS_INCOMPATIBLE = -13

        fun startConnect(context: Context, kitId: String) {
            val intent = Intent(context, YumeVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_KIT_ID, kitId)
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
