package com.fixcraft.yume.diagnostics

import com.fixcraft.basefwx.Crypto
import com.fixcraft.yume.transport.YumePacketBulk
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val SELF_TEST_GLOBAL_MODEL = "yume-global-v1"
private const val SELF_TEST_ANDROID_MODEL = "yume-android-v1"
private const val SELF_TEST_CHECKSUM_FIELD = "checksum"
private const val SELF_TEST_HKDF_INFO_PREFIX = "yume-hop-v1:"
private const val SELF_TEST_AAD = "yume-android-selftest"
private const val SELF_TEST_SUSTAIN_INFO_PREFIX = "yume-sustain:"
private val memInfoSplit = Regex("\\s+")

private fun selfTestChecksum(value: Int): String = "$SELF_TEST_CHECKSUM_FIELD=${value and 0xFF}"
private fun selfTestAscii(value: String): ByteArray = value.toByteArray(StandardCharsets.US_ASCII)

internal data class YumeLocalSelfTestReport(
    val rows: List<YumeLocalSelfTestRow>,
    val elapsedMs: Long,
    val mode: YumeLocalSelfTestMode,
    val globalScore: Long? = null,
    val leagueScore: Long? = null,
    val profileDetail: String = "",
    val workloadDetail: String = "",
) {
    val score: Long?
        get() = globalScore

    fun toPlainText(): String = buildString {
        appendLine("Yume Android local benchmark")
        appendLine("mode=${mode.id}")
        appendLine("elapsed_ms=$elapsedMs")
        globalScore?.let {
            appendLine("GLOBAL ${formatSelfTestInteger(it)} ${benchmarkGrade(it, BenchmarkScoreTrack.Global)} model=$SELF_TEST_GLOBAL_MODEL")
            appendLine("global_score=$it global_grade=${benchmarkGrade(it, BenchmarkScoreTrack.Global)}")
        }
        leagueScore?.let {
            appendLine("PLATFORM ${formatSelfTestInteger(it)} ${benchmarkGrade(it, BenchmarkScoreTrack.AndroidLeague)} model=$SELF_TEST_ANDROID_MODEL")
            appendLine("platform_score=$it platform_grade=${benchmarkGrade(it, BenchmarkScoreTrack.AndroidLeague)}")
        }
        if (profileDetail.isNotBlank()) {
            appendLine("profile=$profileDetail")
        }
        if (workloadDetail.isNotBlank()) {
            appendLine("workload=$workloadDetail")
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

    private fun mib(value: Int): Int {
        return value.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE / MIB) * MIB
    }

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
            hotThreads = 1,
            memoryBandwidthBytes = 64 * MIB,
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
        computeFullWorkload(detectSystemProfile()),
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
            hotThreads = 1,
            memoryBandwidthBytes = 128 * KIB,
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
        val loadSampler = if (mode == YumeLocalSelfTestMode.Full) {
            SystemLoadSampler().also { it.start() }
        } else {
            null
        }
        val hotThreads = workload.hotThreads.coerceIn(1, 64)
        val steps = mutableListOf(
            WorkStep("copy-floor", 0.25) { _ -> runCopyFloor(workload.copyBytes) },
            WorkStep("stream-copy-1", 0.35) { _ -> runStreamCopy(workload.streamSingleBytes, streams = 1) },
            WorkStep("stream-copy-many", 0.70) { _ -> runStreamCopy(workload.streamManyBytes, streams = workload.manyStreams) },
            WorkStep("memory-bandwidth", 1.00) { _ -> runMemoryBandwidth(workload.memoryBandwidthBytes, hotThreads) },
            WorkStep("aes-gcm-encrypt", 2.20) { _ -> runAesGcmEncrypt(workload.cryptoBytes, hotThreads) },
            WorkStep("aes-gcm-decrypt", 2.20) { _ -> runAesGcmDecrypt(workload.cryptoBytes, hotThreads) },
            WorkStep("packet-bulk-encode", 0.90) { _ -> runPacketBulkEncode(workload.packetCodecBytes, hotThreads) },
            WorkStep("packet-bulk-decode", 0.90) { _ -> runPacketBulkDecode(workload.packetCodecBytes, hotThreads) },
            WorkStep("hop-hkdf", 0.80) { _ -> runHopHkdf(workload.hkdfOps, hotThreads) },
        )
        if (workload.diskBytes > 0 && cacheDir != null) {
            steps += WorkStep("disk-write", 1.40) { _ -> runDiskWrite(cacheDir, workload.diskBytes) }
        }
        if (workload.sustainedMixMs > 0) {
            steps += WorkStep("sustained-mix", workload.sustainedMixMs.toDouble() / 1_000.0) { progress ->
                runSustainedMix(workload.sustainedMixMs, hotThreads, progress)
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
        loadSampler?.stop()?.let { rows += it }
        onProgress(YumeLocalSelfTestProgress("complete", 1f))
        val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
        val globalScore = if (workload.scoreEnabled) computeScore(rows, global = true) else null
        val leagueScore = if (workload.scoreEnabled) computeScore(rows, global = false) else null
        return YumeLocalSelfTestReport(
            rows = rows,
            elapsedMs = elapsedMs,
            mode = mode,
            globalScore = globalScore,
            leagueScore = leagueScore,
            profileDetail = workload.profileDetail,
            workloadDetail = workload.workloadDetail,
        )
    }

    private data class AndroidSystemProfile(
        val logicalCpus: Int,
        val totalMemoryMiB: Int,
        val availableMemoryMiB: Int,
        val heapMaxMemoryMiB: Int,
    )

    private data class SystemLoadSample(
        val cpuPercent: Double? = null,
        val memoryUsedPercent: Double? = null,
        val temperatureC: Double? = null,
    )

    private data class CpuTicks(
        val idle: Long,
        val total: Long,
    )

    private class SystemLoadSampler {
        private val running = AtomicBoolean(false)
        private val samples = mutableListOf<SystemLoadSample>()
        private var startedNs: Long = 0L
        private var worker: Thread? = null

        fun start() {
            if (running.getAndSet(true)) return
            startedNs = System.nanoTime()
            worker = thread(start = true, name = "yume-selftest-load") {
                var previousCpu = readCpuTicks()
                while (running.get()) {
                    Thread.sleep(1_000L)
                    val currentCpu = readCpuTicks()
                    val cpuPercent = if (previousCpu != null && currentCpu != null) {
                        cpuLoadPercent(previousCpu, currentCpu)
                    } else {
                        null
                    }
                    previousCpu = currentCpu
                    val memInfo = readProcMemInfo()
                    val memoryUsed = if (memInfo.first > 0 && memInfo.second in 0..memInfo.first) {
                        (1.0 - (memInfo.second.toDouble() / memInfo.first.toDouble())) * 100.0
                    } else {
                        null
                    }
                    val tempC = readMaxThermalC()
                    synchronized(samples) {
                        samples += SystemLoadSample(cpuPercent, memoryUsed, tempC)
                    }
                }
            }
        }

        fun stop(): YumeLocalSelfTestRow {
            running.set(false)
            worker?.join(2_500L)
            val snapshot = synchronized(samples) { samples.toList() }
            val cpu = miniStats(snapshot.mapNotNull { it.cpuPercent })
            val memory = miniStats(snapshot.mapNotNull { it.memoryUsedPercent })
            val temp = miniStats(snapshot.mapNotNull { it.temperatureC })
            val seconds = ((System.nanoTime() - startedNs) / 1_000_000_000.0).coerceAtLeast(0.0)
            val metric = if (cpu.max > 0.0 || cpu.median > 0.0) {
                "${formatNumber(cpu.mean)}% avg CPU"
            } else {
                "unavailable"
            }
            val detail = buildString {
                append("samples=${snapshot.size} ")
                appendLoadStat("cpu", cpu, "%")
                append(' ')
                appendLoadStat("mem_used", memory, "%")
                append(' ')
                appendLoadStat("temp_c", temp, "C")
            }
            return YumeLocalSelfTestRow(
                name = "system-load",
                metric = metric,
                detail = detail,
                ok = snapshot.isNotEmpty(),
                value = cpu.mean.takeIf { cpu.max > 0.0 || cpu.median > 0.0 },
                unit = "%",
                operations = snapshot.size,
                seconds = seconds,
            )
        }
    }

    private fun detectSystemProfile(): AndroidSystemProfile {
        val runtime = Runtime.getRuntime()
        val memInfo = readProcMemInfo()
        val heapMaxMiB = (runtime.maxMemory() / MIB.toLong())
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return AndroidSystemProfile(
            logicalCpus = runtime.availableProcessors().coerceAtLeast(1),
            totalMemoryMiB = memInfo.first,
            availableMemoryMiB = memInfo.second,
            heapMaxMemoryMiB = heapMaxMiB,
        )
    }

    private fun readProcMemInfo(): Pair<Int, Int> {
        var totalMiB = 0
        var availableMiB = 0
        runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("MemTotal:") -> totalMiB = memInfoLineToMiB(line)
                        line.startsWith("MemAvailable:") -> availableMiB = memInfoLineToMiB(line)
                    }
                }
            }
        }
        if (availableMiB == 0 && totalMiB > 0) {
            availableMiB = totalMiB / 2
        }
        return totalMiB to availableMiB
    }

    private fun memInfoLineToMiB(line: String): Int {
        val kib = line.split(memInfoSplit)
            .firstOrNull { token -> token.all { it.isDigit() } }
            ?.toLongOrNull()
            ?: return 0
        return (kib / 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun readCpuTicks(): CpuTicks? {
        val line = runCatching {
            File("/proc/stat").useLines { lines -> lines.firstOrNull().orEmpty() }
        }.getOrNull() ?: return null
        val parts = line.trim().split(memInfoSplit)
        if (parts.size < 5 || parts.first() != "cpu") return null
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
        val total = values.fold(0L) { acc, value -> acc + value }
        if (total <= 0L) return null
        return CpuTicks(idle = idle, total = total)
    }

    private fun cpuLoadPercent(before: CpuTicks, after: CpuTicks): Double? {
        if (after.total <= before.total || after.idle < before.idle) return null
        val totalDelta = (after.total - before.total).toDouble()
        if (totalDelta <= 0.0) return null
        val idleDelta = (after.idle - before.idle).toDouble()
        return ((1.0 - (idleDelta / totalDelta)) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun readMaxThermalC(): Double? {
        val zones = runCatching { File("/sys/class/thermal").listFiles() }.getOrNull()
            ?: return null
        var maxTemp: Double? = null
        for (zone in zones) {
            val raw = runCatching { File(zone, "temp").readText().trim().toDouble() }.getOrNull()
                ?: continue
            val celsius = if (raw > 1_000.0) raw / 1_000.0 else raw
            if (celsius in 1.0..140.0) {
                maxTemp = maxOf(maxTemp ?: celsius, celsius)
            }
        }
        return maxTemp
    }

    private fun computeFullWorkload(profile: AndroidSystemProfile): Workload {
        val usableMemoryMiB = when {
            profile.availableMemoryMiB > 0 -> profile.availableMemoryMiB
            profile.totalMemoryMiB > 0 -> profile.totalMemoryMiB / 2
            else -> 2048
        }
        val hotThreads = profile.logicalCpus.coerceIn(1, 64)
        val heapBudgetMiB = profile.heapMaxMemoryMiB.coerceAtLeast(256)
        val memoryMiB = (usableMemoryMiB / 8).coerceIn(512, minOf(1536, heapBudgetMiB * 2))
        val parallelPayloadMiB = (hotThreads * 192).coerceIn(512, 2048)
        val sustainedMs = when {
            hotThreads >= 8 && usableMemoryMiB >= 4096 -> 120_000L
            hotThreads >= 6 -> 100_000L
            else -> 90_000L
        }
        val copyBytes = mib((usableMemoryMiB / 32).coerceIn(512, 1024))
        val streamSingleBytes = mib((usableMemoryMiB / 64).coerceIn(256, 1024))
        val streamManyBytes = mib((usableMemoryMiB / 16).coerceIn(1024, 2048))
        val memoryBandwidthBytes = mib(memoryMiB)
        val cryptoBytes = mib(parallelPayloadMiB)
        val packetCodecBytes = mib(parallelPayloadMiB)
        val hkdfOps = (hotThreads * 25_000).coerceIn(250_000, 1_000_000)
        val diskBytes = mib((usableMemoryMiB / 64).coerceIn(256, 1024))
        val profileDetail = "cpus=${profile.logicalCpus} " +
            "mem_mib=${profile.totalMemoryMiB} " +
            "available_mib=$usableMemoryMiB " +
            "heap_mib=${profile.heapMaxMemoryMiB}"
        val workloadDetail = "threads=$hotThreads " +
            "memory_mib=${memoryBandwidthBytes / MIB} " +
            "crypto_mib=${cryptoBytes / MIB} " +
            "packet_mib=${packetCodecBytes / MIB} " +
            "disk_mib=${diskBytes / MIB} " +
            "sustained_s=${sustainedMs / 1000L}"
        return Workload(
            copyBytes = copyBytes,
            streamSingleBytes = streamSingleBytes,
            streamManyBytes = streamManyBytes,
            manyStreams = 64,
            hotThreads = hotThreads,
            memoryBandwidthBytes = memoryBandwidthBytes,
            cryptoBytes = cryptoBytes,
            packetCodecBytes = packetCodecBytes,
            hkdfOps = hkdfOps,
            diskBytes = diskBytes,
            sustainedMixMs = sustainedMs,
            scoreEnabled = true,
            profileDetail = profileDetail,
            workloadDetail = workloadDetail,
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
            detail = "64 KiB array copy, ${selfTestChecksum(guard)}",
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
            detail = "streams=$streamCount per_stream_mib_s min=${formatNumber(stats.min)} median=${formatNumber(stats.median)} p95=${formatNumber(stats.p95)} max=${formatNumber(stats.max)} instability=${formatNumber(instability)}% ${selfTestChecksum(guard)}",
        )
    }

    private fun runMemoryBandwidth(totalBytes: Int, workerCount: Int): YumeLocalSelfTestRow {
        val perWorkerBytes = splitBytes(totalBytes, workerCount)
        val chunkSize = if (totalBytes >= 512 * MIB) 2 * MIB else 256 * KIB
        val startFlag = AtomicBoolean(false)
        val copiedByWorker = LongArray(workerCount)
        val guards = IntArray(workerCount)
        val workers = List(workerCount) { workerIndex ->
            thread(start = false, name = "yume-selftest-memory-$workerIndex") {
                val src = patternedBytes(chunkSize, seed = 41 + workerIndex)
                val dst = ByteArray(src.size)
                while (!startFlag.get()) {
                    Thread.yield()
                }
                val target = perWorkerBytes[workerIndex]
                var copied = 0
                var guard = 0
                while (copied < target) {
                    val chunk = minOf(src.size, target - copied)
                    src.copyInto(dst, endIndex = chunk)
                    guard = guard xor dst[(copied / max(1, chunk)) and (dst.size - 1)].toInt()
                    copied += chunk
                }
                copiedByWorker[workerIndex] = copied.toLong()
                guards[workerIndex] = guard
            }
        }
        workers.forEach { it.start() }
        val started = System.nanoTime()
        startFlag.set(true)
        workers.forEach { it.join() }
        val seconds = (System.nanoTime() - started).toDouble() / 1_000_000_000.0
        val copied = copiedByWorker.sum()
        val guard = guards.fold(0) { acc, value -> acc xor value } and 0xFF
        return throughputRow(
            name = "memory-bandwidth",
            bytes = copied,
            seconds = seconds,
            detail = "parallel memory copy, threads=$workerCount, chunk_kib=${chunkSize / KIB}, ${selfTestChecksum(guard)}",
        )
    }

    private fun runAesGcmEncrypt(totalBytes: Int, workerCount: Int): YumeLocalSelfTestRow {
        val perWorkerBytes = splitBytes(totalBytes, workerCount)
        val startFlag = AtomicBoolean(false)
        val bytesByWorker = LongArray(workerCount)
        val guards = IntArray(workerCount)
        val workers = List(workerCount) { workerIndex ->
            thread(start = false, name = "yume-selftest-aead-enc-$workerIndex") {
                val key = Crypto.randomBytes(32)
                val aad = selfTestAscii(SELF_TEST_AAD)
                val plaintext = patternedBytes(64 * KIB, seed = 21 + workerIndex)
                val iterations = iterationsFor(perWorkerBytes[workerIndex], plaintext.size)
                var localGuard = 0
                while (!startFlag.get()) {
                    Thread.yield()
                }
                repeat(iterations) {
                    val encrypted = Crypto.aesGcmEncrypt(key, plaintext, aad)
                    localGuard = localGuard xor encrypted[encrypted.lastIndex].toInt()
                }
                bytesByWorker[workerIndex] = iterations.toLong() * plaintext.size
                guards[workerIndex] = localGuard
            }
        }
        workers.forEach { it.start() }
        val started = System.nanoTime()
        startFlag.set(true)
        workers.forEach { it.join() }
        val seconds = (System.nanoTime() - started).toDouble() / 1_000_000_000.0
        val bytes = bytesByWorker.sum()
        val guard = guards.fold(0) { acc, value -> acc xor value }
        return throughputRow(
            name = "aes-gcm-encrypt",
            bytes = bytes,
            seconds = seconds,
            detail = "BaseFWX AES-GCM, 64 KiB chunks, threads=$workerCount, ${selfTestChecksum(guard)}",
        )
    }

    private fun runAesGcmDecrypt(totalBytes: Int, workerCount: Int): YumeLocalSelfTestRow {
        val perWorkerBytes = splitBytes(totalBytes, workerCount)
        val startFlag = AtomicBoolean(false)
        val bytesByWorker = LongArray(workerCount)
        val guards = IntArray(workerCount)
        val workers = List(workerCount) { workerIndex ->
            thread(start = false, name = "yume-selftest-aead-dec-$workerIndex") {
                val key = Crypto.randomBytes(32)
                val aad = selfTestAscii(SELF_TEST_AAD)
                val plaintext = patternedBytes(64 * KIB, seed = 21 + workerIndex)
                val encrypted = Crypto.aesGcmEncrypt(key, plaintext, aad)
                val iterations = iterationsFor(perWorkerBytes[workerIndex], plaintext.size)
                var localGuard = 0
                while (!startFlag.get()) {
                    Thread.yield()
                }
                repeat(iterations) {
                    val decrypted = Crypto.aesGcmDecrypt(key, encrypted, aad)
                    localGuard = localGuard xor decrypted[it and (decrypted.size - 1)].toInt()
                }
                bytesByWorker[workerIndex] = iterations.toLong() * plaintext.size
                guards[workerIndex] = localGuard
            }
        }
        workers.forEach { it.start() }
        val started = System.nanoTime()
        startFlag.set(true)
        workers.forEach { it.join() }
        val seconds = (System.nanoTime() - started).toDouble() / 1_000_000_000.0
        val bytes = bytesByWorker.sum()
        val guard = guards.fold(0) { acc, value -> acc xor value }
        return throughputRow(
            name = "aes-gcm-decrypt",
            bytes = bytes,
            seconds = seconds,
            detail = "BaseFWX AES-GCM, 64 KiB chunks, threads=$workerCount, ${selfTestChecksum(guard)}",
        )
    }

    private fun runPacketBulkEncode(totalBytes: Int, workerCount: Int): YumeLocalSelfTestRow {
        val perWorkerBytes = splitBytes(totalBytes, workerCount)
        val startFlag = AtomicBoolean(false)
        val bytesByWorker = LongArray(workerCount)
        val guards = IntArray(workerCount)
        val workers = List(workerCount) { workerIndex ->
            thread(start = false, name = "yume-selftest-packet-enc-$workerIndex") {
                val batch = packetBatch()
                val encodedBytes = YumePacketBulk.encodedSize(batch)
                val iterations = iterationsFor(perWorkerBytes[workerIndex], encodedBytes)
                var localGuard = 0
                while (!startFlag.get()) {
                    Thread.yield()
                }
                repeat(iterations) {
                    val sequence = (workerIndex.toLong() shl 48) or it.toLong()
                    val encoded = YumePacketBulk.encode(batch.copy(sequence = sequence))
                    localGuard = localGuard xor encoded[encoded.lastIndex].toInt()
                }
                bytesByWorker[workerIndex] = iterations.toLong() * encodedBytes
                guards[workerIndex] = localGuard
            }
        }
        workers.forEach { it.start() }
        val started = System.nanoTime()
        startFlag.set(true)
        workers.forEach { it.join() }
        val seconds = (System.nanoTime() - started).toDouble() / 1_000_000_000.0
        val bytes = bytesByWorker.sum()
        val guard = guards.fold(0) { acc, value -> acc xor value }
        return throughputRow(
            name = "packet-bulk-encode",
            bytes = bytes,
            seconds = seconds,
            detail = "${YumePacketBulk.MAX_PACKETS_PER_BATCH} packets/batch, threads=$workerCount, ${selfTestChecksum(guard)}",
        )
    }

    private fun runPacketBulkDecode(totalBytes: Int, workerCount: Int): YumeLocalSelfTestRow {
        val perWorkerBytes = splitBytes(totalBytes, workerCount)
        val startFlag = AtomicBoolean(false)
        val bytesByWorker = LongArray(workerCount)
        val guards = IntArray(workerCount)
        val workers = List(workerCount) { workerIndex ->
            thread(start = false, name = "yume-selftest-packet-dec-$workerIndex") {
                val batch = packetBatch()
                val encoded = YumePacketBulk.encode(batch)
                val iterations = iterationsFor(perWorkerBytes[workerIndex], encoded.size)
                var localGuard = 0
                while (!startFlag.get()) {
                    Thread.yield()
                }
                repeat(iterations) {
                    val decoded = YumePacketBulk.decode(encoded)
                    localGuard = localGuard xor decoded.packets.last().last().toInt()
                }
                bytesByWorker[workerIndex] = iterations.toLong() * encoded.size
                guards[workerIndex] = localGuard
            }
        }
        workers.forEach { it.start() }
        val started = System.nanoTime()
        startFlag.set(true)
        workers.forEach { it.join() }
        val seconds = (System.nanoTime() - started).toDouble() / 1_000_000_000.0
        val bytes = bytesByWorker.sum()
        val guard = guards.fold(0) { acc, value -> acc xor value }
        return throughputRow(
            name = "packet-bulk-decode",
            bytes = bytes,
            seconds = seconds,
            detail = "${YumePacketBulk.MAX_PACKETS_PER_BATCH} packets/batch, threads=$workerCount, ${selfTestChecksum(guard)}",
        )
    }

    private fun runHopHkdf(ops: Int, workerCount: Int): YumeLocalSelfTestRow {
        val perWorkerOps = splitBytes(max(1, ops), workerCount)
        val startFlag = AtomicBoolean(false)
        val opsByWorker = IntArray(workerCount)
        val guards = IntArray(workerCount)
        val workers = List(workerCount) { workerIndex ->
            thread(start = false, name = "yume-selftest-hkdf-$workerIndex") {
                val key = Crypto.randomBytes(32)
                val count = perWorkerOps[workerIndex]
                var localGuard = 0
                while (!startFlag.get()) {
                    Thread.yield()
                }
                repeat(count) {
                    val info = "$SELF_TEST_HKDF_INFO_PREFIX$workerIndex:$it".toByteArray(StandardCharsets.US_ASCII)
                    val derived = Crypto.hkdfSha256(key, info, 32)
                    localGuard = localGuard xor derived[0].toInt()
                }
                opsByWorker[workerIndex] = count
                guards[workerIndex] = localGuard
            }
        }
        workers.forEach { it.start() }
        val started = System.nanoTime()
        startFlag.set(true)
        workers.forEach { it.join() }
        val seconds = (System.nanoTime() - started).toDouble() / 1_000_000_000.0
        val count = opsByWorker.sum()
        val guard = guards.fold(0) { acc, value -> acc xor value }
        val opsPerSecond = count / seconds.coerceAtLeast(0.000_001)
        return YumeLocalSelfTestRow(
            name = "hop-hkdf",
            metric = "${formatNumber(opsPerSecond)} ops/s",
            detail = "$count derived hop keys, threads=$workerCount, ${selfTestChecksum(guard)}",
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
            detail = "cache file write, 64 KiB chunks, fsync, ${selfTestChecksum(guard)}",
        )
    }

    private fun runSustainedMix(
        targetMs: Long,
        workerCount: Int,
        onProgress: (Double) -> Unit = {},
    ): YumeLocalSelfTestRow {
        val startFlag = AtomicBoolean(false)
        val bytesByWorker = LongArray(workerCount)
        val roundsByWorker = LongArray(workerCount)
        val guards = IntArray(workerCount)
        val errors = arrayOfNulls<Throwable>(workerCount)
        val targetNs = targetMs.coerceAtLeast(1L) * 1_000_000L
        var started = 0L
        var deadline = 0L
        val workers = List(workerCount) { workerIndex ->
            thread(start = false, name = "yume-selftest-sustain-$workerIndex") {
                try {
                    val key = Crypto.randomBytes(32)
                    val aad = selfTestAscii(SELF_TEST_AAD)
                    val plaintext = patternedBytes(64 * KIB, seed = 31 + workerIndex)
                    val batch = packetBatch()
                    val encodedPacketBytes = YumePacketBulk.encodedSize(batch)
                    var localGuard = 0
                    var localRounds = 0L
                    var localBytes = 0L
                    while (!startFlag.get()) {
                        Thread.yield()
                    }
                    while (System.nanoTime() < deadline) {
                        val encrypted = Crypto.aesGcmEncrypt(key, plaintext, aad)
                        val decrypted = Crypto.aesGcmDecrypt(key, encrypted, aad)
                        val encoded = YumePacketBulk.encode(
                            batch.copy(sequence = (workerIndex.toLong() shl 48) or localRounds),
                        )
                        val decoded = YumePacketBulk.decode(encoded)
                        val info = selfTestAscii("$SELF_TEST_SUSTAIN_INFO_PREFIX$workerIndex:$localRounds")
                        val derived = Crypto.hkdfSha256(key, info, 32)
                        localGuard = localGuard xor decrypted[(localRounds and (decrypted.size - 1).toLong()).toInt()].toInt()
                        localGuard = localGuard xor decoded.packets.last().last().toInt()
                        localGuard = localGuard xor derived[0].toInt()
                        localBytes += plaintext.size.toLong() * 2L
                        localBytes += encodedPacketBytes.toLong() * 2L
                        localRounds += 1L
                    }
                    bytesByWorker[workerIndex] = localBytes
                    roundsByWorker[workerIndex] = localRounds
                    guards[workerIndex] = localGuard
                } catch (throwable: Throwable) {
                    errors[workerIndex] = throwable
                }
            }
        }
        val seconds = timedSeconds {
            workers.forEach { it.start() }
            started = System.nanoTime()
            deadline = started + targetNs
            var nextProgressNs = started
            startFlag.set(true)
            while (System.nanoTime() < deadline) {
                val now = System.nanoTime()
                if (now >= nextProgressNs) {
                    onProgress((now - started).toDouble() / targetNs.toDouble())
                    nextProgressNs = now + 500_000_000L
                }
                Thread.sleep(50L)
            }
            workers.forEach { it.join() }
        }
        errors.firstOrNull { it != null }?.let { throw it }
        val bytes = bytesByWorker.sum()
        val rounds = roundsByWorker.sum()
        val guard = guards.fold(0) { acc, value -> acc xor value }
        onProgress(1.0)
        return throughputRow(
            name = "sustained-mix",
            bytes = bytes,
            seconds = seconds,
            detail = "${targetMs}ms mixed BaseFWX AES-GCM + packet bulk + HKDF, rounds=$rounds, threads=$workerCount, ${selfTestChecksum(guard)}",
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
        val mean: Double,
        val median: Double,
        val p95: Double,
        val max: Double,
    )

    private fun miniStats(values: List<Double>): MiniStats {
        if (values.isEmpty()) return MiniStats(0.0, 0.0, 0.0, 0.0, 0.0)
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
            mean = sorted.average(),
            median = median,
            p95 = pick(0.95),
            max = sorted.last(),
        )
    }

    private fun StringBuilder.appendLoadStat(name: String, stats: MiniStats, unit: String) {
        if (stats.max <= 0.0 && stats.median <= 0.0) {
            append(name)
            append("=unavailable")
            return
        }
        append(name)
        append("_avg=")
        append(formatNumber(stats.mean))
        append(unit)
        append(' ')
        append(name)
        append("_max=")
        append(formatNumber(stats.max))
        append(unit)
    }

    private fun timedSeconds(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start).toDouble() / 1_000_000_000.0
    }

    private fun formatNumber(value: Double): String {
        return String.format(Locale.US, "%,.1f", value)
    }

    private fun computeScore(rows: List<YumeLocalSelfTestRow>, global: Boolean): Long? {
        data class Component(val name: String, val reference: Double, val weight: Double)
        val components = listOf(
            Component("copy-floor", 250_000.0, 100_000.0),
            Component("stream-copy-1", 80_000.0, 150_000.0),
            Component("stream-copy-many", 120_000.0, 150_000.0),
            Component("memory-bandwidth", 180_000.0, 600_000.0),
            Component("aes-gcm-encrypt", 6_000.0, 1_300_000.0),
            Component("aes-gcm-decrypt", 6_000.0, 1_300_000.0),
            Component("packet-bulk-encode", 12_000.0, 1_100_000.0),
            Component("packet-bulk-decode", 12_000.0, 1_100_000.0),
            Component("hop-hkdf", 2_500_000.0, 800_000.0),
            Component("disk-write", 3_500.0, 100_000.0),
            Component("sustained-mix", 14_000.0, 4_300_000.0),
        )
        var earned = 0.0
        var possible = 0.0
        for (component in components) {
            val row = rows.firstOrNull { it.ok && it.name == component.name } ?: continue
            val value = row.value ?: continue
            val reference = if (global) component.reference * 6.0 else component.reference
            val normalized = scoreScale(value / reference)
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
        val hotThreads: Int,
        val memoryBandwidthBytes: Int,
        val cryptoBytes: Int,
        val packetCodecBytes: Int,
        val hkdfOps: Int,
        val diskBytes: Int,
        val sustainedMixMs: Long,
        val scoreEnabled: Boolean,
        val profileDetail: String = "",
        val workloadDetail: String = "",
    )

    private data class WorkStep(
        val name: String,
        val weight: Double,
        val block: ((Double) -> Unit) -> YumeLocalSelfTestRow,
    )

    private fun scoreScale(ratio: Double): Double {
        if (ratio <= 0.0) return 0.0
        if (ratio <= 1.0) return ratio.pow(0.95)
        return ratio.pow(1.15)
    }
}

private fun formatSelfTestRaw(value: Double): String {
    return String.format(Locale.US, "%.6f", value)
}

private fun formatSelfTestInteger(value: Long): String {
    return String.format(Locale.US, "%,d", value)
}

internal enum class BenchmarkScoreTrack {
    Global,
    AndroidLeague,
}

private data class BenchmarkGradeCutoff(
    val global: Long,
    val league: Long,
    val grade: String,
)

private val benchmarkGradeCutoffs = listOf(
    BenchmarkGradeCutoff(50_000_000L, 24_000_000L, "SSS+"),
    BenchmarkGradeCutoff(35_000_000L, 17_000_000L, "SSS"),
    BenchmarkGradeCutoff(24_000_000L, 12_000_000L, "SSS-"),
    BenchmarkGradeCutoff(17_000_000L, 8_500_000L, "SS+"),
    BenchmarkGradeCutoff(12_000_000L, 6_000_000L, "SS"),
    BenchmarkGradeCutoff(8_500_000L, 4_200_000L, "SS-"),
    BenchmarkGradeCutoff(6_000_000L, 3_000_000L, "S+"),
    BenchmarkGradeCutoff(4_200_000L, 2_200_000L, "S"),
    BenchmarkGradeCutoff(3_000_000L, 1_600_000L, "S-"),
    BenchmarkGradeCutoff(2_100_000L, 1_150_000L, "AAA+"),
    BenchmarkGradeCutoff(1_500_000L, 850_000L, "AAA"),
    BenchmarkGradeCutoff(1_050_000L, 620_000L, "AAA-"),
    BenchmarkGradeCutoff(750_000L, 450_000L, "A+"),
    BenchmarkGradeCutoff(520_000L, 320_000L, "A"),
    BenchmarkGradeCutoff(360_000L, 230_000L, "A-"),
    BenchmarkGradeCutoff(250_000L, 165_000L, "B+"),
    BenchmarkGradeCutoff(175_000L, 115_000L, "B"),
    BenchmarkGradeCutoff(120_000L, 80_000L, "B-"),
    BenchmarkGradeCutoff(80_000L, 55_000L, "C+"),
    BenchmarkGradeCutoff(50_000L, 36_000L, "C"),
    BenchmarkGradeCutoff(32_000L, 23_000L, "C-"),
    BenchmarkGradeCutoff(20_000L, 15_000L, "D+"),
    BenchmarkGradeCutoff(12_000L, 9_000L, "D"),
    BenchmarkGradeCutoff(7_000L, 5_000L, "D-"),
    BenchmarkGradeCutoff(3_500L, 2_500L, "F+"),
    BenchmarkGradeCutoff(1_500L, 1_000L, "F"),
)

internal fun benchmarkGrade(score: Long, track: BenchmarkScoreTrack = BenchmarkScoreTrack.Global): String {
    for (cutoff in benchmarkGradeCutoffs) {
        val threshold = if (track == BenchmarkScoreTrack.Global) cutoff.global else cutoff.league
        if (score >= threshold) {
            return cutoff.grade
        }
    }
    return "F-"
}
