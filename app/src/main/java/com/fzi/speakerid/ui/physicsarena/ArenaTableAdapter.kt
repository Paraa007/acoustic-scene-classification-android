package com.fzi.speakerid.ui.physicsarena

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.ViewSpeakeridPhysicsArenaRowBinding
import java.util.Locale

/**
 * Zeilen-Datensatz der Legenden-Tabelle - Pendant zum dict aus
 * `PhysicsArenaScreen._on_clusters_updated` (sid_str, time_str, row_color,
 * is_target, percentage, is_active).
 */
data class ArenaRow(
    val sidStr: String,
    val timeStr: String,
    val color: Int,
    val isTarget: Boolean,
    val percentage: Float,
    val isActive: Boolean,
)

/**
 * RecycleView/`ArenaTableRow` -> RecyclerView-Adapter. Die Daten werden wie
 * in Kivy (`rv.data = rows`) komplett ersetzt.
 */
class ArenaTableAdapter : RecyclerView.Adapter<ArenaTableAdapter.RowHolder>() {

    private val items = mutableListOf<ArenaRow>()

    fun submit(rows: List<ArenaRow>) {
        items.clear()
        items.addAll(rows)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class RowHolder(
        val binding: ViewSpeakeridPhysicsArenaRowBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val binding = ViewSpeakeridPhysicsArenaRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return RowHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = items[position]
        val b = holder.binding
        val ctx = b.root.context

        // canvas.before: primary[:3]+[0.1] gerundet, nur wenn is_active
        if (row.isActive) {
            b.root.setBackgroundResource(R.drawable.speakerid_bg_physics_row_active)
        } else {
            b.root.background = null
        }

        // "⁂" beim Target, sonst 12dp-Farbpunkt (opacity-Logik aus dem .kv)
        b.rowMarker.visibility = if (row.isTarget) View.VISIBLE else View.INVISIBLE
        b.rowDot.visibility = if (row.isTarget) View.INVISIBLE else View.VISIBLE
        b.rowDot.backgroundTintList = ColorStateList.valueOf(row.color)

        // Identitaet: bold bei Target/aktiv, primary-Farbe bei aktiv
        b.rowName.text = row.sidStr
        val boldName = row.isTarget || row.isActive
        b.rowName.typeface = ResourcesCompat.getFont(
            ctx,
            if (boldName) R.font.speakerid_dejavu_sans_bold else R.font.speakerid_dejavu_sans,
        )
        b.rowName.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (row.isActive) R.color.speakerid_primary else R.color.speakerid_text_primary,
            ),
        )

        b.rowTime.text = row.timeStr
        // f"{root.percentage:.1f}%"
        b.rowPct.text = String.format(Locale.US, "%.1f%%", row.percentage)
        b.rowBar.setData(row.color, row.percentage)
    }
}
