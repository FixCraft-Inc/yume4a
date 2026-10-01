package com.fixcraft.yume.agent

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.vpn.TrafficHistory
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime
import com.fixcraft.yume.core.YumeLibrary
import com.fixcraft.yume.core.YumeStatus
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal object AgentTelemetry {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val fileWriteLock = Any()
    private val phaseMilestones = mutableListOf<JSONObject>()
    private var connectStartedElapsedMs: Long? = null
    private var lastOperation = JSONObject()
    private val nativeBuildIdentity: JSONObject by lazy {
        val build = YumeLibrary.build() ?: return@lazy JSONObject().put("available", false)
        JSONObject()
            .put("available", true)
            .put("abi_version", build.abiVersion)
            .put("yume_version", build.productVersion)
            .put("protocol", build.protocolName)
            .put("suite", build.suite)
            .put("config_schema", build.configSchema)
            .put("crypto_backend", build.cryptoBackend)
    }

    fun start(context: Context) {
        check(BuildConfig.ENABLE_ADB_TEST_CONTROL) { "ADB test control is disabled" }
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            var previous: VpnPhase? = null
            YumeVpnRuntime.state.collectLatest { state ->
                if (state.phase != previous) {
                    synchronized(lock) {
                        phaseMilestones += JSONObject()
                            .put("phase", state.phase.name)
                            .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                            .put("since_connect_ms", elapsedSinceConnect())
                        if (phaseMilestones.size > MAX_PHASE_MILESTONES) {
                            phaseMilestones.removeAt(0)
                        }
                    }
                    previous = state.phase
                    writeSnapshot(appContext)
                }
            }
        }
    }

    fun beginConnect(commandId: String) {
        synchronized(lock) {
            connectStartedElapsedMs = SystemClock.elapsedRealtime()
            phaseMilestones.clear()
            phaseMilestones += JSONObject()
                .put("phase", "CommandReceived")
                .put("elapsed_realtime_ms", connectStartedElapsedMs)
                .put("since_connect_ms", 0)
            lastOperation = JSONObject()
                .put("command_id", commandId)
                .put("action", "connect")
                .put("status", "started")
        }
    }

    fun complete(
        context: Context,
        commandId: String,
        action: String,
        ok: Boolean,
        detail: String,
        data: JSONObject = JSONObject(),
    ) {
        val boundedDetail = detail.take(MAX_DETAIL_CHARS)
        val completedEpochMs = System.currentTimeMillis()
        val completedElapsedMs = SystemClock.elapsedRealtime()
        val result = JSONObject()
            .put("schema", SCHEMA)
            .put("command_id", commandId)
            .put("action", action)
            .put("ok", ok)
            .put("detail", boundedDetail)
            .put("completed_epoch_ms", completedEpochMs)
            .put("completed_elapsed_realtime_ms", completedElapsedMs)
            .put("data", data)
        val retainedResult = if (
            action.substringAfterLast('.').equals("snapshot", ignoreCase = true)
        ) {
            // The command result must contain the requested full snapshot, but
            // retaining that same object would recursively embed every prior
            // status result through snapshot.last_operation.
            JSONObject()
                .put("schema", SCHEMA)
                .put("command_id", commandId)
                .put("action", action)
                .put("ok", ok)
                .put("detail", boundedDetail)
                .put("completed_epoch_ms", completedEpochMs)
                .put("completed_elapsed_realtime_ms", completedElapsedMs)
                .put("data", JSONObject().put("omitted_from_snapshot", true))
        } else {
            result
        }
        synchronized(lock) { lastOperation = retainedResult }
        writeSnapshot(context)
        // Publish the command result last. Callers must never observe success
        // before all retained telemetry for that operation is durable.
        writeResult(file(context, AgentProtocol.resultFile(commandId)), result)
    }

    fun writeConsent(context: Context, granted: Boolean, detail: String) {
        val result = JSONObject()
            .put("schema", SCHEMA)
            .put("granted", granted)
            .put("detail", detail.take(MAX_DETAIL_CHARS))
            .put("epoch_ms", System.currentTimeMillis())
        writeJson(file(context, AgentProtocol.CONSENT_FILE), result)
    }

    fun writeSnapshot(context: Context): JSONObject {
        val state = YumeVpnRuntime.state.value
        val traffic = YumeVpnRuntime.traffic.value
        val snapshot = JSONObject()
            .put("schema", SCHEMA)
            .put("captured_epoch_ms", System.currentTimeMillis())
            .put("captured_elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("build", buildJson())
            .put("device", deviceJson())
            .put("process", processJson())
            .put("vpn", stateJson(state))
            .put("traffic", trafficJson(traffic))
            .put("timing", timingJson())
            .put("last_operation", synchronized(lock) { JSONObject(lastOperation.toString()) })
        writeJson(file(context, AgentProtocol.SNAPSHOT_FILE), snapshot)
        return snapshot
    }

    fun file(context: Context, name: String): File {
        val directory = File(context.noBackupFilesDir, AgentProtocol.RELATIVE_DIR)
        check(directory.exists() || directory.mkdirs()) { "Could not create agent data directory" }
        return File(directory, name)
    }

    fun writeText(file: File, text: String) {
        atomicWrite(file, text.toByteArray(Charsets.UTF_8))
    }

    private fun buildJson() = JSONObject()
        .put("application_id", BuildConfig.APPLICATION_ID)
        .put("version_name", BuildConfig.VERSION_NAME)
        .put("version_code", BuildConfig.VERSION_CODE)
        .put("build_type", BuildConfig.BUILD_TYPE)
        .put("debuggable", BuildConfig.DEBUG)
        .put("adb_test_control", BuildConfig.ENABLE_ADB_TEST_CONTROL)
        .put("yume", BuildConfig.YUME_VERSION)
        .put("basefwx", BuildConfig.BASEFWX_ENGINE_VERSION)
        .put("native", JSONObject(nativeBuildIdentity.toString()))

    private fun deviceJson() = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("android_release", Build.VERSION.RELEASE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))

    private fun processJson(): JSONObject {
        val status = runCatching { File("/proc/self/status").readLines() }.getOrDefault(emptyList())
        fun statusValue(key: String): String? = status.firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()
        return JSONObject()
            .put("pid", android.os.Process.myPid())
            .put("threads", statusValue("Threads"))
            .put("vm_rss", statusValue("VmRSS"))
            .put("vm_peak", statusValue("VmPeak"))
            .put("fd_count", File("/proc/self/fd").list()?.size ?: JSONObject.NULL)
            .put("java_heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            .put("java_heap_max_bytes", Runtime.getRuntime().maxMemory())
            .put("native_heap_bytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun stateJson(state: VpnConnectionState) = JSONObject()
        .put("phase", state.phase.name)
        .put("kit_id", state.kitId ?: JSONObject.NULL)
        .put("kit_name", state.kitName ?: JSONObject.NULL)
        .put("endpoint", state.endpointLabel ?: JSONObject.NULL)
        .put("failure", state.failure.name)
        .put("failure_detail", state.failureDetail)
        .put("waiting_for_network", state.waitingForNetwork)
        .put("upstream", state.upstream?.name ?: JSONObject.NULL)
        .put("server_address", state.serverAddress ?: JSONObject.NULL)
        .put("mtu", state.mtu)
        .put("dns", JSONArray(state.dnsServers))
        .put("carries_ipv6", state.carriesIpv6)
        .put("endpoint_status", state.status?.let(::statusJson) ?: JSONObject.NULL)
        .put("events", JSONArray().apply {
            state.eventLog.forEach { entry ->
                put(JSONObject()
                    .put("epoch_ms", entry.timestampEpochMs)
                    .put("level", entry.level.name)
                    .put("message", entry.message))
            }
        })

    // yume_endpoint_status as the library reported it. The fingerprint is the
    // server's public identity, not a secret.
    private fun statusJson(status: YumeStatus) = JSONObject()
        .put("state", status.state)
        .put("session", status.session)
        .put("last_failure", status.lastFailure)
        .put("last_failure_message", status.lastFailureMessage)
        .put("sessions", status.sessions)
        .put("failed_attempts", status.failedAttempts)
        .put("connected_ms", status.connectedMs)
        .put("retry_ms", status.retryMs)
        .put("epoch_bytes", status.epochBytes)
        .put("idle_epoch_rotation", status.idleEpochRotation)
        .put("payload_bytes_sent", status.payloadBytesSent)
        .put("payload_bytes_received", status.payloadBytesReceived)
        .put("record_bytes_sent", status.recordBytesSent)
        .put("record_bytes_received", status.recordBytesReceived)
        .put("tcp_connections", status.tcpConnections)
        .put("udp_destinations", status.udpDestinations)
        .put("peer_fingerprint_sha256", status.peerFingerprint)

    private fun trafficJson(traffic: TrafficHistory) = JSONObject()
        .put("total_rx_bytes", traffic.totalRxBytes)
        .put("total_tx_bytes", traffic.totalTxBytes)
        .put("latest_rx_bytes_per_second", traffic.latestRxRateBps)
        .put("latest_tx_bytes_per_second", traffic.latestTxRateBps)
        .put("samples", JSONArray().apply {
            traffic.samples.takeLast(MAX_TRAFFIC_SAMPLES).forEach { sample ->
                put(JSONObject()
                    .put("epoch_ms", sample.tsMs)
                    .put("rx_bytes", sample.rxBytes)
                    .put("tx_bytes", sample.txBytes)
                    .put("rx_bps", sample.rxRateBps)
                    .put("tx_bps", sample.txRateBps)
                    .put("raw_rx_bps", sample.rawRxRateBps)
                    .put("raw_tx_bps", sample.rawTxRateBps))
            }
        })

    private fun timingJson(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("connect_started_elapsed_realtime_ms", connectStartedElapsedMs ?: JSONObject.NULL)
            .put("elapsed_since_connect_ms", elapsedSinceConnect())
            .put("phase_milestones", JSONArray(phaseMilestones.map { JSONObject(it.toString()) }))
    }

    private fun elapsedSinceConnect(): Any {
        val startedAt = connectStartedElapsedMs ?: return JSONObject.NULL
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    private fun writeJson(file: File, value: JSONObject) {
        atomicWrite(file, (value.toString() + "\n").toByteArray(Charsets.UTF_8))
    }

    private fun writeResult(file: File, value: JSONObject) {
        synchronized(fileWriteLock) {
            val retained = file.parentFile?.listFiles { candidate ->
                RESULT_FILE.matches(candidate.name) && candidate.name != file.name
            }.orEmpty().sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            val excess = (retained.size - (MAX_RESULT_FILES - 1)).coerceAtLeast(0)
            retained.take(excess).forEach { stale ->
                check(stale.delete()) { "Could not prune stale result ${stale.name}" }
            }
            writeJson(file, value)
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        synchronized(fileWriteLock) {
            val temporary = File(target.parentFile, ".${target.name}.tmp")
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Could not publish ${target.name}" }
        }
    }

    private const val SCHEMA = 1
    private const val MAX_DETAIL_CHARS = 2_000
    private const val MAX_PHASE_MILESTONES = 64
    private const val MAX_TRAFFIC_SAMPLES = 120
    private const val MAX_RESULT_FILES = 256
    private val RESULT_FILE = Regex("^result-[A-Za-z0-9._-]{1,96}\\.json$")
}
