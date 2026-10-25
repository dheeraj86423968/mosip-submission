package io.mosip.vivid.android.engine.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomographyTest {

    private val h = doubleArrayOf(1.05, 0.02, 3.0, -0.01, 0.97, -2.0, 1e-4, -2e-4, 1.0)

    private fun apply(h: DoubleArray, pts: DoubleArray) = DoubleArray(pts.size).also { out ->
        for (i in 0 until pts.size / 2) {
            val x = pts[2 * i]; val y = pts[2 * i + 1]
            val w = h[6] * x + h[7] * y + h[8]
            out[2 * i] = (h[0] * x + h[1] * y + h[2]) / w
            out[2 * i + 1] = (h[3] * x + h[4] * y + h[5]) / w
        }
    }

    private val src = doubleArrayOf(10.0, 10.0, 200.0, 15.0, 190.0, 220.0, 12.0, 205.0, 100.0, 110.0, 60.0, 150.0, 140.0, 40.0)

    @Test fun recoversAPlanarMappingWithNearZeroResidual() {
        val dst = apply(h, src)
        val est = Homography.estimate(src, dst)
        assertNotNull(est)
        est!!
        assertEquals(1.0, est[8], 1e-9)
        assertTrue("residual ${Homography.residual(est, src, dst)}", Homography.residual(est, src, dst) < 1e-6)
    }

    @Test fun nonPlanarMotionLeavesAResidual() {
        val dst = apply(h, src)
        // Move the "nose" (interior points) independently of the plane, as a real head turn does.
        dst[8] += 9.0; dst[10] -= 7.0
        val est = Homography.estimate(src, dst)!!
        assertTrue(Homography.residual(est, src, dst) > 1.0)
    }

    @Test fun tooFewPointsIsDegenerate() {
        assertNull(Homography.estimate(doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 0.0)))
    }
}
