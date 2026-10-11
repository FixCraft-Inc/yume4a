package com.fixcraft.yume.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningTest {
    private fun preset(id: String, stealth: Int, security: Int, speed: Int) = TuningPreset(
        id = id,
        limits = TuningLimits(id.length.toLong(), id.length.toLong() * 2, 2, false),
        levels = mapOf("stealth" to stealth, "security" to security, "speed" to speed),
        measurementReference = "epoch-comparison",
        measurementValues = emptyList(),
        measurementRttMs = emptyList(),
    )

    @Test
    fun nearestLevelUsesTheDocumentedTieBreaks() {
        val table = TuningTable(
            defaultId = "a",
            levelNotes = emptyMap(),
            presets = listOf(
                preset("a", 3, 3, 3),
                preset("stealth", 5, 3, 3),
                preset("security", 3, 5, 3),
                preset("speed", 3, 3, 5),
                preset("both", 5, 5, 3),
            ),
            fields = emptyList(),
            measurements = emptyMap(),
            couplingRule = "nearest-levels-v1",
        )
        assertEquals("stealth", table.move(table.presets.first(), "stealth", 5)?.id)
        assertTrue(table.move(table.presets.first(), "stealth", 9) == null)
    }

    @Test
    fun editingLimitsRetainsUnrelatedKitMembers() {
        val original = JSONObject(
            """
            {"role":"client","endpoint":{"host":"example.org","port":443},
             "cover":{"profile":"browser"},"limits":{"max_streams":4}}
            """.trimIndent(),
        )
        val edited = JSONObject(
            String(
                KitConfig.withLimits(
                    original.toString().toByteArray(),
                    TuningLimits(16L * 1024 * 1024, 4L * 1024 * 1024, 2, false),
                ),
            ),
        )
        assertEquals("browser", edited.getJSONObject("cover").getString("profile"))
        assertEquals(4, edited.getJSONObject("limits").getInt("max_streams"))
        assertEquals(16L * 1024 * 1024, edited.getJSONObject("limits").getLong("max_queued_bytes"))
    }
}
