package com.fzi.speakerid.ui.statistics

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridStatisticsBinding
import com.fzi.acousticscene.databinding.ViewSpeakeridStatisticsSpeakerItemBinding
import com.fzi.speakerid.ui.SpeakerIdDataManager
import com.fzi.speakerid.ui.SpeakerIdTheme
import java.util.Locale
import kotlin.math.min

/**
 * Port von siqas `gui/screens/statistics/statistics.py::StatisticsScreen`
 * (+ `statistics.kv`): Post-Session-Auswertung mit SummaryCards, Pie-Chart
 * und Sprecherliste.
 *
 * Lifecycle: `on_enter_screen` -> [onResume] (initialer Statistik-Refresh).
 * `go_back` (Header) geht in siqas explizit zur Arena zurueck — hier
 * Standard-Back, da der Screen aus der Arena geoeffnet wird.
 */
class SpeakerStatisticsFragment : Fragment() {

    private var _binding: FragmentSpeakeridStatisticsBinding? = null
    private val binding get() = _binding!!

    private lateinit var dataManager: SpeakerIdDataManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dataManager = SpeakerIdDataManager.getInstance(requireContext())

        // ModernBackButton -> go_back() (siqas: explizit 'physics_arena')
        binding.speakeridStatisticsBackButton.setOnClickListener {
            findNavController().popBackStack()
        }

