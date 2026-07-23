package com.chrono.app.data

import android.content.ContentUris
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
 *   Documents/ChronoData/
 *     2026-07-06/                <- PROJECT folder, one per day (renameable at
 *       Test1/                      creation via the new-day prompt)
 *         shot.json              <- TEST subfolder, named by the test label or
 *         setup_*.jpg, after_*      Test1/Test2/… auto-incrementing
 *       LongRangeGroupA/
 *
 * A new day (or first run) prompts for the project folder; within a day every
 * test drops into the same project. A test subfolder opens when the first
 * photo or the shot log for that test arrives, and rolls to a new one when the
 * next shot begins.
 *
 * Files are written through MediaStore (no storage permission for app-created
 * files) on Android 10+. On 9 and below the same tree lives under
 * Android/data/com.chrono.app/files/ChronoData.
 */
class SessionManager(private val context: Context, simulation: Boolean = false) {

    private val prefs = context.getSharedPreferences(
        if (simulation) "chrono_session_sim" else "chrono_session", Context.MODE_PRIVATE
    )
    private val useMediaStore = Build.VERSION.SDK_INT >= 29

    // Simulated sessions live in a clearly-labelled sibling folder so their logs
    // and photos never mix with real range data.
    private val rootDir = if (simulation) "ChronoData_SIMULATION" else "ChronoData"

    var projectName: String? = prefs.getString("projectName", null)
        private set
    private var projectDay: String? = prefs.getString("projectDay", null)
    private var testCounter: Int = prefs.getInt("testCounter", 0)
    private var currentTestRel: String? = prefs.getString("currentTestRel", null)
    private var shotLogged: Boolean = prefs.getBoolean("shotLogged", false)

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Prompt for a project only on a genuinely new day (or first run). */
    fun needsProjectPrompt(): Boolean = projectName == null || projectDay != today()

    /** The active test folder remains authoritative through its after photos. */
    fun suggestedLabel(): String =
        currentTestRel?.substringAfterLast('/')
            ?: "Test${testCounter + 1}"

    val pathLabel: String get() = "Documents/$rootDir/${projectName ?: ""}"

    fun startProject(name: String) {
        projectName = sanitize(name.ifBlank { today() })
        projectDay = today()
        testCounter = 0
        currentTestRel = null
        shotLogged = false
        prefs.edit().remove("used_$projectName").apply()
        save()
    }

    /** Keep the previous project on a new day; just stop prompting for today. */
    fun continueProject() {
        if (projectName == null) startProject(today())
        else { projectDay = today(); save() }
    }

    // ------------------------------------------------------------- test folders

    private fun ensureProject() { if (projectName == null) startProject(today()) }

    private fun rollTest(label: String) {
        ensureProject()
        testCounter += 1
        val base = sanitize(label).ifBlank { "Test$testCounter" }
        val name = uniqueName(base)
        addUsedName(name)
        currentTestRel = "$projectName/$name"
        shotLogged = false
        save()
    }

    /** Apply a UI label edit to the active test folder. */
    fun commitCurrentTestLabel(label: String): String {
        val currentRel = currentTestRel
            ?: return sanitize(label).ifBlank { "Test${testCounter + 1}" }
        return renameTestFolder(currentRel, label).second
    }

    /**
     * End the active folder only for an explicit New Test or saved manual log.
     * The next photo or result creates the next numbered folder.
     */
    fun beginNewTest() {
        currentTestRel = null
        shotLogged = false
        save()
    }

    /** Open the test if needed and return the exact folder/label name selected. */
    fun prepareTestLabel(label: String): String {
        val rel = currentTest(label)
        return rel.substringAfterLast('/')
    }

