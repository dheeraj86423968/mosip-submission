package io.mosip.vivid.desktop.mosip;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceDecoder;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.desktop.CaptureBinding;
import io.mosip.vivid.desktop.DesktopLivenessConfig;
import io.mosip.vivid.desktop.LivenessAuditAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Binds the signed {@code rcapture} BiometricsDto to the preceding liveness result (identity continuity).
 * Called from {@code GenericBiometricsController.rCaptureTaskService().setOnSucceeded} (patch 0002) and from
 * {@code BaseController.captureAndValidateFace} / {@code SessionContext.validateFace} (patches 0003/0004).
 * Uses MOSIP's bio-utils {@code FaceDecoder} so JPEG 2000 ISO images are transcoded exactly as the client does
 * for its own thumbnail cache ({@code GenericBiometricsController.addStreamImageAndScoreToCache}).
 */
@Component
public class LivenessCaptureVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(LivenessCaptureVerifier.class);

    @Autowired private LivenessEngine engine;
    @Autowired private BiFunction<float[], float[], Double> identityDistance;
    @Autowired private LivenessAuditAdapter audit;

    /** Also produces the JSON-ish summary stored in the packet's metaInfo ("livenessSummary"). */
    public CaptureBinding.Outcome verify(LivenessResult liveness, List<BiometricsDto> captured, Workflow workflow, String refId) {
        if (liveness == null) {
            // Gate was never run for this capture (e.g. liveness disabled, or hook bypassed): never silently accept.
            DesktopLivenessConfig cfg = DesktopLivenessConfig.fromApplicationMap(ApplicationContext.map());
            if (!cfg.enabled(workflow)) return outcomeNotRequired();
            LOG.error("capture arrived without a liveness result while liveness is enabled; rejecting");
            return new CaptureBinding(engine, identityDistance, 0, true).bind(
                    new LivenessResult("none", workflow, LivenessResult.Decision.ERROR, io.mosip.vivid.api.FailureReason.ENGINE_ERROR, 0, Map.of(), null, null, List.of(), 0, 0, 0, 0, "none", "none", "none", "", null, ""), new byte[0]);
        }
        DesktopLivenessConfig cfg = DesktopLivenessConfig.fromApplicationMap(ApplicationContext.map());
        double threshold = cfg.policy(workflow).identityDriftThreshold();
        boolean required = cfg.policy(workflow).requireIdentityContinuity();
        CaptureBinding binding = new CaptureBinding(engine, identityDistance, threshold, required).withIsoDecoder(iso -> {
            try {
                ConvertRequestDto req = new ConvertRequestDto();
                req.setVersion("ISO19794_5_2011");
                req.setInputBytes(iso);
                return FaceDecoder.convertFaceISOToImageBytes(req);
            } catch (Exception e) { throw new IllegalStateException("FaceDecoder failed", e); }
        });
        BiometricsDto face = captured == null || captured.isEmpty() ? null : captured.get(0);
        CaptureBinding.Outcome outcome = binding.bind(liveness, face == null ? null : face.getAttributeISO());
        outcome.auditEvent().ifPresent(ev -> audit.audit(ev, refId, Map.of("session", liveness.sessionId(), "distance", String.valueOf(outcome.distance()), "status", outcome.status().name())));
        LOG.info("capture binding {} for session {}", outcome, liveness.sessionId());
        return outcome;
    }

    private static CaptureBinding.Outcome outcomeNotRequired() {
        // CaptureBinding.Outcome has a package-private constructor; produce NOT_REQUIRED through a disabled result.
        LivenessResult disabled = new LivenessResult("disabled", Workflow.RESIDENT, LivenessResult.Decision.LIVE, io.mosip.vivid.api.FailureReason.POLICY_DISABLED, Double.NaN, Map.of(), null, null, List.of(), 0, 0, 0, 0, "none", "none", "disabled", "", null, "");
        return new CaptureBinding(new NoopEngine(), (a, b) -> 0.0, 1, false).bind(disabled, new byte[0]);
    }

    /** Summary attached to the packet metaInfo map (PacketHandlerServiceImpl.setMetaData, patch 0002b). Never biometric. */
    public static String summaryJson(LivenessResult r, CaptureBinding.Outcome binding) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"sessionId\":\"").append(r.sessionId()).append("\",");
        sb.append("\"decision\":\"").append(r.decision()).append("\",");
        sb.append("\"fusedLivenessScore\":").append(r.fusedLivenessScore()).append(',');
        sb.append("\"attempts\":").append(r.attemptsUsed()).append(',');
        sb.append("\"challenges\":").append(r.challenges().size()).append(',');
        sb.append("\"engine\":\"").append(r.engineId()).append("\",\"modelBundleVersion\":\"").append(r.modelBundleVersion()).append("\",");
        sb.append("\"policyVersion\":\"").append(r.policyVersion()).append("\",\"nonce\":\"").append(r.nonceHex()).append("\",");
        sb.append("\"evidenceHash\":\"").append(r.evidenceHash()).append("\",");
        sb.append("\"captureBinding\":\"").append(binding == null ? "NONE" : binding.status()).append("\"}");
        return sb.toString();
    }

    private static final class NoopEngine implements LivenessEngine {
        @Override public io.mosip.vivid.api.EngineInfo info() { return new io.mosip.vivid.api.EngineInfo("noop", "0", null, null, java.util.Set.of(), java.util.Set.of(), false); }
        @Override public io.mosip.vivid.api.FrameAnalyzer newAnalyzer() { throw new UnsupportedOperationException(); }
    }
}
