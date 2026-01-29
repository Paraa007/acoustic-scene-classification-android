package com.fzi.acousticscene.model

/**
 * Enum for the 9 Acoustic Scene Classes (Extended DCASE 2025)
 *
 * Supports both 8-class (standard) and 9-class (experimental) models.
 * The 9th class (LIVING_ROOM) is only used with experimental models.
 */
enum class SceneClass(val label: String, val labelShort: String, val emoji: String, val index: Int) {
    TRANSIT_VEHICLES("Transit - Vehicles/Outdoor", "Transit", "🚗", 0),
    URBAN_WAITING("Urban Outdoor & Waiting Areas", "Urban/Waiting", "🏙️", 1),
    NATURE("Outdoor - Nature", "Nature", "🌲", 2),
    SOCIAL("Indoor - Social Environment", "Social", "👥", 3),
    WORK("Indoor - Work Environment", "Work", "💼", 4),
    COMMERCIAL("Indoor - Commercial/Busy", "Commercial", "🛒", 5),
    LEISURE_SPORT("Indoor - Leisure/Sports", "Leisure/Sports", "⚽", 6),
    CULTURE_QUIET("Indoor - Culture/Quiet", "Culture/Quiet", "🎭", 7),
    LIVING_ROOM("Indoor - Living Room", "Living Room", "🏠", 8);

    companion object {
        /**
         * Returns the SceneClass for a given index
         */
        fun fromIndex(index: Int): SceneClass? {
            return values().find { it.index == index }
        }

        /**
         * Standard number of classes (8 for standard models)
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
