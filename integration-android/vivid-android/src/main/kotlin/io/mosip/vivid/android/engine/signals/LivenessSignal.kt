package io.mosip.vivid.android.engine.signals

import io.mosip.vivid.android.engine.FrameImageOps
import io.mosip.vivid.api.Challenge
import io.mosip.vivid.api.FaceObservation
import io.mosip.vivid.api.SignalScore
import io.mosip.vivid.device.FaceFrame

/**
 * What every signal sees for one frame. The face crop is produced once per frame by the engine
 * and shared; signals must not mutate it.
 */
class SignalInput(
    val frame: FaceFrame,
    val face: FaceObservation,
    /** Square crop around the face box, scale factor 1.0 (tight), 112x112 RGB. */
    val faceCrop112: FrameImageOps.Rgb,
    /** Looser crop (scale 2.7, as MiniFASNet expects), 80x80 RGB. */
    val faceCrop80Loose: FrameImageOps.Rgb,
    val activeChallenge: Challenge?,
    val frameIndex: Long
)

/** One anti-spoofing signal. Stateful per session; [reset] clears temporal state only. */
interface LivenessSignal {
    val id: String
    /** Returns null when the signal has nothing to say for this frame (e.g. skipped by cadence). */
    fun score(input: SignalInput): SignalScore?
    fun reset()
    fun close() {}
}
