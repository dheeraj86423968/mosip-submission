package io.mosip.vivid.device.mock.scenario;

import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.MockFaceFrameSource;
import io.mosip.vivid.device.mock.Pacing;
import io.mosip.vivid.device.mock.render.DepthIrRenderer;
import io.mosip.vivid.device.mock.render.FaceState;
import io.mosip.vivid.device.FrameSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S8 sensor planes of the mock device (synthetic depth / IR). */
class SensorPlanesTest {
    static final int W = 640, H = 480;
    // centred face at scale 1: head 288 px tall, 0.764 mm/px; nose tip landmark about (320, 263), cheeks at +-60 px
    static final int CX = 320, CY = 240;

    static List<String> sensorScenarios() { return Scenarios.sensorScenarioNames(); }

    static int depthAt(ByteBuffer b, int u, int v) { return b.getShort(2 * (v * W + u)) & 0xffff; }
    static int irAt(ByteBuffer b, int u, int v) { return b.get(v * W + u) & 0xff; }

    static DepthIrRenderer.Planes first(String scenario) {
        ScenarioTimeline tl = new ScenarioTimeline(Scenarios.bundled(scenario));
        var pos = tl.positionAt(0).orElseThrow();
        return tl.renderPlanes(pos, tl.render(pos)).orElseThrow();
    }

    @Test
    void existingScenariosStayRgbOnly() throws Exception {
        for (String name : Scenarios.bundledNames()) {
            Scenario s = Scenarios.bundled(name);
            assertThat(s.sensors()).as(name).isEmpty();
            ScenarioTimeline tl = new ScenarioTimeline(s);
            var pos = tl.positionAt(0).orElseThrow();
            assertThat(tl.renderPlanes(pos, tl.render(pos))).isEmpty();
        }
        MockFaceFrameSource src = MockFaceFrameSource.builder(Scenarios.bundled("live-pass")).build();
        assertThat(src.capabilities().hasDepth()).isFalse();
        assertThat(src.capabilities().hasIr()).isFalse();
        assertThat(Scenarios.bundledNames()).doesNotContainAnyElementsOf(Scenarios.sensorScenarioNames());
    }

    @ParameterizedTest @MethodSource("sensorScenarios")
    void sensorScenariosDeliverRegisteredPlanesAndReportCapabilities(String name) throws Exception {
        Scenario s = Scenarios.resolve(name);
        assertThat(s.sensors()).isPresent();
        MockFaceFrameSource src = MockFaceFrameSource.builder(s).pacing(Pacing.unthrottled()).build();
        assertThat(src.capabilities().hasDepth()).isTrue();
        assertThat(src.capabilities().hasIr()).isTrue();
        List<FaceFrame> frames = new CopyOnWriteArrayList<>();
        CountDownLatch got = new CountDownLatch(3);
        src.start(new FrameSink() {
            @Override public void onFrame(FaceFrame f) { frames.add(f); got.countDown(); }
            @Override public void onHealth(io.mosip.vivid.device.DeviceHealth h) {}
            @Override public void onEnd() {}
        }, StreamOptions.defaults());
        assertThat(got.await(20, TimeUnit.SECONDS)).isTrue();
        src.stop();
        FaceFrame f = frames.get(0);
        assertThat(f.depth()).isPresent();
        assertThat(f.ir()).isPresent();
        assertThat(f.depth().get().remaining()).isEqualTo(W * H * 2);
        assertThat(f.ir().get().remaining()).isEqualTo(W * H);
    }

    @Test
    void liveHeadHasNoseReliefAndGlints() {
        DepthIrRenderer.Planes p = first("l1-live-depth-ir");
        ByteBuffer d = p.depthBuffer();
        int nose = median(d, CX, 255), cheekL = median(d, CX - 75, 250), cheekR = median(d, CX + 75, 250);
        assertThat(nose).isBetween(470, 520);
        assertThat((cheekL + cheekR) / 2 - nose).as("nose 2-6 cm closer than the cheeks").isBetween(20, 60);
        assertThat(median(d, 20, 20)).isBetween(1590, 1610);   // wall
        // IR: bright skin, saturated glint on each pupil (eyes at x = 320 +- 0.22 * 0.78 * 288, y = 240 - 0.10 * 288)
        ByteBuffer ir = p.irBuffer();
        assertThat(irAt(ir, CX, 250)).isGreaterThan(80);
        assertThat(maxIn(ir, CX - 49, 211, 8)).isEqualTo(255);
        assertThat(maxIn(ir, CX + 49, 211, 8)).isEqualTo(255);
    }

    @Test
    void headDepthFollowsYaw() {
        DepthIrRenderer r = new DepthIrRenderer(W, H);
        var right = r.heads(List.of(new DepthIrRenderer.Head(new FaceState(25, 0, 1, 0, 0, 0), 0.5, 0.5, 1.0)), null, true, false, 0, 0, 1);
        ByteBuffer d = right.depthBuffer();
        // nose tip shifts to image right with positive yaw (drawn at x0 + 1.3 * sin(yaw) * 0.3 * headW)
        int shifted = CX + (int) Math.round(1.3 * Math.sin(Math.toRadians(25)) * 0.3 * 288 * 0.78);
        assertThat(depthAt(d, shifted, 263)).isLessThan(depthAt(d, CX - 40, 263) - 15);
    }

