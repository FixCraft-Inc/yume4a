package com.fixcraft.yume.data

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs

/** The four resource limits owned by a client kit's tuning table. */
data class TuningLimits(
    val maxQueuedBytes: Long,
    val maxEpochBytes: Long,
    val creditReturnsPerWindow: Int,
    val idleEpochRotation: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("max_queued_bytes", maxQueuedBytes)
        .put("max_epoch_bytes", maxEpochBytes)
        .put("credit_returns_per_window", creditReturnsPerWindow)
        .put("idle_epoch_rotation", idleEpochRotation)

    companion object {
        fun from(limits: JSONObject, defaults: TuningLimits): TuningLimits = TuningLimits(
            maxQueuedBytes = limits.optLong("max_queued_bytes", defaults.maxQueuedBytes),
            maxEpochBytes = limits.optLong("max_epoch_bytes", defaults.maxEpochBytes),
            creditReturnsPerWindow = limits.optInt(
                "credit_returns_per_window",
                defaults.creditReturnsPerWindow,
            ),
            idleEpochRotation = if (limits.has("idle_epoch_rotation")) {
                limits.optBoolean("idle_epoch_rotation", defaults.idleEpochRotation)
            } else defaults.idleEpochRotation,
        )
    }
}

data class TuningPreset(
    val id: String,
    val limits: TuningLimits,
    val levels: Map<String, Int>,
    val measurementReference: String,
    val measurementValues: List<Double>,
    val measurementRttMs: List<Int>,
)

data class TuningField(
    val key: String,
    val label: String,
    val unit: String,
    val divisor: Long,
)

data class TuningMeasurement(
    val reference: String,
    val conditions: String,
    val rttMs: List<Int>,
    val metric: String,
    val unit: String,
    val limitation: String,
)

/** The exact schema-1 table shipped by YUME, including its coupling rules. */
class TuningTable internal constructor(
    val defaultId: String,
    val levelNotes: Map<String, String>,
    val presets: List<TuningPreset>,
    val fields: List<TuningField>,
    val measurements: Map<String, TuningMeasurement>,
    private val couplingRule: String,
) {
    val defaultPreset: TuningPreset = presets.first { it.id == defaultId }

    /** The highest level each axis reaches in the table, which its slider ends at. */
    val levelMaximum: Map<String, Int> = AXES.associateWith { axis ->
        presets.maxOfOrNull { it.levels[axis] ?: 1 } ?: 1
    }

    fun match(limits: TuningLimits): TuningPreset? = presets.firstOrNull { it.limits == limits }

    /** Move one axis while choosing the nearest table row, with table tie rules. */
    fun move(current: TuningPreset, axis: String, level: Int): TuningPreset? {
        if (couplingRule != "nearest-levels-v1" || axis !in AXES || level !in 1..5) return null
        return presets
            .filter { it.levels[axis] == level }
            .minWithOrNull(
                compareBy<TuningPreset> {
                    AXES.filter { it != axis }.sumOf { other ->
                        abs((it.levels[other] ?: 0) - (current.levels[other] ?: 0))
                    }
                }.thenByDescending { it.levels["stealth"] ?: 0 }
                    .thenByDescending { it.levels["security"] ?: 0 }
                    .thenBy { it.levels["speed"] ?: 0 }
                    .thenBy { presets.indexOf(it) },
            )
    }

    fun effectiveLimits(json: JSONObject): TuningLimits = TuningLimits.from(
        json.optJSONObject("limits") ?: JSONObject(),
        defaultPreset.limits,
    )

    companion object {
        private val AXES = listOf("stealth", "security", "speed")

        fun load(context: Context): TuningTable {
            val root = JSONObject(
                context.assets.open("tuning_presets.json").bufferedReader().use { it.readText() },
            )
            require(root.optInt("schema") == 1) { "unsupported tuning table" }
            val defaults = root.optString("default")
            val measurementsJson = root.getJSONObject("measurements")
            val measurements = measurementsJson.keys().asSequence().associateWith { key ->
                val item = measurementsJson.getJSONObject(key)
                TuningMeasurement(
                    reference = key,
                    conditions = item.getString("conditions"),
                    rttMs = item.getJSONArray("rtt_ms").let { values ->
                        (0 until values.length()).map(values::getInt)
                    },
                    metric = item.getString("metric"),
                    unit = item.getString("unit"),
                    limitation = item.getString("limitation"),
                )
            }
            val presetJson = root.getJSONArray("presets")
            val presets = (0 until presetJson.length()).map { index ->
                val item = presetJson.getJSONObject(index)
                val levelsJson = item.getJSONObject("levels")
                val measurement = item.getJSONObject("measurement")
                val reference = measurement.getString("reference")
                TuningPreset(
                    id = item.getString("id"),
                    limits = TuningLimits.from(item.getJSONObject("limits"), TuningLimits(0, 0, 0, false)),
                    levels = AXES.associateWith { levelsJson.getInt(it) },
                    measurementReference = reference,
                    measurementValues = measurement.getJSONArray("values").let { values ->
                        (0 until values.length()).map(values::getDouble)
                    },
                    measurementRttMs = measurements[reference]?.rttMs.orEmpty(),
                )
            }
            val fieldsJson = root.getJSONArray("fields")
            val fields = (0 until fieldsJson.length()).map { index ->
                val field = fieldsJson.getJSONObject(index)
                TuningField(
                    key = field.getString("key"),
                    label = field.getString("label"),
                    unit = field.getString("unit"),
                    divisor = field.getLong("divisor"),
                )
            }
            require(presets.any { it.id == defaults }) { "tuning table has no default" }
            return TuningTable(
                defaultId = defaults,
                levelNotes = root.getJSONObject("levels").keys().asSequence()
                    .associateWith { root.getJSONObject("levels").getString(it) },
                presets = presets,
                fields = fields,
                measurements = measurements,
                couplingRule = root.optJSONObject("coupling")?.optString("rule").orEmpty(),
            )
        }
    }
}
