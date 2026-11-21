package com.zippush.app.browser

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.zippush.app.R
import com.zippush.app.net.GitHubClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The target-folder Explorer (v0.3.0; full-screen since v0.4.0) — a
 * full-screen picker that browses the REAL folders of the selected
 * repository/branch, GitHub-style:
 *
 *  - fixed top bar: close (X) + "Choose target folder" + the
 *    owner/repo · branch subtitle;
 *  - breadcrumb bar ("repo / src / android") — tap any crumb to jump;
 *  - one-line list of the subfolders of the current folder (files are
 *    filtered out: this picker picks a destination, not a file);
 *  - "New folder" row to create a folder name inline (no commit is
 *    made — folders materialize on GitHub when the first file lands
 *    under them);
 *  - "Use this folder" confirms and "…" keeps browsing.
 *
 * Navigation model: [navigateTo] fetches one directory page per hop
 * (GET /repos/…/contents?ref=…), exactly what GitHub's own file browser
 * does — no full recursive tree is downloaded for a repo the user just
 * wants to drop files into. The up affordance is the "repo" crumb (and
 * the system back gesture, which closes the picker).
 *
 * UI patterns follow the Explorer/breadcrumb conventions: current
 * location always visible (NN/g breadcrumbs guideline), single-tap to
 * open a folder, dedicated select action (Material list-detail /
 * picker guidance).
 */
