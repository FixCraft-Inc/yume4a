package hev.htproxy

class TProxyService {
    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray
    external fun TProxyLookupMappedHost(ipv4Address: String): String?

    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}
