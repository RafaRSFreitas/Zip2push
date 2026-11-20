package com.zippush.app.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.3.3: persistent push history. Every successful push is appended
 * to a JSON file (newest first) so the History dialog can show what
 * was pushed, when, and link straight to the commit on GitHub.
 *
 * Deliberately dependency-free (org.json ships with Android) and
 * synchronous — the file is tiny (capped at MAX_ENTRIES) and callers
 * already run on Dispatchers.IO or a background test thread.
 */
object PushHistoryStore {

    /** One recorded push. */
    data class Entry(
        val timestampMs: Long,
        val repoFullName: String,
        val branch: String,
        val targetDir: String,
        val messageTitle: String,
        val fileCount: Int,
        val files: List<String>,
        val commitUrl: String?
    )

    private const val FILE_NAME = "push_history.json"
    private const val MAX_ENTRIES = 50

    /** Test hook: resets the in-memory + on-disk state between tests. */
    @Volatile
    private var cacheForTest: List<Entry>? = null

    fun resetForTest(context: Context) {
        cacheForTest = null
        file(context).delete()
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Reads all entries, newest first. Never throws; empty on trouble. */
    @Synchronized
    fun read(context: Context): List<Entry> {
        cacheForTest?.let { return it }
        val out = mutableListOf<Entry>()
        runCatching {
            val raw = file(context).readText()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Entry(
                        timestampMs = o.getLong("ts"),
                        repoFullName = o.getString("repo"),
                        branch = o.getString("branch"),
                        targetDir = o.optString("dir", ""),
                        messageTitle = o.optString("msg", ""),
                        fileCount = o.optInt("count", 0),
                        files = o.optJSONArray("files")?.let { fa ->
                            List(fa.length()) { fa.getString(it) }
                        } ?: emptyList(),
                        commitUrl = o.optString("url").ifBlank { null }
                    )
                )
            }
        }
        cacheForTest = out
        return out
    }

    /**
     * Appends one entry (at the front) and caps the file at
     * [MAX_ENTRIES]. No-op when the write fails — history must never
     * break a successful push.
     */
    @Synchronized
    fun append(context: Context, entry: Entry) {
        val all = mutableListOf(entry) + read(context)
        val trimmed = all.take(MAX_ENTRIES)
        runCatching {
            val arr = JSONArray()
            trimmed.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("ts", e.timestampMs)
                        .put("repo", e.repoFullName)
                        .put("branch", e.branch)
                        .put("dir", e.targetDir)
                        .put("msg", e.messageTitle)
                        .put("count", e.fileCount)
                        .put("files", JSONArray(e.files))
                        .put("url", e.commitUrl ?: "")
                )
            }
            file(context).writeText(arr.toString())
        }
        cacheForTest = trimmed
    }
}
