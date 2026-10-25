package io.mosip.vivid.demo;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.engine.onnx.EngineConfig;
import io.mosip.vivid.engine.onnx.OnnxEngineFactory;
import io.mosip.vivid.engine.onnx.OnnxLivenessEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/** Locates the ONNX models, builds the engine and the demo policies. */
public final class DemoSetup {
    /**
     * YuNet threshold for the mock's drawn faces. YuNet is trained on photographs and scores cartoon faces near its
     * boundary; production keeps 0.7. Same value and reasoning as e2e-tests' SyntheticSubjectEngine.
     */
    public static final double SYNTHETIC_DETECTOR_THRESHOLD = 0.35;
    /** Signals that cannot work on drawn frames and are switched off in the synthetic demo policy. */
    public static final List<String> SYNTHETIC_DISABLED_SIGNALS = List.of(Signals.RPPG, Signals.PARALLAX);

    private DemoSetup() {}

    /** --models flag, then MOSIP_VIVID_MODELS / vivid.models, then ../liveness-engine-onnx/models and ./liveness-engine-onnx/models. */
    public static Optional<Path> findModels(String flag) {
        List<Path> candidates = new ArrayList<>();
        if (flag != null) candidates.add(Path.of(flag));
        String env = System.getenv(OnnxEngineFactory.MODELS_ENV);
        if (env != null && !env.isBlank()) candidates.add(Path.of(env));
        String prop = System.getProperty(OnnxEngineFactory.MODELS_PROPERTY);
        if (prop != null && !prop.isBlank()) candidates.add(Path.of(prop));
        candidates.add(Path.of("..", "liveness-engine-onnx", "models"));
        candidates.add(Path.of("liveness-engine-onnx", "models"));
        for (Path p : candidates) {
            if (Files.isDirectory(p) && Files.exists(p.resolve("face_detection_yunet_2023mar.onnx"))) return Optional.of(p.toAbsolutePath().normalize());
        }
        return flag == null ? OnnxEngineFactory.findModelDirectory() : Optional.empty();
    }

    /** The production engine; with {@code detectorThreshold} > 0 the YuNet threshold is overridden. */
    public static OnnxLivenessEngine engine(Path models, double detectorThreshold) {
        EngineConfig.Builder b = OnnxEngineFactory.discover(models);
        if (detectorThreshold > 0) b.detectorThresholds(detectorThreshold, 0.3);
        return new OnnxLivenessEngine(b.build());
    }

    /**
     * Compiled-in workflow defaults ({@link LivenessPolicy#defaults}) with the changes the synthetic subject needs:
     * rPPG and parallax off (a drawn face has no pulse and no depth, so they would reject the live subject too), and
     * NOD / LOOK_DOWN out of the challenge pool (the five-landmark pose estimate cannot see a drawn face tilt down,
     * see docs/testing/e2e-findings.md). With a real SBI device ({@code synthetic = false}) the defaults are used as is.
     */
    public static LivenessPolicy policy(Workflow w, boolean synthetic) {
        LivenessPolicy d = LivenessPolicy.defaults(w);
        if (!synthetic) return d;
        LivenessPolicy.Builder b = LivenessPolicy.builder(w).policyVersion("attack-lab-synthetic-1")
                .enabled(d.enabled()).passiveThreshold(d.passiveThreshold()).padHardThreshold(d.padHardThreshold())
                .passiveWindowMillis(d.passiveWindowMillis()).passiveMinFrames(d.passiveMinFrames()).activeMode(d.activeMode())
                .minChallenges(d.minChallenges()).maxChallenges(d.maxChallenges())
                .challengePool(EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT, ChallengeType.OPEN_MOUTH))
                .accessibilityPool(d.accessibilityPool()).requireDepthRevealingChallenge(d.requireDepthRevealingChallenge())
                .challengeTimeoutMillis(d.challengeTimeoutMillis()).minActionLatencyMillis(d.minActionLatencyMillis())
                .maxActionLatencyMillis(d.maxActionLatencyMillis()).maxRetries(d.maxRetries()).onMaxRetries(d.onMaxRetries())
                .sessionTimeoutMillis(d.sessionTimeoutMillis()).minFaceQuality(d.minFaceQuality())
                .identityDriftThreshold(d.identityDriftThreshold()).signalWeights(d.signalWeights())
                .requireIdentityContinuity(d.requireIdentityContinuity());
        for (String s : SYNTHETIC_DISABLED_SIGNALS) b.signalWeight(s, 0);
        return b.build();
    }
}
