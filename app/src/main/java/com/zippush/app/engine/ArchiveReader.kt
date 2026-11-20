package com.zippush.app.engine

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.zippush.app.net.GitHubClient
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Reads ZIP, tar, tar.gz and RAR archives.
 *
 * Why not java.util.zip.ZipFile (the old implementation):
 *  - no ZIP64 support, so archives > 4 GB fail;
 *  - non-UTF-8 entry names (zips from legacy Windows tools) come out
 *    mangled or throw;
 *  - no tar / tar.gz at all.
 * Commons Compress handles all of the above in ~200 KB and is also the
 * tar backend (feature 2.8), so those formats share one code path.
 *
 * RAR (feature 3.1) goes through junrar (pure Java, RAR4 through v7,
 * https://github.com/junrar/junrar) — its streaming API differs from
 * commons-compress, hence a dedicated extraction branch below.
 */
object ArchiveReader {

    /** Detects the container format from magic bytes (not the file name). */
    enum class Format { ZIP, TAR, TAR_GZ, RAR4, RAR5, UNKNOWN }

    /**
     * One extracted entry: the sanitized repo-relative path and bytes.
     * Entries with unsafe paths (see [PathSanitizer]) are skipped.
     */
    data class ArchiveEntryData(val path: String, val bytes: ByteArray)

    fun detectFormat(file: File): Format = detectFormat(file.inputStream())

    fun detectFormat(input: InputStream): Format = input.use { s ->
        // A tar header is 512 bytes; the magic "ustar" sits at offset 257.
        // ZIP, gzip and RAR are identified by their leading magic bytes.
        val head = ByteArray(262)
        var read = 0
        while (read < head.size) {
            val n = s.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        if (read < 4) return Format.UNKNOWN
        // gzip magic: 1f 8b
        if (head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) return Format.TAR_GZ
        // "PK\x03\x04" (and friends) = zip
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return Format.ZIP
        // RAR: "Rar!\x1a" followed by version bytes 00 (RAR4) or 01 00 (RAR5+).
        if (head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() &&
            head[2] == 'r'.code.toByte() && head[3] == '!'.code.toByte() &&
            head[4] == 0x1a.toByte() && head[5] == 0x07.toByte() && read >= 7
        ) {
            return if (head[6] == 0x00.toByte()) Format.RAR4 else Format.RAR5
        }
        // tar (POSIX / GNU / v7 with magic): "ustar" at 257..261
        if (read >= 262 &&
            head[257] == 'u'.code.toByte() && head[258] == 's'.code.toByte() &&
            head[259] == 't'.code.toByte() && head[260] == 'a'.code.toByte() &&
            head[261] == 'r'.code.toByte()
        ) return Format.TAR
        Format.UNKNOWN
    }

    /**
     * Extracts every regular-file entry into path+bytes. [skipRootFolder]
     * drops the archive's single root folder (the common "folder.zip"
     * case), matching the previous ZipFile behaviour. Directory entries
     * and unsafe paths are skipped.
     */
    fun extract(file: File, skipRootFolder: Boolean): List<ArchiveEntryData> {
        val format = detectFormat(file)
        if (format == Format.RAR4 || format == Format.RAR5) {
            return extractRar(file, skipRootFolder)
        }
        val raw: InputStream = file.inputStream()
        val input: ArchiveInputStream<*> = when (format) {
            Format.ZIP -> ZipArchiveInputStream(raw)
            Format.TAR -> TarArchiveInputStream(raw)
            Format.TAR_GZ -> TarArchiveInputStream(GzipCompressorInputStream(raw))
            Format.UNKNOWN -> throw IllegalArgumentException("Unsupported archive format")
            else -> throw IllegalArgumentException("Unsupported archive format")
        }
        return input.use { stream ->
            val out = mutableListOf<ArchiveEntryData>()
            var entry: ArchiveEntry? = stream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    addEntry(out, entry.name, skipRootFolder) { stream.readBytes() }
                }
                entry = stream.nextEntry
            }
            out
        }
    }

    /**
     * Shared per-entry rules for [extract]: apply [skipRootFolder],
     * sanitize, and append when anything safe remains.
     *
     * v0.3.4, three rules (a "flat" archive with no folders must keep
     * its root-level files — the old `else path = ""` dropped them, so
     * a flat ZIP/RAR pushed NOTHING):
     *  - all entries share one root folder -> drop that folder;
     *  - entries at the archive's top level -> keep as-is;
     *  - RAR names may use '\' (Windows separators) -> normalize first.
     */
    private fun addEntry(
        out: MutableList<ArchiveEntryData>,
        rawPath: String,
        skipRootFolder: Boolean,
        readBytes: () -> ByteArray
    ) {
        var path = rawPath.replace('\\', '/')
        if (skipRootFolder) {
            val slash = path.indexOf('/')
            // No '/' at all: a root-level file — keep it (old code
            // blanked it, silently dropping flat archives' files).
            if (slash >= 0) path = path.substring(slash + 1)
        }
        val safe = PathSanitizer.sanitize(path)
        if (safe != null) {
            out.add(ArchiveEntryData(safe, readBytes()))
        }
    }

    /**
     * The RAR branch (feature 3.1): junrar's Archive walks file headers
     * instead of a commons-compress stream. A header that cannot be
     * parsed (corrupt or crafted — see the junrar corpus) skips THAT
     * entry; a password-protected or fully corrupt archive surfaces as
     * an exception, so the push fails with a readable reason instead
     * of crashing.
     */
    private fun extractRar(file: File, skipRootFolder: Boolean): List<ArchiveEntryData> {
        val out = mutableListOf<ArchiveEntryData>()
        Archive(file).use { archive ->
            var header: FileHeader? = archive.nextFileHeader()
            while (header != null) {
                val h = header
                if (!h.isDirectory) {
                    // One hostile entry must not kill the whole push:
                    // try to read it, skip on failure.
                    runCatching {
                        addEntry(out, h.fileName, skipRootFolder) {
                            val bos = ByteArrayOutputStream()
                            archive.extractFile(h, bos)
                            bos.toByteArray()
                        }
                    }
                }
                header = archive.nextFileHeader()
            }
        }
        return out
    }
}
