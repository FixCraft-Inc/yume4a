package com.fixcraft.yume.transport

import java.util.concurrent.atomic.AtomicInteger

/**
 * A small pool of [YumeBootstrapSession] handles that load-balances new
 * streams across multiple parallel TLS connections to the server.
 *
 * The cap on a single TLS multiplex (~30 Mbps down / 1 Mbps up on the
 * device under upload backpressure) is the routing/protocol layer rather
 * than the crypto — once one tunnel is congested, additional streams
 * routed through it stall. Spreading streams across N parallel tunnels
 * lifts that ceiling without changing the wire protocol: each tunnel
 * authenticates independently, so the server sees N sessions; the
 * client treats them as one logical egress.
 *
 * Picking policy: least-loaded by [YumeBootstrapSession.activeStreamCount].
 * The session already tracks its own stream-registry size, so the pool
 * has no external counter to drift. Round-robin is available as an
 * opt-in alternative.
 */
class YumeBootstrapSessionPool {
    private val sessions = mutableListOf<YumeBootstrapSession>()
    private val mutex = Any()
    private val roundRobinCursor = AtomicInteger(0)

    /**
     * Register a session as part of the pool. The first session added
     * is conventionally the "primary" but the pool itself treats them
     * uniformly. Not safe to call concurrently with [pickForStream] —
     * call all [add]s before any [pickForStream].
     */
    fun add(session: YumeBootstrapSession) {
        synchronized(mutex) {
            sessions += session
        }
    }

    /**
     * Pick a live session for a new stream. Returns null if the pool
     * is empty or every member is dead. The load is read from the
     * session's own [YumeBootstrapSession.activeStreamCount] — no
     * external book-keeping, so there's nothing for callers to release.
     */
    fun pickForStream(policy: Policy = Policy.LeastLoaded): YumeBootstrapSession? {
        val snapshot = synchronized(mutex) { sessions.toList() }
        if (snapshot.isEmpty()) return null
        val live = snapshot.filter { it.isAlive }
        if (live.isEmpty()) return null
        return when (policy) {
            Policy.RoundRobin -> {
                val idx = (roundRobinCursor.getAndIncrement() and Int.MAX_VALUE) % live.size
                live[idx]
            }
            Policy.LeastLoaded -> {
                var best = live.first()
                var bestLoad = best.activeStreamCount
                for (i in 1 until live.size) {
                    val candidate = live[i]
                    val load = candidate.activeStreamCount
                    if (load < bestLoad) {
                        best = candidate
                        bestLoad = load
                    }
                }
                best
            }
        }
    }

    /**
     * Close every session in the pool. Should be called once during
     * disconnect; safe to call multiple times.
     */
    fun closeAll() {
        val snapshot = synchronized(mutex) { sessions.toList().also { sessions.clear() } }
        for (session in snapshot) {
            runCatching { session.close() }
        }
    }

    /**
     * Remove all dead sessions and return them so the caller can close
     * them off-thread. Live sessions stay in the pool. Used by the
     * partial-rebuild path so we don't tear down healthy tunnels just
     * because one of them died — the previous "close everything,
     * reopen everything" path triggered 4× simultaneous TCP resets on
     * the server side (one RST per tunnel) and a thundering herd of
     * heavy-Argon2 re-auths.
     */
    fun removeDead(): List<YumeBootstrapSession> {
        return synchronized(mutex) {
            val dead = sessions.filter { !it.isAlive }
            sessions.removeAll(dead.toSet())
            dead
        }
    }

    /** Number of sessions in the pool (regardless of liveness). */
    val size: Int
        get() = synchronized(mutex) { sessions.size }

    /** Number of currently-alive sessions. */
    val liveCount: Int
        get() = synchronized(mutex) { sessions.count { it.isAlive } }

    /** Diagnostic snapshot, for logging. */
    fun snapshot(): Snapshot {
        val snap = synchronized(mutex) { sessions.toList() }
        return Snapshot(
            sessionCount = snap.size,
            liveSessionCount = snap.count { it.isAlive },
            streamsPerSession = snap.map { it.activeStreamCount },
            totalActiveStreams = snap.sumOf { it.activeStreamCount },
        )
    }

    data class Snapshot(
        val sessionCount: Int,
        val liveSessionCount: Int,
        val streamsPerSession: List<Int>,
        val totalActiveStreams: Int,
    )

    enum class Policy {
        RoundRobin,
        LeastLoaded,
    }
}
