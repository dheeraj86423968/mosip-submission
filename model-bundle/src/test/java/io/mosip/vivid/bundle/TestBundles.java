package io.mosip.vivid.bundle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Fixture helpers: tiny fake model files, a manifest template and a signed archive. */
final class TestBundles {
    static final KeyPair KEYS = KeyIo.generateEcKeyPair();
    static final KeyPair OTHER_KEYS = KeyIo.generateEcKeyPair();

    private TestBundles() {}

    static Path contentDir(Path tmp) throws IOException {
        Path dir = tmp.resolve("content");
        Files.createDirectories(dir.resolve("selftest"));
        Random rnd = new Random(42);
        byte[] a = new byte[1024], b = new byte[2048], img = new byte[256];
        rnd.nextBytes(a); rnd.nextBytes(b); rnd.nextBytes(img);
        Files.write(dir.resolve("detector.onnx"), a);
        Files.write(dir.resolve("antispoof.onnx"), b);
        Files.write(dir.resolve("selftest/live.jpg"), img);
        return dir;
    }

    static BundleManifest manifest(String id, String version) {
        BundleManifest m = new BundleManifest();
        m.setBundleId(id);
        m.setVersion(version);
        m.setPlatform(BundleManifest.PLATFORM_JVM);
        m.setMinEngineVersion("0.1.0");
        m.setDescription("test bundle");
        m.setModels(new java.util.ArrayList<>(List.of(
                new BundleManifest.ModelEntry("yunet", "detector.onnx", null, new int[] {1, 3, 320, 320}, "face-detection"),
                new BundleManifest.ModelEntry("minifasnet", "antispoof.onnx", null, new int[] {1, 3, 80, 80}, "anti-spoofing"))));
        m.setCalibration(new java.util.LinkedHashMap<>(Map.of("cnn_texture", new BundleManifest.Calibration(-1.5, 0.2, 0.5))));
        m.setSelfTest(new java.util.ArrayList<>(List.of(new BundleManifest.SelfTestEntry("selftest/live.jpg", "cnn_texture", 0.0, 0.4))));
        return m;
    }

    /** Writes a signed bundle to {@code tmp/<id>-<version>.mlb}. */
    static Path bundle(Path tmp, String id, String version) throws IOException, BundleRejectedException {
        return bundle(tmp, id, version, KEYS);
    }

    static Path bundle(Path tmp, String id, String version, KeyPair keys) throws IOException, BundleRejectedException {
        Path content = contentDir(tmp.resolve(id + "-" + version));
        Path out = tmp.resolve(id + "-" + version + ".mlb");
        new BundleWriter(keys.getPrivate()).write(manifest(id, version), content, out);
        return out;
    }

    static BundleVerifier verifier() { return new BundleVerifier(KEYS.getPublic()); }

    static String text(Path p) throws IOException { return Files.readString(p, StandardCharsets.UTF_8); }
}
