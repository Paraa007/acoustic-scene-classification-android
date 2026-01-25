package com.fzi.acousticscene.model

/**
 * Datenklasse für ein Klassifikations-Ergebnis
 * @param sceneClass Die erkannte Szene
 * @param confidence Die Konfidenz (0.0 - 1.0)
 * @param allProbabilities Alle Wahrscheinlichkeiten für alle Klassen
 * @param inferenceTimeMs Die Inferenz-Zeit in Millisekunden
 * @param timestamp Zeitstempel der Klassifikation
 */
data class ClassificationResult(
    val sceneClass: SceneClass,
    val confidence: Float,
    val allProbabilities: FloatArray,
    val inferenceTimeMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Gibt die Top-N Vorhersagen zurück
     */
    fun getTopPredictions(n: Int = 3): List<Pair<SceneClass, Float>> {
        return allProbabilities
            .mapIndexed { index, prob -> SceneClass.fromIndex(index) to prob }
            .filter { it.first != null }
            .map { it.first!! to it.second }
            .sortedByDescending { it.second }
            .take(n)
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as ClassificationResult
        
        if (sceneClass != other.sceneClass) return false
        if (confidence != other.confidence) return false
        if (!allProbabilities.contentEquals(other.allProbabilities)) return false
        if (inferenceTimeMs != other.inferenceTimeMs) return false
        if (timestamp != other.timestamp) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = sceneClass.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + allProbabilities.contentHashCode()
        result = 31 * result + inferenceTimeMs.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}