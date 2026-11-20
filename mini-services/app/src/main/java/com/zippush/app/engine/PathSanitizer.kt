package com.zippush.app.engine

/**
 * Central path-safety rules for everything that becomes a repo path
 * (ZIP/tar entry names, folder-walk paths, the user-typed target dir).
 *
 * Threat model: archive entries and user input are untrusted strings.
 * A malicious ZIP can carry "../../.git/config" or absolute paths;
 * a typo in the target dir can carry "../". GitHub treats paths as
 * plain strings, so without this normalization a push could plant
 * files in unexpected locations of the repository.
 */
object PathSanitizer {

    /**
     * Normalizes [raw] into a safe repo-relative path:
     *  - drops leading slashes and drive-letter style prefixes;
     *  - resolves away "." and ".." segments (path traversal) stack-style,
     *    so "a/../b" becomes "b" and "../../x" becomes "x";
     *  - collapses repeated slashes;
     *  - trims whitespace around the whole string and each segment.
     * Returns null when nothing safe remains (blank result, "..", ".", "/").
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw
            .replace('\\', '/')                    // Windows-style separators
            .trim()
            .substringAfter(':')                   // "C:/evil" -> "/evil"
        val stack = ArrayDeque<String>()
        for (seg in cleaned.split('/')) {
            val s = seg.trim()
            when {
                s.isEmpty() || s == "." -> Unit
                s == ".." -> if (stack.isNotEmpty()) stack.removeLast() // pop: "a/.." cancels
                else -> stack.addLast(s)
            }
        }
        if (stack.isEmpty()) return null
        return stack.joinToString("/")
    }

    /**
     * Sanitizes a target directory typed by the user. Same rules as
     * [sanitize]; blank input is legal (means "repo root").
     */
    fun sanitizeDir(raw: String?): String {
        return sanitize(raw) ?: ""
    }

    /**
     * Joins a (already sanitized) target dir and a (already sanitized)
     * file path without producing "//" or "./" artifacts.
     */
    fun join(dir: String, path: String): String {
        val d = dir.trim('/')
        val p = path.trim('/')
        return when {
            d.isBlank() -> p
            p.isBlank() -> d
            else -> "$d/$p"
        }
    }

    /**
     * Makes a name unique among [existing] by appending " (n)" before
     * the extension: "a.txt" -> "a (1).txt". Used when two picked files
     * would otherwise overwrite each other in the repo.
     */
    fun dedupe(name: String, existing: MutableSet<String>): String {
        if (existing.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (existing.add(candidate)) return candidate
            n++
        }
    }
}
