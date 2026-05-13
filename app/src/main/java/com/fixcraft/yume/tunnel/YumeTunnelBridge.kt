package com.fixcraft.yume.tunnel

import com.fixcraft.yume.transport.YumeNativeTransport

class YumeTunnelBridge {
    external fun startTunnel(configPath: String, fd: Int)
    external fun stopTunnel()
    external fun getStats(): LongArray
    external fun lookupMappedHost(ipv4Address: String): String?
    external fun attachNativeTransport(transport: YumeNativeTransport)
    external fun detachNativeTransport()
    external fun setNativeDirectEnabled(enabled: Boolean)
    external fun hasNativeTransport(): Boolean
    external fun isTunnelRunning(): Boolean

    companion object {
        init {
            System.loadLibrary("yume-route-core")
        }
    }
}
