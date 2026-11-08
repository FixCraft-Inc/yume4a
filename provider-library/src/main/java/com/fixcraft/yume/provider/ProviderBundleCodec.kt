package com.fixcraft.yume.provider

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * How a kit's files are kept inside one vault blob: a magic, a count, then
 * for each file its flag, path and bytes. The bounds are sealed kit 1's
 * (docs/protocol/SEALED_KIT_1.md in YUME), checked again here because this
 * application writes the paths to disk.
 */
object ProviderBundleCodec {
    const val CONFIG_PATH = "yume.json"
    const val MAX_FILES = 32
    const val MAX_PATH_BYTES = 64
    const val MAX_FILE_BYTES = 256 * 1024
    const val MAX_CONTENT_BYTES = 1024 * 1024

    private val MAGIC = byteArrayOf('Y'.code.toByte(), 'K'.code.toByte(), 'B'.code.toByte(), 1)
    private val COMPONENT = Regex("^[A-Za-z0-9._-]+$")

    /** One or two components of the kit alphabet, neither `.` nor `..`. */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH_BYTES) return false
        val parts = path.split('/')
        if (parts.size > 2) return false
        return parts.all { COMPONENT.matches(it) && it != "." && it != ".." }
    }

    /** Null when the files are not a kit this application stores. */
    fun problem(files: List<ProviderFile>): String? {
        if (files.isEmpty() || files.size > MAX_FILES) return "the kit holds no files or too many"
        var total = 0L
        val seen = HashSet<String>()
        val directories = HashSet<String>()
        for (file in files) {
            if (!isSafePath(file.path)) return "the kit names a path outside its directory"
            if (!seen.add(file.path)) return "the kit names a file twice"
            if (file.bytes.size > MAX_FILE_BYTES) return "a file of the kit is too large"
            total += file.bytes.size
            val slash = file.path.indexOf('/')
            if (slash > 0) directories += file.path.substring(0, slash)
        }
        if (total > MAX_CONTENT_BYTES) return "the kit is too large"
        if (seen.any { it in directories }) return "the kit names a file and a directory alike"
        if (CONFIG_PATH !in seen) return "the kit has no yume.json"
        return null
    }

    /** The caller wipes the returned array once it is stored. */
    fun encode(files: List<ProviderFile>): ByteArray {
        problem(files)?.let { throw IllegalArgumentException(it) }
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(files.size)
        for (file in files) {
            val path = file.path.toByteArray(StandardCharsets.US_ASCII)
            out.write(if (file.executable) 1 else 0)
            out.write(path.size)
            out.write(path)
            out.write(ByteBuffer.allocate(4).putInt(file.bytes.size).array())
            out.write(file.bytes)
        }
        return out.toByteArray()
    }

    /** Throws for bytes [encode] did not write. The caller wipes each file. */
    fun decode(bundle: ByteArray): List<ProviderFile> {
        val files = ArrayList<ProviderFile>()
        try {
            val input = ByteBuffer.wrap(bundle)
            val magic = ByteArray(MAGIC.size)
            require(input.remaining() > MAGIC.size) { "the stored kit is truncated" }
            input.get(magic)
            require(magic.contentEquals(MAGIC)) { "the stored kit has another format" }
            val count = input.get().toInt() and 0xff
            require(count in 1..MAX_FILES) { "the stored kit holds no files or too many" }
            repeat(count) {
                require(input.remaining() >= 2) { "the stored kit is truncated" }
                val flag = input.get().toInt()
                val pathSize = input.get().toInt() and 0xff
                require(flag in 0..1 && pathSize in 1..MAX_PATH_BYTES && input.remaining() >= pathSize + 4) {
                    "the stored kit is damaged"
                }
                val path = ByteArray(pathSize)
                input.get(path)
                val size = input.getInt()
                require(size in 0..MAX_FILE_BYTES && input.remaining() >= size) { "the stored kit is damaged" }
                val bytes = ByteArray(size)
                input.get(bytes)
                files += ProviderFile(String(path, StandardCharsets.US_ASCII), flag == 1, bytes)
            }
            require(!input.hasRemaining()) { "the stored kit has trailing bytes" }
            problem(files)?.let { throw IllegalArgumentException(it) }
            return files
        } catch (error: Throwable) {
            files.forEach { it.bytes.fill(0) }
            throw error
        }
    }
}
