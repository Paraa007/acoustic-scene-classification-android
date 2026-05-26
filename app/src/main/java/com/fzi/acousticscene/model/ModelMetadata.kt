package com.fzi.acousticscene.model

/**
 * Lightweight per-model metadata loaded from `assets/model_metadata.json`.
 * Distinct from [ModelInfo] (which carries the full hyperparameter dump for the
 * Settings → Model Details screen): this is the slim record the wizard needs to
 * render the model row and the "Test acc" badge.
 *
 * `testAccuracy` is null when the JSON entry is missing the value entirely
 * (or when the model has no entry at all). The wizard switches to the red
 * "TEST ACC MISSING" state when this is the case; the model itself stays
 * selectable.
 */
data class ModelMetadata(
    val modelFilename: String,
    val trainingSeconds: Int,
    val testAccuracy: Double?
)
