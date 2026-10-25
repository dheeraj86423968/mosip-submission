package io.mosip.vivid.android.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSkipperTest {

    private val frame30 = 1_000_000_000L / 30

    @Test fun halvesA30FpsStreamAt15Fps() {
        val s = FrameSkipper(15)
        var t = 1L
        repeat(60) { if (s.shouldProcess(t)) s.onProcessed(5_000_000); t += frame30 }
        assertEquals(30L, s.accepted)
        assertEquals(30L, s.skipped)
    }

    @Test fun skipsExtraFramesWhenTheEngineIsSlowerThanTheCadence() {
        val s = FrameSkipper(15)
        var t = 1L
        // 15 fps cadence = 66.7 ms; engine takes 120 ms per frame.
        repeat(90) { if (s.shouldProcess(t)) s.onProcessed(120_000_000); t += frame30 }
        assertTrue("accepted ${s.accepted}", s.accepted in 20L..29L)
        assertEquals(120.0, s.emaProcessingMillis(), 1e-6)
    }

    @Test fun targetFpsIsClampedAndResetClearsCounters() {
        val s = FrameSkipper(15)
        s.targetFps = 0; assertEquals(1, s.targetFps)
        s.targetFps = 500; assertEquals(60, s.targetFps)
        s.shouldProcess(1L); s.reset()
        assertEquals(0L, s.accepted); assertEquals(0L, s.skipped); assertEquals(0.0, s.emaProcessingMillis(), 0.0)
    }
}
