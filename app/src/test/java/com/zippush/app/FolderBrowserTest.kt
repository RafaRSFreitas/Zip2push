package com.zippush.app

import org.junit.Rule
import android.widget.AutoCompleteTextView
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import com.zippush.app.net.GitHubClient

/**
 * v0.3.0 end-to-end tests of the target-folder Explorer through the
 * REAL GitHubClient HTTP path (MockWebServer): the popup opens on the
 * selected repo/branch, lists the REAL subfolders (files filtered out),
 * navigates via rows and breadcrumbs, creates a folder name inline and
 * confirms — landing the chosen path in the target-folder field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderBrowserTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var server: MockWebServer
    private lateinit var activity: MainActivity

    /** GitHub-style /contents payload for the paths the tests request. */
    private val listings = mutableMapOf(
        // root: two dirs + two files (files must be filtered out)
        "" to """[
            {"name":"assignments","path":"assignments","type":"dir","sha":"a1"},
            {"name":"docs","path":"docs","type":"dir","sha":"a2"},
            {"name":"README.md","path":"README.md","type":"file","sha":"f1"},
            {"name":"logo.png","path":"logo.png","type":"file","sha":"f2"}
        ]""",
        // assignments: two subdirs + one file
        "assignments" to """[
            {"name":"week3","path":"assignments/week3","type":"dir","sha":"a3"},
            {"name":"week9","path":"assignments/week9","type":"dir","sha":"a4"},
            {"name":"index.md","path":"assignments/index.md","type":"file","sha":"f3"}
        ]""",
        // week3: no subfolders at all (empty-folder state)
        "assignments/week3" to """[
            {"name":"homework.txt","path":"assignments/week3/homework.txt","type":"file","sha":"f4"}
        ]"""
    )

    @Before
    fun setup() {
        SessionState.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/repos/octocat/hello/contents") -> {
                        val dir = path
                            .removePrefix("/repos/octocat/hello/contents")
                            .substringBefore("?")
                            .trim('/')
                        val ref = path.substringAfter("ref=", "")
                        if (ref != "main") {
                            MockResponse().setResponseCode(404)
                                .setBody("""{"message":"branch not found"}""")
                        } else {
                            val body = listings[dir]
                            if (body == null) {
                                MockResponse().setResponseCode(404)
                                    .setBody("""{"message":"Not Found"}""")
                            } else {
                                MockResponse().setResponseCode(200)
                                    .setBody(body)
                            }
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                        .setBody("""{"message":"nope"}""")
                }
            }
        }
        server.start()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        activity = controller.get()
        // Connect through the real client against the mock server.
        activity.connectWithBaseUrlForTest("test-token", server.url("/").toString())
        waitFor { activity.repoItemTextsForTest().isNotEmpty() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        SessionState.resetForTest()
    }

    private fun idle() {
        ShadowLooper.shadowMainLooper().runToEndOfTasks()
    }

    private fun waitFor(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun selectHelloRepo() {
        activity.selectedRepo = GitHubClient.Repo(
            fullName = "octocat/hello", name = "hello", owner = "octocat",
            private = false, defaultBranch = "main"
        )
        activity.selectedBranch = GitHubClient.Branch("main")
    }

    @Test
    fun `explorer lists real repo folders and filters files out`() {
        selectHelloRepo()
        activity.openFolderBrowserForTest()
        assertTrue("browser never opened", waitFor { activity.folderBrowserRowsForTest() != null })
        assertTrue(
            "folder rows never landed",
            waitFor {
                val rows = activity.folderBrowserRowsForTest() ?: emptyList()
                rows.contains("assignments") && rows.contains("docs")
            }
        )
        val rows = activity.folderBrowserRowsForTest()!!
        // Files filtered: only the two dirs + the New-folder action.
        assertEquals(listOf("assignments", "docs", ""), rows)
        // Breadcrumb shows the repo crumb.
        assertEquals(listOf("hello"), activity.folderBrowserCrumbsForTest())
    }

    @Test
    fun `tapping a folder navigates into it and breadcrumbs update`() {
        selectHelloRepo()
        activity.openFolderBrowserForTest()
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("assignments") == true
        })
        // Tap the "assignments" row (position 0).
        activity.tapBrowserRowForTest(0)
        assertTrue(
            "never navigated into assignments",
            waitFor { activity.folderBrowserPathForTest() == "assignments" }
        )
        assertTrue(waitFor {
            val rows = activity.folderBrowserRowsForTest() ?: emptyList()
            rows.containsAll(listOf("week3", "week9"))
        })
        // Breadcrumbs: hello / assignments
        assertEquals(listOf("hello", "assignments"), activity.folderBrowserCrumbsForTest())
    }

    @Test
    fun `breadcrumb tap jumps back to the repo root`() {
        selectHelloRepo()
        activity.openFolderBrowserForTest()
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("assignments") == true
        })
        activity.tapBrowserRowForTest(0)
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "assignments" })
        // Tap the "hello" crumb (index 0) to jump home.
        activity.tapBrowserCrumbForTest(0)
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "" })
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("docs") == true
        })
    }

    @Test
    fun `empty folder state shows and new folder appends to the path`() {
        selectHelloRepo()
        // Start the browser directly at the empty folder (week3).
        activity.findViewById<android.widget.EditText>(R.id.targetDirInput)
            .setText("assignments/week3")
        activity.openFolderBrowserForTest()
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "assignments/week3" })
        assertTrue(waitFor {
            // Only the New-folder action row (no subfolders there).
            activity.folderBrowserRowsForTest() == listOf("")
        })
        // "New folder" appends under the current path LOCALLY (no fetch:
        // the folder does not exist on GitHub until the first push).
        activity.createFolderInBrowserForTest("reports")
        idle()
        assertEquals("assignments/week3/reports", activity.folderBrowserPathForTest())
        // Breadcrumbs reflect the new path; the New-folder row remains.
        assertEquals(
            listOf("hello", "assignments", "week3", "reports"),
            activity.folderBrowserCrumbsForTest()
        )
        assertEquals(listOf(""), activity.folderBrowserRowsForTest())
    }

    @Test
    fun `new folder from the repo root builds a top-level path`() {
        selectHelloRepo()
        activity.openFolderBrowserForTest()
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("assignments") == true
        })
        // A nested name creates nested segments at once.
        activity.createFolderInBrowserForTest("assignments/week4")
        idle()
        assertEquals("assignments/week4", activity.folderBrowserPathForTest())
    }

    @Test
    fun `new folder sanitizes dots and traversal`() {
        selectHelloRepo()
        activity.findViewById<android.widget.EditText>(R.id.targetDirInput).setText("docs")
        activity.openFolderBrowserForTest()
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "docs" })
        activity.createFolderInBrowserForTest("../..//  new  /./")
        idle()
        // Dots, "..", empty segments and stray spaces are dropped.
        assertEquals("docs/new", activity.folderBrowserPathForTest())
    }

    @Test
    fun `choosing a folder writes the path into the target field`() {
        selectHelloRepo()
        activity.openFolderBrowserForTest()
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("assignments") == true
        })
        activity.tapBrowserRowForTest(0)
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "assignments" })
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("week3") == true
        })
        activity.chooseInFolderBrowserForTest()
        // The popup is dismissed and the field carries the chosen path.
        assertTrue(waitFor { activity.folderBrowserRowsForTest() == null })
        val field = activity.findViewById<android.widget.EditText>(R.id.targetDirInput)
        assertEquals("assignments", field.text.toString())
        // Status line confirms the choice.
        assertEquals(
            activity.getString(R.string.browser_selected_folder, "assignments"),
            activity.statusTextForTest()
        )
    }

    @Test
    fun `missing repo selection shows a toast instead of the popup`() {
        activity.selectedRepo = null
        activity.openFolderBrowserForTest()
        idle()
        // No dialog opened.
        assertNull(activity.folderBrowserRowsForTest())
        assertEquals(
            activity.getString(R.string.browser_need_repo),
            ShadowToast.getTextOfLatestToast()
        )
    }
}
