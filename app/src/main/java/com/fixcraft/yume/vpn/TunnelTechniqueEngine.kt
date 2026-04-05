package com.fixcraft.yume.vpn

import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.transport.YumeTransportTuning

sealed interface TunnelRuntimeConfig {
    val technique: ConnectionTechnique
    val performanceProfile: PerformanceProfile
    val summary: String
    val diagnosticsSummary: String
}

data class TechniqueStats(
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long,
)

interface TechniqueDataPlane {
    val technique: ConnectionTechnique
    val summary: String

    suspend fun start()
    fun stop()
    fun setVerificationMode(enabled: Boolean)
    fun stats(): TechniqueStats?
    fun diagnosticsSummary(): String
}

data class Tun2SocksRuntimeConfig(
    override val performanceProfile: PerformanceProfile,
    val maxActiveTcpRelays: Int,
    val relayAcquireTimeoutMs: Long,
    val relayAcquireSliceMs: Long,
    val idleTcpEvictionMs: Long,
    val tcpEvictionGraceMs: Long,
    val tcpEvictionBatchSize: Int,
    val bridgeChunkBytes: Int,
    val tcpStreamBufferBytes: Int,
    val udpStreamBufferBytes: Int,
    val outboundFrameQueueCapacity: Int,
    val tun2SocksConnectTimeoutMs: Int,
    val tun2SocksTcpReadWriteTimeoutMs: Int,
    val tun2SocksUdpReadWriteTimeoutMs: Int,
    val maxDnsUdpAssociations: Int,
    val maxUdpAssociations: Int,
    val dnsRelayMaxConcurrency: Int,
    val preRoutingStabilityDelayMs: Long,
    val postRoutingTrafficRampDelayMs: Long,
) : TunnelRuntimeConfig {
    override val technique: ConnectionTechnique = ConnectionTechnique.Tun2SocksStable
    override val summary: String = "${technique.displayName} / ${performanceProfile.displayName}"
    override val diagnosticsSummary: String = bridgeDiagnosticsSummary()

    fun bridgeDiagnosticsSummary(includeTechnique: Boolean = true): String = buildString {
        if (includeTechnique) {
            appendLine("Technique: ${technique.displayName}")
        }
        appendLine("Profile: ${performanceProfile.displayName}")
        appendLine("TCP relays: $maxActiveTcpRelays")
        appendLine("Relay acquire timeout: ${relayAcquireTimeoutMs}ms")
        appendLine("Relay acquire slice: ${relayAcquireSliceMs}ms")
        appendLine("Idle TCP eviction: ${idleTcpEvictionMs}ms")
        appendLine("TCP eviction grace: ${tcpEvictionGraceMs}ms")
        appendLine("TCP eviction batch: $tcpEvictionBatchSize")
        appendLine("Bridge chunk: ${bridgeChunkBytes / 1024} KiB")
        appendLine("TCP stream buffer: ${tcpStreamBufferBytes / 1024} KiB")
        appendLine("UDP stream buffer: ${udpStreamBufferBytes / 1024} KiB")
        appendLine("Outbound frame queue: $outboundFrameQueueCapacity")
        appendLine("tun2socks connect timeout: ${tun2SocksConnectTimeoutMs}ms")
        appendLine("tun2socks TCP read/write timeout: ${tun2SocksTcpReadWriteTimeoutMs}ms")
        appendLine("tun2socks UDP read/write timeout: ${tun2SocksUdpReadWriteTimeoutMs}ms")
        appendLine("Max DNS UDP associations: $maxDnsUdpAssociations")
        appendLine("Max UDP associations: $maxUdpAssociations")
        appendLine("DNS relay concurrency: $dnsRelayMaxConcurrency")
        appendLine("Pre-routing stability delay: ${preRoutingStabilityDelayMs}ms")
        appendLine("Post-routing traffic ramp: ${postRoutingTrafficRampDelayMs}ms")
        append("UDP 443 policy: forced TCP fallback")
    }

    fun toBootstrapTuning(): YumeTransportTuning {
        return YumeTransportTuning(
            tcpStreamMaxBufferedBytes = tcpStreamBufferBytes,
            udpStreamMaxBufferedBytes = udpStreamBufferBytes,
            outboundFrameQueueCapacity = outboundFrameQueueCapacity,
        )
    }
}

data class NativeDirectRuntimeConfig(
    override val performanceProfile: PerformanceProfile,
    val bridgeTuning: Tun2SocksRuntimeConfig,
    val compatibilityBridgeEnabled: Boolean = true,
) : TunnelRuntimeConfig {
    override val technique: ConnectionTechnique = ConnectionTechnique.NativeDirectBeta
    override val summary: String = "${technique.displayName} / ${performanceProfile.displayName}"
    override val diagnosticsSummary: String = buildString {
        appendLine("Technique: ${technique.displayName}")
        appendLine("Profile: ${performanceProfile.displayName}")
        if (compatibilityBridgeEnabled) {
            appendLine("Status: bridge-backed beta path with technique-specific tuning")
        } else {
            appendLine("Status: packet-native data plane")
        }
        appendLine()
        appendLine("Applied bridge tuning")
        append(bridgeTuning.bridgeDiagnosticsSummary(includeTechnique = false))
    }
}

