package com.fzi.acousticscene.model

/**
 * Asset paths and model-metadata helpers. Used as a namespace — no instances
 * are constructed since the wizard redesign keeps the active config in
 * [SessionConfig].
 */
object ModelConfig {
    const val DEV_MODELS_DIR = "dev_models"
    const val DEFAULT_USER_MODEL = "model1.pt"

    /**
     * All current models use 9 classes. Kept as a function so we can swap in
     * filename-based lookups later without changing call sites.
     */
    fun getClassCountForModel(modelFileName: String): Int = 9
}
