package io.mosip.vivid.desktop.mosip;

import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.enums.Modality;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.util.restclient.ServiceDelegateUtil;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.desktop.DesktopLivenessConfig;
import io.mosip.vivid.desktop.LivenessGate;
import io.mosip.vivid.desktop.LivenessUiBinding;
import io.mosip.vivid.desktop.stream.MjpegInputStreamFrameSource;
import io.mosip.vivid.desktop.ui.LivenessOverlayController;
import io.mosip.vivid.device.DeviceCapabilities;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.image.ImageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Resident face capture gate, called from {@code GenericBiometricsController.scan(...)} (patch 0002) in place of
 * {@code rCaptureTaskService(); streamer.startStream(urlStream, biometricImage, biometricImage);} when the modality
 * is FACE. It runs {@link LivenessGate#verify} on a JavaFX {@code Task}, renders the stream through the overlay and
 * invokes {@code onPassed} (which calls {@code rCaptureTaskService()}) only when the result is accepted.
 * The last accepted result is kept so {@link LivenessCaptureVerifier} can bind the rcapture image to it.
 */
@Component
public class ResidentFaceLivenessHook {
    private static final Logger LOG = LoggerFactory.getLogger(ResidentFaceLivenessHook.class);

    @Autowired private LivenessSpringConfig.LivenessGateFactory gateFactory;
    @Autowired private ServiceDelegateUtil serviceDelegateUtil;

    private volatile LivenessGate activeGate;
    private volatile LivenessResult lastResult;
    private volatile LivenessOverlayController overlay;

    public boolean applies(Modality modality) {
        if (modality != Modality.FACE && modality != Modality.EXCEPTION_PHOTO) return false; // exception photo: policy decides
        DesktopLivenessConfig cfg = DesktopLivenessConfig.fromApplicationMap(ApplicationContext.map());
        return cfg.enabled(Workflow.RESIDENT) && modality == Modality.FACE;
    }

    /** The result of the last gate run; consumed by LivenessCaptureVerifier. */
    public LivenessResult lastResult() { return lastResult; }

    public void run(MdmBioDevice device, InputStream urlStream, ImageView preview, Runnable onPassed, Consumer<LivenessResult> onBlocked, boolean assistedCapture) {
        DesktopLivenessConfig cfg = DesktopLivenessConfig.fromApplicationMap(ApplicationContext.map());
        LivenessGate gate = gateFactory.create();
        activeGate = gate;
        lastResult = null;
        try {
            overlay = LivenessOverlayInstaller.install(preview, cfg);
            overlay.setOnCancel(gate::cancel);
        } catch (Exception e) {
            LOG.error("could not install liveness overlay; running headless", e);
            overlay = null;
        }
        DeviceCapabilities.Builder caps = DeviceCapabilities.builder()
                .vendor(device.getDeviceMake()).model(device.getDeviceModel()).deviceId(device.getSerialNumber())
                .sbiSpecVersion(device.getSpecVersion())
                .certificationLevel("L1".equalsIgnoreCase(device.getCertification()) ? DeviceCapabilities.CertificationLevel.L1 : DeviceCapabilities.CertificationLevel.L0);
        MjpegInputStreamFrameSource source = new MjpegInputStreamFrameSource(urlStream, caps);

        Task<LivenessResult> task = new Task<>() {
            @Override protected LivenessResult call() {
                // network check is an HTTP call: done here on the worker thread, never on the FX thread
                boolean online = isOnline();
                LivenessOverlayController o = overlay;
                if (o != null) Platform.runLater(() -> o.setOnline(online));
                return gate.verify(Workflow.RESIDENT, source, o != null ? o : LivenessUiBinding.NONE, LivenessGate.Options.defaults()
                        .assistedCapture(assistedCapture).online(online)
                        .auditRefId(io.mosip.registration.context.SessionContext.userId()));
            }
        };
        task.setOnSucceeded(ev -> {
            LivenessResult r = task.getValue();
            lastResult = r;
            if (r.accepted()) {
                LOG.info("liveness PASSED session={} fused={} attempts={}", new Object[] {r.sessionId(), r.fusedLivenessScore(), r.attemptsUsed()});
                // The SBI stream InputStream has been consumed and closed by the frame source; rcapture opens its own
                // HTTP call, so it does not depend on it. The overlay stays visible (PASSED state) during rcapture.
                onPassed.run();
            } else {
                LOG.warn("liveness NOT accepted session={} decision={} reason={}", new Object[] {r.sessionId(), r.decision(), r.failure().orElse(null)});
                onBlocked.accept(r);
            }
        });
        task.setOnFailed(ev -> {
            LOG.error("liveness task failed", task.getException());
            onBlocked.accept(null);
        });
        Thread t = new Thread(task, "VIVID-LIVENESS-GATE");
        t.setDaemon(true);
        t.start();
    }

    public void cancel() { LivenessGate g = activeGate; if (g != null) g.cancel(); }

    public void dispose() {
        LivenessOverlayController o = overlay;
        overlay = null;
        if (o != null) Platform.runLater(() -> LivenessOverlayInstaller.remove(o));
        LivenessGate g = activeGate; activeGate = null;
        if (g != null) g.shutdown();
    }

    private boolean isOnline() {
        try {
            return serviceDelegateUtil.isNetworkAvailable(); // same health-check the client uses before sync / upload
        } catch (RuntimeException e) { return false; }
    }
}
