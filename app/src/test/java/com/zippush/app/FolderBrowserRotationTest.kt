package com.zippush.app

import android.os.Bundle
import android.widget.EditText
import com.zippush.app.net.GitHubClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * v0.4.0: rotation persistence of the full-screen folder picker, driven
 * through the REAL state machine (saved instance bundle -> onCreate ->
 * reconnect -> loadRepos -> maybeReopenPendingBrowser):
 *
 *  - a REMOTE path reopens on the same folder after the reconnect;
 *  - a LOCAL path (folder created inline, absent on GitHub) restores
 *    with NO network fetch for it;
 *  - a picker that was CLOSED before the rotation stays closed;
 *  - a failed reconnection leaves the picker closed and the app usable.
 *
 * The vault cannot hold a token under Robolectric (Keystore is not
 * available), so the reconnect is driven by the sanctioned test seams
 * ([MainActivity.armBrowserReopenForTest] re-arms exactly the state the
 * saved bundle carried, and the repo list is faked) — the reopen itself
 * runs the REAL code path (loadRepos -> stillThere -> maybeReopen...).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderBrowserRotationTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var server: MockWebServer
    private val requests = mutableListOf<String>()

    /** GitHub-style /contents payloads for the paths the tests request. */
    private val listings = mapOf(
        "" to """[
            {"name":"assignments","path":"assignments","type":"dir","sha":"a1"},
            {"name":"docs","path":"docs","type":"dir","sha":"a2"},
            {"name":"README.md","path":"README.md","type":"file","sha":"f1"}
        ]""",
        "assignments" to """[
            {"name":"week3","path":"assignments/week3","type":"dir","sha":"a3"},
            {"name":"week9","path":"assignments/week9","type":"dir","sha":"a4"},
            {"name":"index.md","path":"assignments/index.md","type":"file","sha":"f3"}
        ]"""
    )

    @Before
    fun setUp() {
        SessionState.resetForTest()
        requests.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                requests.add(path)
                return when {
                    path.startsWith("/repos/octocat/hello/contents") -> {
                        val dir = path
                            .removePrefix("/repos/octocat/hello/contents")
                            .substringBefore("?")
                            .trim('/')
                        val body = listings[dir]
                        if (body == null) {
                            MockResponse().setResponseCode(404)
                                .setBody("""{"message":"Not Found"}""")
                        } else {
                            MockResponse().setResponseCode(200).setBody(body)
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                        .setBody("""{"message":"nope"}""")
                }
            }
        }
        server.start()
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

    private fun helloRepo() = GitHubClient.Repo(
        fullName = "octocat/hello", name = "hello", owner = "octocat",
        private = false, defaultBranch = "main"
    )

    /** Opens the picker starting at [path] and waits for the rows. */
    private fun openAt(activity: MainActivity, path: String): Boolean {
        activity.findViewById<EditText>(R.id.targetDirInput).setText(path)
        activity.openFolderBrowserForTest()
        return waitFor { activity.folderBrowserRowsForTest() != null }
    }

    /**
     * The rotation itself: saves the instance state (the picker position
     * goes into the bundle through the REAL onSaveInstanceState), kills
     * the activity and creates a fresh one from the same bundle.
     */
    private fun rotate(controller: ActivityController<MainActivity>): MainActivity {
        val bundle = Bundle()
        controller.saveInstanceState(bundle).pause().stop().destroy()
        return Robolectric.buildActivity(MainActivity::class.java)
            .create(bundle).start().resume().visible().get()
    }

    @Test
    fun `picker reopens on the same remote folder after rotation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        activity.connectWithBaseUrlForTest("test-token", server.url("/").toString())
        activity.selectedRepo = helloRepo()
        activity.selectedBranch = GitHubClient.Branch("main")

        assertTrue("picker never opened", openAt(activity, "assignments"))
        assertTrue(waitFor { activity.folderBrowserRowsForTest()?.contains("week3") == true })
        assertTrue("picker must be full-screen", activity.folderBrowserIsFullScreenForTest() == true)

        val fresh = rotate(controller)
        // The reopen must not ALTER the field: capture whatever the fresh
        // activity restored (Robolectric does not replay the saved EditText
        // state) and require it to be identical after the reopen.
        val fieldBeforeReopen =
            fresh.findViewById<EditText>(R.id.targetDirInput).text.toString()
        // Re-arm the pending state the saved bundle carried (the vault
        // cannot hold a token here) and reconnect like the auto-connect.
        fresh.armBrowserReopenForTest("assignments", local = false)
        fresh.fakeRepoListForTest = listOf(helloRepo())
        fresh.connectWithBaseUrlForTest("test-token", server.url("/").toString())

        assertTrue(
            "picker never reopened on the same folder",
            waitFor { fresh.folderBrowserPathForTest() == "assignments" }
        )
        assertTrue(waitFor { fresh.folderBrowserRowsForTest()?.contains("week3") == true })
        assertTrue("reopened picker must be full-screen", fresh.folderBrowserIsFullScreenForTest() == true)
        assertFalse(fresh.folderBrowserIsLocalPathForTest() == true)
        // The target field is untouched by the reopen.
        assertEquals(
            fieldBeforeReopen,
            fresh.findViewById<EditText>(R.id.targetDirInput).text.toString()
        )
    }

    @Test
    fun `picker restores a locally created folder without refetching it`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        activity.connectWithBaseUrlForTest("test-token", server.url("/").toString())
        activity.selectedRepo = helloRepo()
        activity.selectedBranch = GitHubClient.Branch("main")

        assertTrue("picker never opened", openAt(activity, ""))
        assertTrue(waitFor { activity.folderBrowserRowsForTest()?.contains("assignments") == true })

        // "New folder…" builds the path LOCALLY (no fetch: the folder does
        // not exist on GitHub until the first push).
        activity.createFolderInBrowserForTest("reports")
        idle()
        assertEquals("reports", activity.folderBrowserPathForTest())
        assertEquals(true, activity.folderBrowserIsLocalPathForTest())

        val fresh = rotate(controller)
        fresh.armBrowserReopenForTest("reports", local = true)
        fresh.fakeRepoListForTest = listOf(helloRepo())
        fresh.connectWithBaseUrlForTest("test-token", server.url("/").toString())

        assertTrue(
            "local path never restored",
            waitFor { fresh.folderBrowserPathForTest() == "reports" }
        )
        assertTrue(fresh.folderBrowserIsLocalPathForTest() == true)
        // The local state is back: only the New-folder row, confirm enabled.
        assertEquals(listOf(""), fresh.folderBrowserRowsForTest())
        assertEquals(false, fresh.folderBrowserLoadingForTest())
        // And NO listing was fetched for the local path (it would 404).
        assertTrue(
            "local path was fetched over the network: $requests",
            requests.none { it.contains("/contents/") && it.contains("reports") }
        )
        assertTrue(fresh.folderBrowserIsFullScreenForTest() == true)
    }

    @Test
    fun `picker does not reopen after rotation when it was closed`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        activity.connectWithBaseUrlForTest("test-token", server.url("/").toString())
        activity.selectedRepo = helloRepo()
        activity.selectedBranch = GitHubClient.Branch("main")

        assertTrue("picker never opened", openAt(activity, "assignments"))
        assertTrue(waitFor { activity.folderBrowserRowsForTest()?.contains("week3") == true })

        // Close BEFORE saving state: closed stays closed.
        activity.dismissFolderBrowserForTest()
        assertTrue(waitFor { activity.folderBrowserRowsForTest() == null })

        val fresh = rotate(controller)
        // No arm here: nothing was saved (the picker was closed).
        fresh.fakeRepoListForTest = listOf(helloRepo())
        fresh.connectWithBaseUrlForTest("test-token", server.url("/").toString())
        idle()
        idle()
        assertNull("closed picker must not reopen", fresh.folderBrowserRowsForTest())
    }

    @Test
    fun `picker stays closed when the reconnection fails`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        activity.connectWithBaseUrlForTest("test-token", server.url("/").toString())
        activity.selectedRepo = helloRepo()
        activity.selectedBranch = GitHubClient.Branch("main")

        assertTrue("picker never opened", openAt(activity, "assignments"))
        assertTrue(waitFor { activity.folderBrowserRowsForTest()?.contains("week3") == true })

        val fresh = rotate(controller)
        fresh.armBrowserReopenForTest("assignments", local = false)
        // NO fake repo list: the reconnect's repo listing hits the mock's
        // 404 fallback and fails — the picker must NOT reopen and the app
        // must stay usable.
        fresh.connectWithBaseUrlForTest("test-token", server.url("/").toString())
        idle()
        idle()
        assertNull("picker must not reopen on a failed reconnect", fresh.folderBrowserRowsForTest())
        assertEquals(false, fresh.isBusyFlagForTest())
    }
}
