package com.zippush.app

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the persisted day/night toggle in the top-right corner
 * (left of the 3-dot menu): tapping flips and persists the night mode.
 * The pref + global night mode are checked without rebuilding a second
 * activity (uiMode changes + Robolectric choreographer don't mix well).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-normal-long-notround")
class ThemeToggleTest {

    private fun prefValue(activity: MainActivity) =
        activity.getPreferences(android.content.Context.MODE_PRIVATE)
            .getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    @Test
    fun `toggle flips night mode and persists`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()

        // Default: follow system (Robolectric default is day).
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, prefValue(activity))

        // Tap the toggle -> night mode, persisted + applied globally.
        activity.findViewById<android.widget.ImageButton>(R.id.themeToggleBtn).performClick()
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, prefValue(activity))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

        // Tap again -> back to day.
        activity.findViewById<android.widget.ImageButton>(R.id.themeToggleBtn).performClick()
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, prefValue(activity))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
    }
}
