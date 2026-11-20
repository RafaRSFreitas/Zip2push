package com.zippush.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.zippush.app.engine.ArchiveReader
import com.zippush.app.engine.UploadEngine
import com.zippush.app.history.PushHistoryStore
import com.zippush.app.net.GitHubClient
import com.zippush.app.push.PushRunner
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v0.3.5: the archive button is "Add Rar/Zip/Tar" and accepts EXACTLY
 * those three formats (validated by magic bytes at pick time); the
 * second button is "Add file" (any files, several at once); "Add
 * folder" is unchanged. Also covers the RAR/tar.gz push journey and
 * the History record of a finished push.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveButtonsTest {

    @get:Rule
    val sessionStateRule = SessionStateTestRule()

    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PushRunner.resetForTest()
        PushHistoryStore.resetForTest(context)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            private fun ok(json: String) = MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(json)

            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path ?: ""
                return when {
                    p == "/user" -> ok("""{"login":"alice"}""")
                    p.startsWith("/user/repos") -> ok("""[
                        {"full_name":"alice/repo","name":"repo","owner":{"login":"alice"},
                         "private":false,"default_branch":"main","disabled":false}]""")
                    p.contains("/branches") -> ok("""[{"name":"main"}]""")
                    p.contains("/git/ref/heads/main") ->
                        ok("""{"ref":"refs/heads/main","object":{"sha":"HEADSHA"}}""")
                    p.contains("/git/commits/HEADSHA") ->
                        ok("""{"sha":"HEADSHA","tree":{"sha":"BASETREE"}}""")
                    p.contains("/git/blobs") -> ok("""{"sha":"BLOBSHA"}""")
                    p.contains("/git/trees") -> ok("""{"sha":"NEWTREE"}""")
                    p.contains("/git/commits") -> ok("""{"sha":"NEWSHA123456789"}""")
                    p.contains("/git/refs/heads/main") ->
                        ok("""{"object":{"sha":"NEWSHA123456789"}}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"message":"nope"}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        PushRunner.resetForTest()
        PushHistoryStore.resetForTest(context)
        server.shutdown()
    }

    private fun idle() {
        ShadowLooper.shadowMainLooper().idle()
    }

    private fun runUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun fixtureBytes(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("archives/$name")!!.readBytes()

    private fun tarGzBytes(): ByteArray {
        // A minimal real tar.gz with one file, built with the same
        // commons-compress the app uses.
        val tar = ByteArrayOutputStream()
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(tar).use { tos ->
            val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry("docs/readme.txt")
            val payload = "from tar.gz".toByteArray()
            e.size = payload.size.toLong()
            tos.putArchiveEntry(e)
            tos.write(payload)
            tos.closeArchiveEntry()
        }
        val gz = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
        return gz.toByteArray()
    }

    private fun zipBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("z/inner.txt"))
            zos.write("from zip".toByteArray())
            zos.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun MainActivity.onPushClickedForTest() {
        val m = MainActivity::class.java.getDeclaredMethod("onPushClicked")
        m.isAccessible = true
        m.invoke(this)
    }

    // ------------------------------------------------------------- the labels

    @Test
    fun `archive button is named Add Rar-Zip-Tar`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a ->
                val label = a.findViewById<Button>(R.id.zipBtn).text.toString()
                assertEquals("Add Rar/Zip/Tar", label)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `second button is named Add file and folder button unchanged`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a ->
                assertEquals("Add file", a.findViewById<Button>(R.id.filesBtn).text.toString())
                assertEquals("Add folder", a.findViewById<Button>(R.id.folderBtn).text.toString())
            }
        } finally {
            scenario.close()
        }
    }

    // ------------------------------------------- archive validation at pick time

    @Test
    fun `picking a non-archive is rejected with a friendly toast`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val uri = Uri.parse("content://robolectric/files/notes.txt")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(uri, ByteArrayInputStream("plain text".toByteArray()))
            scenario.onActivity { a ->
                a.onArchivePickedForTest(uri)
                idle()
                assertNull("nothing selected", a.tracker.zip)
                assertEquals(
                    context.getString(R.string.toast_unsupported_archive),
                    ShadowToast.getTextOfLatestToast()
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `picking a rar5 archive is accepted and summarized with its format`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val uri = Uri.parse("content://robolectric/files/archive.rar")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(uri, ByteArrayInputStream(fixtureBytes("rar5.rar")))
            scenario.onActivity { a ->
                a.onArchivePickedForTest(uri)
                idle()
                assertNotNull("rar selected", a.tracker.zip)
                val summary = a.findViewById<TextView>(R.id.selectionText).text.toString()
                assertTrue("summary names RAR: $summary", summary.contains("(RAR)"))
                assertTrue(summary.startsWith("Archive"))
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `picking a tar_gz archive is accepted and summarized as tar-dot-gz`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val uri = Uri.parse("content://robolectric/files/bundle.tgz")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(uri, ByteArrayInputStream(tarGzBytes()))
            scenario.onActivity { a ->
                a.onArchivePickedForTest(uri)
                idle()
                assertNotNull(a.tracker.zip)
                val summary = a.findViewById<TextView>(R.id.selectionText).text.toString()
                assertTrue("summary names tar.gz: $summary", summary.contains("(tar.gz)"))
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `picking a zip archive is accepted`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val uri = Uri.parse("content://robolectric/files/bundle.zip")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(uri, ByteArrayInputStream(zipBytes()))
            scenario.onActivity { a ->
                a.onArchivePickedForTest(uri)
                idle()
                assertNotNull(a.tracker.zip)
            }
        } finally {
            scenario.close()
        }
    }

    // ------------------------------------------------- the "Add file" contract

    @Test
    fun `add file button accepts any file and several at once`() {
        // The contract of the second button: ANY file, MULTIPLE at once.
        // pickFiles (OpenMultipleDocuments) already provides that; what
        // we assert here is the tracker contract the button feeds and
        // that a non-archive file is perfectly legal THERE (unlike the
        // archive button).
        val tracker = SelectionTracker()
        val u1 = Uri.parse("content://x/one.txt")
        val u2 = Uri.parse("content://x/two.jpg")
        tracker.pickFiles(listOf(u1, u2))
        assertEquals(listOf(u1, u2), tracker.files)
        assertTrue(tracker.hasSelection())
    }

    @Test
    fun `add file selection pushes without any archive validation`() {
        // A push made of plain files (no archive) goes through the
        // whole engine path — proving "any file" works end to end.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.connectWithBaseUrlForTest("test-token", server.url("/").toString().trimEnd('/'))
            }
            val u1 = Uri.parse("content://robolectric.files/one.txt")
            val u2 = Uri.parse("content://robolectric.files/two.bin")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(u1, ByteArrayInputStream("first".toByteArray()))
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(u2, ByteArrayInputStream(byteArrayOf(1, 2)))
            scenario.onActivity {
                it.selectedRepo = GitHubClient.Repo(
                    fullName = "alice/repo", name = "repo", owner = "alice",
                    private = false, defaultBranch = "main"
                )
                it.tracker.pickFiles(listOf(u1, u2))
                it.refreshSelectionSummary()
                it.onPushClickedForTest()
            }
            assertTrue(
                "push must finish",
                runUntil { PushRunner.state is PushRunner.State.Done }
            )
            val result = (PushRunner.state as PushRunner.State.Done).result
            assertTrue("push succeeded: $result", result is UploadEngine.PushResult.Success)
            assertEquals(2, (result as UploadEngine.PushResult.Success).filesPushed)
        } finally {
            scenario.close()
        }
    }

    // ------------------------------------------------------- push integration

    private fun preparePush(a: MainActivity, archiveUri: Uri) {
        a.selectedRepo = GitHubClient.Repo(
            fullName = "alice/repo", name = "repo", owner = "alice",
            private = false, defaultBranch = "main"
        )
        a.tracker.pickZip(archiveUri)
        a.refreshSelectionSummary()
    }

    @Test
    fun `a rar5 archive pushes end to end and lands in history`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.connectWithBaseUrlForTest("test-token", server.url("/").toString().trimEnd('/'))
            }
            val uri = Uri.parse("content://robolectric/files/payload.rar")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(uri, ByteArrayInputStream(fixtureBytes("rar5.rar")))
            scenario.onActivity {
                preparePush(it, uri)
                it.onPushClickedForTest()
            }

            assertTrue(
                "push must finish",
                runUntil { PushRunner.state is PushRunner.State.Done }
            )

            val result = (PushRunner.state as PushRunner.State.Done).result
            assertTrue("push succeeded: $result", result is UploadEngine.PushResult.Success)
            assertEquals(2, (result as UploadEngine.PushResult.Success).filesPushed)

            // And the History recorded it with the RAR's inner paths.
            val e = PushHistoryStore.read(context).first()
            assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), e.files)
            assertEquals("alice/repo", e.repoFullName)
            assertEquals("main", e.branch)
            assertTrue(e.commitUrl!!.contains("NEWSHA123456789"))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `a tar_gz archive pushes end to end`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.connectWithBaseUrlForTest("test-token", server.url("/").toString().trimEnd('/'))
            }
            val uri = Uri.parse("content://robolectric/files/bundle.tgz")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(uri, ByteArrayInputStream(tarGzBytes()))
            scenario.onActivity {
                preparePush(it, uri)
                it.onPushClickedForTest()
            }

            assertTrue(
                "push must finish",
                runUntil { PushRunner.state is PushRunner.State.Done }
            )
            val result = (PushRunner.state as PushRunner.State.Done).result
            assertTrue(result is UploadEngine.PushResult.Success)
            // Root folder "docs" is stripped by skipRootFolder.
            assertEquals(
                listOf("readme.txt"),
                PushHistoryStore.read(context).first().files
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `a corrupt archive fails the push with a readable reason`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.connectWithBaseUrlForTest("test-token", server.url("/").toString().trimEnd('/'))
            }
            // Valid ZIP magic, truncated body: detection passes at pick
            // time, the engine fails during extraction.
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("a.txt"))
                zos.write("hello".toByteArray())
                zos.closeEntry()
            }
            val truncated = bos.toByteArray().copyOf(40)
            val uri = Uri.parse("content://robolectric/files/cut.zip")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(uri, ByteArrayInputStream(truncated))
            scenario.onActivity {
                preparePush(it, uri)
                it.onPushClickedForTest()
            }

            assertTrue(
                "push must finish",
                runUntil { PushRunner.state is PushRunner.State.Done }
            )
            val result = (PushRunner.state as PushRunner.State.Done).result
            assertTrue("push fails cleanly: $result", result is UploadEngine.PushResult.Failure)
        } finally {
            scenario.close()
        }
    }

    // ---------------------------------------------------------------- history

    @Test
    fun `history menu opens the dialog with recorded pushes`() {
        // Pre-record one push, open the menu path, check the dialog.
        PushHistoryStore.append(
            context,
            PushHistoryStore.Entry(
                timestampMs = 1_700_000_000_000,
                repoFullName = "alice/repo",
                branch = "main",
                targetDir = "",
                messageTitle = "RAR upload",
                fileCount = 2,
                files = listOf("FILE1.TXT", "FILE2.TXT"),
                commitUrl = "https://github.com/alice/repo/commit/abc"
            )
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a ->
                a.showHistoryDialog()
                idle()
                val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                    ?: error("no dialog shown")
                val list = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(
                    R.id.historyList
                )
                assertNotNull("history list visible", list)
                assertEquals(1, list!!.adapter!!.itemCount)
                // The row's first child is the title TextView.
                val holder = list.findViewHolderForAdapterPosition(0)
                assertNotNull(holder)
                val title = (holder!!.itemView as android.view.ViewGroup).getChildAt(0)
                assertEquals("RAR upload", (title as TextView).text.toString())
                val meta = (holder.itemView as android.view.ViewGroup).getChildAt(1) as TextView
                assertTrue(meta.text.toString().contains("alice/repo"))
                assertTrue(meta.text.toString().contains("main"))
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `history stays empty when a push fails`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.connectWithBaseUrlForTest("test-token", server.url("/").toString().trimEnd('/'))
            }
            val uri = Uri.parse("content://robolectric/files/broken.zip")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(uri, ByteArrayInputStream("not an archive".toByteArray()))
            scenario.onActivity {
                preparePush(it, uri)
                it.onPushClickedForTest()
            }
            assertTrue(
                "push must finish",
                runUntil { PushRunner.state is PushRunner.State.Done }
            )
            assertEquals(0, PushHistoryStore.read(context).size)
        } finally {
            scenario.close()
        }
    }
}
