package com.chrono.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * Builds export files and hands them to the system share sheet: a CSV of all
 * results (spreadsheet-friendly) plus the raw calibration history JSONL.
 */
object Exporter {

    fun export(context: Context, results: List<TestResult>) {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val csv = File(dir, "chrono_results_$stamp.csv")
        csv.writeText(buildString {
            appendLine("label,date_iso,split_ns,split_ms,distance_m,velocity_mps,velocity_fps")
            for (r in results) {
                val date = r.epochMillis?.let { Instant.ofEpochMilli(it).toString() } ?: ""
                val label = "\"" + r.label.replace("\"", "\"\"") + "\""
                appendLine(
                    label + "," + date + "," + r.splitNs + "," +
                        String.format(Locale.US, "%.6f", r.splitMillis) + "," +
                        String.format(Locale.US, "%.5f", r.distanceM) + "," +
                        String.format(Locale.US, "%.3f", r.metersPerSecond) + "," +
                        String.format(Locale.US, "%.2f", r.feetPerSecond)
                )
            }
        })

        val uris = arrayListOf(uriFor(context, csv))
        val cal = File(context.filesDir, "cal_history.jsonl")
        if (cal.exists()) {
            val calCopy = File(dir, "chrono_cal_$stamp.jsonl")
            cal.copyTo(calCopy, overwrite = true)
            uris.add(uriFor(context, calCopy))
        }

        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export Chrono data"))
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
}
