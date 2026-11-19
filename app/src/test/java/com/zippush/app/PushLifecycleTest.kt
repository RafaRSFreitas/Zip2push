package com.zippush.app

import org.junit.Rule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.zippush.app.engine.PushManager
import com.zippush.app.engine.UploadEngine
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Lifecycle battery for pushes: the upload must survive screen rotation,
 * backgrounding and activity destruction, must not start twice on a
 * double tap, and the chosen backdate must survive rotation.
 *
 * The push body "parks" with a non-blocking suspend loop (delay + flag)
 * so the Robolectric main looper stays free to process the lifecycle
 * events and observer notifications while the push is in flight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushLifecycleTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    /** Set to true to release the parked push body. */
    @Volatile
    private var release = false

    @Volatile
    private var pushesStarted = 0

    @Volatile
    private var pushEntered = false

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        PushManager.resetForTest()
        release = false
        pushesStarted = 0
        pushEntered = false
    }

    @After
    fun tearDown() {
        scenario.close()
        PushManager.resetForTest()
    }

    /** Sets repo + file selection so onPushClicked passes its guards. */
    private fun prepareSelection(activity: MainActivity) {
        activity.selectedRepo = com.zippush.app.net.GitHubClient.Repo(
            fullName = "alice/repo", name = "repo", owner = "alice",
            private = false, defaultBranch = "main"
        )
        activity.tracker.pickFiles(listOf(android.net.Uri.parse("content://x/a.txt")))
    }

    /** Runs the main looper until all currently pending tasks are done. */
    private fun idle() {
        ShadowLooper.shadowMainLooper().idle()
    }

    /** Suspends (without blocking the looper) until [release] is set. */
    private suspend fun park() {
        pushEntered = true
        while (!release) delay(10)
    }

    private fun awaitEntered(timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.shadowMainLooper().runToEndOfTasks()
            if (pushEntered) return true
            Thread.sleep(20)
        }
        return pushEntered
    }

    /**
     * Pumps the main looper until [condition] holds (the parked push body
     * advances through its delay() loop only while the looper runs).
     * runToEndOfTasks also advances the scheduler's clock so delayed
     * tasks (delay(10)) become due instead of waiting for real time.
     */
    private fun runUntil(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.shadowMainLooper().runToEndOfTasks()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun `push survives screen rotation started before completion`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            prepareSelection(activity)
            assertTrue(PushManager.start("Pushing…") {
                park()
                UploadEngine.PushResult.Success("SHA1", "https://github.com/x/y/commit/SHA1", 1, 10L)
            })
        }
        assertTrue("push body must have started", awaitEntered())
        assertTrue(PushManager.isBusy())

        // Rotate the device mid-push: old instance destroyed, new created.
        scenario.recreate()
        idle()

        // New instance must render the running push: buttons disabled.
        scenario.onActivity { activity ->
            val pushBtn = activity.findViewById<MaterialButton>(R.id.pushBtn)
            assertFalse("push button must stay disabled after rotation", pushBtn.isEnabled)
        }
        assertTrue(PushManager.isBusy())

        // Finish the push; the new instance must show the result.
        release = true
        assertTrue("push must finish after release", runUntil { !PushManager.isBusy() })
        idle()
        scenario.onActivity { activity ->
            val pushBtn = activity.findViewById<MaterialButton>(R.id.pushBtn)
            assertTrue("push button re-enabled after result", pushBtn.isEnabled)
            assertNotNull(activity.lastCommitUrl)
            assertEquals("https://github.com/x/y/commit/SHA1", activity.lastCommitUrl)
        }
    }

    @Test
    fun `push survives activity destroy and finishes alone`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            assertTrue(PushManager.start("Pushing…") {
                park()
                UploadEngine.PushResult.Success("SHA2", "u2", 1, 1L)
            })
        }
        assertTrue(awaitEntered())

        // Destroy the activity entirely (finish()), not just rotate.
        scenario.onActivity { it.finish() }
        idle()
        // The push is still running inside PushManager.
        assertTrue("push must outlive the activity", PushManager.isBusy())

        val observerState = arrayOfNulls<PushManager.State>(1)
        PushManager.addObserver { st ->
            observerState[0] = st
        }
        release = true
        assertTrue("result must still arrive", runUntil { observerState[0] is PushManager.State.Done })
        assertTrue(observerState[0] is PushManager.State.Done)
        val result = (observerState[0] as PushManager.State.Done).result
        assertTrue(result is UploadEngine.PushResult.Success)
        assertFalse(PushManager.isBusy())
    }

    @Test
    fun `second push while one is running is rejected`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            prepareSelection(activity)
            val first = PushManager.start("first") {
                park()
                UploadEngine.PushResult.Success("S1", "u1", 1, 1L)
            }
            assertTrue(first)
            // A second push attempt while the first is in flight.
            val second = PushManager.start("second") {
                UploadEngine.PushResult.Success("S2", "u2", 1, 1L)
            }
            assertFalse("manager must refuse a concurrent push", second)
        }
        assertTrue(awaitEntered())
        release = true
        assertTrue(runUntil { !PushManager.isBusy() })
        idle()
    }

    @Test
    fun `backgrounded activity still receives the result on return`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            prepareSelection(activity)
            assertTrue(PushManager.start("Pushing…") {
                park()
                UploadEngine.PushResult.Success("SHA3", "https://github.com/x/y/commit/SHA3", 1, 1L)
            })
        }
        assertTrue(awaitEntered())

        // App goes to the background mid-push (user pressed Home).
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.CREATED) // stopped, not destroyed
        idle()

        release = true
        assertTrue("push must finish after release", runUntil { !PushManager.isBusy() })
        idle()

        // User returns: activity is resumed and must show the result.
        scenario.moveToState(Lifecycle.State.RESUMED)
        idle()
        scenario.onActivity { activity ->
            assertEquals("https://github.com/x/y/commit/SHA3", activity.lastCommitUrl)
            val viewRepo = activity.findViewById<MaterialButton>(R.id.viewRepoBtn)
            assertEquals(android.view.View.VISIBLE, viewRepo.visibility)
        }
    }

    @Test
    fun `backdate survives rotation`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val epoch = java.util.Calendar.getInstance().apply {
            clear()
            set(2023, java.util.Calendar.DECEMBER, 25, 9, 15, 0)
        }.timeInMillis

        scenario.onActivity { activity ->
            activity.commitAuthorDateForTest = java.util.Calendar.getInstance().apply {
                timeInMillis = epoch
            }
        }

        scenario.recreate()
        idle()

        scenario.onActivity { activity ->
            val restored = activity.commitAuthorDateForTest
            assertNotNull("backdate must survive rotation", restored)
            assertEquals(epoch, restored!!.timeInMillis)
        }
    }

    @Test
    fun `double tapping push does not duplicate the push`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            prepareSelection(activity)
            // Simulate a fast double tap via the manager contract: the
            // second start must be refused because the first is running.
            val first = PushManager.start("tap1") {
                pushesStarted++
                park()
                UploadEngine.PushResult.Success("S", "u", 1, 1L)
            }
            val second = PushManager.start("tap2") {
                pushesStarted++
                UploadEngine.PushResult.Success("S", "u", 1, 1L)
            }
            assertTrue(first)
            assertFalse(second)
        }
        assertTrue(awaitEntered())
        release = true
        assertTrue(runUntil { !PushManager.isBusy() })
        idle()
        assertEquals("exactly one push body may run", 1, pushesStarted)
    }
}
