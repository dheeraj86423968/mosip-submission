package io.mosip.vivid.android.engine.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {

    @Test fun pureToneHasItsPowerAtTheToneBin() {
        val n = 64; val bin = 5
        val re = DoubleArray(n) { sin(2 * PI * bin * it / n) }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        val p = Fft.powerSpectrum(re, im)
        assertEquals(n / 2 + 1, p.size)
        assertEquals(bin, p.indices.maxByOrNull { p[it] })
        // |X_k| = n/2 for a unit sine at bin k.
        assertEquals((n / 2.0) * (n / 2.0), p[bin], 1e-6)
    }

    @Test fun inverseRoundTrips() {
        val n = 32
        val orig = DoubleArray(n) { (it * 7 % 11).toDouble() - 3.0 }
        val re = orig.copyOf(); val im = DoubleArray(n)
        Fft.transform(re, im)
        Fft.inverse(re, im)
        for (i in 0 until n) { assertEquals(orig[i], re[i], 1e-9); assertEquals(0.0, im[i], 1e-9) }
    }

    @Test fun rejectsNonPowerOfTwo() {
        assertThrows(IllegalArgumentException::class.java) { Fft.transform(DoubleArray(12), DoubleArray(12)) }
    }

    @Test fun nextPowerOfTwo() {
        assertEquals(1, Fft.nextPowerOfTwo(1))
        assertEquals(128, Fft.nextPowerOfTwo(100))
        assertEquals(256, Fft.nextPowerOfTwo(256))
    }
}
