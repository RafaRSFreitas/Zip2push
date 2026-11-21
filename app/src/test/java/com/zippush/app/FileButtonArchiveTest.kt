package com.zippush.app

import android.content.Context
import android.net.Uri
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.zippush.app.engine.ArchiveReader
import com.zippush.app.engine.UploadEngine
import com.zippush.app.net.GitHubClient
import com.zippush.app.push.PushRunner
import com.zippush.app.history.PushHistoryStore
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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v0.4.0: the "Add Rar/Zip/Tar" button — label, archive validation at
 * pick time (magic bytes, friendly toast for anything that is not a
 * plain RAR/ZIP/TAR — tar.gz included) and full pushes with the
 * supported formats (RAR5, tar) through to History.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileButtonArchiveTest {

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
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ok = { json: String ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(json)
                }
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
        ShadowLooper.shadowMainLooper().runToEndOfTasks()
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

    private fun tarBytes(): ByteArray {
        // A minimal UNCOMPRESSED tar (one of the three formats the button
        // accepts) with one file inside a single root folder.
        val tar = ByteArrayOutputStream()
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(tar).use { tos ->
            val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry("docs/readme.txt")
            val payload = "from tar".toByteArray()
            e.size = payload.size.toLong()
            tos.putArchiveEntry(e)
            tos.write(payload)
            tos.closeArchiveEntry()
        }
        return tar.toByteArray()
    }

    // ------------------------------------------------------------- the button

    @Test
    fun `button is named Add Rar-Zip-Tar`() {
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
                val toast = ShadowToast.getTextOfLatestToast()
                assertEquals(
                    context.getString(R.string.toast_unsupported_archive), toast
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
                // No display-name provider in Robolectric: the summary
                // falls back to "archive" — the FORMAT label is what we
                // assert here (the friendly name is cosmetic).
                val summary = a.findViewById<TextView>(R.id.selectionText).text.toString()
                assertTrue("summary names RAR: $summary", summary.contains("(RAR)"))
                assertTrue(summary.startsWith("Archive"))
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `picking a tar_gz archive is rejected - only Rar-Zip-Tar accepted`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val uri = Uri.parse("content://robolectric/files/bundle.tgz")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(uri, ByteArrayInputStream(tarGzBytes()))
            scenario.onActivity { a ->
                a.onArchivePickedForTest(uri)
                idle()
                assertNull("tar.gz must be rejected by the Rar/Zip/Tar button", a.tracker.zip)
                val toast = ShadowToast.getTextOfLatestToast()
                assertEquals(
                    context.getString(R.string.toast_unsupported_archive), toast
                )
            }
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
        a.selectedBranch = GitHubClient.Branch("main")
        a.tracker.pickZip(archiveUri)
        a.setCommitMessageForTest("RAR upload")
    }

    private fun MainActivity.onPushClickedForTest() {
        val m = MainActivity::class.java.getDeclaredMethod("onPushClicked")
        m.isAccessible = true
        m.invoke(this)
    }

    @Test
    fun `a rar5 archive pushes end to end and lands in history`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.vaultForTest().setActiveLogin("alice")
                it.connectWithBaseUrlForTest("test-token", server.url("/").toString())
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
            assertEquals("RAR upload", e.messageTitle)
            assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), e.files)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `a plain tar archive pushes end to end`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.connectWithBaseUrlForTest("test-token", server.url("/").toString())
            }
            val uri = Uri.parse("content://robolectric/files/bundle.tar")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(uri, ByteArrayInputStream(tarBytes()))
            scenario.onActivity {
                preparePush(it, uri)
                it.setCommitMessageForTest("tar upload")
                it.onPushClickedForTest()
            }

            assertTrue(
                "push must finish",
                runUntil { PushRunner.state is PushRunner.State.Done }
            )
            val result = (PushRunner.state as PushRunner.State.Done).result
            assertTrue(result is UploadEngine.PushResult.Success)
            assertEquals(
                listOf("readme.txt"), // root folder "docs" is stripped
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
                it.connectWithBaseUrlForTest("test-token", server.url("/").toString())
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
}
