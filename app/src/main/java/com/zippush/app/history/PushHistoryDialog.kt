package com.zippush.app.history

import android.content.Intent
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zippush.app.R

/**
 * v0.3.5: the History dialog (menu → History). Lists finished pushes
 * newest-first; tapping a row expands the file list of that push and a
 * button opens the commit on GitHub.
 *
 * Kept as a plain object with one entry point so tests can drive it
 * against a real Activity + Robolectric shadows.
 */
object PushHistoryDialog {

    /** Shows the history for the given activity context. */
    fun show(activity: android.app.Activity) {
        val entries = PushHistoryStore.read(activity).sortedByDescending { it.timestampMs }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_history, null)
        val list = view.findViewById<RecyclerView>(R.id.historyList)
        val empty = view.findViewById<TextView>(R.id.historyEmpty)

        if (entries.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
        } else {
            list.layoutManager = LinearLayoutManager(activity)
            list.adapter = HistoryAdapter(activity, entries)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.history_title)
            .setView(view)
            .setPositiveButton(R.string.history_dialog_dismiss, null)
            .show()
    }

    private class HistoryAdapter(
        private val activity: android.app.Activity,
        private val entries: List<PushHistoryStore.Entry>
    ) : RecyclerView.Adapter<HistoryAdapter.Row>() {

        private val expanded = mutableSetOf<Int>()

        class Row(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val title: TextView = root.getChildAt(0) as TextView
            val meta: TextView = root.getChildAt(1) as TextView
            val details: LinearLayout = root.getChildAt(2) as LinearLayout
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val pad = (parent.context.resources.displayMetrics.density * 16).toInt()
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, pad / 2)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val title = TextView(parent.context).apply {
                textSize = 16f
                setTextAppearance(
                    parent.context,
                    com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                )
            }
            val meta = TextView(parent.context).apply { textSize = 13f }
            val details = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            root.addView(title)
            root.addView(meta)
            root.addView(details)
            return Row(root)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Row, position: Int) {
            val e = entries[position]
            holder.title.text = e.messageTitle.ifBlank {
                activity.getString(R.string.push_to_github)
            }
            val date = DateFormat.getDateFormat(activity).format(e.timestampMs) + " " +
                DateFormat.getTimeFormat(activity).format(e.timestampMs)
            holder.meta.text = "${e.repoFullName} · ${e.branch}\n$date · ${e.fileCount} files"
            val isExpanded = expanded.contains(position)
            holder.details.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.details.removeAllViews()
            if (isExpanded) {
                TextView(activity).apply {
                    textSize = 13f
                    text = activity.getString(R.string.history_files_header)
                    setPadding(0, pad4(), 0, pad4() / 2)
                }.also { holder.details.addView(it) }
                e.files.take(10).forEach { f ->
                    TextView(activity).apply {
                        textSize = 13f
                        text = "  $f"
                    }.also { holder.details.addView(it) }
                }
                if (e.files.size > 10) {
                    TextView(activity).apply {
                        textSize = 13f
                        text = "  … +${e.files.size - 10} more"
                    }.also { holder.details.addView(it) }
                }
                e.commitUrl?.let { url ->
                    MaterialButton(activity).apply {
                        text = activity.getString(R.string.history_view_commit)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, pad4() / 2, 0, pad4() / 2)
                        setOnClickListener {
                            runCatching {
                                activity.startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                )
                            }
                        }
                    }.also { holder.details.addView(it) }
                }
            }
            holder.root.setOnClickListener {
                if (!expanded.add(position)) expanded.remove(position)
                notifyItemChanged(position)
            }
        }

        private fun pad4(): Int =
            (activity.resources.displayMetrics.density * 8).toInt()
    }
}
