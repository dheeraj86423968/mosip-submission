package io.mosip.vivid.android.perf

/**
 * Decides which frames to analyse so the pipeline never falls behind the camera.
 *
 * Two mechanisms:
 *  1. A fixed cadence from [targetFps] (set by [ThermalGovernor]).
 *  2. Load-adaptive skipping: an EMA of per-frame processing time; when processing takes longer
 *     than the cadence allows, the effective interval stretches to the measured engine time so
 *     frames are dropped and latency stays bounded instead of queueing (CameraX already keeps only
 *     the latest frame, this protects the engine itself).
 *     (An earlier "debt" scheme charged the overrun on every candidate frame, including frames
 *     right after a skip, and throttled to ~65 % of engine capacity; caught by FrameSkipperTest.)
 *
 * Not thread-safe; use from the single analysis thread.
 */
class FrameSkipper(targetFps: Int = 15) {

    @Volatile var targetFps: Int = targetFps
        set(value) { field = value.coerceIn(1, 60) }

    private var lastAcceptedNanos = 0L
    private var emaProcessingNanos = 0.0
    var accepted = 0L; private set
    var skipped = 0L; private set

    /** Returns true if the frame with this capture timestamp should be analysed. */
    fun shouldProcess(frameTimestampNanos: Long): Boolean {
        // The slower of the requested cadence and what the engine has recently managed.
        val interval = maxOf(1_000_000_000.0 / targetFps, emaProcessingNanos)
        val since = frameTimestampNanos - lastAcceptedNanos
        // 0.9: tolerate capture-timestamp jitter so a 30 fps stream really yields 15 fps at target 15.
        if (lastAcceptedNanos != 0L && since < interval * 0.9) { skipped++; return false }
        lastAcceptedNanos = frameTimestampNanos
        accepted++
        return true
    }

    /** Report how long the accepted frame took so the next decision can adapt. */
    fun onProcessed(processingNanos: Long) {
        emaProcessingNanos = if (emaProcessingNanos == 0.0) processingNanos.toDouble()
        else 0.8 * emaProcessingNanos + 0.2 * processingNanos
    }

    fun emaProcessingMillis(): Double = emaProcessingNanos / 1_000_000.0

    fun reset() { lastAcceptedNanos = 0L; emaProcessingNanos = 0.0; accepted = 0; skipped = 0 }
}
