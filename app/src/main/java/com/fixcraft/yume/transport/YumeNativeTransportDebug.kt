/*
 * YUME - Yume Universal Multiprotocol Engine
 * Copyright (C) 2026  FixCraft Inc.
 * Licensed under the GNU General Public License v3.0.
 */

package com.fixcraft.yume.transport

/**
 * Disabled compatibility stub for the experimental native TLS pipe.
 *
 * Native-direct route mode still uses the JNI route bridge. This flag used to
 * switch the outer TLS session itself from SSLSocket to libyumetransport.so,
 * which is a separate beta path and currently destabilizes Android sessions.
 */
object YumeNativeTransportDebug {
    var enabled: Boolean
        get() = false
        set(_) = Unit
}
