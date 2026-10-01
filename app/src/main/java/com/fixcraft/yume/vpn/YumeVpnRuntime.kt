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

/** The state the service publishes and every screen reads. */
object YumeVpnRuntime {
    private val _state = MutableStateFlow(VpnConnectionState())
    val state: StateFlow<VpnConnectionState> = _state.asStateFlow()

    // The service samples traffic while it runs, whether or not a screen is
    // showing, so the chart has its history when the application reopens.
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
        val rxRate = smooth(rawRxRate, last?.rxRateBps)
        val txRate = smooth(rawTxRate, last?.txRateBps)

        val sample = TrafficSample(now, rxBytes, txBytes, rxRate, txRate, rawRxRate, rawTxRate)
        _traffic.value = TrafficHistory((current.samples + sample).takeLast(MAX_TRAFFIC_SAMPLES), rxBytes, txBytes)
    }

    fun resetTrafficHistory() {
        _traffic.value = TrafficHistory()
    }

    private fun smooth(currentBps: Double, previousBps: Double?): Double {
        previousBps ?: return currentBps
        return previousBps * 0.55 + currentBps * 0.45
    }

    /** Replaces everything but the log, which only [appendLog] and [clearLog] change. */
    fun update(transform: (VpnConnectionState) -> VpnConnectionState) {
        synchronized(lock) {
            val previous = _state.value
            _state.value = transform(previous).copy(eventLog = previous.eventLog)
        }
    }

    fun clearLog() {
        synchronized(lock) {
            _state.value = _state.value.copy(eventLog = emptyList())
        }
    }

    /**
     * Adds a line to the application's log and to logcat. Callers pass what
     * happened, never a key, a code or a file's contents.
     */
    fun appendLog(level: VpnLogLevel, message: String, timestampEpochMs: Long = System.currentTimeMillis()) {
        when (level) {
            VpnLogLevel.Info -> Log.i(TAG, message)
            VpnLogLevel.Warn -> Log.w(TAG, message)
            VpnLogLevel.Error -> Log.e(TAG, message)
        }
        synchronized(lock) {
            val entry = VpnLogEntry(timestampEpochMs = timestampEpochMs, level = level, message = message)
            _state.value = _state.value.copy(eventLog = (_state.value.eventLog + entry).takeLast(MAX_LOG_ENTRIES))
        }
    }

    private val lock = Any()

    private const val TAG = "YumeVpn"
    private const val MAX_LOG_ENTRIES = 300
    // One sample a second for the chart's longest window, 15 minutes.
    private const val MAX_TRAFFIC_SAMPLES = 900
}
