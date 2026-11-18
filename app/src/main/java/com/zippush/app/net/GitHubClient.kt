package com.zippush.app.net

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal GitHub REST API v3 client. All requests run on background threads
 * (see GitHubRepository / UploadEngine callers) — this class is synchronous.
 */
class GitHubClient(private val token: String) {

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
    }

    /** A branch entry shown in the branch dropdown. */
    data class Branch(val name: String) {
        override fun toString() = name
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
        conn.requestMethod = method
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
        return when (val r = execute<JSONObject>("https://api.github.com/user", "GET")) {
            is ApiResult.Success -> ApiResult.Success(r.data.getString("login"))
            is ApiResult.Error -> r
        }
    }

    /** GET /user/repos — all repositories the token can push to, newest first. */
    fun listRepos(): ApiResult<List<Repo>> {
        val repos = mutableListOf<Repo>()
        var page = 1
        while (true) {
            val url = "https://api.github.com/user/repos?per_page=100&page=$page" +
                    "&affiliation=owner,collaborator,organization_member&sort=full_name"
            when (val r = execute<JSONArray>(url, "GET")) {
                is ApiResult.Success -> {
                    val arr = r.data
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (!o.optBoolean("disabled", false)) {
                            repos.add(
                                Repo(
                                    fullName = o.getString("full_name"),
                                    name = o.getString("name"),
                                    owner = o.getJSONObject("owner").getString("login"),
                                    private = o.optBoolean("private", false),
                                    defaultBranch = o.optString("default_branch", "main")
                                )
                            )
                        }
                    }
                    if (arr.length() < 100) break
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
        while (true) {
            val url = "https://api.github.com/repos/$owner/$repo/branches?per_page=100&page=$page"
            when (val r = execute<JSONArray>(url, "GET")) {
                is ApiResult.Success -> {
                    val arr = r.data
                    for (i in 0 until arr.length()) {
                        branches.add(Branch(name = arr.getJSONObject(i).getString("name")))
                    }
                    if (arr.length() < 100) break
                    page++
                }
                is ApiResult.Error -> return r
            }
        }
        return ApiResult.Success(branches)
    }

    /** GET /repos/{owner}/{repo}/git/ref/heads/{branch} — commit SHA of a branch head. */
    fun getBranchHead(owner: String, repo: String, branch: String): ApiResult<String> {
        val url = "https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch"
        return when (val r = execute<JSONObject>(url, "GET")) {
            is ApiResult.Success -> ApiResult.Success(r.data.getJSONObject("object").getString("sha"))
            is ApiResult.Error -> r
        }
    }

    /** POST /repos/{owner}/{repo}/git/refs — creates a new branch from a base SHA. */
    fun createBranch(owner: String, repo: String, branch: String, fromSha: String): ApiResult<String> {
        val body = JSONObject().put("ref", "refs/heads/$branch").put("sha", fromSha)
        return when (val r = execute<JSONObject>(
            "https://api.github.com/repos/$owner/$repo/git/refs", "POST", body.toString()
        )) {
            is ApiResult.Success -> ApiResult.Success(r.data.getJSONObject("object").getString("sha"))
            is ApiResult.Error -> r
        }
    }

    /** GET /repos/{owner}/{repo}/git/commits/{sha} — returns the commit's tree SHA. */
    fun getCommit(owner: String, repo: String, sha: String): ApiResult<String> {
        val url = "https://api.github.com/repos/$owner/$repo/git/commits/$sha"
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
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("encoding", "base64")
        return when (val r = execute<JSONObject>(
            "https://api.github.com/repos/$owner/$repo/git/blobs", "POST", body.toString()
        )) {
            is ApiResult.Success -> ApiResult.Success(r.data.getString("sha"))
            is ApiResult.Error -> r
        }
    }

    /** GET /repos/{owner}/{repo}/git/trees/{treeSha}?recursive=1 */
    fun getTree(owner: String, repo: String, treeSha: String): ApiResult<JSONObject> {
        val url = "https://api.github.com/repos/$owner/$repo/git/trees/$treeSha?recursive=1"
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
            "https://api.github.com/repos/$owner/$repo/git/trees", "POST", body.toString()
        )) {
            is ApiResult.Success -> ApiResult.Success(r.data.getString("sha"))
            is ApiResult.Error -> r
        }
    }

    /** POST /repos/{owner}/{repo}/git/commits */
    fun createCommit(
        owner: String,
        repo: String,
        message: String,
        treeSha: String,
        parentShas: List<String>
    ): ApiResult<String> {
        val parents = JSONArray()
        parentShas.forEach { parents.put(it) }
        val body = JSONObject()
            .put("message", message)
            .put("tree", treeSha)
            .put("parents", parents)
        return when (val r = execute<JSONObject>(
            "https://api.github.com/repos/$owner/$repo/git/commits", "POST", body.toString()
        )) {
            is ApiResult.Success -> ApiResult.Success(r.data.getString("sha"))
            is ApiResult.Error -> r
        }
    }

    /** PATCH /repos/{owner}/{repo}/git/refs/heads/{branch} — fast-forwards the branch. */
    fun updateRef(owner: String, repo: String, branch: String, sha: String): ApiResult<Unit> {
        val body = JSONObject().put("sha", sha)
        return execute(
            "https://api.github.com/repos/$owner/$repo/git/refs/heads/$branch", "PATCH", body.toString()
        )
    }

    /** Tree entry used when building the new commit tree. */
    data class TreeEntry(val path: String, val sha: String, val mode: String = "100644")
}
