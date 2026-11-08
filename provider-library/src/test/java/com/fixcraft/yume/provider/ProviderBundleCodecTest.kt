package com.fixcraft.yume.provider

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderBundleCodecTest {
    @Test fun existingStoredBundleBytesAndFileFlagsRemainExact() {
        val files = listOf(ProviderFile("yume.json", false, byteArrayOf(0x7b, 0x7d)))
        val expected = byteArrayOf(0x59, 0x4b, 0x42, 1, 1, 0, 9,
            0x79, 0x75, 0x6d, 0x65, 0x2e, 0x6a, 0x73, 0x6f, 0x6e, 0, 0, 0, 2, 0x7b, 0x7d)
        val encoded = ProviderBundleCodec.encode(files)
        assertArrayEquals(expected, encoded)
        val decoded = ProviderBundleCodec.decode(expected)
        try {
            assertEquals(1, decoded.size)
            assertEquals("yume.json", decoded.single().path)
            assertEquals(false, decoded.single().executable)
            assertArrayEquals(files.single().bytes, decoded.single().bytes)
        } finally { decoded.forEach { it.bytes.fill(0) }; encoded.fill(0); files.forEach { it.bytes.fill(0) } }
    }

    @Test fun traversalDuplicateAndConflictingPathsFailBeforeStaging() {
        for (path in listOf("../secret", "/secret", "a/../secret", "a/b/c")) {
            assertThrows(Exception::class.java) {
                ProviderBundleCodec.encode(listOf(ProviderFile("yume.json", false, byteArrayOf()),
                    ProviderFile(path, false, byteArrayOf())))
            }
        }
        for (paths in listOf(listOf("yume.json", "yume.json"), listOf("yume.json", "a", "a/b"))) {
            assertThrows(Exception::class.java) {
                ProviderBundleCodec.encode(paths.map { ProviderFile(it, false, byteArrayOf()) })
            }
        }
    }
}
