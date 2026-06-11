package com.fzi.speakerid.ui.diarizationreport

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Port von siqas `src/library/calculations/reduce_dimensions.py` fuer den
 * Mobile-Pfad (`_IS_MOBILE`): dort ist ausschliesslich PCA registriert
 * (`NumpyPCA`, "Leichtgewichtige PCA-Implementierung ohne sklearn
 * (fuer Android/Edge)"); UMAP/t-SNE sind deaktiviert.
 *
 * Ablauf wie `reduce_dimensions(...)`:
 *  1. Zeilenweise L2-Normierung der Eingabe.
 *  2. `NumpyPCA.fit_transform`: zentrieren, Kovarianz (ddof=1, wie `np.cov`),
 *     Eigenzerlegung (hier zyklisches Jacobi statt `np.linalg.eigh`),
 *     Projektion auf die 2 Hauptkomponenten.
 *  3. `normalize_to_unit_square`: Koordinaten auf [0, 1] x [0, 1].
 */
object SnapshotProjection {

    const val TARGET_DIM = 2

    /**
     * Port von `reduce_dimensions(data, method, target_dim=2)` (nur der
     * PCA-Zweig; andere Methoden fallen wie auf Mobile auf PCA zurueck).
     * Liefert n x 2 Koordinaten.
     */
    fun reduceDimensionsPca(data: List<DoubleArray>): Array<DoubleArray> {
        // Python: len(data) < target_dim -> np.random.rand(len(data), 2)
        if (data.size < TARGET_DIM) {
            return Array(data.size) { DoubleArray(TARGET_DIM) { Random.nextDouble() } }
        }

        // Zeilenweise L2-Normierung (np.where(norm > 1e-12, data / norm, 0.0))
        val processed = data.map { row ->
            var sq = 0.0
            for (x in row) sq += x * x
            val norm = sqrt(sq)
            if (norm > 1e-12) DoubleArray(row.size) { row[it] / norm } else DoubleArray(row.size)
        }

        return fitTransformPca(processed, nComponents = minOf(data.size, TARGET_DIM))
    }

    /** Port von `NumpyPCA.fit_transform`. */
    private fun fitTransformPca(x: List<DoubleArray>, nComponents: Int): Array<DoubleArray> {
        val n = x.size
        val d = x[0].size

        // mean_ = np.mean(X, axis=0); X_centered = X - mean_
        val mean = DoubleArray(d)
        for (row in x) for (j in 0 until d) mean[j] += row[j]
        for (j in 0 until d) mean[j] /= n
        val centered = Array(n) { i -> DoubleArray(d) { j -> x[i][j] - mean[j] } }

        // cov_matrix = np.cov(X_centered, rowvar=False) -> (X^T X) / (n - 1)
        val cov = Array(d) { DoubleArray(d) }
        for (row in centered) {
            for (a in 0 until d) {
                val va = row[a]
                if (va == 0.0) continue
                val covA = cov[a]
                for (b in a until d) covA[b] += va * row[b]
            }
        }
        val denom = (n - 1).coerceAtLeast(1).toDouble()
        for (a in 0 until d) {
            for (b in a until d) {
                cov[a][b] /= denom
                cov[b][a] = cov[a][b]
            }
        }

        // eigenvalues/eigenvectors (np.linalg.eigh) via Jacobi, absteigend sortiert
        val (eigenValues, eigenVectors) = jacobiEigenSymmetric(cov)
        val order = eigenValues.indices.sortedByDescending { eigenValues[it] }
        val components = Array(d) { row -> DoubleArray(nComponents) { c -> eigenVectors[row][order[c]] } }

        // np.dot(X_centered, components)
        return Array(n) { i ->
            DoubleArray(nComponents) { c ->
                var sum = 0.0
                for (j in 0 until d) sum += centered[i][j] * components[j][c]
                sum
            }
        }
    }

    /**
     * Zyklisches Jacobi-Verfahren fuer symmetrische Matrizen — Ersatz fuer
     * `np.linalg.eigh`. Liefert (Eigenwerte, Eigenvektoren als Spalten).
     */
    private fun jacobiEigenSymmetric(matrix: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val d = matrix.size
        val a = Array(d) { matrix[it].copyOf() }
        val v = Array(d) { row -> DoubleArray(d) { col -> if (row == col) 1.0 else 0.0 } }

        repeat(MAX_SWEEPS) {
            var off = 0.0
            for (p in 0 until d) for (q in p + 1 until d) off += a[p][q] * a[p][q]
            if (off < EPSILON) return@repeat

            for (p in 0 until d) {
                for (q in p + 1 until d) {
                    val apq = a[p][q]
                    if (abs(apq) < EPSILON) continue
                    val theta = (a[q][q] - a[p][p]) / (2.0 * apq)
                    val t = if (theta >= 0.0) {
                        1.0 / (theta + sqrt(1.0 + theta * theta))
                    } else {
                        -1.0 / (-theta + sqrt(1.0 + theta * theta))
                    }
                    val c = 1.0 / sqrt(1.0 + t * t)
                    val s = t * c

                    for (k in 0 until d) {
                        val akp = a[k][p]
                        val akq = a[k][q]
                        a[k][p] = c * akp - s * akq
                        a[k][q] = s * akp + c * akq
                    }
                    for (k in 0 until d) {
                        val apk = a[p][k]
                        val aqk = a[q][k]
                        a[p][k] = c * apk - s * aqk
                        a[q][k] = s * apk + c * aqk
                    }
                    for (k in 0 until d) {
                        val vkp = v[k][p]
                        val vkq = v[k][q]
                        v[k][p] = c * vkp - s * vkq
                        v[k][q] = s * vkp + c * vkq
                    }
                }
            }
        }

        return DoubleArray(d) { a[it][it] } to v
    }

    /** Port von `normalize_to_unit_square`: 2D-Koordinaten auf [0, 1] x [0, 1]. */
    fun normalizeToUnitSquare(coords: Array<DoubleArray>): Array<DoubleArray> {
        if (coords.isEmpty()) return coords
        var xMin = coords[0][0]
        var xMax = coords[0][0]
        var yMin = coords[0][1]
        var yMax = coords[0][1]
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

    private const val MAX_SWEEPS = 32
    private const val EPSILON = 1e-10
}