        // Kivy-Button: background_color = primary, radius dp(28);
        // Pressed wie FlatButton (RGB * 0.8).
        val primary = ContextCompat.getColor(requireContext(), R.color.speakerid_primary)
        binding.speakeridStatisticsFinishButton.background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedRect(SpeakerIdTheme.pressedVariant(primary)),
            )
            addState(intArrayOf(), roundedRect(primary))
        }
        binding.speakeridStatisticsFinishButton.setOnClickListener { finishAndRestart() }

        // Responsive Groessen aus dem .kv:
        //   LogoBar  logo_width: min(dp(120), root.width * 0.22)
        //   Titel    font_size:  min(sp(24), root.width * 0.06)
        //   Button   font_size:  min(sp(13), root.width * 0.036)
        binding.speakeridStatisticsRoot.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val w = (right - left).toFloat()
            if (w <= 0f) return@addOnLayoutChangeListener
            applyResponsiveSizes(w)
        }
    }

    /** `on_enter_screen` — initialer Refresh der Statistiken. */
    override fun onResume() {
        super.onResume()
        generateStatistics()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Port von `_generate_statistics` ─────────────────────────────────────

    private fun generateStatistics() {
        val dm = dataManager
        val mgr = dm.speakerManager()
        val clusters = dm.clustersSnapshot()
        val allClusters = clusters.values.toList()
        Log.d(TAG, "Generiere Statistik für ${allClusters.size} Cluster.")

        // 1./2. Gesamtsummen (ClusterManager-Analytik)
        val validSpeakers = mgr.getValidSpeakers(minTime = 0.01)
        Log.d(TAG, "Valide Sprecher gefunden: ${validSpeakers.size}")

        val totalValidSec = validSpeakers.sumOf { it.totalTime }
        val totalActiveStr = formatTime(totalValidSec)
        // Alle Cluster ohne Stille (inklusive Pool "0") = globale Dauer
        val totalGlobalSec = allClusters.filter { !it.isSilence }.sumOf { it.totalTime }
        val totalTimeStr = formatTime(totalGlobalSec)
        Log.d(TAG, "Gesamtzeit: $totalTimeStr, Aktivzeit: $totalActiveStr")

        // 3. SummaryCards
        val silenceStr = String.format(Locale.US, "%.1f%%", dm.silencePercentage)
        val identifiedStr = String.format(Locale.US, "%.1f%%", mgr.identifiedRatio)
        binding.speakeridStatisticsCardSpeakersValue.text = validSpeakers.size.toString()
        binding.speakeridStatisticsCardTotalValue.text = totalTimeStr
        binding.speakeridStatisticsCardSilenceValue.text = silenceStr
        binding.speakeridStatisticsCardIdentifiedValue.text = identifiedStr

        // Zusammenfassung neben dem Chart ("Gesamt: ..." / "... Stille | ... erkannt")
        binding.speakeridStatisticsTotalLabel.text =
            getString(R.string.speakerid_statistics_total_fmt, totalTimeStr)
        binding.speakeridStatisticsSummaryLabel.text =
            getString(R.string.speakerid_statistics_summary_fmt, silenceStr, identifiedStr)

        // 4. Tortendiagramm fuettern
        val durations = LinkedHashMap<String, Double>()
        val colors = LinkedHashMap<String, Int>()
        for (c in validSpeakers) {
            durations[c.id] = c.totalTime
            colors[c.id] = SpeakerIdTheme.speakerColor(requireContext(), c.id)
        }
        binding.speakeridStatisticsPieChart.setData(durations, colors)

        // 4.b Daten fuer die Sprecher-Liste
        val rows = validSpeakers.map { c ->
            SpeakerRow(
                name = if (c.isTarget) {
                    getString(R.string.speakerid_statistics_target_name)
                } else {
                    getString(R.string.speakerid_statistics_speaker_name_fmt, c.id)
                },
                timeStr = formatTime(c.totalTime),
                percentage = if (totalValidSec > 0) c.totalTime / totalValidSec * 100.0 else 0.0,
                color = SpeakerIdTheme.speakerColor(requireContext(), c.id),
                isTarget = c.isTarget,
            )
        }
            // 5. Target immer ganz oben, dann nach Redeanteil absteigend
            .sortedWith(compareBy({ !it.isTarget }, { -it.percentage }))

        // 6. Liste neu aufbauen
        val container = binding.speakeridStatisticsListContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        rows.forEachIndexed { index, row ->
            val item = ViewSpeakeridStatisticsSpeakerItemBinding.inflate(inflater, container, false)
            // canvas.before: nur gerade Indizes leicht getoent
            item.root.background = if (index % 2 == 0) {
                ContextCompat.getDrawable(requireContext(), R.drawable.speakerid_statistics_bg_row_even)
            } else {
                null
            }

            item.speakeridStatisticsItemTargetMark.visibility =
                if (row.isTarget) View.VISIBLE else View.GONE
            item.speakeridStatisticsItemColorDot.visibility =
                if (row.isTarget) View.GONE else View.VISIBLE
            (item.speakeridStatisticsItemColorDot.background.mutate() as GradientDrawable)
                .setColor(row.color)

            item.speakeridStatisticsItemName.text = row.name
            // kv: bold nur fuer das Target
            item.speakeridStatisticsItemName.typeface = ResourcesCompat.getFont(
                requireContext(),
                if (row.isTarget) R.font.speakerid_dejavu_sans_bold else R.font.speakerid_dejavu_sans,
            )
            item.speakeridStatisticsItemTime.text = row.timeStr
            item.speakeridStatisticsItemBar.percentage = row.percentage.toFloat()
            item.speakeridStatisticsItemBar.fillColor = row.color
            item.speakeridStatisticsItemPercent.text =
                String.format(Locale.US, "%.1f%%", row.percentage)

            container.addView(item.root)
        }
    }

    // ── Port von `finish_and_restart` ───────────────────────────────────────

    private fun finishAndRestart() {
        val dm = dataManager
        val targetCluster = dm.clusters["1"]

        dm.reset()

        // Target wiederherstellen (Centroid bleibt erhalten, Statistik auf 0)
        if (targetCluster != null) {
            targetCluster.totalTime = 0.0
            targetCluster.occurrences = 0
            targetCluster.embeddings.clear()
            dm.speakerManager().clusters["1"] = targetCluster
            dm.dispatchClusters()
        }

        Log.d(TAG, "Session beendet und zurückgesetzt.")
        // super().go_back() von BaseScreen -> Hauptmenue (liegt im Back-Stack
        // unter target_setup/arena/statistics -> dorthin zurueckspringen,
        // statt ein zweites Menue zu pushen).
        val nav = findNavController()
        if (!nav.popBackStack(R.id.speakeridMenuFragment, false)) {
            nav.navigate(R.id.speakeridMenuFragment)
        }
    }

    // ── Helfer ──────────────────────────────────────────────────────────────

    private fun applyResponsiveSizes(width: Float) {
        val b = _binding ?: return
        b.speakeridStatisticsTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX, min(sp(24f), width * 0.06f),
        )
        b.speakeridStatisticsFinishButton.setTextSize(
            TypedValue.COMPLEX_UNIT_PX, min(sp(13f), width * 0.036f),
        )
        val logoWidth = min(dp(120f), (width * 0.22f).toInt())
        if (b.speakeridStatisticsLogoBar.logoWidth != logoWidth) {
            b.speakeridStatisticsLogoBar.logoWidth = logoWidth
        }
    }

    /** Port von `library/calculations/time_helpers.py::format_time` (MM:SS). */
    private fun formatTime(seconds: Double): String {
        val m = (seconds / 60.0).toInt()
        val s = (seconds % 60.0).toInt()
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun roundedRect(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(28f).toFloat()
        setColor(color)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private data class SpeakerRow(
        val name: String,
        val timeStr: String,
        val percentage: Double,
        val color: Int,
        val isTarget: Boolean,
    )

    private companion object {
        const val TAG = "Statistics"
    }
}
