package io.mosip.vivid.android.engine.signals

import io.mosip.vivid.android.engine.FrameImageOps
import io.mosip.vivid.api.SignalScore
import io.mosip.vivid.api.Signals
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * S2: moiré / screen-pixel-grid detector. Screens re-photographed by a camera produce periodic
 * interference: narrow peaks in the mid-to-high spatial frequency band of the face crop.
 *
 * Algorithm (same as the desktop engine): grayscale 112x112 crop -> remove DC and apply a Hann
 * window -> row-wise and column-wise 1D FFTs averaged into two spectra -> in the band
 * 0.12..0.45 cycles/pixel compute peak-to-median ratio (log scale). Natural skin has a smooth
 * 1/f spectrum (ratio ~ 1-3); moiré has a sharp line (ratio > 8). Averaged over the last 8 frames.
 */
class MoireSignal(private val window: Int = 8) : LivenessSignal {
    override val id: String = Signals.MOIRE
    private val history = ArrayDeque<Double>()

    override fun score(input: SignalInput): SignalScore {
        val gray = FrameImageOps.toGray(input.faceCrop112)
        val ratio = maxOf(peakiness(gray, rows = true), peakiness(gray, rows = false))
        history.addLast(ratio)
        if (history.size > window) history.removeFirst()
        val mean = history.average()
        // Logistic on log-ratio: ratio 4 -> ~0.27, 6 -> 0.5, 10 -> 0.8
        val p = 1.0 / (1.0 + exp(-(ln(mean) - ln(6.0)) * 3.0))
        val conf = (history.size.toDouble() / window).coerceIn(0.25, 1.0) * 0.8
        return SignalScore(id, p, conf, mean > 14.0 && history.size >= window, if (p > 0.5) Signals.ATTACK_SCREEN else null)
    }

    private fun peakiness(g: FrameImageOps.Gray, rows: Boolean): Double {
        val lines = if (rows) g.height else g.width
        val len = if (rows) g.width else g.height
        val n = Fft.nextPowerOfTwo(len)
        val spectrum = DoubleArray(n / 2 + 1)
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (l in 0 until lines) {
            var mean = 0.0
            for (i in 0 until len) mean += if (rows) g[i, l] else g[l, i]
            mean /= len
            for (i in 0 until n) {
                re[i] = if (i < len) {
                    val v = (if (rows) g[i, l] else g[l, i]) - mean
                    val hann = 0.5 * (1 - kotlin.math.cos(2 * Math.PI * i / (len - 1)))
                    v * hann
                } else 0.0
                im[i] = 0.0
            }
            Fft.transform(re, im)
            val ps = Fft.powerSpectrum(re, im)
            for (k in ps.indices) spectrum[k] += ps[k]
        }
        val lo = (0.12 * n).toInt().coerceAtLeast(2)
        val hi = (0.45 * n).toInt().coerceAtMost(n / 2)
        if (hi - lo < 4) return 1.0
        val band = spectrum.copyOfRange(lo, hi)
        val sorted = band.sorted()
        val median = sorted[sorted.size / 2].coerceAtLeast(1e-9)
        val peak = sorted.last()
        return sqrt(peak / median)
    }

    override fun reset() { history.clear() }
}
