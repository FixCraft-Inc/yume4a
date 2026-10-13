package com.fixcraft.yume.data

import org.json.JSONArray
import org.json.JSONObject

/** A malformed entry invalidates the index instead of silently hiding a kit. */
internal object KitIndexCodec {
    fun isValidId(id: String): Boolean =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)

    fun decode(index: JSONObject): List<KitSummary> {
        if (index.length() == 0) return emptyList()
        val array = JSONArray(index.getString("kits_json"))
        val seen = HashSet<String>()
        return (0 until array.length()).map { position ->
            val item = array.getJSONObject(position)
            val id = item.getString("id")
            val host = item.getString("host")
            val port = item.getInt("port")
            require(isValidId(id) && seen.add(id) && host.isNotBlank() && port in 1..65535) {
                "Invalid kit index entry"
            }
            KitSummary(id, item.getString("name").ifBlank { host }, host, port, item.getLong("added"))
        }
    }

    fun encode(kits: List<KitSummary>, selected: String?): JSONObject {
        require(selected == null || kits.any { it.id == selected }) { "Invalid kit selection" }
        val array = JSONArray()
        kits.forEach { kit ->
            array.put(JSONObject()
                .put("id", kit.id)
                .put("name", kit.name)
                .put("host", kit.host)
                .put("port", kit.port)
                .put("added", kit.addedAtEpochMs))
        }
        return JSONObject().put("kits_json", array.toString()).put("selected_kit_id", selected)
    }
}
