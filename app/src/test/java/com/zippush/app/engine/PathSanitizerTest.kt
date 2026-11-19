package com.zippush.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path-safety rules (bug 1.1 / 1.8): every string that becomes a repo
 * path — archive entry names, folder-walk paths, the typed target dir —
 * goes through PathSanitizer. Traversal (".."), absolute paths and
 * Windows separators must never survive.
 */
class PathSanitizerTest {

    @Test
    fun `normal relative paths pass through unchanged`() {
        assertEquals("docs/week3/hello.txt", PathSanitizer.sanitize("docs/week3/hello.txt"))
        assertEquals("a.txt", PathSanitizer.sanitize("a.txt"))
    }

    @Test
    fun `parent traversal segments are removed`() {
        assertEquals(".git/config", PathSanitizer.sanitize("../../.git/config"))
        assertEquals("safe.txt", PathSanitizer.sanitize("a/../safe.txt"))
        // repeated traversal with nothing left but dots
        assertNull(PathSanitizer.sanitize(".."))
        assertNull(PathSanitizer.sanitize("../.."))
    }

    @Test
    fun `absolute paths are flattened to relative`() {
        assertEquals("etc/passwd", PathSanitizer.sanitize("/etc/passwd"))
        assertEquals("data/bin/tool", PathSanitizer.sanitize("C:\\data\\bin\\tool"))
        assertEquals("x/y", PathSanitizer.sanitize("C:/x/y"))
    }

    @Test
    fun `blank and whitespace-only inputs are rejected`() {
        assertNull(PathSanitizer.sanitize(""))
        assertNull(PathSanitizer.sanitize("   "))
        assertNull(PathSanitizer.sanitize(null))
        assertNull(PathSanitizer.sanitize("/"))
        assertNull(PathSanitizer.sanitize("././."))
    }

    @Test
    fun `repeated slashes and dot segments are collapsed`() {
        assertEquals("a/b/c.txt", PathSanitizer.sanitize("a//b/./c.txt"))
        assertEquals("a b/c", PathSanitizer.sanitize(" a b / c "))
    }

    @Test
    fun `sanitizeDir allows blank but not traversal`() {
        assertEquals("", PathSanitizer.sanitizeDir(""))
        assertEquals("docs", PathSanitizer.sanitizeDir("../docs"))
        assertEquals("docs/sub", PathSanitizer.sanitizeDir("/docs/sub/"))
    }

    @Test
    fun `join never produces double slashes`() {
        assertEquals("docs/a.txt", PathSanitizer.join("docs", "a.txt"))
        assertEquals("docs/a.txt", PathSanitizer.join("docs/", "/a.txt"))
        assertEquals("a.txt", PathSanitizer.join("", "a.txt"))
        assertEquals("docs", PathSanitizer.join("docs", ""))
    }

    @Test
    fun `dedupe appends counter before extension`() {
        val used = mutableSetOf<String>()
        assertEquals("a.txt", PathSanitizer.dedupe("a.txt", used))
        assertEquals("a (1).txt", PathSanitizer.dedupe("a.txt", used))
        assertEquals("a (2).txt", PathSanitizer.dedupe("a.txt", used))
        // no extension
        val used2 = mutableSetOf<String>()
        assertEquals("README", PathSanitizer.dedupe("README", used2))
        assertEquals("README (1)", PathSanitizer.dedupe("README", used2))
    }
}