interface TunnelTechniqueEngine {
    val technique: ConnectionTechnique
    val isAvailable: Boolean

    fun runtimeConfig(profile: PerformanceProfile): TunnelRuntimeConfig

    fun createDataPlane(
        runtimeConfig: TunnelRuntimeConfig,
        start: suspend () -> Unit,
        stop: () -> Unit,
        setVerificationMode: (Boolean) -> Unit,
        stats: () -> TechniqueStats?,
    ): TechniqueDataPlane
}

private class LambdaTechniqueDataPlane(
    override val technique: ConnectionTechnique,
    override val summary: String,
    private val diagnostics: String,
    private val starter: suspend () -> Unit,
    private val stopper: () -> Unit,
    private val verificationModeSetter: (Boolean) -> Unit,
    private val statsReader: () -> TechniqueStats?,
) : TechniqueDataPlane {
    override suspend fun start() {
        starter()
    }

    override fun stop() {
        stopper()
    }

    override fun setVerificationMode(enabled: Boolean) {
        verificationModeSetter(enabled)
    }

    override fun stats(): TechniqueStats? = statsReader()

    override fun diagnosticsSummary(): String = diagnostics
}

object Tun2SocksTechniqueEngine : TunnelTechniqueEngine {
    override val technique: ConnectionTechnique = ConnectionTechnique.Tun2SocksStable
    override val isAvailable: Boolean = true

    override fun runtimeConfig(profile: PerformanceProfile): TunnelRuntimeConfig {
        return stableBridgeTuning(profile)
    }

    override fun createDataPlane(
        runtimeConfig: TunnelRuntimeConfig,
        start: suspend () -> Unit,
        stop: () -> Unit,
        setVerificationMode: (Boolean) -> Unit,
        stats: () -> TechniqueStats?,
    ): TechniqueDataPlane {
        require(runtimeConfig is Tun2SocksRuntimeConfig)
        return LambdaTechniqueDataPlane(
            technique = technique,
            summary = runtimeConfig.summary,
            diagnostics = runtimeConfig.diagnosticsSummary,
            starter = start,
            stopper = stop,
            verificationModeSetter = setVerificationMode,
            statsReader = stats,
        )
    }
}

object NativeDirectTechniqueEngine : TunnelTechniqueEngine {
    override val technique: ConnectionTechnique = ConnectionTechnique.NativeDirectBeta
    override val isAvailable: Boolean = BuildConfig.ENABLE_NATIVE_DIRECT_BETA

    override fun runtimeConfig(profile: PerformanceProfile): TunnelRuntimeConfig {
        return NativeDirectRuntimeConfig(
            performanceProfile = profile,
            bridgeTuning = nativeDirectBridgeTuning(profile),
        )
    }

    override fun createDataPlane(
        runtimeConfig: TunnelRuntimeConfig,
        start: suspend () -> Unit,
        stop: () -> Unit,
        setVerificationMode: (Boolean) -> Unit,
        stats: () -> TechniqueStats?,
    ): TechniqueDataPlane {
        require(runtimeConfig is NativeDirectRuntimeConfig)
        return LambdaTechniqueDataPlane(
            technique = technique,
            summary = runtimeConfig.summary,
            diagnostics = runtimeConfig.diagnosticsSummary,
            starter = start,
            stopper = stop,
            verificationModeSetter = setVerificationMode,
            statsReader = stats,
        )
    }
}

