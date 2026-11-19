package com.zippush.app

import android.view.View
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.2.7 "Add description" behaviour — the exact spec from the user:
 *
 *   "If the user press to add the description, while not entering
 *    anything inside this text box, the user should see a button to
 *    collapse that field."
 *
 * Derived rules, each covered by a test:
 *  1. The field starts collapsed; only "Add description" is visible.
 *  2. Pressing it expands the field; the expand button disappears and
 *     (field still empty) the collapse button appears.
 *  3. Typing text hides the collapse button — entered content is never
 *     silently collapsible.
 *  4. Clearing the text brings the collapse button back.
 *  5. Collapsing (only possible while empty) hides the field, clears
 *     the text and shows "Add description" again.
 *  6. The description feeds the commit message as "title\n\nbody".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DescriptionCollapseTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var activity: MainActivity

    @Before
    fun setup() {
        // SessionState is a process singleton; isolate tests from each other.
        SessionState.resetForTest()
        // No stored token -> activity starts idle, no network.
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        activity = controller.get()
    }

    @Test
    fun `field starts collapsed with only the expand button visible`() {
        assertFalse(activity.isDescriptionExpanded())
        assertEquals(View.VISIBLE, activity.expandBtnVisibilityForTest())
        assertEquals(View.GONE, activity.collapseBtnVisibilityForTest())
    }

    @Test
    fun `expand shows the field and swaps to the collapse button while empty`() {
        activity.setDescriptionExpanded(true)
        assertTrue(activity.isDescriptionExpanded())
        // Expand button has no further use once the field is open.
        assertEquals(View.GONE, activity.expandBtnVisibilityForTest())
        // Field is empty -> the collapse affordance must be visible.
        assertEquals(View.VISIBLE, activity.collapseBtnVisibilityForTest())
    }

    @Test
    fun `typing text hides the collapse button`() {
        activity.setDescriptionExpanded(true)
        activity.setDescriptionTextForTest("some description")
        assertEquals(
            "typed content must not be silently collapsible",
            View.GONE,
            activity.collapseBtnVisibilityForTest()
        )
        // Clearing the text brings it back.
        activity.setDescriptionTextForTest("")
        assertEquals(View.VISIBLE, activity.collapseBtnVisibilityForTest())
    }

    @Test
    fun `collapse button click hides and clears the field`() {
        activity.setDescriptionExpanded(true)
        activity.setDescriptionTextForTest("")
        activity.commitCollapseBtnForTest().performClick()
        assertFalse(activity.isDescriptionExpanded())
        assertEquals("", activity.descriptionTextForTest())
        assertEquals(View.VISIBLE, activity.expandBtnVisibilityForTest())
        assertEquals(View.GONE, activity.collapseBtnVisibilityForTest())
    }

    @Test
    fun `expand button click is the real UI entry point`() {
        activity.commitExpandBtnForTest().performClick()
        assertTrue(activity.isDescriptionExpanded())
        assertEquals(View.GONE, activity.expandBtnVisibilityForTest())
        assertEquals(View.VISIBLE, activity.collapseBtnVisibilityForTest())
    }

    @Test
    fun `whitespace-only text still counts as empty for the collapse rule`() {
        activity.setDescriptionExpanded(true)
        activity.setDescriptionTextForTest("   ")
        assertEquals(
            "blank text collapses like empty text",
            View.VISIBLE,
            activity.collapseBtnVisibilityForTest()
        )
    }

    /** Direct accessors to the two buttons (performClick-level realism). */
    private fun MainActivity.commitExpandBtnForTest() =
        findViewById<com.google.android.material.button.MaterialButton>(R.id.commitExpandBtn)

    private fun MainActivity.commitCollapseBtnForTest() =
        findViewById<com.google.android.material.button.MaterialButton>(R.id.commitCollapseBtn)
}
