package com.zippush.app.history

import android.content.res.ColorStateList
import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zippush.app.R
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The History screen (v0.3.3): the list of finished pushes, newest
 * first, opened from the 3-dot menu.
 *
 * What each row shows (from the reference UIs — git log and GitHub's
 * Activity view — see PushHistoryStore's header):
 *  - the commit subject (message title) and a success/failure icon;
 *  - relative time ("2 h ago"), the most scannable date format;
 *  - repo · branch · target folder;
 *  - author, file count, size and the abbreviated SHA (%h);
 *  - a "See changes on GitHub" affordance (GitHub's "Compare changes").
 *
 * Tapping a row expands the detail: the file list of that push (git
 * log's --name-only) plus the "See changes on GitHub" button (failures
 * show the reason instead). Everything the user needs to review what a
 * push did, without leaving the app.
 */
object PushHistoryDialog {

    /** Placeholder for the link line, swapped for the localized label. */
    private const val LINK_MARKER = "\u0000LINK\u0000"

    /** Opens the history dialog; returns its content view (test seam
     *  for row assertions). [onCleared] runs after a successful Clear. */
    fun show(context: Context, onCleared: () -> Unit = {}): View {
        val entries = PushHistoryStore.read(context)
        val root = LayoutInflater.from(context)
            .inflate(R.layout.dialog_push_history, null)
        val list = root.findViewById<RecyclerView>(R.id.historyList)
        val emptyText = root.findViewById<TextView>(R.id.historyEmptyText)

        if (entries.isEmpty()) {
            list.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            emptyText.text = context.getString(R.string.history_empty)
        } else {
            val adapter = HistoryAdapter(entries)
            list.layoutManager = LinearLayoutManager(context)
            list.adapter = adapter
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.history_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .apply {
                if (entries.isNotEmpty()) {
                    setNeutralButton(R.string.history_clear, null)
                }
            }
            .show()

        // Overridden after show() so a tap NEVER dismisses the dialog
        // (the built-in behavior closes it before the confirm is seen).
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
            ?.setOnClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.history_clear_confirm_title)
                    .setMessage(R.string.history_clear_confirm_text)
                    .setPositiveButton(R.string.history_delete) { _, _ ->
                        PushHistoryStore.clear(context)
                        dialog.dismiss()
                        onCleared()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }

        return root
    }

    /** Newest-first list of pushes with one expanded row at most. */
    private class HistoryAdapter(
        private val entries: List<HistoryEntry>
    ) : RecyclerView.Adapter<HistoryAdapter.Row>() {

        /** Index of the expanded detail row (-1 = none). */
        private var expanded = -1

        class Row(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.historyStatusIcon)
            val title: TextView = v.findViewById(R.id.historyTitleText)
            val time: TextView = v.findViewById(R.id.historyTimeText)
            val repo: TextView = v.findViewById(R.id.historyRepoText)
            val meta: TextView = v.findViewById(R.id.historyMetaText)
            val detail: TextView = v.findViewById(R.id.historyDetailText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_push_history, parent, false)
            return Row(v)
        }

        override fun getItemCount(): Int = entries.size

        @SuppressLint("NotifyDataSetChanged")
        override fun onBindViewHolder(holder: Row, position: Int) {
            val e = entries[position]
            val ctx = holder.itemView.context

            holder.title.text = e.messageTitle.ifBlank { "—" }
            holder.time.text = relativeTime(e.timestamp)
            holder.repo.text = buildString {
                append(e.repoFullName)
                append(" · ")
                append(e.branch)
                append(" · ")
                append(
                    if (e.targetDir.isBlank()) ctx.getString(R.string.history_root)
                    else e.targetDir
                )
            }
            holder.meta.text = buildString {
                if (e.authorLogin.isNotBlank()) {
                    append(ctx.getString(R.string.history_author, e.authorLogin))
                    append(" · ")
                }
                append(
                    ctx.getString(
                        if (e.files.size == 1) R.string.history_one_file
                        else R.string.history_files_count, e.files.size
                    )
                )
                if (e.bytesPushed > 0) {
                    append(" · ")
                    append(formatBytes(e.bytesPushed))
                }
                if (e.success) {
                    e.shortSha()?.let {
                        append(" · ")
                        append(it)
                    }
                } else {
                    append(" · ")
                    append(ctx.getString(R.string.history_failed))
                }
            }

            val (iconRes, tintRes) = if (e.success) {
                R.drawable.ic_success to R.color.status_success
            } else {
                R.drawable.ic_warning to R.color.status_error
            }
            holder.icon.setImageResource(iconRes)
            holder.icon.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, tintRes))

            // ---- detail (expanded) ----
            if (expanded == position) {
                holder.detail.visibility = View.VISIBLE
                renderDetail(ctx, holder.detail, e)
            } else {
                holder.detail.visibility = View.GONE
            }
            holder.itemView.setOnClickListener {
                expanded = if (expanded == position) -1 else position
                notifyDataSetChanged()
            }
        }

        /**
         * The expanded body: the file list (git log --name-only style)
         * and, for successes, the "See changes on GitHub" link — GitHub
         * Activity's "Compare changes" affordance. Failures show the
         * reason instead.
         */
        private fun renderDetail(
            ctx: Context,
            detail: TextView,
            e: HistoryEntry
        ) {
            val body = buildString {
                if (e.success) {
                    appendLine(ctx.getString(R.string.history_files_header))
                    val maxShown = 8
                    e.files.take(maxShown).forEach { appendLine(it) }
                    if (e.files.size > maxShown) {
                        appendLine(
                            ctx.getString(
                                R.string.history_show_all_files, e.files.size
                            )
                        )
                    }
                    if (e.files.isEmpty()) appendLine("—")
                    appendLine()
                    append(LINK_MARKER)
                } else {
                    e.failReason?.let { append(it) }
                }
            }
            if (e.success && e.commitUrl != null) {
                // The whole tail line is the link (bigger tap target on
                // a phone than the text itself).
                val linkText = ctx.getString(R.string.history_see_changes)
                val span = android.text.SpannableString(
                    body.replace(LINK_MARKER, linkText)
                )
                val start = span.indexOf(linkText)
                span.setSpan(
                    object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: View) {
                            openUrl(ctx, e.commitUrl)
                        }
                    },
                    start, start + linkText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                detail.text = span
                detail.movementMethod =
                    android.text.method.LinkMovementMethod.getInstance()
                detail.highlightColor =
                    android.graphics.Color.TRANSPARENT
                detail.setOnClickListener {
                    openUrl(ctx, e.commitUrl)
                }
            } else {
                detail.text = body.replace(LINK_MARKER, "")
                detail.setOnClickListener(null)
            }
        }

        private fun openUrl(ctx: Context, url: String) {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    )
                )
            }
        }
    }

    /** git log-style relative time (most scannable, per the references). */
    internal fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(now - then)
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ago"
            minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d ago"
            else -> "${minutes / (60 * 24 * 30)}mo ago"
        }
    }

    /** 1.2 kB / 3.4 MB style, ASCII units (repo-safe across locales). */
    internal fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f kB", kb)
        return String.format(Locale.US, "%.1f MB", kb / 1024.0)
    }
}
