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
                "stream-copy-1",
                "stream-copy-many",
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
        assertEquals(YumeLocalSelfTestMode.Quick, report.mode)
        assertEquals(null, report.score)

        val text = report.toPlainText()
        assertTrue(text.contains("Yume Android local self-test"))
        assertTrue(text.contains("mode=quick"))
        assertTrue(text.contains("elapsed_ms="))
        assertTrue(text.contains("copy-floor: ok=true"))
        assertTrue(text.contains("seconds="))
    }
}
