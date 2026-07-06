package com.chrono.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One recorded split. Stored locally in results.json. */
data class TestResult(
    val uid: String,
    val deviceResultId: Int,
    /** Split between the two gates, in nanoseconds (62.5 ns hardware resolution). */
    val splitNs: Long,
    val distanceM: Double,
    var label: String,
    /** null = device had no synced clock at the time of the shot */
    var epochMillis: Long?,
    var tool: String = "",
    /** distance to target kept as value+unit so it can be converted later */
    var targetDistValue: Double? = null,
    var targetDistUnit: String = "in",
    var target: String = "",
    /** the "Result" blank — outcome notes, filled in after the shot */
    var outcome: String = "",
    /** set on manual entries (deviceResultId < 0) that were typed in, not measured */
    var manualVelocityMps: Double? = null,
    /** folder id under ChronoData holding this shot's log and photos */
    var shotFolder: String = "",
) {
    val isManual: Boolean get() = deviceResultId < 0
    val splitSeconds: Double get() = splitNs / 1_000_000_000.0
    val splitMillis: Double get() = splitNs / 1_000_000.0
    val metersPerSecond: Double
        get() = manualVelocityMps
            ?: if (splitNs > 0 && distanceM > 0) distanceM / splitSeconds else 0.0
    val feetPerSecond: Double get() = metersPerSecond * 3.28084

    fun targetDistanceText(): String? =
        targetDistValue?.let {
            val v = if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
            "$v $targetDistUnit"
        }

    fun formattedDate(): String? {
        val ms = epochMillis ?: return null
        return DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(ms))
    }
}

/** Units offered for "distance to target" — value + unit are stored separately. */
val TARGET_DIST_UNITS = listOf("in", "ft", "yd", "m", "cm")

/** Parse the old free-text "targetDistance" field, e.g. "25 yd". */
internal fun legacyDistValue(s: String): Double? =
    Regex("^\\s*([0-9]+\\.?[0-9]*)").find(s)?.groupValues?.get(1)?.toDoubleOrNull()

internal fun legacyDistUnit(s: String): String {
    val unit = Regex("([a-zA-Z]+)\\s*$").find(s)?.groupValues?.get(1)?.lowercase() ?: ""
    return if (unit in TARGET_DIST_UNITS) unit else "in"
}

enum class DistanceUnit(val label: String, val toMeters: Double) {
    INCHES("in", 0.0254),
    MILLIMETERS("mm", 0.001),
    CENTIMETERS("cm", 0.01),
    FEET("ft", 0.3048),
}

/** Dead-simple JSON file persistence — no database needed for a results log. */
class ResultStore(context: Context) {
    private val file = File(context.filesDir, "results.json")

    fun load(): List<TestResult> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TestResult(
                    uid = o.getString("uid"),
                    deviceResultId = o.getInt("deviceResultId"),
                    splitNs = o.getLong("splitNs"),
                    distanceM = o.getDouble("distanceM"),
                    label = o.optString("label", ""),
                    epochMillis = o.optLong("epochMillis", -1L).takeIf { it > 0 },
                    tool = o.optString("tool", ""),
                    targetDistValue = o.optDouble("targetDistValue").takeIf { !it.isNaN() }
                        ?: legacyDistValue(o.optString("targetDistance", "")),
                    targetDistUnit = o.optString("targetDistUnit").ifBlank {
                        legacyDistUnit(o.optString("targetDistance", ""))
                    },
                    target = o.optString("target", ""),
                    outcome = o.optString("outcome", ""),
                    manualVelocityMps = o.optDouble("manualVelocityMps").takeIf { !it.isNaN() },
                    shotFolder = o.optString("shotFolder", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(results: List<TestResult>) {
        val arr = JSONArray()
        for (r in results) {
            arr.put(
                JSONObject()
                    .put("uid", r.uid)
                    .put("deviceResultId", r.deviceResultId)
                    .put("splitNs", r.splitNs)
                    .put("distanceM", r.distanceM)
                    .put("label", r.label)
                    .put("epochMillis", r.epochMillis ?: -1L)
                    .put("tool", r.tool)
                    .put("targetDistUnit", r.targetDistUnit)
                    .put("target", r.target)
                    .put("outcome", r.outcome)
                    .put("shotFolder", r.shotFolder)
                    .apply {
                        r.targetDistValue?.let { put("targetDistValue", it) }
                        r.manualVelocityMps?.let { put("manualVelocityMps", it) }
                    }
            )
        }
        file.writeText(arr.toString())
    }
}
