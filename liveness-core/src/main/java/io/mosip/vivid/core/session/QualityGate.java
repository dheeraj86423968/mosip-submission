package io.mosip.vivid.core.session;

import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.SessionState;

import java.util.Objects;
import java.util.Optional;

/**
 * Signal S9, the per-frame quality gate: decides whether a frame may be scored and, if not, which positioning
 * hint the UI should show. Frames that fail the gate are never fused and never drive a challenge, so garbage
 * in cannot become a decision.
 *
 * <p>Order of checks: face count (none / several), face position and size from the box, then the engine's
 * quality sub-scores against the policy minimum with the worst sub-score choosing the hint.</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class QualityGate {

    /** Maximum distance of the face centre from the frame centre, normalised. */
    public static final double CENTRE_TOLERANCE = 0.30;
    /** Face box width below which the subject is too far away. */
    public static final double MIN_FACE_WIDTH = 0.15;
    /** Face box width above which the subject is too close. */
    public static final double MAX_FACE_WIDTH = 0.70;
    /** When size quality is the worst sub-score, boxes narrower than this get MOVE_CLOSER, wider get MOVE_BACK. */
    static final double SIZE_HINT_SPLIT = 0.35;

    /**
     * Gate outcome.
     *
     * @param passed whether the frame may be scored
     * @param reason the failure category when not passed (used if the attempt times out while never passing)
     * @param hint   guidance for the UI
     */
    public static final class Verdict {
        private final boolean passed;
        private final FailureReason reason;
        private final SessionState.Hint hint;

        public Verdict(boolean passed, FailureReason reason, SessionState.Hint hint) {
            this.passed = passed;
            this.reason = reason;
            this.hint = hint;
        }

        public boolean passed() { return passed; }
        public FailureReason reason() { return reason; }
        public SessionState.Hint hint() { return hint; }

        static final Verdict OK = new Verdict(true, null, SessionState.Hint.NONE);

        public Optional<FailureReason> reasonOptional() {
            return Optional.ofNullable(reason);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Verdict)) return false;
            Verdict other = (Verdict) o;
            return passed == other.passed
                    && java.util.Objects.equals(reason, other.reason)
                    && java.util.Objects.equals(hint, other.hint);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Boolean.hashCode(passed);
            h = 31 * h + java.util.Objects.hashCode(reason);
            h = 31 * h + java.util.Objects.hashCode(hint);
            return h;
        }

        @Override
        public String toString() {
            return "Verdict[passed=" + passed + ", reason=" + reason + ", hint=" + hint + "]";
        }
    }

    private final double minFaceQuality;

    /** Gate requiring {@link FaceQuality#overall()} of at least {@code minFaceQuality}. */
    public QualityGate(double minFaceQuality) {
        this.minFaceQuality = minFaceQuality;
    }

    /** Evaluates one analysis, including head pose in the quality score. */
    public Verdict evaluate(FrameAnalysis analysis) {
        return evaluate(analysis, false);
    }

    /**
     * Evaluates one analysis.
     *
     * @param ignorePose true while a head-movement challenge is active. The challenge asks the subject to turn or
     *                   tilt their head, and pose quality falls exactly as they comply, so including it would reject
     *                   the frames the challenge needs. Face count, size, centring, sharpness, illumination and
     *                   occlusion still apply.
     */
    public Verdict evaluate(FrameAnalysis analysis, boolean ignorePose) {
        Objects.requireNonNull(analysis, "analysis");
        FaceQuality q = analysis.quality();
        if (q.faceCount() <= 0) {
            return new Verdict(false, FailureReason.FACE_NOT_DETECTED, SessionState.Hint.LOOK_AT_CAMERA);
        }
        if (q.faceCount() > 1) {
            return new Verdict(false, FailureReason.MULTIPLE_FACES, SessionState.Hint.ONLY_ONE_FACE);
        }
        Optional<FaceObservation> faceOpt = analysis.face();
        if (faceOpt.isEmpty()) {
            return new Verdict(false, FailureReason.POOR_FACE_QUALITY, SessionState.Hint.LOOK_AT_CAMERA);
        }
        FaceObservation face = faceOpt.get();
        if (face.boxW() > 0 && face.boxW() < MIN_FACE_WIDTH) {
            return new Verdict(false, FailureReason.POOR_FACE_QUALITY, SessionState.Hint.MOVE_CLOSER);
        }
        if (face.boxW() > MAX_FACE_WIDTH) {
            return new Verdict(false, FailureReason.POOR_FACE_QUALITY, SessionState.Hint.MOVE_BACK);
        }
        if (Math.abs(face.centerX() - 0.5) > CENTRE_TOLERANCE || Math.abs(face.centerY() - 0.5) > CENTRE_TOLERANCE) {
            return new Verdict(false, FailureReason.POOR_FACE_QUALITY, SessionState.Hint.CENTER_FACE);
        }
        double overall = ignorePose
                ? Math.min(Math.min(q.sharpness(), q.illumination()), Math.min(q.occlusion(), q.sizeQuality()))
                : q.overall();
        if (overall < minFaceQuality) {
            return new Verdict(false, FailureReason.POOR_FACE_QUALITY, hintForWorst(q, face));
        }
        return Verdict.OK;
    }

    private static SessionState.Hint hintForWorst(FaceQuality q, FaceObservation face) {
        double worst = q.sizeQuality();
        SessionState.Hint hint = face.boxW() < SIZE_HINT_SPLIT ? SessionState.Hint.MOVE_CLOSER : SessionState.Hint.MOVE_BACK;
        if (q.illumination() < worst) {
            worst = q.illumination();
            hint = SessionState.Hint.IMPROVE_LIGHTING;
        }
        if (q.sharpness() < worst) {
            worst = q.sharpness();
            hint = SessionState.Hint.HOLD_STILL;
        }
        if (q.poseQuality() < worst) {
            worst = q.poseQuality();
            hint = SessionState.Hint.LOOK_AT_CAMERA;
        }
        if (q.occlusion() < worst) {
            hint = SessionState.Hint.LOOK_AT_CAMERA;
        }
        return hint;
    }

    /** Minimum overall quality in force. */
    public double minFaceQuality() {
        return minFaceQuality;
    }
}
