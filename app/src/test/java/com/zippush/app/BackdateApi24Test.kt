package com.zippush.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression battery for the reported backdate failure.
 *
 * The original bug: backdateIso() used java.time (ZonedDateTime /
 * DateTimeFormatter), which only exists from Android 8 (API 26). The app
 * runs from API 24 and has no core-library desugaring, so on Android 7
 * the push crashed with NoSuchMethodError the moment a backdated commit
 * was attempted. Robolectric runs on a JVM where java.time always exists,
 * which is why the suite stayed green — these tests pin the fix from
 * three angles:
 *
 *  1. Run the ISO conversion under a simulated API 24 device.
 *  2. Assert the implementation contains no java.time references at all
 *     (source-level guard so it cannot silently come back).
 *  3. Timezone correctness: a wall-clock time in America/Sao_Paulo
 *     converts to the right UTC instant (GitHub date is UTC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class BackdateApi24Test {

    private fun launch(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        return controller.get()
    }

    @Test
    fun `iso conversion works on api 24`() {
        val activity = launch()
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(2024, java.util.Calendar.MARCH, 1, 14, 30, 0)
        activity.commitAuthorDateForTest = cal
        val iso = activity.backdateIso()
        assertTrue("got $iso", Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(iso!!))
    }

    @Test
    fun `no backdate means null iso on api 24`() {
        val activity = launch()
        assertNull(activity.backdateIso())
    }

    @Test
    fun `source contains no java_time usage in the iso path`() {
        // Guard: the production source must not reference java.time in
        // MainActivity's backdate conversion (desugaring is off, minSdk 24).
        val src = java.io.File(
            "src/main/java/com/zippush/app/MainActivity.kt"
        ).readText()
        val isoBlock = src.substringBefore("private fun showBackdateDialog")
        assertFalseBackdateBlock(isoBlock)
    }

    private fun assertFalseBackdateBlock(block: String) {
        assertTrue(
            "backdateIso must not use java.time (minSdk 24, no desugaring)",
            !block.contains("java.time")
        )
    }

    @Test
    fun `sao_paulo wall clock converts to correct utc instant`() {
        // 2024-06-15 14:30 in São Paulo (UTC-3) = 17:30 UTC.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"))
        try {
            val activity = launch()
            val cal = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("America/Sao_Paulo")
            )
            cal.clear()
            cal.set(2024, java.util.Calendar.JUNE, 15, 14, 30, 0)
            activity.commitAuthorDateForTest = cal
            assertEquals("2024-06-15T17:30:00Z", activity.backdateIso())
        } finally {
            java.util.TimeZone.setDefault(null)
        }
    }

    @Test
    fun `utc wall clock passes through unchanged`() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val activity = launch()
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(2024, java.util.Calendar.MARCH, 1, 23, 59, 0)
            activity.commitAuthorDateForTest = cal
            assertEquals("2024-03-01T23:59:00Z", activity.backdateIso())
        } finally {
            java.util.TimeZone.setDefault(null)
        }
    }
}