private fun stableBridgeTuning(profile: PerformanceProfile): Tun2SocksRuntimeConfig {
    return when (profile) {
        PerformanceProfile.Latency -> Tun2SocksRuntimeConfig(
            performanceProfile = profile,
            maxActiveTcpRelays = 16,
            relayAcquireTimeoutMs = 1_500L,
            relayAcquireSliceMs = 150L,
            idleTcpEvictionMs = 2_500L,
            tcpEvictionGraceMs = 1_500L,
            tcpEvictionBatchSize = 4,
            bridgeChunkBytes = 4 * 1024,
            tcpStreamBufferBytes = 192 * 1024,
            udpStreamBufferBytes = 96 * 1024,
            outboundFrameQueueCapacity = 48,
            tun2SocksConnectTimeoutMs = 4_000,
            tun2SocksTcpReadWriteTimeoutMs = 60_000,
            tun2SocksUdpReadWriteTimeoutMs = 9_000,
            maxDnsUdpAssociations = 20,
            maxUdpAssociations = 28,
            dnsRelayMaxConcurrency = 6,
            preRoutingStabilityDelayMs = 1_000L,
            postRoutingTrafficRampDelayMs = 4_000L,
        )

        PerformanceProfile.Balanced -> Tun2SocksRuntimeConfig(
            performanceProfile = profile,
            maxActiveTcpRelays = 24,
            relayAcquireTimeoutMs = 3_500L,
            relayAcquireSliceMs = 200L,
            idleTcpEvictionMs = 5_000L,
            tcpEvictionGraceMs = 2_200L,
            tcpEvictionBatchSize = 4,
            bridgeChunkBytes = 4 * 1024,
            tcpStreamBufferBytes = 320 * 1024,
            udpStreamBufferBytes = 160 * 1024,
            outboundFrameQueueCapacity = 80,
            tun2SocksConnectTimeoutMs = 5_000,
            tun2SocksTcpReadWriteTimeoutMs = 120_000,
            tun2SocksUdpReadWriteTimeoutMs = 15_000,
            maxDnsUdpAssociations = 24,
            maxUdpAssociations = 36,
            dnsRelayMaxConcurrency = 8,
            preRoutingStabilityDelayMs = 1_200L,
            postRoutingTrafficRampDelayMs = 6_000L,
        )

        PerformanceProfile.Throughput -> Tun2SocksRuntimeConfig(
            performanceProfile = profile,
            maxActiveTcpRelays = 36,
            relayAcquireTimeoutMs = 6_000L,
            relayAcquireSliceMs = 250L,
            idleTcpEvictionMs = 9_000L,
            tcpEvictionGraceMs = 3_500L,
            tcpEvictionBatchSize = 2,
            bridgeChunkBytes = 8 * 1024,
            tcpStreamBufferBytes = 512 * 1024,
            udpStreamBufferBytes = 256 * 1024,
            outboundFrameQueueCapacity = 128,
            tun2SocksConnectTimeoutMs = 8_000,
            tun2SocksTcpReadWriteTimeoutMs = 180_000,
            tun2SocksUdpReadWriteTimeoutMs = 30_000,
            maxDnsUdpAssociations = 32,
            maxUdpAssociations = 52,
            dnsRelayMaxConcurrency = 12,
            preRoutingStabilityDelayMs = 1_500L,
            postRoutingTrafficRampDelayMs = 8_000L,
        )
    }
}

private fun nativeDirectBridgeTuning(profile: PerformanceProfile): Tun2SocksRuntimeConfig {
    return when (profile) {
        PerformanceProfile.Latency -> Tun2SocksRuntimeConfig(
            performanceProfile = profile,
            maxActiveTcpRelays = 20,
            relayAcquireTimeoutMs = 1_800L,
            relayAcquireSliceMs = 150L,
            idleTcpEvictionMs = 3_000L,
            tcpEvictionGraceMs = 1_500L,
            tcpEvictionBatchSize = 4,
            bridgeChunkBytes = 4 * 1024,
            tcpStreamBufferBytes = 224 * 1024,
            udpStreamBufferBytes = 112 * 1024,
            outboundFrameQueueCapacity = 64,
            tun2SocksConnectTimeoutMs = 3_500,
            tun2SocksTcpReadWriteTimeoutMs = 75_000,
            tun2SocksUdpReadWriteTimeoutMs = 10_000,
            maxDnsUdpAssociations = 24,
            maxUdpAssociations = 32,
            dnsRelayMaxConcurrency = 8,
            preRoutingStabilityDelayMs = 850L,
            postRoutingTrafficRampDelayMs = 3_000L,
        )

        PerformanceProfile.Balanced -> Tun2SocksRuntimeConfig(
            performanceProfile = profile,
            maxActiveTcpRelays = 30,
            relayAcquireTimeoutMs = 3_000L,
            relayAcquireSliceMs = 200L,
            idleTcpEvictionMs = 5_500L,
            tcpEvictionGraceMs = 2_200L,
            tcpEvictionBatchSize = 4,
            bridgeChunkBytes = 8 * 1024,
            tcpStreamBufferBytes = 384 * 1024,
            udpStreamBufferBytes = 192 * 1024,
            outboundFrameQueueCapacity = 96,
            tun2SocksConnectTimeoutMs = 4_500,
            tun2SocksTcpReadWriteTimeoutMs = 105_000,
            tun2SocksUdpReadWriteTimeoutMs = 14_000,
            maxDnsUdpAssociations = 32,
            maxUdpAssociations = 44,
            dnsRelayMaxConcurrency = 12,
            preRoutingStabilityDelayMs = 1_000L,
            postRoutingTrafficRampDelayMs = 4_500L,
        )

        PerformanceProfile.Throughput -> Tun2SocksRuntimeConfig(
            performanceProfile = profile,
            maxActiveTcpRelays = 44,
            relayAcquireTimeoutMs = 5_000L,
            relayAcquireSliceMs = 250L,
            idleTcpEvictionMs = 8_500L,
            tcpEvictionGraceMs = 3_000L,
            tcpEvictionBatchSize = 2,
            bridgeChunkBytes = 8 * 1024,
            tcpStreamBufferBytes = 640 * 1024,
            udpStreamBufferBytes = 320 * 1024,
            outboundFrameQueueCapacity = 160,
            tun2SocksConnectTimeoutMs = 6_500,
            tun2SocksTcpReadWriteTimeoutMs = 150_000,
            tun2SocksUdpReadWriteTimeoutMs = 25_000,
            maxDnsUdpAssociations = 40,
            maxUdpAssociations = 60,
            dnsRelayMaxConcurrency = 16,
            preRoutingStabilityDelayMs = 1_300L,
            postRoutingTrafficRampDelayMs = 6_500L,
        )
    }
}
