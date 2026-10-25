package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.RgbImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParallaxInjectionQualityTest {
    @TempDir Path tmp;

    /** 3-D face points (IOD 60 px, nose 30 px in front) turned by yaw, perspective-projected at 600 px focal length. */
    static double[] realFace(double yawDeg) {
        double[][] pts = {{-30, -20, 0}, {30, -20, 0}, {0, 15, -30}, {-24, 40, -5}, {24, 40, -5}};
        return project(pts, yawDeg, 0);
    }

    /** The same five points printed on a flat sheet (z = 0) turned by yaw. */
    static double[] flatPhoto(double yawDeg) {
        double[][] pts = {{-30, -20, 0}, {30, -20, 0}, {0, 15, 0}, {-24, 40, 0}, {24, 40, 0}};
        return project(pts, yawDeg, 0);
    }

    static double[] project(double[][] pts, double yawDeg, double shiftX) {
        double y = Math.toRadians(yawDeg), f = 600, dist = 500;
        double[] out = new double[10];
        for (int i = 0; i < 5; i++) {
            double x = pts[i][0] * Math.cos(y) - pts[i][2] * Math.sin(y);
            double z = pts[i][0] * Math.sin(y) + pts[i][2] * Math.cos(y) + dist;
            out[2 * i] = 320 + shiftX + f * x / z;
            out[2 * i + 1] = 240 + f * pts[i][1] / z;
        }
        return out;
    }

    private static SignalScore run(ParallaxSignal s, java.util.function.DoubleFunction<double[]> lm) {
        s.onChallenge(new Challenge(ChallengeType.TURN_LEFT, 0, 1, 0, 8000));
        RgbImage img = SyntheticFaces.flat(128, 128, 128);
        SignalScore last = null;
        for (int deg = 0; deg <= 25; deg += 5) {
            FaceDetection f = new FaceDetection(250, 180, 140, 160, 0.9, lm.apply(deg));
            last = s.score(new FaceContext(img, f, deg * 1_000_000L, deg, null));
        }
        return last;
    }

    @Test
    void realHeadTurnHasParallaxPlanarPhotoDoesNot() {
        SignalScore real = run(new ParallaxSignal(null), ParallaxInjectionQualityTest::realFace);
        SignalScore photo = run(new ParallaxSignal(null), ParallaxInjectionQualityTest::flatPhoto);
        assertThat(real.attackProbability()).isLessThan(0.2);
        assertThat(real.confidence()).isGreaterThan(0.9);
        assertThat(photo.attackProbability()).isGreaterThan(0.8);
        assertThat(photo.confidence()).isGreaterThan(0.3);
        assertThat(photo.attackClass()).isEqualTo(Signals.ATTACK_PRINT);
        double[] a = flatPhoto(0), b = flatPhoto(25);
        assertThat(ParallaxSignal.measure(a, b, 60).homographyResidual).isLessThan(1e-3);
    }

    @Test
    void neutralWithoutDepthChallengeAndVerdictIsSticky() {
        ParallaxSignal s = new ParallaxSignal(null);
        FaceContext ctx = SyntheticFaces.ctx(SyntheticFaces.flat(1, 1, 1));
        assertThat(s.score(ctx).confidence()).isZero();
        s.onChallenge(new Challenge(ChallengeType.BLINK, 0, 1, 0, 8000));
        assertThat(s.isTracking()).isFalse();
        SignalScore v = run(s, ParallaxInjectionQualityTest::flatPhoto);
        s.onChallenge(new Challenge(ChallengeType.SMILE, 1, 2, 0, 8000));
        assertThat(s.score(ctx).attackProbability()).isEqualTo(v.attackProbability());
        s.reset();
        assertThat(s.score(ctx).confidence()).isZero();
    }

    @Test
    void translationOnlyGivesNoConfidence() {
        ParallaxSignal s = new ParallaxSignal(null);
        s.onChallenge(new Challenge(ChallengeType.NOD, 0, 1, 0, 8000));
        RgbImage img = SyntheticFaces.flat(1, 1, 1);
        double[][] pts = {{-30, -20, 0}, {30, -20, 0}, {0, 15, -30}, {-24, 40, -5}, {24, 40, -5}};
        SignalScore last = null;
        for (int dx = 0; dx <= 40; dx += 10) {
            last = s.score(new FaceContext(img, new FaceDetection(200, 150, 140, 160, 0.9, project(pts, 0, dx)), dx, dx, null));
        }
        assertThat(last.confidence()).isLessThan(0.1);
    }

    @Test
    void injectionVetoesOnlyForSystemCameraWithVirtualDevice() throws Exception {
        // a fake sysfs: a real USB laptop camera, a v4l2loopback device renamed to look like it,
        // a USB HDMI capture dongle, and a device with no bus at all
        Path devices = tmp.resolve("devices");
        Path usbCam = devices.resolve("pci0000:00/0000:00:14.0/usb1/1-6");
        Path usbCamIf = Files.createDirectories(usbCam.resolve("1-6:1.0"));
        Files.writeString(usbCam.resolve("idVendor"), "04f2\n");
        Files.writeString(usbCam.resolve("idProduct"), "b6dd\n");
        Path dongle = devices.resolve("pci0000:00/0000:00:14.0/usb1/1-2");
        Path dongleIf = Files.createDirectories(dongle.resolve("1-2:1.0"));
        Files.writeString(dongle.resolve("idVendor"), "534d\n");
        Files.writeString(dongle.resolve("idProduct"), "2109\n");
        Path loop = Files.createDirectories(devices.resolve("platform/v4l2loopback.0"));
        Path loopDriver = Files.createDirectories(tmp.resolve("bus/platform/drivers/v4l2loopback"));
        Files.createSymbolicLink(loop.resolve("driver"), loopDriver);
        Path sys = tmp.resolve("video4linux");
        String[][] devs = {{"video0", "Integrated Camera: Integrated C"}, {"video2", "Integrated Camera"},
                           {"video4", "USB Video: USB Video"}, {"video6", "Dummy video device (0x0000)"}};
        for (String[] d : devs) {
            Files.createDirectories(sys.resolve(d[0]));
            Files.writeString(sys.resolve(d[0] + "/name"), d[1] + "\n");
        }
        Files.createSymbolicLink(sys.resolve("video0/device"), usbCamIf);
        Files.createSymbolicLink(sys.resolve("video2/device"), loop);
        Files.createSymbolicLink(sys.resolve("video4/device"), dongleIf);
        List<String> names = InjectionSignal.linuxDeviceNames(sys);
        assertThat(names).containsExactly("Integrated Camera: Integrated C", "Integrated Camera [no physical bus]",
                "USB Video: USB Video [usb capture card 534d:2109]", "Dummy video device (0x0000) [no physical bus]");
        assertThat(names.stream().filter(InjectionSignal::isVirtual)).hasSize(3);

        SignalScore veto = new InjectionSignal(true, () -> names).scoreWithoutFace(0).orElseThrow();
        assertThat(veto.hardVeto()).isTrue();
        assertThat(veto.attackClass()).isEqualTo(Signals.ATTACK_INJECTION);
        assertThat(new InjectionSignal(false, () -> names).scoreWithoutFace(0).orElseThrow().hardVeto()).isFalse();
        SignalScore clean = new InjectionSignal(true, () -> List.of("Integrated Camera")).score(SyntheticFaces.ctx(SyntheticFaces.flat(1, 1, 1)));
        assertThat(clean.hardVeto()).isFalse();
        assertThat(clean.attackProbability()).isZero();
        assertThat(InjectionSignal.isVirtual("OBS Virtual Camera")).isTrue();
        assertThat(InjectionSignal.isVirtual("Logitech C920")).isFalse();
        assertThat(InjectionSignal.linuxDeviceNames(tmp.resolve("missing"))).isEmpty();
    }

    @Test
    void qualityRewardsSharpWellLitFaces() {
        QualitySignal q = new QualitySignal();
        FaceQuality good = q.assess(SyntheticFaces.ctx(SyntheticFaces.frame((x, y) -> ((x / 3 + y / 3) % 2 == 0) ? SyntheticFaces.rgb(170, 140, 120) : SyntheticFaces.rgb(110, 90, 80))), 1);
        assertThat(good.sharpness()).isEqualTo(1.0);
        assertThat(good.illumination()).isGreaterThan(0.9);
        assertThat(good.sizeQuality()).isGreaterThan(0.6);
        assertThat(good.occlusion()).isEqualTo(1.0);
        FaceQuality dark = q.assess(SyntheticFaces.ctx(SyntheticFaces.flat(10, 10, 10)), 1);
        assertThat(dark.illumination()).isZero();
        assertThat(dark.sharpness()).isZero();
        assertThat(q.assess(SyntheticFaces.ctx(SyntheticFaces.flat(128, 128, 128)), 2).overall()).isZero();
        SignalScore s = q.score(SyntheticFaces.ctx(SyntheticFaces.flat(10, 10, 10)));
        assertThat(s.confidence()).isZero();
        // implausible landmarks (mouth above nose) lower the occlusion score
        FaceDetection weird = new FaceDetection(110, 70, 100, 110, 0.95, new double[] {140, 110, 180, 110, 160, 160, 145, 130, 175, 130});
        assertThat(QualitySignal.landmarkPlausibility(weird)).isLessThan(0.7);
    }
}
