package com.zippush.app

import android.app.Application
import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import com.zippush.app.push.PushRunner
import com.zippush.app.push.PushService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.zippush.app.engine.UploadEngine
import com.zippush.app.net.GitHubClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v0.2.8 behaviour battery: the push must GO UNTIL THE END.
 *
 * The upload now lives in the app-scoped PushRunner, so:
 *  - the full user journey (connect -> repos -> real zip -> push)
 *    completes over real HTTP and renders the result in the UI;
 *  - the commit message carries the description body as
 *    "title\n\nbody" (the v0.2.7 feature, unchanged);
 *  - a second tap while a push is in flight does not start a second
 *    push;
 *  - ROTATION MID-PUSH does not kill the upload: the recreated
 *    activity re-attaches and shows the result;
 *  - even DESTROYING the activity mid-push (user leaves) lets the
 *    push finish in the runner;
 *  - the backdate survives rotation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PushFlowTest.App::class)
class PushFlowTest {

    /** Plain Application: no test-only init that could mask lifecycle bugs. */
    class App : Application()

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val requestLog = mutableListOf<String>()
    private val requestBodies = mutableListOf<String>()

    @Before
    fun setUp() {
        PushRunner.resetForTest()
        server = MockWebServer()
        server.start()
        requestLog.clear()
        requestBodies.clear()
    }

    @After
    fun tearDown() {
        PushRunner.resetForTest()
        server.shutdown()
    }

