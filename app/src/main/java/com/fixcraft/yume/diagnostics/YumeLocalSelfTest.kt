package com.fixcraft.yume.diagnostics

import com.fixcraft.basefwx.Crypto
import com.fixcraft.yume.transport.YumePacketBulk
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class YumeLocalSelfTestReport(
    val rows: List<YumeLocalSelfTestRow>,
    val elapsedMs: Long,
    val mode: YumeLocalSelfTestMode,
    val score: Long? = null,
) {
    fun toPlainText(): String = buildString {
        appendLine("Yume Android local benchmark")
        appendLine("mode=${mode.id}")
        appendLine("elapsed_ms=$elapsedMs")
        score?.let {
            appendLine("TOTAL ${formatSelfTestInteger(it)} grade=${benchmarkGrade(it)} model=yume-bench-v3")
            appendLine("score_model=yume-bench-v3")
            appendLine("score=$it grade=${benchmarkGrade(it)}")
        }
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

internal enum class YumeLocalSelfTestMode(val id: String) {
    Quick("quick"),
    Full("full"),
}

internal data class YumeLocalSelfTestProgress(
    val label: String,
    val fraction: Float,
) {
    val percent: Int
        get() = (fraction.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)
}

internal object YumeLocalSelfTest {
    private const val KIB = 1024
    private const val MIB = 1024 * KIB
    private const val REFERENCE_SCORE = 10_000_000.0

    fun run(
        mode: YumeLocalSelfTestMode,
        cacheDir: File? = null,
        onProgress: (YumeLocalSelfTestProgress) -> Unit = {},
    ): YumeLocalSelfTestReport {
        return when (mode) {
            YumeLocalSelfTestMode.Quick -> runQuick(cacheDir, onProgress)
            YumeLocalSelfTestMode.Full -> runFull(cacheDir, onProgress)
        }
    }

    fun runQuick(
        cacheDir: File? = null,
        onProgress: (YumeLocalSelfTestProgress) -> Unit = {},
    ): YumeLocalSelfTestReport = run(
        Workload(
            copyBytes = 64 * MIB,
            streamSingleBytes = 32 * MIB,
            streamManyBytes = 256 * MIB,
            manyStreams = 64,
            cryptoBytes = 32 * MIB,
            packetCodecBytes = 32 * MIB,
            hkdfOps = 5_000,
            diskBytes = 0,
            sustainedMixMs = 0,
            scoreEnabled = false,
        ),
        mode = YumeLocalSelfTestMode.Quick,
        cacheDir = cacheDir,
        onProgress = onProgress,
    )

    fun runFull(
        cacheDir: File? = null,
        onProgress: (YumeLocalSelfTestProgress) -> Unit = {},
    ): YumeLocalSelfTestReport = run(
        Workload(
            copyBytes = 512 * MIB,
            streamSingleBytes = 256 * MIB,
            streamManyBytes = 1024 * MIB,
            manyStreams = 64,
            cryptoBytes = 512 * MIB,
            packetCodecBytes = 512 * MIB,
            hkdfOps = 100_000,
            diskBytes = 256 * MIB,
            sustainedMixMs = 30_000,
            scoreEnabled = true,
        ),
        mode = YumeLocalSelfTestMode.Full,
        cacheDir = cacheDir,
        onProgress = onProgress,
    )

    internal fun runSmokeForTest(): YumeLocalSelfTestReport = run(
        Workload(
            copyBytes = 128 * KIB,
            streamSingleBytes = 128 * KIB,
            streamManyBytes = 256 * KIB,
            manyStreams = 4,
            cryptoBytes = 128 * KIB,
            packetCodecBytes = 128 * KIB,
            hkdfOps = 32,
            diskBytes = 0,
            sustainedMixMs = 0,
            scoreEnabled = false,
        ),
        mode = YumeLocalSelfTestMode.Quick,
        cacheDir = null,
        onProgress = {},
    )

    private fun run(
        workload: Workload,
        mode: YumeLocalSelfTestMode,
        cacheDir: File?,
        onProgress: (YumeLocalSelfTestProgress) -> Unit,
    ): YumeLocalSelfTestReport {
        val start = System.nanoTime()
        val rows = mutableListOf<YumeLocalSelfTestRow>()
        val steps = mutableListOf(
            WorkStep("copy-floor", 0.25) { _ -> runCopyFloor(workload.copyBytes) },
            WorkStep("stream-copy-1", 0.35) { _ -> runStreamCopy(workload.streamSingleBytes, streams = 1) },
            WorkStep("stream-copy-many", 0.70) { _ -> runStreamCopy(workload.streamManyBytes, streams = workload.manyStreams) },
            WorkStep("aes-gcm-encrypt", 2.20) { _ -> runAesGcmEncrypt(workload.cryptoBytes) },
            WorkStep("aes-gcm-decrypt", 2.20) { _ -> runAesGcmDecrypt(workload.cryptoBytes) },
            WorkStep("packet-bulk-encode", 0.90) { _ -> runPacketBulkEncode(workload.packetCodecBytes) },
            WorkStep("packet-bulk-decode", 0.90) { _ -> runPacketBulkDecode(workload.packetCodecBytes) },
            WorkStep("hop-hkdf", 0.80) { _ -> runHopHkdf(workload.hkdfOps) },
        )
        if (workload.diskBytes > 0 && cacheDir != null) {
            steps += WorkStep("disk-write", 1.40) { _ -> runDiskWrite(cacheDir, workload.diskBytes) }
        }
        if (workload.sustainedMixMs > 0) {
            steps += WorkStep("sustained-mix", workload.sustainedMixMs.toDouble() / 1_000.0) { progress ->
                runSustainedMix(workload.sustainedMixMs, progress)
            }
        }
        val totalWeight = steps.sumOf { it.weight }.coerceAtLeast(1.0)
        var completedWeight = 0.0
        fun publish(weightBeforeStep: Double, step: WorkStep, stageFraction: Double = 0.0) {
            val fraction = ((weightBeforeStep + step.weight * stageFraction.coerceIn(0.0, 1.0)) / totalWeight)
                .coerceIn(0.0, 1.0)
            onProgress(YumeLocalSelfTestProgress(step.name, fraction.toFloat()))
        }
        steps.forEach { step ->
            val weightBeforeStep = completedWeight
            publish(weightBeforeStep, step)
            rows += capture(step.name) { step.block { stageFraction -> publish(weightBeforeStep, step, stageFraction) } }
            completedWeight += step.weight
            publish(weightBeforeStep, step, 1.0)
        }
        onProgress(YumeLocalSelfTestProgress("complete", 1f))
        val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
        val score = if (workload.scoreEnabled) computeScore(rows) else null
        return YumeLocalSelfTestReport(
            rows = rows,
            elapsedMs = elapsedMs,
            mode = mode,
            score = score,
        )
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

    private fun runDiskWrite(cacheDir: File, totalBytes: Int): YumeLocalSelfTestRow {
        val dir = File(cacheDir, "yume-selftest").also { it.mkdirs() }
        val file = File.createTempFile("disk-write-", ".bin", dir)
        val chunk = patternedBytes(64 * KIB)
        val iterations = iterationsFor(totalBytes, chunk.size)
        var guard = 0
        val seconds = try {
            timedSeconds {
                FileOutputStream(file).use { out ->
                    repeat(iterations) {
                        out.write(chunk)
                        guard = guard xor chunk[it and (chunk.size - 1)].toInt()
                    }
                    out.fd.sync()
                }
            }
        } finally {
            file.delete()
        }
        return throughputRow(
            name = "disk-write",
            bytes = iterations.toLong() * chunk.size,
            seconds = seconds,
            detail = "cache file write, 64 KiB chunks, fsync, guard=${guard and 0xFF}",
        )
    }

    private fun runSustainedMix(
        targetMs: Long,
        onProgress: (Double) -> Unit = {},
    ): YumeLocalSelfTestRow {
        val key = Crypto.randomBytes(32)
        val aad = "yume-android-selftest".toByteArray(StandardCharsets.US_ASCII)
        val plaintext = patternedBytes(64 * KIB)
        val batch = packetBatch()
        val encodedPacketBytes = YumePacketBulk.encodedSize(batch)
        val targetNs = targetMs.coerceAtLeast(1L) * 1_000_000L
        val started = System.nanoTime()
        val deadline = started + targetNs
        var nextProgressNs = started
        var guard = 0
        var rounds = 0
        var bytes = 0L
        var now = started
        val seconds = timedSeconds {
            do {
                val encrypted = Crypto.aesGcmEncrypt(key, plaintext, aad)
                val decrypted = Crypto.aesGcmDecrypt(key, encrypted, aad)
                val encoded = YumePacketBulk.encode(batch.copy(sequence = rounds.toLong()))
                val decoded = YumePacketBulk.decode(encoded)
                val derived = Crypto.hkdfSha256(key, "yume-sustain:$rounds".toByteArray(StandardCharsets.US_ASCII), 32)
                guard = guard xor decrypted[rounds and (decrypted.size - 1)].toInt()
                guard = guard xor decoded.packets.last().last().toInt()
                guard = guard xor derived[0].toInt()
                bytes += plaintext.size.toLong() * 2L
                bytes += encodedPacketBytes.toLong() * 2L
                rounds += 1
                now = System.nanoTime()
                if (now >= nextProgressNs) {
                    onProgress((now - started).toDouble() / targetNs.toDouble())
                    nextProgressNs = now + 500_000_000L
                }
            } while (now < deadline)
        }
        onProgress(1.0)
        return throughputRow(
            name = "sustained-mix",
            bytes = bytes,
            seconds = seconds,
            detail = "${targetMs}ms mixed BaseFWX AES-GCM + packet bulk + HKDF, rounds=$rounds, guard=${guard and 0xFF}",
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

    private fun computeScore(rows: List<YumeLocalSelfTestRow>): Long? {
        data class Component(val name: String, val reference: Double, val weight: Double)
        val components = listOf(
            Component("copy-floor", 250_000.0, 100_000.0),
            Component("stream-copy-1", 80_000.0, 150_000.0),
            Component("stream-copy-many", 120_000.0, 150_000.0),
            Component("aes-gcm-encrypt", 6_000.0, 1_300_000.0),
            Component("aes-gcm-decrypt", 6_000.0, 1_300_000.0),
            Component("packet-bulk-encode", 12_000.0, 1_100_000.0),
            Component("packet-bulk-decode", 12_000.0, 1_100_000.0),
            Component("hop-hkdf", 2_500_000.0, 800_000.0),
            Component("disk-write", 3_500.0, 100_000.0),
            Component("sustained-mix", 12_000.0, 3_900_000.0),
        )
        var earned = 0.0
        var possible = 0.0
        for (component in components) {
            val row = rows.firstOrNull { it.ok && it.name == component.name } ?: continue
            val value = row.value ?: continue
            val normalized = scoreScale(value / component.reference)
            earned += normalized * component.weight
            possible += component.weight
        }
        if (possible <= 0.0) return null
        return ((earned / possible).coerceAtLeast(0.0) * REFERENCE_SCORE)
            .roundToLong()
            .coerceAtLeast(0L)
    }

    private data class Workload(
        val copyBytes: Int,
        val streamSingleBytes: Int,
        val streamManyBytes: Int,
        val manyStreams: Int,
        val cryptoBytes: Int,
        val packetCodecBytes: Int,
        val hkdfOps: Int,
        val diskBytes: Int,
        val sustainedMixMs: Long,
        val scoreEnabled: Boolean,
    )

    private data class WorkStep(
        val name: String,
        val weight: Double,
        val block: ((Double) -> Unit) -> YumeLocalSelfTestRow,
    )

    private fun scoreScale(ratio: Double): Double {
        if (ratio <= 0.0) return 0.0
        if (ratio <= 1.0) return ratio.pow(0.95)
        return 1.0 + ln(ratio) * 0.12
    }
}

private fun formatSelfTestRaw(value: Double): String {
    return String.format(Locale.US, "%.6f", value)
}

private fun formatSelfTestInteger(value: Long): String {
    return String.format(Locale.US, "%,d", value)
}

internal fun benchmarkGrade(score: Long): String = when {
    score >= 120_000_000L -> "SSS+"
    score >= 80_000_000L -> "SSS"
    score >= 55_000_000L -> "SSS-"
    score >= 38_000_000L -> "SS+"
    score >= 26_000_000L -> "SS"
    score >= 18_000_000L -> "SS-"
    score >= 12_000_000L -> "S+"
    score >= 8_000_000L -> "S"
    score >= 5_500_000L -> "S-"
    score >= 3_800_000L -> "AAA+"
    score >= 3_000_000L -> "AAA"
    score >= 2_350_000L -> "AAA-"
    score >= 2_000_000L -> "A+"
    score >= 1_750_000L -> "A"
    score >= 1_500_000L -> "A-"
    score >= 1_200_000L -> "B+"
    score >= 900_000L -> "B"
    score >= 650_000L -> "B-"
    score >= 460_000L -> "C+"
    score >= 300_000L -> "C"
    score >= 190_000L -> "C-"
    score >= 140_000L -> "D+"
    score >= 85_000L -> "D"
    score >= 50_000L -> "D-"
    score >= 25_000L -> "F+"
    score >= 10_000L -> "F"
    else -> "F-"
}
