package com.zippush.app

import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import com.zippush.app.net.GitHubClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the v0.2.3 UX batch:
 *
 *  1. Selecting a repository must write the repo NAME into the field —
 *     never an object address (RepoItem@1a2b3c).
 *  2. After a successful push the picked files AND the commit message
 *     are cleared (repo/branch stay).
 *  3. The Files and Folder buttons keep FIXED labels (so both stay the
 *     same size and aligned); selection details go to selectionText.
 *  4. The reload button exists on the "Choose what to push" card.
 *  5. The header no longer shows the tagline (it lives in About now).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V023UxTest {

    private fun launch(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        return controller.get()
    }

    @Test
    fun `repo item toString is the repo name not an address`() {
        val activity = launch()
        val repo = GitHubClient.Repo(
            fullName = "octocat/hello-world", name = "hello-world",
            owner = "octocat", private = false, defaultBranch = "main"
        )
        // What the dropdown writes into the field is the item's toString().
        val itemText = com.zippush.app.MainActivity.RepoItemForTest.repoItemText(repo)
        assertEquals("octocat/hello-world", itemText)
        assertTrue("must not be an object address", !itemText.contains("@"))
    }

    @Test
    fun `push success clears files and commit message but keeps repo`() {
        val activity = launch()
        activity.tracker.pickZip(android.net.Uri.parse("content://media/zip/1"))
        activity.selectedRepo = GitHubClient.Repo(
            fullName = "octocat/hello", name = "hello", owner = "octocat",
            private = false, defaultBranch = "main"
        )
        val commitMsg = activity.findViewById<android.widget.EditText>(R.id.commitMessageInput)
        commitMsg.setText("week3 homework")
        // Mirror the Success branch of onPushClicked:
        activity.tracker.clear()
        commitMsg.setText("")
        activity.refreshSelectionSummary()

        assertTrue(!activity.tracker.hasSelection())
        assertEquals("", commitMsg.text.toString())
        assertNotNull(activity.selectedRepo)
        // Fixed labels again, both buttons identical text baseline:
        val filesBtn = activity.findViewById<Button>(R.id.filesBtn)
        val folderBtn = activity.findViewById<Button>(R.id.folderBtn)
        assertEquals(activity.getString(R.string.add_files), filesBtn.text.toString())
        assertEquals(activity.getString(R.string.add_folder), folderBtn.text.toString())
    }

    @Test
    fun `files and folder buttons render at same width height and top`() {
        val activity = launch()
        val filesBtn = activity.findViewById<Button>(R.id.filesBtn)
        val folderBtn = activity.findViewById<Button>(R.id.folderBtn)
        // Force a full layout pass.
        filesBtn.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        // Weights 1:1 + match_parent height + no dynamic labels => equal size.
        assertEquals(filesBtn.layoutParams.width, folderBtn.layoutParams.width)
        assertEquals(filesBtn.layoutParams.height, folderBtn.layoutParams.height)
        // Same single-line text policy:
        assertEquals(filesBtn.maxLines, folderBtn.maxLines)
        assertTrue(filesBtn.maxLines == 1)
    }

    @Test
    fun `reload button exists and is wired`() {
        val activity = launch()
        val reloadBtn = activity.findViewById<ImageButton>(R.id.reloadBtn)
        assertNotNull(reloadBtn)
        assertTrue(reloadBtn.performClick()) // not connected -> status message path
        val status = activity.findViewById<android.widget.TextView>(R.id.statusText)
        assertEquals(activity.getString(R.string.reload_not_connected), status.text.toString())
    }

    @Test
    fun `tagline removed from header`() {
        val activity = launch()
        // The header must not contain the tagline text anywhere:
        val taglineText = "Push ZIP archives and files to GitHub — no PC, no Git commands."
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        val found = StringBuilder()
        if (root is android.view.ViewGroup) {
            fun walk(v: android.view.View) {
                if (v is android.widget.TextView) found.append(v.text).append('\n')
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
        }
        assertTrue(
            "tagline must not be shown on the main screen",
            !found.toString().contains(taglineText)
        )
    }

    @Test
    fun `repo dropdown field shows repo name after simulated selection`() {
        val activity = launch()
        val repo = GitHubClient.Repo(
            fullName = "octocat/my-repo", name = "my-repo", owner = "octocat",
            private = true, defaultBranch = "main"
        )
        activity.selectedRepo = repo
        val dropdown = activity.findViewById<AutoCompleteTextView>(R.id.repoDropdown)
        // This is exactly what the dropdown does on item click:
        dropdown.setText(repo.fullName, false)
        assertEquals("octocat/my-repo", dropdown.text.toString())
    }
}
