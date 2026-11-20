package com.zippush.app.engine

import com.zippush.app.net.GitHubClient
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.InputStream

/**
 * Reads ZIP and tar archives with Apache Commons Compress.
 *
 * Why not java.util.zip.ZipFile (the old implementation):
 *  - no ZIP64 support, so archives > 4 GB fail;
 *  - non-UTF-8 entry names (zips from legacy Windows tools) come out
 *    mangled or throw;
 *  - no tar / tar.gz at all.
 * Commons Compress handles all of the above in ~200 KB and is also the
 * tar backend (feature 2.8), so both formats share one code path.
 */
object ArchiveReader {

    /** Detects the container format from magic bytes (not the file name). */
    enum class Format { ZIP, TAR, TAR_GZ, UNKNOWN }

    /**
     * One extracted entry: the sanitized repo-relative path and bytes.
     * Entries with unsafe paths (see [PathSanitizer]) are skipped.
     */
    data class ArchiveEntryData(val path: String, val bytes: ByteArray)

    fun detectFormat(file: File): Format = detectFormat(file.inputStream())

    fun detectFormat(input: InputStream): Format = input.use { s ->
        // A tar header is 512 bytes; the magic "ustar" sits at offset 257.
        // ZIP and gzip are identified by their leading magic bytes.
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
        val raw: InputStream = file.inputStream()
        val input: ArchiveInputStream<*> = when (format) {
            Format.ZIP -> ZipArchiveInputStream(raw)
            Format.TAR -> TarArchiveInputStream(raw)
            Format.TAR_GZ -> TarArchiveInputStream(GzipCompressorInputStream(raw))
            Format.UNKNOWN -> throw IllegalArgumentException("Unsupported archive format")
        }
        return input.use { stream ->
            val out = mutableListOf<ArchiveEntryData>()
            var entry: ArchiveEntry? = stream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    var path: String = entry.name
                    if (skipRootFolder) {
                        val slash = path.indexOf('/')
                        if (slash >= 0) path = path.substring(slash + 1) else path = ""
                    }
                    val safe = PathSanitizer.sanitize(path)
                    if (safe != null) {
                        out.add(ArchiveEntryData(safe, stream.readBytes()))
                    }
                }
                entry = stream.nextEntry
            }
            out
        }
    }
}
