package com.fzi.acousticscene.model

/**
 * Enum for the 8 Acoustic Scene Classes (DCASE 2025)
 *
 * New classification with 8 categories instead of 10 DCASE classes
 */
enum class SceneClass(val label: String, val labelShort: String, val emoji: String, val index: Int) {
    TRANSIT_VEHICLES("Transit - Vehicles/Outdoor", "Transit/Vehicles", "🚗", 0),
    URBAN_WAITING("Outdoor-Urban & Transit Buildings/Waiting", "Urban/Waiting", "🏙️", 1),
    NATURE("Outdoor - Nature", "Nature", "🌲", 2),
    SOCIAL("Indoor - Social Environment", "Social", "👥", 3),
    WORK("Indoor - Work Environment", "Work", "💼", 4),
    COMMERCIAL("Indoor - Commercial/Busy", "Commercial", "🛒", 5),
    LEISURE_SPORT("Indoor - Leisure/Sports", "Leisure/Sports", "⚽", 6),
    CULTURE_QUIET("Indoor - Culture/Quiet Leisure", "Culture/Quiet", "🎭", 7);

    companion object {
        /**
         * Returns the SceneClass for a given index
         */
        fun fromIndex(index: Int): SceneClass? {
            return entries.find { it.index == index }
        }

        /**
         * Number of classes
         */
        const val NUM_CLASSES = 8
    }
}
