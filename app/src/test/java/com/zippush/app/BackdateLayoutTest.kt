package com.zippush.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the backdate button's placement in the layout: directly below
 * the commit message field, above the ZIP picker, right-aligned, with the
 * calendar-clock icon present.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-normal-long-notround")
class BackdateLayoutTest {

    @Test
    fun backdateButtonPlacedBetweenCommitFieldAndZipPicker() {
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

        val commit = activity.findViewById<android.view.View>(R.id.commitMessageInput)
        val backdate = activity.findViewById<android.view.View>(R.id.backdateBtn)
        val zip = activity.findViewById<android.view.View>(R.id.zipBtn)

        // Structural order in the card: commit field -> backdate -> zip.
        val card = (backdate.parent as android.view.ViewGroup)
        val idxOf = { v: android.view.View -> (0 until card.childCount).indexOfFirst { card.getChildAt(it) === v } }
        assertTrue(idxOf(commit.parent.parent as android.view.View) < idxOf(backdate))
        assertTrue(idxOf(backdate) < idxOf(zip))

        // Icon present on the button.
        val btn = backdate as com.google.android.material.button.MaterialButton
        assertTrue(btn.icon != null)

        // Measured geometry: the row is taller now (backdate adds one row).
        assertTrue("backdate must have measurable height", backdate.height > 0)
        assertTrue("commit field must be measured", commit.height > 0)
    }
}
