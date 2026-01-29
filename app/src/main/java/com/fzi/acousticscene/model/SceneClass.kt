package com.fzi.acousticscene.model

/**
 * Enum for the Acoustic Scene Classes
 *
 * Supports both 8-class (standard) and 9-class (experimental) models:
 * - Classes 0-7: Standard DCASE 2025 classes
 * - Class 8: Living Room (experimental, only in 9-class models)
 */
enum class SceneClass(val label: String, val labelShort: String, val emoji: String, val index: Int) {
    // German labels as specified - DO NOT TRANSLATE
    TRANSIT_VEHICLES("Transit - Fahrzeuge/draußen", "Transit/Fahrzeuge", "🚗", 0),
    URBAN_WAITING("Außen - urban & Transit-Gebäude/Wartezonen", "Urban/Wartezonen", "🏙️", 1),
    NATURE("Außen - naturbetont", "Naturbetont", "🌲", 2),
    SOCIAL("Innen - Soziale Umgebung", "Sozial", "👥", 3),
    WORK("Innen - Arbeitsumgebung", "Arbeit", "💼", 4),
    COMMERCIAL("Innen - Kommerzielle/ belebte Umgebung", "Kommerziell", "🛒", 5),
    LEISURE_SPORT("Innen - Freizeit/Sport", "Freizeit/Sport", "⚽", 6),
    CULTURE_QUIET("Innen - Kultur/ Freizeit ruhig", "Kultur/Ruhig", "🎭", 7),
    // 9th class for experimental 9-class models only
    LIVING_ROOM("Innen - Wohnbereich", "Wohnbereich", "🏠", 8);

    companion object {
        /**
         * Returns the SceneClass for a given index
         */
        fun fromIndex(index: Int): SceneClass? {
            return entries.find { it.index == index }
        }

        /**
         * Standard number of classes (8)
         */
        const val NUM_CLASSES_STANDARD = 8

        /**
         * Extended number of classes (9) for experimental models
         */
        const val NUM_CLASSES_EXTENDED = 9

        /**
         * Default number of classes (for backward compatibility)
         */
        const val NUM_CLASSES = NUM_CLASSES_STANDARD

        /**
         * Returns the list of classes for a given class count
         */
        fun getClassesForCount(numClasses: Int): List<SceneClass> {
            return entries.filter { it.index < numClasses }
        }

        /**
         * Returns classes for standard 8-class model
         */
        fun getStandardClasses(): List<SceneClass> {
            return getClassesForCount(NUM_CLASSES_STANDARD)
        }

        /**
         * Returns classes for extended 9-class model
         */
        fun getExtendedClasses(): List<SceneClass> {
            return getClassesForCount(NUM_CLASSES_EXTENDED)
        }
    }
}
