package com.zippush.app.push

import android.content.Context
import android.net.Uri
import com.zippush.app.R
import com.zippush.app.SelectionTracker
import com.zippush.app.engine.UploadEngine
import com.zippush.app.net.GitHubClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Application-scoped home for the ONE push that may be in flight.
 *
 * Why it exists (v0.2.8): the 0.2.6/0.2.7 architecture ran the push
 * inside the activity's coroutine scope. Two things killed the upload
 * mid-way: rotating the device (scope cancelled on destroy) and the
 * system reaping the backgrounded process. This runner fixes the first
 * — the job runs on its own scope, which no activity owns — and gives
 * the foreground service something stable to protect the second.
 *
 * Deliberately NOT the v0.3.x PushManager: no queue, no retry logic, no
 * persistence. One job at a time; when it finishes, its result stays
 * available until the next push starts, so an activity recreated after
 * a rotation still shows "Pushed ✓".
 */
object PushRunner {

    /** A push in progress. */
    data class PushJob(
        val id: Long,
        val description: String
    )

    /** Observable push lifecycle. */
    sealed class State {
        object Idle : State()
        data class Running(val job: PushJob) : State()
        data class Done(val result: UploadEngine.PushResult) : State()
    }

    /** Called on the main thread whenever the state changes. */
    fun interface Observer {
        fun onPushStateChanged(state: State)
    }

    /**
     * Last-resort handler for the notification coroutines: an observer
     * touching a dead activity's views must never kill the whole
     * process (an unhandled exception on Dispatchers.Main is fatal).
     * Observer callbacks are best-effort — a failure is logged and the
     * push lifecycle itself is unaffected.
     */
    private val notifyGuard = CoroutineExceptionHandler { _, t ->
        android.util.Log.e("Zip2Push", "Observer notification failed", t)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + notifyGuard)

    /** Monotonic id so observers can tell push jobs apart. */
    private var nextId = 1L

    @Volatile
    var state: State = State.Idle
        private set

    private val observers = mutableListOf<Observer>()

    /** Everything a push needs, captured on the main thread at start. */
    private class Pending(
        val job: PushJob,
        val engine: UploadEngine,
        val selection: SelectionTracker.Snapshot,
        val repo: GitHubClient.Repo,
        val branch: String,
        val targetDir: String,
        val message: String,
        val authorDateIso: String?,
        val authorLogin: String?
    )

    private var pending: Pending? = null

    /** Registers [observer]; returns false if it was already registered. */
    fun addObserver(observer: Observer): Boolean = synchronized(observers) {
        if (observers.contains(observer)) false else observers.add(observer)
    }

    fun removeObserver(observer: Observer) {
        synchronized(observers) { observers.remove(observer) }
    }

    /** Test hook: drop all observers and reset to Idle. */
    fun resetForTest() {
        synchronized(observers) { observers.clear() }
        state = State.Idle
        pending = null
    }

    /** True while a push is in flight. */
    fun isBusy(): Boolean = state is State.Running

    /**
     * Starts a push. Returns false when one is already running (double
     * tap) — the caller shows "already in progress" in that case.
     *
     * All inputs are captured here, on the caller's (main) thread: the
     * engine reference, the snapshotted selection and the ISO string
     * (no java.time on API 24 — must not be built on the IO path).
     */
    fun start(
        context: Context,
        engine: UploadEngine,
        selection: SelectionTracker.Snapshot,
        repo: GitHubClient.Repo,
        branch: String,
        targetDir: String,
        message: String,
        authorDateIso: String?,
        authorLogin: String?
    ): Boolean {
        if (isBusy()) return false
        val job = PushJob(nextId++, "Pushing to ${repo.fullName}…")
        val appContext = context.applicationContext
        pending = Pending(
            job, engine, selection, repo, branch, targetDir, message,
            authorDateIso, authorLogin
        )
        setState(State.Running(job))
        scope.launch {
            val p = pending ?: return@launch
            val result = execute(p, appContext)
            pending = null
            setState(State.Done(result))
        }
        return true
    }

    /** The upload itself; runs on IO inside the runner's own scope. */
    private fun execute(
        p: Pending,
        appContext: Context
    ): UploadEngine.PushResult {
        return try {
            val files = buildFileList(p.engine, p.selection, appContext)
            if (files.isEmpty()) {
                UploadEngine.PushResult.Failure(
                    "Prepare",
                    appContext.getString(R.string.no_readable_files)
                )
            } else {
                p.engine.push(
                    owner = p.repo.owner,
                    repo = p.repo.name,
                    branch = p.branch,
                    newBranch = null,
                    targetDir = p.targetDir,
                    commitMessage = p.message,
                    files = files,
                    authorDate = p.authorDateIso,
                    authorLogin = p.authorLogin
                )
            }
        } catch (t: Throwable) {
            // Nothing in the runner may crash the process: the upload
            // surfaces as a normal failure on the status line.
            android.util.Log.e("Zip2Push", "Push failed", t)
            UploadEngine.PushResult.Failure("Prepare", t.message ?: "unexpected error")
        }
    }

    /** Turns the snapshotted selection into the engine's pending files. */
    private fun buildFileList(
        engine: UploadEngine,
        selection: SelectionTracker.Snapshot,
        context: Context
    ): List<UploadEngine.PendingFile> {
        val files = mutableListOf<UploadEngine.PendingFile>()

        // The ZIP (if any) contributes entries with their inner paths.
        // A failed read (expired URI grant, provider gone) skips the
        // archive — the user sees "no readable files" instead of a crash.
        selection.zip?.let { uri ->
            val cached = runCatching { copyUriToCache(context, uri) }.getOrNull()
            if (cached != null) {
                runCatching {
                    files.addAll(engine.extractZipEntries(cached, skipRootFolder = true))
                }
                // The cache copy is no longer needed once parsed.
                cached.delete()
            }
        }

        // Standalone files and folder files: one item each. Folder files
        // keep their repo paths.
        val named = mutableListOf<Pair<String, ByteArray>>()
        for (u in selection.files) {
            val name = runCatching { queryDisplayName(context, u) }.getOrNull() ?: "file"
            val bytes = runCatching {
                context.contentResolver.openInputStream(u)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) named.add(name to bytes)
        }
        for (ff in selection.folderFiles) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(ff.uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) named.add(ff.repoPath to bytes)
        }
        files.addAll(engine.collectNamedFiles(named))
        return files
    }

    private fun copyUriToCache(context: Context, uri: Uri): File {
        // Unique name per copy: a previous push's cache file could still
        // be held open by the engine when a second push reused the same
        // path, and a partial overwrite corrupted the archive read.
        val target = File(context.cacheDir, "zippush_upload_${System.nanoTime()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        if (!target.exists() || target.length() == 0L) {
            throw java.io.IOException("empty or unreadable archive")
        }
        return target
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        name
    } catch (e: Exception) {
        // Providers can throw SecurityException for expired grants or
        // disappear entirely (uninstalled app) — the fallback name is
        // enough; a crash here would kill an in-flight push.
        null
    }

    private fun setState(newState: State) {
        state = newState
        // Observers are notified on the main thread (they touch views);
        // failures there are contained by notifyGuard.
        mainScope.launch { notifyObservers(newState) }
    }

    private fun notifyObservers(newState: State) {
        val snapshot = synchronized(observers) { observers.toList() }
        for (o in snapshot) o.onPushStateChanged(newState)
    }
}
