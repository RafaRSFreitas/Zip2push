package com.zippush.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject
import com.zippush.app.net.GitHubClient

/**
 * Tests the defensive JSON handling in GitHubClient.listRepos()/listBranches()
 * against the "deleted repositories on GitHub" scenario: entries missing
 * owner, full_name, name or default_branch, null owners, disabled repos and
 * empty arrays must all degrade to skipped entries — never exceptions.
 *
 * The parsing is extracted into GitHubClient.Repo.parseList / Branch.parseList
 * so it can be tested without the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubClientParsingTest {

    private fun repoJson(
        fullName: Any? = "octocat/hello",
        name: Any? = "hello",
        ownerLogin: Any? = "octocat",
        private: Boolean = false,
        defaultBranch: Any? = "main",
        disabled: Boolean = false
    ): JSONObject {
        val o = JSONObject()
        if (fullName == null) o.put("full_name", JSONObject.NULL) else o.put("full_name", fullName)
        if (name == null) o.put("name", JSONObject.NULL) else o.put("name", name)
        if (ownerLogin == null) o.put("owner", JSONObject.NULL)
        else o.put("owner", JSONObject().put("login", ownerLogin))
        o.put("private", private)
        o.put("disabled", disabled)
        if (defaultBranch == null) o.put("default_branch", JSONObject.NULL)
        else o.put("default_branch", defaultBranch)
        return o
    }

    @Test
    fun `well-formed repo list parses fully`() {
        val arr = JSONArray()
            .put(repoJson())
            .put(repoJson("octocat/priv", "priv", "octocat", private = true))
        val repos = GitHubClient.Repo.parseList(arr)
        assertEquals(2, repos.size)
        assertEquals("octocat/hello", repos[0].fullName)
        assertTrue(repos[1].private)
    }

    @Test
    fun `repo with null owner is skipped`() {
        val arr = JSONArray().put(repoJson(ownerLogin = null)).put(repoJson("octocat/ok", "ok"))
        val repos = GitHubClient.Repo.parseList(arr)
        assertEquals(1, repos.size)
        assertEquals("octocat/ok", repos[0].fullName)
    }

    @Test
    fun `repo with missing full_name or blank name is skipped`() {
        val arr = JSONArray()
            .put(repoJson(fullName = null))
            .put(repoJson(name = ""))
            .put(repoJson("octocat/ok", "ok"))
        val repos = GitHubClient.Repo.parseList(arr)
        assertEquals(1, repos.size)
    }

    @Test
    fun `null default_branch falls back to main`() {
        val arr = JSONArray().put(repoJson(defaultBranch = null))
        val repos = GitHubClient.Repo.parseList(arr)
        assertEquals("main", repos[0].defaultBranch)
    }

    @Test
    fun `disabled repos are filtered out`() {
        val arr = JSONArray()
            .put(repoJson(disabled = true))
            .put(repoJson("octocat/ok", "ok"))
        val repos = GitHubClient.Repo.parseList(arr)
        assertEquals(1, repos.size)
        assertEquals("octocat/ok", repos[0].fullName)
    }

    @Test
    fun `empty repo list (all deleted) parses to empty without error`() {
        val repos = GitHubClient.Repo.parseList(JSONArray())
        assertTrue(repos.isEmpty())
    }

    @Test
    fun `branch entries with blank or null names are skipped`() {
        val arr = JSONArray()
            .put(JSONObject().put("name", JSONObject.NULL))
            .put(JSONObject().put("name", ""))
            .put(JSONObject().put("name", "develop"))
        val branches = GitHubClient.Branch.parseList(arr)
        assertEquals(1, branches.size)
        assertEquals("develop", branches[0].name)
    }
}
