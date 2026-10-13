package com.fixcraft.yume.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The version-1 envelope is unchanged; reject lengths before allocating. */
internal object VaultBlobFile {
    const val MAX_BLOB_BYTES = 16 * 1024 * 1024
    data class Envelope(val iv: ByteArray, val ciphertext: ByteArray)

    fun read(file: File): Envelope = FileInputStream(file).use { stream ->
        val size = stream.channel.size()
        require(size in 28L..MAX_BLOB_BYTES.toLong()) { "Invalid app vault blob size" }
        val input = DataInputStream(stream.buffered())
        require(input.readInt() == 1) { "Unsupported app vault version" }
        val ivLength = input.readInt()
        require(ivLength in 1..32 && size >= 12L + ivLength + 16) { "Invalid app vault IV" }
        val iv = ByteArray(ivLength)
        input.readFully(iv)
        val ciphertextLength = input.readInt()
        require(ciphertextLength >= 16 && ciphertextLength.toLong() == size - 12L - ivLength) {
            "Invalid app vault payload"
        }
        val ciphertext = ByteArray(ciphertextLength)
        input.readFully(ciphertext)
        require(input.read() == -1) { "Invalid app vault trailing data" }
        Envelope(iv, ciphertext)
    }

    /** The previous file remains intact on write, sync or rename failure. */
    fun write(
        file: File,
        envelope: Envelope,
        sync: (FileOutputStream) -> Unit = { it.fd.sync() },
        replace: (File, File) -> Unit = { temporary, target ->
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        },
    ) {
        require(envelope.iv.size in 1..32 && envelope.ciphertext.size >= 16)
        require(12L + envelope.iv.size + envelope.ciphertext.size <= MAX_BLOB_BYTES)
        val temporary = File.createTempFile(".${file.name}.", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream.buffered())
                output.writeInt(1)
                output.writeInt(envelope.iv.size)
                output.write(envelope.iv)
                output.writeInt(envelope.ciphertext.size)
                output.write(envelope.ciphertext)
                output.flush()
                sync(stream)
            }
            replace(temporary, file)
        } finally {
            temporary.delete()
        }
    }
}
