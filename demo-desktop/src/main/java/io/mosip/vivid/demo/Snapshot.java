package io.mosip.vivid.demo;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.Workflow;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/** Everything the attack-lab view paints, captured after one frame was fed. Immutable. */
public record Snapshot(
        Workflow workflow,
        String sourceLabel,
        boolean synthetic,
        SessionState state,
        Challenge challenge,
        double actionProgress,
        Map<String, Double> signalScores,
        Map<String, Double> signalWeights,
        double passiveThreshold,
        LivenessResult result,
        BufferedImage frame,
        Rectangle2D faceBox,
        long elapsedMs,
        int framesAnalyzed,
        List<AuditLine> audit,
        String note) {

    /** One audit event as shown in the log panel. */
    public record AuditLine(long elapsedMs, String event, String severity, String detail) {}

    public FailureReason failure() { return state == null ? null : state.failure().orElse(null); }

    /** An empty snapshot for the idle screen. */
    public static Snapshot idle(Workflow w, String sourceLabel, boolean synthetic, String note) {
        return new Snapshot(w, sourceLabel, synthetic, null, null, 0, Map.of(), Map.of(), 0, null, null, null, 0, 0, List.of(), note);
    }

    public Snapshot withNote(String n) {
        return new Snapshot(workflow, sourceLabel, synthetic, state, challenge, actionProgress, signalScores, signalWeights,
                passiveThreshold, result, frame, faceBox, elapsedMs, framesAnalyzed, audit, n);
    }
}
