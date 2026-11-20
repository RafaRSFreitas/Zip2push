package com.zippush.app.engine

import com.zippush.app.net.GitHubClient
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
import org.robolectric.annotation.Config
import java.io.File
import java.net.URL

/**
 * Engine hardening against the v0.3.0 bug list, driven through a real
 * HTTP stack (MockWebServer):
 *  - 1.4 blank newBranch behaves as null;
 *  - 1.5 duplicate names are deduped, not overwritten;
 *  - 1.8 targetDir traversal is sanitized;
 *  - 1.9 empty file list fails fast;
 *  - 2.9 a moved branch (422 on updateRef) triggers one rebase retry.
 *
 * Robolectric runner: the engine parses JSON bodies, and the JVM stub of
 * org.json throws "not mocked" outside Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineHardeningTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: UploadEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = HappyPathDispatcher()
        server.start()
        engine = UploadEngine(GitHubClient("test-token", baseUrl = server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** The full happy-path sequence: head, commit, blobs, tree, commit, ref. */
    private class HappyPathDispatcher : Dispatcher() {
        var updateRefCalls = 0
        var firstRefUpdateFails = false
        override fun dispatch(request: RecordedRequest): MockResponse {
            val p = request.path ?: ""
            return when {
                p.contains("/git/ref/heads/main") -> ok("""{"object":{"sha":"head1"}}""")
                p.contains("/git/commits/head1") -> ok("""{"tree":{"sha":"tree1"}}""")
                p.endsWith("/git/blobs") -> ok("""{"sha":"blob${request.hashCode().toLong() and 0xffff}"}""")
                p.endsWith("/git/trees") -> ok("""{"sha":"newtree"}""")
                p.endsWith("/git/commits") && !p.contains("/git/commits/") ->
                    ok("""{"sha":"commit${System.nanoTime() % 1000}"}""")
                // updateRef may arrive as PATCH or as POST + X-HTTP-Method-Override
                // (see GitHubClient.newConnection) — both mean "update the ref".
                p.contains("/git/refs/heads/main") &&
                    (request.method == "PATCH" ||
                        request.getHeader("X-HTTP-Method-Override") == "PATCH") -> {
                    updateRefCalls++
                    if (firstRefUpdateFails && updateRefCalls == 1) {
                        MockResponse().setResponseCode(422)
                            .setBody("""{"message":"Update is not a fast forward"}""")
                    } else ok("""{"object":{"sha":"ok"}}""")
                }
                p.contains("/git/ref/heads/dev") -> ok("""{"object":{"sha":"head1"}}""")
                else -> MockResponse().setResponseCode(404).setBody("""{"message":"nope"}""")
            }
        }

        private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
    }

    private fun file(path: String, content: String = "x") =
        UploadEngine.PendingFile(path, content.toByteArray())

    @Test
    fun `empty file list fails fast without any API call`() {
        val r = engine.push("o", "r", "main", null, "", "msg", emptyList())
        assertTrue(r is UploadEngine.PushResult.Failure)
        assertEquals("Prepare", (r as UploadEngine.PushResult.Failure).stage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `blank newBranch is treated as no new branch`() {
        // Bug 1.4: "" would have tried to create a branch named "".
        val r = engine.push("o", "r", "main", "  ", "", "msg", listOf(file("a.txt")))
        assertTrue(r is UploadEngine.PushResult.Success)
        // No POST /git/refs (branch creation) happened:
        val refCreates = (0 until server.requestCount)
            .map { server.takeRequest() }
            .count { it.path!!.endsWith("/git/refs") && it.method == "POST" }
        assertEquals(0, refCreates)
    }

    @Test
    fun `duplicate display names are deduped into distinct paths`() {
        // Bug 1.5: two "a.txt" picks used to overwrite each other in the tree.
        val pending = engine.collectNamedFiles(
            listOf("a.txt" to "1".toByteArray(), "a.txt" to "2".toByteArray())
        )
        assertEquals(listOf("a.txt", "a (1).txt"), pending.map { it.path })

        val r = engine.push("o", "r", "main", null, "", "msg", pending)
        assertTrue(r is UploadEngine.PushResult.Success)
        // Both entries present in the tree request (order-insensitive check).
        val treeReq = (0 until server.requestCount)
            .map { server.takeRequest() }
            .first { it.path!!.endsWith("/git/trees") }
        val body = treeReq.body.readUtf8()
        assertTrue(body.contains("\"a.txt\""))
        assertTrue(body.contains("\"a (1).txt\""))
    }

    @Test
    fun `target dir traversal is sanitized before joining`() {
        // Bug 1.8: "../docs" must become "docs".
        val r = engine.push("o", "r", "main", null, "../docs", "msg", listOf(file("a.txt")))
        assertTrue(r is UploadEngine.PushResult.Success)
        val treeReq = (0 until server.requestCount)
            .map { server.takeRequest() }
            .first { it.path!!.endsWith("/git/trees") }
        val body = treeReq.body.readUtf8()
        // org.json escapes "/" as "\/", so the JSON text holds "docs\/a.txt".
        assertTrue(body.contains("docs\\/a.txt"))
        assertTrue(!body.contains("../"))
    }

    @Test
    fun `moved branch triggers a single rebase retry and then succeeds`() {
        // Feature 2.9: first updateRef 422s (branch moved), engine re-reads
        // the head, rebuilds the commit on the new parent and retries.
        val dispatcher = HappyPathDispatcher()
        dispatcher.firstRefUpdateFails = true
        server.dispatcher = dispatcher

        val r = engine.push("o", "r", "main", null, "", "msg", listOf(file("a.txt")))
        assertTrue(r is UploadEngine.PushResult.Success)

        val requests = (0 until server.requestCount).map { server.takeRequest() }
        val patchCount = requests.count {
            it.path!!.contains("/git/refs/heads/main") &&
                (it.method == "PATCH" ||
                    it.getHeader("X-HTTP-Method-Override") == "PATCH")
        }
        val headReads = requests.count { it.path!!.contains("/git/ref/heads/main") }
        val commits = requests.count { it.path!!.endsWith("/git/commits") && it.method == "POST" }
        // 1 initial head read + 1 re-read after the conflict; 2 commits; 2 patches.
        assertEquals(2, patchCount)
        assertEquals(2, headReads)
        assertEquals(2, commits)
    }

    @Test
    fun `traversal file paths inside the push list are neutralized`() {
        // Defence in depth: engine.push sanitizes even unsanitized PendingFiles.
        val r = engine.push(
            "o", "r", "main", null, "", "msg",
            listOf(
                file("../../evil.txt"),
                file("/abs.txt"),
                file("ok.txt")
            )
        )
        assertTrue(r is UploadEngine.PushResult.Success)
        val treeReq = (0 until server.requestCount)
            .map { server.takeRequest() }
            .first { it.path!!.endsWith("/git/trees") }
        val body = treeReq.body.readUtf8()
        assertTrue(body.contains("evil.txt"))
        assertTrue(body.contains("abs.txt"))
        assertTrue(body.contains("ok.txt"))
        assertTrue(!body.contains("../"))
        assertTrue(!body.contains("\"/"))
    }
}
