package com.fzi.acousticscene.model

/**
 * Enum für die 8 Acoustic Scene Klassen (DCASE 2025)
 * 
 * Neue Klassifikation mit 8 Kategorien statt 10 DCASE-Klassen
 */
enum class SceneClass(val label: String, val labelShort: String, val emoji: String, val index: Int) {
    TRANSIT_VEHICLES("Transit - Fahrzeuge/draußen", "Transit/Fahrzeuge", "🚗", 0),
    URBAN_WAITING("Außen-urban & Transit-Gebäude/Wartezonen", "Urban/Wartezonen", "🏙️", 1),
    NATURE("Außen - naturbetont", "Natur", "🌲", 2),
    SOCIAL("Innen - Soziale Umgebung", "Sozial", "👥", 3),
    WORK("Innen - Arbeitsumgebung", "Arbeit", "💼", 4),
    COMMERCIAL("Innen - Kommerzielle/belebte Umgebung", "Kommerziell", "🛒", 5),
    LEISURE_SPORT("Innen - Freizeit/Sport", "Freizeit/Sport", "⚽", 6),
    CULTURE_QUIET("Innen - Kultur/Freizeit ruhig", "Kultur/Ruhig", "🎭", 7);
    
    companion object {
        /**
         * Gibt die SceneClass für einen gegebenen Index zurück
         */
        fun fromIndex(index: Int): SceneClass? {
            return values().find { it.index == index }
        }
        
        /**
         * Anzahl der Klassen
         */
        const val NUM_CLASSES = 8
    }
}