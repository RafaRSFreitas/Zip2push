package com.zippush.app.engine

import com.zippush.app.net.GitHubClient
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Turns a set of local files / ZIP archive contents into a single GitHub commit
 * using the Git Data API (blobs -> tree -> commit -> update ref).
 *
 * Flow per push:
 *  1. Resolve target branch head SHA (create the branch if requested).
 *  2. Read the base commit's tree (preserves existing repo files).
 *  3. Create blobs for every file to be pushed.
 *  4. Create a new tree based on the base tree, with our entries overlaid.
 *  5. Create a commit pointing at the new tree with the branch head as parent.
 *  6. Fast-forward the branch ref to the new commit.
 */
class UploadEngine(private val client: GitHubClient) {

    sealed class PushResult {
        data class Success(
            val commitSha: String,
            val commitUrl: String,
            val filesPushed: Int,
            val bytesPushed: Long
        ) : PushResult()

        data class Failure(val stage: String, val message: String) : PushResult()
    }

    /** One file to upload, either from a ZIP entry or a standalone file. */
    data class PendingFile(val path: String, val bytes: ByteArray)

    /** Collects ZIP entries (optionally skipping the archive's root folder). */
    fun extractZipEntries(zipFile: File, skipRootFolder: Boolean): List<PendingFile> {
        val out = mutableListOf<PendingFile>()
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e: ZipEntry = entries.nextElement()
                if (e.isDirectory) continue
                var path = e.name
                if (skipRootFolder) {
                    // Drop the first path segment (the ZIP's root folder).
                    val slash = path.indexOf('/')
                    if (slash >= 0) {
                        path = path.substring(slash + 1)
                    } else {
                        continue // single file directly in the archive root
                    }
                }
                if (path.isBlank()) continue
                zf.getInputStream(e).use { input ->
                    val bytes = input.readBytes()
                    out.add(PendingFile(path, bytes))
                }
            }
        }
        return out
    }

    /** Collects standalone files picked by the user. */
    fun collectFiles(files: List<File>): List<PendingFile> {
        return files.map { PendingFile(it.name, it.readBytes()) }
    }

    /**
     * Pushes [files] to owner/repo at branch, storing them under [targetDir].
     * If [newBranch] is non-null the branch is created from the current head first.
     * If [authorDate] is non-null the commit is retroactive (backdated).
     */
    fun push(
        owner: String,
        repo: String,
        branch: String,
        newBranch: String?,
        targetDir: String,
        commitMessage: String,
        files: List<PendingFile>,
        authorDate: String? = null
    ): PushResult {
        try {
            // -- 1. Resolve branch head ------------------------------------------------
            val targetBranch = newBranch ?: branch
            var headSha: String? = null
            if (newBranch != null) {
                when (val base = client.getBranchHead(owner, repo, branch)) {
                    is GitHubClient.ApiResult.Success -> headSha = base.data
                    is GitHubClient.ApiResult.Error ->
                        return PushResult.Failure("Resolve base branch", base.message)
                }
                when (val created = client.createBranch(owner, repo, newBranch, headSha!!)) {
                    is GitHubClient.ApiResult.Success -> headSha = created.data
                    is GitHubClient.ApiResult.Error ->
                        return PushResult.Failure("Create branch", created.message)
                }
            } else {
                when (val head = client.getBranchHead(owner, repo, branch)) {
                    is GitHubClient.ApiResult.Success -> headSha = head.data
                    is GitHubClient.ApiResult.Error ->
                        return PushResult.Failure("Resolve branch", head.message)
                }
            }

            // -- 2. Base tree (preserve existing files) --------------------------------
            var baseTreeSha: String? = null
            when (val commit = client.getCommit(owner, repo, headSha!!)) {
                is GitHubClient.ApiResult.Success -> baseTreeSha = commit.data
                is GitHubClient.ApiResult.Error ->
                    return PushResult.Failure("Read base commit", commit.message)
            }

            // -- 3. Blobs --------------------------------------------------------------
            val treeEntries = mutableListOf<GitHubClient.TreeEntry>()
            var totalBytes = 0L
            for ((index, f) in files.withIndex()) {
                val blobSha = when (val r = client.createBlob(owner, repo, f.bytes)) {
                    is GitHubClient.ApiResult.Success -> r.data
                    is GitHubClient.ApiResult.Error ->
                        return PushResult.Failure("Upload ${f.path}", r.message)
                }
                val full = if (targetDir.isBlank()) f.path else "${targetDir.trim('/')}/${f.path}"
                treeEntries.add(GitHubClient.TreeEntry(path = full, sha = blobSha))
                totalBytes += f.bytes.size
            }

            // -- 4. New tree -----------------------------------------------------------
            val newTreeSha = when (val r = client.createTree(owner, repo, baseTreeSha, treeEntries)) {
                is GitHubClient.ApiResult.Success -> r.data
                is GitHubClient.ApiResult.Error -> return PushResult.Failure("Create tree", r.message)
            }

            // -- 5. Commit -------------------------------------------------------------
            val commitSha = when (val r = client.createCommit(owner, repo, commitMessage, newTreeSha, listOf(headSha!!), authorDate)) {
                is GitHubClient.ApiResult.Success -> r.data
                is GitHubClient.ApiResult.Error -> return PushResult.Failure("Create commit", r.message)
            }

            // -- 6. Update ref ---------------------------------------------------------
            when (val r = client.updateRef(owner, repo, targetBranch, commitSha)) {
                is GitHubClient.ApiResult.Success -> { /* branch fast-forwarded */ }
                is GitHubClient.ApiResult.Error -> return PushResult.Failure("Update branch", r.message)
            }

            return PushResult.Success(
                commitSha = commitSha,
                commitUrl = "https://github.com/$owner/$repo/commit/$commitSha",
                filesPushed = files.size,
                bytesPushed = totalBytes
            )
        } catch (e: Exception) {
            return PushResult.Failure("Unexpected", e.message ?: "unknown error")
        }
    }
}
