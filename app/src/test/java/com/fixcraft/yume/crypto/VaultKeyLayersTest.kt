package com.fixcraft.yume.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The layering and migration logic, with reversible stand-ins for both wraps. */
class VaultKeyLayersTest {
    private val dataKey = ByteArray(VaultKeyLayers.DATA_KEY_BYTES) { it.toByte() }

    // Stand-ins: the Keystore layer prefixes 'K', the password layer 'P' plus
    // the password. Each unwrap refuses input the matching wrap did not make.
    private val layers = VaultKeyLayers(
        androidWrap = { byteArrayOf('K'.code.toByte()) + it },
        androidUnwrap = { require(it[0] == 'K'.code.toByte()) { "keystore" }; it.copyOfRange(1, it.size) },
        passwordWrap = { key, password -> "P$password:".toByteArray() + key },
        passwordUnwrap = { wrapped, password ->
            val prefix = "P$password:".toByteArray()
            require(wrapped.size > prefix.size && wrapped.copyOf(prefix.size).contentEquals(prefix)) { "password" }
            wrapped.copyOfRange(prefix.size, wrapped.size)
        },
    )

    @Test
    fun theLayeredFormIsKeystoreOverPassword() {
        val stored = layers.protect(dataKey, "correct horse")
        assertEquals('K'.code.toByte(), stored[0])
        var migrated: ByteArray? = null
        assertArrayEquals(dataKey, layers.open(stored, layered = true, password = "correct horse") { migrated = it })
        assertNull("a layered key needs no migration", migrated)
        assertThrows(IllegalArgumentException::class.java) {
            layers.open(stored, layered = true, password = "wrong password") {}
        }
    }

    @Test
    fun aPasswordOnlyKeyMigratesToTheLayeredForm() {
        val legacy = "Pcorrect horse:".toByteArray() + dataKey
        var migrated: ByteArray? = null
        val opened = layers.open(legacy, layered = false, password = "correct horse") { migrated = it.copyOf() }
        assertArrayEquals(dataKey, opened)
        // The replacement opens as the layered form with the same password.
        assertArrayEquals(dataKey, layers.open(migrated!!, layered = true, password = "correct horse") {})
    }

    @Test
    fun aWrongPasswordNeitherMigratesNorFallsBack() {
        val legacy = "Pcorrect horse:".toByteArray() + dataKey
        var migrated = false
        assertThrows(IllegalArgumentException::class.java) {
            layers.open(legacy, layered = false, password = "wrong password") { migrated = true }
        }
        assertEquals(false, migrated)
        // A layered record is never read as a password-only one.
        val layered = layers.protect(dataKey, "correct horse")
        assertThrows(IllegalArgumentException::class.java) {
            layers.open(layered, layered = false, password = "correct horse") {}
        }
    }

    @Test
    fun aFailedMigrationCommitReturnsNoKey() {
        val legacy = "Pcorrect horse:".toByteArray() + dataKey
        assertThrows(IllegalStateException::class.java) {
            layers.open(legacy, layered = false, password = "correct horse") { error("commit failed") }
        }
    }

    @Test
    fun aKeystoreOnlyKeyOpensWithoutAPassword() {
        assertArrayEquals(dataKey, layers.openAndroid(byteArrayOf('K'.code.toByte()) + dataKey))
        assertThrows(IllegalStateException::class.java) { layers.openAndroid(byteArrayOf('K'.code.toByte(), 1, 2)) }
    }
}
