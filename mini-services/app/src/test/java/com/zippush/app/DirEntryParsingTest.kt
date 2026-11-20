package com.zippush.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.zippush.app.net.GitHubClient

/**
 * v0.3.0: parsing of GET /repos/…/contents directory listings
 * (GitHubClient.DirEntry.parseList) — the payload the target-folder
 * Explorer consumes. Same defensive posture as the repo/branch parsers:
 * malformed entries are skipped, never thrown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirEntryParsingTest {

    private fun entry(
        name: Any? = "docs",
        path: Any? = "docs",
        type: Any? = "dir"
    ): JSONObject {
        val o = JSONObject()
        if (name == null) o.put("name", JSONObject.NULL) else o.put("name", name)
        if (path == null) o.put("path", JSONObject.NULL) else o.put("path", path)
        if (type == null) o.put("type", JSONObject.NULL) else o.put("type", type)
        return o
    }

    @Test
    fun `well-formed listing parses with dirs and files`() {
        val arr = JSONArray()
            .put(entry(name = "src", path = "src", type = "dir"))
            .put(entry(name = "README.md", path = "README.md", type = "file"))
            .put(entry(name = "notes", path = "docs/notes", type = "dir"))
        val entries = GitHubClient.DirEntry.parseList(arr)
        assertEquals(3, entries.size)
        assertEquals(2, entries.count { it.isDir })
        assertEquals("docs/notes", entries[2].path)
        assertEquals("notes", entries[2].name)
    }

    @Test
    fun `entry with null or blank name is skipped`() {
        val arr = JSONArray()
            .put(entry(name = null))
            .put(entry(name = ""))
            .put(entry(name = "ok", path = "ok", type = "dir"))
        val entries = GitHubClient.DirEntry.parseList(arr)
        assertEquals(1, entries.size)
        assertEquals("ok", entries[0].name)
    }

    @Test
    fun `missing path falls back to the name`() {
        val arr = JSONArray().put(entry(name = "assets", path = null, type = "dir"))
        val entries = GitHubClient.DirEntry.parseList(arr)
        assertEquals("assets", entries[0].path)
    }

    @Test
    fun `missing or null type reads as file`() {
        val arr = JSONArray()
            .put(entry(name = "a", path = "a", type = null))
            .put(entry(name = "b", path = "b", type = "dir"))
        val entries = GitHubClient.DirEntry.parseList(arr)
        assertTrue(!entries[0].isDir)
        assertTrue(entries[1].isDir)
    }

    @Test
    fun `empty listing parses to empty`() {
        val entries = GitHubClient.DirEntry.parseList(JSONArray())
        assertTrue(entries.isEmpty())
    }
}
