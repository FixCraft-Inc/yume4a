package com.fixcraft.yume.diagnostics

import com.fixcraft.basefwx.Crypto
import com.fixcraft.yume.transport.YumePacketBulk
import java.nio.charset.StandardCharsets
import java.util.Locale
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
            cryptoBytes = 32 * MIB,
            packetCodecBytes = 32 * MIB,
            hkdfOps = 5_000,
        )
    )

    internal fun runSmokeForTest(): YumeLocalSelfTestReport = run(
        Workload(
            copyBytes = 128 * KIB,
            cryptoBytes = 128 * KIB,
            packetCodecBytes = 128 * KIB,
            hkdfOps = 32,
        )
    )

    private fun run(workload: Workload): YumeLocalSelfTestReport {
        val start = System.nanoTime()
        val rows = mutableListOf<YumeLocalSelfTestRow>()
        rows += capture("copy-floor") { runCopyFloor(workload.copyBytes) }
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
        val cryptoBytes: Int,
        val packetCodecBytes: Int,
        val hkdfOps: Int,
    )
}

private fun formatSelfTestRaw(value: Double): String {
    return String.format(Locale.US, "%.6f", value)
}
