package io.mosip.vivid.demo;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.engine.onnx.OnnxLivenessEngine;
import io.mosip.vivid.engine.signal.InjectionSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Wraps the real {@link OnnxLivenessEngine} for the demo. Every frame still goes through the production analyzer;
 * the wrapper only adds two things:
 * <ol>
 *   <li>It keeps the last {@link FrameAnalysis} so the UI can draw the detected face box.</li>
 *   <li>While the "Injected stream" subject is selected it runs the engine's real {@link InjectionSignal} with a
 *       simulated device list that contains a virtual camera driver ("OBS Virtual Camera"). This machine has no
 *       virtual camera, so without the simulation the signal would have nothing to find. The decision logic (veto
 *       when a virtual camera is present and the source claims to be a system camera) is the engine's own.</li>
 * </ol>
 */
public final class DemoEngine implements LivenessEngine {
    /** Device name the demo pretends is installed while the injected stream is selected. */
    public static final String SIMULATED_VIRTUAL_CAMERA = "OBS Virtual Camera";

    private final OnnxLivenessEngine delegate;
    private final BooleanSupplier injectionActive;
    private final AtomicReference<FrameAnalysis> last = new AtomicReference<>();

    public DemoEngine(OnnxLivenessEngine delegate, BooleanSupplier injectionActive) {
        this.delegate = Objects.requireNonNull(delegate);
        this.injectionActive = Objects.requireNonNull(injectionActive);
    }

    /** Most recent analysis of any session, or null. */
    public FrameAnalysis lastAnalysis() { return last.get(); }
    public void clearLastAnalysis() { last.set(null); }

    @Override public EngineInfo info() { return delegate.info(); }
    @Override public SelfTestResult selfTest() { return delegate.selfTest(); }
    @Override public void close() { delegate.close(); }

    @Override public FrameAnalyzer newAnalyzer() {
        FrameAnalyzer a = delegate.newAnalyzer();
        return new FrameAnalyzer() {
            private InjectionSignal injection;

            @Override public FrameAnalysis analyze(FaceFrame frame) {
                FrameAnalysis r = a.analyze(frame);
                if (injectionActive.getAsBoolean()) {
                    if (injection == null) injection = new InjectionSignal(true, () -> List.of(SIMULATED_VIRTUAL_CAMERA));
                    List<SignalScore> signals = new ArrayList<>();
                    for (SignalScore s : r.signals()) if (!s.signalId().equals(injection.id())) signals.add(s);
                    injection.scoreWithoutFace(frame.timestampNanos()).ifPresent(signals::add);
                    r = new FrameAnalysis(r.frameSequence(), r.frameTimestampNanos(), r.quality(), r.face().orElse(null),
                            signals, r.embedding().orElse(null), r.processingNanos());
                }
                last.set(r);
                return r;
            }
            @Override public void onChallenge(Challenge c) { a.onChallenge(c); }
            @Override public void reset() { a.reset(); }
            @Override public void close() { a.close(); }
        };
    }
}
