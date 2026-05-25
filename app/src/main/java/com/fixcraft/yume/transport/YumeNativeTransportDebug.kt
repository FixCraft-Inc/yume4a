/*
 * YUME - Yume Universal Multiprotocol Engine
 * Copyright (C) 2026  FixCraft Inc.
 * Licensed under the GNU General Public License v3.0.
 */

package com.fixcraft.yume.transport

/**
 * Runtime toggle for the native TLS transport (libyumetransport.so).
 *
 * Off by default for release-channel safety so the SSLSocket path
 * stays in production until the native pipe proves itself in
 * field testing. The Diagnostics screen exposes a switch that
 * flips this — operators can A/B compare without rebuilding.
 *
 * The toggle is consulted at TLS handshake time
 * ([com.fixcraft.yume.transport.YumeBootstrapClient.connect]),
 * NOT mid-session. An existing session that authenticated over
 * SSLSocket will keep using it until reconnect. The diagnostic
 * label `transport=<backend>` in `runPerfReporter` shows which
 * path is actually live so the operator can confirm the toggle
 * took effect on the next connect.
 *
 * The native lib is silently absent for ABIs where it wasn't
 * shipped — bootstrap falls back to SSLSocket with a one-line log
 * line and leaves this flag alone (it's the *user intent*, not
 * the *current state*).
 */
object YumeNativeTransportDebug {
    @Volatile
    var enabled: Boolean = com.fixcraft.yume.BuildConfig.DEBUG
}
