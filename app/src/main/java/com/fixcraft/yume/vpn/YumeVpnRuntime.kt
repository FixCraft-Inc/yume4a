package com.fixcraft.yume.vpn

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object YumeVpnRuntime {
    private val _state = MutableStateFlow(VpnConnectionState())
    val state: StateFlow<VpnConnectionState> = _state.asStateFlow()

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
}
