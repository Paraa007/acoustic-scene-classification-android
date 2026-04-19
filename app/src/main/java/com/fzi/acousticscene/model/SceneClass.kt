package com.fzi.acousticscene.model

/**
 * Enum for the Acoustic Scene Classes
 *
 * 9 Acoustic Scene Classes (DCASE 2025):
 * - Classes 0-7: Original DCASE classes
 * - Class 8: Living Room
 */
enum class SceneClass(val label: String, val labelShort: String, val emoji: String, val index: Int) {
    TRANSIT_VEHICLES("Transit - Vehicles/Outdoor", "Transit/Vehicles", "🚗", 0),
    URBAN_WAITING("Outdoor - Urban & Transit Buildings/Waiting Areas", "Urban/Waiting", "🏙️", 1),
    NATURE("Outdoor - Nature", "Nature", "🌲", 2),
    SOCIAL("Indoor - Social Environment", "Social", "👥", 3),
    WORK("Indoor - Work Environment", "Work", "💼", 4),
    COMMERCIAL("Indoor - Commercial/Busy Environment", "Commercial", "🛒", 5),
    LEISURE_SPORT("Indoor - Leisure/Sports", "Leisure/Sports", "⚽", 6),
    CULTURE_QUIET("Indoor - Culture/Quiet Leisure", "Culture/Quiet", "🎭", 7),
    LIVING_ROOM("Indoor - Living Room", "Living Room", "🏠", 8);

    companion object {
        /**
         * Returns the SceneClass for a given index
         */
        fun fromIndex(index: Int): SceneClass? {
            return entries.find { it.index == index }
        }

        /**
         * Standard number of classes (9)
         */
        const val NUM_CLASSES_STANDARD = 9

        /**
         * Alias (kept for compatibility)
         */
        const val NUM_CLASSES_EXTENDED = 9

        /**
         * Default number of classes
         */
        const val NUM_CLASSES = NUM_CLASSES_STANDARD

        /**
         * Returns the list of classes for a given class count
         */
        fun getClassesForCount(numClasses: Int): List<SceneClass> {
            return entries.filter { it.index < numClasses }
        }

        /**
         * Returns classes for standard 9-class model
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
