package com.zippush.app.engine

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Archive reading (bug 1.6 / feature 2.8): ZIP (incl. entries that
 * java.util.zip handles poorly), tar and tar.gz all extract through
 * commons-compress, with path traversal neutralized by PathSanitizer.
 */
class ArchiveReaderTest {

    private fun tempFile(name: String): File =
        File.createTempFile(name, null, File("/tmp").apply { mkdirs() })

    private fun writeZip(entries: Map<String, ByteArray>): File {
        val f = tempFile("test")
        ZipArchiveOutputStream(FileOutputStream(f)).use { zos ->
            entries.forEach { (path, bytes) ->
                zos.putArchiveEntry(ZipArchiveEntry(path))
                zos.write(bytes)
                zos.closeArchiveEntry()
            }
        }
        return f
    }

    private fun writeTar(entries: Map<String, ByteArray>, gzip: Boolean): File {
        val f = tempFile("test")
        val raw = FileOutputStream(f)
        val stream = if (gzip) {
            org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(raw)
        } else raw
        TarArchiveOutputStream(stream).use { tos ->
            entries.forEach { (path, bytes) ->
                val e = TarArchiveEntry(path)
                e.size = bytes.size.toLong()
                tos.putArchiveEntry(e)
                tos.write(bytes)
                tos.closeArchiveEntry()
            }
        }
        return f
    }

    @Test
    fun `zip entries extract with contents`() {
        val f = writeZip(
            mapOf(
                "docs/a.txt" to "alpha".toByteArray(),
                "docs/sub/b.txt" to "beta".toByteArray()
            )
        )
        val out = ArchiveReader.extract(f, skipRootFolder = false)
        assertEquals(2, out.size)
        assertEquals("docs/a.txt", out[0].path)
        assertEquals("alpha", String(out[0].bytes))
        assertEquals("docs/sub/b.txt", out[1].path)
    }

    @Test
    fun `tar entries extract`() {
        val f = writeTar(mapOf("x/one.txt" to "ONE".toByteArray()), gzip = false)
        assertEquals(ArchiveReader.Format.TAR, ArchiveReader.detectFormat(f))
        val out = ArchiveReader.extract(f, skipRootFolder = false)
        assertEquals(1, out.size)
        assertEquals("x/one.txt", out[0].path)
        assertEquals("ONE", String(out[0].bytes))
    }

    @Test
    fun `tar_gz entries extract`() {
        val f = writeTar(mapOf("y/two.bin" to byteArrayOf(1, 2, 3)), gzip = true)
        assertEquals(ArchiveReader.Format.TAR_GZ, ArchiveReader.detectFormat(f))
        val out = ArchiveReader.extract(f, skipRootFolder = false)
        assertEquals(1, out.size)
        assertEquals("y/two.bin", out[0].path)
        assertTrue(out[0].bytes.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `zip traversal entries are neutralized`() {
        // The classic malicious archive: entries escaping the target dir.
        val f = writeZip(
            mapOf(
                "../../.git/config" to "evil".toByteArray(),
                "/abs/path.txt" to "evil2".toByteArray(),
                "good.txt" to "ok".toByteArray()
            )
        )
        val out = ArchiveReader.extract(f, skipRootFolder = false)
        // "../.." collapses to nothing -> dropped; "/abs" -> "abs/path.txt"
        // is kept as a RELATIVE path (no leading slash) — safe on GitHub.
        val paths = out.map { it.path }
        assertTrue(paths.contains("good.txt"))
        assertTrue(paths.contains("abs/path.txt"))
        assertTrue(paths.none { it.startsWith("/") })
        assertTrue(paths.none { it.contains("..") })
        // .git/config keeps its name but stays a relative repo path
        assertTrue(paths.any { it.endsWith(".git/config") && !it.contains("..") })
    }

    @Test
    fun `skipRootFolder drops the shared first segment`() {
        val f = writeZip(
            mapOf(
                "myroot/a.txt" to "1".toByteArray(),
                "myroot/sub/b.txt" to "2".toByteArray()
            )
        )
        val out = ArchiveReader.extract(f, skipRootFolder = true)
        assertEquals(listOf("a.txt", "sub/b.txt"), out.map { it.path })
    }

    @Test
    fun `non-archive files are rejected`() {
        val f = tempFile("notanarchive")
        f.writeBytes("just some text, not an archive at all".toByteArray())
        assertEquals(ArchiveReader.Format.UNKNOWN, ArchiveReader.detectFormat(f))
        val ex = runCatching { ArchiveReader.extract(f, skipRootFolder = false) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `legacy java-util-zip zips still read`() {
        // A zip written by the JDK's own ZipOutputStream (what many tools
        // emit) must extract identically.
        val f = tempFile("legacy")
        ZipOutputStream(FileOutputStream(f)).use { zos ->
            zos.putNextEntry(ZipEntry("legacy/hello.txt"))
            zos.write("hi".toByteArray())
            zos.closeEntry()
        }
        val out = ArchiveReader.extract(f, skipRootFolder = true)
        assertEquals(listOf("hello.txt"), out.map { it.path })
        assertEquals("hi", String(out[0].bytes))
    }
}
