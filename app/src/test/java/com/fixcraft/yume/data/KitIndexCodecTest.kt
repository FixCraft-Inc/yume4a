package com.fixcraft.yume.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KitIndexCodecTest {
    private val first = KitSummary("01234567-89ab-cdef-0123-456789abcdef", "Home", "vpn.example.org", 443, 1L)
    private val second = KitSummary("fedcba98-7654-3210-fedc-ba9876543210", "Work", "192.0.2.1", 8443, 2L)

    @Test
    fun entriesAndSelectionRoundTrip() {
        val encoded = KitIndexCodec.encode(listOf(first, second), second.id)
        assertEquals(second.id, encoded.getString("selected_kit_id"))
        assertEquals(listOf(first, second), KitIndexCodec.decode(JSONObject(encoded.toString())))
        assertTrue(KitIndexCodec.decode(JSONObject()).isEmpty())
    }

    @Test
    fun aSelectionMustNameAStoredKit() {
        assertThrows(IllegalArgumentException::class.java) { KitIndexCodec.encode(listOf(first), second.id) }
    }

    @Test
    fun oneBadEntryInvalidatesTheIndexInsteadOfHidingKits() {
        fun index(vararg items: JSONObject) = JSONObject().put("kits_json", JSONArray().apply { items.forEach(::put) }.toString())
        fun item(id: String, host: String = "h", port: Int = 443) =
            JSONObject().put("id", id).put("name", "n").put("host", host).put("port", port).put("added", 0)
        for (bad in listOf(
            index(item(first.id), item(first.id)),
            index(item("not-an-id")),
            index(item(first.id, host = " ")),
            index(item(first.id, port = 0)),
            index(item(first.id).apply { remove("added") }),
            JSONObject().put("kits_json", "{"),
        )) {
            assertThrows(Exception::class.java) { KitIndexCodec.decode(bad) }
        }
    }
}
