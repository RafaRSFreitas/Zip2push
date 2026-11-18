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
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
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
import com.zippush.app.engine.UploadEngine
import com.zippush.app.net.GitHubClient
import com.zippush.app.storage.TokenVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MENU_ABOUT = 100
        private const val MENU_DONATIONS = 101
        private const val PREF_THEME_MODE = "theme_mode"
    }

    // ------------------------------------------------------------------ views
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var accountBtn: MaterialButton
    private lateinit var repoDropdown: MaterialAutoCompleteTextView
    private lateinit var branchDropdown: MaterialAutoCompleteTextView
    private lateinit var targetDirInput: TextInputEditText
    private lateinit var commitMessageInput: TextInputEditText
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

    // ------------------------------------------------------------------ state
    private lateinit var vault: TokenVault
    private var client: GitHubClient? = null
    private var engine: UploadEngine? = null
    private val repos = mutableListOf<GitHubClient.Repo>()
    internal var selectedRepo: GitHubClient.Repo? = null
    private val branches = mutableListOf<GitHubClient.Branch>()
    internal var selectedBranch: GitHubClient.Branch? = null
    internal val tracker = SelectionTracker()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private var accountsDialog: android.app.Dialog? = null

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

        // Apply the persisted theme override before first draw.
        applyTheme(prefThemeMode)
        themeToggleBtn.setOnClickListener { toggleTheme() }

        vault = TokenVault(this)

        // ------------------------------------------------ account interactions
        accountBtn.setOnClickListener { showAccountsPopup() }

        repoDropdown.setOnItemClickListener { _, _, position, _ ->
            scope.launch { onRepoSelected(position) }
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
        branches.clear()
        branchItems.clear()
        selectedRepo = null
        selectedBranch = null
        repoDropdown.setText("", false)
        (repoDropdown.adapter as? ArrayAdapter<*>)?.clear()
        branchDropdown.setText("", false)
        (branchDropdown.adapter as? ArrayAdapter<*>)?.clear()
        updateAccountButton()
        setStatus(getString(R.string.status_ready), StatusKind.INFO)
    }

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

    private suspend fun loadRepos() {
        setBusy(true)
        setStatus("Loading repositories…", StatusKind.PROGRESS)
        val c = client ?: return
        when (val r = withContext(Dispatchers.IO) { c.listRepos() }) {
            is GitHubClient.ApiResult.Success -> {
                repos.clear()
                repos.addAll(r.data)
                repoDropdown.setAdapter(RepoAdapter())
                setBusy(false)
                if (repos.isEmpty()) {
                    setStatus(getString(R.string.repos_error, "no accessible repositories"), StatusKind.INFO)
                } else if (repos.none { it.private }) {
                    setStatus(getString(R.string.repos_loaded_public_only, repos.size), StatusKind.INFO)
                    repoDropdown.showDropDown()
                } else {
                    setStatus(getString(R.string.repos_loaded, repos.size), StatusKind.SUCCESS)
                    repoDropdown.showDropDown()
                }
            }
            is GitHubClient.ApiResult.Error -> {
                setBusy(false)
                setStatus(getString(R.string.repos_error, r.message), StatusKind.ERROR)
            }
        }
    }

    /** Dropdown adapter rendering repo name + Public/Private badge. */
    private inner class RepoAdapter : ArrayAdapter<GitHubClient.Repo>(
        this@MainActivity, R.layout.item_repo, repos
    ) {
        /** Pass-through filter: the dropdown must always list every repo. */
        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val res = FilterResults()
                    res.values = repos.toList()
                    res.count = repos.size
                    return res
                }
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    notifyDataSetChanged()
                }
            }
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_repo, parent, false)
            val repo = getItem(position) ?: return v
            v.findViewById<TextView>(R.id.repoNameText).text = repo.fullName
            val badge = v.findViewById<TextView>(R.id.repoTypeBadge)
            if (repo.private) {
                badge.text = getString(R.string.badge_private)
                badge.setBackgroundResource(R.drawable.bg_badge_private)
            } else {
                badge.text = getString(R.string.badge_public)
                badge.setBackgroundResource(R.drawable.bg_badge_public)
            }
            return v
        }
    }

    private suspend fun onRepoSelected(position: Int) {
        selectedRepo = repos.getOrNull(position) ?: return
        selectedBranch = null
        branchDropdown.setText("", false)
        viewRepoBtn.visibility = View.GONE
        lastCommitUrl = null
        val repo = selectedRepo ?: return
        setBusy(true)
        setStatus("Loading branches of ${repo.name}…", StatusKind.PROGRESS)
        val c = client ?: return
        when (val r = withContext(Dispatchers.IO) { c.listBranches(repo.owner, repo.name) }) {
            is GitHubClient.ApiResult.Success -> {
                branches.clear()
                branches.addAll(r.data)
                updateBranchItems()
                setBusy(false)
                setStatus(
                    getString(R.string.branches_loaded, repo.name, branches.size),
                    StatusKind.SUCCESS
                )
            }
            is GitHubClient.ApiResult.Error -> {
                setBusy(false)
                setStatus(getString(R.string.branches_error, r.message), StatusKind.ERROR)
            }
        }
    }

    /** Rebuilds the branch dropdown model: branches + create action at the end. */
    private fun updateBranchItems() {
        branchItems.clear()
        branches.forEach { branchItems.add(BranchItem.Branch(it)) }
        branchItems.add(BranchItem.CreateAction)
        branchDropdown.setAdapter(BranchAdapter())
        // Preselect the default branch if present.
        val repo = selectedRepo ?: return
        if (branches.isNotEmpty()) {
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
        // Legacy button labels kept in sync for compatibility.
        zipBtn.text = if (zip != null) "ZIP: selected (tap to change)" else getString(R.string.add_zip)
        filesBtn.text = if (files.isNotEmpty()) "Files: ${files.size} (tap to change)" else getString(R.string.add_files)
        folderBtn.text = if (files.isNotEmpty()) "Folder: ${files.size} files (tap to change)" else getString(R.string.add_folder)
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
        viewRepoBtn.visibility = View.GONE
        lastCommitUrl = null
        refreshSelectionSummary()

        if (hadAnything) {
            setStatus(getString(R.string.selection_cleared), StatusKind.INFO)
        } else {
            setStatus(getString(R.string.nothing_to_clear), StatusKind.INFO)
        }
    }

    // ------------------------------------------------------------------- push

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

        setBusy(true)
        setStatus("Preparing files…", StatusKind.PROGRESS)
        scope.launch {
            // Read the snapshot into memory off the main thread.
            val files = withContext(Dispatchers.IO) {
                val list = mutableListOf<UploadEngine.PendingFile>()
                selection.zip?.let { uri ->
                    runCatching {
                        val f = copyUriToCache(uri)
                        list.addAll(engine!!.extractZipEntries(f, skipRootFolder = true))
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
                setBusy(false)
                setStatus(getString(R.string.no_readable_files), StatusKind.ERROR)
                return@launch
            }

            setStatus("Pushing ${files.size} files…", StatusKind.PROGRESS)
            val e = engine ?: return@launch
            val result = withContext(Dispatchers.IO) {
                e.push(
                    owner = repo.owner,
                    repo = repo.name,
                    branch = branch ?: "main",
                    newBranch = null,
                    targetDir = targetDir,
                    commitMessage = msg,
                    files = files
                )
            }
            setBusy(false)
            when (result) {
                is UploadEngine.PushResult.Success -> {
                    setStatus(
                        getString(R.string.push_success, result.filesPushed, result.commitUrl),
                        StatusKind.SUCCESS
                    )
                    // Show the button that opens the commit on GitHub.
                    lastCommitUrl = result.commitUrl
                    viewRepoBtn.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, "Pushed ✓", Toast.LENGTH_SHORT).show()
                }
                is UploadEngine.PushResult.Failure -> {
                    setStatus(
                        getString(R.string.push_failed, result.stage, result.message),
                        StatusKind.ERROR
                    )
                }
            }
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
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        pushBtn.isEnabled = !busy
        zipBtn.isEnabled = !busy
        filesBtn.isEnabled = !busy
        folderBtn.isEnabled = !busy
        clearBtn.isEnabled = !busy
        accountBtn.isEnabled = !busy
    }
}
