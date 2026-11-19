package com.zippush.app

import android.widget.AutoCompleteTextView
import androidx.test.core.app.ActivityScenario
import com.zippush.app.net.GitHubClient.Repo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Comprehensive battery for the "dropdown re-opens after rotation" fix
 * and the related dropdown-name change.
 *
 * Root cause of the bug: the selected repo lived in the activity
 * instance and died on rotation; the recreated activity auto-connected,
 * reloaded the repo list and unconditionally called showDropDown() —
 * popping the picker open again over a selection the user had already
 * made. The fix: selection lives in SessionState (process lifetime) and
 * loadRepos() only auto-opens the dropdown while nothing has been
 * picked in the session.
 *
 * The repo list is faked via the test seam (no network in unit tests);
 * the dropdown click is simulated exactly as Android delivers it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepoDropdownSessionTest {

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        SessionState.resetForTest()
    }

    @After
    fun tearDown() {
        scenario?.close()
        SessionState.resetForTest()
    }

    private fun idle() {
        ShadowLooper.shadowMainLooper().runToEndOfTasks()
    }

    /**
     * Pumps the main looper until [condition] holds. Needed because the
     * seams still hop through Dispatchers.IO (a real thread pool), so a
     * single idle() can lose the race with the continuation.
     */
    private fun waitFor(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun repo(fullName: String, defaultBranch: String = "main") = Repo(
        fullName = fullName,
        name = fullName.substringAfter('/'),
        owner = fullName.substringBefore('/'),
        private = false,
        defaultBranch = defaultBranch
    )

    /** Launches, fakes a connected client and loads [list] into the dropdown. */
    private fun launchWithRepos(list: List<Repo>): MainActivity {
        val sc = ActivityScenario.launch(MainActivity::class.java)
        scenario = sc
        var out: MainActivity? = null
        sc.onActivity { activity ->
            activity.fakeBranchListForTest = listOf(
                com.zippush.app.net.GitHubClient.Branch("main"),
                com.zippush.app.net.GitHubClient.Branch("dev")
            )
            activity.loadReposForTest(list)
            out = activity
        }
        // Wait until the list actually landed in the dropdown model.
        assertTrue("repo list never loaded", waitFor { out!!.repoItemTextsForTest().isNotEmpty() })
        return out!!
    }

    /** Simulates the user picking position [pos] in the repo dropdown. */
    private fun pickRepo(activity: MainActivity, pos: Int) {
        val dropdown = activity.findViewById<AutoCompleteTextView>(R.id.repoDropdown)
        // Faithful to a real click: MaterialAutoCompleteTextView first
        // writes the item text into the field, then invokes the listener.
        val texts = activity.repoItemTextsForTest()
        dropdown.setText(texts[pos], false)
        dropdown.onItemClickListener!!.onItemClick(null, null, pos, pos.toLong())
        // onRepoSelected loads branches async; wait for the branch pick.
        assertTrue(
            "branch list never loaded after pick",
            waitFor { activity.selectedBranch != null }
        )
    }

    // ------------------------------------------------------------------
    // SessionState unit behaviour
    // ------------------------------------------------------------------

    @Test
    fun `session state starts clean and records a pick`() {
        assertNull(SessionState.selectedRepo)
        assertFalse(SessionState.repoPickedThisSession)
        SessionState.selectedRepo = repo("alice/alpha")
        assertTrue(SessionState.repoPickedThisSession)
        SessionState.clear()
        assertNull(SessionState.selectedRepo)
        assertFalse(SessionState.repoPickedThisSession)
    }

    @Test
    fun `dropdown item text is the repo name only`() {
        // The field must show "alpha", not "alice/alpha".
        assertEquals("alpha", MainActivity.RepoItemForTest.repoItemText(repo("alice/alpha")))
        assertEquals("site-web", MainActivity.RepoItemForTest.repoItemText(repo("bob/site-web")))
    }

    // ------------------------------------------------------------------
    // Auto-open behaviour
    // ------------------------------------------------------------------

    @Test
    fun `fresh session auto-opens the dropdown once the list loads`() {
        val activity = launchWithRepos(listOf(repo("alice/alpha"), repo("alice/beta")))
        assertTrue(
            "dropdown should auto-open on a fresh session",
            activity.repoDropdownWasShown
        )
        // The rows show names only.
        val field = activity.findViewById<AutoCompleteTextView>(R.id.repoDropdown)
        assertEquals("", field.text.toString())
    }

    @Test
    fun `picking a repo then rotating does not reopen the dropdown`() {
        val activity = launchWithRepos(listOf(repo("alice/alpha"), repo("alice/beta")))
        pickRepo(activity, 0)
        assertTrue(activity.repoDropdownWasShown) // opened once (fresh)

        // Rotate.
        scenario!!.recreate()
        idle()

        scenario!!.onActivity { fresh ->
            // Selection restored: repo + its branch list + field text.
            assertNotNull(fresh.selectedRepo)
            assertEquals("alice/alpha", fresh.selectedRepo!!.fullName)
            val field = fresh.findViewById<AutoCompleteTextView>(R.id.repoDropdown)
            assertEquals("alpha", field.text.toString())
            // Branch pre-selected from the restored list.
            assertNotNull(fresh.selectedBranch)
            assertEquals("main", fresh.selectedBranch!!.name)
        }
    }

    @Test
    fun `rotation after pick suppresses auto-open even across further reloads`() {
        val activity = launchWithRepos(listOf(repo("alice/alpha")))
        pickRepo(activity, 0)

        repeat(3) {
            scenario!!.recreate()
            idle()
        }

        scenario!!.onActivity { fresh ->
            assertEquals("alice/alpha", fresh.selectedRepo!!.fullName)
            // Manual reload from the fresh instance must not pop it open.
            fresh.resetRepoDropdownWasShownForTest()
            fresh.onReloadClickedForTest()
        }
        idle()
        scenario!!.onActivity { fresh ->
            assertFalse(
                "reload after a pick must not auto-open the dropdown",
                fresh.repoDropdownWasShown
            )
            assertEquals("alice/alpha", fresh.selectedRepo!!.fullName)
        }
    }

    @Test
    fun `manual reload never auto-opens the dropdown`() {
        // Production behaviour (kept from v0.2.3): the manual reload
        // button refreshes the list quietly; only a fresh connection
        // auto-opens the picker.
        val activity = launchWithRepos(listOf(repo("alice/alpha")))
        activity.resetRepoDropdownWasShownForTest()
        activity.onReloadClickedForTest()
        // Give the reload every chance to (wrongly) open it.
        assertTrue(waitFor { !activity.isBusyFlagForTest() })
        assertFalse(
            "manual reload must not pop the dropdown open",
            activity.repoDropdownWasShown
        )
    }

    @Test
    fun `logout clears the session so the dropdown may open again`() {
        val activity = launchWithRepos(listOf(repo("alice/alpha")))
        pickRepo(activity, 0)

        activity.disconnectForTest()
        idle()

        assertNull(activity.selectedRepo)
        assertFalse(SessionState.repoPickedThisSession)
        // After logout the next load may auto-open again (new session).
        activity.resetRepoDropdownWasShownForTest()
        activity.loadReposForTest(listOf(repo("alice/alpha")))
        assertTrue(
            "auto-open must return after logout",
            waitFor { activity.repoDropdownWasShown }
        )
    }
}
