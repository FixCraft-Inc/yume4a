package com.fixcraft.yume.agent

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import com.fixcraft.yume.data.KitConfig
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** DNS and every socket use the selected VPN Network; no default-route fallback. */
internal object BenchmarkDownload {
    suspend fun fetch(network: Network, uri: URI, maxBytes: Long, timeoutMs: Long): BenchmarkHttp.Result {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        return withTimeout(timeoutMs) {
            val host = uri.host.removePrefix("[").removeSuffix("]")
            val addresses = if (KitConfig.isAddressLiteral(host)) listOf(InetAddress.getByName(host))
                else resolve(network, host)
            require(addresses.isNotEmpty()) { "Benchmark DNS returned no addresses" }
            BenchmarkSocket.fetch(
                network.socketFactory.createSocket(), addresses.first(), uri, maxBytes,
                deadline, SystemClock::elapsedRealtime,
            )
        }
    }

    private suspend fun resolve(network: Network, host: String): List<InetAddress> {
        require(Build.VERSION.SDK_INT >= 29) { "Android 9 benchmark URLs must use an IP literal; cancellable DNS requires Android 10" }
        return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, Executor(Runnable::run),
                cancellation, object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (!continuation.isActive) return
                        if (rcode == 0) continuation.resume(answer)
                        else continuation.resumeWithException(IllegalStateException("Benchmark DNS failed"))
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Benchmark DNS failed", error))
                    }
                })
        }
    }
}
