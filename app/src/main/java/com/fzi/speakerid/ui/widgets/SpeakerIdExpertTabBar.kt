package com.fzi.speakerid.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R

/**
 * Port der Experten-Tab-Leiste aus siqas `src/gui/main.kv`
 * (`tab_bar_container` + `<TabButton@ToggleButton>`):
 *
 *  - In Kivy haengt die Leiste ueber dem ScreenManager und ist nur bei
 *    `app.expert_lab_active` sichtbar. Im Port liegt sie als Widget oben in
 *    den Experten-Screens (Dashboard, Diarization-Report) — die sind nur
 *    ueber "Experten-Labor" (Flag = true) erreichbar, die Leiste ist dort
 *    also wie im Original immer aktiv.
 *  - ToggleButton-Gruppe 'main_tabs' (allow_no_selection: False) ->
 *    [setActiveTab]; `bold: self.state == 'down'` wird per Typeface
 *    nachgezogen, Farbe/Indikator via Selector-Ressourcen.
 *  - Klick auf den bereits aktiven Tab war in Kivy ein No-Op
 *    (`screen_manager.current = <gleicher Screen>`) -> Callback wird nur
 *    bei Tab-Wechsel gefeuert.
 *  - Welle-2-Tabs (Datei-Auswahl/Analyse/2D-Projektion/Performance-Test/
 *    Einstellungen) haben noch keinen Ziel-Screen im Port und sind bis zur
 *    Portierung deaktiviert.
 *  - X-Button: `app.expert_lab_active = False; current = 'main_menu'` ->
 *    [onClose]-Lambda (Flag + Navigation setzt der Fragment-Host).
 */
class SpeakerIdExpertTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Die Tabs in kv-Reihenfolge; Werte = Kivy-Screen-Namen. */
    enum class Tab {
        STATUS,        // 'dashboard'
        EXPLORER,      // 'explorer' (Welle 2)
        PIPELINE,      // 'pipeline' (Welle 2)
        EMBEDDINGS,    // 'embeddings' (Welle 2)
        PERFORMANCE,   // 'performance_test' (Welle 2)
        SETTINGS,      // 'expert_settings' (Welle 2)
        DIARIZATION,   // 'diarization_report'
    }

    /** `on_release: screen_manager.current = ...` — nur bei Tab-Wechsel. */
    var onTabSelected: ((Tab) -> Unit)? = null

    /** X-Button (`expert_lab_active = False` + Ruecksprung 'main_menu'). */
    var onClose: (() -> Unit)? = null

    private val tabViews: Map<Tab, TextView>
    private var activeTab: Tab = Tab.STATUS

    init {
        LayoutInflater.from(context).inflate(R.layout.view_speakerid_expert_tab_bar, this, true)
        // tab_bar_container canvas.before: surface_color
        setBackgroundColor(ContextCompat.getColor(context, R.color.speakerid_surface))

        tabViews = mapOf(
            Tab.STATUS to findViewById(R.id.speakeridExpertTabStatus),
            Tab.EXPLORER to findViewById(R.id.speakeridExpertTabExplorer),
            Tab.PIPELINE to findViewById(R.id.speakeridExpertTabPipeline),
            Tab.EMBEDDINGS to findViewById(R.id.speakeridExpertTabEmbeddings),
            Tab.PERFORMANCE to findViewById(R.id.speakeridExpertTabPerformance),
            Tab.SETTINGS to findViewById(R.id.speakeridExpertTabSettings),
            Tab.DIARIZATION to findViewById(R.id.speakeridExpertTabDiarization),
        )
        for ((tab, view) in tabViews) {
            if (tab in PENDING_WAVE_2) {
                view.isEnabled = false
                continue
            }
            view.setOnClickListener {
                if (tab != activeTab) onTabSelected?.invoke(tab)
            }
        }
        findViewById<TextView>(R.id.speakeridExpertTabClose)
            .setOnClickListener { onClose?.invoke() }

        applyActiveTab()
    }

    /** Kivy: `state: 'down' if screen_manager.current == '<name>' else 'normal'`. */
    fun setActiveTab(tab: Tab) {
        activeTab = tab
        applyActiveTab()
    }

    private fun applyActiveTab() {
        val bold = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)
        val regular = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans)
        for ((tab, view) in tabViews) {
            val selected = tab == activeTab
            view.isSelected = selected
            // kv: `bold: self.state == 'down'`
            view.typeface = if (selected) bold else regular
        }
    }

    /** Hoehe fix dp(48) wie im .kv, sofern das Layout nichts erzwingt. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val forced = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(dp(48f), MeasureSpec.EXACTLY)
        }
        super.onMeasure(widthMeasureSpec, forced)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        /** Screens, die erst in Welle 2 portiert werden. */
        val PENDING_WAVE_2 = setOf(
            Tab.EXPLORER, Tab.PIPELINE, Tab.EMBEDDINGS, Tab.PERFORMANCE, Tab.SETTINGS,
        )
    }
}
