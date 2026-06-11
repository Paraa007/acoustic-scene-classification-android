package com.fzi.speakerid.ui.explorer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.ViewSpeakeridExplorerRowBinding
import java.io.File

/**
 * Eintrag der Dateiliste — Pendant zu einem Kivy-`FileListEntry`.
 * `file == null` ist der "../"-Eltern-Eintrag der FileChooserListView.
 */
data class SpeakerExplorerEntry(
    val file: File?,
    val isDir: Boolean,
    val displayName: String,
    val sizeText: String,
)

/**
 * FileChooserListView -> RecyclerView-Adapter. Die Eintraege werden wie in
 * Kivy (`_update_files`) komplett ersetzt; die Selektion (Atlas-Highlight
 * 'filechooser_selected') wird ueber die absoluten Pfade gespiegelt.
 */
class SpeakerExplorerFileAdapter(
    private val onEntryTapped: (SpeakerExplorerEntry) -> Unit,
) : RecyclerView.Adapter<SpeakerExplorerFileAdapter.RowHolder>() {

    private val items = mutableListOf<SpeakerExplorerEntry>()
    private val selectedPaths = mutableSetOf<String>()

    fun submit(entries: List<SpeakerExplorerEntry>, selected: Collection<String>) {
        items.clear()
        items.addAll(entries)
        selectedPaths.clear()
        selectedPaths.addAll(selected)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    /** Pendant zu Kivys `selection`-Aenderung (Highlight-Refresh). */
    fun setSelected(selected: Collection<String>) {
        selectedPaths.clear()
        selectedPaths.addAll(selected)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class RowHolder(
        val binding: ViewSpeakeridExplorerRowBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val binding = ViewSpeakeridExplorerRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return RowHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val entry = items[position]
        val b = holder.binding

        b.speakeridExplorerRowName.text = entry.displayName
        b.speakeridExplorerRowSize.text = entry.sizeText
        // Ordner-Icon nur fuer Verzeichnisse/".." (wie im Kivy-Default-Theme)
        b.speakeridExplorerRowIcon.visibility =
            if (entry.isDir) View.VISIBLE else View.INVISIBLE

        val selected = entry.file != null && entry.file.absolutePath in selectedPaths
        if (selected) {
            b.root.setBackgroundResource(R.drawable.speakerid_explorer_row_selected)
        } else {
            b.root.background = null
        }

        b.root.setOnClickListener { onEntryTapped(entry) }
    }
}
