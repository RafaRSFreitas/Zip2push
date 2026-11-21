package com.zippush.app

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure logic tests for the selection model backing Add Rar/Zip/Tar /
 * Add file / Clear selection and the push snapshot.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionTrackerTest {

    private fun uri(s: String) = Uri.parse("content://test/$s")

    @Test
    fun `empty tracker has no selection and clear reports nothing to clear`() {
        val t = SelectionTracker()
        assertFalse(t.hasSelection())
        assertFalse(t.clear())  // nothing was there
        assertFalse(t.hasSelection())
    }

    @Test
    fun `pickZip then clear resets the zip`() {
        val t = SelectionTracker()
        t.pickZip(uri("archive.zip"))
        assertTrue(t.hasSelection())
        assertTrue(t.clear())
        assertEquals(null, t.zip)
        assertFalse(t.hasSelection())
    }

    @Test
    fun `pickFiles replaces the previous file list`() {
        val t = SelectionTracker()
        t.pickFiles(listOf(uri("a.txt"), uri("b.txt")))
        assertEquals(2, t.files.size)
        t.pickFiles(listOf(uri("c.txt")))
        assertEquals(1, t.files.size)
        assertEquals("c.txt", t.files.first().lastPathSegment)
    }

    @Test
    fun `snapshot is immune to later clear`() {
        val t = SelectionTracker()
        t.pickZip(uri("archive.zip"))
        t.pickFiles(listOf(uri("a.txt"), uri("b.txt")))
        val snap = t.snapshot()
        assertEquals(3, snap.itemCount)
        // Clear after snapshotting: the snapshot must be unchanged.
        t.clear()
        assertEquals(3, snap.itemCount)
        assertFalse(snap.isEmpty)
        assertTrue(t.snapshot().isEmpty)
    }

    @Test
    fun `folder files are stored with repo paths and cleared`() {
        val t = SelectionTracker()
        t.pickFilesWithPaths(
            listOf(
                SelectionTracker.FolderFile(uri("tree/1/doc1"), "photos/cat.jpg"),
                SelectionTracker.FolderFile(uri("tree/1/doc2"), "photos/dog.jpg")
            )
        )
        assertEquals(2, t.filesWithPaths.size)
        assertEquals("photos/dog.jpg", t.filesWithPaths[1].repoPath)
        assertTrue(t.hasSelection())
        val snap = t.snapshot()
        assertEquals(2, snap.itemCount)
        assertTrue(t.clear())
        assertEquals(0, t.filesWithPaths.size)
        assertFalse(t.hasSelection())
    }

    @Test
    fun `picking a folder replaces the previous folder selection`() {
        val t = SelectionTracker()
        t.pickFilesWithPaths(
            listOf(SelectionTracker.FolderFile(uri("tree/1/doc1"), "a.txt"))
        )
        t.pickFilesWithPaths(
            listOf(SelectionTracker.FolderFile(uri("tree/2/doc9"), "b.txt"))
        )
        assertEquals(1, t.filesWithPaths.size)
        assertEquals("b.txt", t.filesWithPaths[0].repoPath)
    }

    @Test
    fun `files list is a defensive copy`() {
        val t = SelectionTracker()
        t.pickFiles(listOf(uri("a.txt")))
        val files = t.files
        // Mutating the returned list must not affect the tracker.
        runCatching { (files as? MutableList<Uri>)?.clear() }
        assertEquals(1, t.files.size)
    }
}
