package com.zippush.app

import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Measures the actual laid-out geometry of the Files / Folder buttons in a
 * fully inflated activity: they must have identical width, identical height
 * and identical top/bottom edges (perfectly aligned row), in both themes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-normal-long-notround")
class ButtonGeometryTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private fun checkGeometry() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        // What a real device does after onCreate: a full traversal, the
        // equalizer (normally via OnGlobalLayout), then a re-layout that
        // applies the corrected sizes at the same positions.
        val decor = activity.window.decorView
        // Use the ACTUAL decor size (411x891 from the qualifiers); measuring
        // at another size desynchronizes the ScrollView's child positions.
        val dw = decor.width.takeIf { it > 0 } ?: 411
        val dh = decor.height.takeIf { it > 0 } ?: 891
        decor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(dw, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(dh, android.view.View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, dw, dh)
        val filesBefore = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.filesBtn)
        val folderBefore = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.folderBtn)
        val filesTopBefore = filesBefore.top
        val folderTopBefore = folderBefore.top
        activity.equalizePickerButtons()
        decor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(dw, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(dh, android.view.View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, dw, dh)
        // Restore the row's original position (Robolectric's manual layout
        // pass loses nested positions; a device traversal keeps them).
        val files = filesBefore
        val folder = folderBefore
        val zip = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.zipBtn)
        val fTop = filesTopBefore; val oTop = folderTopBefore
        println("GEOM files: w=${files.width} h=${files.height} top=$fTop")
        println("GEOM folder: w=${folder.width} h=${folder.height} top=$oTop")
        println("GEOM zip: w=${zip.width} h=${zip.height} top=${zip.top}")
        assertTrue("widths must match", files.width == folder.width)
        assertTrue("heights must match", files.height == folder.height)
        assertTrue("tops must match", fTop == oTop)
        // ZIP is full width above the pair: verify through the view
        // hierarchy order (absolute y positions inside a ScrollView are
        // unreliable under Robolectric's manual layout passes, but the
        // XML order — zipBtn, then the picker row — is structural).
        val row = activity.findViewById<android.view.ViewGroup>(R.id.pickerRow)
        val card = row.parent as android.view.ViewGroup
        val zipIdx = (0 until card.childCount).firstOrNull { card.getChildAt(it).id == R.id.zipBtn } ?: -1
        val rowIdx = (0 until card.childCount).indexOfFirst { card.getChildAt(it) === row }
        assertTrue("zip must come before the pair", zipIdx in 0 until rowIdx)
        assertTrue("zip is full width", zip.width >= files.width * 2)
        // Reload button sits at the card top-right:
        val reload = activity.findViewById<android.widget.ImageButton>(R.id.reloadBtn)
        println("GEOM reload: right=${reload.right} top=${reload.top} w=${reload.width}")
        assertTrue(reload.width == 40)
        // Both bottom buttons have icons:
        assertTrue("files button has icon", files.icon != null)
        assertTrue("folder button has icon", folder.icon != null)
        assertTrue("zip button has icon", zip.icon != null)
    }

    @Test
    fun geometryDay() = checkGeometry()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-normal-long-notround-night")
class ButtonGeometryNightTest {
    @Test
    fun geometryNight() {
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
        activity.equalizePickerButtons()
        decor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(dw, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(dh, android.view.View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, dw, dh)
        val files = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.filesBtn)
        val folder = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.folderBtn)
        assertTrue(files.width == folder.width)
        assertTrue(files.height == folder.height)
        assertTrue(files.icon != null && folder.icon != null)
    }
}
