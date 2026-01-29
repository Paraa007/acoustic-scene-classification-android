package com.fzi.acousticscene.model

/**
 * Enum für die 9 Acoustic Scene Klassen (Extended DCASE 2025)
 *
 * Supports both 8-class (standard) and 9-class (experimental) models.
 * The 9th class (LIVING_ROOM) is only used with experimental models.
 */
enum class SceneClass(val label: String, val labelShort: String, val emoji: String, val index: Int) {
    TRANSIT_VEHICLES("Transit - Fahrzeuge/draußen", "Transit/Fahrzeuge", "🚗", 0),
    URBAN_WAITING("Außen-urban & Transit-Gebäude/Wartezonen", "Urban/Wartezonen", "🏙️", 1),
    NATURE("Außen - naturbetont", "Natur", "🌲", 2),
    SOCIAL("Innen - Soziale Umgebung", "Sozial", "👥", 3),
    WORK("Innen - Arbeitsumgebung", "Arbeit", "💼", 4),
    COMMERCIAL("Innen - Kommerzielle/belebte Umgebung", "Kommerziell", "🛒", 5),
    LEISURE_SPORT("Innen - Freizeit/Sport", "Freizeit/Sport", "⚽", 6),
    CULTURE_QUIET("Innen - Kultur/Freizeit ruhig", "Kultur/Ruhig", "🎭", 7),
    LIVING_ROOM("Innen - Wohnbereich", "Wohnbereich", "🏠", 8);

    companion object {
        /**
         * Gibt die SceneClass für einen gegebenen Index zurück
         */
        fun fromIndex(index: Int): SceneClass? {
            return values().find { it.index == index }
        }

        /**
         * Standard-Anzahl der Klassen (8 for standard models)
         */
        const val NUM_CLASSES = 8

        /**
         * Extended number of classes (9 for experimental models)
         */
        const val NUM_CLASSES_EXTENDED = 9

        /**
         * Get standard 8 classes (excludes LIVING_ROOM)
         */
        fun getStandardClasses(): List<SceneClass> {
            return values().filter { it.index < 8 }
        }

        /**
         * Get all 9 classes (includes LIVING_ROOM)
         */
        fun getExtendedClasses(): List<SceneClass> {
            return values().toList()
        }
    }
}