package com.fixcraft.yume.vpn

import com.fixcraft.yume.data.ConnectionTechnique

enum class NativeDirectEngineStatus {
    CompatibilityBridgeFallback,
    PacketNative,
}

data class NativeDirectEngineSnapshot(
    val status: NativeDirectEngineStatus,
    val packetEngineActive: Boolean,
    val ipv6FailClosed: Boolean,
    val tcpFlowCount: Int? = null,
    val udpFlowCount: Int? = null,
    val queueCapacity: Int? = null,
    val tcpFlowWindowBytes: Int? = null,
    val udpFlowBufferBytes: Int? = null,
)

interface NativeDirectPacketEngine {
    suspend fun start()
    fun stop()
    fun setVerificationMode(enabled: Boolean)
    fun stats(): TechniqueStats?
    fun snapshot(): NativeDirectEngineSnapshot
}

internal class NativeDirectTunnelPacketEngine(
    private val queueCapacity: Int,
    private val tcpFlowWindowBytes: Int,
    private val udpFlowBufferBytes: Int,
    private val startBridge: suspend () -> Unit,
    private val stopBridge: () -> Unit,
    private val setBridgeVerificationMode: (Boolean) -> Unit,
    private val statsReader: () -> TechniqueStats?,
) : NativeDirectPacketEngine {
    override suspend fun start() {
        startBridge()
    }

    override fun stop() {
        stopBridge()
    }

    override fun setVerificationMode(enabled: Boolean) {
        setBridgeVerificationMode(enabled)
    }

    override fun stats(): TechniqueStats? = statsReader()

    override fun snapshot(): NativeDirectEngineSnapshot {
        return NativeDirectEngineSnapshot(
            status = NativeDirectEngineStatus.CompatibilityBridgeFallback,
            packetEngineActive = false,
            ipv6FailClosed = true,
            queueCapacity = queueCapacity,
            tcpFlowWindowBytes = tcpFlowWindowBytes,
            udpFlowBufferBytes = udpFlowBufferBytes,
        )
    }
}

internal class NativeDirectTechniqueDataPlane(
    private val runtimeConfig: NativeDirectRuntimeConfig,
    private val packetEngine: NativeDirectPacketEngine,
) : TechniqueDataPlane {
    override val technique: ConnectionTechnique = ConnectionTechnique.NativeDirectBeta
    override val summary: String = runtimeConfig.summary

    override suspend fun start() {
        packetEngine.start()
    }

    override fun stop() {
        packetEngine.stop()
    }

    override fun setVerificationMode(enabled: Boolean) {
        packetEngine.setVerificationMode(enabled)
    }

    override fun stats(): TechniqueStats? = packetEngine.stats()

    override fun diagnosticsSummary(): String = runtimeConfig.diagnosticsSummary
}
