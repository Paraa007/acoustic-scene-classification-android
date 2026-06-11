package com.fzi.speakerid.library.pipeline.steps

import com.fzi.speakerid.library.calculations.CosineSimilarity

/**
 * Port von siqas `src/library/pipeline/steps/embedding.py::Embedding`:
 * ein extrahiertes Audio-Embedding samt Metadaten.
 *
 * Der Vektor ist ein 192-dim, L2-normiertes ReDimNet-Embedding (Double, wie
 * Pythons `list[float]` nach `.tolist()`); `null` bei Stille-/Verwurf-Chunks.
 */
class Embedding(
    val embedding: DoubleArray?,
    /** Sekunden Speech in diesem Embedding. */
    val duration: Double,
    /** Start in Sekunden im Original-Audio. */
    val timestamp: Double,
    /** [STATUS_SPEECH] | [STATUS_SILENCE] | [STATUS_DISCARDED] | [STATUS_OVERLAP_DROP]. */
    val status: String,
    /** Pyannote-Sicht (0/1/2/3). */
    val numActiveSpeakers: Int = 0,
    /** Pyannote-Solo-Index bei split-Result. */
    val localSpeakerIdx: Int? = null,
    val meta: MutableMap<String, Any?> = mutableMapOf(),
) {

    /** Port von `Embedding.is_speech`. */
    val isSpeech: Boolean
        get() = status == STATUS_SPEECH && embedding != null

    /** Port von `Embedding.similarity`: normalized cosine zu einem anderen Embedding. */
    fun similarity(other: Embedding): Double = similarity(other.embedding)

    /** Port von `Embedding.similarity`: normalized cosine zu einem rohen Vektor. */
    fun similarity(otherVector: DoubleArray?): Double {
        val ownVector = embedding ?: return 0.0
        if (otherVector == null) return 0.0
        return CosineSimilarity.normalizedCosine(ownVector, otherVector)
    }

    companion object {
        const val STATUS_SPEECH = "speech"
        const val STATUS_SILENCE = "silence"
        const val STATUS_DISCARDED = "discarded"
        const val STATUS_OVERLAP_DROP = "overlap_drop"
    }
}
