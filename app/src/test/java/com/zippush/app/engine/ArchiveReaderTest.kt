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

    // ------------------------------------------------------------- RAR (3.1)
    // Real archives from the junrar test corpus (Apache-2.0 repo,
    // https://github.com/junrar/junrar): no pure-Java RAR writer exists,
    // so extraction is verified against genuine RAR4/RAR5 bytes.

    private fun fixture(name: String): File =
        java.io.File(
            javaClass.classLoader.getResource("archives/$name")!!.toURI()
        )

    @Test
    fun `rar4 magic bytes detect as RAR4`() {
        assertEquals(ArchiveReader.Format.RAR4, ArchiveReader.detectFormat(fixture("rar4.rar")))
    }

    @Test
    fun `rar5 magic bytes detect as RAR5`() {
        assertEquals(ArchiveReader.Format.RAR5, ArchiveReader.detectFormat(fixture("rar5.rar")))
    }

    @Test
    fun `rar4 entries extract with contents`() {
        // junrar's own corpus: rar4.rar carries FILE1.TXT and FILE2.TXT
        // ("file1\n" / "file2\n") — verify names AND bytes exactly.
        val out = ArchiveReader.extract(fixture("rar4.rar"), skipRootFolder = false)
        assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), out.map { it.path })
        assertEquals("file1", String(out[0].bytes).trim())
        assertEquals("file2", String(out[1].bytes).trim())
    }

    @Test
    fun `rar5 entries extract with contents`() {
        // Same corpus in RAR5 format — both generations must work.
        val out = ArchiveReader.extract(fixture("rar5.rar"), skipRootFolder = false)
        assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), out.map { it.path })
        assertEquals("file1", String(out[0].bytes).trim())
        assertEquals("file2", String(out[1].bytes).trim())
    }

    @Test
    fun `rar traversal entries are neutralized`() {
        // Three crafted archives from the junrar corpus with hostile
        // entry paths. Whatever comes out must NEVER contain a
        // traversal (".."), an absolute path, or a Windows drive —
        // hostile entries either sanitize to something safe inside the
        // repo or are skipped entirely (their headers are also broken).
        listOf("mkdir-escape.rar", "parent-dir.rar", "sibling-prefix-traversal.rar")
            .forEach { name ->
                val out = ArchiveReader.extract(fixture(name), skipRootFolder = false)
                out.forEach { e ->
                    assertTrue("$name: no traversal: ${e.path}", !e.path.contains(".."))
                    assertTrue("$name: relative: ${e.path}", !e.path.startsWith("/"))
                    assertTrue("$name: no drive: ${e.path}", !e.path.contains(':'))
                    assertTrue("$name: no backslash: ${e.path}", !e.path.contains('\\'))
                }
            }
    }

    @Test
    fun `rar4 with unicode names extracts`() {
        // unicode.rar carries Japanese and Chinese entry names with
        // matching contents — names must survive intact.
        val out = ArchiveReader.extract(fixture("unicode.rar"), skipRootFolder = false)
        assertEquals(2, out.size)
        val paths = out.map { it.path }
        assertTrue(paths.contains("ウニコド.txt"))
        assertTrue(paths.contains("新建文本文档.txt"))
        val uni = out.first { it.path == "ウニコド.txt" }
        assertEquals("このファイルにはUnicodeテキストが含まれています", String(uni.bytes))
    }

    @Test
    fun `corrupt rar fails as exception not crash`() {
        // RAR magic followed by garbage: detection says RAR4 but the
        // body cannot be parsed — must surface as an exception, not
        // corrupt data or an infinite loop.
        val f = tempFile("corrupt")
        f.writeBytes(
            byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00) +
                "garbage that is not a rar body".toByteArray()
        )
        assertEquals(ArchiveReader.Format.RAR4, ArchiveReader.detectFormat(f))
        val ex = runCatching { ArchiveReader.extract(f, skipRootFolder = false) }.exceptionOrNull()
        assertTrue(ex is Exception)
    }

    @Test
    fun `rar4 detection works on a stream`() {
        java.io.FileInputStream(fixture("rar4.rar")).use { s ->
            assertEquals(ArchiveReader.Format.RAR4, ArchiveReader.detectFormat(s))
        }
    }

    @Test
    fun `skipRootFolder applies to rar too`() {
        // test.rar (junrar corpus): entry "foo\bar.txt" under dir "foo".
        // With skipRootFolder, the root folder is dropped: "bar.txt".
        val out = ArchiveReader.extract(fixture("test.rar"), skipRootFolder = true)
        assertEquals(listOf("bar.txt"), out.map { it.path })
    }
}