    private fun ok(json: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(json)

    private fun idle() {
        ShadowLooper.shadowMainLooper().idle()
    }

    /** Runs main-looper tasks until [condition] or timeout (real time). */
    private fun runUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** A real ZIP, as the document picker would hand to the app. */
    private fun realZip(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("docs/report.txt"))
            zip.write("hello zip push".toByteArray())
            zip.closeEntry()
        }
        return bos.toByteArray()
    }

    /** Full GitHub script: /user, /user/repos, then a full push sequence. */
    private fun installDispatcher() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestLog += "${request.method} ${request.path}"
                requestBodies += request.body.readUtf8()
                val p = request.path ?: ""
                return when {
                    p == "/user" ->
                        ok("""{"login":"alice","name":"Alice"}""")
                    p.startsWith("/user/repos") ->
                        ok("""[{"full_name":"alice/repo","name":"repo","owner":{"login":"alice"},"private":false,"default_branch":"main","disabled":false}]""")
                    p.startsWith("/repos/alice/repo/branches") ->
                        ok("""[{"name":"main"}]""")
                    p.startsWith("/repos/alice/repo/git/ref/heads/main") ->
                        ok("""{"ref":"refs/heads/main","object":{"sha":"HEADSHA"}}""")
                    p.startsWith("/repos/alice/repo/git/commits/HEADSHA") ->
                        ok("""{"sha":"HEADSHA","tree":{"sha":"BASETREE"}}""")
                    p.startsWith("/repos/alice/repo/git/blobs") ->
                        ok("""{"sha":"BLOB${System.nanoTime()}"}""")
                    p.startsWith("/repos/alice/repo/git/trees") ->
                        ok("""{"sha":"NEWTREE"}""")
                    p.startsWith("/repos/alice/repo/git/commits") ->
                        ok("""{"sha":"NEWSHA"}""")
                    p.startsWith("/repos/alice/repo/git/refs/heads/main") ->
                        ok("""{"object":{"sha":"NEWSHA"}}""")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
    }

    /** Connects the activity to the test server through the real HTTP path. */
    private fun connect(activity: MainActivity) {
        activity.connectWithBaseUrlForTest("test-token", server.url("/").toString().trimEnd('/'))
    }

    private fun preparePush(activity: MainActivity) {
        activity.selectedRepo = GitHubClient.Repo(
            fullName = "alice/repo", name = "repo", owner = "alice",
            private = false, defaultBranch = "main"
        )
        val uri = Uri.parse("content://robolectric.zips/journey.zip")
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(realZip()))
        activity.tracker.pickZip(uri)
        activity.refreshSelectionSummary()
    }

    /** Test hook for the private push entry point. */
    private fun MainActivity.onPushClickedForTest() {
        val m = MainActivity::class.java.getDeclaredMethod("onPushClicked")
        m.isAccessible = true
        m.invoke(this)
    }

    @Test
    fun `full journey push succeeds and resets the form`() {
        installDispatcher()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { connect(it) }
            assertTrue("repo list must load", runUntil { requestLog.any { it.contains("/user/repos") } })
            idle()

            scenario.onActivity { activity ->
                preparePush(activity)
                activity.setDescriptionTextForTest("line one\nline two")
                activity.onPushClickedForTest()
            }
            idle()

            assertTrue("push must finish", runUntil { PushRunner.state is PushRunner.State.Done })
            scenario.onActivity { activity ->
                assertEquals("https://github.com/alice/repo/commit/NEWSHA", activity.lastCommitUrl)
                assertTrue("selection cleared after success", activity.tracker.hasSelection().not())
                assertFalse("description collapsed after success", activity.isDescriptionExpanded())
                assertTrue("status bar shows success", activity.statusTextForTest().contains("Pushed"))
            }
            // v0.2.9 render-once: rotating right after the result must not
            // replay the toast nor re-clear a form the user started
            // filling for the next push.
            scenario.onActivity { activity ->
                activity.setDescriptionTextForTest("next push body")
            }
            val toastsBefore = org.robolectric.shadows.ShadowToast.shownToastCount()
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                assertEquals("no re-toast after rotation",
                    toastsBefore, org.robolectric.shadows.ShadowToast.shownToastCount())
                assertEquals("typed description must survive the rotation after a result",
                    "next push body", activity.descriptionTextForTest())
                // The bar still shows the previous push's result.
                assertTrue(activity.statusTextForTest().contains("Pushed"))
            }
            // The description really reached the commit message (title \n\n body).
            val commit = requestBodies.first { it.contains("\"message\"") }
            assertTrue(
                "commit message must carry title + blank line + body, got: $commit",
                commit.contains("Upload via ZipPush\\n\\nline one\\nline two")
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `second tap while pushing is refused`() {
        installDispatcher()
        // Gate the commit POST so the push is provably still running
        // when the second tap lands (MockWebServer answers instantly).
        val commitGate = CountDownLatch(1)
        gateCommitPostUntil(commitGate)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { connect(it) }
            assertTrue(runUntil { requestLog.any { it.contains("/user/repos") } })
            idle()

            scenario.onActivity { activity ->
                preparePush(activity)
                activity.onPushClickedForTest()
                // Second tap immediately after: the runner keeps exactly
                // one push at a time. The refusal is a TOAST now, and the
                // status bar stays on the push (v0.2.9) — with the file
                // counter once the first blob landed (v0.3.1).
                activity.onPushClickedForTest()
                val status = activity.statusTextForTest()
                assertTrue(
                    "status bar must stay on the push, was: $status",
                    status == "Pushing to alice/repo…" ||
                        status.startsWith("Pushing to alice/repo — ")
                )
                assertTrue("spinner must be spinning mid-push",
                    activity.isSpinnerVisibleForTest())
            }
            idle()
            // The refusal toast really fired.
            assertEquals("second tap must toast 'in progress'",
                "Push already in progress…",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            commitGate.countDown()
            assertTrue("push must finish", runUntil { PushRunner.state is PushRunner.State.Done })
            // Exactly one full Git sequence hit the wire.
            val refReads = requestLog.count { it.startsWith("GET /repos/alice/repo/git/ref/heads/main") }
            assertEquals("exactly one push may run", 1, refReads)
        } finally {
            commitGate.countDown()
            scenario.close()
        }
    }

    /**
     * THE v0.2.8 regression test: rotating the device mid-push must not
     * cancel the upload. The first activity is destroyed (its scope
     * cancelled — the old 0.2.7 behaviour died here) while the runner's
     * HTTP sequence is still in flight; the recreated activity must see
     * the push through to "Pushed".
     */
    @Test
    fun `push survives screen rotation started before completion`() {
        installDispatcher()
        // Gate the push: the commit POST only answers after the rotation
        // has happened, proving the upload crossed the recreation.
        val rotationDone = CountDownLatch(1)
        gateCommitPostUntil(rotationDone)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { connect(it) }
            assertTrue(runUntil { requestLog.any { it.contains("/user/repos") } })
            idle()

            scenario.onActivity { activity ->
                preparePush(activity)
                activity.onPushClickedForTest()
            }
            // Wait until the push is really in flight (branch head read).
            assertTrue("push must be running before rotation",
                runUntil { requestLog.any { it.contains("/git/ref/heads/main") } })

            // Rotate: destroys the activity (and its scope) mid-push.
            scenario.recreate()
            idle()

            // THE reported bug (v0.2.9): right after rotation the bar
            // must still say the push is running and the spinner must be
            // visible — the auto-connect ("Verifying token…", "Connected
            // as…") and the repo refresh may NOT overwrite or clear it.
            scenario.onActivity { activity ->
                assertTrue("push still running after rotation",
                    PushRunner.state is PushRunner.State.Running)
                assertTrue("spinner must stay visible after rotation",
                    activity.isSpinnerVisibleForTest())
                assertTrue("status bar must still show the push after rotation: '" +
                    activity.statusTextForTest() + "'",
                    activity.statusTextForTest().contains("Pushing to alice/repo"))
                assertTrue("Push must stay disabled mid-push",
                    !activity.pushBtnEnabledForTest())
            }

            rotationDone.countDown()
            assertTrue("push must finish after rotation",
                runUntil { PushRunner.state is PushRunner.State.Done })
            scenario.onActivity { activity ->
                val done = PushRunner.state as PushRunner.State.Done
                assertTrue("result must be success after rotation",
                    done.result is UploadEngine.PushResult.Success)
                assertEquals("https://github.com/alice/repo/commit/NEWSHA", activity.lastCommitUrl)
                assertTrue("status bar must show the success",
                    activity.statusTextForTest().contains("Pushed"))
                assertTrue("spinner must be gone after the push",
                    !activity.isSpinnerVisibleForTest())
            }
            val refReads = requestLog.count { it.startsWith("GET /repos/alice/repo/git/ref/heads/main") }
            assertEquals("no second push after rotation", 1, refReads)
        } finally {
            rotationDone.countDown()
            scenario.close()
        }
    }

    /**
     * Even with NO activity at all (user backed out of the app mid-push),
     * the runner completes the upload; a freshly launched activity then
     * shows the finished result.
     */
    @Test
    fun `push completes with no activity attached and result shows on relaunch`() {
        installDispatcher()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { connect(it) }
            assertTrue(runUntil { requestLog.any { it.contains("/user/repos") } })
            idle()

            scenario.onActivity { activity ->
                preparePush(activity)
                activity.onPushClickedForTest()
            }
            assertTrue("push must be running before close",
                runUntil { requestLog.any { it.contains("/git/ref/heads/main") } })

            // Close the activity entirely (finish + destroy).
            scenario.close()

            assertTrue("push must finish without any activity",
                runUntil { PushRunner.state is PushRunner.State.Done })
            val done = PushRunner.state as PushRunner.State.Done
            assertTrue(done.result is UploadEngine.PushResult.Success)

            // A fresh activity re-attaches and renders the stored result.
            val second = ActivityScenario.launch(MainActivity::class.java)
            try {
                second.onActivity { activity ->
                    assertEquals("https://github.com/alice/repo/commit/NEWSHA", activity.lastCommitUrl)
                }
            } finally {
                second.close()
            }
        } finally {
            scenario.close()
        }
    }

    /**
     * The foreground service lifecycle: the start intent is issued when
     * a push starts (while the UI is started).
     */
    @Test
    fun `foreground service starts with push`() {
        installDispatcher()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { connect(it) }
            assertTrue(runUntil { requestLog.any { it.contains("/user/repos") } })
            idle()

            // Gate the commit POST so the push is still in flight while
            // we inspect the service start (deterministic mid-push view).
            val commitGate = CountDownLatch(1)
            gateCommitPostUntil(commitGate)

            scenario.onActivity { activity ->
                preparePush(activity)
                activity.onPushClickedForTest()
            }
            // The startService intent is issued synchronously by the push
            // click (while the activity is started); drain the queue.
            idle()
            val started = serviceStartIntents().any {
                it.component?.className == PushService::class.java.name
            }
            assertTrue("push must start the foreground service", started)
            assertTrue("push must be running while gated",
                PushRunner.state is PushRunner.State.Running)

            commitGate.countDown()
            assertTrue("push must finish", runUntil { PushRunner.state is PushRunner.State.Done })
        } finally {
            scenario.close()
        }
    }

    /**
     * The v0.2.x regression kept: a fresh activity shows busy while an
     * inherited push still runs (blocks Push, shows progress).
     */
    @Test
    fun `recreated activity shows busy while inherited push runs`() {
        installDispatcher()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { connect(it) }
            assertTrue(runUntil { requestLog.any { it.contains("/user/repos") } })
            idle()

            // Gate the commit POST so the push is provably still running
            // when the recreated activity renders.
            val commitGate = CountDownLatch(1)
            gateCommitPostUntil(commitGate)

            scenario.onActivity { activity ->
                preparePush(activity)
                activity.onPushClickedForTest()
            }
            assertTrue("push must reach the wire before rotation",
                runUntil { requestLog.any { it.contains("/git/ref/heads/main") } })
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue("recreated activity must show busy", activity.isBusyFlagForTest())
            }
            commitGate.countDown()
            assertTrue(runUntil { PushRunner.state is PushRunner.State.Done })
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `backdate survives rotation`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
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
        ShadowLooper.shadowMainLooper().idle()
        scenario.onActivity { activity ->
            val restored = activity.commitAuthorDateForTest
            assertNotNull("backdate must survive rotation", restored)
            assertEquals(epoch, restored!!.timeInMillis)
        }
        scenario.close()
    }

    /**
     * Wraps the installed dispatcher so the commit POST (the last write
     * of a push) only answers after [gate] opens. Note the wrapper does
     * NOT touch the request log — the wrapped dispatcher already logs.
     */
    private fun gateCommitPostUntil(gate: CountDownLatch) {
        val base = server.dispatcher!!
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.contains("/git/commits") == true &&
                    request.method == "POST"
                ) {
                    assertTrue("gate must open while push is running",
                        gate.await(10, TimeUnit.SECONDS))
                }
                return base.dispatch(request)
            }
        }
    }

    /** All service start intents seen so far by the shadow application. */
    private fun serviceStartIntents(): List<Intent> {
        val app = context as android.app.Application
        val shadow = Shadows.shadowOf(app)
        val out = mutableListOf<Intent>()
        while (true) {
            val next: Intent? = try {
                shadow.peekNextStartedService()
            } catch (e: Throwable) {
                null
            }
            if (next == null) break
            // Remove it from the shadow's queue so the loop advances.
            try {
                shadow.nextStartedService
            } catch (e: Throwable) {
                break
            }
            out += next
        }
        return out
    }
}
