package com.zippush.app.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /** Called on the main thread whenever the state changes. */
    fun interface Observer {
        fun onPushStateChanged(state: State)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Monotonic id so observers can tell push jobs apart. */
    private var nextId = 1L

    @Volatile
    var state: State = State.Idle
        private set

    private val observers = mutableListOf<Observer>()

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

    private fun setState(s: State) {
        state = s
        // Copy to avoid ConcurrentModification while notifying.
        observers.toList().forEach { it.onPushStateChanged(s) }
    }

    /** True while a push is in flight — buttons must stay disabled. */
    fun isBusy(): Boolean = state is State.Running

    /**
     * Starts [work] as the push body. The caller hands over a suspend
     * block that performs the (already snapshotted) upload; the manager
     * owns its lifecycle. Returns false (and does nothing) when a push
     * is already running — this is the guard that makes double-tapping
     * the push button harmless.
     */
    fun start(description: String, work: suspend () -> UploadEngine.PushResult): Boolean {
        if (isBusy()) return false
        val job = PushJob(nextId++, description)
        setState(State.Running(job))
        scope.launch {
            val result = work()
            setState(State.Done(result))
        }
        return true
    }

    /** Test seam: reset between tests. */
    fun resetForTest() {
        observers.clear()
        state = State.Idle
    }
}
