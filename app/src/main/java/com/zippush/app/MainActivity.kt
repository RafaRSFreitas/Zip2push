package com.zippush.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import android.widget.DatePicker
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.zippush.app.engine.ArchiveReader
import com.zippush.app.engine.UploadEngine
import com.zippush.app.history.PushHistoryStore
import com.zippush.app.net.GitHubClient
import com.zippush.app.push.PushRunner
import com.zippush.app.push.PushService
import com.zippush.app.storage.TokenVault
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zip2Push v0.2.7 — the v0.2.x architecture restored.
 *
 * The v0.3.x line (background push service, pending queue, push history,
 * share target, accent colours, folder autocomplete, notification
 * permission) is gone: those subsystems carried the crashes reported
 * since 0.3.0. What remains is exactly what the last fully-working
 * version (0.2.6) did, plus the new "Add description" behaviour:
 *
 *  - a push runs in the activity's own scope on Dispatchers.IO; it does
 *    NOT survive rotation (same as 0.2.6 — the upload either finishes
 *    or the user re-taps Push; no background machinery to break);
 *  - every coroutine error lands on the status line (crashGuard), so
 *    nothing kills the process;
 *  - Add description: the field starts collapsed. "Add description"
 *    expands it and disappears. While the field is EMPTY, a "Hide
 *    description" button is available to collapse it again; once the
 *    user typed something, the collapse affordance is gone (their text
 *    must not be silently hidden). Title + blank line + body is the
 *    standard Git commit format.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val MENU_ABOUT = 100
        private const val MENU_DONATIONS = 101
        private const val MENU_HISTORY = 102
        private const val PREF_THEME_MODE = "theme_mode"
        private const val STATE_BACKDATE_EPOCH = "backdate_epoch_ms"

        /**
         * v0.3.5: MIME types the "Add Rar/Zip/Tar" picker offers —
         * exactly the three archive families the button accepts. The
         * list is only a provider-side filter; the real validation
         * happens on the picked bytes (see [onArchivePicked]).
         */
        private val ARCHIVE_MIME_TYPES = arrayOf(
            // zip
            "application/zip", "application/x-zip-compressed", "application/x-compressed",
            // tar (and tar.gz — providers vary)
            "application/x-tar", "application/tar", "application/x-gtar",
            "application/x-gzip", "application/gzip",
            // rar
            "application/x-rar-compressed", "application/vnd.rar", "application/rar",
            // providers frequently report unknown binaries as this
            "application/octet-stream"
        )
    }

    // ------------------------------------------------------------------ views
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var accountBtn: MaterialButton
    private lateinit var repoDropdown: MaterialAutoCompleteTextView
    private lateinit var branchDropdown: MaterialAutoCompleteTextView
    private lateinit var targetDirInput: MaterialAutoCompleteTextView
    private lateinit var commitMessageInput: TextInputEditText
    private lateinit var backdateBtn: MaterialButton

    /**
     * The backdated author-date chosen by the user (null = commit now).
     * Format is the user-facing one; [backdateIso] converts for the API.
     */
    private var commitAuthorDate: java.util.Calendar? = null
    private lateinit var zipBtn: MaterialButton
    private lateinit var filesBtn: MaterialButton
    private lateinit var folderBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var pushBtn: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var viewRepoBtn: MaterialButton
    private lateinit var selectionText: TextView
    private lateinit var overflowBtn: ImageButton
    private lateinit var themeToggleBtn: ImageButton
    private lateinit var reloadBtn: ImageButton
    private lateinit var pickerRow: LinearLayout
    private lateinit var commitBodyLayout: TextInputLayout
    private lateinit var commitBodyInput: TextInputEditText
    private lateinit var commitExpandBtn: MaterialButton
    private lateinit var commitCollapseBtn: MaterialButton

    // ------------------------------------------------------------------ state
    private lateinit var vault: TokenVault
    private var client: GitHubClient? = null
    private var engine: UploadEngine? = null
    private val repos = mutableListOf<GitHubClient.Repo>()

    /** Dropdown model: a repository row, or the create-repository action. */
    private sealed class RepoItem {
        class Repo(val repo: GitHubClient.Repo) : RepoItem() {
            /**
             * The dropdown writes toString() of the picked item into the
             * field. Users asked for just the repo name here — the owner
             * prefix repeated the same account over and over and made
             * rows long. The full name still shows in status messages.
             */
            override fun toString() = repo.name
        }
        object CreateAction : RepoItem() {
            override fun toString() = ""
        }
    }

    /** Test hook: the text the dropdown would write for a repo item. */
    internal object RepoItemForTest {
        fun repoItemText(repo: GitHubClient.Repo): String = RepoItem.Repo(repo).toString()
    }
    private val repoItems = mutableListOf<RepoItem>()

    /**
     * Test hook: set whenever the repo dropdown is programmatically
     * shown (auto-open after loading). Tests reset it and check whether
     * a rotation / reload popped the dropdown open again.
     */
    internal var repoDropdownWasShown = false
        private set

    /** Test hook: clear the auto-open marker between assertions. */
    fun resetRepoDropdownWasShownForTest() { repoDropdownWasShown = false }

    /** Only path allowed to auto-open the repo dropdown. */
    private fun maybeAutoOpenRepoDropdown() {
        if (isFinishing || !alive) return
        repoDropdownWasShown = true
        repoDropdown.showDropDown()
    }

    /**
     * Selected repo / branch. Backed by [SessionState] so a rotation
     * does not wipe the selection: the recreated activity reads it back
     * and does not re-open the repo dropdown as if nothing had been
     * chosen.
     */
    internal var selectedRepo: GitHubClient.Repo?
        get() = SessionState.selectedRepo
        set(value) { SessionState.selectedRepo = value }
    private val branches = mutableListOf<GitHubClient.Branch>()
    internal var selectedBranch: GitHubClient.Branch?
        get() = SessionState.selectedBranch
        set(value) { SessionState.selectedBranch = value }

    /** Test seam: when non-null, loadRepos uses this instead of the network. */
    internal var fakeRepoListForTest: List<GitHubClient.Repo>? = null

    /** Test seam: when non-null, loadBranchesOf uses this instead of the network. */
    internal var fakeBranchListForTest: List<GitHubClient.Branch>? = null

    /** Test hook: connect a fake client and load the (faked) repo list. */
    internal fun loadReposForTest(list: List<GitHubClient.Repo>) {
        client = GitHubClient("test-token")
        engine = UploadEngine(client!!)
        fakeRepoListForTest = list
        scope.launch { loadRepos() }
    }

    /**
     * Test seam for the push-flow tests: connect a REAL client pointed
     * at a test server (MockWebServer) and load the repo list through
     * the real HTTP path — the success path of [connectWithToken],
     * minus the vault write.
     */
    internal fun connectWithBaseUrlForTest(token: String, baseUrl: String) {
        val c = GitHubClient(token, baseUrl)
        client = c
        engine = UploadEngine(c)
        scope.launch { loadRepos() }
    }

    /** Test hook: the current dropdown texts (repo names + trailing ""). */
    internal fun repoItemTextsForTest(): List<String> = repoItems.map { it.toString() }

    /** Test hook: whether the busy overlay (progress) is showing. */
    internal fun isBusyFlagForTest(): Boolean = progress.visibility == View.VISIBLE
    internal val tracker = SelectionTracker()

    /**
     * Re-renders PushRunner state (running / done) into this activity
     * instance. Re-attached by every recreation, so a push started
     * before a rotation still shows its result afterwards (v0.2.8).
     */
    private val pushObserver = PushRunner.Observer { st -> renderPushState(st) }

    /**
     * Last-resort safety net: any exception escaping a coroutine shows on
     * the status line instead of killing the app ("app closes by itself").
     */
    private val crashGuard = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("Zip2Push", "Uncaught coroutine error", throwable)
        if (alive) {
            setStatus(
                getString(R.string.unexpected_error, throwable.message ?: "unknown"),
                StatusKind.ERROR
            )
            setBusy(false)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + crashGuard)
    private var accountsDialog: android.app.Dialog? = null

    /** True while this activity instance is still usable for UI updates. */
    private var alive = true

    /**
     * True between onStart and onStop — i.e. while starting the
     * foreground service is ALLOWED. Android 12+ kills the process with
     * ForegroundServiceStartNotAllowedException when startForegroundService()
     * is called while the app is backgrounded. The push itself runs in
     * the app-scoped PushRunner regardless; the service is only the
     * process-lifeboat while the upload is in flight.
     */
    private var uiStarted = false

    override fun onStart() {
        super.onStart()
        uiStarted = true
    }

    override fun onStop() {
        super.onStop()
        uiStarted = false
    }

    /** Starts the push foreground service only while legally allowed. */
    internal fun startPushServiceIfAllowed() {
        if (alive && uiStarted) PushService.start(this)
    }

    override fun onDestroy() {
        alive = false
        PushRunner.removeObserver(pushObserver)
        scope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Preserve the chosen backdate across rotation / process death so
        // it is not silently lost mid-flow.
        commitAuthorDate?.let { outState.putLong(STATE_BACKDATE_EPOCH, it.timeInMillis) }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // super just reapplied the saved view state — which carries the
        // OLD field text. The session is the source of truth for the
        // repo/branch selection, so re-apply it on top. This also covers
        // the case where the saved text is stale (e.g. cleared then
        // rotated in the same frame).
        SessionState.selectedRepo?.let { repoDropdown.setText(it.name, false) }
        SessionState.selectedBranch?.let { branchDropdown.setText(it.name, false) }
    }

    /** Last pushed commit URL, used by the "View on GitHub" button. */
    internal var lastCommitUrl: String? = null

    /**
     * Target folder of the push currently in flight (captured at tap
     * time; remembered in the session state when it succeeds).
     */
    private var pushedTargetDir: String = ""

    /** v0.3.5: captured at push-start for the History record. */
    private var pushedRepoName: String = ""
    private var pushedBranchName: String = "main"
    private var pushedMessageTitle: String = ""
    private var pushedFileNames: List<String> = emptyList()

    // Adapter model for the branch dropdown: a real branch or the
    // trailing "create new branch" action row.
    private sealed class BranchItem {
        open class Branch(val branch: GitHubClient.Branch) : BranchItem() {
            override fun toString() = branch.name
        }
        object CreateAction : BranchItem() {
            override fun toString() = ""
        }
    }
    private val branchItems = mutableListOf<BranchItem>()

    // ------------------------------------------------------- activity results
    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) onArchivePicked(uri)
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        tracker.pickFiles(uris)
        refreshSelectionSummary()
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { onFolderPicked(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIcon = findViewById(R.id.statusIcon)
        statusText = findViewById(R.id.statusText)
        accountBtn = findViewById(R.id.accountBtn)
        repoDropdown = findViewById(R.id.repoDropdown)
        branchDropdown = findViewById(R.id.branchDropdown)
        targetDirInput = findViewById(R.id.targetDirInput)
        commitMessageInput = findViewById(R.id.commitMessageInput)
        backdateBtn = findViewById(R.id.backdateBtn)
        zipBtn = findViewById(R.id.zipBtn)
        filesBtn = findViewById(R.id.filesBtn)
        folderBtn = findViewById(R.id.folderBtn)
        clearBtn = findViewById(R.id.clearBtn)
        pushBtn = findViewById(R.id.pushBtn)
        progress = findViewById(R.id.progress)
        viewRepoBtn = findViewById(R.id.viewRepoBtn)
        selectionText = findViewById(R.id.selectionText)
        overflowBtn = findViewById(R.id.overflowBtn)
        themeToggleBtn = findViewById(R.id.themeToggleBtn)
        reloadBtn = findViewById(R.id.reloadBtn)
        pickerRow = findViewById(R.id.pickerRow)
        commitBodyLayout = findViewById(R.id.commitBodyLayout)
        commitBodyInput = findViewById(R.id.commitBodyInput)
        commitExpandBtn = findViewById(R.id.commitExpandBtn)
        commitCollapseBtn = findViewById(R.id.commitCollapseBtn)

        // Enforce EXACT equality of the Files / Folder buttons. LinearLayout
        // weight rounding can differ by a pixel on odd-width rows; after
        // the row is laid out we clamp both buttons to the same width/height.
        pickerRow.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    equalizePickerButtons()
                }
            }
        )

        // Apply the persisted theme override before first draw.
        applyTheme(prefThemeMode)
        themeToggleBtn.setOnClickListener { toggleTheme() }

        vault = TokenVault(this)

        // A push may have been started by a previous activity instance
        // (rotation). Re-attach to render its state or result (v0.2.8).
        PushRunner.addObserver(pushObserver)
        renderPushState(PushRunner.state)

        // Restore a repo chosen before a configuration change right away:
        // the field shows it immediately (the list refresh re-matches it
        // against the fresh data when it lands).
        SessionState.selectedRepo?.let { repoDropdown.setText(it.name, false) }
        SessionState.selectedBranch?.let { branchDropdown.setText(it.name, false) }

        // Restore the backdate chosen before a configuration change.
        val savedEpoch = savedInstanceState?.getLong(STATE_BACKDATE_EPOCH, -1L) ?: -1L
        if (savedEpoch > 0) {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = savedEpoch }
            commitAuthorDate = cal
            refreshBackdateButtonForTest()
        }

        // ------------------------------------------------ account interactions
        accountBtn.setOnClickListener { showAccountsPopup() }

        repoDropdown.setOnItemClickListener { _, _, position, _ ->
            when (val item = repoItems.getOrNull(position)) {
                is RepoItem.Repo -> scope.launch { onRepoSelected(item.repo) }
                RepoItem.CreateAction -> {
                    // Restore the selected repo text (the click would clear it).
                    repoDropdown.setText(selectedRepo?.name ?: "", false)
                    openCreateRepoInBrowser()
                }
                null -> Unit
            }
        }

        branchDropdown.setOnItemClickListener { _, _, position, _ ->
            when (val item = branchItems.getOrNull(position)) {
                is BranchItem.Branch -> selectedBranch = item.branch
                BranchItem.CreateAction -> {
                    // Restore the selected branch text (the click would clear it).
                    branchDropdown.setText(selectedBranch?.name ?: "", false)
                    showCreateBranchDialog()
                }
                null -> Unit
            }
        }

        zipBtn.setOnClickListener {
            // v0.3.5: the archive button accepts RAR, ZIP and tar only.
            // The picker filters by the MIME types providers usually
            // report; providers that report something odd fall back to
            // showing all files (the format is validated by magic bytes
            // at pick time anyway).
            try {
                pickZip.launch(ARCHIVE_MIME_TYPES)
            } catch (e: Exception) {
                pickZip.launch(arrayOf("*/*"))
            }
        }

        filesBtn.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }

        folderBtn.setOnClickListener { pickFolder.launch(null) }

        clearBtn.setOnClickListener { onClearClicked() }

        pushBtn.setOnClickListener { onPushClicked() }

        viewRepoBtn.setOnClickListener {
            val url = lastCommitUrl ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        overflowBtn.setOnClickListener { showOverflowMenu(it) }
        reloadBtn.setOnClickListener { onReloadClicked() }
        backdateBtn.setOnClickListener { showBackdateDialog() }

        // ------------------------------------------------ description (0.2.7)
        // "Add description" expands the (initially collapsed) field and
        // disappears — it has no further use once the field is open.
        commitExpandBtn.setOnClickListener { setDescriptionExpanded(true) }
        // The collapse button only ever shows while the field is EMPTY:
        // the user opened it by accident and wants it gone. Once text is
        // typed it stays away, so typed content is never hidden.
        commitCollapseBtn.setOnClickListener { setDescriptionExpanded(false) }
        commitBodyInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                refreshCollapseButton()
            }
        })

        // Auto-connect to the active or pending account on launch.
        val token = vault.loadToken()
        if (token != null) {
            connectWithToken(token, auto = true)
        } else {
            updateAccountButton()
            setStatus(getString(R.string.status_ready), StatusKind.INFO)
        }
    }

    // ------------------------------------------------- description (v0.2.7)

    /** True when the optional description field is visible. */
    internal fun isDescriptionExpanded(): Boolean =
        commitBodyLayout.visibility == View.VISIBLE

    /** True when the description field has no user text. */
    internal fun isDescriptionEmpty(): Boolean =
        commitBodyInput.text?.toString()?.isBlank() != false

    /**
     * Shows / hides the optional description field and syncs both buttons:
     *  - expand button: visible only while the field is collapsed;
     *  - collapse button: visible only while the field is expanded AND
     *    empty (v0.2.7 spec: "while not entering anything inside this
     *    text box, the user should see a button to collapse that field").
     */
    internal fun setDescriptionExpanded(expanded: Boolean) {
        commitBodyLayout.visibility = if (expanded) View.VISIBLE else View.GONE
        if (!expanded) commitBodyInput.setText("")
        // "Add description" has no further use once the field is open.
        commitExpandBtn.visibility = if (expanded) View.GONE else View.VISIBLE
        refreshCollapseButton()
    }

    /** Re-evaluates the collapse button against the field's content. */
    private fun refreshCollapseButton() {
        commitCollapseBtn.visibility =
            if (isDescriptionExpanded() && isDescriptionEmpty()) View.VISIBLE else View.GONE
    }

    // Test hooks for the description behaviour.
    internal fun expandBtnVisibilityForTest(): Int = commitExpandBtn.visibility
    internal fun collapseBtnVisibilityForTest(): Int = commitCollapseBtn.visibility
    internal fun setDescriptionTextForTest(text: String?) {
        commitBodyInput.setText(text)
    }
    internal fun descriptionTextForTest(): String =
        commitBodyInput.text?.toString().orEmpty()

    // ----------------------------------------------------------- status line

    private enum class StatusKind { INFO, PROGRESS, SUCCESS, ERROR }

    /**
     * A finished push's result that the bar must KEEP showing until the
     * user starts something new (v0.2.9): after a rotation the
     * auto-connect used to overwrite "Pushed ✓…" with "Verifying
     * token…" / "Connected as…", hiding the outcome the user cares
     * about. Cleared by user-initiated actions (push, connect, reload,
     * clear) — those are allowed to own the bar again.
     */
    @Volatile
    private var holdDoneResult: PushRunner.State.Done? = null

    /**
     * True while a background network flow from THIS activity (connect,
     * repos, branches…) is between setBusy(true) and setBusy(false).
     * The visible spinner is the OR of this and the app-scoped push, so
     * a repo refresh finishing mid-push can no longer hide the push's
     * spinner — the "loading stopped" illusion after rotation (v0.2.9).
     */
    @Volatile
    private var netBusy = false


    /**
     * Single writer of the spinner and the button locks: visible when
     * this activity's network work OR the app-scoped push is running.
     * Every setBusy call funnels here, so nothing can bypass the OR.
     */
    private fun applyBusy() {
        if (!alive) return
        val busy = netBusy || PushRunner.isBusy()
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        pushBtn.isEnabled = !busy
        zipBtn.isEnabled = !busy
        filesBtn.isEnabled = !busy
        folderBtn.isEnabled = !busy
        clearBtn.isEnabled = !busy
        accountBtn.isEnabled = !busy
    }

    /**
     * Shows [message] on the fixed bottom status line. Always visible.
     *
     * While a push is in flight the push owns the bar (its description
     * says what is happening); a background refresh landing mid-push
     * ("Connected as…", "Loaded branches…") used to overwrite it right
     * after a rotation and made the push look stopped. Those messages
     * are dropped instead — the push is the more important information.
     */
    private fun setStatus(message: String, kind: StatusKind = StatusKind.INFO) {
        if (!alive) return
        if (PushRunner.isBusy()) return
        if (holdDoneResult != null) return
        statusText.text = message
        val (iconRes, tintRes) = when (kind) {
            StatusKind.INFO -> R.drawable.ic_info to R.color.status_text
            StatusKind.PROGRESS -> R.drawable.ic_info to R.color.status_text
            StatusKind.SUCCESS -> R.drawable.ic_success to R.color.status_success
            StatusKind.ERROR -> R.drawable.ic_warning to R.color.status_error
        }
        statusIcon.setImageResource(iconRes)
        statusIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, tintRes))
    }

    /** Test hook: the current status line text. */
    internal fun statusTextForTest(): String = statusText.text.toString()

    /** Test hook: whether the Push button is enabled. */
    internal fun pushBtnEnabledForTest(): Boolean = pushBtn.isEnabled

    /** Test hook: whether the progress spinner is visible. */
    internal fun isSpinnerVisibleForTest(): Boolean = progress.visibility == View.VISIBLE

    /**
     * Clamps the Files / Folder buttons to identical width/height and
     * balances the leftover pixel into the row's outer padding (keeps
     * the pair centered). Weights are dropped once the width is fixed —
     * a LinearLayout re-derives weighted children's width on every measure
     * pass, which would undo the correction.
     */
    internal fun equalizePickerButtons() {
        if (filesBtn.width <= 0 || folderBtn.width <= 0) return
        if (filesBtn.width == folderBtn.width &&
            filesBtn.height == folderBtn.height
        ) return
        val w = minOf(filesBtn.width, folderBtn.width)
        val h = maxOf(filesBtn.height, folderBtn.height)
        listOf(filesBtn, folderBtn).forEach { b ->
            val lp = b.layoutParams as LinearLayout.LayoutParams
            lp.width = w
            lp.height = h
            lp.weight = 0f
            b.layoutParams = lp
        }
        val innerMargins =
            (filesBtn.layoutParams as ViewGroup.MarginLayoutParams).marginEnd +
            (folderBtn.layoutParams as ViewGroup.MarginLayoutParams).marginStart
        val leftover = pickerRow.width - 2 * w - innerMargins
        if (leftover > 0) pickerRow.setPadding(leftover / 2, 0, leftover / 2, 0)
        // On a device the next traversal applies the new sizes; in tests the
        // caller drives a measure/layout pass on the decor view.
        pickerRow.requestLayout()
    }

    // ------------------------------------------------------------- overflow menu

    /** 3-dot menu (top right): History, About and Donations. */
    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_HISTORY, 0, R.string.menu_history)
        popup.menu.add(0, MENU_ABOUT, 1, R.string.menu_about)
        popup.menu.add(0, MENU_DONATIONS, 2, R.string.menu_donations)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HISTORY -> { showHistoryDialog(); true }
                MENU_ABOUT -> { showAboutDialog(); true }
                MENU_DONATIONS -> { showDonationsDialog(); true }
                else -> false
            }
        }
        popup.show()
    }

    /** v0.3.5: the History dialog (past pushes, newest first). */
    internal fun showHistoryDialog() {
        com.zippush.app.history.PushHistoryDialog.show(this)
    }

    // ------------------------------------------------------------- theme toggle

    /**
     * Persists the user's manual day/night choice. System = follow the OS
     * setting (the default); the other two override it. Both modes use the
     * JornalWeb palettes (values/colors_jw.xml and values-night/).
     */
    private val prefThemeMode: Int
        get() = getPreferences(MODE_PRIVATE).getInt(PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    private fun applyTheme(mode: Int, animateIcon: Boolean = true) {
        getPreferences(MODE_PRIVATE).edit().putInt(PREF_THEME_MODE, mode).apply()
        // The icon shows the mode you would switch TO: moon in day mode,
        // sun in night mode (JornalWeb-style affordance).
        val currentlyNight = when (mode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        themeToggleBtn.setImageResource(
            if (currentlyNight) R.drawable.ic_theme_sun else R.drawable.ic_theme_moon
        )
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Toggles between day and night; a manual toggle overrides the system. */
    private fun toggleTheme() {
        val currentlyNight = prefThemeMode == AppCompatDelegate.MODE_NIGHT_YES ||
            (prefThemeMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM &&
                resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES)
        if (currentlyNight) {
            applyTheme(AppCompatDelegate.MODE_NIGHT_NO)
            setStatus(getString(R.string.theme_switched_day), StatusKind.INFO)
        } else {
            applyTheme(AppCompatDelegate.MODE_NIGHT_YES)
            setStatus(getString(R.string.theme_switched_night), StatusKind.INFO)
        }
    }

    /** About screen as a dialog: version, description, privacy, open source. */
    private fun showAboutDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_about, null)
        val versionText = view.findViewById<TextView>(R.id.aboutVersionText)
        versionText.text = getString(
            R.string.about_version_format,
            com.zippush.app.BuildConfig.VERSION_NAME
        )
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Donations — placeholder until payment details are defined. */
    private fun showDonationsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_donations)
            .setMessage(R.string.donations_coming_soon)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------- folder walk

    /**
     * v0.3.5: an archive picked with the "Add Rar/Zip/Tar" button.
     * The format is verified by MAGIC BYTES (providers lie about MIME
     * types — a .rar often arrives as application/octet-stream), so a
     * non-archive file is rejected here with a clear toast instead of
     * failing mid-push.
     */
    private fun onArchivePicked(uri: Uri) {
        val format = runCatching {
            contentResolver.openInputStream(uri)?.use { ArchiveReader.detectFormat(it) }
        }.getOrNull() ?: ArchiveReader.Format.UNKNOWN
        if (format == ArchiveReader.Format.UNKNOWN) {
            tracker.pickZip(null)
            pickedArchiveFormat = null
            refreshSelectionSummary()
            Toast.makeText(this, getString(R.string.toast_unsupported_archive), Toast.LENGTH_SHORT).show()
            return
        }
        tracker.pickZip(uri)
        pickedArchiveFormat = format
        refreshSelectionSummary()
    }

    /** Test hook: runs the archive-button pick path for [uri]. */
    internal fun onArchivePickedForTest(uri: Uri) {
        onArchivePicked(uri)
    }

    /**
     * The format of the currently picked archive (detected at pick
     * time, v0.3.5) — spares the summary a second stream open.
     */
    private var pickedArchiveFormat: ArchiveReader.Format? = null

    /**
     * Walks the picked document tree recursively and turns every regular
     * file into a pending upload with a repo-relative path (folder name
     * preserved, e.g. "photos/cat.jpg"). Runs the walk on Dispatchers.IO.
     */
    private fun onFolderPicked(treeUri: Uri) {
        setBusy(true)
        setStatus("Scanning folder…", StatusKind.PROGRESS)
        scope.launch {
            val result = withContext(Dispatchers.IO) { walkDocumentTree(treeUri) }
            setBusy(false)
            when {
                result.errorMessage != null ->
                    setStatus(result.errorMessage!!, StatusKind.ERROR)
                result.files.isEmpty() ->
                    setStatus(getString(R.string.folder_empty), StatusKind.INFO)
                else -> {
                    tracker.pickFilesWithPaths(result.files)
                    refreshSelectionSummary()
                    setStatus(
                        getString(R.string.folder_picked_summary, result.files.size, result.rootName),
                        StatusKind.SUCCESS
                    )
                }
            }
        }
    }

    /** Outcome of a recursive folder walk. */
    data class FolderWalkResult(
        val files: List<SelectionTracker.FolderFile>,
        val rootName: String,
        val errorMessage: String?
    )

    /** Recursively lists files under [treeUri]; paths relative to the folder root. */
    private fun walkDocumentTree(treeUri: Uri): FolderWalkResult {
        return try {
            val root = android.provider.DocumentsContract.getTreeDocumentId(treeUri) // e.g. "primary:Pictures/MyFolder"
            val rootName = root.substringAfterLast('/').ifBlank { "folder" }
            val files = mutableListOf<SelectionTracker.FolderFile>()
            val stack = ArrayDeque(listOf(root))
            while (stack.isNotEmpty()) {
                val dirId = stack.removeLast()
                val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, dirId
                )
                contentResolver.query(
                    childrenUri,
                    arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0)
                        val name = cursor.getString(1)
                        val mime = cursor.getString(2)
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.addLast(docId)
                        } else {
                            // Repo path: folder-root-relative, forward slashes.
                            val relative = docId.removePrefix(root).trimStart('/')
                            val repoPath = relative.ifBlank { name }
                            files.add(
                                SelectionTracker.FolderFile(
                                    android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                                    repoPath
                                )
                            )
                        }
                    }
                }
            }
            FolderWalkResult(files.sortedBy { it.repoPath }, rootName, null)
        } catch (e: Exception) {
            FolderWalkResult(emptyList(), "", e.message ?: "unreadable folder")
        }
    }

    private fun updateAccountButton() {
        val active = vault.activeLogin()
        accountBtn.text = when {
            client != null && active != null -> active
            else -> getString(R.string.connect_account)
        }
    }

    // ------------------------------------------------------------ account UI

    /** Popup listing all saved accounts with switch and remove actions. */
    private fun showAccountsPopup() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_accounts, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.accountsContainer)
        val emptyText = dialogView.findViewById<TextView>(R.id.accountsEmptyText)
        val addBtn = dialogView.findViewById<MaterialButton>(R.id.addAccountBtn)

        val accounts = vault.listAccounts()
        emptyText.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE

        val currentLogin = vault.activeLogin()
        for (account in accounts) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_account, container, false) as LinearLayout
            val isActive = account.login == currentLogin
            row.findViewById<TextView>(R.id.accountName).text = account.login
            row.findViewById<TextView>(R.id.accountTokenPreview).text =
                if (isActive) getString(R.string.active_account) else account.tokenPreview
            row.findViewById<ImageView>(R.id.accountIcon).setImageResource(
                if (isActive) R.drawable.ic_check else R.drawable.ic_account
            )
            row.setOnClickListener {
                accountsDialog?.dismiss()
                if (!isActive) switchAccount(account.login)
            }
            row.findViewById<ImageButton>(R.id.removeAccountBtn).setOnClickListener {
                vault.removeAccount(account.login)
                if (isActive) disconnect()
                accountsDialog?.dismiss()
                updateAccountButton()
                // Reopen the popup so the user sees the updated list.
                if (vault.listAccounts().isNotEmpty()) showAccountsPopup()
                else showAddAccountDialog()
            }
            container.addView(row)
        }

        accountsDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        addBtn.setOnClickListener {
            accountsDialog?.dismiss()
            showAddAccountDialog()
        }

        accountsDialog?.show()
    }

    /**
     * Popup with the token field for adding a new account. Tapping outside
     * (or Cancel / back) simply gives up, as with any normal dialog.
     */
    private fun showAddAccountDialog() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_account, null)
        val tokenLayout = dialogView.findViewById<TextInputLayout>(R.id.addTokenLayout)
        val tokenInput = dialogView.findViewById<TextInputEditText>(R.id.addTokenInput)
        tokenInput.requestFocus()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_account_title)
            .setView(dialogView)
            // Positive listener is set after show() so an empty token keeps
            // the dialog open instead of dismissing it.
            .setPositiveButton(R.string.connect, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener {
                val token = tokenInput.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    tokenLayout.error = getString(R.string.token_empty)
                } else {
                    tokenLayout.error = null
                    dialog.dismiss()
                    connectWithToken(token, reopenDialogOnFailure = true)
                }
            }
    }

    /** Connects with the given login's stored token. */
    private fun switchAccount(login: String) {
        holdDoneResult = null
        val token = vault.loadAccountToken(login) ?: run {
            setStatus(getString(R.string.token_error, "stored token unreadable"), StatusKind.ERROR)
            return
        }
        vault.setActiveLogin(login)
        // Switching accounts keeps the picked files — the selection is
        // account-independent; only repo access changes.
        connectWithToken(token)
    }

    /** Clears the connection state back to "no account". */
    private fun disconnect() {
        client = null
        engine = null
        repos.clear()
        repoItems.clear()
        branches.clear()
        branchItems.clear()
        // Session state too: a new login is a new session (the repo
        // dropdown may auto-open again for the fresh account). Picked
        // FILES survive — they belong to the user, not the account.
        SessionState.clear()
        repoDropdown.setText("", false)
        (repoDropdown.adapter as? ArrayAdapter<*>)?.clear()
        branchDropdown.setText("", false)
        (branchDropdown.adapter as? ArrayAdapter<*>)?.clear()
        targetDirInput.setAdapter(null)
        updateAccountButton()
        setStatus(getString(R.string.status_ready), StatusKind.INFO)
    }

    /** Test hook: disconnect without touching the UI-less parts. */
    internal fun disconnectForTest() = disconnect()

    /** Test hook: manual reload path. */
    internal fun onReloadClickedForTest() = onReloadClicked()

    // ------------------------------------------------------------------ logic

    private fun connectWithToken(
        token: String,
        reopenDialogOnFailure: Boolean = false,
        auto: Boolean = false
    ) {
        // An AUTO-connect (activity recreation) must not clear the held
        // push result off the status bar: after a rotation the user still
        // needs to see "Pushed ✓ …". User-initiated connects clear it —
        // the user just started a new, more important flow (v0.2.9).
        if (!auto) holdDoneResult = null
        setBusy(true)
        setStatus("Verifying token…", StatusKind.PROGRESS)
        scope.launch {
            val c = GitHubClient(token)
            when (val r = withContext(Dispatchers.IO) { c.verifyToken() }) {
                is GitHubClient.ApiResult.Success -> {
                    vault.saveAccount(r.data, token)
                    client = c
                    engine = UploadEngine(c)
                    updateAccountButton()
                    setStatus(getString(R.string.connected_as, r.data), StatusKind.SUCCESS)
                    loadRepos()
                }
                is GitHubClient.ApiResult.Error -> {
                    setBusy(false)
                    setStatus(getString(R.string.token_error, r.message), StatusKind.ERROR)
                    // Re-open the add-account dialog so the user can fix the token
                    // (only when they explicitly initiated the connect).
                    if (reopenDialogOnFailure) showAddAccountDialog()
                }
            }
        }
    }

    private suspend fun loadRepos(reopenDropdown: Boolean = true) {
        setBusy(true)
        setStatus("Loading repositories…", StatusKind.PROGRESS)
        val c = client ?: return
        val fetched = withContext(Dispatchers.IO) {
            fakeRepoListForTest?.let { return@withContext GitHubClient.ApiResult.Success(it) }
            c.listRepos()
        }
        when (fetched) {
            is GitHubClient.ApiResult.Success -> {
                val r = fetched
                if (!alive) return
                repos.clear()
                repos.addAll(r.data)
                updateRepoItems()
                setBusy(false)
                if (repos.isEmpty()) {
                    setStatus(getString(R.string.repos_error, "no accessible repositories"), StatusKind.INFO)
                } else {
                    // Restore a selection made before a rotation: the repo
                    // list may have been re-fetched, so re-match by name.
                    val wanted = SessionState.selectedRepo
                    val stillThere = wanted?.let { w -> repos.firstOrNull { it.fullName == w.fullName } }
                    if (stillThere != null) {
                        selectedRepo = stillThere
                        repoDropdown.setText(stillThere.name, false)
                        // Branch list is per-repo: only reload it when the
                        // activity instance is fresh (its adapter is empty).
                        if (branchItems.isEmpty() && selectedBranch == null) {
                            loadBranchesOf(stillThere)
                        } else if (selectedBranch != null) {
                            branchDropdown.setText(selectedBranch!!.name, false)
                        }
                    }
                    if (selectedRepo == null) {
                        setStatus(
                            if (repos.none { it.private })
                                getString(R.string.repos_loaded_public_only, repos.size)
                            else getString(R.string.repos_loaded, repos.size),
                            StatusKind.INFO
                        )
                        // Auto-open only when nothing has been picked this
                        // session. This is the rotation fix: a recreated
                        // activity whose user already chose a repo must not
                        // pop the dropdown open again.
                        if (reopenDropdown && !SessionState.repoPickedThisSession) {
                            maybeAutoOpenRepoDropdown()
                        }
                    } else {
                        setStatus(
                            getString(R.string.branches_loaded, selectedRepo!!.name, branches.size),
                            StatusKind.SUCCESS
                        )
                    }
                }
            }
            is GitHubClient.ApiResult.Error -> {
                setBusy(false)
                setStatus(getString(R.string.repos_error, fetched.message), StatusKind.ERROR)
            }
        }
    }

    /** Loads the branch list of [repo] without touching the repo selection. */
    private suspend fun loadBranchesOf(repo: GitHubClient.Repo) {
        val c = client ?: return
        val fetched = withContext(Dispatchers.IO) {
            fakeBranchListForTest?.let { return@withContext GitHubClient.ApiResult.Success(it) }
            c.listBranches(repo.owner, repo.name)
        }
        when (fetched) {
            is GitHubClient.ApiResult.Success -> {
                if (!alive) return
                branches.clear()
                branches.addAll(fetched.data)
                updateBranchItems()
            }
            is GitHubClient.ApiResult.Error -> Unit // status already says it
        }
    }

    /** Rebuilds the repo dropdown model: repositories + the create action at the end. */
    private fun updateRepoItems() {
        repoItems.clear()
        repos.forEach { repoItems.add(RepoItem.Repo(it)) }
        repoItems.add(RepoItem.CreateAction)
        repoDropdown.setAdapter(RepoAdapter())
    }

    /** Dropdown adapter rendering repo name + Public/Private badge. */
    private inner class RepoAdapter : ArrayAdapter<RepoItem>(
        this@MainActivity, R.layout.item_repo, repoItems
    ) {
        /** Pass-through filter: the dropdown must always list every repo. */
        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val res = FilterResults()
                    res.values = repoItems.toList()
                    res.count = repoItems.size
                    return res
                }
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    notifyDataSetChanged()
                }
            }
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val item = getItem(position)) {
                is RepoItem.Repo -> {
                    val v = convertView?.takeIf { it.tag == "repo" }
                        ?: LayoutInflater.from(context)
                            .inflate(R.layout.item_repo, parent, false)
                            .also { it.tag = "repo" }
                    v.findViewById<TextView>(R.id.repoNameText).text = item.repo.name
                    val badge = v.findViewById<TextView>(R.id.repoTypeBadge)
                    if (item.repo.private) {
                        badge.text = getString(R.string.badge_private)
                        badge.setBackgroundResource(R.drawable.bg_badge_private)
                    } else {
                        badge.text = getString(R.string.badge_public)
                        badge.setBackgroundResource(R.drawable.bg_badge_public)
                    }
                    v
                }
                RepoItem.CreateAction -> {
                    val v = LayoutInflater.from(context)
                        .inflate(R.layout.item_action, parent, false)
                    v.findViewById<ImageView>(R.id.actionIcon)
                        .setImageResource(R.drawable.ic_add)
                    v.findViewById<TextView>(R.id.actionText).text =
                        getString(R.string.create_repo_action)
                    v
                }
                null -> View(context)
            }
        }
    }

    private suspend fun onRepoSelected(repo: GitHubClient.Repo) {
        selectedRepo = repo
        selectedBranch = null
        branchDropdown.setText("", false)
        viewRepoBtn.visibility = View.GONE
        lastCommitUrl = null
        setBusy(true)
        setStatus("Loading branches of ${repo.name}…", StatusKind.PROGRESS)
        val c = client ?: return
        val fetched = withContext(Dispatchers.IO) {
            fakeBranchListForTest?.let { return@withContext GitHubClient.ApiResult.Success(it) }
            c.listBranches(repo.owner, repo.name)
        }
        when (fetched) {
            is GitHubClient.ApiResult.Success -> {
                branches.clear()
                branches.addAll(fetched.data)
                updateBranchItems()
                setBusy(false)
                setStatus(
                    getString(R.string.branches_loaded, repo.name, branches.size),
                    StatusKind.SUCCESS
                )
            }
            is GitHubClient.ApiResult.Error -> {
                setBusy(false)
                setStatus(getString(R.string.branches_error, fetched.message), StatusKind.ERROR)
            }
        }
    }

    /** Rebuilds the branch dropdown model: branches + create action at the end. */
    private fun updateBranchItems() {
        branchItems.clear()
        branches.forEach { branchItems.add(BranchItem.Branch(it)) }
        branchItems.add(BranchItem.CreateAction)
        branchDropdown.setAdapter(BranchAdapter())
        val repo = selectedRepo ?: return
        if (branches.isNotEmpty()) {
            // Keep a branch chosen before a rotation; only fall back to
            // the repo's default branch (or the first) when none was.
            val chosen = selectedBranch?.takeIf { b -> branches.any { it.name == b.name } }
            if (chosen != null) {
                selectedBranch = chosen
                branchDropdown.setText(chosen.name, false)
                return
            }
            val defaultIdx = branches.indexOfFirst { it.name == repo.defaultBranch }
            val idx = if (defaultIdx >= 0) defaultIdx else 0
            selectedBranch = branches[idx]
            branchDropdown.setText(branches[idx].name, false)
        }
    }

    /** Dropdown adapter for branch rows and the trailing create-branch action. */
    private inner class BranchAdapter : ArrayAdapter<BranchItem>(
        this@MainActivity, R.layout.item_branch, branchItems
    ) {
        /** Pass-through filter: the dropdown must always list every branch. */
        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val res = FilterResults()
                    res.values = branchItems.toList()
                    res.count = branchItems.size
                    return res
                }
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    notifyDataSetChanged()
                }
            }
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            // Always inflate: branch rows and the action row use different layouts,
            // and the default recycler does not track view types.
            return when (val item = getItem(position)) {
                is BranchItem.Branch -> {
                    val v = LayoutInflater.from(context)
                        .inflate(R.layout.item_branch, parent, false)
                    v.findViewById<TextView>(R.id.branchNameText).text = item.branch.name
                    v.findViewById<TextView>(R.id.branchHintText).visibility = View.GONE
                    v
                }
                BranchItem.CreateAction -> {
                    val v = LayoutInflater.from(context)
                        .inflate(R.layout.item_action, parent, false)
                    v.findViewById<ImageView>(R.id.actionIcon)
                        .setImageResource(R.drawable.ic_add)
                    v.findViewById<TextView>(R.id.actionText).text =
                        getString(R.string.create_branch_action)
                    v
                }
                null -> View(context)
            }
        }
    }

    /** Opens GitHub's create-repository page in the browser. */
    private fun openCreateRepoInBrowser() {
        setStatus(getString(R.string.create_repo_opening), StatusKind.INFO)
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            Uri.parse("https://github.com/new")
        )
        runCatching { startActivity(intent) }
            .onFailure {
                setStatus(
                    getString(R.string.repos_error, "no browser available"),
                    StatusKind.ERROR
                )
            }
    }

    /** Dialog for entering a new branch name; creates it on GitHub. */
    private fun showCreateBranchDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.new_branch_dialog_hint)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val pad = (resources.displayMetrics.density * 20).toInt()
        wrap.setPadding(pad, pad / 2, pad, 0)
        wrap.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_branch_title)
            .setMessage(R.string.create_branch_dialog_helper)
            .setView(wrap)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    setStatus(getString(R.string.branch_name_empty), StatusKind.ERROR)
                    return@setPositiveButton
                }
                createBranch(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Creates the branch on GitHub from the selected branch's head. */
    private fun createBranch(name: String) {
        val repo = selectedRepo ?: return
        val base = selectedBranch?.name ?: repo.defaultBranch
        setBusy(true)
        setStatus("Creating branch $name…", StatusKind.PROGRESS)
        val c = client ?: return
        scope.launch {
            when (val head = withContext(Dispatchers.IO) {
                c.getBranchHead(repo.owner, repo.name, base)
            }) {
                is GitHubClient.ApiResult.Success -> {
                    when (val created = withContext(Dispatchers.IO) {
                        c.createBranch(repo.owner, repo.name, name, head.data)
                    }) {
                        is GitHubClient.ApiResult.Success -> {
                            branches.add(GitHubClient.Branch(name))
                            updateBranchItems()
                            selectedBranch = branches.last { it.name == name }
                            branchDropdown.setText(name, false)
                            setBusy(false)
                            setStatus(getString(R.string.branch_created, name), StatusKind.SUCCESS)
                        }
                        is GitHubClient.ApiResult.Error -> {
                            setBusy(false)
                            if (created.code == 422) {
                                // Branch already exists — just select it.
                                branches.add(GitHubClient.Branch(name))
                                updateBranchItems()
                                selectedBranch = branches.last { it.name == name }
                                branchDropdown.setText(name, false)
                                setStatus(getString(R.string.branch_exists, name), StatusKind.INFO)
                            } else {
                                setStatus(
                                    getString(R.string.branch_create_error, created.message),
                                    StatusKind.ERROR
                                )
                            }
                        }
                    }
                }
                is GitHubClient.ApiResult.Error -> {
                    setBusy(false)
                    setStatus(getString(R.string.branch_create_error, head.message), StatusKind.ERROR)
                }
            }
        }
    }

    // ------------------------------------------------------ selection / clear

    @SuppressLint("SetTextI18n")
    internal fun refreshSelectionSummary() {
        if (!alive) return
        val zip = tracker.zip
        val files = tracker.filesWithPaths
        refreshTargetDirSuggestions()
        selectionText.visibility = if (zip == null && files.isEmpty()) View.GONE else View.VISIBLE
        selectionText.text = buildString {
            zip?.let {
                // v0.3.5: any archive format may sit here (RAR/ZIP/tar),
                // so the label names what was detected at pick time.
                val label = when (pickedArchiveFormat) {
                    ArchiveReader.Format.TAR_GZ -> "tar.gz"
                    ArchiveReader.Format.RAR4, ArchiveReader.Format.RAR5 -> "RAR"
                    ArchiveReader.Format.ZIP -> "zip"
                    ArchiveReader.Format.TAR -> "tar"
                    null, ArchiveReader.Format.UNKNOWN -> null
                }
                val name = queryDisplayName(it) ?: "archive"
                appendLine(
                    if (label != null) "Archive ($label): $name" else "Archive: $name"
                )
            }
            if (files.isNotEmpty()) {
                appendLine("Files: ${files.size}")
                files.take(4).forEach { appendLine("  ${it.repoPath}") }
                if (files.size > 4) appendLine("  … +${files.size - 4} more")
            }
        }.trim()
        // The selection details live in selectionText above; the buttons keep
        // their fixed labels so both bottom buttons stay the same size.
    }

    /**
     * Rebuilds the target-folder suggestion list (v0.2.9). Offline and
     * local: the parent folders of the currently picked files (the user
     * just told us where their content lives) plus folders used by
     * earlier pushes this session, most recent first. The chevron on
     * the field opens this list — that is what makes it a real affordance
     * instead of a dead icon keeping the box tall.
     */
    private fun refreshTargetDirSuggestions() {
        if (!alive) return
        val fromSelection = tracker.filesWithPaths
            .map { it.repoPath.substringBeforeLast('/', "") }
            .filter { it.isNotBlank() }
            .distinct()
        val hints = (fromSelection + SessionState.recentTargetDirs).distinct().take(6)
        if (hints.isEmpty()) {
            targetDirInput.setAdapter(null)
            return
        }
        targetDirInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, hints)
        )
    }

    /** Test hook: the current target-folder suggestions. */
    internal fun targetDirSuggestionsForTest(): List<String> {
        val ad = targetDirInput.adapter as? ArrayAdapter<*>
        return (0 until (ad?.count ?: 0)).map { ad?.getItem(it)?.toString() ?: "" }
    }

    /**
     * Clear Selection: resets the whole form — picked files, repository,
     * branch, target folder, commit message and the View-on-GitHub button —
     * and confirms on the status line. Says so when there was nothing to do.
     */
    private fun onClearClicked() {
        holdDoneResult = null
        val hadFiles = tracker.hasSelection()
        val hadAnything = hadFiles || selectedRepo != null ||
                !targetDirInput.text.isNullOrBlank() ||
                !commitMessageInput.text.isNullOrBlank() ||
                viewRepoBtn.visibility == View.VISIBLE

        tracker.clear()
        pickedArchiveFormat = null
        selectedRepo = null
        selectedBranch = null
        repoDropdown.setText("", false)
        branchDropdown.setText("", false)
        targetDirInput.setText("")
        commitMessageInput.setText("")
        // The description collapses back to its hidden state (and its
        // text is cleared), so the next push starts from the same clean
        // form the user saw at launch.
        setDescriptionExpanded(false)
        resetBackdate()
        viewRepoBtn.visibility = View.GONE
        lastCommitUrl = null
        refreshSelectionSummary()

        if (hadAnything) {
            setStatus(getString(R.string.selection_cleared), StatusKind.INFO)
        } else {
            setStatus(getString(R.string.nothing_to_clear), StatusKind.INFO)
        }
    }

    // ------------------------------------------------------------- reload

    /**
     * Reload button: re-fetches the repository list (and the branches of the
     * selected repo) so anything created or deleted on GitHub shows up.
     * If the selected repo or branch was deleted, the selection is cleared
     * and the user is told why.
     */
    private fun onReloadClicked() {
        holdDoneResult = null
        if (client == null) {
            setStatus(getString(R.string.reload_not_connected), StatusKind.INFO)
            return
        }
        setBusy(true)
        setStatus(getString(R.string.reload_in_progress), StatusKind.PROGRESS)
        scope.launch {
            loadRepos(reopenDropdown = false)
            if (alive && selectedRepo != null) {
                val stillThere = repos.firstOrNull { it.fullName == selectedRepo!!.fullName }
                if (stillThere == null) {
                    // The selected repo was deleted on GitHub.
                    selectedRepo = null
                    selectedBranch = null
                    repoDropdown.setText("", false)
                    branchDropdown.setText("", false)
                    branches.clear()
                    updateBranchItems()
                    setStatus(getString(R.string.reload_repo_gone), StatusKind.INFO)
                } else {
                    selectedRepo = stillThere
                    // Re-fetch branches of the still-selected repo.
                    val c = client ?: return@launch
                    setStatus(
                        getString(R.string.branches_loading, stillThere.name),
                        StatusKind.PROGRESS
                    )
                    when (val r = withContext(Dispatchers.IO) {
                        c.listBranches(stillThere.owner, stillThere.name)
                    }) {
                        is GitHubClient.ApiResult.Success -> {
                            branches.clear()
                            branches.addAll(r.data)
                            updateBranchItems()
                            val branchGone = selectedBranch != null &&
                                    branches.none { it.name == selectedBranch!!.name }
                            if (branchGone) {
                                setStatus(getString(R.string.reload_branch_gone), StatusKind.INFO)
                            } else {
                                setStatus(getString(R.string.reload_done, repos.size), StatusKind.SUCCESS)
                            }
                        }
                        is GitHubClient.ApiResult.Error -> {
                            setBusy(false)
                            setStatus(getString(R.string.branches_error, r.message), StatusKind.ERROR)
                        }
                    }
                }
            }
            setBusy(false)
        }
    }

    // ----------------------------------------------------------------- backdate

    /** User-facing format of the chosen author date. */
    internal fun backdateLabel(): String =
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
        ).format(commitAuthorDate!!.time)

    /**
     * ISO-8601 form GitHub expects, e.g. 2024-03-01T14:30:00Z.
     *
     * Deliberately uses SimpleDateFormat (API 1) instead of the modern
     * date-time API: the app runs on devices from API 24 and core-library
     * desugaring is not enabled, so those classes are absent on Android
     * 7.x and would crash the push (the original backdate bug).
     * Must run on the main thread before the push coroutine starts.
     */
    internal fun backdateIso(): String? {
        val cal = commitAuthorDate ?: return null
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(cal.time)
    }

    /** Back to "now"; used after a push and by Clear selection. */
    private fun resetBackdate() {
        commitAuthorDate = null
        backdateBtn.setText(R.string.backdate_now)
    }

    // Test hooks (used by BackdateTest; tiny seams, no logic).
    internal var commitAuthorDateForTest
        get() = commitAuthorDate
        set(value) { commitAuthorDate = value }
    internal fun resetBackdateForTest() = resetBackdate()
    internal fun refreshBackdateButtonForTest() {
        backdateBtn.text = if (commitAuthorDate != null) {
            getString(R.string.backdate_set, backdateLabel())
        } else getString(R.string.backdate_now)
    }
    internal var lastBackdateDialogForTest: android.app.Dialog? = null
    internal fun showBackdateDialogForTest() { showBackdateDialog() }

    /**
     * Popup with date + time pickers and an always-visible warning: the
     * commit will be listed under the chosen date, but it still lands at
     * the top of the branch, the contribution graph counts the real
     * upload time and the committer date stays visible in the details.
     */
    private fun showBackdateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_backdate, null)
        val datePicker = view.findViewById<DatePicker>(R.id.backdateDatePicker)
        val timePicker = view.findViewById<TimePicker>(R.id.backdateTimePicker)

        // Start the pickers from the currently chosen date (if any), else now.
        val start = commitAuthorDate ?: java.util.Calendar.getInstance()
        datePicker.updateDate(
            start.get(java.util.Calendar.YEAR),
            start.get(java.util.Calendar.MONTH),
            start.get(java.util.Calendar.DAY_OF_MONTH)
        )
        @Suppress("DEPRECATION")
        val startHour = start.get(java.util.Calendar.HOUR_OF_DAY)
        @Suppress("DEPRECATION")
        val startMinute = start.get(java.util.Calendar.MINUTE)
        timePicker.hour = startHour
        timePicker.minute = startMinute
        timePicker.setIs24HourView(true)

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.backdate_title)
            .setView(view)
            .setPositiveButton(R.string.backdate_apply) { _, _ ->
                @Suppress("DEPRECATION")
                val cal = java.util.Calendar.getInstance().apply {
                    set(datePicker.year, datePicker.month, datePicker.dayOfMonth,
                        timePicker.hour, timePicker.minute, 0)
                }
                commitAuthorDate = cal
                backdateBtn.text = getString(R.string.backdate_set, backdateLabel())
                setStatus(getString(R.string.backdate_applied_status, backdateLabel()), StatusKind.INFO)
            }
            .setNegativeButton(R.string.backdate_cancel, null)
            .setNeutralButton(R.string.backdate_use_now) { _, _ ->
                resetBackdate()
                setStatus(getString(R.string.backdate_cleared_status), StatusKind.INFO)
            }
            .show()
            .also { lastBackdateDialogForTest = it }
    }

    // ------------------------------------------------------------------- push

    /** Renders the app-scoped push state into this activity instance. */
    private fun renderPushState(st: PushRunner.State) {
        if (!alive || isFinishing) return
        when (st) {
            is PushRunner.State.Running -> {
                applyBusy()
                holdDoneResult = null
                statusText.text = st.job.description
                statusIcon.setImageResource(R.drawable.ic_info)
                statusIcon.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_text))
            }
            is PushRunner.State.Done -> {
                applyBusy()
                holdDoneResult = st
                // The status line is re-rendered by every instance (a
                // recreated screen needs the context), but the
                // presentation EFFECTS — toast, form clear, folder memory
                // — happen exactly once GLOBALLY per result: re-attaching
                // after a rotation must not replay the "Pushed ✓" toast
                // nor re-clear a form the user may have started filling
                // for the next push (v0.2.9).
                val result = st.result
                when (result) {
                    is UploadEngine.PushResult.Success -> {
                        statusText.text = getString(
                            R.string.push_success, result.filesPushed, result.commitUrl
                        )
                        statusIcon.setImageResource(R.drawable.ic_success)
                        statusIcon.imageTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.status_success)
                        )
                        lastCommitUrl = result.commitUrl
                        viewRepoBtn.visibility = View.VISIBLE
                        if (!PushRunner.wasPresented(result)) {
                            PushRunner.markPresented(result)
                            Toast.makeText(this, "Pushed ✓", Toast.LENGTH_SHORT).show()
                            // v0.3.5: record this push in the History
                            // (exactly once per push, with it).
                            runCatching {
                                PushHistoryStore.append(
                                    this,
                                    PushHistoryStore.Entry(
                                        timestampMs = System.currentTimeMillis(),
                                        repoFullName = pushedRepoName,
                                        branch = pushedBranchName,
                                        targetDir = pushedTargetDir,
                                        messageTitle = pushedMessageTitle,
                                        fileCount = result.filesPushed,
                                        files = result.pushedPaths.ifEmpty { pushedFileNames },
                                        commitUrl = result.commitUrl
                                    )
                                )
                            }
                            // The folder just pushed to becomes the top
                            // suggestion for the next push (session-scoped).
                            SessionState.rememberTargetDir(pushedTargetDir)
                            // Clear the picked files, the commit message AND the
                            // backdate so the user starts fresh with the next
                            // upload. The repo / branch selection stays (users
                            // usually push again to the same place).
                            tracker.clear()
        pickedArchiveFormat = null
                            commitMessageInput.setText("")
                            setDescriptionExpanded(false)
                            resetBackdate()
                            refreshSelectionSummary()
                        }
                    }
                    is UploadEngine.PushResult.Failure -> {
                        statusText.text = getString(
                            R.string.push_failed, result.stage, result.message
                        )
                        statusIcon.setImageResource(R.drawable.ic_warning)
                        statusIcon.imageTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.status_error)
                        )
                    }
                }
            }
            PushRunner.State.Idle -> {
                // Nothing running and nothing to show — e.g. right after
                // attach when no push ever started. Keep current UI.
            }
        }
    }

    private fun onPushClicked() {
        holdDoneResult = null
        // Tap-level feedback is a short-lived pop-up, not the status bar
        // (v0.2.9): the bar must stay reserved for the actual push /
        // connection state so it never shows contradictory information.
        if (PushRunner.isBusy()) {
            Toast.makeText(this, getString(R.string.toast_push_in_progress), Toast.LENGTH_SHORT).show()
            return
        }
        val repo = selectedRepo
        val branch = selectedBranch?.name
            ?: selectedRepo?.defaultBranch?.takeIf { it.isNotBlank() }
        val targetDir = targetDirInput.text?.toString()?.trim().orEmpty()
        // Title + blank line + body is the standard Git commit format.
        val title = commitMessageInput.text?.toString()?.trim().orEmpty()
            .ifBlank { "Upload via ZipPush" }
        val body = commitBodyInput.text?.toString()?.trim().orEmpty()
        val msg = if (body.isBlank()) title else "$title\n\n$body"

        if (repo == null) {
            Toast.makeText(this, getString(R.string.toast_pick_repo), Toast.LENGTH_SHORT).show()
            return
        }
        // Snapshot immediately: clearing / re-picking afterwards must not
        // affect the in-flight push.
        val selection = tracker.snapshot()
        if (selection.isEmpty) {
            Toast.makeText(this, getString(R.string.toast_pick_files), Toast.LENGTH_SHORT).show()
            return
        }

        val engine = engine ?: return
        // Capture everything the push needs on the main thread, before the
        // runner hops to IO: the ISO string (no java.time on API 24) and
        // the login for the author fields.
        val authorIso = backdateIso()
        val authorLogin = vault.activeLogin()

        // Remembered when THIS push succeeds (feeds the folder
        // suggestions; captured here because the field may be edited
        // or cleared while the push is in flight).
        pushedTargetDir = targetDir
        // v0.3.5: same-moment capture for the History record.
        pushedRepoName = repo.fullName
        pushedBranchName = branch ?: "main"
        pushedMessageTitle = title
        pushedFileNames = selection.folderFiles.map { it.repoPath }

        // Hand over to the app-scoped runner: the push survives rotation
        // and the recreated activity re-attaches to show the result
        // (v0.2.8 — "goes until the end").
        val started = PushRunner.start(
            context = this,
            engine = engine,
            selection = selection,
            repo = repo,
            branch = branch ?: "main",
            targetDir = targetDir,
            message = msg,
            authorDateIso = authorIso,
            authorLogin = authorLogin
        )
        if (!started) {
            Toast.makeText(this, getString(R.string.toast_push_in_progress), Toast.LENGTH_SHORT).show()
            return
        }
        // The push owns the bar from this instant (the observer re-renders
        // it too, but not before the next looper tick — the bar must never
        // linger on the pre-push message, e.g. "Loaded N repositories…").
        renderPushState(PushRunner.state)
        // Keep the process alive while the upload runs (only legal while
        // the UI is started — see startPushServiceIfAllowed).
        startPushServiceIfAllowed()
    }

    /** Display name of a content Uri (for the selection summary). */
    private fun queryDisplayName(uri: Uri): String? = try {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        name
    } catch (e: Exception) {
        // Providers can throw SecurityException for expired grants or
        // disappear entirely (uninstalled app) — the fallback name is
        // enough; a crash here would break the summary line.
        null
    }

    /**
     * Marks THIS activity's network work. The visible spinner is the OR
     * of this and the app-scoped push (see [applyBusy]) — a flow calling
     * setBusy(false) when it finishes can no longer hide a running
     * push's spinner.
     */
    private fun setBusy(busy: Boolean) {
        netBusy = busy
        applyBusy()
    }
}