class FolderBrowser(
    private val context: Context,
    private val client: GitHubClient,
    private val scope: CoroutineScope
) {

    /** Result handed back through [show]. */
    sealed class Result {
        /** The user confirmed a folder ("" = repository root). */
        data class Selected(val path: String) : Result()
        /** The user dismissed the picker without choosing. */
        object Cancelled : Result()
    }

    /** One row in the list: a real repo folder or the new-folder action. */
    private sealed class Row {
        open class Dir(val name: String, val path: String) : Row() {
            override fun toString() = name
        }
        object NewFolder : Row() {
            override fun toString() = ""
        }
    }

    private var dialog: android.app.Dialog? = null
    private var loadJob: Job? = null

    /** Path of the folder currently displayed ("" = repo root). */
    private var currentPath: String = ""

    /**
     * v0.4.0: true when [currentPath] does NOT exist on GitHub yet (a
     * folder created inline via "New folder", or restored as local after
     * a rotation). A local path must never be listed — the fetch would
     * 404 — so reopens restore it without any network call.
     */
    private var currentPathIsLocal = false

    /**
     * v0.4.0: exactly-one-result guard. The dismiss listener delivers
     * [Result.Cancelled] and the choose button delivers
     * [Result.Selected]; dismissing right after choosing must NOT fire a
     * second (wrong) result. [deliver] passes the first result through
     * and ignores everything after it.
     */
    private var resultCallback: ((Result) -> Unit)? = null
    private var resultDelivered = false

    private fun deliver(result: Result) {
        if (resultDelivered) return
        resultDelivered = true
        resultCallback?.invoke(result)
        resultCallback = null
    }

    /** Rows currently displayed: the repo's subfolders + the new-folder action. */
    private val rows = mutableListOf<Row>()

    private lateinit var breadcrumbContainer: LinearLayout
    private lateinit var upBtn: ImageButton
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loading: CircularProgressIndicator
    private lateinit var errorView: TextView
    private lateinit var chooseBtn: MaterialButton
    private lateinit var adapter: RowAdapter

    /** True while the current folder listing is being fetched. */
    private var loadingFolder = false

    // ------------------------------------------------------------- lifecycle

    /**
     * Opens the full-screen picker at [startPath] (repository root when
     * blank) and reports the outcome to [onResult] on the caller's thread
     * (the scope's). Re-opens are safe: a previous picker is dismissed
     * first. With [startPathIsLocal] the start path is restored LOCALLY
     * (no network call) — used after a rotation to come back to a folder
     * created inline that GitHub does not know about yet.
     */
    fun show(
        repo: GitHubClient.Repo,
        branch: String,
        startPath: String,
        startPathIsLocal: Boolean = false,
        onResult: (Result) -> Unit
    ) {
        dialog?.dismiss()
        resultDelivered = false
        resultCallback = onResult
        currentRepoName = repo.name
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_folder_browser, null)
        breadcrumbContainer = view.findViewById(R.id.browserBreadcrumbs)
        upBtn = view.findViewById(R.id.browserUpBtn)
        list = view.findViewById(R.id.browserList)
        emptyView = view.findViewById(R.id.browserEmpty)
        loading = view.findViewById(R.id.browserLoading)
        errorView = view.findViewById(R.id.browserError)
        chooseBtn = view.findViewById(R.id.browserChooseBtn)

        adapter = RowAdapter()
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        wireNavigation(repo, branch)

        // v0.3.1: one tap returns to the parent folder (the Explorer
        // affordance the breadcrumbs alone did not give). At the repo
        // root there is no parent, so the button disables itself.
        upBtn.setOnClickListener { goUp() }

        // v0.4.0: the fixed top bar — X closes without touching the
        // field; the subtitle names owner/repo · branch.
        val closeBtn = view.findViewById<ImageButton>(R.id.browserCloseBtn)
        view.findViewById<TextView>(R.id.browserSubtitle).text =
            context.getString(R.string.browser_subtitle_repo, repo.fullName, branch)

        chooseBtn.setOnClickListener {
            deliver(Result.Selected(currentPath))
            dialog?.dismiss()
        }

        // v0.4.0: an android.app.Dialog styled by Theme.ZipPush.FullScreenDialog
        // (non-floating, no dim, app theme) — the native-feeling full-screen
        // picker, NOT a MaterialAlertDialog card.
        val d = Dialog(context, R.style.Theme_ZipPush_FullScreenDialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(view)
        d.setCancelable(true)
        d.setCanceledOnTouchOutside(false)
        d.setOnDismissListener {
            // Back, the X and programmatic dismissal all land here: stop
            // the in-flight listing and report the cancellation ONCE
            // (deliver() also suppresses the double report when the user
            // had just confirmed a folder).
            loadJob?.cancel()
            deliver(Result.Cancelled)
        }
        d.show()
        // Window tuning comes after show() (the window object is created
        // by show). setBackgroundDrawable is redundant with the theme but
        // guarantees no rounded dialog backdrop sneaks in.
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(context.getColor(R.color.jw_background)))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setGravity(Gravity.FILL)
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog = d
        closeBtn.setOnClickListener { d.dismiss() }

        if (startPathIsLocal && startPath.isNotBlank()) {
            // Restore a LOCAL path (folder created inline, not on GitHub
            // yet) without any fetch: a listing would 404. Same state the
            // user left: path in the breadcrumbs, "New folder…" row,
            // confirm enabled.
            currentPath = startPath.trim().trim('/').replace(Regex("/+"), "/")
            currentPathIsLocal = true
            currentRepoName = repo.name
            renderBreadcrumbs(repo.name)
            refreshUpButton()
            setLoading(false)
            rows.clear()
            rows.add(Row.NewFolder)
            renderList()
        } else {
            currentPathIsLocal = false
            navigateTo(repo, branch, startPath)
        }
    }

    /** Dismisses the picker programmatically (e.g. activity destroyed). */
    fun dismiss() {
        dialog?.dismiss()
    }

    // -------------------------------------------------------------- loading

    /**
     * Fetches the listing of [path] and renders it. The in-flight job is
     * cancelled first so a slow response can never overwrite a folder
     * the user has already navigated into.
     */
    private fun navigateTo(repo: GitHubClient.Repo, branch: String, path: String) {
        loadJob?.cancel()
        val clean = path.trim().trim('/').replace(Regex("/+"), "/")
        currentPath = clean
        // Navigation always targets a REAL GitHub folder — clear the
        // local flag set by a previous inline folder creation.
        currentPathIsLocal = false
        currentRepoName = repo.name
        renderBreadcrumbs(repo.name)
        refreshUpButton()
        setLoading(true)
        adapter.notifyDataSetChanged() // rows will be replaced
        rows.clear()
        loadJob = scope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.listDir(repo.owner, repo.name, clean, branch)
            }
            setLoading(false)
            when (result) {
                is GitHubClient.ApiResult.Success -> {
                    rows.clear()
                    result.data.filter { it.isDir }
                        .sortedBy { it.name.lowercase() }
                        .forEach { rows.add(Row.Dir(it.name, it.path)) }
                    rows.add(Row.NewFolder)
                    renderList()
                }
                is GitHubClient.ApiResult.Error -> {
                    errorView.text = context.getString(
                        R.string.browser_load_error, result.message
                    )
                    showError(true)
                }
            }
        }
    }

    // -------------------------------------------------------------- render

    private fun setLoading(on: Boolean) {
        loadingFolder = on
        loading.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            list.visibility = View.GONE
            emptyView.visibility = View.GONE
            errorView.visibility = View.GONE
        }
        chooseBtn.isEnabled = !on
    }

    private fun showError(on: Boolean) {
        errorView.visibility = if (on) View.VISIBLE else View.GONE
        list.visibility = if (on) View.GONE else View.VISIBLE
        if (on) emptyView.visibility = View.GONE
    }

    private fun renderList() {
        showError(false)
        // The action row is always present; an empty listing means
        // "no subfolders", not "nothing to show".
        emptyView.visibility = if (rows.size <= 1) View.VISIBLE else View.GONE
        list.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    /**
     * Renders the breadcrumb trail: repo name / folder / subfolder.
     * Every crumb is tappable (jumps straight to that folder) — the
     * classic Explorer wayfinding affordance.
     */
    private fun renderBreadcrumbs(repoName: String) {
        breadcrumbContainer.removeAllViews()
        val crumbs = buildList {
            add(Crumb(repoName, ""))
            if (currentPath.isNotEmpty()) {
                var acc = ""
                currentPath.split('/').forEach { part ->
                    acc = if (acc.isEmpty()) part else "$acc/$part"
                    add(Crumb(part, acc))
                }
            }
        }
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) addCrumbSeparator()
            addCrumbView(crumb, isLast = index == crumbs.lastIndex)
        }
    }

    private data class Crumb(val label: String, val path: String)

    private fun addCrumbSeparator() {
        val tv = TextView(context)
        tv.text = "/"
        tv.setTextAppearance(R.style.TextAppearance_ZipPush_BodySmall)
        tv.setTextColor(context.getColor(R.color.browser_crumb))
        tv.isClickable = false
        breadcrumbContainer.addView(tv)
    }

    private fun addCrumbView(crumb: Crumb, isLast: Boolean) {
        val tv = TextView(context)
        tv.text = crumb.label
        tv.setTextAppearance(R.style.TextAppearance_ZipPush_BodySmall)
        tv.maxLines = 1
        tv.isSingleLine = true
        tv.isEnabled = true
        tv.isClickable = true
        tv.setBackgroundResource(R.drawable.bg_crumb)
        tv.setOnClickListener { pendingNav?.invoke(crumb.path) }
        if (isLast) {
            // Current folder: bold, foreground colour — location is
            // always visible and distinguishable (breadcrumb guideline).
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.setTextColor(context.getColor(R.color.browser_crumb_current))
        } else {
            tv.setTextColor(context.getColor(R.color.browser_crumb))
        }
        breadcrumbContainer.addView(tv)
    }

    /** Navigation target of breadcrumb taps; set by show() per dialog. */
    private var pendingNav: ((String) -> Unit)? = null

    // It needs the repo/branch too, so it is wired in show().
    private fun wireNavigation(repo: GitHubClient.Repo, branch: String) {
        pendingNav = { path -> navigateTo(repo, branch, path) }
    }

    /**
     * Parent of the current path ("" when already at the repo root):
     * "a/b/c" -> "a/b", "a" -> "".
     */
    private fun parentOf(path: String): String =
        path.substringBeforeLast('/', "")

    /**
     * v0.3.1: goes up one level — the "back to the previous folder"
     * affordance. Re-uses the normal navigation (same loading state,
     * same breadcrumbs, same cancellation guarantees); when a listing
     * is already in flight the tap is ignored instead of stacking a
     * second fetch on top.
     */
    private fun goUp() {
        if (loadingFolder) return
        val parent = parentOf(currentPath)
        if (parent == currentPath) return // already at the root
        pendingNav?.invoke(parent)
    }

    /**
     * Enables the up button only when a parent exists (any path below
     * the repo root); at the root the affordance stays visible but
     * disabled, so its position never jumps while navigating.
     */
    private fun refreshUpButton() {
        upBtn.isEnabled = currentPath.isNotEmpty()
        upBtn.alpha = if (currentPath.isEmpty()) 0.35f else 1f
    }

    /** Row adapter: folder rows + the trailing new-folder action. */
    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.browserRowIcon)
            val text: TextView = v.findViewById(R.id.browserRowText)
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_browser_folder, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows.getOrNull(position)) {
                is Row.Dir -> {
                    holder.icon.setImageResource(R.drawable.ic_folder)
                    holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(
                        context.getColor(R.color.browser_folder_icon)
                    )
                    holder.text.text = row.name
                    holder.text.setTextColor(context.getColor(R.color.browser_row_text))
                    holder.itemView.setOnClickListener {
                        pendingNav?.invoke(row.path)
                    }
                }
                Row.NewFolder -> {
                    holder.icon.setImageResource(R.drawable.ic_add)
                    holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(
                        context.getColor(R.color.browser_new_folder)
                    )
                    holder.text.text =
                        holder.itemView.context.getString(R.string.browser_new_folder)
                    holder.text.setTextColor(context.getColor(R.color.browser_new_folder))
                    holder.itemView.setOnClickListener { showNewFolderDialog() }
                }
                null -> Unit
            }
        }
    }

    /**
     * "New folder": asks for a name and appends it to the current path —
     * creating a folder in Git needs no server call; the folder exists
     * once a file is committed under it. Supports nested names like
     * "assignments/week4"; the resulting path is sanitized to drop
     * empty segments, dots and ".." (the engine sanitizes again at push
     * time — defence in depth).
     */
    private fun showNewFolderDialog() {
        val input = EditText(context)
        input.hint = context.getString(R.string.browser_new_folder_hint)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        val wrap = LinearLayout(context)
        wrap.orientation = LinearLayout.VERTICAL
        val pad = (context.resources.displayMetrics.density * 20).toInt()
        wrap.setPadding(pad, pad / 2, pad, 0)
        wrap.addView(input)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.browser_new_folder_title)
            .setMessage(R.string.browser_new_folder_helper)
            .setView(wrap)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                applyNewFolder(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Appends a typed folder name to the current path and renders it
     * locally — NO network call: the folder does not exist on GitHub
     * yet (it materializes with the first push), so a listing would
     * 404. The user sees the new path in the breadcrumb immediately and
     * can keep creating nested names or confirm the choice.
     */
    private fun applyNewFolder(name: String) {
        val rel = sanitizeFolderName(name)
        if (rel.isEmpty()) return
        val newPath = if (currentPath.isEmpty()) rel else "$currentPath/$rel"
        currentPath = newPath
        // The folder does not exist on GitHub yet: mark the path local
        // so a rotation restores it WITHOUT a listing call (v0.4.0).
        currentPathIsLocal = true
        rows.clear()
        rows.add(Row.NewFolder)
        renderBreadcrumbs(currentRepoName ?: return)
        refreshUpButton()
        renderList()
    }

    /** Repo display name of the browser session (for breadcrumbs). */
    private var currentRepoName: String? = null

    /** v0.4.0: whether [currentPath] is local-only (not on GitHub yet). */
    fun isLocalPath(): Boolean = currentPathIsLocal

    /** v0.4.0: the path the picker is currently showing ("" = root). */
    fun currentPathValue(): String = currentPath

    /** Drops empty segments, "." and ".." from a typed folder name. */
    private fun sanitizeFolderName(name: String): String = name.split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")

    // ------------------------------------------------------------- test seams

    /** Test seam: current folder path shown by the picker. */
    internal fun currentPathForTest(): String = currentPathValue()

    /** Test seam: whether the current path is local-only (no fetch). */
    internal fun isLocalPathForTest(): Boolean = isLocalPath()

    /** Test seam: whether the picker window is truly full-screen. */
    internal fun isFullScreenForTest(): Boolean {
        val w = dialog?.window ?: return false
        val attrs = w.attributes
        return attrs.width == ViewGroup.LayoutParams.MATCH_PARENT &&
            attrs.height == ViewGroup.LayoutParams.MATCH_PARENT &&
            (attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0
    }

    /** Test seam: labels of the rows currently displayed. */
    internal fun rowLabelsForTest(): List<String> = rows.map { it.toString() }

    /** Test seam: breadcrumb labels (separators excluded). */
    internal fun breadcrumbLabelsForTest(): List<String> =
        (0 until breadcrumbContainer.childCount)
            .mapNotNull { (breadcrumbContainer.getChildAt(it) as? TextView)?.text?.toString() }
            .filter { it != "/" }

    /** Test seam: visibility flags of the main views. */
    internal fun visibilityForTest(): Map<String, Int> = mapOf(
        "loading" to loading.visibility,
        "empty" to emptyView.visibility,
        "error" to errorView.visibility,
        "list" to list.visibility,
        "chooseBtnEnabled" to if (chooseBtn.isEnabled) 1 else 0
    )

    /** Test seam: simulate a breadcrumb tap by index (crumbs only). */
    internal fun tapBreadcrumbForTest(index: Int) {
        var seen = -1
        for (i in 0 until breadcrumbContainer.childCount) {
            val child = breadcrumbContainer.getChildAt(i)
            if (child is TextView && child.text.toString() != "/") {
                seen++
                if (seen == index) {
                    child.performClick()
                    return
                }
            }
        }
    }

    /** Test seam: simulate a row tap by position. */
    internal fun tapRowForTest(position: Int) {
        list.findViewHolderForAdapterPosition(position)?.itemView?.performClick()
            ?: rows.getOrNull(position)?.let { row ->
                // Fallback for holders not attached (tests): route through
                // the same handlers the binder wires.
                when (row) {
                    is Row.Dir -> pendingNav?.invoke(row.path)
                    Row.NewFolder -> showNewFolderDialog()
                }
            }
    }

    /** Test seam: whether a folder listing fetch is in flight. */
    internal fun isLoadingForTest(): Boolean = loadingFolder

    /** Test seam: programmatically open the new-folder dialog (asserts on it). */
    internal fun openNewFolderForTest() = showNewFolderDialog()

    /** Test seam: run the new-folder action with a typed name. */
    internal fun createFolderForTest(name: String) = applyNewFolder(name)

    /** Test seam: the choose button click without a real dialog tap. */
    internal fun chooseForTest() = chooseBtn.performClick()

    /** Test seam: the up (level-up) button state — null when disabled
     *  by path, plus whether a tap would do anything. */
    internal fun upEnabledForTest(): Boolean = upBtn.isEnabled

    /** Test seam: simulate a level-up tap. */
    internal fun tapUpForTest() = upBtn.performClick()

    /** Test seam: parent-path computation used by the up button. */
    internal fun parentOfForTest(path: String): String = parentOf(path)
}
