package com.zippush.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import com.zippush.app.net.GitHubClient
import com.zippush.app.push.PushRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v0.3.1: the push progress must show HOW MANY files are being uploaded.
 * The engine reports one tick per uploaded blob; PushRunner re-emits
 * Running(done,total); the activity turns the bottom bar into a
 * DETERMINATE progress bar and the status line says "x of y files".
 *
 * The commit POST is gated so the push stays in flight while the UI is
 * inspected — without the gate the whole upload completes between two
 * main-looper idles and the transient "x of y" states are unobservable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushProgressTest {

    class App : Application()

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val requestLog = mutableListOf<String>()

    @Before
    fun setUp() {
        PushRunner.resetForTest()
        server = MockWebServer()
        server.start()
        requestLog.clear()
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

    private fun runUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** A ZIP with three files under one root folder — the shape the app
     *  expects (the root folder is stripped on extraction). */
    private fun zipWithThreeFiles(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for (name in listOf("a.txt", "b.txt", "c.txt")) {
                zip.putNextEntry(ZipEntry("docs/$name"))
                zip.write("content of $name".toByteArray())
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun installDispatcher() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestLog += "${request.method} ${request.path}"
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

    /** Holds the commit POST until [gate] opens — keeps the push in flight. */
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

    private fun connect(activity: MainActivity) {
        activity.connectWithBaseUrlForTest(
            "test-token", server.url("/").toString().trimEnd('/')
        )
    }

    private fun preparePush(activity: MainActivity) {
        activity.selectedRepo = GitHubClient.Repo(
            fullName = "alice/repo", name = "repo", owner = "alice",
            private = false, defaultBranch = "main"
        )
        val uri = Uri.parse("content://robolectric.zips/three.zip")
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(zipWithThreeFiles()))
        activity.tracker.pickZip(uri)
        activity.refreshSelectionSummary()
    }

    private fun MainActivity.onPushClickedForTest() {
        val m = MainActivity::class.java.getDeclaredMethod("onPushClicked")
        m.isAccessible = true
        m.invoke(this)
    }

    @Test
    fun `push reports one progress tick per uploaded file`() {
        installDispatcher()
        val seen = mutableListOf<Pair<Int, Int>>()
        PushRunner.addObserver { st ->
            val p = (st as? PushRunner.State.Running)?.progress
            if (p != null) seen.add(p.done to p.total)
        }
        // Gate the commit so the Running(progress) states are observable.
        val gate = CountDownLatch(1)
        gateCommitPostUntil(gate)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { connect(it) }
            assertTrue("repo list must load",
                runUntil { requestLog.any { it.contains("/user/repos") } })
            idle()

            scenario.onActivity { activity ->
                preparePush(activity)
                activity.onPushClickedForTest()
            }

            // All three blobs upload BEFORE the (gated) commit: by the
            // time the blobs are done the counter must have hit 3/3.
            assertTrue("counter must reach 3 of 3",
                runUntil { seen.contains(3 to 3) })
            gate.countDown()

            assertTrue("push must finish",
                runUntil { PushRunner.state is PushRunner.State.Done })

            // The three blobs were each reported, in order.
            assertEquals(
                "every file upload must be reported",
                listOf(1 to 3, 2 to 3, 3 to 3),
                seen
            )
            assertTrue("three blobs must be uploaded",
                requestLog.count { it.contains("/git/blobs") } == 3)
        } finally {
            gate.countDown()
            scenario.close()
        }
    }

    @Test
    fun `status line and bar show x of y files during the push`() {
        installDispatcher()
        val gate = CountDownLatch(1)
        gateCommitPostUntil(gate)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { connect(it) }
            assertTrue(runUntil { requestLog.any { it.contains("/user/repos") } })
            idle()

            scenario.onActivity { activity ->
                preparePush(activity)
                activity.onPushClickedForTest()
            }

            // Mid-push (blobs done, commit gated): the status line shows
            // the counter and the bar is DETERMINATE at 3 of 3.
            assertTrue("counter text must appear",
                runUntil {
                    var ok = false
                    scenario.onActivity { a ->
                        if (a.statusTextForTest().contains("of 3 files")) ok = true
                    }
                    ok
                })
            scenario.onActivity { activity ->
                assertEquals("determinate bar at 3 of 3",
                    Pair(3, 3), activity.pushProgressForTest())
            }
            gate.countDown()

            assertTrue("push must finish",
                runUntil { PushRunner.state is PushRunner.State.Done })
            idle()
            scenario.onActivity { activity ->
                assertTrue("final status is the success",
                    activity.statusTextForTest().contains("Pushed"))
                // After Done the bar resets to indeterminate.
                assertNull("bar back to indeterminate after the push",
                    activity.pushProgressForTest())
            }
        } finally {
            gate.countDown()
            scenario.close()
        }
    }

    @Test
    fun `bar starts indeterminate before the first blob lands`() {
        installDispatcher()
        // Gate the FIRST blob: nothing has a counter yet at that point.
        val firstBlobGate = CountDownLatch(1)
        val base = server.dispatcher!!
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.contains("/git/blobs") == true &&
                    requestLog.none { it.contains("/git/blobs") }
                ) {
                    // still let the request be LOGGED by the base dispatcher
                    // but hold the response until the gate opens.
                    firstBlobGate.await(10, TimeUnit.SECONDS)
                }
                return base.dispatch(request)
            }
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { connect(it) }
            assertTrue(runUntil { requestLog.any { it.contains("/user/repos") } })
            idle()

            scenario.onActivity { activity ->
                preparePush(activity)
                activity.onPushClickedForTest()
            }

            // While the first blob is gated, no counter exists yet: the
            // bar must be the classic indeterminate one.
            assertTrue("push must be running",
                runUntil { PushRunner.state is PushRunner.State.Running })
            Thread.sleep(150) // let any (wrong) early progress land
            scenario.onActivity { activity ->
                assertNull("indeterminate while preparing",
                    activity.pushProgressForTest())
                assertTrue("spinner visible during prepare",
                    activity.isSpinnerVisibleForTest())
            }
            firstBlobGate.countDown()
        } finally {
            firstBlobGate.countDown()
            scenario.close()
        }
    }
}
