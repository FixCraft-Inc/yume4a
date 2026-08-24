/*
 * YUME - Yume Universal Multiprotocol Engine
 * Copyright (C) 2026  FixCraft Inc.
 * Licensed under the GNU General Public License v3.0.
 */

package com.fixcraft.yume.transport

/**
 * Runtime toggle for the throughput-diagnostic perf reporter in
 * [YumeBootstrapSession]. The session also consults
 * `System.getProperty("yume.perf")` and `System.getenv("YUME_PERF")`.
 *
 * This only observes the retired Kotlin wire, which no supported Android
 * session uses, so Diagnostics no longer exposes a switch for it. Both go
 * away together in the legacy cleanup pass.
 *
 * When enabled, the session logs a one-line throughput summary
 * every PERF_REPORT_INTERVAL_MS (2 s by default) covering:
 *
 *   tls-out / tls-in     wire-rate Mbps in/out
 *   enc / dec            inner-crypto plaintext-rate Mbps
 *   frames-sent/-recv    frame rate per second
 *   enc-cpu / dec-cpu    cumulative ms/sec spent in encrypt / decrypt
 *   tls-write-cpu        cumulative ms/sec spent blocking on SSL out.write
 *   write-wait           cumulative ms/sec producers spent waiting on the
 *                        outboundFrames channel (i.e. backpressure)
 *
 * Reading the output:
 *   - If tls-out == enc, encryption ≈ no overhead (native fast path).
 *   - If enc-cpu is close to 1000 ms/s, you're CPU-bound in encrypt.
 *   - If write-wait is high, the writer can't drain fast enough
 *     (likely SSL or kernel TCP send buffer).
 *   - If tls-write-cpu is high but write-wait is low, you're
 *     bounded by SSL throughput itself.
 */
object YumePerfDebug {
    // Default ON in debug builds so the operator can troubleshoot
    // throughput without enabling anything extra. Release builds
    // start OFF (zero overhead beyond an atomic counter add per
    // frame, which costs ~5 ns).
    @Volatile
    var enabled: Boolean = com.fixcraft.yume.BuildConfig.DEBUG
}
