package com.zippush.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zippush.app.history.HistoryEntry
import com.zippush.app.history.PushHistoryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.3.3: the persistence behind the History screen — record, read-back
 * (JSON round trip), ordering, cap, clear and corruption resilience.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushHistoryStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PushHistoryStore.resetForTest(context)
    }

    @After
    fun tearDown() {
        PushHistoryStore.resetForTest(context)
    }

    private fun entry(
        ts: Long,
        repo: String = "alice/repo",
        success: Boolean = true,
        files: List<String> = listOf("docs/report.txt"),
        sha: String? = "abcdef1234567890",
        url: String? = "https://github.com/alice/repo/commit/abcdef1234567890",
        fail: String? = null
    ) = HistoryEntry(
        timestamp = ts,
        repoFullName = repo,
        branch = "main",
        targetDir = "assignments",
        messageTitle = "Upload week 3",
        authorLogin = "alice",
        success = success,
        commitSha = sha,
        commitUrl = url,
        files = files,
        bytesPushed = 1234L,
        failReason = fail
    )

    @Test
    fun `record then read returns the entry with every field intact`() {
        PushHistoryStore.record(context, entry(1_000L, files = listOf("a.txt", "b/c.txt")))

        val all = PushHistoryStore.read(context)
        assertEquals(1, all.size)
        val e = all[0]
        assertEquals(1_000L, e.timestamp)
        assertEquals("alice/repo", e.repoFullName)
        assertEquals("main", e.branch)
        assertEquals("assignments", e.targetDir)
        assertEquals("Upload week 3", e.messageTitle)
        assertEquals("alice", e.authorLogin)
        assertTrue(e.success)
        assertEquals("abcdef1234567890", e.commitSha)
        assertEquals("https://github.com/alice/repo/commit/abcdef1234567890", e.commitUrl)
        assertEquals(listOf("a.txt", "b/c.txt"), e.files)
        assertEquals(1234L, e.bytesPushed)
        assertNull(e.failReason)
    }

    @Test
    fun `newest entry comes first`() {
        PushHistoryStore.record(context, entry(ts = 1L, repo = "alice/old"))
        PushHistoryStore.record(context, entry(ts = 2L, repo = "alice/new"))

        val all = PushHistoryStore.read(context)
        assertEquals(2, all.size)
        assertEquals("alice/new", all[0].repoFullName)
        assertEquals("alice/old", all[1].repoFullName)
    }

    @Test
    fun `a failure entry keeps its reason and has no SHA`() {
        PushHistoryStore.record(
            context, entry(ts = 5L, success = false, sha = null, url = null,
                fail = "Blob: HTTP 422 too big")
        )

        val e = PushHistoryStore.read(context)[0]
        assertEquals(false, e.success)
        assertNull(e.commitSha)
        assertNull(e.commitUrl)
        assertEquals("Blob: HTTP 422 too big", e.failReason)
    }

    @Test
    fun `history is capped at MAX_ENTRIES`() {
        repeat(PushHistoryStore.MAX_ENTRIES + 20) { i ->
            PushHistoryStore.record(context, entry(ts = i.toLong()))
        }

        val all = PushHistoryStore.read(context)
        assertEquals(PushHistoryStore.MAX_ENTRIES, all.size)
        // The NEWEST survive: the cap drops the oldest.
        assertEquals(
            (PushHistoryStore.MAX_ENTRIES + 19).toLong(),
            all.first().timestamp
        )
        assertEquals(
            20.toLong(),
            all.last().timestamp
        )
    }

    @Test
    fun `shortSha is the git log style 7 chars`() {
        assertEquals("abcdef1", entry(0L).shortSha())
        assertNull(entry(0L, sha = null).shortSha())
    }

    @Test
    fun `clear wipes the history`() {
        PushHistoryStore.record(context, entry(1L))
        PushHistoryStore.record(context, entry(2L))

        PushHistoryStore.clear(context)

        assertTrue(PushHistoryStore.read(context).isEmpty())
    }

    @Test
    fun `a corrupted file degrades to an empty history instead of crashing`() {
        PushHistoryStore.record(context, entry(1L))
        val f = java.io.File(context.filesDir, "push_history.json")
        f.writeText("not json at all {{{")

        assertEquals(emptyList<HistoryEntry>(), PushHistoryStore.read(context))
    }

    @Test
    fun `unicode and special characters survive the round trip`() {
        PushHistoryStore.record(
            context, entry(
                ts = 3L,
                repo = "ünïcode/répo",
                files = listOf("pasta/café.txt", "emoji/🚀 launch.txt")
            )
        )

        val e = PushHistoryStore.read(context)[0]
        assertEquals("ünïcode/répo", e.repoFullName)
        assertEquals("emoji/🚀 launch.txt", e.files[1])
    }

    @Test
    fun `entries with missing optional fields still parse`() {
        // A failure entry serialised without sha/url/fail keys.
        val json = org.json.JSONObject(
            """{"timestamp":9,"repo":"a/b","branch":"main","dir":"","title":"t",
               "author":"","success":false,"files":[],"bytes":0}"""
        )
        val e = HistoryEntry.fromJson(json)
        assertEquals(false, e.success)
        assertNull(e.commitSha)
        assertNull(e.commitUrl)
        assertNull(e.failReason)
        assertEquals("", e.targetDir)
    }
}
