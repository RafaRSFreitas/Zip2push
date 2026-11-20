package com.zippush.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import com.zippush.app.engine.UploadEngine
import com.zippush.app.net.GitHubClient
import com.zippush.app.push.PushRunner
import com.zippush.app.SelectionTracker
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Engine-level check of the v0.3.1 progress callback: three files in a
 * ZIP must produce exactly three ticks (1/3, 2/3, 3/3) — first directly
 * on UploadEngine.push, then through PushRunner's Running states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushProgressEngineTest {

    class App : Application()

    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PushRunner.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path ?: ""
                val ok = { json: String ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(json)
                }
                return when {
                    p.startsWith("/repos/alice/repo/git/ref/heads/main") ->
                        ok("""{"ref":"refs/heads/main","object":{"sha":"HEADSHA"}}""")
                    p.startsWith("/repos/alice/repo/git/commits/HEADSHA") ->
                        ok("""{"sha":"HEADSHA","tree":{"sha":"BASETREE"}}""")
                    p.startsWith("/repos/alice/repo/git/blobs") ->
                        ok("""{"sha":"BLOB${System.nanoTime()}"}""")
                    p.startsWith("/repos/alice/repo/git/trees") ->
                        ok("""{"sha":"NEWTREE"}""")
                    p.startsWith("/repos/alice/repo/git/commits") ->
                        ok("""{"sha":"NEWSHA"}""")
                    p.startsWith("/repos/alice/repo/git/refs/heads/main") ->
                        ok("""{"object":{"sha":"NEWSHA"}}""")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        PushRunner.resetForTest()
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

    /** A ZIP with three files under one root folder — the shape the app
     *  expects (the root folder is stripped on extraction). */
    private fun zipWithThreeFiles(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for (name in listOf("a.txt", "b.txt", "c.txt")) {
                zip.putNextEntry(ZipEntry("docs/$name"))
                zip.write("content of $name".toByteArray())
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun threeFileSelection(): SelectionTracker.Snapshot {
        val uri = Uri.parse("content://robolectric.zips/three.zip")
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(zipWithThreeFiles()))
        val tracker = SelectionTracker()
        tracker.pickZip(uri)
        return tracker.snapshot()
    }

    private fun repo() = GitHubClient.Repo(
        fullName = "alice/repo", name = "repo", owner = "alice",
        private = false, defaultBranch = "main"
    )

    @Test
    fun `engine reports one tick per file in order`() {
        val client = GitHubClient("t", server.url("/").toString())
        val engine = UploadEngine(client)
        val zf = java.io.File.createTempFile("three", ".zip").apply {
            writeBytes(zipWithThreeFiles())
        }
        val files = engine.extractZipEntries(zf, skipRootFolder = true)
        assertEquals(3, files.size)
        val ticks = mutableListOf<Pair<Int, Int>>()
        val result = engine.push(
            owner = "alice", repo = "repo", branch = "main", newBranch = null,
            targetDir = "", commitMessage = "m",
            files = files,
            onProgress = { d, t -> ticks.add(d to t) }
        )
        assertTrue("push must succeed: $result", result is UploadEngine.PushResult.Success)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), ticks)
        zf.delete()
    }

    @Test
    fun `runner surfaces the ticks as running progress states`() {
        val client = GitHubClient("t", server.url("/").toString())
        val engine = UploadEngine(client)
        val seen = mutableListOf<Pair<Int, Int>>()
        PushRunner.addObserver { st ->
            val p = (st as? PushRunner.State.Running)?.progress
            if (p != null) seen.add(p.done to p.total)
        }
        val started = PushRunner.start(
            context = context,
            engine = engine,
            selection = threeFileSelection(),
            repo = repo(),
            branch = "main",
            targetDir = "",
            message = "m",
            authorDateIso = null,
            authorLogin = null
        )
        assertTrue(started)
        assertTrue("push must finish",
            runUntil { PushRunner.state is PushRunner.State.Done })
        val result = (PushRunner.state as PushRunner.State.Done).result
        assertTrue("push must succeed: $result",
            result is UploadEngine.PushResult.Success)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
    }
}
