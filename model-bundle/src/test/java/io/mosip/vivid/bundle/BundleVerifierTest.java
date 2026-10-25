package io.mosip.vivid.bundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleVerifierTest {
    @TempDir Path tmp;

    @Test
    void goodBundleVerifiesWithEitherKeyInTrustSet() throws Exception {
        Path mlb = TestBundles.bundle(tmp, "good", "1.0.0");
        VerifiedBundle v = TestBundles.verifier().verify(mlb);
        assertThat(v.manifest().getBundleId()).isEqualTo("good");
        assertThat(v.manifest().getModels()).allSatisfy(m -> assertThat(m.getSha256()).hasSize(64));
        assertThat(v.manifest().getCreatedAt()).isNotNull();
        assertThat(new BundleVerifier(List.of(TestBundles.OTHER_KEYS.getPublic(), TestBundles.KEYS.getPublic())).verify(mlb).manifest()).isEqualTo(v.manifest());
    }

    @Test
    void rsaSignedBundleVerifies() throws Exception {
        java.security.KeyPair rsa = KeyIo.generateRsaKeyPair(2048);
        Path mlb = TestBundles.bundle(tmp, "rsa", "1.0.0", rsa);
        assertThat(new BundleVerifier(rsa.getPublic()).verify(mlb).manifest().getBundleId()).isEqualTo("rsa");
        assertReason(() -> TestBundles.verifier().verify(mlb), BundleRejectedException.Reason.BAD_SIGNATURE);
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        Path mlb = TestBundles.bundle(tmp, "b", "1.0.0", TestBundles.OTHER_KEYS);
        assertReason(() -> TestBundles.verifier().verify(mlb), BundleRejectedException.Reason.BAD_SIGNATURE);
    }

    @Test
    void tamperedManifestIsRejected() throws Exception {
        Path mlb = TestBundles.bundle(tmp, "b", "1.0.0");
        Map<String, byte[]> entries = read(mlb);
        String json = new String(entries.get("manifest.json"), StandardCharsets.UTF_8).replace("\"minEngineVersion\" : \"0.1.0\"", "\"minEngineVersion\" : \"0.0.1\"");
        assertThat(json).contains("0.0.1");
        entries.put("manifest.json", json.getBytes(StandardCharsets.UTF_8));
        Path evil = write(tmp.resolve("tampered.mlb"), entries);
        assertReason(() -> TestBundles.verifier().verify(evil), BundleRejectedException.Reason.BAD_SIGNATURE);
    }

    @Test
    void tamperedModelFileIsRejectedWithHashMismatch() throws Exception {
        Path mlb = TestBundles.bundle(tmp, "b", "1.0.0");
        Map<String, byte[]> entries = read(mlb);
        byte[] model = entries.get("antispoof.onnx");
        model[10] ^= 0x55;
        Path evil = write(tmp.resolve("tampered.mlb"), entries);
        assertReason(() -> TestBundles.verifier().verify(evil), BundleRejectedException.Reason.HASH_MISMATCH);
    }

    @Test
    void missingPiecesAreTyped() throws Exception {
        Path mlb = TestBundles.bundle(tmp, "b", "1.0.0");
        Map<String, byte[]> entries = read(mlb);

        Map<String, byte[]> noSig = new HashMap<>(entries); noSig.remove("manifest.sig");
        assertReason(() -> TestBundles.verifier().verify(write(tmp.resolve("nosig.mlb"), noSig)), BundleRejectedException.Reason.MISSING_SIGNATURE);

        Map<String, byte[]> noManifest = new HashMap<>(entries); noManifest.remove("manifest.json");
        assertReason(() -> TestBundles.verifier().verify(write(tmp.resolve("nomanifest.mlb"), noManifest)), BundleRejectedException.Reason.MISSING_MANIFEST);

        Map<String, byte[]> noModel = new HashMap<>(entries); noModel.remove("detector.onnx");
        assertReason(() -> TestBundles.verifier().verify(write(tmp.resolve("nomodel.mlb"), noModel)), BundleRejectedException.Reason.MISSING_FILE);

        Map<String, byte[]> noSelfTest = new HashMap<>(entries); noSelfTest.remove("selftest/live.jpg");
        assertReason(() -> TestBundles.verifier().verify(write(tmp.resolve("noselftest.mlb"), noSelfTest)), BundleRejectedException.Reason.MISSING_FILE);

        Map<String, byte[]> badSig = new HashMap<>(entries); badSig.put("manifest.sig", "!!!not base64!!!".getBytes(StandardCharsets.US_ASCII));
        assertReason(() -> TestBundles.verifier().verify(write(tmp.resolve("badsig.mlb"), badSig)), BundleRejectedException.Reason.BAD_SIGNATURE);

        Map<String, byte[]> zipSlip = new HashMap<>(entries); zipSlip.put("../escape.txt", new byte[] {1});
        assertReason(() -> TestBundles.verifier().verify(write(tmp.resolve("slip.mlb"), zipSlip)), BundleRejectedException.Reason.UNSAFE_PATH);

        Files.writeString(tmp.resolve("garbage.mlb"), "this is not a zip");
        assertReason(() -> TestBundles.verifier().verify(tmp.resolve("garbage.mlb")), BundleRejectedException.Reason.IO_ERROR);
    }

    @Test
    void directoryVerificationDetectsOnDiskTampering() throws Exception {
        Path content = TestBundles.contentDir(tmp);
        BundleManifest m = TestBundles.manifest("dir", "1.0.0");
        byte[] manifestBytes = new BundleWriter(TestBundles.KEYS.getPrivate()).write(m, content, tmp.resolve("dir.mlb"));
        Files.write(content.resolve("manifest.json"), manifestBytes);
        Files.write(content.resolve("manifest.sig"), new BundleSigner(TestBundles.KEYS.getPrivate()).signatureEntry(manifestBytes));
        assertThat(TestBundles.verifier().verifyDirectory(content).manifest().getBundleId()).isEqualTo("dir");
        Files.write(content.resolve("detector.onnx"), new byte[] {9, 9, 9});
        assertReason(() -> TestBundles.verifier().verifyDirectory(content), BundleRejectedException.Reason.HASH_MISMATCH);
    }

    static void assertReason(ThrowingRunnable r, BundleRejectedException.Reason reason) {
        assertThatThrownBy(r::run).isInstanceOf(BundleRejectedException.class)
                .satisfies(e -> assertThat(((BundleRejectedException) e).reason()).isEqualTo(reason));
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static Map<String, byte[]> read(Path mlb) throws IOException {
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        try (ZipFile z = new ZipFile(mlb.toFile())) {
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                try (var in = z.getInputStream(e)) { out.put(e.getName(), in.readAllBytes()); }
            }
        }
        return out;
    }

    static Path write(Path target, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return target;
    }
}
