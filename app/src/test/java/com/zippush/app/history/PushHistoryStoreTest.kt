package com.zippush.app.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.3.5: the push-history store — append/read round trip (newest
 * first), the 50-entry cap, and resilience to a corrupt file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushHistoryStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PushHistoryStore.resetForTest(context)
    }

    @After
    fun tearDown() {
        PushHistoryStore.resetForTest(context)
    }

    private fun entry(ts: Long, title: String = "push $ts") = PushHistoryStore.Entry(
        timestampMs = ts,
        repoFullName = "alice/repo",
        branch = "main",
        targetDir = "docs",
        messageTitle = title,
        fileCount = 1,
        files = listOf("docs/a.txt"),
        commitUrl = "https://github.com/alice/repo/commit/$ts"
    )

    @Test
    fun `read on a fresh install is empty`() {
        assertEquals(0, PushHistoryStore.read(context).size)
    }

    @Test
    fun `append then read round-trips newest first`() {
        PushHistoryStore.append(context, entry(1_000))
        PushHistoryStore.append(context, entry(2_000))
        PushHistoryStore.append(context, entry(3_000))
        val all = PushHistoryStore.read(context)
        assertEquals(3, all.size)
        assertEquals(3_000L, all[0].timestampMs)
        assertEquals(2_000L, all[1].timestampMs)
        assertEquals(1_000L, all[2].timestampMs)
        assertEquals("alice/repo", all[0].repoFullName)
        assertEquals("main", all[0].branch)
        assertEquals("docs", all[0].targetDir)
        assertEquals(listOf("docs/a.txt"), all[0].files)
        assertTrue(all[0].commitUrl!!.endsWith("/3000"))
    }

    @Test
    fun `entry without commit url round-trips as null`() {
        PushHistoryStore.append(
            context, entry(5).copy(commitUrl = null)
        )
        assertNull(PushHistoryStore.read(context).first().commitUrl)
    }

    @Test
    fun `history is capped at fifty entries`() {
        repeat(60) { i -> PushHistoryStore.append(context, entry(i.toLong())) }
        val all = PushHistoryStore.read(context)
        assertEquals(50, all.size)
        // The newest 50 survive; the oldest 10 are dropped.
        assertEquals(59L, all.first().timestampMs)
        assertEquals(10L, all.last().timestampMs)
    }

    @Test
    fun `corrupt history file reads as empty instead of crashing`() {
        PushHistoryStore.append(context, entry(1))
        // Corrupt the on-disk file behind the store's back.
        val f = java.io.File(context.filesDir, "push_history.json")
        f.writeText("not json at all {{{")
        PushHistoryStore.resetForTest(context) // drops the cache only
        // Re-read must not throw and must not return garbage entries.
        assertEquals(0, PushHistoryStore.read(context).size)
    }
}
