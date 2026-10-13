package com.fixcraft.yume.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class VaultBlobFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun envelope(seed: Int) =
        VaultBlobFile.Envelope(ByteArray(12) { (seed + it).toByte() }, ByteArray(48) { (seed * 3 + it).toByte() })

    private fun assertHolds(file: File, expected: VaultBlobFile.Envelope) {
        val read = VaultBlobFile.read(file)
        assertArrayEquals(expected.iv, read.iv)
        assertArrayEquals(expected.ciphertext, read.ciphertext)
    }

    @Test
    fun writeThenReadReturnsTheEnvelope() {
        val file = File(folder.root, "kits.bin")
        VaultBlobFile.write(file, envelope(1))
        assertHolds(file, envelope(1))
        VaultBlobFile.write(file, envelope(2))
        assertHolds(file, envelope(2))
        assertEquals(listOf("kits.bin"), folder.root.list()!!.toList())
    }

    @Test
    fun aFailedSyncOrRenameKeepsThePreviousFile() {
        val file = File(folder.root, "kits.bin")
        VaultBlobFile.write(file, envelope(1))
        assertThrows(IOException::class.java) {
            VaultBlobFile.write(file, envelope(2), sync = { throw IOException("sync") })
        }
        assertHolds(file, envelope(1))
        assertThrows(IOException::class.java) {
            VaultBlobFile.write(file, envelope(3), replace = { _, _ -> throw IOException("rename") })
        }
        assertHolds(file, envelope(1))
        // No temporary file is left behind.
        assertEquals(listOf("kits.bin"), folder.root.list()!!.toList())
    }

    @Test
    fun truncatedPaddedOrMalformedFilesAreRefused() {
        val file = File(folder.root, "kits.bin")
        VaultBlobFile.write(file, envelope(1))
        val good = file.readBytes()
        for (bad in listOf(
            good.copyOf(good.size - 1),
            good + byteArrayOf(0),
            byteArrayOf(0, 0, 0, 2) + good.copyOfRange(4, good.size),
            good.copyOf(27),
        )) {
            file.writeBytes(bad)
            assertThrows(IllegalArgumentException::class.java) { VaultBlobFile.read(file) }
        }
        // A length field larger than the file is refused before allocation.
        file.writeBytes(good.copyOf().also { it[4] = 0x7f })
        assertThrows(IllegalArgumentException::class.java) { VaultBlobFile.read(file) }
    }
}
