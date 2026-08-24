package com.fixcraft.yume.vpn

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Resolves carrier endpoints on a specific non-VPN Android network. */
internal object UnderlyingNetworkResolver {
    private val directExecutor = Executor(Runnable::run)

    suspend fun resolve(network: Network, host: String): List<InetAddress> {
        require(host.isNotBlank()) { "YUME server host is empty" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolveWithDnsResolver(network, host)
        } else {
            resolveWithBoundedLegacyWorker(network, host)
        }
    }

    @Suppress("NewApi")
    private suspend fun resolveWithDnsResolver(
        network: Network,
        host: String,
    ): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        DnsResolver.getInstance().query(
            network,
            host,
            DnsResolver.FLAG_EMPTY,
            directExecutor,
            cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (!continuation.isActive) return
                    if (rcode != 0 || answer.isEmpty()) {
                        continuation.resumeWithException(
                            IOException("Underlying-network DNS returned no address for $host (rcode=$rcode)"),
                        )
                    } else {
                        continuation.resume(answer)
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Underlying-network DNS failed for $host: ${error.message}", error),
                        )
                    }
                }
            },
        )
    }

    /**
     * API 28 has no cancellable resolver. Keep the blocking lookup on a daemon
     * worker that owns only immutable inputs; coroutine cancellation returns
     * immediately and a late answer is discarded without touching the service.
     */
    private suspend fun resolveWithBoundedLegacyWorker(
        network: Network,
        host: String,
    ): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        val worker = Thread({
            runCatching { network.getAllByName(host).toList() }
                .onSuccess { answer ->
                    if (!continuation.isActive) return@onSuccess
                    if (answer.isEmpty()) {
                        continuation.resumeWithException(
                            IOException("Underlying-network DNS returned no address for $host"),
                        )
                    } else {
                        continuation.resume(answer)
                    }
                }
                .onFailure { error ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Underlying-network DNS failed for $host: ${error.message}", error),
                        )
                    }
                }
        }, "yume-underlying-dns").apply {
            isDaemon = true
        }
        continuation.invokeOnCancellation { worker.interrupt() }
        worker.start()
    }
}
