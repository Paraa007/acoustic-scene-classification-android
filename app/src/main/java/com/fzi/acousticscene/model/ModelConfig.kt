package com.fzi.acousticscene.model

/**
 * Configuration for different model types and their class counts
 *
 * User Mode: Uses standard 9-class model from user_model/
 * Dev Mode: Uses experimental models from dev_models/ (9 classes)
 */
data class ModelConfig(
    val modelPath: String,
    val modelName: String,
    val numClasses: Int,
    val isDevMode: Boolean
) {
    companion object {
        const val USER_MODEL_DIR = "user_model"  // Singular - matches assets folder name
        const val DEV_MODELS_DIR = "dev_models"  // Plural - matches assets folder name
        const val DEFAULT_USER_MODEL = "model1.pt"

        /**
         * Creates a User Mode config with the default model
         */
        fun createUserMode(): ModelConfig {
            return ModelConfig(
                modelPath = "$USER_MODEL_DIR/$DEFAULT_USER_MODEL",
                modelName = DEFAULT_USER_MODEL,
                numClasses = 9,
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
         * All current models use 9 classes
         */
        fun getClassCountForModel(modelFileName: String): Int {
            return 9
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
