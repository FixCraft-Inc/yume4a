package com.fixcraft.yume.agent

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BenchmarkSocketTest {
    @Test fun deadlineCancelsAStalledHeaderReadAndClosesSocket() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val peer = thread {
                server.accept().use {
                    accepted.countDown()
                    finish.await(3, TimeUnit.SECONDS)
                }
            }
            val socket = Socket()
            val now = { System.nanoTime() / 1_000_000 }
            val started = now()
            try {
                withTimeout(250) {
                    BenchmarkSocket.fetch(socket, InetAddress.getLoopbackAddress(),
                        URI("http://localhost:${server.localPort}/"), 1024, now() + 10_000, now)
                }
                fail("Stalled read must time out")
            } catch (_: CancellationException) {
                assertTrue(socket.isClosed)
                assertTrue(now() - started < 2_000)
            } finally {
                finish.countDown()
                server.close()
                peer.join(3_000)
            }
            assertTrue(accepted.count == 0L)
            assertTrue(!peer.isAlive)
        }
    }
}
