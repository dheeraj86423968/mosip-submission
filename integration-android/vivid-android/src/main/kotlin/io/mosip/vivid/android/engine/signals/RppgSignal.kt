package io.mosip.vivid.android.engine.signals

import io.mosip.vivid.api.SignalScore
import io.mosip.vivid.api.Signals
import kotlin.math.exp

/**
 * S4: remote photoplethysmography. A live face shows a periodic 0.7-4 Hz (42-240 bpm) colour
 * modulation in the skin; prints and masks show none, and screen replays show either none or a
 * frame-rate alias outside the band.
 *
 * Algorithm (mirrors the desktop engine):
 *  - Per frame, mean R/G/B of the forehead+cheek ROI (upper 55% of the tight crop, centre 60%
 *    width) pushed into a ring buffer with its timestamp.
 *  - When >= [minSeconds] of samples exist: resample onto a uniform grid at [fs] Hz, apply CHROM
 *    (X = 3R-2G, Y = 1.5R+G-1.5B, S = X - (sd(X)/sd(Y)) Y) which cancels specular/illumination
 *    changes better than raw green, detrend, Hann window, FFT, band-limit to 0.7-4 Hz.
 *  - SNR = power within +/-0.15 Hz of the band peak / power in the rest of the band.
 *  - attackProbability = 1 - sigmoid((SNR_dB - 2) / 2). Confidence ramps with buffer fill and
 *    is capped at 0.7 because motion (challenges!) corrupts rPPG. Disabled by the engine when
 *    the thermal governor says so.
 */
class RppgSignal(
    private val fs: Double = 15.0,
    private val windowSeconds: Double = 8.0,
    private val minSeconds: Double = 4.0
) : LivenessSignal {
    override val id: String = Signals.RPPG

    private val capacity = (fs * windowSeconds * 2).toInt().coerceAtLeast(64)
    private val t = DoubleArray(capacity); private val r = DoubleArray(capacity); private val g = DoubleArray(capacity); private val b = DoubleArray(capacity)
    private var head = 0; private var count = 0
    private var lastResultFrame = -1L
    private var lastScore: SignalScore? = null

    override fun score(input: SignalInput): SignalScore? {
        val crop = input.faceCrop112
        val x0 = (crop.width * 0.2).toInt(); val x1 = (crop.width * 0.8).toInt()
        val y0 = (crop.height * 0.05).toInt(); val y1 = (crop.height * 0.55).toInt()
        var rs = 0.0; var gs = 0.0; var bs = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { rs += crop.r(x, y); gs += crop.g(x, y); bs += crop.b(x, y); n++ }
        push(input.frame.timestampNanos() / 1e9, rs / n, gs / n, bs / n)

        val span = spanSeconds()
        if (span < minSeconds) return SignalScore.of(id, 0.5, 0.0)
        // Recompute every ~0.5 s; otherwise repeat.
        if (lastScore != null && input.frameIndex - lastResultFrame < (fs / 2).toLong()) return lastScore
        lastResultFrame = input.frameIndex

        val snrDb = snrDb() ?: return SignalScore.of(id, 0.5, 0.0)
        val fill = ((span - minSeconds) / (windowSeconds - minSeconds)).coerceIn(0.0, 1.0)
        val conf = (0.3 + 0.4 * fill)
        val p = 1.0 - 1.0 / (1.0 + exp(-(snrDb - 2.0) / 2.0))
        val s = SignalScore(id, p, conf, false, if (p > 0.6) Signals.ATTACK_PRINT else null)
        lastScore = s
        return s
    }

    private fun push(ts: Double, rv: Double, gv: Double, bv: Double) {
        t[head] = ts; r[head] = rv; g[head] = gv; b[head] = bv
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    private fun idx(i: Int) = (head - count + i + capacity) % capacity
    private fun spanSeconds(): Double = if (count < 2) 0.0 else t[idx(count - 1)] - t[idx(0)]

    private fun snrDb(): Double? {
        val span = minOf(spanSeconds(), windowSeconds)
        val tEnd = t[idx(count - 1)]
        val tStart = tEnd - span
        val m = Fft.nextPowerOfTwo((span * fs).toInt().coerceAtLeast(32))
        val ur = DoubleArray(m); val ug = DoubleArray(m); val ub = DoubleArray(m)
        // Linear resample onto uniform grid.
        var j = 0
        for (i in 0 until m) {
            val tt = tStart + i / fs
            if (tt > tEnd) { ur[i] = ur[i - 1]; ug[i] = ug[i - 1]; ub[i] = ub[i - 1]; continue }
            while (j < count - 2 && t[idx(j + 1)] < tt) j++
            val ta = t[idx(j)]; val tb = t[idx(j + 1)]
            val a = if (tb > ta) ((tt - ta) / (tb - ta)).coerceIn(0.0, 1.0) else 0.0
            ur[i] = r[idx(j)] + a * (r[idx(j + 1)] - r[idx(j)])
            ug[i] = g[idx(j)] + a * (g[idx(j + 1)] - g[idx(j)])
            ub[i] = b[idx(j)] + a * (b[idx(j + 1)] - b[idx(j)])
        }
        // Normalise channels by their mean (CHROM).
        normaliseByMean(ur); normaliseByMean(ug); normaliseByMean(ub)
        val x = DoubleArray(m) { 3 * ur[it] - 2 * ug[it] }
        val y = DoubleArray(m) { 1.5 * ur[it] + ug[it] - 1.5 * ub[it] }
        val alpha = std(x) / std(y).coerceAtLeast(1e-9)
        val s = DoubleArray(m) { x[it] - alpha * y[it] }
        detrend(s)
        val im = DoubleArray(m)
        for (i in 0 until m) s[i] *= 0.5 * (1 - kotlin.math.cos(2 * Math.PI * i / (m - 1)))
        Fft.transform(s, im)
        val ps = Fft.powerSpectrum(s, im)
        val df = fs / m
        val kLo = (0.7 / df).toInt().coerceAtLeast(1); val kHi = (4.0 / df).toInt().coerceAtMost(ps.size - 1)
        if (kHi - kLo < 3) return null
        var peak = kLo
        for (k in kLo..kHi) if (ps[k] > ps[peak]) peak = k
        val halfWidth = (0.15 / df).toInt().coerceAtLeast(1)
        var inBand = 0.0; var rest = 0.0
        for (k in kLo..kHi) if (kotlin.math.abs(k - peak) <= halfWidth) inBand += ps[k] else rest += ps[k]
        if (rest <= 0.0) return null
        return 10 * kotlin.math.log10(inBand / rest)
    }

    private fun normaliseByMean(v: DoubleArray) { val mean = v.average().coerceAtLeast(1e-9); for (i in v.indices) v[i] = v[i] / mean - 1.0 }
    private fun std(v: DoubleArray): Double { val mean = v.average(); var s = 0.0; for (x in v) s += (x - mean) * (x - mean); return kotlin.math.sqrt(s / v.size) }
    private fun detrend(v: DoubleArray) {
        // Remove a linear trend (least squares).
        val n = v.size; val xm = (n - 1) / 2.0; val ym = v.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { num += (i - xm) * (v[i] - ym); den += (i - xm) * (i - xm) }
        val slope = if (den == 0.0) 0.0 else num / den
        for (i in 0 until n) v[i] -= ym + slope * (i - xm)
    }

    override fun reset() { head = 0; count = 0; lastResultFrame = -1; lastScore = null }
}
