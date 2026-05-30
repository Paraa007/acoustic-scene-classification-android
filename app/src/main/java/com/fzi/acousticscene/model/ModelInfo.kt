package com.fzi.acousticscene.model

/**
 * Training and evaluation metadata for a single `.pt` model file.
 * `perClassF1` is indexed by [SceneClass.index].
 */
data class ModelInfo(
    val fileName: String,
    val runId: Int? = null,
    val batchSize: Int,
    val learningRate: Double,
    val weightDecay: Double,
    val dropout: Double,
    val labelSmoothing: Double,
    val valAccuracy: Double,
    val testAccuracy: Double,
    val testBalancedAccuracy: Double,
    val testMacroF1: Double,
    val testWeightedF1: Double,
    val testMacroPrecision: Double,
    val testMacroRecall: Double,
    // Null when the source report didn't include the weighted precision (the
    // grid-search summaries only carry macro precision/recall + weighted F1).
    // Not surfaced in the UI; kept for data completeness.
    val testWeightedPrecision: Double? = null,
    val testWeightedRecall: Double,
    val valTestDiff: Double,
    val bestEpoch: Int,
    val totalEpochs: Int,
    val trainValGap: Double,
    val perClassF1: List<Double>,
    val augmentations: List<String>? = null
)
