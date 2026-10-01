package com.fixcraft.yume.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.os.SystemClock
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.R
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.data.KitImport
import com.fixcraft.yume.data.KitStore
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime
import com.fixcraft.yume.vpn.YumeVpnService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AgentCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()
    private val commandStateLock = Any()
    private var activeCommands = 0
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.ENABLE_ADB_TEST_CONTROL) { "ADB test control is disabled" }
        AgentProtocol.LEGACY_ONE_SHOT_FILES.forEach { name ->
            AgentTelemetry.file(this, name).delete()
        }
        AgentTelemetry.start(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Waiting for ADB command"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        val commandId = intent?.getStringExtra(AgentProtocol.EXTRA_COMMAND_ID).orEmpty()
        startForeground(NOTIFICATION_ID, notification(action.substringAfterLast('.').lowercase()))
        synchronized(commandStateLock) {
            activeCommands += 1
            latestStartId = startId
        }
        scope.launch {
            try {
                commandMutex.withLock {
                    runCommand(intent, commandId, action)
                }
            } finally {
                val stopId = synchronized(commandStateLock) {
                    check(activeCommands > 0) { "Agent command accounting underflow" }
                    activeCommands -= 1
                    latestStartId.takeIf { activeCommands == 0 }
                }
                if (stopId != null) {
                    // If a newer start arrives after the lock is released,
                    // Android rejects this captured older start ID.
                    stopSelfResult(stopId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runCommand(intent: Intent?, commandId: String, action: String) {
        if (intent == null || commandId.isBlank()) return
        try {
            when (action) {
                AgentProtocol.ACTION_SNAPSHOT -> snapshot(commandId)
                AgentProtocol.ACTION_KITS -> kits(commandId)
                AgentProtocol.ACTION_CONNECT -> connect(intent, commandId)
                AgentProtocol.ACTION_DISCONNECT -> disconnect(commandId)
                AgentProtocol.ACTION_IMPORT_KIT -> importKit(commandId)
                AgentProtocol.ACTION_DELETE_KIT -> deleteKit(intent, commandId)
                AgentProtocol.ACTION_NETWORK_BENCH -> networkBench(intent, commandId)
                else -> complete(commandId, action, false, "Unsupported action")
            }
        } catch (throwable: Throwable) {
            complete(
                commandId,
                action,
                false,
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
    }

    private fun snapshot(commandId: String) {
        val snapshot = AgentTelemetry.writeSnapshot(this)
        complete(commandId, "snapshot", true, "Snapshot captured", snapshot)
    }

    private fun kits(commandId: String) {
        require(AppVaultManager.ensureReady(this)) { "The app vault is locked" }
        val store = KitStore.get(this)
        val selected = store.selectedId.value
        val kits = JSONArray().apply {
            store.kits.value.forEach { kit ->
                put(JSONObject()
                    .put("id", kit.id)
                    .put("name", kit.name)
                    .put("endpoint", kit.endpointLabel)
                    .put("selected", kit.id == selected))
            }
        }
        complete(
            commandId,
            "kits",
            true,
            "Found ${kits.length()} kit(s)",
            JSONObject().put("kits", kits).put("selected_kit_id", selected ?: JSONObject.NULL),
        )
    }

    private fun connect(intent: Intent, commandId: String) {
        require(AppVaultManager.ensureReady(this)) { "The app vault is locked" }
        val store = KitStore.get(this)
        val kitId = intent.getStringExtra(AgentProtocol.EXTRA_KIT_ID)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: store.selectedId.value.orEmpty()
        require(store.get(kitId) != null) { "Kit not found" }
        if (VpnService.prepare(this) != null) {
            complete(
                commandId,
                "connect",
                false,
                "VPN consent is required. Run the harness prepare command and approve it once",
                JSONObject().put("consent_required", true),
            )
            return
        }
        AgentTelemetry.beginConnect(commandId)
        YumeVpnService.startConnect(this, kitId)
        complete(
            commandId,
            "connect",
            true,
            "Connect command accepted",
            JSONObject().put("kit_id", kitId),
        )
    }

    private fun disconnect(commandId: String) {
        YumeVpnService.startDisconnect(this)
        complete(commandId, "disconnect", true, "Disconnect command accepted")
    }

    // The sealed kit and its code arrive as one-shot files that are deleted
    // as they are read. The code never appears in a command line or a result.
    private suspend fun importKit(commandId: String) = withContext(Dispatchers.IO) {
        var sealed: ByteArray? = null
        var code: ByteArray? = null
        try {
            var inputFailure: Throwable? = null
            try {
                sealed = consumeRequired(AgentProtocol.importKitFile(commandId), MAX_KIT_BYTES)
            } catch (throwable: Throwable) {
                inputFailure = throwable
            }
            try {
                code = consumeSecret(AgentProtocol.importCodeFile(commandId), MAX_CODE_BYTES)
                    ?: throw IllegalArgumentException("Missing one-shot input for the kit's code")
            } catch (throwable: Throwable) {
                val preceding = inputFailure
                if (preceding == null) inputFailure = throwable else preceding.addSuppressed(throwable)
            }
            inputFailure?.let { throw it }
            require(AppVaultManager.ensureReady(this@AgentCommandService)) { "The app vault is locked" }
            val codeBytes = checkNotNull(code)
            val typed = CharArray(codeBytes.size) { index -> (codeBytes[index].toInt() and 0xff).toChar() }
            val store = KitStore.get(this@AgentCommandService)
            when (val result = store.importSealed(checkNotNull(sealed), typed, "")) {
                is KitImport.Imported -> {
                    store.select(result.kit.id)
                    complete(
                        commandId,
                        "import_kit",
                        true,
                        "Imported and selected kit ${result.kit.name}",
                        JSONObject()
                            .put("kit_id", result.kit.id)
                            .put("name", result.kit.name)
                            .put("endpoint", result.kit.endpointLabel),
                    )
                }

                is KitImport.Refused -> complete(
                    commandId,
                    "import_kit",
                    false,
                    "Refused: ${result.reason.name} ${result.detail}".trim(),
                    JSONObject().put("reason", result.reason.name),
                )
            }
        } finally {
            sealed?.fill(0)
            code?.fill(0)
        }
    }

    private fun deleteKit(intent: Intent, commandId: String) {
        require(AppVaultManager.ensureReady(this)) { "The app vault is locked" }
        val kitId = intent.getStringExtra(AgentProtocol.EXTRA_KIT_ID)?.trim().orEmpty()
        val store = KitStore.get(this)
        require(store.get(kitId) != null) { "Kit not found" }
        val state = YumeVpnRuntime.state.value
        require(!(state.isActive && state.kitId == kitId)) { "The kit is in use. Disconnect first" }
        store.delete(kitId)
        complete(commandId, "delete_kit", true, "Deleted the kit", JSONObject().put("kit_id", kitId))
    }

    private suspend fun networkBench(intent: Intent, commandId: String) = withContext(Dispatchers.IO) {
        val state = YumeVpnRuntime.state.value
        require(state.phase == VpnPhase.Connected) { "The VPN is not connected" }
        val rawUrl = intent.getStringExtra(AgentProtocol.EXTRA_URL)?.trim().orEmpty()
        val uri = validateBenchmarkUri(rawUrl)
        val maxBytes = intent.getLongExtra(AgentProtocol.EXTRA_MAX_BYTES, DEFAULT_BENCH_BYTES)
            .coerceIn(MIN_BENCH_BYTES, MAX_BENCH_BYTES)
        val timeoutMs = intent.getLongExtra(AgentProtocol.EXTRA_TIMEOUT_MS, DEFAULT_BENCH_TIMEOUT_MS)
            .coerceIn(MIN_BENCH_TIMEOUT_MS, MAX_BENCH_TIMEOUT_MS)
        val before = YumeVpnRuntime.traffic.value
        val started = SystemClock.elapsedRealtime()
        var firstByteAt: Long? = null
        var bytesRead = 0L
        var httpStatus = 0
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            readTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Yume-Android-Agent/${BuildConfig.VERSION_NAME}")
        }
        try {
            httpStatus = connection.responseCode
            require(httpStatus in 200..299) { "Benchmark endpoint returned HTTP $httpStatus" }
            BufferedInputStream(connection.inputStream, BENCH_BUFFER_BYTES).use { input ->
                val buffer = ByteArray(BENCH_BUFFER_BYTES)
                try {
                    while (bytesRead < maxBytes && SystemClock.elapsedRealtime() - started < timeoutMs) {
                        val wanted = minOf(buffer.size.toLong(), maxBytes - bytesRead).toInt()
                        val count = input.read(buffer, 0, wanted)
                        if (count < 0) break
                        if (firstByteAt == null) firstByteAt = SystemClock.elapsedRealtime()
                        bytesRead += count
                    }
                } finally {
                    buffer.fill(0)
                }
            }
        } finally {
            connection.disconnect()
        }
        val elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
        val tunnelAfter = YumeVpnRuntime.traffic.value
        val tunnelRxDelta = (tunnelAfter.totalRxBytes - before.totalRxBytes).coerceAtLeast(0L)
        val tunnelTxDelta = (tunnelAfter.totalTxBytes - before.totalTxBytes).coerceAtLeast(0L)
        val megabitsPerSecond = bytesRead.toDouble() * 8.0 / elapsedMs.toDouble() / 1_000.0
        complete(
            commandId,
            "network_bench",
            bytesRead > 0,
            "Downloaded $bytesRead bytes through the Android VPN",
            JSONObject()
                .put("url", uri.toASCIIString())
                .put("http_status", httpStatus)
                .put("bytes", bytesRead)
                .put("elapsed_ms", elapsedMs)
                .put("time_to_first_byte_ms", firstByteAt?.minus(started) ?: JSONObject.NULL)
                .put("megabits_per_second", megabitsPerSecond)
                .put("tunnel_rx_delta_bytes", tunnelRxDelta)
                .put("tunnel_tx_delta_bytes", tunnelTxDelta),
        )
    }

    private fun validateBenchmarkUri(raw: String): URI {
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("Invalid benchmark URL") }
        require(uri.scheme == "http" || uri.scheme == "https") { "Benchmark URL must use http or https" }
        require(!uri.host.isNullOrBlank()) { "Benchmark URL requires a host" }
        require(uri.userInfo == null && uri.fragment == null) { "Benchmark URL must not contain credentials or a fragment" }
        return uri
    }

    private fun consumeSecret(name: String, maxBytes: Int): ByteArray? {
        val file = AgentTelemetry.file(this, name)
        if (!file.isFile) return null
        return consumeRequired(name, maxBytes).also { bytes ->
            var end = bytes.size
            while (end > 0 && (bytes[end - 1] == '\n'.code.toByte() || bytes[end - 1] == '\r'.code.toByte())) {
                end--
            }
            if (end != bytes.size) return bytes.copyOf(end).also { bytes.fill(0) }
        }
    }

    private fun consumeRequired(name: String, maxBytes: Int): ByteArray {
        val file = AgentTelemetry.file(this, name)
        require(file.isFile) { "Missing one-shot input ${file.name}" }
        return FileInputStream(file).use { input ->
            check(file.delete()) { "Could not consume one-shot input ${file.name}" }
            val expectedSize = input.channel.size()
            require(expectedSize in 1..maxBytes.toLong()) { "Invalid ${file.name} size" }
            val bytes = ByteArray(expectedSize.toInt())
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    require(count > 0) { "One-shot input ${file.name} changed while reading" }
                    offset += count
                }
                require(input.read() == -1) { "One-shot input ${file.name} is too large" }
                bytes
            } catch (throwable: Throwable) {
                bytes.fill(0)
                throw throwable
            }
        }
    }

    private fun complete(
        commandId: String,
        action: String,
        ok: Boolean,
        detail: String,
        data: JSONObject = JSONObject(),
    ) {
        AgentTelemetry.complete(this, commandId, action.substringAfterLast('.').lowercase(), ok, detail, data)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Yume ADB testing", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(detail: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Yume agent test")
        .setContentText(detail.take(100))
        .setOngoing(true)
        .build()

    private companion object {
        const val CHANNEL_ID = "yume_adb_agent"
        const val NOTIFICATION_ID = 1108
        const val MAX_CODE_BYTES = 128
        // The largest sealed kit (docs/protocol/SEALED_KIT_1.md in YUME).
        const val MAX_KIT_BYTES = 16 + 12 + 4 + 1024 * 1024 + 1024 + 16
        const val BENCH_BUFFER_BYTES = 256 * 1024
        const val MIN_BENCH_BYTES = 1_024L
        const val DEFAULT_BENCH_BYTES = 64L * 1024L * 1024L
        const val MAX_BENCH_BYTES = 1024L * 1024L * 1024L
        const val MIN_BENCH_TIMEOUT_MS = 1_000L
        const val DEFAULT_BENCH_TIMEOUT_MS = 30_000L
        const val MAX_BENCH_TIMEOUT_MS = 180_000L
    }
}
