package com.fzi.acousticscene.model

/**
 * Configuration for different model types and their class counts
 *
 * User Mode: Uses standard 8-class model from user_models/
 * Dev Mode: Uses experimental models from dev_models/ (8 or 9 classes)
 */
data class ModelConfig(
    val modelPath: String,
    val modelName: String,
    val numClasses: Int,
    val isDevMode: Boolean
) {
    companion object {
        const val USER_MODELS_DIR = "user_models"
        const val DEV_MODELS_DIR = "dev_models"
        const val DEFAULT_USER_MODEL = "model1.pt"

        /**
         * Creates a User Mode config with the default model
         */
        fun createUserMode(): ModelConfig {
            return ModelConfig(
                modelPath = "$USER_MODELS_DIR/$DEFAULT_USER_MODEL",
                modelName = DEFAULT_USER_MODEL,
                numClasses = 8,
                isDevMode = false
            )
        }

        /**
         * Creates a Dev Mode config for a specific model
         */
        fun createDevMode(modelFileName: String): ModelConfig {
            val numClasses = getClassCountForModel(modelFileName)
            return ModelConfig(
                modelPath = "$DEV_MODELS_DIR/$modelFileName",
                modelName = modelFileName,
                numClasses = numClasses,
                isDevMode = true
            )
        }

        /**
         * Determines the number of classes based on the model filename
         * - model1 or User Mode: 8 classes
         * - model2 or other dev models: 9 classes
         */
        fun getClassCountForModel(modelFileName: String): Int {
            return when {
                modelFileName.contains("model1", ignoreCase = true) -> 8
                modelFileName.contains("model2", ignoreCase = true) -> 9
                else -> 9 // Default to 9 classes for unknown dev models
            }
        }
    }

    /**
     * Returns a display string for the model info
     */
    fun getDisplayInfo(): String {
        return "$modelName ($numClasses Classes)"
    }

    /**
     * Returns the mode display name
     */
    fun getModeDisplayName(): String {
        return if (isDevMode) "Development Mode" else "User Mode"
    }
}
