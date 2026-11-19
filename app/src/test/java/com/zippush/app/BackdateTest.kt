package com.zippush.app

import android.widget.Button
import android.widget.DatePicker
import android.widget.TimePicker
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the retroactive (backdated) commit feature:
 *
 *  1. The backdate button starts as "Now (default)" and opens the picker.
 *  2. The picker dialog contains date + time pickers AND the warning text.
 *  3. Applying a date updates the button label to "Backdated: …".
 *  4. backdateIso() produces the ISO-8601 form GitHub expects.
 *  5. "Use current time" resets to null (commit uses the real time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackdateTest {

    private fun launch(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        return controller.get()
    }

    @Test
    fun `backdate button exists defaults to now and is wired`() {
        val activity = launch()
        val btn = activity.findViewById<MaterialButton>(R.id.backdateBtn)
        assertEquals(activity.getString(R.string.backdate_now), btn.text.toString())
        assertNull(activity.backdateIso())
        assertTrue(btn.performClick()) // opens the dialog without crashing
    }

    @Test
    fun `dialog has date time pickers and the warning`() {
        val activity = launch()
        activity.showBackdateDialogForTest()
        val dialog = activity.lastBackdateDialogForTest
        assertNotNull(dialog)
        val view = dialog!!.findViewById<DatePicker>(R.id.backdateDatePicker)
        val time = dialog.findViewById<TimePicker>(R.id.backdateTimePicker)
        val warning = dialog.findViewById<android.widget.TextView>(R.id.backdateWarningText)
        assertNotNull(view)
        assertNotNull(time)
        assertNotNull(warning)
        // The warning must spell out the consequences:
        val text = warning.text.toString().lowercase()
        assertTrue(text.contains("out of order"))
        assertTrue(text.contains("contribution"))
        assertTrue(text.contains("committer"))
    }

    @Test
    fun `iso format matches github expectation`() {
        val activity = launch()
        // Simulate a chosen date: 2024-03-01 14:30 in the device zone.
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(2024, java.util.Calendar.MARCH, 1, 14, 30, 0)
        activity.commitAuthorDateForTest = cal
        val iso = activity.backdateIso()
        assertNotNull(iso)
        assertTrue("got $iso", Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(iso!!))
        // The same instant expressed in UTC, computed independently:
        val expected = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(cal.toInstant())
        assertEquals(expected, iso)
    }

    @Test
    fun `apply updates label and iso, use-now resets`() {
        val activity = launch()
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(2023, java.util.Calendar.DECEMBER, 25, 9, 15, 0)
        activity.commitAuthorDateForTest = cal
        activity.refreshBackdateButtonForTest()
        val btn = activity.findViewById<MaterialButton>(R.id.backdateBtn)
        assertTrue(btn.text.toString().startsWith(activity.getString(R.string.backdate_set, "").dropLast(1)))
        assertNotNull(activity.backdateIso())

        activity.resetBackdateForTest()
        assertEquals(activity.getString(R.string.backdate_now), btn.text.toString())
        assertNull(activity.backdateIso())
    }
}
