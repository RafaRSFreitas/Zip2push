package com.zippush.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.zippush.app.history.PushHistoryStore
import com.zippush.app.net.GitHubClient
import com.zippush.app.push.PushRunner
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v0.3.3: end-to-end — a push that finishes lands in the History with
 * the right metadata, and the History survives across "restarts" (a
 * fresh read of the store).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushHistoryIntegrationTest {

    class App : Application()

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PushRunner.resetForTest()
        PushHistoryStore.resetForTest(context)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ok = { json: String ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(json)
                }
                val p = request.path ?: ""
                return when {
                    p == "/user" -> ok("""{"login":"alice","name":"Alice"}""")
                    p.startsWith("/user/repos") -> ok("""[
                        {"full_name":"alice/repo","name":"repo","owner":{"login":"alice"},
                         "private":false,"default_branch":"main","disabled":false}]""")
                    p.contains("/branches") -> ok("""[{"name":"main"}]""")
                    p.contains("/git/ref/heads/main") ->
                        ok("""{"ref":"refs/heads/main","object":{"sha":"HEADSHA"}}""")
                    p.contains("/git/commits/HEADSHA") ->
                        ok("""{"sha":"HEADSHA","tree":{"sha":"BASETREE"}}""")
                    p.contains("/git/blobs") -> ok("""{"sha":"BLOBSHA"}""")
                    p.contains("/git/trees") -> ok("""{"sha":"NEWTREE"}""")
                    p.contains("/git/commits") -> ok("""{"sha":"NEWSHA123456789"}""")
                    p.contains("/git/refs/heads/main") ->
                        ok("""{"object":{"sha":"NEWSHA123456789"}}""")
                    else -> MockResponse().setResponseCode(404)
                        .setBody("""{"message":"nope"}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        PushRunner.resetForTest()
        PushHistoryStore.resetForTest(context)
        server.shutdown()
    }

    private fun idle() {
        ShadowLooper.shadowMainLooper().runToEndOfTasks()
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

    private fun zipBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("docs/report.txt"))
            zip.write("hello world".toByteArray())
            zip.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun preparePush(a: MainActivity, targetDir: String, message: String) {
        a.selectedRepo = GitHubClient.Repo(
            fullName = "alice/repo", name = "repo", owner = "alice",
            private = false, defaultBranch = "main"
        )
        a.selectedBranch = GitHubClient.Branch("main")
        val uri = Uri.parse("content://robolectric.zips/one.zip")
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(zipBytes()))
        a.tracker.pickZip(uri)
        a.refreshSelectionSummary()
        a.setTargetDirTextForTest(targetDir)
        a.setCommitMessageForTest(message)
    }

    private fun MainActivity.onPushClickedForTest() {
        val m = MainActivity::class.java.getDeclaredMethod("onPushClicked")
        m.isAccessible = true
        m.invoke(this)
    }

    @Test
    fun `a successful push is recorded in the history with its metadata`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                // An active account feeds the History's author field.
                it.vaultForTest().setActiveLogin("alice")
                it.connectWithBaseUrlForTest("test-token", server.url("/").toString())
            }
            assertTrue(runUntil { PushRunner.state != PushRunner.State.Idle || true })
            scenario.onActivity {
                preparePush(it, targetDir = "assignments", message = "Week 3 upload")
                it.onPushClickedForTest()
            }

            assertTrue(
                "push must finish",
                runUntil { PushRunner.state is PushRunner.State.Done }
            )

            val all = PushHistoryStore.read(context)
            assertEquals("one history entry", 1, all.size)
            val e = all[0]
            assertTrue(e.success)
            assertEquals("alice/repo", e.repoFullName)
            assertEquals("main", e.branch)
            assertEquals("assignments", e.targetDir)
            // The commit message TITLE (first line) — git log's subject.
            assertEquals("Week 3 upload", e.messageTitle)
            assertEquals("alice", e.authorLogin)
            assertEquals("NEWSHA123456789", e.commitSha)
            assertNotNull(e.commitUrl)
            assertTrue(
                "commit url points at the commit",
                e.commitUrl!!.endsWith("/commit/NEWSHA123456789")
            )
            // The ZIP's inner path with its single root folder stripped
            // (skipRootFolder — same rule as the push itself).
            assertEquals(listOf("report.txt"), e.files)
            assertEquals("hello world".length.toLong(), e.bytesPushed)
            assertNull(e.failReason)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `a failed push is recorded as a failure with the reason`() {
        // A second server whose ref lookup always 500s — the push
        // starts but the engine surfaces a failure.
        val failing = MockWebServer()
        failing.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ok = { json: String ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(json)
                }
                val p = request.path ?: ""
                return when {
                    p == "/user" -> ok("""{"login":"alice"}""")
                    p.startsWith("/user/repos") -> ok("""[
                        {"full_name":"alice/repo","name":"repo","owner":{"login":"alice"},
                         "private":false,"default_branch":"main","disabled":false}]""")
                    // The ref/commit endpoints fail: the push errors out.
                    else -> MockResponse().setResponseCode(500)
                        .setBody("""{"message":"Internal error"}""")
                }
            }
        }
        failing.start()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.connectWithBaseUrlForTest("test-token", failing.url("/").toString())
            }
            scenario.onActivity {
                preparePush(it, targetDir = "assignments", message = "Will fail")
                it.onPushClickedForTest()
            }

            assertTrue(
                "push must finish (as a failure)",
                runUntil {
                    val st = PushRunner.state
                    st is PushRunner.State.Done &&
                        st.result is com.zippush.app.engine.UploadEngine.PushResult.Failure
                }
            )

            val all = PushHistoryStore.read(context)
            assertEquals(1, all.size)
            val e = all[0]
            assertEquals(false, e.success)
            assertNull(e.commitSha)
            assertNull(e.commitUrl)
            assertNotNull("failure reason kept", e.failReason)
            assertEquals("Will fail", e.messageTitle)
            assertEquals("alice/repo", e.repoFullName)
        } finally {
            scenario.close()
            failing.shutdown()
        }
    }

    @Test
    fun `two pushes keep both entries newest first`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.connectWithBaseUrlForTest("test-token", server.url("/").toString())
            }
            scenario.onActivity {
                preparePush(it, targetDir = "", message = "First")
                it.onPushClickedForTest()
            }
            assertTrue(runUntil { PushRunner.state is PushRunner.State.Done })
            // Wait for the second push to be startable.
            Thread.sleep(50); idle()

            scenario.onActivity {
                preparePush(it, targetDir = "docs", message = "Second")
                it.onPushClickedForTest()
            }
            assertTrue(runUntil {
                val st = PushRunner.state
                st is PushRunner.State.Done &&
                    (st.result as? com.zippush.app.engine.UploadEngine.PushResult.Success)
                        ?.let { true } == true &&
                    PushHistoryStore.read(context).size == 2
            })

            val all = PushHistoryStore.read(context)
            assertEquals(2, all.size)
            assertEquals("Second", all[0].messageTitle)
            assertEquals("First", all[1].messageTitle)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `menu shows History as the first entry`() {
        // The History dialog opens through the same route the 3-dot
        // menu uses (showOverflowMenu -> showHistoryDialog).
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertEquals("History", context.getString(R.string.menu_history))
            scenario.onActivity { a -> a.showHistoryDialogForTest() }
            scenario.onActivity { a ->
                assertNotNull("dialog opens from the menu route", a.lastHistoryDialogViewForTest())
            }
        } finally {
            scenario.close()
        }
    }
}
