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
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.data.SecureMaterialSummary
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.ServerProfileStore
import com.fixcraft.yume.diagnostics.YumeLocalSelfTest
import com.fixcraft.yume.diagnostics.YumeLocalSelfTestMode
import com.fixcraft.yume.share.ShareFile
import com.fixcraft.yume.transport.SharedYumeNative
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
                AgentProtocol.ACTION_PROFILES -> profiles(commandId)
                AgentProtocol.ACTION_CONNECT -> connect(intent, commandId)
                AgentProtocol.ACTION_DISCONNECT -> disconnect(commandId)
                AgentProtocol.ACTION_IMPORT_BUNDLE -> importBundle(commandId)
                AgentProtocol.ACTION_SELF_TEST -> selfTest(intent, commandId)
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

    private fun profiles(commandId: String) {
        require(AppVaultManager.ensureReady(this)) { "The app vault is locked" }
        val store = ServerProfileStore.get(this)
        val selected = store.selectedProfileId.value
        val profiles = JSONArray().apply {
            store.profiles.value.forEach { profile ->
                put(JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("endpoint", profile.endpointLabel)
                    .put("sni", profile.serverName)
                    .put("selected", profile.id == selected)
                    .put("has_auth_key", profile.authKeyMaterialId.isNotBlank())
                    .put("requires_operator_identity", profile.requireAnonymProof))
            }
        }
        complete(
            commandId,
            "profiles",
            true,
            "Found ${profiles.length()} profile(s)",
            JSONObject().put("profiles", profiles).put("selected_profile_id", selected ?: JSONObject.NULL),
        )
    }

    private fun connect(intent: Intent, commandId: String) {
        val password = consumeSecret(
            AgentProtocol.authPasswordFile(commandId),
            MAX_PASSWORD_BYTES,
        )
        try {
            val profileId = intent.getStringExtra(AgentProtocol.EXTRA_PROFILE_ID)
                ?.trim().orEmpty()
            require(profileId.isNotEmpty()) { "profile_id is required" }
            require(AppVaultManager.ensureReady(this)) { "The app vault is locked" }
            require(ServerProfileStore.get(this).getProfile(profileId) != null) {
                "Profile not found"
            }
            if (VpnService.prepare(this) != null) {
                complete(
                    commandId,
                    "connect",
                    false,
                    "VPN consent is required; run the harness prepare command and approve it once",
                    JSONObject().put("consent_required", true),
                )
                return
            }
            AgentTelemetry.beginConnect(commandId)
            YumeVpnService.startConnect(this, profileId, password?.decodeToString())
            complete(
                commandId,
                "connect",
                true,
                "Connect command accepted",
                JSONObject().put("profile_id", profileId),
            )
        } finally {
            password?.fill(0)
        }
    }

    private fun disconnect(commandId: String) {
        YumeVpnService.startDisconnect(this)
        complete(commandId, "disconnect", true, "Disconnect command accepted")
    }

    private suspend fun importBundle(commandId: String) = withContext(Dispatchers.IO) {
        var bundleBytes: ByteArray? = null
        var passwordBytes: ByteArray? = null
        var ca: SecureMaterialSummary? = null
        var authKey: SecureMaterialSummary? = null
        var profileStore: ServerProfileStore? = null
        var importedProfile: ServerProfile? = null
        var previousSelectedProfileId: String? = null
        try {
            var inputFailure: Throwable? = null
            try {
                bundleBytes = consumeRequired(
                    AgentProtocol.importBundleFile(commandId),
                    MAX_BUNDLE_BYTES,
                )
            } catch (throwable: Throwable) {
                inputFailure = throwable
            }
            try {
                passwordBytes = consumeRequired(
                    AgentProtocol.importPasswordFile(commandId),
                    MAX_PASSWORD_BYTES,
                )
            } catch (throwable: Throwable) {
                val precedingFailure = inputFailure
                if (precedingFailure == null) {
                    inputFailure = throwable
                } else {
                    precedingFailure.addSuppressed(throwable)
                }
            }
            inputFailure?.let { throw it }
            val consumedBundle = checkNotNull(bundleBytes)
            val consumedPassword = checkNotNull(passwordBytes)
            require(AppVaultManager.ensureReady(this@AgentCommandService)) {
                "The app vault is locked"
            }
            val password = consumedPassword.decodeToString().trimEnd('\r', '\n')
            val bundle = ShareFile.decode(consumedBundle, password)
            validateBundle(bundle)

            val materials = SecureMaterialStore.get(this@AgentCommandService)
            if (bundle.anonymCaCertPem.isNotBlank()) {
                ca = materials.saveMaterialText(
                    SecureMaterialType.AnonymCa,
                    "ADB imported CA (${bundle.serverHost})",
                    bundle.anonymCaCertPem,
                    sourceLabel = "ADB agent .yss import",
                )
            }
            if (bundle.authPrivateKeyPem.isNotBlank()) {
                authKey = materials.saveImportedAuthKeyText(
                    displayName = "ADB imported key (${bundle.serverHost})",
                    rawText = bundle.authPrivateKeyPem,
                    encrypted = false,
                    password = "",
                    sourceLabel = "ADB agent .yss import",
                )
            }
            val profile = ServerProfile(
                name = bundle.label.trim().ifEmpty { bundle.serverHost },
                host = bundle.serverHost.trim(),
                port = bundle.serverPort,
                sni = bundle.tlsServerName.trim(),
                obfsSecret = bundle.obfsSecret.trim(),
                requireAnonymProof = bundle.requireOperatorIdentity,
                anonymCaMaterialId = ca?.id.orEmpty(),
                authKeyMaterialId = authKey?.id.orEmpty(),
                notes = "Imported by the ADB agent harness",
                tlsStealthProfile = bundle.tlsStealthProfile.trim().ifEmpty {
                    ServerProfile.DEFAULT_TLS_STEALTH_PROFILE
                },
                innerPsk = bundle.innerPsk.trim(),
                tlsPinSha256 = bundle.tlsPinSha256.trim(),
            )
            profileStore = ServerProfileStore.get(this@AgentCommandService)
            previousSelectedProfileId = profileStore.selectedProfileId.value
            importedProfile = profile
            profileStore.saveProfile(profile)
            profileStore.selectProfile(profile.id)
            complete(
                commandId,
                "import_bundle",
                true,
                "Imported and selected profile ${profile.name}",
                JSONObject()
                    .put("profile_id", profile.id)
                    .put("name", profile.name)
                    .put("endpoint", profile.endpointLabel)
                    .put("has_auth_key", authKey != null),
            )
        } catch (throwable: Throwable) {
            var profileRollbackSucceeded = true
            importedProfile?.let { profile ->
                runCatching {
                    val store = checkNotNull(profileStore)
                    store.deleteProfile(profile.id)
                    store.selectProfile(previousSelectedProfileId)
                }.onFailure { rollbackFailure ->
                    profileRollbackSucceeded = false
                    throwable.addSuppressed(rollbackFailure)
                }
            }
            // If profile rollback failed, retain its referenced materials so
            // the store does not contain dangling material IDs.
            if (profileRollbackSucceeded) {
                val materials = runCatching {
                    SecureMaterialStore.get(this@AgentCommandService)
                }.getOrNull()
                authKey?.let { runCatching { materials?.deleteMaterial(it.id) } }
                ca?.let { runCatching { materials?.deleteMaterial(it.id) } }
            }
            throw throwable
        } finally {
            bundleBytes?.fill(0)
            passwordBytes?.fill(0)
        }
    }

    private suspend fun selfTest(intent: Intent, commandId: String) = withContext(Dispatchers.Default) {
        val mode = when (intent.getStringExtra(AgentProtocol.EXTRA_MODE)?.lowercase()) {
            "full" -> YumeLocalSelfTestMode.Full
            else -> YumeLocalSelfTestMode.Quick
        }
        val nativeLifecycle = nativeLifecycleCheck()
        val report = YumeLocalSelfTest.run(mode, cacheDir) { progress ->
            startForeground(
                NOTIFICATION_ID,
                notification("${mode.id} self-test: ${progress.percent}% ${progress.label}"),
            )
        }
        val reportFile = AgentTelemetry.file(this@AgentCommandService, AgentProtocol.SELF_TEST_REPORT_FILE)
        AgentTelemetry.writeText(reportFile, report.toPlainText() + "\n")
        val failed = report.rows.filterNot { it.ok }.map { it.name }
        complete(
            commandId,
            "self_test",
            failed.isEmpty(),
            if (failed.isEmpty()) "${mode.id} self-test passed" else "Failed: ${failed.joinToString()}",
            JSONObject()
                .put("mode", mode.id)
                .put("elapsed_ms", report.elapsedMs)
                .put("global_score", report.globalScore ?: JSONObject.NULL)
                .put("platform_score", report.leagueScore ?: JSONObject.NULL)
                .put("failed_rows", JSONArray(failed))
                .put("native_lifecycle", nativeLifecycle)
                .put("report_file", AgentProtocol.SELF_TEST_REPORT_FILE),
        )
    }

    private fun nativeLifecycleCheck(): JSONObject {
        SharedYumeNative { true }.close() // Load libraries before the baseline.
        val fdsBefore = openFdCount()
        val threadsBefore = processThreadCount()
        repeat(NATIVE_LIFECYCLE_ITERATIONS) {
            val native = SharedYumeNative { true }
            try {
                native.stop()
                native.stop()
            } finally {
                native.close()
                native.close()
            }
        }
        val fdsAfter = openFdCount()
        val threadsAfter = processThreadCount()
        check(fdsAfter <= fdsBefore + MAX_NATIVE_FD_GROWTH) {
            "Native create/stop/close leaked file descriptors: $fdsBefore -> $fdsAfter"
        }
        return JSONObject()
            .put("iterations", NATIVE_LIFECYCLE_ITERATIONS)
            .put("fd_before", fdsBefore)
            .put("fd_after", fdsAfter)
            .put("threads_before", threadsBefore)
            .put("threads_after", threadsAfter)
    }

    private fun openFdCount(): Int =
        File("/proc/self/fd").list()?.size ?: error("Could not inspect process fds")

    private fun processThreadCount(): Int =
        File("/proc/self/task").list()?.size ?: error("Could not inspect process threads")

    private suspend fun networkBench(intent: Intent, commandId: String) = withContext(Dispatchers.IO) {
        val state = YumeVpnRuntime.state.value
        require(state.phase == VpnPhase.Connected && state.trafficRoutedThroughYume) {
            "VPN is not connected with verified routed traffic"
        }
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
                .put("tunnel_tx_delta_bytes", tunnelTxDelta)
                .put("route", state.activeRouteLabel ?: JSONObject.NULL),
        )
    }

    private fun validateBundle(bundle: ShareFile.Bundle) {
        require(bundle.serverHost.isNotBlank() && bundle.serverHost.length <= 253) { "Invalid server host" }
        require(bundle.serverHost.none { it.isWhitespace() || it.isISOControl() }) { "Invalid server host" }
        require(bundle.serverPort in 1..65535) { "Invalid server port" }
        require(SECRET_HEX.matches(bundle.obfsSecret.trim())) { "Admission secret must be 64 lowercase hex characters" }
        require(SECRET_HEX.matches(bundle.innerPsk.trim())) { "Inner PSK must be 64 lowercase hex characters" }
        require(bundle.tlsPinSha256.isBlank() || SECRET_HEX.matches(bundle.tlsPinSha256.trim())) {
            "TLS pin must be 64 lowercase hex characters"
        }
        require(!bundle.requireOperatorIdentity || bundle.anonymCaCertPem.isNotBlank()) {
            "Operator identity is required but the bundle has no CA certificate"
        }
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
        const val MAX_PASSWORD_BYTES = 4 * 1024
        const val MAX_BUNDLE_BYTES = 2 * 1024 * 1024
        const val BENCH_BUFFER_BYTES = 256 * 1024
        const val MIN_BENCH_BYTES = 1_024L
        const val DEFAULT_BENCH_BYTES = 64L * 1024L * 1024L
        const val MAX_BENCH_BYTES = 1024L * 1024L * 1024L
        const val MIN_BENCH_TIMEOUT_MS = 1_000L
        const val DEFAULT_BENCH_TIMEOUT_MS = 30_000L
        const val MAX_BENCH_TIMEOUT_MS = 180_000L
        const val NATIVE_LIFECYCLE_ITERATIONS = 100
        const val MAX_NATIVE_FD_GROWTH = 2
        val SECRET_HEX = Regex("^[0-9a-f]{64}$")
    }
}
