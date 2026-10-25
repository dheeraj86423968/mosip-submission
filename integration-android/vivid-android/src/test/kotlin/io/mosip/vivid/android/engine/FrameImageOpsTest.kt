package io.mosip.vivid.android.engine

import io.mosip.vivid.device.FaceFrame
import io.mosip.vivid.device.PixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameImageOpsTest {

    private fun nv21(w: Int, h: Int, y: (Int, Int) -> Int, v: Int = 128, u: Int = 128): FaceFrame {
        val buf = ByteArray(w * h * 3 / 2)
        for (r in 0 until h) for (c in 0 until w) buf[r * w + c] = y(c, r).toByte()
        var i = w * h
        while (i < buf.size) { buf[i] = v.toByte(); buf[i + 1] = u.toByte(); i += 2 }
        return FaceFrame.builder().sequence(1).timestampNanos(1).width(w).height(h).format(PixelFormat.NV21).pixels(buf).build()
    }

    @Test fun neutralChromaNv21IsGrey() {
        val f = nv21(8, 8, { _, _ -> 100 })
        val rgba = FrameImageOps.toRgba(f, null)
        assertEquals(8 * 8 * 4, rgba.remaining())
        assertEquals(100, rgba.get(0).toInt() and 0xff)
        assertEquals(100, rgba.get(1).toInt() and 0xff)
        assertEquals(100, rgba.get(2).toInt() and 0xff)
        assertEquals(255, rgba.get(3).toInt() and 0xff)
    }

    @Test fun redChromaRaisesRed() {
        val f = nv21(4, 4, { _, _ -> 100 }, v = 200)
        val rgb = FrameImageOps.cropRgb(f, 0, 0, 4, 4, 2, 2)
        assertTrue(rgb.r(0, 0) > rgb.g(0, 0) && rgb.r(0, 0) > rgb.b(0, 0))
    }

    @Test fun cropResizesToRequestedSizeAndClipsToFrame() {
        val f = nv21(16, 16, { x, _ -> x * 16 })
        val rgb = FrameImageOps.cropRgb(f, -10, -10, 100, 100, 5, 3)
        assertEquals(5, rgb.width); assertEquals(3, rgb.height); assertEquals(45, rgb.data.size)
        assertTrue("left darker than right", rgb.r(0, 1) < rgb.r(4, 1))
    }

    @Test fun flatImageHasNoSharpnessAndCheckerboardIsSharp() {
        val flat = FrameImageOps.toGray(FrameImageOps.cropRgb(nv21(16, 16, { _, _ -> 128 }), 0, 0, 16, 16, 16, 16))
        assertEquals(0.0, FrameImageOps.laplacianSharpness(flat), 1e-9)
        val checker = FrameImageOps.toGray(FrameImageOps.cropRgb(nv21(16, 16, { x, y -> if ((x + y) % 2 == 0) 20 else 230 }), 0, 0, 16, 16, 16, 16))
        assertEquals(1.0, FrameImageOps.laplacianSharpness(checker), 1e-9)
    }

    @Test fun illuminationQualityPenalisesDarkAndBurntFrames() {
        fun q(level: Int) = FrameImageOps.illuminationQuality(FrameImageOps.Gray(ByteArray(64) { level.toByte() }, 8, 8))
        assertEquals(1.0, q(128), 1e-9)
        assertTrue(q(20) < 0.3)
        assertTrue(q(250) < 0.1)
    }
}
