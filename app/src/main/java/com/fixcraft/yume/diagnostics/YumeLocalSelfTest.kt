package com.fixcraft.yume.diagnostics

import com.fixcraft.basefwx.Crypto
import com.fixcraft.yume.transport.YumePacketBulk
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

internal data class YumeLocalSelfTestReport(
    val rows: List<YumeLocalSelfTestRow>,
    val elapsedMs: Long,
) {
    fun toPlainText(): String = buildString {
        appendLine("Yume Android local self-test")
        appendLine("elapsed_ms=$elapsedMs")
        for (row in rows) {
            append(row.name)
            append(": ok=")
            append(row.ok)
            append(" metric=\"")
            append(row.metric)
            append('"')
            row.value?.let {
                append(" value=")
                append(formatSelfTestRaw(it))
            }
            if (row.unit.isNotBlank()) {
                append(" unit=\"")
                append(row.unit)
                append('"')
            }
            row.bytes?.let {
                append(" bytes=")
                append(it)
            }
            row.operations?.let {
                append(" ops=")
                append(it)
            }
            row.seconds?.let {
                append(" seconds=")
                append(formatSelfTestRaw(it))
            }
            if (row.detail.isNotBlank()) {
                append(" detail=\"")
                append(row.detail.replace("\"", "'"))
                append('"')
            }
            appendLine()
        }
    }.trimEnd()
}

internal data class YumeLocalSelfTestRow(
    val name: String,
    val metric: String,
    val detail: String,
    val ok: Boolean = true,
    val value: Double? = null,
    val unit: String = "",
    val bytes: Long? = null,
    val operations: Int? = null,
    val seconds: Double? = null,
)

internal object YumeLocalSelfTest {
    private const val KIB = 1024
    private const val MIB = 1024 * KIB

    fun runQuick(): YumeLocalSelfTestReport = run(
        Workload(
            copyBytes = 64 * MIB,
            streamBytes = 32 * MIB,
            manyStreams = 64,
            cryptoBytes = 32 * MIB,
            packetCodecBytes = 32 * MIB,
            hkdfOps = 5_000,
        )
    )

    internal fun runSmokeForTest(): YumeLocalSelfTestReport = run(
        Workload(
            copyBytes = 128 * KIB,
            streamBytes = 128 * KIB,
            manyStreams = 4,
            cryptoBytes = 128 * KIB,
            packetCodecBytes = 128 * KIB,
            hkdfOps = 32,
        )
    )

    private fun run(workload: Workload): YumeLocalSelfTestReport {
        val start = System.nanoTime()
        val rows = mutableListOf<YumeLocalSelfTestRow>()
        rows += capture("copy-floor") { runCopyFloor(workload.copyBytes) }
        rows += capture("stream-copy-1") { runStreamCopy(workload.streamBytes, streams = 1) }
        rows += capture("stream-copy-many") { runStreamCopy(workload.streamBytes, streams = workload.manyStreams) }
        rows += capture("aes-gcm-encrypt") { runAesGcmEncrypt(workload.cryptoBytes) }
        rows += capture("aes-gcm-decrypt") { runAesGcmDecrypt(workload.cryptoBytes) }
        rows += capture("packet-bulk-encode") { runPacketBulkEncode(workload.packetCodecBytes) }
        rows += capture("packet-bulk-decode") { runPacketBulkDecode(workload.packetCodecBytes) }
        rows += capture("hop-hkdf") { runHopHkdf(workload.hkdfOps) }
        val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
        return YumeLocalSelfTestReport(rows = rows, elapsedMs = elapsedMs)
    }

    private fun capture(name: String, block: () -> YumeLocalSelfTestRow): YumeLocalSelfTestRow {
        return runCatching(block).getOrElse { throwable ->
            YumeLocalSelfTestRow(
                name = name,
                metric = "failed",
                detail = throwable.message ?: throwable.javaClass.simpleName,
                ok = false,
            )
        }
    }

    private fun runCopyFloor(totalBytes: Int): YumeLocalSelfTestRow {
        val src = patternedBytes(64 * KIB)
        val dst = ByteArray(src.size)
        var guard = 0
        val iterations = iterationsFor(totalBytes, src.size)
        val seconds = timedSeconds {
            repeat(iterations) {
                src.copyInto(dst)
                guard = guard xor dst[it and (dst.size - 1)].toInt()
            }
        }
        return throughputRow(
            name = "copy-floor",
            bytes = iterations.toLong() * src.size,
            seconds = seconds,
            detail = "64 KiB array copy, guard=${guard and 0xFF}",
        )
    }

