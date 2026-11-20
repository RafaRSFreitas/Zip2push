package com.zippush.app

import com.zippush.app.net.GitHubClient

/**
 * Selection state that survives configuration changes (rotation,
 * dark-mode flip) for the lifetime of the app process — "the session".
 *
 * Why not onSaveInstanceState: the repo list reload is asynchronous
 * (token verify -> repo list -> branch list), so the recreated activity
 * restores this state when each response lands, not only from a Bundle.
 * Why a singleton and not persistence: the user asked for "once per
 * session" behaviour — a fresh app start should begin clean, and a
 * logged-in user rotating the device should never lose (or get forced
 * to redo) a selection made seconds ago.
 */
object SessionState {

    /** Repo picked in the dropdown this session, or null. Picking one
     *  marks the session as "already chose" (see [repoPickedThisSession]). */
    @Volatile
    var selectedRepo: GitHubClient.Repo? = null
        set(value) {
            field = value
            if (value != null) repoPickedThisSession = true
        }

    /** Branch picked for [selectedRepo] this session, or null. */
    @Volatile
    var selectedBranch: GitHubClient.Branch? = null

    /**
     * True once the user has picked a repository this session. The repo
     * dropdown auto-opens after the list loads only while this is false
     * — that is what stops the dropdown from popping up again after a
     * rotation: by then the user has already chosen.
     */
    @Volatile
    var repoPickedThisSession: Boolean = false

    /**
     * Target folders the user actually pushed to this session, most
     * recent first (v0.2.9). Feeds the target-folder suggestions; a
     * fresh app start begins clean, like the rest of the session state.
     */
    private val _recentTargetDirs = mutableListOf<String>()

    val recentTargetDirs: List<String> get() = _recentTargetDirs.toList()

    /** Records a used target folder (deduped, newest first, capped). */
    fun rememberTargetDir(dir: String) {
        val d = dir.trim().trimEnd('/')
        if (d.isEmpty()) return
        _recentTargetDirs.removeAll { it == d }
        _recentTargetDirs.add(0, d)
        while (_recentTargetDirs.size > 5) _recentTargetDirs.removeAt(_recentTargetDirs.size - 1)
    }

    /** Clears everything (logout / account switch). */
    fun clear() {
        selectedRepo = null
        selectedBranch = null
        repoPickedThisSession = false
        _recentTargetDirs.clear()
    }

    /** Test seam. */
    fun resetForTest() = clear()
}
