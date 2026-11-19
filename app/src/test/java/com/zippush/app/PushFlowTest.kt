package com.zippush.app

import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import com.google.android.material.button.MaterialButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.zippush.app.engine.UploadEngine
import com.zippush.app.net.GitHubClient
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v0.2.7 behaviour battery for the single-activity push flow.
 *
 * The v0.2.x architecture runs the push inside the activity's own scope:
 * no PushManager singleton, no service, no queue. What must still hold:
 *
 *  - the full user journey (connect -> repos -> real zip -> push)
 *    completes over real HTTP and renders the result in the UI;
 *  - the commit message carries the description body as
 *    "title\n\nbody" (the v0.2.7 feature);
 *  - a second tap while a push is in flight does not start a second
 *    push (activity-local busy flag);
 *  - the backdate survives rotation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushFlowTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val requestLog = mutableListOf<String>()
    private val requestBodies = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        requestLog.clear()
        requestBodies.clear()
    }

    @After
    fun tearDown() {
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

    /** Test hook for the private push entry point. */
    private fun MainActivity.onPushClickedForTest() {
        val m = MainActivity::class.java.getDeclaredMethod("onPushClicked")
        m.isAccessible = true
        m.invoke(this)
    }

    /** Test hook for the activity-local busy flag. */
    private fun MainActivity.isPushRunningForTest(): Boolean {
        val f = MainActivity::class.java.getDeclaredField("pushRunning")
        f.isAccessible = true
        return f.getBoolean(this)
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

    @Test
    fun `full journey push succeeds and resets the form`() {
        installDispatcher()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenarioRef = scenario
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

            assertTrue("push must finish", runUntil { activityPushDone() })
            scenario.onActivity { activity ->
                assertEquals("https://github.com/alice/repo/commit/NEWSHA", activity.lastCommitUrl)
                assertTrue("selection cleared after success", activity.tracker.hasSelection().not())
                assertFalse("description collapsed after success", activity.isDescriptionExpanded())
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
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenarioRef = scenario
        try {
            scenario.onActivity { connect(it) }
            assertTrue(runUntil { requestLog.any { it.contains("/user/repos") } })
            idle()

            scenario.onActivity { activity ->
                preparePush(activity)
                activity.onPushClickedForTest()
                // Second tap immediately after: the activity-local guard
                // must keep exactly one push running.
                activity.onPushClickedForTest()
            }
            idle()
            assertTrue("push must finish", runUntil { activityPushDone() })
            // Exactly one full Git sequence hit the wire.
            val refReads = requestLog.count { it.startsWith("GET /repos/alice/repo/git/ref/heads/main") }
            assertEquals("exactly one push may run", 1, refReads)
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
    }

    /** True when the activity's push coroutine has finished (push button re-enabled). */
    private var scenarioRef: ActivityScenario<MainActivity>? = null

    private fun activityPushDone(): Boolean {
        val scenario = scenarioRef ?: return false
        var done = false
        scenario.onActivity { activity ->
            done = !activity.isBusyFlagForTest() && !activity.isPushRunningForTest()
        }
        return done
    }
}
