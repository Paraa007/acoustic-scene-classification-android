package com.fzi.speakerid.ui

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R

/**
 * Port der Farb-Logik aus siqas `gui/data/theme.py::ThemeManager` (Theme "FZI",
 * der App-Default) inkl. `get_speaker_color` und der Sprecher-Palette aus
 * `library/config/colors.py::Color._PALETTE`.
 *
 * Die statischen Theme-Farben liegen als Ressourcen in
 * `res/values/speakerid_colors.xml` (speakerid_bg, speakerid_surface, ...).
 */
object SpeakerIdTheme {

    /** `Color._PALETTE` (Index 0..8) als Ressourcen-IDs. */
    private val PALETTE_RES = intArrayOf(
        R.color.speakerid_palette_0,
        R.color.speakerid_palette_1,
        R.color.speakerid_palette_2,
        R.color.speakerid_palette_3,
        R.color.speakerid_palette_4,
        R.color.speakerid_palette_5,
        R.color.speakerid_palette_6,
        R.color.speakerid_palette_7,
        R.color.speakerid_palette_8,
    )

    /**
     * Port von `ThemeManager.get_speaker_color`:
     *  - `null`/"-1"/"0"/0  -> text_secondary (Grau, ungelabelt/unbekannt)
     *  - "1"                -> accent (Gold, Target)
     *  - sonst              -> `_PALETTE[idx % len]`, idx = int(id) bzw.
     *                          Summe der Char-Codes bei nicht-numerischer ID
     */
    @ColorInt
    fun speakerColor(context: Context, speakerId: String?): Int {
        if (speakerId == null || speakerId == "-1" || speakerId == "0") {
            return ContextCompat.getColor(context, R.color.speakerid_text_secondary)
        }
        if (speakerId == "1") {
            return ContextCompat.getColor(context, R.color.speakerid_accent)
        }
        val idx = if (speakerId.isNotEmpty() && speakerId.all { it.isDigit() }) {
            speakerId.toInt()
        } else {
            speakerId.sumOf { it.code }
        }
        return ContextCompat.getColor(context, PALETTE_RES[idx % PALETTE_RES.size])
    }

    /**
     * Kivy-Pressed-Verhalten von `FlatButton._update_colors`:
     * `tuple(min(c * 0.8, 1) for c in bg)` — RGB-Kanaele * 0.8, Alpha bleibt.
     */
    @ColorInt
    fun pressedVariant(@ColorInt color: Int): Int {
        val a = (color ushr 24) and 0xFF
        val r = (((color ushr 16) and 0xFF) * 0.8f).toInt().coerceIn(0, 255)
        val g = (((color ushr 8) and 0xFF) * 0.8f).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * 0.8f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
