package com.fixcraft.yume.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedYumeNativeInstrumentedTest {
    @Test
    fun exactV2BuildExposesMandatoryPacketAndPqFeatures() {
        SharedYumeNative { true }.use { native ->
            val build = native.buildInfo
            assertEquals(1, build.abiVersion)
            assertEquals("0.2.0-dev6", build.yumeVersion)
            assertFalse(build.pqBackend.equals("unavailable", ignoreCase = true))
            assertTrue(build.featureFlags and 0x10 != 0)
            assertTrue(build.featureFlags and 0x20 != 0)
        }
    }

    @Test
    fun repeatedCreateStopCloseIsIdempotent() {
        SharedYumeNative { true }.close() // Load libraries before the baseline.
        val fdsBefore = openFdCount()
        repeat(100) {
            val native = SharedYumeNative { true }
            native.stop()
            native.stop()
            native.close()
            native.close()
        }
        val fdsAfter = openFdCount()
        assertTrue("native holder leaked file descriptors", fdsAfter <= fdsBefore + 2)
    }

    private fun openFdCount(): Int =
        java.io.File("/proc/self/fd").list()?.size ?: error("Could not inspect process fds")
}
