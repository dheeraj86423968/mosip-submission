package io.mosip.vivid.android.engine.signals

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT. Length must be a power of two. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "FFT length must be a power of two" }
        // Bit reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun inverse(re: DoubleArray, im: DoubleArray) {
        for (i in im.indices) im[i] = -im[i]
        transform(re, im)
        val n = re.size.toDouble()
        for (i in re.indices) { re[i] /= n; im[i] = -im[i] / n }
    }

    fun nextPowerOfTwo(n: Int): Int { var p = 1; while (p < n) p = p shl 1; return p }

    /** Power spectrum |X_k|^2 for k in [0, n/2]. */
    fun powerSpectrum(re: DoubleArray, im: DoubleArray): DoubleArray {
        val half = re.size / 2 + 1
        return DoubleArray(half) { k -> re[k] * re[k] + im[k] * im[k] }
    }
}
