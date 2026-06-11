package com.fzi.speakerid.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pendant zu siqas `gui/data/theme.py::ThemeManager.ui_theme`
 * (StringProperty, Default "FZI").
 *
 * siqas haelt die Theme-Wahl nur im Prozess-Singleton (keine Persistenz auf
 * Platte — App-Neustart setzt auf "FZI" zurueck); dieses Objekt bildet genau
 * das ab. Werte wie im .kv: "Basic" | "FZI" | "FZI-Dark".
 *
 * Hinweis: Das GUI-Fundament portiert ausschliesslich das Default-Theme "FZI"
 * als statische Ressourcen (`speakerid_colors.xml`) — ein Umschalten der
 * Farben zur Laufzeit ist damit (noch) ohne Wirkung; die Auswahl selbst
 * verhaelt sich aber wie im Original (prozessweit, Screens teilen den Wert).
 */
object SpeakerSettingsState {
    val uiTheme = MutableStateFlow("FZI")
}
