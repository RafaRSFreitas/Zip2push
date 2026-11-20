package com.zippush.app.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One finished push, as the History screen shows it (v0.3.3).
 *
 * The field set follows what the two reference UIs consider essential:
 * git log (https://git-scm.com/book/en/v2/Git-Basics-Viewing-the-Commit-History)
 * shows SHA, author, date and subject; GitHub's Activity view
 * (https://docs.github.com/en/repositories/viewing-activity-and-data-for-your-repository/using-the-activity-view-to-see-changes-to-a-repository)
 * additionally associates each push with the repo, the branch and a
 * "Compare changes" affordance.
 */
data class HistoryEntry(
    /** Epoch millis of the push FINISHING (newest first in the list). */
    val timestamp: Long,
    /** "owner/name" of the target repository. */
    val repoFullName: String,
    val branch: String,
    /** Target folder inside the repo ("" = repository root). */
    val targetDir: String,
    /** First line of the commit message (git log's subject). */
    val messageTitle: String,
    /** GitHub login that made the push (author). */
    val authorLogin: String,
    /** True when the push landed; false entries keep the failure reason. */
    val success: Boolean,
    /** Abbreviated commit SHA (git log style, %h) — null on failure. */
    val commitSha: String?,
    /** Full https URL of the commit — "see the changes" (null on failure). */
    val commitUrl: String?,
    /** Files the push carried, as repo paths ("docs/report.txt"). */
    val files: List<String>,
    /** Total bytes carried (formatted for display by the caller). */
    val bytesPushed: Long,
    /** Failure reason when [success] is false. */
    val failReason: String?
) {
    /** Short SHA for display (git log %h). */
    fun shortSha(): String? = commitSha?.take(7)

    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("repo", repoFullName)
        put("branch", branch)
        put("dir", targetDir)
        put("title", messageTitle)
        put("author", authorLogin)
        put("success", success)
        commitSha?.let { put("sha", it) }
        commitUrl?.let { put("url", it) }
        put("files", JSONArray(files))
        put("bytes", bytesPushed)
        failReason?.let { put("fail", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): HistoryEntry = HistoryEntry(
            timestamp = o.getLong("timestamp"),
            repoFullName = o.optString("repo", ""),
            branch = o.optString("branch", ""),
            targetDir = o.optString("dir", ""),
            messageTitle = o.optString("title", ""),
            authorLogin = o.optString("author", ""),
            success = o.optBoolean("success", false),
            commitSha = if (o.has("sha")) o.getString("sha") else null,
            commitUrl = if (o.has("url")) o.getString("url") else null,
            files = o.optJSONArray("files")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList(),
            bytesPushed = o.optLong("bytes", 0L),
            failReason = if (o.has("fail")) o.getString("fail") else null
        )
    }
}

/**
 * Local, append-only log of finished pushes (v0.3.3).
 *
 * Design notes:
 * - Persisted as one JSON file in the app's private storage (no new
 *   dependencies, no runtime permission). A corrupted file is dropped
 *   to an empty history rather than crashing the app.
 * - Newest first. Capped at [MAX_ENTRIES] entries so the file stays
 *   small; the file is rewritten atomically (temp + rename).
 * - Recording is best-effort: a history failure must never fail the
 *   push flow itself.
 */
object PushHistoryStore {

    /** Enough history to be useful, small enough to load instantly. */
    const val MAX_ENTRIES = 100

    private const val FILE_NAME = "push_history.json"

    /** Adds [entry] at the head; trims to [MAX_ENTRIES]. */
    fun record(context: Context, entry: HistoryEntry) {
        runCatching {
            val all = read(context).toMutableList()
            all.add(0, entry)
            write(context, all.take(MAX_ENTRIES))
        }
    }

    /** The history, newest first. */
    fun read(context: Context): List<HistoryEntry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { HistoryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Clears the whole history (the "Clear history" action). */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /** Atomic write: temp file + rename, so a crash mid-write keeps the old file. */
    private fun write(context: Context, entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        val target = file(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(target)) {
            // Cross-filesystem fallback (paranoia; same dir in practice).
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** Test seam: wipes the persisted file. */
    fun resetForTest(context: Context) {
        runCatching {
            file(context).delete()
            File(file(context).parentFile, "$FILE_NAME.tmp").delete()
        }
    }
}
