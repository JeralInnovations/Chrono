package com.chrono.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Folder-based logging in PUBLIC storage so the user can browse it with any
 * file manager:
 *
 *   Documents/ChronoData/                 <- main data folder (Android 10+)
 *     Test_2026-07-06_1432/               <- one folder per test session
 *       Shot_0001/
 *         shot.json                       <- the shot log
 *         setup_*.jpg, after_*.jpg        <- photos for that shot
 *
 * Files are written through MediaStore (no storage permission needed for
 * app-created files). On Android 9 and below, where MediaStore relative
 * paths don't exist, the same tree lives under
 * Android/data/com.chrono.app/files/ChronoData (browsable pre-10).
 *
 * A shot folder opens when the first setup photo is taken (or when a result
 * arrives with no photos), receives the shot log, stays open for the after
 * photos, and closes when the next shot begins.
 */
class SessionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("chrono_session", Context.MODE_PRIVATE)
    private val useMediaStore = Build.VERSION.SDK_INT >= 29

    private var sessionName: String? = prefs.getString("sessionName", null)
    private var shotIndex: Int = prefs.getInt("shotIndex", 0)   // 0 = no shot folder yet
    private var shotLogged: Boolean = prefs.getBoolean("shotLogged", false)

    fun lastSessionName(): String? = sessionName

    /** User-readable location, shown in the UI. */
    val pathLabel: String
        get() = (if (useMediaStore) "Documents/ChronoData/" else "Android/data/…/ChronoData/") +
            (sessionName ?: "")

    fun startNew(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        sessionName = "Test_$stamp"
        shotIndex = 0
        shotLogged = false
        save()
        return sessionName!!
    }

    private fun ensureSession(): String = sessionName ?: startNew()

    private fun newShot() {
        ensureSession()
        shotIndex += 1
        shotLogged = false
        save()
    }

    private fun shotRelPath(): String =
        "Documents/ChronoData/${ensureSession()}/Shot_%04d".format(shotIndex)

    /** Setup photos belong to the NEXT shot; after photos stay with the last. */
    fun newPhotoUri(kind: String): Uri? {
        if (shotIndex == 0 || (kind == "setup" && shotLogged)) newShot()
        val name = "${kind}_${System.currentTimeMillis()}.jpg"
        return createUri(name, "image/jpeg")
    }

    fun logShot(json: JSONObject) {
        if (shotIndex == 0 || shotLogged) newShot()
        createUri("shot.json", "application/json")?.let { uri ->
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toString(2).toByteArray())
                }
            }
        }
        shotLogged = true
        save()
    }

    private fun createUri(displayName: String, mime: String): Uri? =
        if (useMediaStore) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, shotRelPath())
            }
            runCatching {
                context.contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
            }.getOrNull()
        } else {
            val dir = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "ChronoData/${ensureSession()}/Shot_%04d".format(shotIndex),
            )
            dir.mkdirs()
            runCatching {
                FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", File(dir, displayName)
                )
            }.getOrNull()
        }

    /** Best-effort: open the data folder in the system Files app. */
    fun openFolder(context: Context) {
        val docId = if (useMediaStore) "primary:Documents/ChronoData"
        else "primary:Android/data/${context.packageName}/files/ChronoData"
        val uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", docId
        )
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        for (intent in attempts) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    private fun save() {
        prefs.edit()
            .putString("sessionName", sessionName)
            .putInt("shotIndex", shotIndex)
            .putBoolean("shotLogged", shotLogged)
            .apply()
    }
}
