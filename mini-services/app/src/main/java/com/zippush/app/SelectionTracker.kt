package com.zippush.app

import android.net.Uri

/**
 * Holds the user's current file selection (a ZIP, individual files, and/or
 * the recursive contents of a picked folder). Kept apart from the activity
 * so reset and snapshot behaviour can be unit-tested without Android UI.
 */
class SelectionTracker {

    /** A file picked as part of a folder, with its repo-relative path. */
    data class FolderFile(val uri: Uri, val repoPath: String)

    /** Immutable copy of the selection at a point in time (used by a push). */
    data class Snapshot(
        val zip: Uri?,
        val files: List<Uri>,
        val folderFiles: List<FolderFile>
    ) {
        val isEmpty: Boolean get() = zip == null && files.isEmpty() && folderFiles.isEmpty()
        val itemCount: Int get() = files.size + folderFiles.size + if (zip != null) 1 else 0
    }

    var zip: Uri? = null
        private set

    private val _files = mutableListOf<Uri>()

    /** Current individual files (defensive copy). */
    val files: List<Uri> get() = _files.toList()

    private val _folderFiles = mutableListOf<FolderFile>()

    /** Current folder files (defensive copy). */
    val filesWithPaths: List<FolderFile> get() = _folderFiles.toList()

    fun pickZip(uri: Uri?) {
        zip = uri
    }

    /** Replaces the individual-files selection (document picker contract). */
    fun pickFiles(uris: List<Uri>) {
        _files.clear()
        _files.addAll(uris)
    }

    /** Replaces the folder selection (recursive walk output). */
    fun pickFilesWithPaths(files: List<FolderFile>) {
        _folderFiles.clear()
        _folderFiles.addAll(files)
    }

    /** True when a push is possible (a ZIP, files or folder files). */
    fun hasSelection(): Boolean = zip != null || _files.isNotEmpty() || _folderFiles.isNotEmpty()

    /**
     * Immutable copy for an in-flight push. Taken the moment the user taps
     * Push, so clearing (or re-picking) afterwards cannot corrupt the upload.
     */
    fun snapshot(): Snapshot = Snapshot(zip, _files.toList(), _folderFiles.toList())

    /** Resets ZIP, files and folder files. Returns true if anything was cleared. */
    fun clear(): Boolean {
        val hadSelection = hasSelection()
        zip = null
        _files.clear()
        _folderFiles.clear()
        return hadSelection
    }
}
