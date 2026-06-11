package com.fzi.speakerid.ui.embeddings

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fzi.speakerid.ui.SpeakerIdDataManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Port von siqas `gui/services/projection_service.py::ProjectionService` plus
 * dem Mobile-Pfad von `library/calculations/reduce_dimensions.py`:
 *
 *  - Liest Embeddings aus `dm.clusters` (gleiche Flachreihenfolge wie
 *    `EmbeddingsScreen._get_flat_embeddings`: Cluster-Keys String-sortiert),
 *    haengt die echten 192D-Centroids an (kein Unlabeled-Pool) und projiziert
 *    alles gemeinsam auf 2D.
 *  - Auf Android sind wie im Original (`_IS_MOBILE`) UMAP/t-SNE deaktiviert —
 *    jede andere Methode faellt mit Warnung auf PCA zurueck.
 *  - PCA = `NumpyPCA` (mittelwert-zentriert, Kovarianz, Top-2-Eigenvektoren;
 *    hier via Potenz-Iteration mit Re-Orthogonalisierung — Ergebnis bis auf
 *    Vorzeichen/Spiegelung identisch, danach ohnehin auf [0,1]² normiert).
 *  - Ergebnis wird wie `Clock.schedule_once` auf dem Main-Thread in den
 *    DataManager geschrieben (`embedding_coords_2d`, `speaker_positions_2d`).
 *  - Doppelte Aufrufe werden ignoriert ([isProcessing]); bei einer Exception
 *    in der Projektion wird wie im Original KEIN Callback gefeuert.
 */
class SpeakerEmbeddingsProjectionService(private val dm: SpeakerIdDataManager) {

    @Volatile
    var isProcessing: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Startet die Projektion im Hintergrund. Ignoriert doppelte Aufrufe. */
    fun runProjection(onFinishedCallback: (() -> Unit)? = null) {
        if (isProcessing) return
        isProcessing = true
        Thread({ runInBackground(onFinishedCallback) }, "SpeakeridProjection").apply {
            isDaemon = true
            start()
        }
    }

    private fun runInBackground(onFinishedCallback: (() -> Unit)?) {
        val clusters = dm.clustersSnapshot()

        // Gleiche Reihenfolge wie EmbeddingsScreen._get_flat_embeddings()
        val flat = ArrayList<DoubleArray>()
        for (cid in clusters.keys.sorted()) {
            for (emb in clusters.getValue(cid).embeddings) {
                val vec = emb.embedding ?: continue
                flat.add(vec)
            }
        }

        if (flat.size < 3) {
            isProcessing = false
            if (onFinishedCallback != null) mainHandler.post { onFinishedCallback() }
            return
        }

        // Echte 192D-Centroids (nur Cluster mit vorhandenem Centroid, kein Pool)
        val centroidIds = ArrayList<String>()
        val centroidVecs = ArrayList<DoubleArray>()
        for (cid in clusters.keys.sorted()) {
            val cluster = clusters.getValue(cid)
            val centroid = cluster.centroid
            if (!cluster.isUnlabeled && centroid != null) {
                centroidIds.add(cid)
                centroidVecs.add(centroid)
            }
        }

        val nEmbeddings = flat.size
        val allVecs = Array(nEmbeddings + centroidVecs.size) { i ->
            if (i < nEmbeddings) flat[i] else centroidVecs[i - nEmbeddings]
        }

        val coords: Array<DoubleArray>
        try {
            coords = normalizeToUnitSquare(reduceDimensions(allVecs, dm.projectionMethod.value))
        } catch (exc: Exception) {
            Log.e(TAG, "Fehler bei '${dm.projectionMethod.value}': $exc")
            isProcessing = false
            return
        }

        // Embedding-Koordinaten und Centroid-Koordinaten trennen
        val flatCoords = ArrayList<FloatArray>(nEmbeddings)
        for (i in 0 until nEmbeddings) {
            flatCoords.add(floatArrayOf(coords[i][0].toFloat(), coords[i][1].toFloat()))
        }
        val centroidMeans = LinkedHashMap<String, FloatArray>()
        for ((i, cid) in centroidIds.withIndex()) {
            val c = coords[nEmbeddings + i]
            centroidMeans[cid] = floatArrayOf(c[0].toFloat(), c[1].toFloat())
        }

        mainHandler.post {
            dm.embeddingCoords2d.value = flatCoords
            dm.speakerPositions2d.value = centroidMeans
            isProcessing = false
            onFinishedCallback?.invoke()
        }
    }

    // ── Port von `reduce_dimensions` (Mobile: nur PCA registriert) ──────────

