package com.zippush.app

import org.junit.Rule
import android.widget.AutoCompleteTextView
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
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
import com.zippush.app.net.GitHubClient

/**
 * v0.3.0: switching the GitHub account must refresh the "Choose what to
 * push" card automatically — the repo/branch/target-folder selections
 * made for the previous account cannot survive a switch (the new
 * account has other repositories), while the picked FILES stay (they
 * belong to the user, not to the account).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountSwitchCardResetTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var server: MockWebServer
    private lateinit var activity: MainActivity

    /** Which account the mock /user endpoint currently answers as. */
    private var activeLogin = "alice"

    @Before
    fun setup() {
        SessionState.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    // Order matters: /user/repos must be matched before the
                    // bare /user verification endpoint.
                    path.startsWith("/user/repos") -> MockResponse().setResponseCode(200)
                        .setBody(
                            JSONArray()
                                .put(repoJson("alice/alpha"))
                                .put(repoJson("alice/beta"))
                                .toString()
                        )
                    path.startsWith("/user") -> MockResponse().setResponseCode(200)
                        .setBody(JSONObject().put("login", activeLogin).toString())
                    path.startsWith("/repos/alice/alpha/branches") ->
                        MockResponse().setResponseCode(200)
                            .setBody(
                                JSONArray()
                                    .put(JSONObject().put("name", "main"))
                                    .put(JSONObject().put("name", "dev"))
                                    .toString()
                            )
                    else -> MockResponse().setResponseCode(404)
                        .setBody("""{"message":"nope"}""")
                }
            }
        }
        server.start()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        server.shutdown()
        SessionState.resetForTest()
    }

    private fun repoJson(fullName: String): JSONObject {
        val parts = fullName.split("/")
        return JSONObject()
            .put("full_name", fullName)
            .put("name", parts[1])
            .put("owner", JSONObject().put("login", parts[0]))
            .put("private", false)
            .put("default_branch", "main")
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

    /** Connects as the given login (mock /user answers with it). */
    private fun connectAs(login: String, asAccountSwitch: Boolean = false) {
        activeLogin = login
        activity.connectWithBaseUrlForTest(
            "token-$login",
            server.url("/").toString(),
            asAccountSwitch = asAccountSwitch
        )
        assertTrue(waitFor { activity.repoItemTextsForTest().isNotEmpty() })
    }

    /** Fills the card the way a user would before switching accounts. */
    private fun fillCardForAlice() {
        connectAs("alice")
        // Pick a repo + branch via the real dropdown path.
        val dropdown = activity.findViewById<AutoCompleteTextView>(R.id.repoDropdown)
        val texts = activity.repoItemTextsForTest()
        dropdown.setText(texts[0], false) // "alpha"
        dropdown.onItemClickListener!!.onItemClick(null, null, 0, 0L)
        assertTrue(waitFor { activity.selectedBranch != null })
        // A target folder and a previous push's link.
        activity.findViewById<android.widget.EditText>(R.id.targetDirInput)
            .setText("assignments/week3")
        activity.lastCommitUrl = "https://github.com/alice/alpha/commit/abc"
        activity.findViewById<android.view.View>(R.id.viewRepoBtn).visibility =
            android.view.View.VISIBLE
        // Picked files: must SURVIVE the switch.
        activity.tracker.pickFiles(
            listOf(android.net.Uri.parse("content://media/external/file/1"))
        )
    }

    @Test
    fun `switching account resets the card but keeps picked files`() {
        fillCardForAlice()
        assertNotNull(activity.selectedRepo)
        assertNotNull(activity.selectedBranch)

        // --- switch to bob: connect with a different login ---
        connectAs("bob", asAccountSwitch = true)

        // Account-bound selections are gone.
        assertNull(activity.selectedRepo)
        assertNull(activity.selectedBranch)
        assertEquals("", activity.findViewById<AutoCompleteTextView>(R.id.repoDropdown).text.toString())
        assertEquals("", activity.findViewById<AutoCompleteTextView>(R.id.branchDropdown).text.toString())
        assertEquals("", activity.findViewById<android.widget.EditText>(R.id.targetDirInput).text.toString())
        // The previous push's link no longer applies to the new account.
        assertNull(activity.lastCommitUrl)
        assertEquals(
            android.view.View.GONE,
            activity.findViewById<android.view.View>(R.id.viewRepoBtn).visibility
        )
        // The picked files survive — they belong to the user.
        assertEquals(1, activity.tracker.files.size)
    }

    @Test
    fun `card reset happens for any non-auto connect`() {
        fillCardForAlice()
        // A manual reconnect (same account even) also refreshes the card:
        // the repo list that lands belongs to the freshly verified login.
        connectAs("alice", asAccountSwitch = true)
        assertNull(activity.selectedRepo)
        assertEquals("", activity.findViewById<android.widget.EditText>(R.id.targetDirInput).text.toString())
    }

    @Test
    fun `auto reconnect after rotation keeps the selection`() {
        fillCardForAlice()
        // The AUTO connect path (activity recreation) must NOT clear the
        // card — that is the rotation flow, and the selection must
        // survive it (v0.2.8 behaviour preserved).
        val savedRepo = activity.selectedRepo
        activity.connectWithBaseUrlForTest("token-alice", server.url("/").toString())
        assertTrue(waitFor { activity.repoItemTextsForTest().isNotEmpty() })
        idle()
        assertNotNull(activity.selectedRepo)
        assertEquals(savedRepo!!.fullName, activity.selectedRepo!!.fullName)
        assertEquals(
            "assignments/week3",
            activity.findViewById<android.widget.EditText>(R.id.targetDirInput).text.toString()
        )
    }
}