    /**
     * Rename a test folder and return its effective relative path and label.
     * MediaStore item ids are retained, so existing photo URIs remain valid.
     */
    fun renameTestFolder(rel: String, label: String): Pair<String, String> {
        if (rel.isBlank()) return rel to sanitize(label)
        val oldName = rel.substringAfterLast('/')
        val requested = sanitize(label).ifBlank { oldName }
        if (oldName == requested) return rel to oldName

        val project = rel.substringBeforeLast('/')
        val newName = if (project == projectName) {
            uniqueName(requested, excluding = oldName)
        } else {
            requested
        }
        val newRel = "$project/$newName"
        if (!moveTestFolder(rel, newRel)) return rel to oldName

        if (project == projectName) replaceUsedName(oldName, newName)
        if (currentTestRel == rel) {
            currentTestRel = newRel
            save()
        }
        return newRel to newName
    }

    /** Folder for the active test cycle. Only beginNewTest() advances it. */
    private fun currentTest(label: String): String {
        if (currentTestRel == null) {
            rollTest(label)
        } else {
            commitCurrentTestLabel(label)
        }
        return currentTestRel!!
    }

    fun currentTestLogged(): Boolean = shotLogged

    /** Setup photos open/join the upcoming test; after photos stay with it. */
    fun newPhotoUri(kind: String, label: String): Uri? {
        val rel = writablePhotoRel(kind, label)
        return createUriAt(rel, "${kind}_${System.currentTimeMillis()}.jpg", "image/jpeg")
    }

    fun listPromptPhotos(kind: String, label: String): List<Uri> =
        readablePhotoRel(kind, label)?.let { listPhotos(it) } ?: emptyList()

    fun importPromptPhoto(kind: String, label: String, source: Uri): Boolean =
        importPhoto(writablePhotoRel(kind, label), source)

    private fun writablePhotoRel(kind: String, label: String): String =
        if (kind == "after") (currentTestRel ?: currentTest(label)) else currentTest(label)

    private fun readablePhotoRel(kind: String, label: String): String? =
        if (kind == "after") currentTestRel ?: currentTest(label)
        else currentTestRel?.takeUnless { shotLogged }

    /** Writes the log into its test folder; returns the folder id for the record. */
    fun logShot(label: String, json: JSONObject): String {
        val rel = currentTest(label)
        writeShotJson(rel, json)
        shotLogged = true
        save()
        return rel
    }

    /** Rewrites an existing test's canonical log after the user edits it. */
    fun updateShot(rel: String, json: JSONObject): Boolean {
        if (rel.isBlank()) return false
        return writeShotJson(rel, json)
    }

    /** Folder to attach photos to an existing record without touching counters. */
    fun folderForResult(existingRel: String?, uidHint: String): String {
        if (!existingRel.isNullOrBlank()) return existingRel
        ensureProject()
        return "$projectName/Extra_${uidHint.take(8)}"
    }

    fun importPhoto(rel: String, source: Uri): Boolean {
        val dest = createUriAt(rel, "attached_${System.currentTimeMillis()}.jpg", "image/jpeg")
            ?: return false
        return runCatching {
            context.contentResolver.openInputStream(source)!!.use { input ->
                context.contentResolver.openOutputStream(dest)!!.use { out -> input.copyTo(out) }
            }
        }.isSuccess
    }

