package com.fixcraft.yume.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YumeLocalSelfTestTest {
    @Test
    fun smokeWorkloadProducesExpectedRows() {
        val report = YumeLocalSelfTest.runSmokeForTest()

        assertEquals(
            listOf(
                "copy-floor",
                "aes-gcm-encrypt",
                "aes-gcm-decrypt",
                "packet-bulk-encode",
                "packet-bulk-decode",
                "hop-hkdf",
            ),
            report.rows.map { it.name },
        )
        assertTrue(report.rows.all { it.ok })
        assertTrue(report.rows.all { it.metric.isNotBlank() })
        assertTrue(report.elapsedMs >= 0L)
    }
}