    @Test
    void flatPrintIsPlanarInInverseDepth() {
        ByteBuffer d = first("l1-print-depth-ir").depthBuffer();
        // three collinear points along a row: 1/Z is linear for a plane under a pinhole camera (noise 1 mm, 1 mm quantisation)
        double a = 1.0 / median(d, CX - 80, 240), b = 1.0 / median(d, CX, 240), c = 1.0 / median(d, CX + 80, 240);
        assertThat(Math.abs((a + c) / 2 - b) * 450 * 450).isLessThan(2.0);
        assertThat(median(d, CX - 80, 240)).isNotEqualTo(median(d, CX + 80, 240));   // tilted
        ByteBuffer ir = first("l1-print-depth-ir").irBuffer();
        assertThat(maxIn(ir, CX - 49, 211, 8)).isLessThan(230);   // no glint
    }

    @Test
    void curvedPrintBulgesWithoutANose() {
        ByteBuffer d = first("l1-curved-print-depth-ir").depthBuffer();
        int centre = median(d, CX, 255), side = median(d, CX + 100, 255);
        assertThat(side - centre).isGreaterThan(10);   // bent: edges farther
        assertThat(Math.abs(median(d, CX, 200) - median(d, CX, 300))).isLessThanOrEqualTo(2);   // no vertical relief, no nose
    }

    @Test
    void screenPanelIsIrDarkInsideALitBezel() {
        DepthIrRenderer.Planes p = first("l1-screen-depth-ir");
        ByteBuffer ir = p.irBuffer();
        assertThat(median8(ir, CX, 250)).isLessThan(20);
        assertThat(median8(ir, CX + 210, 250)).isGreaterThan(50);   // bezel
        assertThat(median(p.depthBuffer(), CX, 250)).isBetween(390, 420);
    }

    @Test
    void parsesSensorsAndRejectsUnknownSurface() {
        Scenario s = Scenarios.fromJson("""
            {"name":"x","sensors":{"depth":true,"ir":false,"surface":"curved-print","bendRadiusMm":90,"noiseMm":2},
             "segments":[{"durationMs":1000}]}""");
        SensorConfig c = s.sensors().orElseThrow();
        assertThat(c.depth()).isTrue();
        assertThat(c.ir()).isFalse();
        assertThat(c.surface()).isEqualTo(SurfaceKind.CURVED_PRINT);
        assertThat(c.bendRadiusMm()).isEqualTo(90);
        assertThat(c.noiseMm()).isEqualTo(2);
        ScenarioTimeline tl = new ScenarioTimeline(s);
        var pos = tl.positionAt(0).orElseThrow();
        DepthIrRenderer.Planes p = tl.renderPlanes(pos, tl.render(pos)).orElseThrow();
        assertThat(p.depth()).isNotNull();
        assertThat(p.ir()).isNull();
        assertThat(MockFaceFrameSource.builder(s).build().capabilities().hasIr()).isFalse();
        assertThatThrownBy(() -> Scenarios.fromJson("{\"name\":\"x\",\"sensors\":{\"surface\":\"bogus\"},\"segments\":[{\"durationMs\":5}]}"))
                .hasMessageContaining("bogus");
        // both planes off is the same as no sensors
        assertThat(Scenarios.fromJson("{\"name\":\"x\",\"sensors\":{\"depth\":false,\"ir\":false},\"segments\":[{\"durationMs\":5}]}").sensors()).isEmpty();
        assertThat(SurfaceKind.AUTO.resolve(PresentationLabel.VIDEO_REPLAY)).isEqualTo(SurfaceKind.SCREEN);
        assertThat(SurfaceKind.AUTO.resolve(PresentationLabel.PRINT)).isEqualTo(SurfaceKind.FLAT_PRINT);
        assertThat(SurfaceKind.AUTO.resolve(PresentationLabel.LIVE)).isEqualTo(SurfaceKind.HEAD);
    }

    @Test
    void depthIsLittleEndianMillimetresWithHolesAsZero() {
        DepthIrRenderer r = new DepthIrRenderer(W, H);
        var p = r.heads(List.of(), null, true, false, 0, 0.5, 3);
        ByteBuffer d = ByteBuffer.wrap(p.depth()).order(ByteOrder.LITTLE_ENDIAN);
        int zeros = 0, wall = 0;
        for (int i = 0; i < W * H; i++) {
            int v = d.getShort(2 * i) & 0xffff;
            if (v == 0) zeros++; else if (v == 1600) wall++;
        }
        assertThat(zeros + wall).isEqualTo(W * H);
        assertThat(zeros / (double) (W * H)).isBetween(0.45, 0.55);
    }

    /** Median of the valid (non-zero) depth samples in a 5x5 window. */
    static int median(ByteBuffer d, int u, int v) {
        int[] s = new int[25];
        int n = 0;
        for (int dv = -2; dv <= 2; dv++) for (int du = -2; du <= 2; du++) {
            int z = depthAt(d, u + du, v + dv);
            if (z > 0) s[n++] = z;
        }
        java.util.Arrays.sort(s, 0, n);
        return s[n / 2];
    }

    static int median8(ByteBuffer ir, int u, int v) {
        int[] s = new int[25];
        int n = 0;
        for (int dv = -2; dv <= 2; dv++) for (int du = -2; du <= 2; du++) s[n++] = irAt(ir, u + du, v + dv);
        java.util.Arrays.sort(s);
        return s[12];
    }

    static int maxIn(ByteBuffer ir, int u, int v, int r) {
        int m = 0;
        for (int dv = -r; dv <= r; dv++) for (int du = -r; du <= r; du++) m = Math.max(m, irAt(ir, u + du, v + dv));
        return m;
    }
}
