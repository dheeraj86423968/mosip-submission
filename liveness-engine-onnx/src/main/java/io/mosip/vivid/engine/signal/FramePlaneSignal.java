package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.device.FaceFrame;

import java.util.Optional;

/**
 * A signal that reads an optional sensor plane of the raw {@link FaceFrame} (depth, IR) in addition to the decoded
 * RGB face context. The analyzer calls {@link #score(FaceContext, FaceFrame)} instead of {@link #score(FaceContext)};
 * an empty result means the plane is absent and the signal abstains: no score is added to the frame analysis, so
 * RGB-only devices see exactly the same analysis as before S8 existed.
 */
public interface FramePlaneSignal extends Signal {
    /** Scores the primary face using the frame's sensor plane, or empty when the plane is absent or unusable. */
    Optional<SignalScore> score(FaceContext ctx, FaceFrame frame);

    /** Without the raw frame there is no plane to read: neutral. */
    @Override
    default SignalScore score(FaceContext ctx) { return Signal.neutral(id()); }
}
