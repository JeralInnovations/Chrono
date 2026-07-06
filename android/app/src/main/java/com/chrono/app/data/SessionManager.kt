package com.chrono.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Folder-based logging, human-readable and user-accessible:
 *
 *   Android/data/com.chrono.app/files/ChronoData/   <- main data folder
 *     Test_2026-07-06_1432/                          <- one per test session
 *       Shot_0001/
 *         shot.json                                  <- the shot log
 *         setup_*.jpg, after_*.jpg                   <- photos for that shot
 *
 * A shot folder opens when the first setup photo is taken (or when a result
 * arrives with no photos), receives the shot log, stays open for the after
 * photos, and closes when the next shot begins.
 */
class SessionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("chrono_session", Context.MODE_PRIVATE)

    /** Main data folder — on external storage so the user can browse/copy it. */
    val root: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "ChronoData")

    private var current: File? = prefs.getString("sessionDir", null)
        ?.let { File(it) }?.takeIf { it.isDirectory }
    private var activeShot: File? = prefs.getString("shotDir", null)
        ?.let { File(it) }?.takeIf { it.isDirectory }
    private var shotLogged: Boolean = prefs.getBoolean("shotLogged", false)

    fun lastSessionName(): String? = current?.name

    fun startNew(): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        var dir = File(root, "Test_$stamp")
        var n = 2
        while (dir.exists()) dir = File(root, "Test_${stamp}_$n").also { n++ }
        dir.mkdirs()
        current = dir
        activeShot = null
        shotLogged = false
        save()
        return dir
    }

    private fun ensureSession(): File = current ?: startNew()

    private fun newShotFolder(): File {
        val session = ensureSession()
        val next = (session.listFiles { f -> f.isDirectory && f.name.startsWith("Shot_") }
            ?.size ?: 0) + 1
        val dir = File(session, "Shot_%04d".format(next))
        dir.mkdirs()
        activeShot = dir
        shotLogged = false
        save()
        return dir
    }

    /** Setup photos belong to the NEXT shot: advance if the last one is logged. */
    fun folderForSetupPhoto(): File {
        val shot = activeShot
        return if (shot == null || shotLogged) newShotFolder() else shot
    }

    /** After photos belong to the shot just logged: never advance. */
    fun folderForAfterPhoto(): File = activeShot ?: newShotFolder()

    fun logShot(json: JSONObject) {
        val shot = activeShot
        val dir = if (shot == null || shotLogged) newShotFolder() else shot
        runCatching { File(dir, "shot.json").writeText(json.toString(2)) }
        shotLogged = true
        save()
    }

    private fun save() {
        prefs.edit()
            .putString("sessionDir", current?.absolutePath)
            .putString("shotDir", activeShot?.absolutePath)
            .putBoolean("shotLogged", shotLogged)
            .apply()
    }
}
