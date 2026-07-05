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
) {
    val splitSeconds: Double get() = splitNs / 1_000_000_000.0
    val splitMillis: Double get() = splitNs / 1_000_000.0
    val metersPerSecond: Double get() = if (splitNs > 0 && distanceM > 0) distanceM / splitSeconds else 0.0
    val feetPerSecond: Double get() = metersPerSecond * 3.28084

    fun formattedDate(): String? {
        val ms = epochMillis ?: return null
        return DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(ms))
    }
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
            )
        }
        file.writeText(arr.toString())
    }
}
