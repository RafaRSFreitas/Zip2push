package com.zippush.app

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.4.0: the full-screen folder picker must keep every essential
 * control visible and inside the screen in BOTH orientations. The
 * layout is inflated and MEASURED with the real screen dimensions of
 * each qualifier set (portrait 411x891dp, landscape 891x411dp): the
 * list is the only part allowed to shrink (0dp + weight); the top bar,
 * navigation row and the fixed footer must never be cut off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderBrowserLayoutTest {

    /** Inflates + measures the picker layout and asserts the geometry. */
    private fun measureAndAssert() {
        // The dialog theme is not needed for measurement; what matters is
        // the APP theme (Theme.ZipPush), which supplies the tokens the
        // layout references (?attr/textAppearance*, colours, styles).
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            R.style.Theme_ZipPush
        )
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_folder_browser, null)
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)

        val closeBtn = view.findViewById<ImageButton>(R.id.browserCloseBtn)
        val upBtn = view.findViewById<ImageButton>(R.id.browserUpBtn)
        val list = view.findViewById<RecyclerView>(R.id.browserList)
        val content = view.findViewById<FrameLayout>(R.id.browserContent)
        val chooseBtn = view.findViewById<MaterialButton>(R.id.browserChooseBtn)

        assertTrue("close button has no height", closeBtn.height > 0)
        assertTrue("up button has no height", upBtn.height > 0)
        assertTrue("list has no height", list.height > 0)
        assertTrue("choose button has no height", chooseBtn.height > 0)
        assertTrue(
            "list area overlaps the footer (${content.bottom} > ${chooseBtn.top})",
            content.bottom <= chooseBtn.top
        )
        assertTrue(
            "footer pushed off screen (${chooseBtn.bottom} > $h)",
            chooseBtn.bottom <= h
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-normal-long-notround")
    fun `picker keeps every control visible in portrait`() {
        measureAndAssert()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w891dp-h411dp-normal-long-notround")
    fun `picker keeps every control visible in landscape`() {
        measureAndAssert()
    }
}
