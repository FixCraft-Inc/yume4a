package com.fixcraft.yume.vpn

import android.os.SystemClock
import com.fixcraft.yume.transport.YumeBootstrapException
import com.fixcraft.yume.transport.YumeBootstrapSession
import com.fixcraft.yume.transport.YumeTcpStream
import com.fixcraft.yume.transport.YumeUdpStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class YumeSocks5Proxy(
    private val scope: CoroutineScope,
    private val runtimeConfig: Tun2SocksRuntimeConfig,
    private val sessionProvider: () -> YumeBootstrapSession?,
    private val dnsServersProvider: () -> List<String> = { emptyList() },
    private val ipv4OnlyMode: Boolean = false,
    private val forceTcpFallbackUdp443Provider: () -> Boolean = { true },
    private val protectedDestinationsProvider: () -> ProtectedDestinations = { ProtectedDestinations() },
    private val protectedAddressResolver: (String) -> Set<String> = { emptySet() },
    private val warmupTcpDestinationAllowedProvider: (String, Int) -> Boolean = { _, _ -> false },
    private val mappedHostResolver: (String) -> String? = { null },
    private val log: (VpnLogLevel, String) -> Unit,
) {
    private val clientJobs = linkedSetOf<Job>()
    private val clientJobsLock = Any()
    private val trackedCloseables = linkedSetOf<Closeable>()
    private val trackedCloseablesLock = Any()
    private val sharedUdpRelayLock = Any()
    private val tcpRelaysLock = Any()
    private val policyLock = Any()
    private val dnsRelayLimiter = Semaphore(runtimeConfig.dnsRelayMaxConcurrency)
    private val tcpRelayLimiter = Semaphore(runtimeConfig.maxActiveTcpRelays)
    private val warmupBlockedWarnings = linkedSetOf<String>()
    private val dnsHealthLock = Any()
    private val activeTcpRelays = linkedMapOf<Int, ActiveTcpRelay>()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var sharedUdpRelay: SharedUdpRelay? = null
    private var activeUdpAssociations = 0
    private var dnsFailureWindowStartedAtMs = 0L
    private var dnsFailureCount = 0
    private var dnsFailureSession: YumeBootstrapSession? = null
    @Volatile
    private var startupWarmupMode = false

    val listenAddress: InetSocketAddress?
        get() = serverSocket?.localSocketAddress as? InetSocketAddress

    fun setStartupWarmupMode(enabled: Boolean) {
        startupWarmupMode = enabled
        synchronized(policyLock) {
            if (!enabled) {
                warmupBlockedWarnings.clear()
            }
        }
    }

    suspend fun start(preferredPort: Int = DEFAULT_PROXY_PORT): InetSocketAddress {
        listenAddress?.let { return it }
        val boundSocket = bindLoopback(preferredPort)
        serverSocket = boundSocket
        acceptJob = scope.launch(Dispatchers.IO) {
            acceptLoop(boundSocket)
        }
        return boundSocket.localSocketAddress as InetSocketAddress
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(sharedUdpRelayLock) {
            sharedUdpRelay = null
            activeUdpAssociations = 0
        }
        synchronized(dnsHealthLock) {
            dnsFailureWindowStartedAtMs = 0L
            dnsFailureCount = 0
            dnsFailureSession = null
        }
        synchronized(tcpRelaysLock) {
            activeTcpRelays.clear()
        }
        closeTrackedCloseables()
        val jobs = synchronized(clientJobsLock) {
            clientJobs.toList().also { clientJobs.clear() }
        }
        jobs.forEach { it.cancel() }
    }

    private suspend fun acceptLoop(listener: ServerSocket) {
        while (true) {
            val client = try {
                listener.accept()
            } catch (throwable: IOException) {
                if (listener.isClosed) {
                    return
                }
                throw throwable
            }
            client.keepAlive = true
            client.tcpNoDelay = true
            registerCloseable(client)
            val job = scope.launch(Dispatchers.IO) {
                try {
                    handleClient(client)
                } catch (throwable: Throwable) {
                    if (!isExpectedSocketShutdown(throwable)) {
                        log(
                            VpnLogLevel.Warn,
                            "SOCKS5 client handler failed: ${throwable.message ?: throwable.javaClass.simpleName}.",
                        )
                    }
                    runCatching { client.close() }
                }
            }
            synchronized(clientJobsLock) {
                clientJobs += job
            }
            job.invokeOnCompletion {
                synchronized(clientJobsLock) {
                    clientJobs -= job
                }
                unregisterCloseable(client)
                runCatching { client.close() }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        var stream: YumeTcpStream? = null
        var tcpRelayPermitAcquired = false
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            client.soTimeout = HANDSHAKE_TIMEOUT_MS

            if (!negotiateNoAuth(input, output)) {
                log(VpnLogLevel.Warn, "SOCKS5 client does not support the no-auth method.")
                return
            }

            val request = try {
                readClientRequest(input)
            } catch (throwable: SocksRequestException) {
                sendSocksReply(output, throwable.replyCode)
                log(VpnLogLevel.Warn, "SOCKS5 request rejected: ${throwable.message}.")
                return
            }

            when (request.command) {
                COMMAND_CONNECT -> {
                    val effectiveRequest = request.withResolvedHost(resolveMappedHost(request.host))
                    if (ipv4OnlyMode && isIpv6LiteralHost(effectiveRequest.host)) {
                        sendSocksReply(output, REPLY_ADDRESS_TYPE_NOT_SUPPORTED)
                        log(
                            VpnLogLevel.Warn,
                            "SOCKS5 CONNECT ${effectiveRequest.host}:${effectiveRequest.port} blocked on Android because Yume is operating in IPv6 fail-closed mode.",
                        )
                        return
                    }
                    if (ipv4OnlyMode && isEncryptedDnsBypass(effectiveRequest.host, effectiveRequest.port)) {
                        sendSocksReply(output, REPLY_CONNECTION_NOT_ALLOWED)
                        log(
                            VpnLogLevel.Warn,
                            "SOCKS5 CONNECT ${effectiveRequest.host}:${effectiveRequest.port} blocked because app-managed encrypted DNS would bypass Yume's IPv4-only DNS relay policy.",
                        )
                        return
                    }
                    val protectedMatches = protectedDestinationMatches(effectiveRequest.host, effectiveRequest.port)
                    if (protectedMatches.isNotEmpty()) {
                        sendSocksReply(output, REPLY_CONNECTION_NOT_ALLOWED)
                        log(
                            VpnLogLevel.Warn,
                            "SOCKS5 CONNECT ${effectiveRequest.host}:${effectiveRequest.port} blocked to avoid routing the active Yume server endpoint back through the Yume tunnel (matched protected IPs: ${protectedMatches.joinToString(", ")}).",
                        )
                        return
                    }
                    if (startupWarmupMode && !isWarmupTcpAllowed(effectiveRequest.host, effectiveRequest.port)) {
                        sendSocksReply(output, REPLY_CONNECTION_NOT_ALLOWED)
                        val destination = "${effectiveRequest.host}:${effectiveRequest.port}"
                        val shouldLog = synchronized(policyLock) {
                            warmupBlockedWarnings.add(destination)
                        }
                        if (shouldLog) {
                            log(
                                VpnLogLevel.Warn,
                                "SOCKS5 CONNECT $destination blocked during Android tunnel warm-up. Yume is only allowing verification traffic until routed protection is confirmed.",
                            )
                        }
                        return
                    }
                    val relaySlotAcquired = acquireTcpRelaySlot(effectiveRequest)
                    if (!relaySlotAcquired) {
                        sendSocksReply(output, REPLY_GENERAL_FAILURE)
                        log(
                            VpnLogLevel.Warn,
                            "SOCKS5 CONNECT ${effectiveRequest.host}:${effectiveRequest.port} deferred because Yume is smoothing Android TCP fan-out to keep the authenticated session stable.",
                        )
                        return
                    }
                    tcpRelayPermitAcquired = true

                    val session = sessionProvider()
                    if (session == null || !session.isAlive) {
                        sendSocksReply(output, REPLY_GENERAL_FAILURE)
                        log(
                            VpnLogLevel.Warn,
                            "SOCKS5 CONNECT ${effectiveRequest.host}:${effectiveRequest.port} failed because the Yume session is unavailable.",
                        )
                        return
                    }

                    stream = try {
                        session.openTcpStream(effectiveRequest.host, effectiveRequest.port)
                    } catch (throwable: Exception) {
                        sendSocksReply(output, mapOpenFailureToReply(throwable))
                        log(
                            VpnLogLevel.Warn,
                            "SOCKS5 CONNECT ${effectiveRequest.host}:${effectiveRequest.port} failed: ${throwable.message ?: "open failed"}.",
                        )
                        return
                    }

                    registerActiveTcpRelay(client, stream, effectiveRequest)
                    sendSocksReply(output, REPLY_SUCCESS)
                    client.soTimeout = 0
                    log(VpnLogLevel.Info, "SOCKS5 CONNECT ${effectiveRequest.host}:${effectiveRequest.port} via Yume stream ${stream.streamId}.")
                    bridgeClient(client, input, output, stream, effectiveRequest)
                }

                COMMAND_UDP_ASSOCIATE -> {
                    handleUdpAssociate(client, input, output)
                }

                else -> {
                    sendSocksReply(output, REPLY_COMMAND_NOT_SUPPORTED)
                    log(VpnLogLevel.Warn, "SOCKS5 command ${request.command} is not supported.")
                }
            }
        } finally {
            stream?.let { unregisterActiveTcpRelay(it.streamId) }
            if (stream != null && !stream.isClosed) {
                try {
                    stream.close("SOCKS5 client disconnected")
                } catch (_: Exception) {
                }
            }
            if (tcpRelayPermitAcquired) {
                tcpRelayLimiter.release()
            }
            runCatching { client.close() }
        }
    }

    private suspend fun acquireTcpRelaySlot(request: SocksRequest): Boolean {
        val deadline = SystemClock.elapsedRealtime() + runtimeConfig.relayAcquireTimeoutMs
        while (true) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) {
                return false
            }
            val acquired = withTimeoutOrNull(minOf(remainingMs, runtimeConfig.relayAcquireSliceMs)) {
                tcpRelayLimiter.acquire()
                true
            } ?: false
            if (acquired) {
                return true
            }
            evictStaleTcpRelaysForPressure(request)
        }
    }

    private suspend fun evictStaleTcpRelaysForPressure(request: SocksRequest) {
        val nowMs = SystemClock.elapsedRealtime()
        val evicted = synchronized(tcpRelaysLock) {
            selectTcpRelaysToEvictLocked(nowMs, request).also { relays ->
                relays.forEach { relay ->
                    activeTcpRelays.remove(relay.streamId)
                }
            }
        }
        evicted.forEach { relay ->
            log(
                VpnLogLevel.Warn,
                "Evicting stale SOCKS5 CONNECT ${relay.host}:${relay.port} to preserve Yume stream capacity for active Android traffic.",
            )
            runCatching { relay.client.close() }
            runCatching { relay.stream.close("evicted for Android relay pressure") }
        }
    }

    private fun selectTcpRelaysToEvictLocked(
        nowMs: Long,
        request: SocksRequest,
    ): List<ActiveTcpRelay> {
        val candidates = activeTcpRelays.values
            .filter { relay ->
                (nowMs - relay.createdAtMs) >= runtimeConfig.tcpEvictionGraceMs &&
                    (nowMs - relay.lastActivityAtMs) >= runtimeConfig.idleTcpEvictionMs
            }
            .sortedWith(
                compareBy<ActiveTcpRelay> { it.lastActivityAtMs }
                    .thenByDescending { if (it.port == request.port) 1 else 0 }
                    .thenByDescending { if (it.port == 443) 1 else 0 },
            )
        if (candidates.isEmpty()) {
            return emptyList()
        }
        return candidates.take(runtimeConfig.tcpEvictionBatchSize)
    }

    private fun registerActiveTcpRelay(
        client: Socket,
        stream: YumeTcpStream,
        request: SocksRequest,
    ) {
        val relay = ActiveTcpRelay(
            streamId = stream.streamId,
            host = request.host,
            port = request.port,
            client = client,
            stream = stream,
        )
        synchronized(tcpRelaysLock) {
            activeTcpRelays[stream.streamId] = relay
        }
    }

    private fun unregisterActiveTcpRelay(streamId: Int) {
        synchronized(tcpRelaysLock) {
            activeTcpRelays.remove(streamId)
        }
    }

    private fun touchActiveTcpRelay(streamId: Int) {
        synchronized(tcpRelaysLock) {
            activeTcpRelays[streamId]?.touch()
        }
    }

    private fun registerCloseable(closeable: Closeable) {
        synchronized(trackedCloseablesLock) {
            trackedCloseables += closeable
        }
    }

    private fun unregisterCloseable(closeable: Closeable) {
        synchronized(trackedCloseablesLock) {
            trackedCloseables -= closeable
        }
    }

    private fun closeTrackedCloseables() {
        val closeables = synchronized(trackedCloseablesLock) {
            trackedCloseables.toList().also { trackedCloseables.clear() }
        }
        closeables.forEach { closeable ->
            runCatching { closeable.close() }
        }
    }

    private suspend fun handleUdpAssociate(
        client: Socket,
        input: InputStream,
        output: OutputStream,
    ) = coroutineScope {
        if (startupWarmupMode) {
            sendSocksReply(output, REPLY_CONNECTION_NOT_ALLOWED)
            log(
                VpnLogLevel.Warn,
                "SOCKS5 UDP ASSOCIATE blocked during Android tunnel warm-up. Yume waits for routed protection before exposing UDP relay.",
            )
            return@coroutineScope
        }
        val activeSession = sessionProvider()
        if (activeSession == null || !activeSession.isAlive) {
            sendSocksReply(output, REPLY_GENERAL_FAILURE)
            log(VpnLogLevel.Warn, "SOCKS5 UDP ASSOCIATE failed because the authenticated Yume session is unavailable.")
            return@coroutineScope
        }
        val relay = ensureSharedUdpRelay()

        try {
            incrementActiveUdpAssociations()
            sendSocksReply(
                output = output,
                replyCode = REPLY_SUCCESS,
                address = relay.socket.localAddress,
                port = relay.socket.localPort,
            )
            client.soTimeout = 0
            try {
                while (input.read() >= 0) {
                }
            } catch (throwable: IOException) {
                if (!isExpectedSocketShutdown(throwable)) {
                    log(
                        VpnLogLevel.Warn,
                        "SOCKS5 UDP control channel failed: ${throwable.message ?: "socket error"}.",
                    )
                }
            }
        } finally {
            decrementActiveUdpAssociations()
        }
    }

    private suspend fun relayDnsDatagramOverTcp(
        hosts: List<String>,
        payload: ByteArray,
        udpSocket: DatagramSocket,
        clientAddress: InetAddress,
        clientPort: Int,
    ): Boolean {
        if (hosts.isEmpty()) {
            return false
        }
        return dnsRelayLimiter.withPermit {
            val session = sessionProvider()
            if (session == null || !session.isAlive) {
                false
            } else {
                for (host in hosts) {
                    var stream: YumeTcpStream? = null
                    try {
                        stream = session.openTcpStream(host, DNS_PORT)
                        val tcpRequest = ByteArray(2 + payload.size)
                        tcpRequest[0] = ((payload.size ushr 8) and 0xFF).toByte()
                        tcpRequest[1] = (payload.size and 0xFF).toByte()
                        payload.copyInto(tcpRequest, 2)
                        stream.write(tcpRequest)

                        val dnsResponse = withTimeout(DNS_OVER_TCP_TIMEOUT_MS.toLong()) {
                            readDnsResponseFromTcp(stream)
                        }
                        if (clientPort > 0) {
                            val filteredDnsResponse = if (ipv4OnlyMode) {
                                filterDnsResponseForIpv4Only(dnsResponse)
                            } else {
                                dnsResponse
                            }
                            val udpResponse = buildUdpResponse(host, DNS_PORT, filteredDnsResponse)
                            udpSocket.send(DatagramPacket(udpResponse, udpResponse.size, clientAddress, clientPort))
                        }
                        resetDnsTransportFailures(session)
                        return@withPermit true
                    } catch (_: CancellationException) {
                        return@withPermit false
                    } catch (_: Exception) {
                    } finally {
                        if (stream != null && !stream.isClosed) {
                            runCatching { stream.close("dns over tcp relay complete") }
                        }
                    }
                }
                false
            }
        }
    }

    private suspend fun readDnsResponseFromTcp(stream: YumeTcpStream): ByteArray {
        val response = ByteArrayOutputStream()
        var expectedLength = -1
        val timeoutMs = 5000L
        var dnsResponse: ByteArray? = null
        try {
            withTimeout(timeoutMs) {
                while (dnsResponse == null) {
                    val chunk = stream.readChunk() ?: break
                    response.write(chunk)
                    val bytes = response.toByteArray()
                    if (expectedLength < 0 && bytes.size >= 2) {
                        expectedLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                        if (expectedLength <= 0 || expectedLength > MAX_DNS_RESPONSE_BYTES) {
                            throw IOException("invalid DNS-over-TCP response length")
                        }
                    }
                    if (expectedLength >= 0 && bytes.size >= expectedLength + 2) {
                        dnsResponse = bytes.copyOfRange(2, 2 + expectedLength)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("DNS-over-TCP read timeout after ${timeoutMs}ms")
        }
        
        return dnsResponse ?: throw EOFException("DNS-over-TCP response ended unexpectedly")
    }

    private suspend fun obtainUdpAssociation(
        relay: SharedUdpRelay,
        session: YumeBootstrapSession,
        host: String,
        port: Int,
        udpSocket: DatagramSocket,
        clientAddress: InetAddress,
        clientPort: Int,
    ): UdpAssociation {
        val key = udpAssociationKey(clientAddress, clientPort, host, port)
        while (true) {
            var staleAssociation: UdpAssociation? = null
            var associationsToEvict: List<UdpAssociation> = emptyList()
            var pendingOpen: CompletableDeferred<UdpAssociation>? = null
            var shouldOpen = false
            synchronized(relay.associationsLock) {
                relay.associations[key]?.let { existing ->
                    if (existing.ownerSession === session && !existing.stream.isClosed) {
                        existing.touch()
                        return existing
                    }
                    relay.associations.remove(key)
                    staleAssociation = existing
                }
                pendingOpen = relay.pendingAssociationOpens[key]
                if (pendingOpen == null) {
                    pendingOpen = CompletableDeferred()
                    relay.pendingAssociationOpens[key] = pendingOpen as CompletableDeferred<UdpAssociation>
                    shouldOpen = true
                    associationsToEvict = selectAssociationsToEvictLocked(relay, port)
                }
            }

            staleAssociation?.let { closeUdpAssociation(it, "replacing stale UDP association") }
            associationsToEvict.forEach { association ->
                log(
                    VpnLogLevel.Warn,
                    "Evicting stale SOCKS5 UDP association ${association.host}:${association.port} to preserve Yume stream capacity.",
                )
                closeUdpAssociation(association, "association capacity exceeded")
            }
            if (!shouldOpen) {
                val existing = pendingOpen!!.await()
                if (existing.ownerSession === session && !existing.stream.isClosed) {
                    existing.touch()
                    return existing
                }
                continue
            }

            try {
                val stream = session.openUdpStream(host, port)
                lateinit var association: UdpAssociation
                association = UdpAssociation(
                    key = key,
                    host = host,
                    port = port,
                    ownerSession = session,
                    clientAddress = clientAddress,
                    clientPort = clientPort,
                    stream = stream,
                    pumpJob = scope.launch(Dispatchers.IO) {
                        try {
                            while (true) {
                                val payload = stream.readDatagram() ?: break
                                association.touch()
                                val relayPayload = if (ipv4OnlyMode && port == DNS_PORT) {
                                    filterDnsResponseForIpv4Only(payload)
                                } else {
                                    payload
                                }
                                if (port == DNS_PORT) {
                                    relayDnsResponse(
                                        relay = relay,
                                        resolverHost = host,
                                        payload = relayPayload,
                                        udpSocket = udpSocket,
                                    )
                                    continue
                                }
                                if (clientPort <= 0) {
                                    continue
                                }
                                val response = buildUdpResponse(host, port, relayPayload)
                                udpSocket.send(DatagramPacket(response, response.size, clientAddress, clientPort))
                            }
                        } catch (_: CancellationException) {
                        } catch (throwable: IOException) {
                            if (!isExpectedSocketShutdown(throwable)) {
                                log(
                                    VpnLogLevel.Warn,
                                    "SOCKS5 UDP ${host}:${port} receive loop failed: ${throwable.message ?: "socket error"}.",
                                )
                            }
                        } catch (throwable: Exception) {
                            log(
                                VpnLogLevel.Warn,
                                "SOCKS5 UDP ${host}:${port} stream failed: ${throwable.message ?: throwable.javaClass.simpleName}.",
                            )
                        } finally {
                            synchronized(relay.associationsLock) {
                                if (relay.associations[key] === association) {
                                    relay.associations.remove(key)
                                }
                            }
                        }
                    },
                    idleTimeoutMs = associationIdleTimeoutMs(port),
                )
                synchronized(relay.associationsLock) {
                    relay.associations[key] = association
                    relay.pendingAssociationOpens.remove(key)
                }
                pendingOpen!!.complete(association)
                log(VpnLogLevel.Info, "SOCKS5 UDP ${host}:${port} via Yume stream ${stream.streamId}.")
                return association
            } catch (throwable: Throwable) {
                synchronized(relay.associationsLock) {
                    relay.pendingAssociationOpens.remove(key)
                }
                pendingOpen!!.completeExceptionally(throwable)
                throw throwable
            }
        }
    }

    private suspend fun bridgeClient(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        stream: YumeTcpStream,
        request: SocksRequest,
    ) = coroutineScope {
        val completion = CompletableDeferred<Unit>()
        val localCompleted = AtomicBoolean(false)
        val remoteCompleted = AtomicBoolean(false)
        val clientToYume = launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(runtimeConfig.bridgeChunkBytes)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read > 0) {
                        stream.write(buffer, 0, read)
                        touchActiveTcpRelay(stream.streamId)
                    }
                }
            } catch (throwable: IOException) {
                if (!isExpectedSocketShutdown(throwable)) {
                    log(
                        VpnLogLevel.Warn,
                        "SOCKS5 CONNECT ${request.host}:${request.port} local read failed: ${throwable.message ?: "socket error"}.",
                    )
                }
            } catch (_: CancellationException) {
            } catch (throwable: Exception) {
                log(
                    VpnLogLevel.Warn,
                    "SOCKS5 CONNECT ${request.host}:${request.port} upload failed: ${throwable.message ?: throwable.javaClass.simpleName}.",
                )
            } finally {
                localCompleted.set(true)
                completion.complete(Unit)
            }
        }
        val yumeToClient = launch(Dispatchers.IO) {
            try {
                while (true) {
                    val chunk = stream.readChunk() ?: break
                    output.write(chunk)
                    touchActiveTcpRelay(stream.streamId)
                }
            } catch (throwable: IOException) {
                if (!isExpectedSocketShutdown(throwable)) {
                    log(
                        VpnLogLevel.Warn,
                        "SOCKS5 CONNECT ${request.host}:${request.port} local write failed: ${throwable.message ?: "socket error"}.",
                    )
                }
            } catch (_: CancellationException) {
            } catch (throwable: Exception) {
                log(
                    VpnLogLevel.Warn,
                    "SOCKS5 CONNECT ${request.host}:${request.port} download failed: ${throwable.message ?: throwable.javaClass.simpleName}.",
                )
            } finally {
                remoteCompleted.set(true)
                completion.complete(Unit)
            }
        }

        completion.await()
        when {
            localCompleted.get() && !remoteCompleted.get() -> {
                withTimeoutOrNull(BRIDGE_DRAIN_TIMEOUT_MS) {
                    yumeToClient.join()
                }
                if (!yumeToClient.isCompleted && !stream.isClosed) {
                    runCatching { stream.close("SOCKS5 client upload finished") }
                }
            }

            remoteCompleted.get() && !localCompleted.get() -> {
                runCatching { client.shutdownOutput() }
                withTimeoutOrNull(BRIDGE_DRAIN_TIMEOUT_MS) {
                    clientToYume.join()
                }
            }
        }

        if (!clientToYume.isCompleted) {
            clientToYume.cancel()
        }
        if (!yumeToClient.isCompleted) {
            yumeToClient.cancel()
        }
        log(VpnLogLevel.Info, "SOCKS5 CONNECT ${request.host}:${request.port} finished.")
        runCatching { client.close() }
    }

    private fun ensureSharedUdpRelay(): SharedUdpRelay {
        synchronized(sharedUdpRelayLock) {
            sharedUdpRelay?.let { return it }
            val relay = SharedUdpRelay(
                socket = DatagramSocket(0, InetAddress.getByName(LOOPBACK_HOST)).apply {
                    reuseAddress = true
                },
            )
            relay.receiveJob = scope.launch(Dispatchers.IO) {
                runSharedUdpRelay(relay)
            }
            relay.cleanupJob = scope.launch(Dispatchers.IO) {
                cleanupUdpAssociations(relay)
            }
            registerCloseable(relay)
            sharedUdpRelay = relay
            log(VpnLogLevel.Info, "SOCKS5 UDP relay ready on ${relay.socket.localAddress.hostAddress}:${relay.socket.localPort}.")
            return relay
        }
    }

    private suspend fun runSharedUdpRelay(relay: SharedUdpRelay) {
        val buffer = ByteArray(MAX_UDP_PACKET_BYTES)
        try {
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                relay.socket.receive(packet)
                if (packet.port <= 0 || !hasActiveUdpAssociations()) {
                    continue
                }
                val datagram = parseUdpDatagram(packet.data, packet.length) ?: continue
                if (startupWarmupMode) {
                    val destination = "${datagram.host}:${datagram.port}"
                    val shouldLog = synchronized(policyLock) {
                        warmupBlockedWarnings.add("udp:$destination")
                    }
                    if (shouldLog) {
                        log(
                            VpnLogLevel.Warn,
                            if (datagram.port == DNS_PORT) {
                                "SOCKS5 DNS $destination blocked during Android tunnel warm-up. Yume is only allowing verification traffic until routed protection is confirmed."
                            } else {
                                "SOCKS5 UDP $destination blocked during Android tunnel warm-up. Yume waits for routed protection before exposing UDP relay."
                            },
                        )
                    }
                    continue
                }
                scope.launch(Dispatchers.IO) {
                    relayUdpDatagram(
                        relay = relay,
                        datagram = datagram,
                        clientAddress = packet.address,
                        clientPort = packet.port,
                    )
                }
            }
        } catch (throwable: IOException) {
            if (!isExpectedSocketShutdown(throwable)) {
                log(
                    VpnLogLevel.Warn,
                    "SOCKS5 shared UDP relay failed: ${throwable.message ?: "socket error"}.",
                )
            }
        }
    }

    private suspend fun cleanupUdpAssociations(relay: SharedUdpRelay) {
        while (true) {
            delay(UDP_ASSOCIATION_CLEANUP_INTERVAL_MS)
            val expired = mutableListOf<UdpAssociation>()
            val now = System.currentTimeMillis()
            synchronized(relay.associationsLock) {
                val iterator = relay.associations.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val association = entry.value
                    if (association.stream.isClosed || now - association.lastActivityAtMs >= association.idleTimeoutMs) {
                        iterator.remove()
                        expired += association
                    }
                }
                val routeIterator = relay.pendingDnsRoutes.entries.iterator()
                while (routeIterator.hasNext()) {
                    val queue = routeIterator.next().value
                    while (queue.isNotEmpty() && now - queue.first().createdAtMs >= DNS_PENDING_ROUTE_TIMEOUT_MS) {
                        queue.removeFirst()
                    }
                    if (queue.isEmpty()) {
                        routeIterator.remove()
                    }
                }
            }
            expired.forEach { association ->
                closeUdpAssociation(association, "idle timeout")
            }
        }
    }

    private suspend fun relayUdpDatagram(
        relay: SharedUdpRelay,
        datagram: SocksUdpDatagram,
        clientAddress: InetAddress,
        clientPort: Int,
    ) {
        val effectiveDatagram = datagram.withResolvedHost(resolveMappedHost(datagram.host))
        if (clientPort <= 0) {
            return
        }
        if (ipv4OnlyMode && isIpv6LiteralHost(effectiveDatagram.host)) {
            val destination = "${effectiveDatagram.host}:${effectiveDatagram.port}"
            val shouldLog = synchronized(relay.policyWarnings) {
                relay.policyWarnings.add("ipv6:$destination")
            }
            if (shouldLog) {
                log(
                    VpnLogLevel.Warn,
                    "SOCKS5 UDP $destination blocked on Android because Yume is operating in IPv6 fail-closed mode.",
                )
            }
            return
        }
        if (ipv4OnlyMode && isEncryptedDnsBypass(effectiveDatagram.host, effectiveDatagram.port)) {
            val destination = "${effectiveDatagram.host}:${effectiveDatagram.port}"
            val shouldLog = synchronized(relay.policyWarnings) {
                relay.policyWarnings.add("dns-bypass:$destination")
            }
            if (shouldLog) {
                log(
                    VpnLogLevel.Warn,
                    "SOCKS5 UDP $destination blocked because app-managed encrypted DNS would bypass Yume's IPv4-only DNS relay policy.",
                )
            }
            return
        }
        if (shouldForceTcpFallback(effectiveDatagram.port)) {
            val destination = "${effectiveDatagram.host}:${effectiveDatagram.port}"
            val shouldLog = synchronized(relay.policyWarnings) {
                relay.policyWarnings.add("force-tcp:$destination")
            }
            if (shouldLog) {
                log(
                    VpnLogLevel.Warn,
                    "SOCKS5 UDP $destination blocked on Android to force TCP fallback over the current Yume TLS/TCP carrier. Native UDP relay resumes when Yume has a packet-native transport.",
                )
            }
            return
        }
        val protectedMatches = protectedDestinationMatches(effectiveDatagram.host, effectiveDatagram.port)
        if (protectedMatches.isNotEmpty()) {
            val destination = "${effectiveDatagram.host}:${effectiveDatagram.port}"
            val shouldLog = synchronized(relay.policyWarnings) {
                relay.policyWarnings.add("protected:$destination")
            }
            if (shouldLog) {
                log(
                    VpnLogLevel.Warn,
                    "SOCKS5 UDP $destination blocked to avoid routing the active Yume server endpoint back through the Yume tunnel (matched protected IPs: ${protectedMatches.joinToString(", ")}).",
                )
            }
            return
        }
        val session = sessionProvider()
        if (session == null || !session.isAlive) {
            if (effectiveDatagram.port == DNS_PORT) {
                noteDnsTransportFailure(session, effectiveDatagram.host, "The authenticated Yume session is unavailable")
            }
            log(VpnLogLevel.Warn, "SOCKS5 UDP ${effectiveDatagram.host}:${effectiveDatagram.port} failed: The authenticated Yume session is unavailable.")
            return
        }

        if (effectiveDatagram.port == DNS_PORT) {
            relayDnsDatagram(
                relay = relay,
                session = session,
                requestedHost = effectiveDatagram.host,
                payload = effectiveDatagram.payload,
                clientAddress = clientAddress,
                clientPort = clientPort,
            )
            return
        }

        try {
            val association = obtainUdpAssociation(
                relay = relay,
                session = session,
                host = effectiveDatagram.host,
                port = effectiveDatagram.port,
                udpSocket = relay.socket,
                clientAddress = clientAddress,
                clientPort = clientPort,
            )
            association.touch()
            association.stream.sendDatagram(effectiveDatagram.payload)
            association.touch()
        } catch (_: CancellationException) {
        } catch (throwable: Exception) {
            if (!isExpectedSocketShutdown(throwable)) {
                log(
                    VpnLogLevel.Warn,
                    "SOCKS5 UDP ${effectiveDatagram.host}:${effectiveDatagram.port} failed: ${throwable.message ?: "udp relay failed"}.",
                )
            }
        }
    }

    private suspend fun relayDnsDatagram(
        relay: SharedUdpRelay,
        session: YumeBootstrapSession,
        requestedHost: String,
        payload: ByteArray,
        clientAddress: InetAddress,
        clientPort: Int,
    ) {
        val resolverHosts = buildDnsRelayCandidates(requestedHost)
        if (resolverHosts.isEmpty()) {
            noteDnsTransportFailure(session, requestedHost, "no DNS resolvers are configured")
            return
        }

        var lastFailure: Throwable? = null
        for (host in resolverHosts) {
            val pendingRoute = registerDnsPendingRoute(
                relay = relay,
                resolverHost = host,
                payload = payload,
                clientAddress = clientAddress,
                clientPort = clientPort,
            )
            try {
                val association = obtainUdpAssociation(
                    relay = relay,
                    session = session,
                    host = host,
                    port = DNS_PORT,
                    udpSocket = relay.socket,
                    clientAddress = clientAddress,
                    clientPort = clientPort,
                )
                association.touch()
                association.stream.sendDatagram(payload)
                association.touch()
                resetDnsTransportFailures(session)
                return
            } catch (_: CancellationException) {
                removeDnsPendingRoute(relay, pendingRoute)
                return
            } catch (throwable: Exception) {
                removeDnsPendingRoute(relay, pendingRoute)
                lastFailure = throwable
            }
        }

        if (relayDnsDatagramOverTcp(
                hosts = resolverHosts,
                payload = payload,
                udpSocket = relay.socket,
                clientAddress = clientAddress,
                clientPort = clientPort,
            )
        ) {
            return
        }

        val detail = lastFailure?.message ?: "all Yume DNS transports failed"
        noteDnsTransportFailure(session, resolverHosts.first(), detail)
        if (!isExpectedSocketShutdown(lastFailure ?: Exception(detail))) {
            log(
                VpnLogLevel.Warn,
                "SOCKS5 UDP ${resolverHosts.joinToString(", ")}:$DNS_PORT failed across Yume UDP and TCP fallback: $detail.",
            )
        }
    }

    private fun buildDnsRelayCandidates(requestedHost: String): List<String> {
        return buildList {
            val normalizedRequested = normalizeHost(requestedHost)
            if (normalizedRequested.isNotEmpty()) {
                add(normalizedRequested)
            }
            dnsServersProvider()
                .map(::normalizeHost)
                .filterTo(this) { it.isNotEmpty() }
        }.distinct()
    }

    private fun closeUdpAssociation(association: UdpAssociation, reason: String) {
        if (!association.stream.isClosed) {
            scope.launch(Dispatchers.IO) {
                runCatching { association.stream.close(reason) }
            }
        }
        association.pumpJob.cancel()
    }

    private fun noteDnsTransportFailure(session: YumeBootstrapSession?, resolver: String, detail: String) {
        if (session == null) {
            return
        }
        val now = System.currentTimeMillis()
        var shouldWarn = false
        synchronized(dnsHealthLock) {
            if (dnsFailureSession !== session || now - dnsFailureWindowStartedAtMs > DNS_FAILURE_WINDOW_MS) {
                dnsFailureSession = session
                dnsFailureWindowStartedAtMs = now
                dnsFailureCount = 0
            }
            dnsFailureCount += 1
            shouldWarn = !startupWarmupMode && dnsFailureCount >= DNS_FAILURE_THRESHOLD
            if (shouldWarn) {
                dnsFailureWindowStartedAtMs = now
                dnsFailureCount = 0
            }
        }
        if (shouldWarn && session.isAlive) {
            log(
                VpnLogLevel.Warn,
                "DNS transport through Yume is degraded for $resolver, but the authenticated session will stay up while Android retries ($detail).",
            )
        }
    }

    private fun resetDnsTransportFailures(session: YumeBootstrapSession) {
        synchronized(dnsHealthLock) {
            if (dnsFailureSession === session) {
                dnsFailureWindowStartedAtMs = 0L
                dnsFailureCount = 0
            }
        }
    }

    private fun incrementActiveUdpAssociations() {
        synchronized(sharedUdpRelayLock) {
            activeUdpAssociations += 1
        }
    }

    private fun decrementActiveUdpAssociations() {
        synchronized(sharedUdpRelayLock) {
            if (activeUdpAssociations > 0) {
                activeUdpAssociations -= 1
            }
        }
    }

    private fun hasActiveUdpAssociations(): Boolean {
        return synchronized(sharedUdpRelayLock) {
            activeUdpAssociations > 0
        }
    }

    private fun negotiateNoAuth(input: InputStream, output: OutputStream): Boolean {
        val version = input.read()
        if (version < 0) {
            throw EOFException("SOCKS5 client disconnected during method negotiation")
        }
        if (version != SOCKS_VERSION) {
            throw IOException("Unsupported SOCKS version: $version")
        }
        val methodCount = input.read()
        if (methodCount <= 0) {
            throw IOException("SOCKS5 method negotiation is invalid")
        }
        val methods = readExact(input, methodCount)
        val supportsNoAuth = methods.any { (it.toInt() and 0xFF) == METHOD_NO_AUTH }
        if (!supportsNoAuth) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NO_ACCEPTABLE.toByte()))
            output.flush()
            return false
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NO_AUTH.toByte()))
        output.flush()
        return true
    }

    private fun readClientRequest(input: InputStream): SocksRequest {
        val header = readExact(input, 4)
        val version = header[0].toInt() and 0xFF
        val command = header[1].toInt() and 0xFF
        val addressType = header[3].toInt() and 0xFF
        if (version != SOCKS_VERSION) {
            throw SocksRequestException(REPLY_GENERAL_FAILURE, "bad SOCKS version")
        }
        if (command != COMMAND_CONNECT && command != COMMAND_UDP_ASSOCIATE) {
            throw SocksRequestException(REPLY_COMMAND_NOT_SUPPORTED, "unsupported command")
        }

        val host = when (addressType) {
            ADDRESS_TYPE_IPV4 -> readExact(input, 4).joinToString(".") { (it.toInt() and 0xFF).toString() }
            ADDRESS_TYPE_DOMAIN -> {
                val length = input.read()
                if (length < 0) {
                    throw SocksRequestException(REPLY_ADDRESS_TYPE_NOT_SUPPORTED, "missing domain length")
                }
                String(readExact(input, length), StandardCharsets.UTF_8)
            }

            ADDRESS_TYPE_IPV6 -> InetAddress.getByAddress(readExact(input, 16)).hostAddress
            else -> throw SocksRequestException(REPLY_ADDRESS_TYPE_NOT_SUPPORTED, "unsupported address type")
        }
        val portBytes = readExact(input, 2)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        val validDestination = when (command) {
            COMMAND_UDP_ASSOCIATE -> {
                // RFC 1928 allows 0.0.0.0:0 or [::]:0 when the client does not know the
                // UDP association address yet. tun2socks uses this form during setup.
                port in 0..65535 && (host.isNotBlank() || port == 0)
            }

            else -> host.isNotBlank() && port in 1..65535
        }
        if (!validDestination) {
            throw SocksRequestException(REPLY_GENERAL_FAILURE, "invalid destination")
        }
        return SocksRequest(command = command, host = host, port = port)
    }

    private fun parseUdpDatagram(buffer: ByteArray, length: Int): SocksUdpDatagram? {
        if (length < 4) {
            return null
        }
        if (buffer[0].toInt() != 0 || buffer[1].toInt() != 0 || buffer[2].toInt() != 0) {
            return null
        }
        var index = 4
        val host = when (val addressType = buffer[3].toInt() and 0xFF) {
            ADDRESS_TYPE_IPV4 -> {
                if (length < index + 4 + 2) {
                    return null
                }
                val bytes = byteArrayOf(buffer[index], buffer[index + 1], buffer[index + 2], buffer[index + 3])
                index += 4
                InetAddress.getByAddress(bytes).hostAddress
            }

            ADDRESS_TYPE_DOMAIN -> {
                if (length < index + 1) {
                    return null
                }
                val domainLength = buffer[index].toInt() and 0xFF
                index += 1
                if (length < index + domainLength + 2) {
                    return null
                }
                val host = String(buffer, index, domainLength, StandardCharsets.UTF_8)
                index += domainLength
                host
            }

            ADDRESS_TYPE_IPV6 -> {
                if (length < index + 16 + 2) {
                    return null
                }
                val bytes = buffer.copyOfRange(index, index + 16)
                index += 16
                InetAddress.getByAddress(bytes).hostAddress
            }

            else -> return null
        }
        if (length < index + 2) {
            return null
        }
        val port = ((buffer[index].toInt() and 0xFF) shl 8) or (buffer[index + 1].toInt() and 0xFF)
        index += 2
        if (port !in 1..65535) {
            return null
        }
        return SocksUdpDatagram(
            host = host,
            port = port,
            payload = buffer.copyOfRange(index, length),
        )
    }

    private fun buildUdpResponse(host: String, port: Int, payload: ByteArray): ByteArray {
        val address = when {
            host.all { it.isDigit() || it == '.' } -> runCatching { InetAddress.getByName(host) }.getOrNull()
            host.contains(':') -> runCatching { InetAddress.getByName(host) }.getOrNull()
            else -> null
        }
        val header = mutableListOf<Byte>()
        header += 0x00.toByte()
        header += 0x00.toByte()
        header += 0x00.toByte()
        when {
            address != null && address.address.size == 4 -> {
                header += ADDRESS_TYPE_IPV4.toByte()
                address.address.forEach { header += it }
            }

            address != null && address.address.size == 16 -> {
                header += ADDRESS_TYPE_IPV6.toByte()
                address.address.forEach { header += it }
            }

            else -> {
                val encoded = host.toByteArray(StandardCharsets.UTF_8)
                require(encoded.size <= 255) { "SOCKS5 domain is too long" }
                header += ADDRESS_TYPE_DOMAIN.toByte()
                header += encoded.size.toByte()
                encoded.forEach { header += it }
            }
        }
        header += ((port ushr 8) and 0xFF).toByte()
        header += (port and 0xFF).toByte()
        return ByteArray(header.size + payload.size).also { output ->
            header.forEachIndexed { index, byte -> output[index] = byte }
            payload.copyInto(output, header.size)
        }
    }

    private fun sendSocksReply(
        output: OutputStream,
        replyCode: Int,
        address: InetAddress = InetAddress.getByName("0.0.0.0"),
        port: Int = 0,
    ) {
        val rawAddress = address.address
        val reply = when (rawAddress.size) {
            16 -> byteArrayOf(
                SOCKS_VERSION.toByte(),
                (replyCode and 0xFF).toByte(),
                0x00,
                ADDRESS_TYPE_IPV6.toByte(),
            ) + rawAddress + byteArrayOf(
                ((port ushr 8) and 0xFF).toByte(),
                (port and 0xFF).toByte(),
            )

            else -> byteArrayOf(
                SOCKS_VERSION.toByte(),
                (replyCode and 0xFF).toByte(),
                0x00,
                ADDRESS_TYPE_IPV4.toByte(),
            ) + byteArrayOf(
                *rawAddress.copyOf(4),
                ((port ushr 8) and 0xFF).toByte(),
                (port and 0xFF).toByte(),
            )
        }
        output.write(reply)
        output.flush()
    }

    private fun bindLoopback(preferredPort: Int): ServerSocket {
        var lastError: IOException? = null
        for (port in listOf(preferredPort, 0).distinct()) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port))
                }
            } catch (throwable: IOException) {
                lastError = throwable
            }
        }
        throw lastError ?: IOException("Failed to bind the local SOCKS5 proxy")
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) {
                throw EOFException("SOCKS5 client disconnected unexpectedly")
            }
            offset += read
        }
        return bytes
    }

    private fun mapOpenFailureToReply(throwable: Throwable): Int {
        val message = throwable.message?.lowercase(Locale.ROOT).orEmpty()
        return when {
            message.contains("resolve") || message.contains("host") -> REPLY_HOST_UNREACHABLE
            message.contains("network") -> REPLY_NETWORK_UNREACHABLE
            message.contains("refused") -> REPLY_CONNECTION_REFUSED
            throwable is YumeBootstrapException -> REPLY_GENERAL_FAILURE
            else -> REPLY_GENERAL_FAILURE
        }
    }

    private fun protectedDestinationMatches(host: String, port: Int): Set<String> {
        val protected = protectedDestinationsProvider()
        if (port != protected.port || port !in 1..65535) {
            return emptySet()
        }
        val normalizedHost = normalizeHost(host)
        if (normalizedHost.isEmpty()) {
            return emptySet()
        }
        if (normalizedHost in protected.addresses) {
            return linkedSetOf(normalizedHost)
        }
        val resolvedAddresses = runCatching { protectedAddressResolver(normalizedHost) }
            .getOrDefault(emptySet())
            .mapTo(linkedSetOf()) { address -> normalizeHost(address) }
            .filterTo(linkedSetOf()) { address -> address.isNotEmpty() }
        val intersections = resolvedAddresses
            .filterTo(linkedSetOf()) { address -> address in protected.addresses }
        if (intersections.isNotEmpty()) {
            return intersections
        }
        if (normalizedHost in protected.hosts) {
            return if (protected.addresses.isNotEmpty()) protected.addresses else linkedSetOf(normalizedHost)
        }
        return emptySet()
    }

    private fun normalizeHost(host: String): String {
        return host
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')
            .lowercase(Locale.ROOT)
    }

    private fun resolveMappedHost(host: String): String {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty()) {
            return host
        }
        return mappedHostResolver(normalized)
            ?.takeIf { it.isNotBlank() }
            ?: host
    }

    private fun isIpv6LiteralHost(host: String): Boolean {
        val normalized = normalizeHost(host)
        if (!normalized.contains(':')) {
            return false
        }
        val address = runCatching { InetAddress.getByName(normalized) }.getOrNull() ?: return false
        return address.address.size == 16
    }

    private fun isEncryptedDnsBypass(host: String, port: Int): Boolean {
        return YumeTrafficPolicy.isEncryptedDnsBypass(
            host = host,
            port = port,
            dnsServers = dnsServersProvider(),
        )
    }

    private fun isExpectedSocketShutdown(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase(Locale.ROOT).orEmpty()
        return throwable is CancellationException ||
            throwable is EOFException ||
            message.contains("socket closed") ||
            message.contains("broken pipe") ||
            message.contains("connection reset") ||
            message.contains("cancelled") ||
            message.contains("closed")
    }

    private fun isWarmupTcpAllowed(host: String, port: Int): Boolean {
        if (port !in STARTUP_WARMUP_TCP_PORTS) {
            return false
        }
        val normalizedHost = normalizeHost(host)
        if (normalizedHost.isEmpty()) {
            return false
        }
        return normalizedHost in STARTUP_WARMUP_DNS_NAMES ||
            warmupTcpDestinationAllowedProvider(normalizedHost, port)
    }

    private fun isWarmupDnsAllowed(name: String): Boolean {
        return name.lowercase(Locale.ROOT) in STARTUP_WARMUP_DNS_NAMES
    }

    private fun filterDnsResponseForIpv4Only(payload: ByteArray): ByteArray {
        if (payload.size < DNS_HEADER_BYTES) {
            return payload
        }

        val questionCount = readDnsU16(payload, 4)
        val answerCount = readDnsU16(payload, 6)
        val authorityCount = readDnsU16(payload, 8)
        val additionalCount = readDnsU16(payload, 10)

        var offset = DNS_HEADER_BYTES
        repeat(questionCount) {
            val nameEnd = skipDnsName(payload, offset) ?: return payload
            if (nameEnd + 4 > payload.size) {
                return payload
            }
            offset = nameEnd + 4
        }
        val questionSectionEnd = offset

        val keptAnswers = ArrayList<ByteArray>(answerCount)
        val keptAuthority = ArrayList<ByteArray>(authorityCount)
        val keptAdditional = ArrayList<ByteArray>(additionalCount)
        var stripped = false
        var malformed = false

        fun collectSection(count: Int, target: MutableList<ByteArray>): Int {
            var kept = 0
            repeat(count) {
                val recordStart = offset
                val nameEnd = skipDnsName(payload, offset) ?: run {
                    malformed = true
                    return kept
                }
                if (nameEnd + 10 > payload.size) {
                    malformed = true
                    return kept
                }
                val type = readDnsU16(payload, nameEnd)
                val rdLength = readDnsU16(payload, nameEnd + 8)
                val recordEnd = nameEnd + 10 + rdLength
                if (recordEnd > payload.size) {
                    malformed = true
                    return kept
                }
                if (shouldStripDnsRecordType(type)) {
                    stripped = true
                } else {
                    target += payload.copyOfRange(recordStart, recordEnd)
                    kept += 1
                }
                offset = recordEnd
            }
            return kept
        }

        val keptAnswerCount = collectSection(answerCount, keptAnswers)
        val keptAuthorityCount = collectSection(authorityCount, keptAuthority)
        val keptAdditionalCount = collectSection(additionalCount, keptAdditional)
        if (malformed) {
            return payload
        }
        if (!stripped) {
            return payload
        }

        val header = payload.copyOfRange(0, DNS_HEADER_BYTES)
        writeDnsU16(header, 6, keptAnswerCount)
        writeDnsU16(header, 8, keptAuthorityCount)
        writeDnsU16(header, 10, keptAdditionalCount)

        return ByteArrayOutputStream(payload.size).use { output ->
            output.write(header)
            output.write(payload, DNS_HEADER_BYTES, questionSectionEnd - DNS_HEADER_BYTES)
            keptAnswers.forEach(output::write)
            keptAuthority.forEach(output::write)
            keptAdditional.forEach(output::write)
            output.toByteArray()
        }
    }

    private fun skipDnsName(payload: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < payload.size) {
            val length = payload[offset].toInt() and 0xFF
            offset += 1
            if (length == 0) {
                return offset
            }
            if ((length and 0xC0) == 0xC0) {
                return if (offset < payload.size) offset + 1 else null
            }
            if (offset + length > payload.size) {
                return null
            }
            offset += length
        }
        return null
    }

    private fun shouldStripDnsRecordType(type: Int): Boolean {
        return type == DNS_TYPE_AAAA || type == DNS_TYPE_SVCB || type == DNS_TYPE_HTTPS
    }

    private fun readDnsU16(payload: ByteArray, offset: Int): Int {
        return ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
    }

    private fun writeDnsU16(payload: ByteArray, offset: Int, value: Int) {
        payload[offset] = ((value ushr 8) and 0xFF).toByte()
        payload[offset + 1] = (value and 0xFF).toByte()
    }

    private fun udpAssociationKey(clientAddress: InetAddress, clientPort: Int, host: String, port: Int): String {
        val normalizedHost = normalizeHost(host)
        return if (port == DNS_PORT) {
            "dns|$normalizedHost|$port"
        } else {
            "${clientAddress.hostAddress}|$clientPort|$normalizedHost|$port"
        }
    }

    private fun registerDnsPendingRoute(
        relay: SharedUdpRelay,
        resolverHost: String,
        payload: ByteArray,
        clientAddress: InetAddress,
        clientPort: Int,
    ): PendingDnsRoute {
        val route = PendingDnsRoute(
            routeKey = YumeDnsRouting.buildRouteKey(resolverHost, payload),
            clientAddress = clientAddress,
            clientPort = clientPort,
        )
        synchronized(relay.associationsLock) {
            relay.pendingDnsRoutes
                .getOrPut(route.routeKey) { ArrayDeque() }
                .addLast(route)
        }
        return route
    }

    private fun removeDnsPendingRoute(
        relay: SharedUdpRelay,
        route: PendingDnsRoute,
    ) {
        synchronized(relay.associationsLock) {
            val queue = relay.pendingDnsRoutes[route.routeKey] ?: return
            queue.remove(route)
            if (queue.isEmpty()) {
                relay.pendingDnsRoutes.remove(route.routeKey)
            }
        }
    }

    private fun relayDnsResponse(
        relay: SharedUdpRelay,
        resolverHost: String,
        payload: ByteArray,
        udpSocket: DatagramSocket,
    ) {
        val routeKey = YumeDnsRouting.buildRouteKey(resolverHost, payload)
        val route = synchronized(relay.associationsLock) {
            val queue = relay.pendingDnsRoutes[routeKey] ?: return@synchronized null
            while (queue.isNotEmpty()) {
                val candidate = queue.removeFirst()
                if (System.currentTimeMillis() - candidate.createdAtMs <= DNS_PENDING_ROUTE_TIMEOUT_MS) {
                    if (queue.isEmpty()) {
                        relay.pendingDnsRoutes.remove(routeKey)
                    }
                    return@synchronized candidate
                }
            }
            relay.pendingDnsRoutes.remove(routeKey)
            null
        } ?: return

        val response = buildUdpResponse(resolverHost, DNS_PORT, payload)
        udpSocket.send(DatagramPacket(response, response.size, route.clientAddress, route.clientPort))
    }

    private fun selectAssociationsToEvictLocked(
        relay: SharedUdpRelay,
        requestedPort: Int,
    ): List<UdpAssociation> {
        val candidates = relay.associations.values.toList()
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val selected = linkedSetOf<UdpAssociation>()

        fun selectOldest(
            pool: List<UdpAssociation>,
            count: Int,
        ) {
            if (count <= 0) {
                return
            }
            pool.sortedBy { it.lastActivityAtMs }
                .take(count)
                .forEach(selected::add)
        }

        val totalOverflow = (relay.associations.size + 1) - runtimeConfig.maxUdpAssociations
        if (totalOverflow > 0) {
            selectOldest(candidates, totalOverflow)
        }

        if (requestedPort == DNS_PORT) {
            val dnsAssociations = candidates.filter { it.port == DNS_PORT }
            val dnsOverflow = (dnsAssociations.size + 1) - runtimeConfig.maxDnsUdpAssociations
            if (dnsOverflow > 0) {
                selectOldest(dnsAssociations, dnsOverflow)
            }
        }

        if (selected.isEmpty()) {
            return emptyList()
        }

        selected.forEach { association ->
            relay.associations.remove(association.key)
        }
        return selected.toList()
    }

    private fun associationIdleTimeoutMs(port: Int): Long {
        return if (port == DNS_PORT) {
            DNS_UDP_ASSOCIATION_IDLE_TIMEOUT_MS
        } else {
            UDP_ASSOCIATION_IDLE_TIMEOUT_MS
        }
    }

    private fun shouldForceTcpFallback(port: Int): Boolean {
        return port in TCP_FALLBACK_UDP_PORTS && forceTcpFallbackUdp443Provider()
    }

    private data class SocksRequest(
        val command: Int,
        val host: String,
        val port: Int,
    ) {
        fun withResolvedHost(resolvedHost: String): SocksRequest {
            if (resolvedHost == host) {
                return this
            }
            return copy(host = resolvedHost)
        }
    }

    private data class SocksUdpDatagram(
        val host: String,
        val port: Int,
        val payload: ByteArray,
    ) {
        fun withResolvedHost(resolvedHost: String): SocksUdpDatagram {
            if (resolvedHost == host) {
                return this
            }
            return copy(host = resolvedHost)
        }
    }

    private data class UdpAssociation(
        val key: String,
        val host: String,
        val port: Int,
        val ownerSession: YumeBootstrapSession,
        val clientAddress: InetAddress,
        val clientPort: Int,
        val stream: YumeUdpStream,
        val pumpJob: Job,
        val idleTimeoutMs: Long,
        @Volatile var lastActivityAtMs: Long = System.currentTimeMillis(),
    ) {
        fun touch() {
            lastActivityAtMs = System.currentTimeMillis()
        }
    }

    private data class ActiveTcpRelay(
        val streamId: Int,
        val host: String,
        val port: Int,
        val client: Socket,
        val stream: YumeTcpStream,
        val createdAtMs: Long = SystemClock.elapsedRealtime(),
        @Volatile var lastActivityAtMs: Long = SystemClock.elapsedRealtime(),
    ) {
        fun touch(nowMs: Long = SystemClock.elapsedRealtime()) {
            lastActivityAtMs = nowMs
        }
    }

    private data class PendingDnsRoute(
        val routeKey: String,
        val clientAddress: InetAddress,
        val clientPort: Int,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private class SharedUdpRelay(
        val socket: DatagramSocket,
        val policyWarnings: MutableSet<String> = linkedSetOf(),
        val associations: MutableMap<String, UdpAssociation> = linkedMapOf(),
        val pendingAssociationOpens: MutableMap<String, CompletableDeferred<UdpAssociation>> = linkedMapOf(),
        val pendingDnsRoutes: MutableMap<String, ArrayDeque<PendingDnsRoute>> = linkedMapOf(),
        val associationsLock: Any = Any(),
        var receiveJob: Job? = null,
        var cleanupJob: Job? = null,
    ) : Closeable {
        override fun close() {
            receiveJob?.cancel()
            cleanupJob?.cancel()
            val (associationsToClose, pendingOpens) = synchronized(associationsLock) {
                val pending = pendingAssociationOpens.values.toList()
                pendingAssociationOpens.clear()
                pendingDnsRoutes.clear()
                associations.values.toList().also { associations.clear() } to pending
            }
            pendingOpens.forEach { deferred ->
                deferred.completeExceptionally(IOException("SOCKS5 shared UDP relay stopped"))
            }
            associationsToClose.forEach { association ->
                association.pumpJob.cancel()
            }
            socket.close()
        }
    }

    data class ProtectedDestinations(
        val port: Int = 0,
        val hosts: Set<String> = emptySet(),
        val addresses: Set<String> = emptySet(),
    )

    private class SocksRequestException(
        val replyCode: Int,
        message: String,
    ) : IOException(message)

    companion object {
        private const val DEFAULT_PROXY_PORT = 1088
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val MAX_UDP_PACKET_BYTES = 65_535
        private const val MAX_DNS_RESPONSE_BYTES = 65_535
        private const val DNS_HEADER_BYTES = 12
        private const val DNS_TYPE_AAAA = 28
        private const val DNS_TYPE_SVCB = 64
        private const val DNS_TYPE_HTTPS = 65
        private const val DNS_PORT = 53
        private const val DNS_OVER_TCP_TIMEOUT_MS = 10_000
        private const val DNS_FAILURE_THRESHOLD = 6
        private const val DNS_FAILURE_WINDOW_MS = 15_000L
        private const val DNS_PENDING_ROUTE_TIMEOUT_MS = 15_000L
        private const val DNS_UDP_ASSOCIATION_IDLE_TIMEOUT_MS = 8_000L
        private const val UDP_ASSOCIATION_IDLE_TIMEOUT_MS = 15_000L
        private const val UDP_ASSOCIATION_CLEANUP_INTERVAL_MS = 5_000L
        private val TCP_FALLBACK_UDP_PORTS = setOf(443)
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val BRIDGE_DRAIN_TIMEOUT_MS = 1_500L

        private const val SOCKS_VERSION = 5
        private const val METHOD_NO_AUTH = 0x00
        private const val METHOD_NO_ACCEPTABLE = 0xFF
        private const val COMMAND_CONNECT = 0x01
        private const val COMMAND_UDP_ASSOCIATE = 0x03

        private const val ADDRESS_TYPE_IPV4 = 0x01
        private const val ADDRESS_TYPE_DOMAIN = 0x03
        private const val ADDRESS_TYPE_IPV6 = 0x04

        private const val REPLY_SUCCESS = 0x00
        private const val REPLY_CONNECTION_NOT_ALLOWED = 0x02
        private const val REPLY_GENERAL_FAILURE = 0x01
        private const val REPLY_NETWORK_UNREACHABLE = 0x03
        private const val REPLY_HOST_UNREACHABLE = 0x04
        private const val REPLY_CONNECTION_REFUSED = 0x05
        private const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
        private const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
        private val STARTUP_WARMUP_TCP_PORTS = setOf(80)
        private val STARTUP_WARMUP_DNS_NAMES = setOf("ifconfig.me", "api.ipify.org", "icanhazip.com")
    }
}
