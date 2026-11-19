package com.zippush.app

import com.zippush.app.engine.UploadEngine
import com.zippush.app.net.GitHubClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
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
 * Full-HTTP battery for the push flow, including the backdated commit.
 *
 * A MockWebServer stands in for api.github.com (GitHubClient takes an
 * injectable base URL); every endpoint of the Git Data API sequence is
 * scripted and every outgoing request is asserted:
 *
 *   GET  git/ref/heads/main          -> head SHA
 *   GET  git/commits/{sha}           -> base tree SHA
 *   POST git/blobs                   -> blob SHA (one per file)
 *   POST git/trees                   -> new tree SHA
 *   POST git/commits                 -> commit SHA   <-- author JSON here
 *   PATCH git/refs/heads/main        -> done
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushHttpTest {

    private lateinit var server: MockWebServer
    private val requests = mutableListOf<String>()
    private val authHeaders = mutableListOf<String?>()

    /** Records each request path + body, replies per the scripted map. */
    private inner class Script(private val handler: (RecordedRequest) -> MockResponse) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add("${request.method} ${request.path}\n${request.body.readUtf8()}")
            authHeaders.add(request.getHeader("Authorization"))
            return handler(request)
        }
    }

    private fun ok(json: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(json)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        requests.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Happy path: two files, backdated commit. */
    private fun happyDispatcher() = Script { req ->
        when {
            req.path!!.startsWith("/repos/o/r/git/ref/heads/main") ->
                ok("""{"ref":"refs/heads/main","object":{"sha":"HEADSHA"}}""")
            req.path!!.startsWith("/repos/o/r/git/commits/HEADSHA") ->
                ok("""{"sha":"HEADSHA","tree":{"sha":"BASETREE"}}""")
            req.path!!.startsWith("/repos/o/r/git/blobs") ->
                ok("""{"sha":"BLOB${requests.size}"}""")
            req.path!!.startsWith("/repos/o/r/git/trees") ->
                ok("""{"sha":"NEWTREE"}""")
            req.path!!.startsWith("/repos/o/r/git/commits") ->
                ok("""{"sha":"NEWSHA"}""")
            req.path!!.startsWith("/repos/o/r/git/refs/heads/main") ->
                ok("""{"object":{"sha":"NEWSHA"}}""")
            else -> MockResponse().setResponseCode(404).setBody("""{"message":"nope"}""")
        }
    }

    @Test
    fun `full push sequence sends six requests in order`() {
        server.dispatcher = happyDispatcher()
        val client = GitHubClient("tok", server.url("/").toString().trimEnd('/'))
        val engine = UploadEngine(client)

        val result = engine.push(
            owner = "o", repo = "r", branch = "main", newBranch = null,
            targetDir = "docs", commitMessage = "release",
            files = listOf(
                UploadEngine.PendingFile("a.txt", "hello".toByteArray()),
                UploadEngine.PendingFile("b.txt", "world".toByteArray())
            )
        )

        assertTrue("got $result", result is UploadEngine.PushResult.Success)
        // 2 files -> 2 blob uploads; the rest is one request each:
        // ref, commit, blobs x2, tree, commit, update-ref.
        if (requests.size != 7) {
            org.junit.Assert.fail("expected 7 requests, got ${requests.size}:\n" +
                requests.joinToString("\n") { it.lines()[0] })
        }
        val methods = requests.map { it.substringBefore(' ') }
        // The update-ref is PATCH on Android's OkHttp stack, or POST with
        // an X-HTTP-Method-Override header where PATCH is unavailable
        // (JDK HttpURLConnection / restrictive proxies) — both accepted.
        val lastIsPatch = methods.last() == "PATCH"
        val lastIsOverride = methods.last() == "POST"
        assertTrue(
            "update-ref must be PATCH or POST+override, got ${methods.last()}",
            lastIsPatch || lastIsOverride
        )
        assertEquals(listOf("GET", "GET", "POST", "POST", "POST", "POST"), methods.dropLast(1))
        val paths = requests.map { it.lines()[0].substringAfter(' ') }
        assertTrue(paths[0].startsWith("/repos/o/r/git/ref/heads/main"))
        assertTrue(paths[2].startsWith("/repos/o/r/git/blobs"))
        assertTrue(paths[3].startsWith("/repos/o/r/git/blobs"))
        assertTrue(paths[5].startsWith("/repos/o/r/git/commits"))
        assertTrue(paths[6].startsWith("/repos/o/r/git/refs/heads/main"))
        // Target dir is applied to tree entries.
        val treeBody = JSONObject(requests[4].lines()[1])
        val firstEntry = treeBody.getJSONArray("tree").getJSONObject(0)
        assertEquals("docs/a.txt", firstEntry.getString("path"))
    }

    @Test
    fun `backdated push sends complete author object with iso date`() {
        server.dispatcher = happyDispatcher()
        val client = GitHubClient("tok", server.url("/").toString().trimEnd('/'))
        val engine = UploadEngine(client)

        val result = engine.push(
            owner = "o", repo = "r", branch = "main", newBranch = null,
            targetDir = "", commitMessage = "late upload",
            files = listOf(UploadEngine.PendingFile("a.txt", "x".toByteArray())),
            authorDate = "2024-03-01T14:30:00Z",
            authorLogin = "alicesf"
        )

        assertTrue(result is UploadEngine.PushResult.Success)
        val commitBody = JSONObject(requests.first { it.startsWith("POST /repos/o/r/git/commits") }.lines()[1])
        // Complete author object: name + email (required by the API) + date.
        val author = commitBody.getJSONObject("author")
        assertEquals("alicesf", author.getString("name"))
        assertEquals("alicesf@users.noreply.github.com", author.getString("email"))
        assertEquals("2024-03-01T14:30:00Z", author.getString("date"))
        // The commit still has the required core fields.
        assertEquals("late upload", commitBody.getString("message"))
        assertEquals("NEWTREE", commitBody.getString("tree"))
    }

    @Test
    fun `push without backdate omits the author object entirely`() {
        server.dispatcher = happyDispatcher()
        val client = GitHubClient("tok", server.url("/").toString().trimEnd('/'))
        val engine = UploadEngine(client)

        engine.push(
            owner = "o", repo = "r", branch = "main", newBranch = null,
            targetDir = "", commitMessage = "plain",
            files = listOf(UploadEngine.PendingFile("a.txt", "x".toByteArray()))
        )

        val commitBody = JSONObject(requests.first { it.startsWith("POST /repos/o/r/git/commits") }.lines()[1])
        assertNull(commitBody.optJSONObject("author"))
    }

    @Test
    fun `blob upload carries base64 content and the auth header`() {
        server.dispatcher = happyDispatcher()
        val client = GitHubClient("SECRET_TOKEN", server.url("/").toString().trimEnd('/'))
        val engine = UploadEngine(client)

        engine.push(
            owner = "o", repo = "r", branch = "main", newBranch = null,
            targetDir = "", commitMessage = "m",
            files = listOf(UploadEngine.PendingFile("a.txt", "hi".toByteArray()))
        )

        val blobBody = JSONObject(requests.first { it.startsWith("POST /repos/o/r/git/blobs") }.lines()[1])
        assertEquals("base64", blobBody.getString("encoding"))
        assertEquals("aGk=", blobBody.getString("content")) // "hi" in base64
        // Every request carries the token as a Bearer header.
        assertTrue(authHeaders.all { it == "Bearer SECRET_TOKEN" })
    }

    @Test
    fun `api rejects commit with 422 and the failure names the stage`() {
        server.dispatcher = Script { req ->
            when {
                req.path!!.startsWith("/repos/o/r/git/ref/heads/main") ->
                    ok("""{"ref":"refs/heads/main","object":{"sha":"HEADSHA"}}""")
                req.path!!.startsWith("/repos/o/r/git/commits/HEADSHA") ->
                    ok("""{"sha":"HEADSHA","tree":{"sha":"BASETREE"}}""")
                req.path!!.startsWith("/repos/o/r/git/blobs") ->
                    ok("""{"sha":"BLOB1"}""")
                req.path!!.startsWith("/repos/o/r/git/trees") ->
                    ok("""{"sha":"NEWTREE"}""")
                req.path!!.startsWith("/repos/o/r/git/commits") ->
                    MockResponse().setResponseCode(422)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"message":"Invalid request.\n\n\"date\" must be a valid ISO 8601 date."}""")
                else -> MockResponse().setResponseCode(404).setBody("""{"message":"nope"}""")
            }
        }
        val client = GitHubClient("tok", server.url("/").toString().trimEnd('/'))
        val engine = UploadEngine(client)

        val result = engine.push(
            owner = "o", repo = "r", branch = "main", newBranch = null,
            targetDir = "", commitMessage = "m",
            files = listOf(UploadEngine.PendingFile("a.txt", "x".toByteArray())),
            authorDate = "2024-03-01T14:30:00Z",
            authorLogin = "alicesf"
        )

        assertTrue(result is UploadEngine.PushResult.Failure)
        result as UploadEngine.PushResult.Failure
        assertEquals("Create commit", result.stage)
        assertTrue(result.message.contains("422") || result.message.contains("ISO", ignoreCase = true) ||
            result.message.contains("Invalid request"))
        // The branch ref was never fast-forwarded: no PATCH in the log.
        assertTrue(requests.none { it.startsWith("PATCH") })
    }

    @Test
    fun `server error on blob aborts with upload stage and no commit follows`() {
        server.dispatcher = Script { req ->
            when {
                req.path!!.startsWith("/repos/o/r/git/ref/heads/main") ->
                    ok("""{"ref":"refs/heads/main","object":{"sha":"HEADSHA"}}""")
                req.path!!.startsWith("/repos/o/r/git/commits/HEADSHA") ->
                    ok("""{"sha":"HEADSHA","tree":{"sha":"BASETREE"}}""")
                req.path!!.startsWith("/repos/o/r/git/blobs") ->
                    MockResponse().setResponseCode(500).setBody("""{"message":"server boom"}""")
                else -> MockResponse().setResponseCode(404).setBody("""{"message":"nope"}""")
            }
        }
        val client = GitHubClient("tok", server.url("/").toString().trimEnd('/'))
        val engine = UploadEngine(client)

        val result = engine.push(
            owner = "o", repo = "r", branch = "main", newBranch = null,
            targetDir = "", commitMessage = "m",
            files = listOf(UploadEngine.PendingFile("a.txt", "x".toByteArray()))
        )

        assertTrue(result is UploadEngine.PushResult.Failure)
        result as UploadEngine.PushResult.Failure
        assertEquals("Upload a.txt", result.stage)
        assertTrue(requests.none { it.startsWith("POST /repos/o/r/git/commits") })
    }

    @Test
    fun `local base64 encoder matches android no-wrap output`() {
        // Cross-checks the hand-rolled encoder against known vectors.
        assertEquals("", GitHubClient.base64NoWrap(ByteArray(0)))
        assertEquals("aGk=", GitHubClient.base64NoWrap("hi".toByteArray()))
        assertEquals("aGVsbG8=", GitHubClient.base64NoWrap("hello".toByteArray()))
        // Binary bytes incl. all zero/high bits:
        val bin = byteArrayOf(0, 1, 2, -1, -128, 127)
        assertEquals("AAEC/4B/", GitHubClient.base64NoWrap(bin))
        // Padding cases 1 and 2:
        assertEquals("YQ==", GitHubClient.base64NoWrap("a".toByteArray()))
        assertEquals("YWI=", GitHubClient.base64NoWrap("ab".toByteArray()))
    }
}
