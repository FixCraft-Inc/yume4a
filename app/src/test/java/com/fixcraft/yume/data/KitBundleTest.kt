package com.fixcraft.yume.data

import com.fixcraft.yume.core.KitFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KitBundleTest {
    private fun kit() = listOf(
        KitFile("credentials/admission.key", false, ByteArray(32) { it.toByte() }),
        KitFile("start-client", true, "#!/bin/sh\n".toByteArray()),
        KitFile("yume.json", false, "{}".toByteArray()),
    )

    @Test
    fun aKitSurvivesTheRoundTrip() {
        val decoded = KitBundle.decode(KitBundle.encode(kit()))
        assertEquals(kit().map { it.path }, decoded.map { it.path })
        assertEquals(kit().map { it.executable }, decoded.map { it.executable })
        kit().zip(decoded).forEach { (wanted, got) -> assertArrayEquals(wanted.bytes, got.bytes) }
    }

    @Test
    fun onlyPathsInsideTheKitAreSafe() {
        for (path in listOf("yume.json", "credentials/client-access.psk", "a_b-c.d/e.f")) {
            assertTrue(path, KitBundle.isSafePath(path))
        }
        for (path in listOf(
            "", "/etc/passwd", "../yume.json", "credentials/../x", "a/b/c", "a//b", "a/", ".", "..",
            "credentials/.", "name with space", "nul\u0000", "x".repeat(65),
        )) {
            assertFalse(path, KitBundle.isSafePath(path))
        }
    }

    @Test
    fun aKitThatIsNotOneIsNamed() {
        assertNull(KitBundle.problem(kit()))
        assertNotNull(KitBundle.problem(emptyList()))
        assertNotNull(KitBundle.problem(kit().filterNot { it.path == "yume.json" }))
        assertNotNull(KitBundle.problem(kit() + KitFile("../escape", false, ByteArray(1))))
        assertNotNull(KitBundle.problem(kit() + KitFile("yume.json", false, ByteArray(1))))
        assertNotNull(KitBundle.problem(kit() + KitFile("credentials", false, ByteArray(1))))
        assertNotNull(KitBundle.problem(kit() + KitFile("big", false, ByteArray(KitBundle.MAX_FILE_BYTES + 1))))
        val many = (0 until KitBundle.MAX_FILES).map { KitFile("f$it", false, ByteArray(1)) }
        assertNotNull(KitBundle.problem(many + KitFile("yume.json", false, ByteArray(1))))
        val heavy = (0 until 5).map { KitFile("f$it", false, ByteArray(KitBundle.MAX_FILE_BYTES)) }
        assertNotNull(KitBundle.problem(heavy + KitFile("yume.json", false, ByteArray(1))))
    }

    @Test
    fun bytesThatWereNotEncodedAreRefused() {
        val encoded = KitBundle.encode(kit())
        assertThrows(IllegalArgumentException::class.java) { KitBundle.decode(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { KitBundle.decode(encoded.copyOf(encoded.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { KitBundle.decode(encoded + 0) }
        assertThrows(IllegalArgumentException::class.java) {
            KitBundle.decode(encoded.copyOf().also { it[0] = 'X'.code.toByte() })
        }
        // A stored path that leaves the kit is refused even when well formed.
        val escaped = encoded.copyOf()
        val at = String(escaped, Charsets.ISO_8859_1).indexOf("start-client")
        "../../x.sh..".toByteArray().copyInto(escaped, at)
        assertThrows(IllegalArgumentException::class.java) { KitBundle.decode(escaped) }
    }
}
