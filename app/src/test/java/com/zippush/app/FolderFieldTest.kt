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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v0.3.2: full battery on the TARGET FOLDER field — every situation the
 * field can go through:
 *
 *  A. filling it      — Explorer selection (subfolder, root, new folder,
 *                       breadcrumb jump), free-typed text, suggestions;
 *  B. clearing it     — changing the REPOSITORY (new rule: the folder
 *                       belongs to the previous repo's tree), switching
 *                       account, Clear Selection, after a push;
 *  C. keeping it      — changing only the BRANCH (same repo = same tree),
 *                       screen rotation;
 *  D. consuming it    — the push really targets the chosen folder; the
 *                       sanitize rules apply at the push boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderFieldTest {

    class App : Application()

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val requestLog = mutableListOf<String>()
    /** POST bodies by request path (for tree-path assertions). */
    private val requestBodies = mutableMapOf<String, String>()

    /** Folder listings per repo (key: "repo:path"). */
    private val listings = mutableMapOf(
        "repo:" to """[
            {"name":"assignments","path":"assignments","type":"dir","sha":"a1"},
            {"name":"docs","path":"docs","type":"dir","sha":"a2"}
        ]""",
        "repo:assignments" to """[
            {"name":"week3","path":"assignments/week3","type":"dir","sha":"a3"},
            {"name":"week4","path":"assignments/week4","type":"dir","sha":"a4"}
        ]""",
        "other:" to """[
            {"name":"notes","path":"notes","type":"dir","sha":"b1"}
        ]"""
    )

    @Before
    fun setUp() {
        PushRunner.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestLog += "${request.method} ${request.path}"
                if (request.method == "POST") {
                    requestBodies["${request.path}"] = request.body.readUtf8()
                }
                val p = request.path ?: ""
                val ok = { json: String ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(json)
                }
                return when {
                    p == "/user" -> ok("""{"login":"alice","name":"Alice"}""")
                    p.startsWith("/user/repos") -> ok("""[
                        {"full_name":"alice/repo","name":"repo","owner":{"login":"alice"},"private":false,"default_branch":"main","disabled":false},
                        {"full_name":"alice/other","name":"other","owner":{"login":"alice"},"private":false,"default_branch":"main","disabled":false}
                    ]""")
                    p.startsWith("/repos/alice/repo/branches") ||
                        p.startsWith("/repos/alice/other/branches") ->
                        ok("""[{"name":"main"},{"name":"dev"}]""")
                    // Folder listings for the Explorer.
                    p.startsWith("/repos/alice/") && p.contains("/contents") -> {
                        val repo = p.substringAfter("/repos/alice/").substringBefore("/contents")
                        val dir = p.substringAfter("/contents", "").substringBefore("?").trim('/')
                        listings["$repo:$dir"]?.let { ok(it) }
                            ?: MockResponse().setResponseCode(404)
                                .setBody("""{"message":"Not Found"}""")
                    }
                    // Push sequence (repo or other).
                    p.contains("/git/ref/heads/main") ->
                        ok("""{"ref":"refs/heads/main","object":{"sha":"HEADSHA"}}""")
                    p.contains("/git/commits/HEADSHA") ->
                        ok("""{"sha":"HEADSHA","tree":{"sha":"BASETREE"}}""")
                    p.contains("/git/blobs") ->
                        ok("""{"sha":"BLOB${System.nanoTime()}"}""")
                    p.contains("/git/trees") -> ok("""{"sha":"NEWTREE"}""")
                    p.contains("/git/commits") -> ok("""{"sha":"NEWSHA"}""")
                    p.contains("/git/refs/heads/main") ->
                        ok("""{"object":{"sha":"NEWSHA"}}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"message":"nope"}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        PushRunner.resetForTest()
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

    private fun launchAndConnect(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            it.connectWithBaseUrlForTest("test-token", server.url("/").toString())
        }
        assertTrue("repo list must load",
            runUntil { requestLog.any { r -> r.contains("/user/repos") } })
        idle()
    }

    private fun repo(name: String) = GitHubClient.Repo(
        fullName = "alice/$name", name = name, owner = "alice",
        private = false, defaultBranch = "main"
    )

    /** Opens the Explorer on repo/branch with a given start path. */
    private fun openBrowser(a: MainActivity, repoName: String = "repo", startPath: String = "") {
        a.selectedRepo = repo(repoName)
        a.selectedBranch = GitHubClient.Branch("main")
        a.setTargetDirTextForTest(startPath)
        a.openFolderBrowserForTest()
    }

    private fun zipBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("docs/report.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun preparePush(a: MainActivity) {
        a.selectedRepo = repo("repo")
        val uri = Uri.parse("content://robolectric.zips/one.zip")
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(zipBytes()))
        a.tracker.pickZip(uri)
        a.refreshSelectionSummary()
    }

    private fun MainActivity.onPushClickedForTest() {
        val m = MainActivity::class.java.getDeclaredMethod("onPushClicked")
        m.isAccessible = true
        m.invoke(this)
    }

    // ============================ A. filling the field ============================

    @Test
    fun `A1 explorer selection puts the chosen folder in the field`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { openBrowser(it) }
            assertTrue("root listing must load", runUntil {
                var ok = false
                scenario.onActivity { a ->
                    if (a.folderBrowserRowsForTest()?.contains("assignments") == true) ok = true
                }
                ok
            })
            scenario.onActivity { a -> a.tapBrowserRowForTest(0) } // assignments
            assertTrue(runUntil {
                var p = ""
                scenario.onActivity { p = it.folderBrowserPathForTest() ?: "" }
                p == "assignments"
            })
            scenario.onActivity { a -> a.chooseInFolderBrowserForTest() }
            idle()
            scenario.onActivity { a ->
                assertEquals("assignments", a.targetDirTextForTest())
                assertEquals("assignments", a.lastBrowserSelectionForTest)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `A2 choosing the repository root leaves the field empty`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { openBrowser(it, startPath = "assignments") }
            assertTrue(runUntil { var ok = false; scenario.onActivity { a -> if ((a.folderBrowserRowsForTest()?.size ?: 0) > 0) ok = true }; ok })
            // Jump back to the root via the repo crumb and confirm there.
            scenario.onActivity { a -> a.tapBrowserCrumbForTest(0) }
            assertTrue(runUntil { var p = ""; scenario.onActivity { p = it.folderBrowserPathForTest() ?: "" }; p == "" })
            scenario.onActivity { a -> a.chooseInFolderBrowserForTest() }
            idle()
            scenario.onActivity { a -> assertEquals("", a.targetDirTextForTest()) }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `A3 new folder dialog builds the nested path into the field`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { openBrowser(it) }
            assertTrue(runUntil { var ok = false; scenario.onActivity { a -> if (a.folderBrowserRowsForTest()?.contains("assignments") == true) ok = true }; ok })
            scenario.onActivity { a ->
                a.createFolderInBrowserForTest("assignments/week4")
                a.chooseInFolderBrowserForTest()
            }
            idle()
            scenario.onActivity { a ->
                assertEquals("assignments/week4", a.targetDirTextForTest())
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `A4 free-typed text stays in the field`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { a ->
                a.setTargetDirTextForTest("my/typed/folder")
                assertEquals("my/typed/folder", a.targetDirTextForTest())
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `A5 typed folder becomes a suggestion for the next push`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { a -> a.setTargetDirTextForTest("courses/2026") }
            preparePushVia(scenario)
            scenario.onActivity { it.onPushClickedForTest() }
            assertTrue(runUntil { PushRunner.state is PushRunner.State.Done })
            scenario.onActivity { a ->
                assertTrue(
                    "the pushed folder must be suggested",
                    a.targetDirSuggestionsForTest().contains("courses/2026")
                )
            }
        } finally {
            scenario.close()
        }
    }

    private fun preparePushVia(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { preparePush(it) }
    }

    // ============================ B. clearing the field ============================

    @Test
    fun `B1 changing the repository clears the target folder`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            // Select repo + a folder in it.
            scenario.onActivity { openBrowser(it) }
            assertTrue(runUntil { var ok = false; scenario.onActivity { a -> if (a.folderBrowserRowsForTest()?.contains("assignments") == true) ok = true }; ok })
            scenario.onActivity { a ->
                a.tapBrowserRowForTest(0)
            }
            assertTrue(runUntil { var p = ""; scenario.onActivity { p = it.folderBrowserPathForTest() ?: "" }; p == "assignments" })
            scenario.onActivity { a -> a.chooseInFolderBrowserForTest() }
            idle()
            scenario.onActivity { a -> assertEquals("assignments", a.targetDirTextForTest()) }

            // Now pick the OTHER repository through the real selection
            // path (the dropdown listener route).
            scenario.onActivity { a -> a.selectRepoForTest(repo("other")) }
            assertTrue(runUntil { requestLog.any { it.contains("/repos/alice/other/branches") } })
            idle()
            scenario.onActivity { a ->
                assertEquals(
                    "changing repo must clear the folder of the old repo",
                    "", a.targetDirTextForTest()
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `B2 re-picking the SAME repository keeps the folder`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { a ->
                a.selectedRepo = repo("repo")
                a.setTargetDirTextForTest("assignments")
            }
            // Same repo again — no reason to wipe what the user chose.
            scenario.onActivity { a -> a.selectRepoForTest(repo("repo")) }
            assertTrue(runUntil { requestLog.count { it.contains("/repos/alice/repo/branches") } >= 1 })
            idle()
            scenario.onActivity { a -> assertEquals("assignments", a.targetDirTextForTest()) }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `B3 switching account clears the target folder`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { a ->
                a.selectedRepo = repo("repo")
                a.setTargetDirTextForTest("assignments")
            }
            // Switching account goes through the same reset (v0.3.0) —
            // simulate the second connect of another login.
            scenario.onActivity { a ->
                a.connectWithBaseUrlForTest("token-bob", server.url("/").toString(), asAccountSwitch = true)
            }
            assertTrue(runUntil { requestLog.count { it.contains("/user/repos") } >= 2 })
            idle()
            scenario.onActivity { a ->
                assertEquals(
                    "an account switch must clear the account-bound fields",
                    "", a.targetDirTextForTest()
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `B4 clear selection resets the folder too`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { a ->
                a.selectedRepo = repo("repo")
                a.setTargetDirTextForTest("assignments")
            }
            scenario.onActivity { a ->
                val m = MainActivity::class.java.getDeclaredMethod("onClearClicked")
                m.isAccessible = true
                m.invoke(a)
            }
            idle()
            scenario.onActivity { a -> assertEquals("", a.targetDirTextForTest()) }
        } finally {
            scenario.close()
        }
    }

    // ============================ C. keeping the field ============================

    @Test
    fun `C1 changing only the branch keeps the target folder`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { a ->
                a.selectedRepo = repo("repo")
                a.selectedBranch = GitHubClient.Branch("main")
                a.setTargetDirTextForTest("assignments")
            }
            // Another branch of the SAME repository: the folder tree is
            // the same repo — the selection must survive.
            scenario.onActivity { a ->
                a.selectedBranch = GitHubClient.Branch("dev")
                assertEquals("assignments", a.targetDirTextForTest())
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `C2 the folder survives a screen rotation`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { a ->
                a.selectedRepo = repo("repo")
                a.setTargetDirTextForTest("assignments/week3")
            }
            scenario.recreate()
            idle()
            scenario.onActivity { a ->
                assertEquals("assignments/week3", a.targetDirTextForTest())
            }
        } finally {
            scenario.close()
        }
    }

    // ============================ D. consuming the field ============================

    @Test
    fun `D1 the push really targets the chosen folder`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { a ->
                preparePush(a)
                a.setTargetDirTextForTest("assignments/week3")
                a.onPushClickedForTest()
            }
            assertTrue(runUntil { PushRunner.state is PushRunner.State.Done })
            scenario.onActivity { a ->
                assertTrue("push must succeed: ${a.statusTextForTest()}",
                    a.statusTextForTest().contains("Pushed"))
            }
            // The tree built by the push must carry the target folder in
            // the pushed path. The ZIP's single root folder ("docs/") is
            // stripped on extraction, so the pushed path is
            // "assignments/week3/report.txt".
            val treeBody = requestBodies.entries
                .firstOrNull { it.key.contains("/git/trees") }?.value ?: ""
            assertTrue(
                "tree must target the chosen folder, was: $treeBody",
                treeBody.contains("\"path\":\"assignments\\/week3\\/report.txt\"")
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `D2 messy typed folder is sanitized at the push boundary`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            launchAndConnect(scenario)
            scenario.onActivity { a ->
                preparePush(a)
                a.setTargetDirTextForTest("  ./assignments//week3/  ")
                a.onPushClickedForTest()
            }
            assertTrue(runUntil { PushRunner.state is PushRunner.State.Done })
            scenario.onActivity { a ->
                assertTrue("push must succeed: ${a.statusTextForTest()}",
                    a.statusTextForTest().contains("Pushed"))
            }
            // The sanitized path lands in the tree: "./a//b/" becomes
            // "assignments/week3/report.txt" (root folder stripped).
            val treeBody = requestBodies.entries
                .firstOrNull { it.key.contains("/git/trees") }?.value ?: ""
            assertTrue(
                "messy folder must be sanitized in the tree, was: $treeBody",
                treeBody.contains("\"path\":\"assignments\\/week3\\/report.txt\"")
            )
        } finally {
            scenario.close()
        }
    }
}
