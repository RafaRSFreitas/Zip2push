package com.zippush.app

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.zippush.app.history.HistoryEntry
import com.zippush.app.history.PushHistoryDialog
import com.zippush.app.history.PushHistoryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.3.3: the History dialog itself — menu entry, list contents,
 * relative time, byte formatting, expand / collapse, the "See changes"
 * link and Clear (with confirmation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushHistoryDialogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Flushes the main looper so adapter rebinds are applied. */
    private fun idle() {
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().runToEndOfTasks()
    }

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
        success: Boolean = true,
        files: List<String> = listOf("a.txt"),
        url: String? = "https://github.com/alice/repo/commit/abc",
        fail: String? = null
    ) = HistoryEntry(
        timestamp = ts,
        repoFullName = "alice/repo",
        branch = "main",
        targetDir = "assignments",
        messageTitle = "Upload week 3",
        authorLogin = "alice",
        success = success,
        commitSha = if (success) "abcdef1234567890" else null,
        commitUrl = url,
        files = files,
        bytesPushed = 1024L,
        failReason = fail
    )

    // ------------------------------------------------------- formatting

    @Test
    fun `relative time buckets match git log conventions`() {
        val now = 1_000_000_000L
        assertEquals("now", PushHistoryDialog.relativeTime(now - 30_000, now))
        assertEquals("5m ago", PushHistoryDialog.relativeTime(now - 5 * 60_000, now))
        assertEquals("3h ago", PushHistoryDialog.relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2d ago", PushHistoryDialog.relativeTime(now - 2 * 86_400_000, now))
        assertEquals("6mo ago", PushHistoryDialog.relativeTime(now - 180L * 86_400_000, now))
    }

    @Test
    fun `bytes are formatted in ascii units`() {
        assertEquals("512 B", PushHistoryDialog.formatBytes(512))
        assertEquals("1.0 kB", PushHistoryDialog.formatBytes(1024))
        assertEquals("1.5 kB", PushHistoryDialog.formatBytes(1536))
        assertEquals("2.0 MB", PushHistoryDialog.formatBytes(2 * 1024 * 1024))
    }

    // ------------------------------------------------------- the dialog

    @Test
    fun `empty history shows the empty state, not the list`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a -> a.showHistoryDialogForTest() }
            scenario.onActivity { a ->
                val empty = a.findViewById<TextView>(R.id.historyEmptyText)
                val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyList)
                // Both live inside the dialog's window: query the dialog view.
                val dialog = a.lastHistoryDialogViewForTest()
                org.junit.Assert.assertNotNull("dialog must exist", dialog)
                val emptyText = dialog!!.findViewById<TextView>(R.id.historyEmptyText)
                val recycler = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyList)
                assertEquals(
                    "No pushes yet.\nYour pushes will show up here.",
                    emptyText.text.toString()
                )
                assertEquals(android.view.View.VISIBLE, emptyText.visibility)
                assertEquals(android.view.View.GONE, recycler.visibility)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `rows show repo branch folder author files and short sha`() {
        PushHistoryStore.record(context, entry(System.currentTimeMillis() - 60_000))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a -> a.showHistoryDialogForTest() }
            scenario.onActivity { a ->
                val dialog = a.lastHistoryDialogViewForTest()!!
                val recycler = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyList)
                assertEquals(1, recycler.adapter!!.itemCount)
                val row = recycler.findViewHolderForAdapterPosition(0)!!.itemView
                assertEquals(
                    "alice/repo · main · assignments",
                    row.findViewById<TextView>(R.id.historyRepoText).text.toString()
                )
                assertEquals(
                    "Upload week 3",
                    row.findViewById<TextView>(R.id.historyTitleText).text.toString()
                )
                val meta = row.findViewById<TextView>(R.id.historyMetaText).text.toString()
                assertTrue("author in meta: $meta", meta.contains("by alice"))
                assertTrue("files in meta: $meta", meta.contains("1 file"))
                assertTrue("sha in meta: $meta", meta.contains("abcdef1"))
                assertTrue("time in meta: $meta",
                    row.findViewById<TextView>(R.id.historyTimeText).text.contains("ago"))
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `plural forms - one file vs many files`() {
        PushHistoryStore.record(
            context, entry(System.currentTimeMillis() - 60_000, files = (1..3).map { "f$it.txt" })
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a -> a.showHistoryDialogForTest() }
            scenario.onActivity { a ->
                val dialog = a.lastHistoryDialogViewForTest()!!
                val recycler = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyList)
                val row = recycler.findViewHolderForAdapterPosition(0)!!.itemView
                val meta = row.findViewById<TextView>(R.id.historyMetaText).text.toString()
                assertTrue("3 files in meta: $meta", meta.contains("3 files"))
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `tapping a row expands the files and the GitHub link`() {
        PushHistoryStore.record(context, entry(System.currentTimeMillis() - 60_000))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a -> a.showHistoryDialogForTest() }
            scenario.onActivity { a ->
                val dialog = a.lastHistoryDialogViewForTest()!!
                val recycler = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyList)
                val row = recycler.findViewHolderForAdapterPosition(0)!!.itemView
                val detail = row.findViewById<TextView>(R.id.historyDetailText)
                assertEquals("collapsed first", android.view.View.GONE, detail.visibility)

                row.performClick()
                // Rebind happens on the next frame: flush the looper.
                idle()
                assertEquals("expanded after tap", android.view.View.VISIBLE, detail.visibility)
                val text = detail.text.toString()
                assertTrue("file list shown: $text", text.contains("a.txt"))
                assertTrue(
                    "github link shown: $text",
                    text.contains(context.getString(R.string.history_see_changes))
                )

                row.performClick()
                idle()
                assertEquals("collapsed again", android.view.View.GONE, detail.visibility)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `a failed push row shows the failure reason on expand`() {
        PushHistoryStore.record(
            context, entry(
                System.currentTimeMillis() - 60_000,
                success = false, url = null, fail = "Tree: HTTP 403"
            )
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a -> a.showHistoryDialogForTest() }
            scenario.onActivity { a ->
                val dialog = a.lastHistoryDialogViewForTest()!!
                val recycler = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyList)
                val row = recycler.findViewHolderForAdapterPosition(0)!!.itemView
                val meta = row.findViewById<TextView>(R.id.historyMetaText).text.toString()
                assertTrue("Failed label in meta: $meta", meta.contains("Failed"))
                row.performClick()
                idle()
                val detail = row.findViewById<TextView>(R.id.historyDetailText)
                assertEquals("Tree: HTTP 403", detail.text.toString().trim())
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `many files are truncated with a show-all line`() {
        val files = (1..20).map { "file$it.txt" }
        PushHistoryStore.record(
            context, entry(System.currentTimeMillis() - 60_000, files = files)
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a -> a.showHistoryDialogForTest() }
            scenario.onActivity { a ->
                val dialog = a.lastHistoryDialogViewForTest()!!
                val recycler = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyList)
                val row = recycler.findViewHolderForAdapterPosition(0)!!.itemView
                row.performClick()
                idle()
                val text = row.findViewById<TextView>(R.id.historyDetailText).text.toString()
                assertTrue("first 8 files shown", text.contains("file1.txt") && text.contains("file8.txt"))
                assertTrue("9th hidden", !text.contains("file9.txt"))
                assertTrue(
                    "show-all line present",
                    text.contains(context.getString(R.string.history_show_all_files, 20))
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `root pushes say repository root instead of a folder`() {
        PushHistoryStore.record(
            context, entry(System.currentTimeMillis() - 60_000).copy(targetDir = "")
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a -> a.showHistoryDialogForTest() }
            scenario.onActivity { a ->
                val dialog = a.lastHistoryDialogViewForTest()!!
                val recycler = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyList)
                val row = recycler.findViewHolderForAdapterPosition(0)!!.itemView
                assertEquals(
                    "alice/repo · main · repository root",
                    row.findViewById<TextView>(R.id.historyRepoText).text.toString()
                )
            }
        } finally {
            scenario.close()
        }
    }
}
