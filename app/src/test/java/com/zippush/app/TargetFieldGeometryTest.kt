package com.zippush.app

import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.2.9 layout fix: the target-folder field must be the same height as
 * the repo / branch fields (the user reported it was visibly thinner).
 * The fields are fully inflated and laid out at a real device size; the
 * measured heights must match exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-normal-long-notround")
class TargetFieldGeometryTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    @Test
    fun `target folder field matches the other dropdown fields`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        val decor = activity.window.decorView
        val dw = decor.width.takeIf { it > 0 } ?: 411
        val dh = decor.height.takeIf { it > 0 } ?: 891
        decor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(dw, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(dh, android.view.View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, dw, dh)

        val repo = activity.findViewById<android.view.View>(R.id.repoDropdown)
        val branch = activity.findViewById<android.view.View>(R.id.branchDropdown)
        val target = activity.findViewById<android.view.View>(R.id.targetDirInput)

        assertEquals(
            "target field must be as tall as the repo field",
            repo.height, target.height
        )
        assertEquals(
            "target field must be as tall as the branch field",
            branch.height, target.height
        )
        // Sanity: the fields really laid out at a plausible height.
        assertTrue("fields must have measured to a real height", target.height >= 48)
    }

    /**
     * The chevron affordance is real: after picking files with folder
     * paths, the field offers their parent folders as suggestions
     * (offline, no network).
     */
    @Test
    fun `target folder offers parent folders of picked files as suggestions`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()

        // A picked folder contributes two files under assignments/week3.
        activity.tracker.pickFilesWithPaths(
            listOf(
                SelectionTracker.FolderFile(
                    android.net.Uri.parse("content://media/external/file/1"),
                    "assignments/week3/a.txt"
                ),
                SelectionTracker.FolderFile(
                    android.net.Uri.parse("content://media/external/file/2"),
                    "assignments/week3/sub/b.txt"
                )
            )
        )
        activity.refreshSelectionSummary()

        val hints = activity.targetDirSuggestionsForTest()
        assertTrue(
            "parent folder must be suggested: $hints",
            hints.contains("assignments/week3")
        )
    }

    /**
     * A folder pushed to becomes the top suggestion for the next push
     * (session-scoped memory).
     */
    @Test
    fun `pushed folder becomes top suggestion`() {
        SessionState.rememberTargetDir("projects/demo")
        SessionState.rememberTargetDir("assignments/week3")
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        activity.refreshSelectionSummary()

        val hints = activity.targetDirSuggestionsForTest()
        assertEquals("most recent first", "assignments/week3", hints.firstOrNull())
        assertTrue(hints.contains("projects/demo"))
    }
}
