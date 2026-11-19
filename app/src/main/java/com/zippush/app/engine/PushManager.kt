package com.zippush.app.engine

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps an in-flight push alive across configuration changes (screen
 * rotation, resize, dark-mode flip) and while the app is in the
 * background. The previous implementation ran the push inside the
 * activity's scope, so rotating mid-push cancelled the upload mid-way
 * and the new activity instance let the user start a second push.
 *
 * The manager is a process-wide singleton: the job runs on its own
 * scope (never cancelled by the activity lifecycle), and each new
 * MainActivity instance re-attaches as an observer to re-render the
 * busy state / the push result. A finished result stays in [state]
 * until the next push starts, so a rotation right after completion
 * still shows "Pushed ✓" in the recreated activity.
 *
 * Bug 1.3: the scope now runs on Dispatchers.IO. The old Main scope
 * only worked because every caller remembered to hop to IO itself —
 * one forgotten withContext and a push would die with
 * NetworkOnMainThreadException. IO is the correct default for the work
 * this manager exists to run; UI callbacks still arrive on the main
 * thread because [setState] is invoked from a main-thread context via
 * [setStateOnMain].
 */
object PushManager {

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

    /**
     * Progress report for the running push: how many files were handed
     * to the engine so far and how many total (feature 2.1 feeds this to
     * the foreground-service notification; 2.2 re-broadcasts it after a
     * process restart).
     */
    data class Progress(val done: Int, val total: Int, val currentFile: String? = null)

    /** Called on the main thread whenever the state changes. */
    fun interface Observer {
        fun onPushStateChanged(state: State)
    }

    /**
     * Progress notifications for the running push (feature 2.1): the
     * foreground service subscribes to refresh "Sending 3/10 files…"
     * as each file is handed to the engine. Called on the main thread.
     */
    fun interface ProgressListener {
        fun onPushProgress(progress: Progress)
    }

    /**
     * Last-resort handler for the notification coroutines (v0.3.3): an
     * observer touching a dead activity's views used to kill the whole
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

    /** Latest progress of the running push (null when idle). */
    @Volatile
    var progress: Progress? = null
        private set

    private val observers = mutableListOf<Observer>()
    private val progressListeners = mutableListOf<ProgressListener>()

    fun addObserver(o: Observer) {
        observers.add(o)
        // Late attachers immediately learn the current state (a push that
        // started before the rotation, or the result of one that finished
        // while nobody was watching).
        o.onPushStateChanged(state)
    }

    fun removeObserver(o: Observer) {
        observers.remove(o)
    }

    fun addProgressListener(l: ProgressListener) {
        progressListeners.add(l)
    }

    fun removeProgressListener(l: ProgressListener) {
        progressListeners.remove(l)
    }

    private fun setState(s: State) {
        state = s
        // Copy to avoid ConcurrentModification while notifying.
        val toNotify = observers.toList()
        val notify = { toNotify.forEach { it.onPushStateChanged(s) } }
        // Observers touch views; always deliver on the main thread even
        // though the push itself runs on IO.
        mainScope.launch { notify() }
    }

    /** True while a push is in flight — buttons must stay disabled. */
    fun isBusy(): Boolean = state is State.Running

    /** Publishes progress from inside the running push body. */
    fun reportProgress(done: Int, total: Int, currentFile: String? = null) {
        if (state !is State.Running) return
        val p = Progress(done, total, currentFile)
        progress = p
        val toNotify = progressListeners.toList()
        if (toNotify.isNotEmpty()) {
            mainScope.launch { toNotify.forEach { it.onPushProgress(p) } }
        }
    }

    /**
     * Starts [work] as the push body. The caller hands over a suspend
     * block that performs the (already snapshotted) upload; the manager
     * owns its lifecycle. Returns false (and does nothing) when a push
     * is already running — this is the guard that makes double-tapping
     * the push button harmless.
     *
     * [work] runs on Dispatchers.IO (see class docs for bug 1.3); code
     * needing the main thread must use withContext explicitly.
     *
     * Stability: any exception escaping [work] is converted into a
     * Failure result instead of crashing the process. Before this, a
     * SecurityException (expired URI grant after a reboot), an
     * OutOfMemoryError while reading a huge file, or an IOException on
     * a full disk would kill the app mid-push "randomly".
     */
    fun start(description: String, work: suspend () -> UploadEngine.PushResult): Boolean {
        if (isBusy()) return false
        val job = PushJob(nextId++, description)
        progress = null
        setState(State.Running(job))
        scope.launch {
            val result = try {
                work()
            } catch (t: Throwable) {
                progress = null
                UploadEngine.PushResult.Failure(
                    "Unexpected",
                    "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                )
            }
            progress = null
            setState(State.Done(result))
        }
        return true
    }

    /** Test seam: reset between tests. */
    fun resetForTest() {
        observers.clear()
        state = State.Idle
        progress = null
    }

    /** Test seam: synchronous state set (no main-thread hop). */
    internal fun setStateForTest(s: State) {
        state = s
        observers.toList().forEach { it.onPushStateChanged(s) }
    }
}
