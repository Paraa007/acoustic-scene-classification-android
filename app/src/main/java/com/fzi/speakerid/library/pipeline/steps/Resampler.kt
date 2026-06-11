package com.fzi.speakerid.library.pipeline.steps

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Soxr-Aequivalent fuer das siqas-Preprocessing
 * (`soxr.resample(waveform.T, current_sr, target_sr)` in `preprocess_audio`).
 *
 * Implementierung: rationaler Polyphase-Resampler mit Kaiser-gefenstertem
 * Sinc-Tiefpass (Upsample um L, Filter, Downsample um M; L/M = outRate/inRate
 * gekuerzt). Der Filter ist linearphasig und um t=0 zentriert, dadurch ist
 * Output-Sample k exakt auf die Eingangszeit k*M/L ausgerichtet — dieselbe
 * Ausrichtung wie der One-Shot-Modus von libsoxr (Signal ausserhalb = 0,
 * Laufzeit kompensiert). Output-Laenge = ceil(n_in * L / M) wie python-soxr.
 *
 * Die Default-Parameter (Daempfung, Passband-Ende) sind am soxr-HQ-Profil
 * orientiert und gegen eine echte soxr-Referenz validiert
 * (ResamplerSoxrParityTest).
 */
class Resampler(
    val inputRate: Int,
    val outputRate: Int,
    /** Stoppband-Daempfung des Kaiser-Designs in dB. */
    attenuationDb: Double = 120.0,
    /** Ende des Passbands als Anteil der Ziel-Nyquist (soxr-HQ: 0.913). */
    passbandEndRatio: Double = 0.913,
) {
    /** Upsample-Faktor L (z. B. 160 fuer 44100->16000). */
    val up: Int
    /** Downsample-Faktor M (z. B. 441 fuer 44100->16000). */
    val down: Int

    private val kernel: DoubleArray // Laenge 2*half+1, Index n+half fuer n in [-half, half]
    private val half: Int

    init {
        require(inputRate > 0 && outputRate > 0) { "Raten muessen > 0 sein" }
        val g = gcd(inputRate, outputRate)
        up = outputRate / g
        down = inputRate / g

        if (up == down) {
            kernel = DoubleArray(0)
            half = 0
        } else {
            // Filterdesign auf der hochgetasteten Rate Fs_up = inputRate * L
            val fsUp = inputRate.toDouble() * up
            val nyqOut = min(inputRate, outputRate) / 2.0
            val passEnd = passbandEndRatio * nyqOut          // Hz
            val stopBegin = nyqOut                            // Hz
            val cutoffHz = 0.5 * (passEnd + stopBegin)
            val transitionHz = stopBegin - passEnd

            val beta = kaiserBeta(attenuationDb)
            val deltaOmega = 2.0 * PI * transitionHz / fsUp
            val taps = ceil((attenuationDb - 7.95) / (2.285 * deltaOmega)).toInt()
            half = (taps / 2).coerceAtLeast(up)

            val f0 = cutoffHz / fsUp // Zyklen pro Upsample-Sample
            val k = DoubleArray(2 * half + 1)
            val i0Beta = besselI0(beta)
            for (n in -half..half) {
                val window = besselI0(beta * sqrt(1.0 - (n.toDouble() / half) * (n.toDouble() / half))) / i0Beta
                k[n + half] = up * 2.0 * f0 * sinc(2.0 * f0 * n) * window
            }
            kernel = k
        }
    }

    /** Resampelt das komplette Signal (One-Shot, Raender als 0 angenommen). */
    fun resample(input: FloatArray): FloatArray {
        if (up == down) return input.copyOf()
        val outLen = outputLength(input.size, inputRate, outputRate)
        val out = FloatArray(outLen)
        val lastIn = input.size - 1
        for (k in 0 until outLen) {
            val t = k.toLong() * down // Position auf dem Upsample-Gitter
            // m-Bereich, fuer den t - m*L im Kernel-Traeger [-half, half] liegt
            var mLo = ceilDiv(t - half, up.toLong())
            var mHi = floorDiv(t + half, up.toLong())
            if (mLo < 0) mLo = 0
            if (mHi > lastIn) mHi = lastIn.toLong()
            var acc = 0.0
            var m = mLo
            var kIdx = (t - mLo * up + half).toInt()
            while (m <= mHi) {
                acc += kernel[kIdx] * input[m.toInt()]
                kIdx -= up
                m++
            }
            out[k] = acc.toFloat()
        }
        return out
    }

    companion object {
        /** Bequemer One-Shot mit Default-Parametern. */
        fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray =
            Resampler(inputRate, outputRate).resample(input)

        /** Output-Laenge wie python-soxr: ceil(n_in * outRate / inRate). */
        fun outputLength(inputLength: Int, inputRate: Int, outputRate: Int): Int {
            val g = gcd(inputRate, outputRate)
            val l = (outputRate / g).toLong()
            val m = (inputRate / g).toLong()
            return ((inputLength.toLong() * l + m - 1) / m).toInt()
        }

        private fun gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while (y != 0) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }

        private fun sinc(x: Double): Double =
            if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Kaiser-Beta nach der Standard-Formel fuer gegebene Daempfung. */
        private fun kaiserBeta(attenuationDb: Double): Double = when {
            attenuationDb > 50.0 -> 0.1102 * (attenuationDb - 8.7)
            attenuationDb >= 21.0 -> 0.5842 * Math.pow(attenuationDb - 21.0, 0.4) + 0.07886 * (attenuationDb - 21.0)
            else -> 0.0
        }

        /** Modifizierte Besselfunktion 1. Art, Ordnung 0 (Reihenentwicklung). */
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val halfX = x / 2.0
            var k = 1
            while (true) {
                val f = halfX / k
                term *= f * f
                sum += term
                if (term < sum * 1e-21) break
                k++
            }
            return sum
        }

        private fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)
        private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
    }
}