    private fun runStreamCopy(totalBytes: Int, streams: Int): YumeLocalSelfTestRow {
        val streamCount = max(1, streams)
        val perStreamBytes = splitBytes(totalBytes, streamCount)
        val src = patternedBytes(32 * KIB)
        val startFlag = AtomicBoolean(false)
        val secondsByStream = DoubleArray(streamCount)
        val guards = IntArray(streamCount)
        val workers = List(streamCount) { streamIndex ->
            thread(start = false, name = "yume-selftest-stream-$streamIndex") {
                val dst = ByteArray(src.size)
                while (!startFlag.get()) {
                    Thread.yield()
                }
                val started = System.nanoTime()
                var copied = 0
                var guard = 0
                val target = perStreamBytes[streamIndex]
                while (copied < target) {
                    val chunk = minOf(src.size, target - copied)
                    src.copyInto(dst, endIndex = chunk)
                    guard = guard xor dst[(copied / max(1, chunk)) and (dst.size - 1)].toInt()
                    copied += chunk
                }
                secondsByStream[streamIndex] = (System.nanoTime() - started).toDouble() / 1_000_000_000.0
                guards[streamIndex] = guard
            }
        }
        workers.forEach { it.start() }
        val started = System.nanoTime()
        startFlag.set(true)
        workers.forEach { it.join() }
        val aggregateSeconds = (System.nanoTime() - started).toDouble() / 1_000_000_000.0
        val streamRates = secondsByStream.mapIndexed { index, seconds ->
            (perStreamBytes[index].toDouble() / MIB.toDouble()) / seconds.coerceAtLeast(0.000_001)
        }
        val stats = miniStats(streamRates)
        val instability = if (stats.median > 0.0) ((stats.max - stats.min) / stats.median) * 100.0 else 0.0
        val guard = guards.fold(0) { acc, value -> acc xor value } and 0xFF
        val measuredBytes = perStreamBytes.fold(0L) { acc, value -> acc + value.toLong() }
        return throughputRow(
            name = if (streamCount == 1) "stream-copy-1" else "stream-copy-many",
            bytes = measuredBytes,
            seconds = aggregateSeconds,
            detail = "streams=$streamCount per_stream_mib_s min=${formatNumber(stats.min)} median=${formatNumber(stats.median)} p95=${formatNumber(stats.p95)} max=${formatNumber(stats.max)} instability=${formatNumber(instability)}% guard=$guard",
        )
    }

    private fun runAesGcmEncrypt(totalBytes: Int): YumeLocalSelfTestRow {
        val key = Crypto.randomBytes(32)
        val aad = "yume-android-selftest".toByteArray(StandardCharsets.US_ASCII)
        val plaintext = patternedBytes(64 * KIB)
        var guard = 0
        val iterations = iterationsFor(totalBytes, plaintext.size)
        val seconds = timedSeconds {
            repeat(iterations) {
                val encrypted = Crypto.aesGcmEncrypt(key, plaintext, aad)
                guard = guard xor encrypted[encrypted.lastIndex].toInt()
            }
        }
        return throughputRow(
            name = "aes-gcm-encrypt",
            bytes = iterations.toLong() * plaintext.size,
            seconds = seconds,
            detail = "BaseFWX AES-GCM, 64 KiB chunks, guard=${guard and 0xFF}",
        )
    }

    private fun runAesGcmDecrypt(totalBytes: Int): YumeLocalSelfTestRow {
        val key = Crypto.randomBytes(32)
        val aad = "yume-android-selftest".toByteArray(StandardCharsets.US_ASCII)
        val plaintext = patternedBytes(64 * KIB)
        val encrypted = Crypto.aesGcmEncrypt(key, plaintext, aad)
        var guard = 0
        val iterations = iterationsFor(totalBytes, plaintext.size)
        val seconds = timedSeconds {
            repeat(iterations) {
                val decrypted = Crypto.aesGcmDecrypt(key, encrypted, aad)
                guard = guard xor decrypted[it and (decrypted.size - 1)].toInt()
            }
        }
        return throughputRow(
            name = "aes-gcm-decrypt",
            bytes = iterations.toLong() * plaintext.size,
            seconds = seconds,
            detail = "BaseFWX AES-GCM, 64 KiB chunks, guard=${guard and 0xFF}",
        )
    }

    private fun runPacketBulkEncode(totalBytes: Int): YumeLocalSelfTestRow {
        val batch = packetBatch()
        val encodedBytes = YumePacketBulk.encodedSize(batch)
        val iterations = iterationsFor(totalBytes, encodedBytes)
        var guard = 0
        val seconds = timedSeconds {
            repeat(iterations) {
                val encoded = YumePacketBulk.encode(batch.copy(sequence = it.toLong()))
                guard = guard xor encoded[encoded.lastIndex].toInt()
            }
        }
        return throughputRow(
            name = "packet-bulk-encode",
            bytes = iterations.toLong() * encodedBytes,
            seconds = seconds,
            detail = "${batch.packets.size} packets/batch, guard=${guard and 0xFF}",
        )
    }

