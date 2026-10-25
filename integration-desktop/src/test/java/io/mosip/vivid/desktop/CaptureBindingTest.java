package io.mosip.vivid.desktop;

import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.desktop.stream.MjpegInputStreamFrameSourceTest;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureBindingTest {

    static final BiFunction<float[], float[], Double> COSINE = (a, b) -> {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return 1.0 - dot / Math.sqrt(na * nb);
    };

    /** Engine whose analyzer returns a canned embedding/face count and records the frame it saw. */
    static LivenessEngine engine(int faces, float[] embedding, boolean veto, AtomicReference<FaceFrame> seen) {
        return new LivenessEngine() {
            @Override public EngineInfo info() { return new EngineInfo("fake", "1", null, null, Set.of(), Set.of(), true); }
            @Override public FrameAnalyzer newAnalyzer() {
                return new FrameAnalyzer() {
                    @Override public FrameAnalysis analyze(FaceFrame frame) {
                        seen.set(frame);
                        FaceQuality q = faces == 1 ? new FaceQuality(1, 0.9, 0.9, 0.9, 1, 0.9) : new FaceQuality(faces, 0, 0, 0, 0, 0);
                        FaceObservation face = faces == 1 ? FaceObservation.builder().box(0.3, 0.2, 0.4, 0.5).build() : null;
                        List<SignalScore> signals = veto ? List.of(SignalScore.veto("cnn_texture", "PRINT")) : List.of(SignalScore.of("cnn_texture", 0.1, 0.9));
                        return new FrameAnalysis(frame.sequence(), frame.timestampNanos(), q, face, signals, faces == 1 ? embedding : null, 1000);
                    }
                    @Override public void reset() {}
                };
            }
        };
    }

    static LivenessResult live(float[] anchor) {
        return new LivenessResult("s1", Workflow.RESIDENT, LivenessResult.Decision.LIVE, null, 0.93, Map.of("cnn_texture", 0.05), null, null,
                List.of(), 1, 2500, 6000, 40, "fake", "1", "builtin-1.0", "00ff", anchor, "hash");
    }

    @Test
    void isoRecordRoundTripsThroughTheBuiltInParser() throws Exception {
        byte[] jpeg = MjpegInputStreamFrameSourceTest.jpeg(120, 160);
        byte[] iso = Iso19794Face.wrap(jpeg, 0, 120, 160);
        assertThat(Iso19794Face.isFaceRecord(iso)).isTrue();
        Iso19794Face.ImageRecord rec = Iso19794Face.extract(iso).orElseThrow();
        assertThat(rec.type).isEqualTo(Iso19794Face.ImageType.JPEG);
        assertThat(rec.width).isEqualTo(120);
        assertThat(rec.height).isEqualTo(160);
        assertThat(rec.data).isEqualTo(jpeg);
        // raw JPEG without ISO wrapper also works, and a JP2 payload is recognised but not decoded
        assertThat(Iso19794Face.extract(jpeg).orElseThrow().type).isEqualTo(Iso19794Face.ImageType.JPEG);
        byte[] jp2 = new byte[] { 0, 0, 0, 0x0C, 'j', 'P', ' ', ' ', 13, 10, (byte) 0x87, 10, 1, 2, 3, 4 };
        assertThat(Iso19794Face.extract(Iso19794Face.wrap(jp2, 1, 10, 10)).orElseThrow().type).isEqualTo(Iso19794Face.ImageType.JPEG2000);
        assertThat(Iso19794Face.extract(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13 })).isEmpty();
    }

    @Test
    void sameIdentityBindsAndDifferentIdentityIsDrift() throws Exception {
        float[] anchor = { 1f, 0f, 0.2f, 0.1f };
        byte[] iso = Iso19794Face.wrap(MjpegInputStreamFrameSourceTest.jpeg(64, 80), 0, 64, 80);
        AtomicReference<FaceFrame> seen = new AtomicReference<>();

        CaptureBinding same = new CaptureBinding(engine(1, new float[] { 0.98f, 0.02f, 0.21f, 0.1f }, false, seen), COSINE, 0.45, true);
        CaptureBinding.Outcome ok = same.bind(live(anchor), iso);
        assertThat(ok.status()).isEqualTo(CaptureBinding.Status.BOUND);
        assertThat(ok.accepted()).isTrue();
        assertThat(ok.distance()).isLessThan(0.05);
        assertThat(ok.auditEvent()).contains(DesktopAuditEvent.CAPTURE_BOUND);
        assertThat(seen.get().format()).isEqualTo(PixelFormat.RGB888);   // decoded by ImageIO
        assertThat(seen.get().width()).isEqualTo(64);

        CaptureBinding other = new CaptureBinding(engine(1, new float[] { 0f, 1f, 0f, 0f }, false, seen), COSINE, 0.45, true);
        CaptureBinding.Outcome drift = other.bind(live(anchor), iso);
        assertThat(drift.status()).isEqualTo(CaptureBinding.Status.IDENTITY_DRIFT);
        assertThat(drift.accepted()).isFalse();
        assertThat(drift.auditEvent()).contains(DesktopAuditEvent.IDENTITY_DRIFT);
    }

    @Test
    void edgeCasesAreReportedNotThrown() throws Exception {
        float[] anchor = { 1f, 0f };
        byte[] iso = Iso19794Face.wrap(MjpegInputStreamFrameSourceTest.jpeg(32, 32), 0, 32, 32);
        AtomicReference<FaceFrame> seen = new AtomicReference<>();
        assertThat(new CaptureBinding(engine(0, null, false, seen), COSINE, 0.45, true).bind(live(anchor), iso).status()).isEqualTo(CaptureBinding.Status.NO_FACE);
        assertThat(new CaptureBinding(engine(2, null, false, seen), COSINE, 0.45, true).bind(live(anchor), iso).status()).isEqualTo(CaptureBinding.Status.MULTIPLE_FACES);
        assertThat(new CaptureBinding(engine(1, anchor, true, seen), COSINE, 0.45, true).bind(live(anchor), iso).status()).isEqualTo(CaptureBinding.Status.PAD_VETO);
        assertThat(new CaptureBinding(engine(1, anchor, false, seen), COSINE, 0.45, true).bind(live(anchor), new byte[] { 9, 9, 9 }).status()).isEqualTo(CaptureBinding.Status.UNSUPPORTED_IMAGE);
        assertThat(new CaptureBinding(engine(1, anchor, false, seen), COSINE, 0.45, true).bind(live(null), iso).status()).isEqualTo(CaptureBinding.Status.NO_ANCHOR);
        assertThat(new CaptureBinding(engine(1, anchor, false, seen), COSINE, 0.45, false).bind(live(null), iso).accepted()).isTrue();
        LivenessResult notLive = new LivenessResult("s", Workflow.RESIDENT, LivenessResult.Decision.ATTACK, FailureReason.PAD_DETECTED, 0.1, Map.of(), "moire", "SCREEN_REPLAY", List.of(), 1, 0, 0, 0, "f", "1", "p", "", anchor, "");
        assertThat(new CaptureBinding(engine(1, anchor, false, seen), COSINE, 0.45, true).bind(notLive, iso).status()).isEqualTo(CaptureBinding.Status.SESSION_NOT_LIVE);
        LivenessResult disabled = new LivenessResult("s", Workflow.RESIDENT, LivenessResult.Decision.LIVE, FailureReason.POLICY_DISABLED, Double.NaN, Map.of(), null, null, List.of(), 0, 0, 0, 0, "none", "none", "p", "", null, "");
        assertThat(new CaptureBinding(engine(1, anchor, false, seen), COSINE, 0.45, true).bind(disabled, iso).status()).isEqualTo(CaptureBinding.Status.NOT_REQUIRED);
    }

    @Test
    void externalIsoDecoderIsPreferredWhenPresent() throws Exception {
        float[] anchor = { 1f, 0f };
        byte[] jpeg = MjpegInputStreamFrameSourceTest.jpeg(40, 40);
        byte[] opaque = new byte[] { 'F', 'A', 'C', 0, '0', '1', '0', 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9, 9 }; // 2005-edition header, nothing parseable
        AtomicReference<FaceFrame> seen = new AtomicReference<>();
        CaptureBinding cb = new CaptureBinding(engine(1, anchor, false, seen), COSINE, 0.45, true).withIsoDecoder(iso -> jpeg);
        assertThat(cb.bind(live(anchor), opaque).status()).isEqualTo(CaptureBinding.Status.BOUND);
        assertThat(seen.get().width()).isEqualTo(40);
    }
}
