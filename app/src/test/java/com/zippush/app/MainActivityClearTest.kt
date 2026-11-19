package com.zippush.app

import org.junit.Rule
import android.widget.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI-level test of the Clear Selection fix: launching the real activity,
 * simulating a picked ZIP + repo + branch + commit message, clicking the
 * button and asserting the whole form resets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityClearTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var activity: MainActivity

    @Before
    fun setup() {
        // SessionState is a process singleton; isolate tests from each other.
        SessionState.resetForTest()
        // No stored token -> activity starts in the idle state, no network.
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        activity = controller.get()
    }

    @Test
    fun `clear resets zip, files, folder, repo, branch, texts and hides view button`() {
        // Simulate the user having picked things.
        activity.tracker.pickZip(android.net.Uri.parse("content://media/zip/1"))
        activity.tracker.pickFiles(
            listOf(android.net.Uri.parse("content://media/file/1"))
        )
        activity.tracker.pickFilesWithPaths(
            listOf(
                SelectionTracker.FolderFile(
                    android.net.Uri.parse("content://tree/1/doc1"),
                    "photos/cat.jpg"
                )
            )
        )
        activity.selectedRepo = com.zippush.app.net.GitHubClient.Repo(
            fullName = "octocat/hello", name = "hello", owner = "octocat",
            private = false, defaultBranch = "main"
        )
        activity.selectedBranch = com.zippush.app.net.GitHubClient.Branch("develop")
        activity.lastCommitUrl = "https://github.com/octocat/hello/commit/abc123"

        val zipBtn = activity.findViewById<Button>(R.id.zipBtn)
        val filesBtn = activity.findViewById<Button>(R.id.filesBtn)
        val folderBtn = activity.findViewById<Button>(R.id.folderBtn)
        val repoDropdown = activity.findViewById<android.widget.AutoCompleteTextView>(R.id.repoDropdown)
        val branchDropdown = activity.findViewById<android.widget.AutoCompleteTextView>(R.id.branchDropdown)
        val targetDir = activity.findViewById<android.widget.EditText>(R.id.targetDirInput)
        val commitMsg = activity.findViewById<android.widget.EditText>(R.id.commitMessageInput)
        val viewRepoBtn = activity.findViewById<Button>(R.id.viewRepoBtn)
        val selectionText = activity.findViewById<android.widget.TextView>(R.id.selectionText)

        repoDropdown.setText("octocat/hello", false)
        branchDropdown.setText("develop", false)
        targetDir.setText("assignments/week3")
        commitMsg.setText("week3 homework")
        viewRepoBtn.visibility = android.view.View.VISIBLE
        activity.refreshSelectionSummary()

        // Sanity: everything is set before the click.
        assertNotNull(activity.selectedRepo)
        assertEquals("week3 homework", commitMsg.text.toString())
        assertEquals(android.view.View.VISIBLE, selectionText.visibility)

        // --- the actual user action under test ---
        activity.findViewById<Button>(R.id.clearBtn).performClick()

        assertNull(activity.tracker.zip)
        assertEquals(0, activity.tracker.files.size)
        assertEquals(0, activity.tracker.filesWithPaths.size)
        assertNull(activity.selectedRepo)
        assertNull(activity.selectedBranch)
        assertNull(activity.lastCommitUrl)
        assertEquals("", repoDropdown.text.toString())
        assertEquals("", branchDropdown.text.toString())
        assertEquals("", targetDir.text.toString())
        assertEquals("", commitMsg.text.toString())
        assertEquals(android.view.View.GONE, viewRepoBtn.visibility)
        assertEquals(android.view.View.GONE, selectionText.visibility)
        assertEquals(activity.getString(R.string.add_zip), zipBtn.text.toString())
        assertEquals(activity.getString(R.string.add_files), filesBtn.text.toString())
        assertEquals(activity.getString(R.string.add_folder), folderBtn.text.toString())
        // Status line confirms the action to the user.
        val statusText = activity.findViewById<android.widget.TextView>(R.id.statusText)
        assertEquals(
            activity.getString(R.string.selection_cleared),
            statusText.text.toString()
        )
    }

    @Test
    fun `clear with empty form reports nothing to clear`() {
        activity.findViewById<Button>(R.id.clearBtn).performClick()
        val statusText = activity.findViewById<android.widget.TextView>(R.id.statusText)
        assertEquals(
            activity.getString(R.string.nothing_to_clear),
            statusText.text.toString()
        )
    }
}
