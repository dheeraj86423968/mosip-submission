package io.mosip.vivid.android.engine.signals

import io.mosip.vivid.api.SignalScore
import io.mosip.vivid.api.Signals
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * S3: colour-statistics signal. Prints lose chroma range (ink gamut) and screens shift towards
 * blue with a compressed luminance histogram. Per frame we compute on the tight 112x112 crop:
 *   - mean saturation and saturation spread (HSV)
 *   - hue dispersion (circular std) of skin-like pixels
 *   - blue-to-red ratio
 *   - luminance histogram entropy
 * and combine them with a hand-calibrated logistic. This is a weak signal on purpose
 * (confidence <= 0.5); it exists to catch cheap attacks and to disagree loudly with the CNN
 * when lighting is strange. Smoothed over the last 10 frames.
 */
class ColorStatsSignal(private val window: Int = 10) : LivenessSignal {
    override val id: String = Signals.COLOR_STATS
    private val history = ArrayDeque<Double>()

    override fun score(input: SignalInput): SignalScore {
        val c = input.faceCrop112
        val n = c.width * c.height
        var satSum = 0.0; var satSq = 0.0
        var hueSin = 0.0; var hueCos = 0.0; var skin = 0
        var rSum = 0.0; var bSum = 0.0
        val hist = IntArray(32)
        for (y in 0 until c.height) for (x in 0 until c.width) {
            val r = c.r(x, y); val g = c.g(x, y); val b = c.b(x, y)
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val sat = if (max == 0) 0.0 else (max - min).toDouble() / max
            satSum += sat; satSq += sat * sat
            rSum += r; bSum += b
            hist[((r * 77 + g * 150 + b * 29) shr 8) shr 3]++
            if (max != min) {
                val h = hue(r, g, b, max, min)
                if (sat > 0.15 && r > g && g >= b * 0.8) { hueSin += kotlin.math.sin(h); hueCos += kotlin.math.cos(h); skin++ }
            }
        }
        val satMean = satSum / n
        val satStd = sqrt((satSq / n - satMean * satMean).coerceAtLeast(0.0))
        val hueConc = if (skin == 0) 0.0 else sqrt(hueSin * hueSin + hueCos * hueCos) / skin // 1 = all same hue
        val blueRatio = if (rSum == 0.0) 1.0 else bSum / rSum
        var entropy = 0.0
        for (h in hist) if (h > 0) { val p = h.toDouble() / n; entropy -= p * kotlin.math.ln(p) }
        entropy /= kotlin.math.ln(32.0)

        // Logistic: positives push towards attack. Calibrated on the desktop corpus; see docs/design.
        val z = -1.2 +
            2.5 * (0.28 - satMean).coerceAtLeast(0.0) * 4 +   // low saturation (print)
            1.5 * (0.10 - satStd).coerceAtLeast(0.0) * 10 +   // flat saturation (print/screen)
            2.0 * (blueRatio - 1.05).coerceAtLeast(0.0) * 4 + // blue cast (screen)
            1.5 * (0.70 - entropy).coerceAtLeast(0.0) * 3 +   // compressed histogram (screen)
            1.0 * (hueConc - 0.97).coerceAtLeast(0.0) * 20     // unnaturally uniform skin hue (print)
        val p = 1.0 / (1.0 + exp(-z))
        history.addLast(p)
        if (history.size > window) history.removeFirst()
        val mean = history.average()
        val conf = 0.5 * (history.size.toDouble() / window).coerceIn(0.3, 1.0)
        val cls = if (mean > 0.5) (if (blueRatio > 1.05) Signals.ATTACK_SCREEN else Signals.ATTACK_PRINT) else null
        return SignalScore(id, mean, conf, false, cls)
    }

    private fun hue(r: Int, g: Int, b: Int, max: Int, min: Int): Double {
        val d = (max - min).toDouble()
        val h = when (max) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return h * Math.PI / 3.0
    }

    override fun reset() { history.clear() }
}
