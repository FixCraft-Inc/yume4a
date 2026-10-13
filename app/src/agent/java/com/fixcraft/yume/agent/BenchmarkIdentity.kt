package com.fixcraft.yume.agent

import com.fixcraft.yume.core.YumeStatus
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnPhase

internal data class BenchmarkIdentity(
    val runId: String,
    val endpointId: Long,
    val session: Long,
    val underlyingNetworkHandle: Long,
) {
    companion object {
        fun from(state: VpnConnectionState): BenchmarkIdentity? {
            if (state.phase != VpnPhase.Connected || state.status?.session != YumeStatus.SESSION_ACTIVE) return null
            return BenchmarkIdentity(
                state.runId ?: return null,
                state.endpointId ?: return null,
                state.status.sessions,
                state.underlyingNetworkHandle ?: return null,
            )
        }
    }
}
