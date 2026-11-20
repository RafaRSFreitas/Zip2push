package com.zippush.app.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
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
 * The target-folder Explorer (v0.3.0) — a Material dialog that browses
 * the REAL folders of the selected repository/branch, GitHub-style:
 *
 *  - breadcrumb bar ("repo / src / android") — tap any crumb to jump;
 *  - one-line list of the subfolders of the current folder (files are
 *    filtered out: this popup picks a destination, not a file);
 *  - "New folder" row to create a folder name inline (no commit is
 *    made — folders materialize on GitHub when the first file lands
 *    under them);
 *  - "Choose this folder" confirms and "…" keeps browsing.
 *
 * Navigation model: [navigateTo] fetches one directory page per hop
 * (GET /repos/…/contents?ref=…), exactly what GitHub's own file browser
 * does — no full recursive tree is downloaded for a repo the user just
 * wants to drop files into. The up affordance is the "repo" crumb (and
 * the system back gesture, which closes the dialog).
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
        /** The user dismissed the popup without choosing. */
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

    /** Rows currently displayed: the repo's subfolders + the new-folder action. */
    private val rows = mutableListOf<Row>()

    private lateinit var breadcrumbContainer: LinearLayout
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
     * Opens the browser at [startPath] (repository root when blank) and
     * reports the outcome to [onResult] on the caller's thread (the
     * scope's). Re-opens are safe: a previous dialog is dismissed first.
     */
    fun show(
        repo: GitHubClient.Repo,
        branch: String,
        startPath: String,
        onResult: (Result) -> Unit
    ) {
        dialog?.dismiss()
        currentRepoName = repo.name
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_folder_browser, null)
        breadcrumbContainer = view.findViewById(R.id.browserBreadcrumbs)
        list = view.findViewById(R.id.browserList)
        emptyView = view.findViewById(R.id.browserEmpty)
        loading = view.findViewById(R.id.browserLoading)
        errorView = view.findViewById(R.id.browserError)
        chooseBtn = view.findViewById(R.id.browserChooseBtn)

        adapter = RowAdapter()
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        wireNavigation(repo, branch)

        chooseBtn.setOnClickListener {
            onResult(Result.Selected(currentPath))
            dialog?.dismiss()
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.browser_title)
            .setView(view)
            .create()
        dialog?.setOnDismissListener {
            loadJob?.cancel()
            onResult(Result.Cancelled)
        }
        dialog?.show()
        navigateTo(repo, branch, startPath)
    }

    /** Dismisses the popup programmatically (e.g. activity destroyed). */
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
        currentRepoName = repo.name
        renderBreadcrumbs(repo.name)
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
        rows.clear()
        rows.add(Row.NewFolder)
        renderBreadcrumbs(currentRepoName ?: return)
        renderList()
    }

    /** Repo display name of the browser session (for breadcrumbs). */
    private var currentRepoName: String? = null

    /** Drops empty segments, "." and ".." from a typed folder name. */
    private fun sanitizeFolderName(name: String): String = name.split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")

    // ------------------------------------------------------------- test seams

    /** Test seam: current folder path shown by the popup. */
    internal fun currentPathForTest(): String = currentPath

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
}