    private fun reduceDimensions(data: Array<DoubleArray>, method: String): Array<DoubleArray> {
        if (data.size < 2) {
            return Array(data.size) { doubleArrayOf(Math.random(), Math.random()) }
        }

        // Zeilenweise L2-Normalisierung (Norm-Guard 1e-12 -> Nullvektor)
        val processed = Array(data.size) { i ->
            val row = data[i]
            var sq = 0.0
            for (x in row) sq += x * x
            val norm = sqrt(sq)
            if (norm > 1e-12) DoubleArray(row.size) { row[it] / norm } else DoubleArray(row.size)
        }

        if (method != "PCA") {
            Log.w(TAG, "WARNUNG: Methode '$method' nicht verfügbar. Nutze Fallback: PCA.")
        }
        return pcaFitTransform(processed, minOf(data.size, 2))
    }

    /** Port von `NumpyPCA.fit_transform` (zentrieren, Kovarianz, Top-k-Projektion). */
    private fun pcaFitTransform(x: Array<DoubleArray>, nComponents: Int): Array<DoubleArray> {
        val n = x.size
        val d = x[0].size

        val mean = DoubleArray(d)
        for (row in x) for (j in 0 until d) mean[j] += row[j]
        for (j in 0 until d) mean[j] /= n

        val centered = Array(n) { i -> DoubleArray(d) { j -> x[i][j] - mean[j] } }

        // Kovarianzmatrix (rowvar=False, ddof=1 wie np.cov)
        val denom = (n - 1).coerceAtLeast(1).toDouble()
        val cov = Array(d) { DoubleArray(d) }
        for (row in centered) {
            for (a in 0 until d) {
                val ra = row[a]
                if (ra == 0.0) continue
                val covA = cov[a]
                for (b in a until d) covA[b] += ra * row[b]
            }
        }
        for (a in 0 until d) {
            for (b in a until d) {
                val v = cov[a][b] / denom
                cov[a][b] = v
                cov[b][a] = v
            }
        }

        val components = topEigenvectors(cov, nComponents)
        return Array(n) { i ->
            DoubleArray(components.size) { k ->
                var dot = 0.0
                val comp = components[k]
                val row = centered[i]
                for (j in 0 until d) dot += row[j] * comp[j]
                dot
            }
        }
    }

    /** Top-k-Eigenvektoren via Potenz-Iteration mit Re-Orthogonalisierung. */
    private fun topEigenvectors(cov: Array<DoubleArray>, k: Int): List<DoubleArray> {
        val d = cov.size
        val comps = ArrayList<DoubleArray>(k)
        for (c in 0 until k) {
            // Deterministischer Start (kein Zufall -> reproduzierbar wie random_state)
            var v = DoubleArray(d) { 1.0 / sqrt(d.toDouble()) }
            orthogonalize(v, comps)
            normalize(v)
            for (iter in 0 until POWER_ITERATIONS) {
                val w = DoubleArray(d)
                for (a in 0 until d) {
                    val covA = cov[a]
                    var sum = 0.0
                    for (b in 0 until d) sum += covA[b] * v[b]
                    w[a] = sum
                }
                orthogonalize(w, comps)
                var sq = 0.0
                for (xw in w) sq += xw * xw
                if (sqrt(sq) < 1e-12) break // degeneriert (z. B. Null-Kovarianz): v behalten
                normalize(w)
                var dot = 0.0
                for (j in 0 until d) dot += w[j] * v[j]
                v = w
                if (abs(abs(dot) - 1.0) < 1e-12) break // konvergiert
            }
            comps.add(v)
        }
        return comps
    }

    private fun orthogonalize(v: DoubleArray, basis: List<DoubleArray>) {
        for (b in basis) {
            var proj = 0.0
            for (j in v.indices) proj += v[j] * b[j]
            for (j in v.indices) v[j] -= proj * b[j]
        }
    }

    private fun normalize(v: DoubleArray) {
        var sq = 0.0
        for (x in v) sq += x * x
        val norm = sqrt(sq)
        if (norm > 1e-12) for (j in v.indices) v[j] /= norm
    }

    /** Port von `normalize_to_unit_square`: pro Achse Min-Max auf [0,1]. */
    private fun normalizeToUnitSquare(coords: Array<DoubleArray>): Array<DoubleArray> {
        if (coords.isEmpty()) return coords
        var xMin = Double.POSITIVE_INFINITY
        var xMax = Double.NEGATIVE_INFINITY
        var yMin = Double.POSITIVE_INFINITY
        var yMax = Double.NEGATIVE_INFINITY
        for (c in coords) {
            if (c[0] < xMin) xMin = c[0]
            if (c[0] > xMax) xMax = c[0]
            if (c[1] < yMin) yMin = c[1]
            if (c[1] > yMax) yMax = c[1]
        }
        val eps = 1e-10
        val xRange = xMax - xMin
        val yRange = yMax - yMin
        return Array(coords.size) { i ->
            doubleArrayOf(
                if (xRange > eps) (coords[i][0] - xMin) / xRange else 0.5,
                if (yRange > eps) (coords[i][1] - yMin) / yRange else 0.5,
            )
        }
    }

    companion object {
        private const val TAG = "SpeakeridProjection"
        private const val POWER_ITERATIONS = 300
    }
}
