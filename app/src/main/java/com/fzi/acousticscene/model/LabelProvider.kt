package com.fzi.acousticscene.model

/**
 * Dynamic Label Provider for 8 or 9 class classification
 *
 * Determines the class configuration based on model filename:
 * - Logic A (8 Classes): Standard DCASE classes for model1 or User Mode
 * - Logic B (9 Classes): Extended classes with LIVING_ROOM for model2 or experimental models
 */
object LabelProvider {

    /**
     * Scene class data for dynamic class support
     */
    data class DynamicSceneClass(
        val id: String,
        val label: String,
        val labelShort: String,
        val emoji: String,
        val index: Int
    )

    // Standard 8 DCASE classes
    private val STANDARD_8_CLASSES = listOf(
        DynamicSceneClass("TRANSIT_VEHICLES", "Transit - Fahrzeuge/draußen", "Transit/Fahrzeuge", "🚗", 0),
        DynamicSceneClass("URBAN_WAITING", "Außen-urban & Transit-Gebäude/Wartezonen", "Urban/Wartezonen", "🏙️", 1),
        DynamicSceneClass("NATURE", "Außen - naturbetont", "Natur", "🌲", 2),
        DynamicSceneClass("SOCIAL", "Innen - Soziale Umgebung", "Sozial", "👥", 3),
        DynamicSceneClass("WORK", "Innen - Arbeitsumgebung", "Arbeit", "💼", 4),
        DynamicSceneClass("COMMERCIAL", "Innen - Kommerzielle/belebte Umgebung", "Kommerziell", "🛒", 5),
        DynamicSceneClass("LEISURE_SPORT", "Innen - Freizeit/Sport", "Freizeit/Sport", "⚽", 6),
        DynamicSceneClass("CULTURE_QUIET", "Innen - Kultur/Freizeit ruhig", "Kultur/Ruhig", "🎭", 7)
    )

    // Extended 9 classes (8 standard + LIVING_ROOM)
    private val EXTENDED_9_CLASSES = STANDARD_8_CLASSES + DynamicSceneClass(
        id = "LIVING_ROOM",
        label = "Innen - Wohnbereich",
        labelShort = "Wohnbereich",
        emoji = "🏠",
        index = 8
    )

    /**
     * Determines if the model uses 9 classes based on filename
     *
     * @param modelName The model filename (without path, with or without .pt extension)
     * @param isDevMode Whether the app is in development mode
     * @return true if model uses 9 classes, false for 8 classes
     */
    fun isExtendedModel(modelName: String, isDevMode: Boolean): Boolean {
        val name = modelName.lowercase().removeSuffix(".pt")

        // Logic A: Standard 8 classes
        // - model1 or any model in User Mode
        if (name.contains("model1") || !isDevMode) {
            return false
        }

        // Logic B: Extended 9 classes
        // - model2 or any other model in Dev Mode
        return true
    }

    /**
     * Gets the number of classes for the given model
     */
    fun getNumClasses(modelName: String, isDevMode: Boolean): Int {
        return if (isExtendedModel(modelName, isDevMode)) 9 else 8
    }

    /**
     * Gets the class list for the given model
     */
    fun getClasses(modelName: String, isDevMode: Boolean): List<DynamicSceneClass> {
        return if (isExtendedModel(modelName, isDevMode)) {
            EXTENDED_9_CLASSES
        } else {
            STANDARD_8_CLASSES
        }
    }

    /**
     * Gets a class by index for the given model configuration
     */
    fun getClassByIndex(index: Int, modelName: String, isDevMode: Boolean): DynamicSceneClass? {
        val classes = getClasses(modelName, isDevMode)
        return classes.find { it.index == index }
    }

    /**
     * Converts SceneClass enum to DynamicSceneClass
     */
    fun fromSceneClass(sceneClass: SceneClass): DynamicSceneClass {
        return DynamicSceneClass(
            id = sceneClass.name,
            label = sceneClass.label,
            labelShort = sceneClass.labelShort,
            emoji = sceneClass.emoji,
            index = sceneClass.index
        )
    }

    /**
     * Tries to convert DynamicSceneClass to SceneClass enum
     * Returns null for LIVING_ROOM (index 8) as it's not in the enum
     */
    fun toSceneClass(dynamicClass: DynamicSceneClass): SceneClass? {
        return SceneClass.fromIndex(dynamicClass.index)
    }

    /**
     * Gets model info string for UI display
     */
    fun getModelInfoString(modelName: String, isDevMode: Boolean): String {
        val numClasses = getNumClasses(modelName, isDevMode)
        return "Model: $modelName ($numClasses Classes)"
    }

    /**
     * Gets the LIVING_ROOM class (9th class)
     */
    fun getLivingRoomClass(): DynamicSceneClass {
        return EXTENDED_9_CLASSES.last()
    }
}
