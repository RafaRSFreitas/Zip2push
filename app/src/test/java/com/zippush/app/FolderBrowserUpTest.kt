package com.zippush.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import com.zippush.app.net.GitHubClient

/**
 * v0.3.1: the level-up button of the target-folder Explorer. After
 * navigating into a subfolder the user could only escape via the
 * breadcrumbs — now a dedicated "go up one level" button sits next to
 * them: enabled below the repo root, disabled at the root, and one tap
 * returns to the parent folder (fetching its real listing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderBrowserUpTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var server: MockWebServer
    private lateinit var activity: MainActivity

    private val listings = mutableMapOf(
        "" to """[
            {"name":"assignments","path":"assignments","type":"dir","sha":"a1"},
            {"name":"docs","path":"docs","type":"dir","sha":"a2"}
        ]""",
        "assignments" to """[
            {"name":"week3","path":"assignments/week3","type":"dir","sha":"a3"}
        ]""",
        "assignments/week3" to """[
            {"name":"homework.txt","path":"assignments/week3/homework.txt","type":"file","sha":"f1"}
        ]"""
    )

    @Before
    fun setup() {
        SessionState.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.startsWith("/repos/octocat/hello/contents")) {
                    val dir = path
                        .removePrefix("/repos/octocat/hello/contents")
                        .substringBefore("?").trim('/')
                    listings[dir]?.let {
                        MockResponse().setResponseCode(200).setBody(it)
                    } ?: MockResponse().setResponseCode(404)
                        .setBody("""{"message":"Not Found"}""")
                } else {
                    MockResponse().setResponseCode(404).setBody("""{"message":"nope"}""")
                }
            }
        }
        server.start()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        activity = controller.get()
        activity.connectWithBaseUrlForTest("test-token", server.url("/").toString())
        waitFor { activity.repoItemTextsForTest().isNotEmpty() }
        activity.selectedRepo = GitHubClient.Repo(
            fullName = "octocat/hello", name = "hello", owner = "octocat",
            private = false, defaultBranch = "main"
        )
        activity.selectedBranch = GitHubClient.Branch("main")
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

    @Test
    fun `up button is disabled at the repo root`() {
        activity.openFolderBrowserForTest()
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("assignments") == true
        })
        // Root: no parent — the button stays visible but disabled.
        assertEquals(false, activity.folderBrowserUpEnabledForTest())
    }

    @Test
    fun `up button enables inside a folder and returns to the parent`() {
        activity.openFolderBrowserForTest()
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("assignments") == true
        })
        activity.tapBrowserRowForTest(0) // -> assignments
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "assignments" })
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("week3") == true
        })
        // Inside a folder: enabled.
        assertEquals(true, activity.folderBrowserUpEnabledForTest())

        // One tap returns to the parent (with the real root listing).
        activity.tapBrowserUpForTest()
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "" })
        assertTrue(waitFor {
            val rows = activity.folderBrowserRowsForTest() ?: emptyList()
            rows.containsAll(listOf("assignments", "docs"))
        })
        // Back at the root: disabled again.
        assertEquals(false, activity.folderBrowserUpEnabledForTest())
    }

    @Test
    fun `up from a nested folder goes one level not to the root`() {
        // Navigate to assignments/week3 in two hops.
        activity.openFolderBrowserForTest()
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("assignments") == true
        })
        activity.tapBrowserRowForTest(0)
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "assignments" })
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("week3") == true
        })
        activity.tapBrowserRowForTest(0)
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "assignments/week3" })
        // The listing fetch of the destination must be done before the
        // next navigation (goUp ignores taps while a listing is loading).
        assertTrue(waitFor { !activity.folderBrowserLoadingForTest() })

        // Up ONE level: assignments, not the root.
        activity.tapBrowserUpForTest()
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "assignments" })
        assertTrue(waitFor { !activity.folderBrowserLoadingForTest() })
        assertTrue(activity.folderBrowserUpEnabledForTest() == true)

        // A second up returns to the root.
        activity.tapBrowserUpForTest()
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "" })
    }

    @Test
    fun `up works after creating a new folder locally`() {
        activity.openFolderBrowserForTest()
        assertTrue(waitFor {
            activity.folderBrowserRowsForTest()?.contains("assignments") == true
        })
        activity.tapBrowserRowForTest(0)
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "assignments" })
        // "New folder" builds the path locally (no listing exists yet).
        activity.createFolderInBrowserForTest("reports")
        idle()
        assertEquals("assignments/reports", activity.folderBrowserPathForTest())
        // Up must still work from a locally-created path.
        activity.tapBrowserUpForTest()
        assertTrue(waitFor { activity.folderBrowserPathForTest() == "assignments" })
    }
}
