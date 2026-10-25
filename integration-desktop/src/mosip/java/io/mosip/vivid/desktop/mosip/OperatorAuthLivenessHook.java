package io.mosip.vivid.desktop.mosip;

import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.service.bio.BioService;
import io.mosip.registration.service.bio.FaceAuthLivenessGuard;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.desktop.DesktopLivenessConfig;
import io.mosip.vivid.desktop.LivenessGate;
import io.mosip.vivid.desktop.LivenessUiBinding;
import io.mosip.vivid.desktop.stream.MjpegInputStreamFrameSource;
import io.mosip.vivid.desktop.ui.LivenessOverlayController;
import io.mosip.vivid.device.DeviceCapabilities;
import javafx.scene.image.ImageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Operator / supervisor face authentication gate. Called synchronously on the worker thread of
 * {@code BaseController.captureAndValidateFace} (patch 0003) and {@code SessionContext.validateFace} (patch 0004),
 * before {@code bioService.captureModalityForAuth}. Opens its own SBI stream (the auth screens' preview Streamer is
 * stopped first), runs the gate, and returns the result; callers proceed to capture only if {@code accepted()}.
 * <p>
 * Also the {@link FaceAuthLivenessGuard} that {@code SessionContext.validateFace} (registration-services, patch 0004)
 * looks up from the Spring context for the login screen: registration-services cannot reference this class directly,
 * because it lives in registration-client, which depends on registration-services.
 */
@Component
public class OperatorAuthLivenessHook implements FaceAuthLivenessGuard {
    private static final Logger LOG = LoggerFactory.getLogger(OperatorAuthLivenessHook.class);

    @Autowired private BioService bioService;
    @Autowired private LivenessSpringConfig.LivenessGateFactory gateFactory;
    @Autowired private LivenessCaptureVerifier captureVerifier;

    private volatile LivenessResult lastResult;
    /** Login flow only: result of {@link #beforeFaceAuthCapture} awaiting {@link #afterFaceAuthCapture}. */
    private volatile LivenessResult pendingLogin;

    public LivenessResult lastResult() { return lastResult; }

    public boolean enabled(Workflow workflow) {
        return DesktopLivenessConfig.fromApplicationMap(ApplicationContext.map()).enabled(workflow);
    }

    /**
     * @param workflow OPERATOR for operator / login auth, SUPERVISOR for the reviewer flag
     * @param preview  the screen's face ImageView (may be null: then no overlay, prompts are not shown and only
     *                 passive liveness can pass; audit still records everything)
     */
    public LivenessResult verify(Workflow workflow, ImageView preview, String userId) throws RegBaseCheckedException {
        DesktopLivenessConfig cfg = DesktopLivenessConfig.fromApplicationMap(ApplicationContext.map());
        LivenessGate gate = gateFactory.create();
        LivenessUiBinding ui = LivenessUiBinding.NONE;
        LivenessOverlayController overlay = null;
        if (preview != null) {
            try {
                overlay = LivenessOverlayInstaller.install(preview, cfg);
                overlay.setCancelVisible(false);
                ui = overlay;
            } catch (Exception e) {
                LOG.error("could not install liveness overlay for {} auth; running headless", workflow, e);
            }
        }
        InputStream stream = bioService.getStream(RegistrationConstants.FACE_FULLFACE); // throws MDS_STREAM_ERROR / BIODEVICE_NOT_FOUND like today
        MjpegInputStreamFrameSource source = new MjpegInputStreamFrameSource(stream, DeviceCapabilities.builder().sbiSpecVersion("1.0"));
        try {
            LivenessResult r = gate.verify(workflow, source, ui, LivenessGate.Options.defaults().auditRefId(userId));
            lastResult = r;
            return r;
        } finally {
            gate.shutdown();
            if (overlay != null) {
                LivenessOverlayController o = overlay;
                // leave the PASSED / failure state visible briefly for the operator; the next scan re-installs it
                javafx.application.Platform.runLater(() -> LivenessOverlayInstaller.remove(o));
            }
        }
    }

    /** Login ({@code SessionContext.validateFace}): OPERATOR liveness, headless (LoginController keeps its own preview). */
    @Override
    public boolean beforeFaceAuthCapture(String userId) throws RegBaseCheckedException {
        pendingLogin = null;
        if (!enabled(Workflow.OPERATOR)) return true;
        LivenessResult r = verify(Workflow.OPERATOR, null, userId);
        if (!r.accepted()) {
            LOG.warn("Face liveness not accepted for login of {}: {}", userId, r.decision());
            return false;
        }
        pendingLogin = r;
        return true;
    }

    /** Login: bind the signed auth capture to the liveness session before {@code authValidator}. */
    @Override
    public boolean afterFaceAuthCapture(String userId, List<BiometricsDto> biometrics) {
        LivenessResult r = pendingLogin;
        pendingLogin = null;
        if (r == null) return !enabled(Workflow.OPERATOR); // enabled but no accepted session: never accept silently
        return captureVerifier.verify(r, biometrics, Workflow.OPERATOR, userId).accepted();
    }
}
