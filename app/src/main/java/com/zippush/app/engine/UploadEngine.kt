package com.zippush.app.engine

import com.zippush.app.net.GitHubClient
import java.io.File

/**
 * Turns a set of local files / archive (ZIP, tar, tar.gz) contents into a
 * single GitHub commit using the Git Data API (blobs -> tree -> commit ->
 * update ref).
 *
 * Flow per push:
 *  1. Resolve target branch head SHA (create the branch if requested).
 *  2. Read the base commit's tree (preserves existing repo files).
 *  3. Create blobs for every file to be pushed.
 *  4. Create a new tree based on the base tree, with our entries overlaid.
 *  5. Create a commit pointing at the new tree with the branch head as parent.
 *  6. Fast-forward the branch ref to the new commit.
 *
 * Hardening (v0.3.0):
 *  - every path (archive entries, [collectFiles] names, [targetDir]) is
 *    sanitized through [PathSanitizer] — path traversal like "../" or
 *    absolute paths can no longer plant files outside the intended dir;
 *  - duplicate file names are de-duplicated instead of silently
 *    overwriting each other;
 *  - an empty [files] list fails fast instead of creating an empty commit;
 *  - a blank [newBranch] is treated as null (no invalid branch name sent);
 *  - if the branch moved between the head read and the ref update (someone
 *    pushed concurrently), the commit is re-created once on top of the new
 *    head (simple rebase) before giving up.
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

    /** One file to upload, either from an archive entry or a standalone file. */
    data class PendingFile(val path: String, val bytes: ByteArray)

    /**
     * Extracts archive entries (optionally skipping the archive's root
     * folder). ZIP, tar and tar.gz are all supported; unsafe entry paths
     * are dropped by [PathSanitizer].
     */
    fun extractZipEntries(zipFile: File, skipRootFolder: Boolean): List<PendingFile> {
        return ArchiveReader.extract(zipFile, skipRootFolder)
            .map { PendingFile(it.path, it.bytes) }
    }

    /**
     * Collects standalone files picked by the user. Two files with the
     * same display name no longer overwrite each other: the second one
     * becomes "name (1).ext", the third "name (2).ext", and so on.
     */
    fun collectFiles(files: List<File>): List<PendingFile> {
        val used = mutableSetOf<String>()
        return files.map { f ->
            val safe = PathSanitizer.sanitize(f.name) ?: "file_${used.size + 1}"
            PendingFile(PathSanitizer.dedupe(safe, used), f.readBytes())
        }
    }

    /**
     * Same as [collectFiles] for display names coming from the document
     * picker (names may repeat across different folders).
     */
    fun collectNamedFiles(names: List<Pair<String, ByteArray>>): List<PendingFile> {
        val used = mutableSetOf<String>()
        return names.map { (name, bytes) ->
            val safe = PathSanitizer.sanitize(name) ?: "file_${used.size + 1}"
            PendingFile(PathSanitizer.dedupe(safe, used), bytes)
        }
    }

    /**
     * Pushes [files] to owner/repo at branch, storing them under [targetDir].
     * If [newBranch] is non-null (and not blank) the branch is created from
     * the current head first. If [authorDate] is non-null the commit is
     * retroactive (backdated); [authorLogin] fills the required author
     * name/email fields.
     */
    fun push(
        owner: String,
        repo: String,
        branch: String,
        newBranch: String?,
        targetDir: String,
        commitMessage: String,
        files: List<PendingFile>,
        authorDate: String? = null,
        authorLogin: String? = null
    ): PushResult {
        try {
            // Bug 1.9: an empty push would create an empty commit and
            // confuse the user — fail fast with a clear message instead.
            if (files.isEmpty()) {
                return PushResult.Failure("Prepare", "No files to push")
            }

            // Bug 1.4: a blank newBranch (empty UI field) must behave as
            // "no new branch", not as an invalid branch name.
            val targetNewBranch = newBranch?.takeIf { it.isNotBlank() }
            val targetBranch = targetNewBranch ?: branch

            // Bug 1.8: the target dir is user input — sanitize before use.
            val safeDir = PathSanitizer.sanitizeDir(targetDir)

            // -- 1. Resolve branch head ------------------------------------------------
            var headSha: String? = null
            if (targetNewBranch != null) {
                when (val base = client.getBranchHead(owner, repo, branch)) {
                    is GitHubClient.ApiResult.Success -> headSha = base.data
                    is GitHubClient.ApiResult.Error ->
                        return PushResult.Failure("Resolve base branch", base.message)
                }
                when (val created = client.createBranch(owner, repo, targetNewBranch, headSha!!)) {
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

            // -- 3. Blobs + sanitized tree paths ---------------------------------------
            // Bug 1.1: entry paths are untrusted; sanitize each one and
            // skip anything unsafe. (PendingFile paths are also sanitized
            // at extraction time — this is defence in depth for the push
            // boundary itself.)
            val treeEntries = mutableListOf<GitHubClient.TreeEntry>()
            var totalBytes = 0L
            val usedPaths = mutableSetOf<String>()
            for (f in files) {
                val safePath = PathSanitizer.sanitize(f.path) ?: continue
                val full = PathSanitizer.dedupe(PathSanitizer.join(safeDir, safePath), usedPaths)
                val blobSha = when (val r = client.createBlob(owner, repo, f.bytes)) {
                    is GitHubClient.ApiResult.Success -> r.data
                    is GitHubClient.ApiResult.Error ->
                        return PushResult.Failure("Upload $safePath", r.message)
                }
                treeEntries.add(GitHubClient.TreeEntry(path = full, sha = blobSha))
                totalBytes += f.bytes.size
            }
            if (treeEntries.isEmpty()) {
                return PushResult.Failure("Prepare", "No safe file paths to push")
            }

            // -- 4..6: tree, commit, ref — with one conflict-retry pass ----------------
            var head = headSha!!
            repeat(CONFLICT_RETRIES + 1) { attempt ->
                val newTreeSha = when (val r = client.createTree(owner, repo, baseTreeSha, treeEntries)) {
                    is GitHubClient.ApiResult.Success -> r.data
                    is GitHubClient.ApiResult.Error ->
                        return PushResult.Failure("Create tree", r.message)
                }

                val commitSha = when (val r = client.createCommit(
                    owner, repo, commitMessage, newTreeSha, listOf(head),
                    authorDate, authorLogin, authorLogin?.let { "$it@users.noreply.github.com" }
                )) {
                    is GitHubClient.ApiResult.Success -> r.data
                    is GitHubClient.ApiResult.Error ->
                        return PushResult.Failure("Create commit", r.message)
                }

                when (val r = client.updateRef(owner, repo, targetBranch, commitSha)) {
                    is GitHubClient.ApiResult.Success -> {
                        return PushResult.Success(
                            commitSha = commitSha,
                            commitUrl = "https://github.com/$owner/$repo/commit/$commitSha",
                            filesPushed = treeEntries.size,
                            bytesPushed = totalBytes
                        )
                    }
                    is GitHubClient.ApiResult.Error -> {
                        // Feature 2.9: the branch moved since we read its
                        // head (concurrent push). Re-read the head, rebuild
                        // the commit on top of it and try once more. Blobs
                        // and the tree are content-addressed and immutable,
                        // so nothing is re-uploaded.
                        if (attempt < CONFLICT_RETRIES && r.code == 422) {
                            when (val fresh = client.getBranchHead(owner, repo, targetBranch)) {
                                is GitHubClient.ApiResult.Success -> {
                                    head = fresh.data
                                    // The new head's tree becomes the base so
                                    // the concurrent commit's files survive.
                                    when (val base = client.getCommit(owner, repo, fresh.data)) {
                                        is GitHubClient.ApiResult.Success -> baseTreeSha = base.data
                                        is GitHubClient.ApiResult.Error ->
                                            return PushResult.Failure("Update branch", r.message)
                                    }
                                }
                                is GitHubClient.ApiResult.Error ->
                                    return PushResult.Failure("Update branch", r.message)
                            }
                        } else {
                            return PushResult.Failure("Update branch", r.message)
                        }
                    }
                }
            }
            return PushResult.Failure("Update branch", "branch moved repeatedly — try again")
        } catch (e: Exception) {
            return PushResult.Failure("Unexpected", e.message ?: "unknown error")
        }
    }

    companion object {
        /** How many times a moved-branch conflict is retried (simple rebase). */
        private const val CONFLICT_RETRIES = 1
    }
}
