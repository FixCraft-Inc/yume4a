package com.fixcraft.yume.vpn

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrafficSample(
    val tsMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val rxRateBps: Double,
    val txRateBps: Double,
    val rawRxRateBps: Double,
    val rawTxRateBps: Double,
)

data class TrafficHistory(
    val samples: List<TrafficSample> = emptyList(),
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
) {
    val latestRxRateBps: Double get() = samples.lastOrNull()?.rxRateBps ?: 0.0
    val latestTxRateBps: Double get() = samples.lastOrNull()?.txRateBps ?: 0.0
}

object YumeVpnRuntime {
    private val _state = MutableStateFlow(VpnConnectionState())
    val state: StateFlow<VpnConnectionState> = _state.asStateFlow()

    // Rolling traffic ring buffer. Always ticks while the VPN service is
    // alive (regardless of UI lifecycle), so when the user re-opens the
    // app the chart has up-to-date samples ready — no jarring reset, no
    // wasted UI redraws while the app is backgrounded.
    private val _traffic = MutableStateFlow(TrafficHistory())
    val traffic: StateFlow<TrafficHistory> = _traffic.asStateFlow()

    fun pushTrafficSample(rxBytes: Long, txBytes: Long) {
        val now = System.currentTimeMillis()
        val current = _traffic.value
        val last = current.samples.lastOrNull()
        val dtSec = if (last != null) (now - last.tsMs).coerceAtLeast(1L) / 1000.0 else 0.0

        val dRx = (rxBytes - current.totalRxBytes).coerceAtLeast(0L)
        val dTx = (txBytes - current.totalTxBytes).coerceAtLeast(0L)
        val rawRxRate = if (dtSec > 0.0) dRx / dtSec else 0.0
        val rawTxRate = if (dtSec > 0.0) dTx / dtSec else 0.0
        val rxRate = smoothTrafficRate(rawRxRate, last?.rxRateBps)
        val txRate = smoothTrafficRate(rawTxRate, last?.txRateBps)

        val sample = TrafficSample(now, rxBytes, txBytes, rxRate, txRate, rawRxRate, rawTxRate)
        val next = (current.samples + sample).takeLast(MAX_TRAFFIC_SAMPLES)
        _traffic.value = TrafficHistory(next, rxBytes, txBytes)
    }

    fun resetTrafficHistory() {
        _traffic.value = TrafficHistory()
    }

    private fun smoothTrafficRate(currentBps: Double, previousBps: Double?): Double {
        previousBps ?: return currentBps
        return previousBps * 0.55 + currentBps * 0.45
    }

    fun update(newState: VpnConnectionState, preserveLogs: Boolean = true) {
        val previous = _state.value
        val logs = when {
            !preserveLogs -> newState.eventLog
            newState.eventLog.isNotEmpty() -> newState.eventLog
            else -> previous.eventLog
        }.takeLast(MAX_LOG_ENTRIES)
        _state.value = newState.copy(eventLog = logs)
    }

    fun update(transform: (VpnConnectionState) -> VpnConnectionState) {
        val previous = _state.value
        val nextState = transform(_state.value)
        _state.value = nextState.copy(
            eventLog = when {
                nextState.eventLog.isNotEmpty() -> nextState.eventLog.takeLast(MAX_LOG_ENTRIES)
                else -> previous.eventLog
            }
        )
    }

    fun reset(newState: VpnConnectionState = VpnConnectionState()) {
        _state.value = newState.copy(eventLog = emptyList())
    }

    fun appendLog(level: VpnLogLevel, message: String) {
        val now = System.currentTimeMillis()
        var output = message
        if (level != VpnLogLevel.Error) {
            val key = "${level.name}|$message"
            val shouldDrop = synchronized(logLimiterLock) {
                val existing = recentLogMessages[key]
                if (existing != null && now - existing.lastSeenMs < DUPLICATE_LOG_WINDOW_MS) {
                    existing.lastSeenMs = now
                    existing.suppressed += 1
                    true
                } else {
                    if (existing != null && existing.suppressed > 0) {
                        output = "$message (suppressed ${existing.suppressed} repeats)"
                    }
                    recentLogMessages[key] = LogLimiterEntry(now, 0)
                    if (recentLogMessages.size > MAX_LOG_LIMITER_KEYS) {
                        val oldest = recentLogMessages.minByOrNull { it.value.lastSeenMs }?.key
                        if (oldest != null) {
                            recentLogMessages.remove(oldest)
                        }
                    }
                    false
                }
            }
            if (shouldDrop) {
                return
            }
        }

        val entry = VpnLogEntry(level = level, message = output)
        when (level) {
            VpnLogLevel.Info -> Log.i(TAG, output)
            VpnLogLevel.Warn -> Log.w(TAG, output)
            VpnLogLevel.Error -> Log.e(TAG, output)
        }
        _state.value = _state.value.copy(
            eventLog = (_state.value.eventLog + entry).takeLast(MAX_LOG_ENTRIES)
        )
    }

    private data class LogLimiterEntry(
        var lastSeenMs: Long,
        var suppressed: Int,
    )

    private val logLimiterLock = Any()
    private val recentLogMessages = LinkedHashMap<String, LogLimiterEntry>()

    private const val TAG = "YumeVpn"
    private const val MAX_LOG_ENTRIES = 120
    private const val MAX_LOG_LIMITER_KEYS = 96
    private const val DUPLICATE_LOG_WINDOW_MS = 5_000L
    // 900 samples @ 1 s = 15 minutes window — matches the longest range
    // the home-screen chart can render. ~22 KB at full fill, fine in
    // memory for a foreground VPN service.
    private const val MAX_TRAFFIC_SAMPLES = 900
}
