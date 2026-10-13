package com.fixcraft.yume.agent

import com.fixcraft.yume.core.YumeStatus
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnPhase
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkIdentityTest {
    private val status = YumeStatus(
        state = YumeStatus.STATE_RUNNING, session = YumeStatus.SESSION_ACTIVE,
        lastFailure = 0, epochBytes = 0, connectedMs = 0, retryMs = 0, sessions = 1,
        failedAttempts = 0, idleEpochRotation = false, payloadBytesSent = 0,
        payloadBytesReceived = 0, recordBytesSent = 0, recordBytesReceived = 0,
        maxQueuedBytes = 0, maxEpochBytes = 0, creditReturnsPerWindow = 0,
        tcpConnections = 0, udpDestinations = 0, peerFingerprint = "", lastFailureMessage = "",
    )
    private val connected = VpnConnectionState(
        phase = VpnPhase.Connected, runId = "run", endpointId = 1,
        underlyingNetworkHandle = 7, status = status,
    )

    @Test fun disconnectBeforeSocketCreationInvalidatesIdentity() {
        assertNull(BenchmarkIdentity.from(connected.copy(phase = VpnPhase.Disconnecting)))
        assertNull(BenchmarkIdentity.from(connected.copy(status = status.copy(session = YumeStatus.SESSION_ENDED))))
    }

    @Test fun replacementRunSessionAndNetworkAreDistinct() {
        val original = BenchmarkIdentity.from(connected)
        for (changed in listOf(
            connected.copy(runId = "new run"), connected.copy(endpointId = 2),
            connected.copy(status = status.copy(sessions = 2)), connected.copy(underlyingNetworkHandle = 8),
        )) assertNotEquals(original, BenchmarkIdentity.from(changed))
    }
}
