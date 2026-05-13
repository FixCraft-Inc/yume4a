package com.fixcraft.yume.vpn

import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.canonicalLabel
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
    val routeTcpBufferBytes: Int,
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
    override val summary: String = "${technique.canonicalLabel()} / ${performanceProfile.canonicalLabel()}"
    override val diagnosticsSummary: String = bridgeDiagnosticsSummary()

    fun bridgeDiagnosticsSummary(includeTechnique: Boolean = true): String = buildString {
        if (includeTechnique) {
            appendLine("Technique: ${technique.canonicalLabel()}")
        }
        appendLine("Profile: ${performanceProfile.canonicalLabel()}")
        appendLine("TCP relays: $maxActiveTcpRelays")
        appendLine("Relay acquire timeout: ${relayAcquireTimeoutMs}ms")
        appendLine("Relay acquire slice: ${relayAcquireSliceMs}ms")
        appendLine("Idle TCP eviction: ${idleTcpEvictionMs}ms")
        appendLine("TCP eviction grace: ${tcpEvictionGraceMs}ms")
        appendLine("TCP eviction batch: $tcpEvictionBatchSize")
        appendLine("Bridge chunk: ${bridgeChunkBytes / 1024} KiB")
        appendLine("Route-core TCP buffer: ${routeTcpBufferBytes / 1024} KiB")
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
        append("UDP 443 policy: route-core drop for TCP fallback")
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
    val engineStatus: NativeDirectEngineStatus = NativeDirectEngineStatus.PacketNative,
    val nativeQueueCapacity: Int,
    val tcpOpenConcurrency: Int,
    val tcpFlowWindowBytes: Int,
    val udpFlowBufferBytes: Int,
    val ipv6FailClosed: Boolean = true,
) : TunnelRuntimeConfig {
    override val technique: ConnectionTechnique = ConnectionTechnique.NativeDirectBeta
    override val summary: String = technique.canonicalLabel()
    override val diagnosticsSummary: String = buildString {
        appendLine("Technique: ${technique.canonicalLabel()}")
        appendLine("Profile: $NATIVE_DIRECT_FIXED_TUNING_LABEL")
        appendLine(
            "Status: " + when (engineStatus) {
                NativeDirectEngineStatus.CompatibilityBridgeFallback ->
                    "legacy bridge fallback"

                NativeDirectEngineStatus.PacketNative ->
                    "direct Yume stream tunnel backend"
            }
        )
        appendLine("Native queue: $nativeQueueCapacity")
        appendLine("Native TCP open fan-out: $tcpOpenConcurrency")
        appendLine("Native TCP active streams: ${bridgeTuning.maxActiveTcpRelays}")
        appendLine("Native TCP flow window: ${tcpFlowWindowBytes / 1024} KiB")
        appendLine("Native UDP flow buffer: ${udpFlowBufferBytes / 1024} KiB")
        appendLine("UDP policy: DNS only; non-DNS UDP is dropped so apps fall back to TCP")
        appendLine("IPv6 policy: ${if (ipv6FailClosed) "fail-closed" else "enabled"}")
        appendLine()
        appendLine("Applied tunnel tuning")
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
        val bridgeTuning = nativeDirectBridgeTuning()
        return NativeDirectRuntimeConfig(
            performanceProfile = PerformanceProfile.Balanced,
            bridgeTuning = bridgeTuning,
            nativeQueueCapacity = bridgeTuning.outboundFrameQueueCapacity,
            tcpOpenConcurrency = nativeDirectTcpOpenConcurrency(bridgeTuning.maxActiveTcpRelays),
            tcpFlowWindowBytes = bridgeTuning.tcpStreamBufferBytes,
            udpFlowBufferBytes = bridgeTuning.udpStreamBufferBytes,
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

private const val NATIVE_DIRECT_FIXED_TUNING_LABEL = "Fixed native tuning"

private fun stableBridgeTuning(profile: PerformanceProfile): Tun2SocksRuntimeConfig {
    return when (profile) {
        PerformanceProfile.Latency -> Tun2SocksRuntimeConfig(
            performanceProfile = profile,
            maxActiveTcpRelays = 48,
            relayAcquireTimeoutMs = 2_000L,
            relayAcquireSliceMs = 100L,
            idleTcpEvictionMs = 5_000L,
            tcpEvictionGraceMs = 2_000L,
            tcpEvictionBatchSize = 8,
            bridgeChunkBytes = 8 * 1024,
            routeTcpBufferBytes = 128 * 1024,
            tcpStreamBufferBytes = 512 * 1024,
            udpStreamBufferBytes = 128 * 1024,
            outboundFrameQueueCapacity = 128,
            tun2SocksConnectTimeoutMs = 4_000,
            tun2SocksTcpReadWriteTimeoutMs = 60_000,
            tun2SocksUdpReadWriteTimeoutMs = 9_000,
            maxDnsUdpAssociations = 32,
            maxUdpAssociations = 48,
            dnsRelayMaxConcurrency = 10,
            preRoutingStabilityDelayMs = 150L,
            postRoutingTrafficRampDelayMs = 0L,
        )

        PerformanceProfile.Balanced -> Tun2SocksRuntimeConfig(
            performanceProfile = profile,
            maxActiveTcpRelays = 64,
            relayAcquireTimeoutMs = 5_000L,
            relayAcquireSliceMs = 150L,
            idleTcpEvictionMs = 15_000L,
            tcpEvictionGraceMs = 5_000L,
            tcpEvictionBatchSize = 8,
            bridgeChunkBytes = 16 * 1024,
            routeTcpBufferBytes = 192 * 1024,
            tcpStreamBufferBytes = 1024 * 1024,
            udpStreamBufferBytes = 256 * 1024,
            outboundFrameQueueCapacity = 192,
            tun2SocksConnectTimeoutMs = 5_000,
            tun2SocksTcpReadWriteTimeoutMs = 120_000,
            tun2SocksUdpReadWriteTimeoutMs = 15_000,
            maxDnsUdpAssociations = 40,
            maxUdpAssociations = 64,
            dnsRelayMaxConcurrency = 12,
            preRoutingStabilityDelayMs = 250L,
            postRoutingTrafficRampDelayMs = 0L,
        )

        PerformanceProfile.Throughput -> Tun2SocksRuntimeConfig(
            performanceProfile = profile,
            maxActiveTcpRelays = 80,
            relayAcquireTimeoutMs = 8_000L,
            relayAcquireSliceMs = 150L,
            idleTcpEvictionMs = 30_000L,
            tcpEvictionGraceMs = 8_000L,
            tcpEvictionBatchSize = 8,
            bridgeChunkBytes = 32 * 1024,
            routeTcpBufferBytes = 256 * 1024,
            tcpStreamBufferBytes = 2 * 1024 * 1024,
            udpStreamBufferBytes = 512 * 1024,
            outboundFrameQueueCapacity = 256,
            tun2SocksConnectTimeoutMs = 8_000,
            tun2SocksTcpReadWriteTimeoutMs = 180_000,
            tun2SocksUdpReadWriteTimeoutMs = 30_000,
            maxDnsUdpAssociations = 48,
            maxUdpAssociations = 80,
            dnsRelayMaxConcurrency = 16,
            preRoutingStabilityDelayMs = 350L,
            postRoutingTrafficRampDelayMs = 0L,
        )
    }
}

private fun nativeDirectBridgeTuning(): Tun2SocksRuntimeConfig {
    return Tun2SocksRuntimeConfig(
        performanceProfile = PerformanceProfile.Balanced,
        maxActiveTcpRelays = 96,
        relayAcquireTimeoutMs = 5_000L,
        relayAcquireSliceMs = 100L,
        idleTcpEvictionMs = 18_000L,
        tcpEvictionGraceMs = 6_000L,
        tcpEvictionBatchSize = 8,
        bridgeChunkBytes = 32 * 1024,
        routeTcpBufferBytes = 256 * 1024,
        tcpStreamBufferBytes = 2 * 1024 * 1024,
        udpStreamBufferBytes = 256 * 1024,
        outboundFrameQueueCapacity = 384,
        tun2SocksConnectTimeoutMs = 5_000,
        tun2SocksTcpReadWriteTimeoutMs = 120_000,
        tun2SocksUdpReadWriteTimeoutMs = 10_000,
        maxDnsUdpAssociations = 16,
        maxUdpAssociations = 16,
        dnsRelayMaxConcurrency = 6,
        preRoutingStabilityDelayMs = 100L,
        postRoutingTrafficRampDelayMs = 0L,
    )
}

private fun nativeDirectTcpOpenConcurrency(maxActiveTcpRelays: Int): Int {
    return (maxActiveTcpRelays.coerceAtLeast(1) / 6).coerceIn(12, 24)
}