    private fun runPacketBulkDecode(totalBytes: Int): YumeLocalSelfTestRow {
        val batch = packetBatch()
        val encoded = YumePacketBulk.encode(batch)
        val iterations = iterationsFor(totalBytes, encoded.size)
        var guard = 0
        val seconds = timedSeconds {
            repeat(iterations) {
                val decoded = YumePacketBulk.decode(encoded)
                guard = guard xor decoded.packets.last().last().toInt()
            }
        }
        return throughputRow(
            name = "packet-bulk-decode",
            bytes = iterations.toLong() * encoded.size,
            seconds = seconds,
            detail = "${batch.packets.size} packets/batch, guard=${guard and 0xFF}",
        )
    }

    private fun runHopHkdf(ops: Int): YumeLocalSelfTestRow {
        val key = Crypto.randomBytes(32)
        var guard = 0
        val count = max(1, ops)
        val seconds = timedSeconds {
            repeat(count) {
                val info = "yume-hop-v1:$it".toByteArray(StandardCharsets.US_ASCII)
                val derived = Crypto.hkdfSha256(key, info, 32)
                guard = guard xor derived[0].toInt()
            }
        }
        val opsPerSecond = count / seconds.coerceAtLeast(0.000_001)
        return YumeLocalSelfTestRow(
            name = "hop-hkdf",
            metric = "${formatNumber(opsPerSecond)} ops/s",
            detail = "$count derived hop keys, guard=${guard and 0xFF}",
            value = opsPerSecond,
            unit = "ops/s",
            operations = count,
            seconds = seconds,
        )
    }

    private fun throughputRow(
        name: String,
        bytes: Long,
        seconds: Double,
        detail: String,
    ): YumeLocalSelfTestRow {
        val mib = bytes.toDouble() / MIB.toDouble()
        val mibPerSecond = mib / seconds.coerceAtLeast(0.000_001)
        return YumeLocalSelfTestRow(
            name = name,
            metric = "${formatNumber(mibPerSecond)} MiB/s",
            detail = detail,
            value = mibPerSecond,
            unit = "MiB/s",
            bytes = bytes,
            seconds = seconds,
        )
    }

    private fun packetBatch(): YumePacketBulk.Batch {
        val packets = List(YumePacketBulk.MAX_PACKETS_PER_BATCH) { index ->
            patternedBytes(1_200, seed = index + 1).also { packet ->
                packet[0] = 0x45.toByte()
                packet[1] = 0x00.toByte()
            }
        }
        return YumePacketBulk.Batch(sequence = 1L, packets = packets)
    }

    private fun patternedBytes(size: Int, seed: Int = 17): ByteArray {
        return ByteArray(size) { index ->
            ((index * 31 + seed * 13) and 0xFF).toByte()
        }
    }

    private fun iterationsFor(totalBytes: Int, chunkBytes: Int): Int {
        return max(1, totalBytes / max(1, chunkBytes))
    }

    private fun splitBytes(totalBytes: Int, streams: Int): IntArray {
        val count = max(1, streams)
        val out = IntArray(count) { totalBytes / count }
        var remaining = totalBytes % count
        var index = 0
        while (remaining > 0) {
            out[index] += 1
            remaining -= 1
            index = (index + 1) % count
        }
        return out
    }

    private data class MiniStats(
        val min: Double,
        val median: Double,
        val p95: Double,
        val max: Double,
    )

    private fun miniStats(values: List<Double>): MiniStats {
        if (values.isEmpty()) return MiniStats(0.0, 0.0, 0.0, 0.0)
        val sorted = values.sorted()
        fun pick(q: Double): Double {
            val raw = kotlin.math.ceil(q * sorted.size.toDouble()).toInt() - 1
            return sorted[raw.coerceIn(0, sorted.lastIndex)]
        }
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
        return MiniStats(
            min = sorted.first(),
            median = median,
            p95 = pick(0.95),
            max = sorted.last(),
        )
    }

    private fun timedSeconds(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start).toDouble() / 1_000_000_000.0
    }

    private fun formatNumber(value: Double): String {
        return String.format(Locale.US, "%,.1f", value)
    }

    private data class Workload(
        val copyBytes: Int,
        val streamBytes: Int,
        val manyStreams: Int,
        val cryptoBytes: Int,
        val packetCodecBytes: Int,
        val hkdfOps: Int,
    )
}

private fun formatSelfTestRaw(value: Double): String {
    return String.format(Locale.US, "%.6f", value)
}
