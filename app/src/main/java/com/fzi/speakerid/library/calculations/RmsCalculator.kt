package com.fzi.speakerid.library.calculations

import kotlin.math.sqrt

/**
 * RMS wie im siqas-Live-Flow (`generate_golden.py` / LiveProcessor):
 * `sqrt(mean(x.astype(float64) ** 2))` — Akkumulation in Double.
 */
object RmsCalculator {

    /** RMS in Double-Praezision. Leeres Array ergibt 0.0. */
    fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var acc = 0.0
        for (s in samples) {
            val d = s.toDouble()
            acc += d * d
        }
        return sqrt(acc / samples.size)
    }
}
