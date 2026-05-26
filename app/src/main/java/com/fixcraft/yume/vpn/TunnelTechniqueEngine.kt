package com.fixcraft.yume.vpn

import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.Socks5BindMode
import com.fixcraft.yume.data.Socks5ProxySettings
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
            // Wired to the Diagnostics screen's "Native transport (beta)"
            // toggle (see [com.fixcraft.yume.transport.YumeNativeTransportDebug]).
            // Read here at the moment the bootstrap is built, which is
            // also the moment connect() decides which TLS backend to use.
            useNativeTransport = com.fixcraft.yume.transport.YumeNativeTransportDebug.enabled,
        )
    }
}

data class NativeDirectRuntimeConfig(
    override val performanceProfile: PerformanceProfile,
    val bridgeTuning: Tun2SocksRuntimeConfig,
    val engineStatus: NativeDirectEngineStatus = NativeDirectEngineStatus.CompatibilityBridgeFallback,
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

data class Socks5ProxyRuntimeConfig(
    override val performanceProfile: PerformanceProfile,
    val bridgeTuning: Tun2SocksRuntimeConfig,
    val proxySettings: Socks5ProxySettings,
) : TunnelRuntimeConfig {
    override val technique: ConnectionTechnique = ConnectionTechnique.Socks5ProxyOnly
    override val summary: String = "${technique.canonicalLabel()} :${proxySettings.normalizedPort}"
    override val diagnosticsSummary: String = buildString {
        appendLine("Technique: ${technique.canonicalLabel()}")
        appendLine("Profile: ${performanceProfile.canonicalLabel()}")
        appendLine("Listen port: ${proxySettings.normalizedPort}")
        appendLine("Bind: ${proxySettings.bindMode.canonicalLabel()}")
        appendLine("No system-wide VPN: only apps configured with the SOCKS5 proxy will route through Yume.")
        appendLine()
        appendLine("Applied tunnel tuning")
        append(bridgeTuning.bridgeDiagnosticsSummary(includeTechnique = false))
    }

    fun toBootstrapTuning(): YumeTransportTuning = bridgeTuning.toBootstrapTuning()
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

object Socks5ProxyTechniqueEngine : TunnelTechniqueEngine {
    override val technique: ConnectionTechnique = ConnectionTechnique.Socks5ProxyOnly
    override val isAvailable: Boolean = true

    override fun runtimeConfig(profile: PerformanceProfile): TunnelRuntimeConfig {
        return runtimeConfig(profile, Socks5ProxySettings())
    }

    fun runtimeConfig(profile: PerformanceProfile, proxySettings: Socks5ProxySettings): Socks5ProxyRuntimeConfig {
        val effectiveProfile = if (profile == PerformanceProfile.Latency) PerformanceProfile.Balanced else profile
        val bridgeTuning = socks5BridgeTuning(effectiveProfile)
        return Socks5ProxyRuntimeConfig(
            performanceProfile = effectiveProfile,
            bridgeTuning = bridgeTuning,
            proxySettings = proxySettings,
        )
    }

    override fun createDataPlane(
        runtimeConfig: TunnelRuntimeConfig,
        start: suspend () -> Unit,
        stop: () -> Unit,
        setVerificationMode: (Boolean) -> Unit,
        stats: () -> TechniqueStats?,
    ): TechniqueDataPlane {
        require(runtimeConfig is Socks5ProxyRuntimeConfig)
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

private fun socks5BridgeTuning(profile: PerformanceProfile): Tun2SocksRuntimeConfig {
    val base = stableBridgeTuning(profile)
    return base.copy(
        // Without a TUN, the SOCKS5-only mode has no lwIP layer in the
        // bridge; the relay counts and timeouts inherited from the
        // tun2socks profile are still fine, but the DNS/UDP-association
        // headroom can be trimmed since system DNS works directly here.
        maxDnsUdpAssociations = 16,
        maxUdpAssociations = 32,
    )
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
            // bridgeChunkBytes is the read-size in the SOCKS5 splice
            // loop (YumeSocks5Proxy.bridgeClient). At 16 KiB and high
            // throughput the per-read syscall + JNI overhead becomes
            // a measurable fraction of CPU; 64 KiB cuts that by 4×
            // without meaningfully hurting latency.
            bridgeChunkBytes = 64 * 1024,
            // routeTcpBufferBytes sizes the local TCP socket buffer
            // between the SOCKS5 splice and the lwIP layer. 1 MiB
            // matches lwIP's new TCP_WND so we don't bottleneck the
            // handover.
            routeTcpBufferBytes = 1024 * 1024,
            // tcpStreamBufferBytes is the cap on the per-stream
            // BufferedChunkQueue used to absorb bursts between the
            // Yume tunnel and the SOCKS5 splice. 4 MiB gives ~500 ms
            // of slack at 64 Mbps per stream — comfortably above the
            // RTT of mobile networks. Previously 1 MiB triggered the
            // "incoming Yume stream overflow" stream-kill path on
            // every speedtest download (see YumeBootstrapClient
            // runReadLoop, which no longer kills the stream but
            // silently drops; this larger buffer keeps that drop
            // rare).
            tcpStreamBufferBytes = 4 * 1024 * 1024,
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
            bridgeChunkBytes = 64 * 1024,
            routeTcpBufferBytes = 1024 * 1024,
            tcpStreamBufferBytes = 4 * 1024 * 1024,
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
        // Current native-direct is still the route-core/lwIP compatibility
        // bridge. Keep fan-out below Dispatchers.IO + route-core thread storm
        // territory; packet-bulk will lift this by removing per-flow relays.
        maxActiveTcpRelays = 20,
        relayAcquireTimeoutMs = 750L,
        relayAcquireSliceMs = 50L,
        idleTcpEvictionMs = 18_000L,
        tcpEvictionGraceMs = 6_000L,
        tcpEvictionBatchSize = 8,
        bridgeChunkBytes = 32 * 1024,
        // routeTcpBufferBytes sizes the SOCKS5 splice's local TCP
        // buffer toward the lwIP layer. 1 MiB matches the lwIP
        // TCP_WND (128×MSS) we configured in lwipopts.h, so we don't
        // bottleneck the handover between Yume stream and lwIP.
        // 256 KiB used to mean lwIP could only stage 256 KiB of
        // inflight bytes before it had to wait for the previous
        // chunk to drain — a 4× cap on the per-stream rate when
        // many streams shared the lwIP processing thread.
        routeTcpBufferBytes = 1024 * 1024,
        // tcpStreamBufferBytes is the per-stream BufferedChunkQueue
        // cap on the Kotlin side. The route-core / lwIP / TUN
        // consumer chain is the slow link in native-direct mode. Give
        // each active flow more burst slack, but pair it with a lower
        // active-flow cap so the Android heap cannot fill with dozens
        // of 64 KiB chunks during a speedtest/ad-network fan-out.
        tcpStreamBufferBytes = 6 * 1024 * 1024,
        udpStreamBufferBytes = 256 * 1024,
        outboundFrameQueueCapacity = 256,
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
    return (maxActiveTcpRelays.coerceAtLeast(1) / 4).coerceIn(4, 8)
}
