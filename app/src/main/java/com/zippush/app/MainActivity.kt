package com.zippush.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ArrayAdapter
import android.widget.DatePicker
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
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
import com.zippush.app.engine.PushManager
import com.zippush.app.engine.UploadEngine
import com.zippush.app.net.GitHubClient
import com.zippush.app.storage.TokenVault
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MENU_ABOUT = 100
        private const val MENU_DONATIONS = 101
        private const val PREF_THEME_MODE = "theme_mode"
        private const val STATE_BACKDATE_EPOCH = "backdate_epoch_ms"
    }

    // ------------------------------------------------------------------ views
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var accountBtn: MaterialButton
    private lateinit var repoDropdown: MaterialAutoCompleteTextView
    private lateinit var branchDropdown: MaterialAutoCompleteTextView
    private lateinit var targetDirInput: TextInputEditText
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

    /** Test hook: the current dropdown texts (repo names + trailing ""). */
    internal fun repoItemTextsForTest(): List<String> = repoItems.map { it.toString() }

    /** Test hook: whether the busy overlay (progress) is showing. */
    internal fun isBusyFlagForTest(): Boolean = progress.visibility == View.VISIBLE
    internal val tracker = SelectionTracker()
    private val mainHandler = Handler(Looper.getMainLooper())

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
     * Re-renders PushManager state (running / done) into this activity
     * instance. Re-attached by every recreation, so a push started
     * before a rotation still shows its result afterwards.
     */
    private val pushObserver = PushManager.Observer { st -> renderPushState(st) }

    override fun onDestroy() {
        alive = false
        PushManager.removeObserver(pushObserver)
        // Only the activity's own scope dies here; an in-flight push lives
        // in PushManager and continues in the background (see PushManager).
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
        uri?.let { tracker.pickZip(it); refreshSelectionSummary() }
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

        // Enforce EXACT equality of the Files / Folder buttons. LinearLayout
        // weight rounding can differ by a pixel on odd-width rows; after the
        // row is laid out we clamp both buttons to the same width/height.
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
        // (rotation / background). Re-attach to render its state or result.
        PushManager.addObserver(pushObserver)

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
            try {
                pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
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

        // Auto-connect to the active or pending account on launch.
        val token = vault.loadToken()
        if (token != null) {
            connectWithToken(token)
        } else {
            updateAccountButton()
            setStatus(getString(R.string.status_ready), StatusKind.INFO)
        }
    }

    // ----------------------------------------------------------- status line

    private enum class StatusKind { INFO, PROGRESS, SUCCESS, ERROR }

    /** Shows [message] on the fixed bottom status line. Always visible. */
    private fun setStatus(message: String, kind: StatusKind = StatusKind.INFO) {
        if (!alive) return
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

    /**
     * Clamps the Files / Folder buttons to identical width/height and
     * balances the leftover pixel into the row's outer padding (keeps the
     * pair centered). Weights are dropped once the width is fixed — a
     * LinearLayout re-derives weighted children's width on every measure
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

    /** 3-dot menu (top right): About and Donations. */
    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_ABOUT, 0, R.string.menu_about)
        popup.menu.add(0, MENU_DONATIONS, 1, R.string.menu_donations)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ABOUT -> { showAboutDialog(); true }
                MENU_DONATIONS -> { showDonationsDialog(); true }
                else -> false
            }
        }
        popup.show()
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
            val root = DocumentsContract.getTreeDocumentId(treeUri) // e.g. "primary:Pictures/MyFolder"
            val rootName = root.substringAfterLast('/').ifBlank { "folder" }
            val files = mutableListOf<SelectionTracker.FolderFile>()
            val stack = ArrayDeque(listOf(root))
            while (stack.isNotEmpty()) {
                val dirId = stack.removeLast()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, dirId
                )
                contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0)
                        val name = cursor.getString(1)
                        val mime = cursor.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.addLast(docId)
                        } else {
                            // Repo path: folder-root-relative, forward slashes.
                            val relative = docId.removePrefix(root).trimStart('/')
                            val repoPath = relative.ifBlank { name }
                            files.add(
                                SelectionTracker.FolderFile(
                                    DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
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
        val token = vault.loadAccountToken(login) ?: run {
            setStatus(getString(R.string.token_error, "stored token unreadable"), StatusKind.ERROR)
            return
        }
        vault.setActiveLogin(login)
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
        // dropdown may auto-open again for the fresh account).
        SessionState.clear()
        repoDropdown.setText("", false)
        (repoDropdown.adapter as? ArrayAdapter<*>)?.clear()
        branchDropdown.setText("", false)
        (branchDropdown.adapter as? ArrayAdapter<*>)?.clear()
        updateAccountButton()
        setStatus(getString(R.string.status_ready), StatusKind.INFO)
    }

    /** Test hook: disconnect without touching the UI-less parts. */
    internal fun disconnectForTest() = disconnect()

    /** Test hook: manual reload path. */
    internal fun onReloadClickedForTest() = onReloadClicked()

    // ------------------------------------------------------------------ logic

    private fun connectWithToken(token: String, reopenDialogOnFailure: Boolean = false) {
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
        selectionText.visibility = if (zip == null && files.isEmpty()) View.GONE else View.VISIBLE
        selectionText.text = buildString {
            zip?.let { appendLine("ZIP: ${queryDisplayName(it) ?: "archive.zip"}") }
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
     * Clear Selection: resets the whole form — picked files, repository,
     * branch, target folder, commit message and the View-on-GitHub button —
     * and confirms on the status line. Says so when there was nothing to do.
     */
    private fun onClearClicked() {
        val hadFiles = tracker.hasSelection()
        val hadAnything = hadFiles || selectedRepo != null ||
                !targetDirInput.text.isNullOrBlank() ||
                !commitMessageInput.text.isNullOrBlank() ||
                viewRepoBtn.visibility == View.VISIBLE

        tracker.clear()
        selectedRepo = null
        selectedBranch = null
        repoDropdown.setText("", false)
        branchDropdown.setText("", false)
        targetDirInput.setText("")
        commitMessageInput.setText("")
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

    /**
     * Renders PushManager state into the UI. Called for the live instance
     * and again for every recreated instance (rotation) — that is how a
     * push that started before the rotation still shows "Pushing…" and
     * its final result afterwards.
     */
    private fun renderPushState(st: PushManager.State) {
        if (!alive) return
        when (st) {
            is PushManager.State.Running -> {
                setBusy(true)
                setStatus(st.job.description, StatusKind.PROGRESS)
            }
            is PushManager.State.Done -> {
                setBusy(false)
                when (val result = st.result) {
                    is UploadEngine.PushResult.Success -> {
                        setStatus(
                            getString(R.string.push_success, result.filesPushed, result.commitUrl),
                            StatusKind.SUCCESS
                        )
                        lastCommitUrl = result.commitUrl
                        viewRepoBtn.visibility = View.VISIBLE
                        Toast.makeText(this, "Pushed ✓", Toast.LENGTH_SHORT).show()
                        // Clear the picked files, the commit message AND the
                        // backdate so the user starts fresh with the next
                        // upload. The repo / branch selection stays (users
                        // usually push again to the same place).
                        tracker.clear()
                        commitMessageInput.setText("")
                        resetBackdate()
                        refreshSelectionSummary()
                    }
                    is UploadEngine.PushResult.Failure -> {
                        setStatus(
                            getString(R.string.push_failed, result.stage, result.message),
                            StatusKind.ERROR
                        )
                    }
                }
            }
            PushManager.State.Idle -> {
                // Nothing running and nothing to show — e.g. right after
                // attach when no push ever started. Keep current UI.
            }
        }
    }

    private fun onPushClicked() {
        val repo = selectedRepo
        val branch = selectedBranch?.name
            ?: selectedRepo?.defaultBranch?.takeIf { it.isNotBlank() }
        val targetDir = targetDirInput.text?.toString()?.trim().orEmpty()
        val msg = commitMessageInput.text?.toString()?.trim().orEmpty()
            .ifBlank { "Upload via ZipPush" }

        if (repo == null) {
            setStatus(getString(R.string.pick_repo_first), StatusKind.ERROR)
            return
        }
        // Snapshot immediately: clearing / re-picking afterwards must not
        // affect the in-flight push.
        val selection = tracker.snapshot()
        if (selection.isEmpty) {
            setStatus(getString(R.string.pick_files_first), StatusKind.ERROR)
            return
        }

        // Capture everything the push needs from THIS activity instance on
        // the main thread, before handing over to PushManager: the ISO
        // string (no java.time on API 24 — must not run on the IO path),
        // the login for the author fields, and the content resolver reads.
        val engine = engine ?: return
        val authorIso = backdateIso()
        val authorLogin = vault.activeLogin()
        val description = "Pushing to ${repo.fullName}…"

        val started = PushManager.start(description) {
            // Runs on PushManager's own scope: survives rotation and the
            // activity going to the background.
            val files = withContext(Dispatchers.IO) {
                val list = mutableListOf<UploadEngine.PendingFile>()
                selection.zip?.let { uri ->
                    runCatching {
                        val f = copyUriToCache(uri)
                        list.addAll(engine.extractZipEntries(f, skipRootFolder = true))
                    }.onFailure {
                        mainHandler.post {
                            setStatus("Failed reading ZIP: ${it.message}", StatusKind.ERROR)
                        }
                    }
                }
                for (u in selection.files) {
                    runCatching {
                        val name = queryDisplayName(u) ?: "file${list.size}"
                        val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() }
                        if (bytes != null) list.add(UploadEngine.PendingFile(name, bytes))
                    }
                }
                for (ff in selection.folderFiles) {
                    runCatching {
                        val bytes = contentResolver.openInputStream(ff.uri)?.use { it.readBytes() }
                        if (bytes != null) list.add(UploadEngine.PendingFile(ff.repoPath, bytes))
                    }
                }
                list
            }

            if (files.isEmpty()) {
                return@start UploadEngine.PushResult.Failure(
                    "Prepare", getString(R.string.no_readable_files)
                )
            }

            withContext(Dispatchers.IO) {
                engine.push(
                    owner = repo.owner,
                    repo = repo.name,
                    branch = branch ?: "main",
                    newBranch = null,
                    targetDir = targetDir,
                    commitMessage = msg,
                    files = files,
                    authorDate = authorIso,
                    authorLogin = authorLogin
                )
            }
        }
        if (!started) {
            // A push is already in flight (double tap, or one surviving a
            // rotation). Show the running status instead of starting over.
            setStatus(getString(R.string.push_in_progress), StatusKind.PROGRESS)
        }
    }

    private fun copyUriToCache(uri: Uri): File {
        val target = File(cacheDir, "zippush_upload.zip")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun setBusy(busy: Boolean) {
        if (!alive) return
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        pushBtn.isEnabled = !busy
        zipBtn.isEnabled = !busy
        filesBtn.isEnabled = !busy
        folderBtn.isEnabled = !busy
        clearBtn.isEnabled = !busy
        accountBtn.isEnabled = !busy
    }
}