    fun deletePhoto(uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    /** Image files already saved in a shot's folder, for thumbnails. */
    fun listPhotos(rel: String): List<Uri> {
        if (rel.isBlank()) return emptyList()
        return if (useMediaStore) {
            val coll = MediaStore.Files.getContentUri("external")
            val out = mutableListOf<Uri>()
            runCatching {
                context.contentResolver.query(
                    coll,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                        "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%'",
                    arrayOf("Documents/$rootDir/$rel/"),
                    "${MediaStore.MediaColumns._ID} ASC",
                )?.use { c ->
                    val idc = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (c.moveToNext()) out.add(ContentUris.withAppendedId(coll, c.getLong(idc)))
                }
            }
            out
        } else {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "$rootDir/$rel")
            dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                ?.sortedBy { it.name }
                ?.mapNotNull {
                    runCatching {
                        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it)
                    }.getOrNull()
                } ?: emptyList()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun writeShotJson(rel: String, json: JSONObject): Boolean {
        val bytes = json.toString(2).toByteArray()
        if (!useMediaStore) {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "$rootDir/$rel")
            return runCatching {
                dir.mkdirs()
                File(dir, "shot.json").writeBytes(bytes)
            }.isSuccess
        }

        val uri = findUriAt(rel, "shot.json")
            ?: createUriAt(rel, "shot.json", "application/json")
            ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        }.isSuccess
    }

    private fun findUriAt(rel: String, displayName: String): Uri? {
        val coll = MediaStore.Files.getContentUri("external")
        return runCatching {
            context.contentResolver.query(
                coll,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("Documents/$rootDir/$rel/", displayName),
                "${MediaStore.MediaColumns._ID} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else ContentUris.withAppendedId(
                    coll,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                )
            }
        }.getOrNull()
    }

    private fun createUriAt(rel: String, displayName: String, mime: String): Uri? =
        if (useMediaStore) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/$rootDir/$rel")
            }
            runCatching {
                context.contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
            }.getOrNull()
        } else {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "$rootDir/$rel")
            dir.mkdirs()
            runCatching {
                FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", File(dir, displayName)
                )
            }.getOrNull()
        }

    private fun sanitize(s: String): String =
        s.trim().replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").take(40).trim()

    private fun usedNames(): MutableSet<String> =
        prefs.getStringSet("used_$projectName", emptySet())!!.toMutableSet()

    private fun addUsedName(n: String) {
        val s = usedNames(); s.add(n)
        prefs.edit().putStringSet("used_$projectName", s).apply()
    }

    private fun replaceUsedName(old: String, new: String) {
        val names = usedNames()
        names.remove(old)
        names.add(new)
        prefs.edit().putStringSet("used_$projectName", names).apply()
    }

    private fun uniqueName(base: String, excluding: String? = null): String {
        val used = usedNames().apply { excluding?.let { remove(it) } }
        if (base !in used) return base
        var i = 2
        while ("${base}_$i" in used) i++
        return "${base}_$i"
    }

    private fun moveTestFolder(oldRel: String, newRel: String): Boolean {
        if (oldRel == newRel) return true
        if (!useMediaStore) {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val oldDir = File(root, "$rootDir/$oldRel")
            val newDir = File(root, "$rootDir/$newRel")
            if (!oldDir.exists()) return true
            newDir.parentFile?.mkdirs()
            return oldDir.renameTo(newDir)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val oldPath = "Documents/$rootDir/$oldRel/"
        val newPath = "Documents/$rootDir/$newRel/"
        val ids = mutableListOf<Long>()
        val queried = runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(oldPath),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) ids.add(cursor.getLong(idColumn))
            }
        }
        if (queried.isFailure) return false

        val moved = mutableListOf<Uri>()
        for (id in ids) {
            val uri = ContentUris.withAppendedId(collection, id)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, newPath)
            }
            if (runCatching { resolver.update(uri, values, null, null) }.getOrDefault(0) != 1) {
                val rollback = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, oldPath)
                }
                moved.forEach { runCatching { resolver.update(it, rollback, null, null) } }
                return false
            }
            moved.add(uri)
        }
        return true
    }

    /** Best-effort: open the data folder in the system Files app. */
    fun openFolder(context: Context) {
        val sub = projectName?.let { "/$it" } ?: ""
        val docId = if (useMediaStore) "primary:Documents/$rootDir$sub"
        else "primary:Android/data/${context.packageName}/files/$rootDir$sub"
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
            .putString("projectName", projectName)
            .putString("projectDay", projectDay)
            .putInt("testCounter", testCounter)
            .putString("currentTestRel", currentTestRel)
            .putBoolean("shotLogged", shotLogged)
            .apply()
    }
}
