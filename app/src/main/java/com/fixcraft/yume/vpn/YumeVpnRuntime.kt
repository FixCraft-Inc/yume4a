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
        val rxRate = if (dtSec > 0.0) dRx / dtSec else 0.0
        val txRate = if (dtSec > 0.0) dTx / dtSec else 0.0

        val sample = TrafficSample(now, rxBytes, txBytes, rxRate, txRate)
        val next = (current.samples + sample).takeLast(MAX_TRAFFIC_SAMPLES)
        _traffic.value = TrafficHistory(next, rxBytes, txBytes)
    }

    fun resetTrafficHistory() {
        _traffic.value = TrafficHistory()
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
        val entry = VpnLogEntry(level = level, message = message)
        when (level) {
            VpnLogLevel.Info -> Log.i(TAG, message)
            VpnLogLevel.Warn -> Log.w(TAG, message)
            VpnLogLevel.Error -> Log.e(TAG, message)
        }
        _state.value = _state.value.copy(
            eventLog = (_state.value.eventLog + entry).takeLast(MAX_LOG_ENTRIES)
        )
    }

    private const val TAG = "YumeVpn"
    private const val MAX_LOG_ENTRIES = 120
    // 60 samples @ 1 s = 1 minute window. Cheap to hold in memory.
    private const val MAX_TRAFFIC_SAMPLES = 60
}
