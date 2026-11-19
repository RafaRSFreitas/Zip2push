package com.zippush.app.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal GitHub REST API v3 client. All requests run on background threads
 * (see GitHubRepository / UploadEngine callers) — this class is synchronous.
 */
class GitHubClient(
    private val token: String,
    /** Injectable for tests (MockWebServer); empty = real GitHub. */
    private val baseUrl: String = "https://api.github.com"
) {

    /** Result of an API call that returns a JSON body. */
    sealed class ApiResult<out T> {
        data class Success<T>(val data: T) : ApiResult<T>()
        data class Error(val code: Int, val message: String) : ApiResult<Nothing>()
    }

    /** A repository entry shown in the dropdown. */
    data class Repo(
        val fullName: String,
        val name: String,
        val owner: String,
        val private: Boolean,
        val defaultBranch: String
    ) {
        /** The dropdown writes the selected item's toString() into the field. */
        override fun toString() = fullName

        companion object {
            /**
             * optString() returns the STRING "null" for JSON null (a classic
             * org.json trap), so nulls must be checked explicitly.
             */
            private fun JSONObject.optRealString(key: String): String {
                if (isNull(key)) return ""
                return optString(key, "").orEmpty()
            }

            /**
             * Parses a /user/repos page defensively: entries missing owner,
             * full_name or name are skipped instead of throwing — GitHub
             * returns odd entries for renamed/deleted/forked repos.
             */
            fun parseList(arr: JSONArray): List<Repo> {
                val out = mutableListOf<Repo>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val fullName = o.optRealString("full_name")
                    val name = o.optRealString("name")
                    val owner = if (o.isNull("owner")) ""
                        else o.optJSONObject("owner")?.optRealString("login").orEmpty()
                    if (fullName.isBlank() || name.isBlank() || owner.isBlank()) continue
                    if (o.optBoolean("disabled", false)) continue
                    out.add(
                        Repo(
                            fullName = fullName,
                            name = name,
                            owner = owner,
                            private = o.optBoolean("private", false),
                            defaultBranch = o.optRealString("default_branch")
                                .ifBlank { "main" }
                        )
                    )
                }
                return out
            }
        }
    }

    /** A branch entry shown in the branch dropdown. */
    data class Branch(val name: String) {
        override fun toString() = name

        companion object {
            /** Parses a /branches page, skipping blank or null names. */
            fun parseList(arr: JSONArray): List<Branch> {
                val out = mutableListOf<Branch>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = if (o.isNull("name")) ""
                        else o.optString("name", "").orEmpty()
                    if (name.isNotBlank()) out.add(Branch(name))
                }
                return out
            }
        }
    }

    // ---------------------------------------------------------------------
    // Low-level request helpers
    // ---------------------------------------------------------------------

    private fun newConnection(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "ZipPush-Android")
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            conn.requestMethod = method
        } catch (e: java.net.ProtocolException) {
            // Some HttpURLConnection implementations (and corporate proxies)
            // reject PATCH. GitHub documents the POST + method-override
            // fallback for exactly this case.
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", method)
        }
        return conn
    }

    private fun readError(conn: HttpURLConnection): String {
        return try {
            val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
            if (body.isNotBlank()) {
                try { JSONObject(body).optString("message", body) } catch (e: Exception) { body }
            } else {
                conn.responseMessage ?: "HTTP ${conn.responseCode}"
            }
        } catch (e: Exception) {
            "HTTP ${conn.responseCode}"
        }
    }

    private inline fun <reified T> execute(url: String, method: String, body: String? = null): ApiResult<T> {
        var conn: HttpURLConnection? = null
        try {
            conn = newConnection(url, method)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code / 100 != 2) return ApiResult.Error(code, readError(conn))
            if (T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST")
                return ApiResult.Success(Unit as T)
            }
            val text = conn.inputStream.bufferedReader().readText()
            if (T::class == String::class) {
                @Suppress("UNCHECKED_CAST")
                return ApiResult.Success(text as T)
            }
            // JSONArray or JSONObject inferred from payload shape
            return try {
                val parsed = JSONObject(text)
                @Suppress("UNCHECKED_CAST")
                ApiResult.Success(parsed as T)
            } catch (e: Exception) {
                try {
                    val arr = JSONArray(text)
                    @Suppress("UNCHECKED_CAST")
                    ApiResult.Success(arr as T)
                } catch (e2: Exception) {
                    ApiResult.Error(-1, "Unexpected response format")
                }
            }
        } catch (e: IOException) {
            return ApiResult.Error(-1, "Network error: ${e.message ?: "connection failed"}")
        } catch (e: Exception) {
            return ApiResult.Error(-1, "Error: ${e.message ?: "unknown"}")
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /** GET /user — verifies the token and returns the login name. */
    fun verifyToken(): ApiResult<String> {
        return when (val r = execute<JSONObject>("$baseUrl/user", "GET")) {
            is ApiResult.Success -> {
                val login = r.data.optString("login", "")
                if (login.isBlank()) ApiResult.Error(-1, "Token has no login (expired?)")
                else ApiResult.Success(login)
            }
            is ApiResult.Error -> r
        }
    }

    /** GET /user/repos — all repositories the token can push to, newest first.
     *  Defensive against schema changes: every field is read optionally and
     *  malformed entries are skipped instead of crashing the caller. */
    fun listRepos(): ApiResult<List<Repo>> {
        val repos = mutableListOf<Repo>()
        var page = 1
        while (page <= MAX_PAGES) {
            val url = "$baseUrl/user/repos?per_page=100&page=$page" +
                    "&affiliation=owner,collaborator,organization_member&sort=full_name"
            when (val r = execute<JSONArray>(url, "GET")) {
                is ApiResult.Success -> {
                    val arr = r.data
                    repos.addAll(Repo.parseList(arr))
                    if (arr.length() == 0 || arr.length() < 100) break
                    page++
                }
                is ApiResult.Error -> return r
            }
        }
        return ApiResult.Success(repos)
    }

    /** GET /repos/{owner}/{repo}/branches — branches for the branch dropdown. */
    fun listBranches(owner: String, repo: String): ApiResult<List<Branch>> {
        val branches = mutableListOf<Branch>()
        var page = 1
        while (page <= MAX_PAGES) {
            val url = "$baseUrl/repos/$owner/$repo/branches?per_page=100&page=$page"
            when (val r = execute<JSONArray>(url, "GET")) {
                is ApiResult.Success -> {
                    val arr = r.data
                    branches.addAll(Branch.parseList(arr))
                    if (arr.length() == 0 || arr.length() < 100) break
                    page++
                }
                is ApiResult.Error -> return r
            }
        }
        return ApiResult.Success(branches)
    }

    /** GET /repos/{owner}/{repo}/git/ref/heads/{branch} — commit SHA of a branch head. */
    fun getBranchHead(owner: String, repo: String, branch: String): ApiResult<String> {
        val url = "$baseUrl/repos/$owner/$repo/git/ref/heads/$branch"
        return when (val r = execute<JSONObject>(url, "GET")) {
            is ApiResult.Success -> ApiResult.Success(r.data.getJSONObject("object").getString("sha"))
            is ApiResult.Error -> r
        }
    }

    /** POST /repos/{owner}/{repo}/git/refs — creates a new branch from a base SHA. */
    fun createBranch(owner: String, repo: String, branch: String, fromSha: String): ApiResult<String> {
        val body = JSONObject().put("ref", "refs/heads/$branch").put("sha", fromSha)
        return when (val r = execute<JSONObject>(
            "$baseUrl/repos/$owner/$repo/git/refs", "POST", body.toString()
        )) {
            is ApiResult.Success -> ApiResult.Success(r.data.getJSONObject("object").getString("sha"))
            is ApiResult.Error -> r
        }
    }

    /** GET /repos/{owner}/{repo}/git/commits/{sha} — returns the commit's tree SHA. */
    fun getCommit(owner: String, repo: String, sha: String): ApiResult<String> {
        val url = "$baseUrl/repos/$owner/$repo/git/commits/$sha"
        return when (val r = execute<JSONObject>(url, "GET")) {
            is ApiResult.Success -> {
                // "tree" is normally an object {"sha": ...}; fall back to plain string.
                val treeField = r.data.optJSONObject("tree")
                val treeSha = treeField?.getString("sha") ?: r.data.getString("tree")
                ApiResult.Success(treeSha)
            }
            is ApiResult.Error -> r
        }
    }

    /** POST /repos/{owner}/{repo}/git/blobs — binary-safe blob upload (base64). */
    fun createBlob(owner: String, repo: String, bytes: ByteArray): ApiResult<String> {
        val body = JSONObject()
            .put("content", base64NoWrap(bytes))
            .put("encoding", "base64")
        return when (val r = execute<JSONObject>(
            "$baseUrl/repos/$owner/$repo/git/blobs", "POST", body.toString()
        )) {
            is ApiResult.Success -> ApiResult.Success(r.data.getString("sha"))
            is ApiResult.Error -> r
        }
    }

    /** GET /repos/{owner}/{repo}/git/trees/{treeSha}?recursive=1 */
    fun getTree(owner: String, repo: String, treeSha: String): ApiResult<JSONObject> {
        val url = "$baseUrl/repos/$owner/$repo/git/trees/$treeSha?recursive=1"
        return execute(url, "GET")
    }

    /** POST /repos/{owner}/{repo}/git/trees — build the new tree. */
    fun createTree(
        owner: String,
        repo: String,
        baseTreeSha: String?,
        entries: List<TreeEntry>
    ): ApiResult<String> {
        val arr = JSONArray()
        entries.forEach { e ->
            val o = JSONObject()
                .put("path", e.path)
                .put("mode", e.mode)
                .put("type", "blob")
                .put("sha", e.sha)
            arr.put(o)
        }
        val body = JSONObject()
        if (baseTreeSha != null) body.put("base_tree", baseTreeSha)
        body.put("tree", arr)
        return when (val r = execute<JSONObject>(
            "$baseUrl/repos/$owner/$repo/git/trees", "POST", body.toString()
        )) {
            is ApiResult.Success -> ApiResult.Success(r.data.getString("sha"))
            is ApiResult.Error -> r
        }
    }

    /**
     * POST /repos/{owner}/{repo}/git/commits
     *
     * [authorDate] (ISO-8601, e.g. 2024-03-01T14:30:00Z) makes the commit
     * retroactive: GitHub shows it under that date. The committer date —
     * what actually orders history and feeds the activity/contribution
     * graphs — stays the real upload time.
     *
     * The GitHub API expects a complete author object when any author
     * field is sent: name and email are required, date is optional. The
     * app authenticates with a token only, so callers pass the account
     * login and we use GitHub's noreply address as the email.
     */
    fun createCommit(
        owner: String,
        repo: String,
        message: String,
        treeSha: String,
        parentShas: List<String>,
        authorDate: String? = null,
        authorName: String? = null,
        authorEmail: String? = null
    ): ApiResult<String> {
        val parents = JSONArray()
        parentShas.forEach { parents.put(it) }
        val body = JSONObject()
            .put("message", message)
            .put("tree", treeSha)
            .put("parents", parents)
        if (authorDate != null) {
            val name = authorName?.takeIf { it.isNotBlank() } ?: "Zip2Push"
            val email = authorEmail?.takeIf { it.isNotBlank() }
                ?: "$name@users.noreply.github.com"
            body.put("author", JSONObject()
                .put("name", name)
                .put("email", email)
                .put("date", authorDate))
        }
        return when (val r = execute<JSONObject>(
            "$baseUrl/repos/$owner/$repo/git/commits", "POST", body.toString()
        )) {
            is ApiResult.Success -> ApiResult.Success(r.data.getString("sha"))
            is ApiResult.Error -> r
        }
    }

    /** PATCH /repos/{owner}/{repo}/git/refs/heads/{branch} — fast-forwards the branch. */
    fun updateRef(owner: String, repo: String, branch: String, sha: String): ApiResult<Unit> {
        val body = JSONObject().put("sha", sha)
        return execute(
            "$baseUrl/repos/$owner/$repo/git/refs/heads/$branch", "PATCH", body.toString()
        )
    }

    /** Tree entry used when building the new commit tree. */
    data class TreeEntry(val path: String, val sha: String, val mode: String = "100644")

    companion object {
        /** Safety cap for paginated list endpoints (100 pages × 100 items). */
        private const val MAX_PAGES = 100

        /**
         * Base64 without line wraps. java.util.Base64 is API 26+ and the
         * app runs from API 24, so this is a tiny local encoder with the
         * standard alphabet (+ /) and '=' padding — same output as
         * android.util.Base64 NO_WRAP, but also usable from JVM tests.
         */
        internal fun base64NoWrap(bytes: ByteArray): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val out = StringBuilder((bytes.size + 2) / 3 * 4)
            var i = 0
            while (i + 2 < bytes.size) {
                val n = (bytes[i].toInt() and 0xFF) shl 16 or
                        ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                        (bytes[i + 2].toInt() and 0xFF)
                out.append(chars[(n shr 18) and 0x3F])
                out.append(chars[(n shr 12) and 0x3F])
                out.append(chars[(n shr 6) and 0x3F])
                out.append(chars[n and 0x3F])
                i += 3
            }
            val rem = bytes.size - i
            if (rem == 1) {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(chars[(n shr 18) and 0x3F])
                out.append(chars[(n shr 12) and 0x3F])
                out.append("==")
            } else if (rem == 2) {
                val n = (bytes[i].toInt() and 0xFF) shl 16 or
                        ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(chars[(n shr 18) and 0x3F])
                out.append(chars[(n shr 12) and 0x3F])
                out.append(chars[(n shr 6) and 0x3F])
                out.append('=')
            }
            return out.toString()
        }
    }
}
