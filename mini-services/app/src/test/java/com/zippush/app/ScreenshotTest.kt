package com.zippush.app

import org.junit.Rule
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the real activity to PNG so the JornalWeb restyle can be
 * visually checked without a device. Day and night are separate classes
 * because the uiMode qualifier must be set before the activity inflates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-normal-long-notround")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotDayTest {

    @Test
    fun renderDay() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        Thread.sleep(400) // let coroutines settle
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File("/tmp/render").mkdirs()
        FileOutputStream("/tmp/render/day.png").use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("day size: ${view.width}x${view.height}")

        // Day: background must be the JornalWeb light background (#FFFFFF),
        // and the theme toggle must exist left of the overflow menu.
        assertEquals(Color.WHITE, bmp.getPixel(5, 5))
        val toggle = activity.findViewById<android.widget.ImageButton>(R.id.themeToggleBtn)
        assertTrue(toggle.width > 0)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-normal-long-notround-night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotNightTest {

    @Test
    fun renderNight() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        Thread.sleep(400)
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File("/tmp/render").mkdirs()
        FileOutputStream("/tmp/render/night.png").use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("night size: ${view.width}x${view.height}")

        // Night: background must be JornalWeb zinc-950 (#0A0A0A), and the
        // input fields must be VISIBLE against the card (#171717): their
        // fill (#212121) differs from the card by a measurable amount, and
        // the stroke row (#383838) differs from both.
        val bg = bmp.getPixel(5, 5)
        assertEquals(0xFF0A0A0A.toInt(), bg)

        // Scan the full field width: the JornalWeb-dark fill (#212121) must
        // cover most of the field (proving visible fields, not faint
        // outlines), and a clearly lighter border/stroke must exist at the
        // field edges (fill luminance +30 or more).
        val inputRow = activity.findViewById<android.widget.AutoCompleteTextView>(R.id.repoDropdown)
        val loc = IntArray(2)
        inputRow.getLocationOnScreen(loc)
        val y = loc[1] + inputRow.height / 2
        var fillPixels = 0
        var sawStroke = false
        for (x in 1 until bmp.width - 1) {
            val p = bmp.getPixel(x, y)
            if (p == 0xFF212121.toInt()) fillPixels++
            val r = (p shr 16) and 0xFF
            if (r in 0x32..0x9E) sawStroke = true // 50..158: lighter than fill, not text
        }
        assertTrue("input fill not visible on row $y", fillPixels > 100)
        assertTrue("input stroke not visible on row $y", sawStroke)
    }
}
