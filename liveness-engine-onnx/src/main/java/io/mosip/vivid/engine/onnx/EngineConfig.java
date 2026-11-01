package io.mosip.vivid.engine.onnx;

import io.mosip.vivid.api.Signals;
import io.mosip.vivid.bundle.BundleManifest;
import io.mosip.vivid.bundle.InstalledBundle;
import io.mosip.vivid.engine.signal.DepthIrSettings;
import io.mosip.vivid.engine.signal.PlattCalibration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Immutable engine configuration: model paths and recipes, thread count, enabled signals, per-signal Platt
 * calibration, self-test cases and the bundle identity reported in {@code EngineInfo}. Built with the
 * {@link Builder}, from a {@code .properties} file ({@link #fromProperties}) or from an installed model bundle
 * ({@link #fromBundle}).
 *
 * <p>Property keys (all optional; paths are resolved against the properties file's directory):
 * <pre>
 * vivid.engine.threads=2                     # default: 1 on machines with 2 cores or fewer, else 2
 * vivid.engine.executionProvider=cpu          # cpu (default) | cuda | dnnl | openvino | directml | coreml | auto;
 *                                             # falls back to cpu with a warning when the ORT build lacks it
 * vivid.engine.detector.model=models/face_detection_yunet_2023mar.onnx
 * vivid.engine.detector.scoreThreshold=0.7
 * vivid.engine.detector.nmsThreshold=0.3
 * vivid.engine.antispoof.model=models/antispoof_minifasnetv2se_128.onnx
 * vivid.engine.antispoof.inputSize=128          # omit to use the preset for the file name
 * vivid.engine.antispoof.cropScale=1.5
 * vivid.engine.antispoof.colorOrder=RGB         # or BGR
 * vivid.engine.antispoof.realClassIndex=0
 * vivid.engine.antispoof.inputScale=0.00392157  # multiplier for 0..255 pixels; preset: 1/255, or 1 for Silent-Face
 * vivid.engine.antispoof.flip=false             # also score the mirrored crop and average (test-time augmentation)
 * vivid.engine.antispoof.cropMode=square         # square (reflect-padded) | fit (Silent-Face: kept inside the frame)
 * vivid.engine.antispoof2.model=...             # optional second scale (same sub-keys)
 * vivid.engine.embedding.model=models/face_recognition_sface_2021dec_int8.onnx
 * vivid.engine.embedding.everyFrames=5
 * vivid.engine.landmarks.model=                 # reserved for a dense landmark model
 * vivid.engine.signals=cnn_texture,moire,color_stats,rppg,parallax,quality,injection,depth,ir
 * vivid.engine.depth.byteOrder=LITTLE_ENDIAN    # S8 GRAY16 planes (depth and 16-bit IR); or BIG_ENDIAN
 * vivid.engine.depth.scaleMm=1.0                # millimetres per depth count
 * vivid.engine.depth.minMm=150 / .maxMm=2000    # valid depth range
 * vivid.engine.depth.quadReliefMm=2.5           # quadratic-fit relief at which depth evidence is neutral
 * vivid.engine.depth.noseProtrusionMm=6         # nose protrusion at which depth evidence is neutral
 * vivid.engine.depth.hardVeto=true              # planar face region may veto
 * vivid.engine.depth.vetoPlaneReliefMm=2.0 / .vetoMinConfidence=0.8 / .maxFusedProbability=0.85
 * vivid.engine.ir.fullScale16=65535 / .darkFraction=0.10 / .flatCv=0.06 / .maxFusedProbability=0.85
 * vivid.engine.injection.sourceIsSystemCamera=false
 * vivid.engine.calibration.&lt;signalId&gt;.a / .b / .threshold
 * vivid.engine.bundle.id / vivid.engine.bundle.version
 * vivid.engine.selftest.&lt;n&gt;.file / .signal / .min / .max
 * </pre>
 */
public final class EngineConfig {
    public static final String PREFIX = "vivid.engine.";
    public static final Set<String> ALL_SIGNALS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            Signals.CNN_TEXTURE, Signals.MOIRE, Signals.COLOR_STATS, Signals.RPPG, Signals.PARALLAX, Signals.QUALITY, Signals.INJECTION,
            Signals.DEPTH, Signals.IR)));

    /** A packaged image that must produce a signal score inside a range. */
    public static final class SelfTestCase {
        private final Path image;
        private final String signalId;
        private final double min, max;

        public SelfTestCase(Path image, String signalId, double min, double max) {
            this.image = Objects.requireNonNull(image); this.signalId = Objects.requireNonNull(signalId); this.min = min; this.max = max;
        }
        public Path image() { return image; }
        public String signalId() { return signalId; }
        public double min() { return min; }
        public double max() { return max; }
        @Override public String toString() { return image.getFileName() + ":" + signalId + "[" + min + "," + max + "]"; }
    }

    private final int threads;
    private final ExecutionProvider executionProvider;
    private final Path detectorModel;
    private final double detectorScoreThreshold, detectorNmsThreshold;
    private final List<AntiSpoofModelConfig> antiSpoofModels;
    private final Path embeddingModel;
    private final int embeddingEveryFrames;
    private final Path landmarkModel;
    private final Set<String> enabledSignals;
    private final Map<String, PlattCalibration> calibration;
    private final boolean sourceIsSystemCamera;
    private final String bundleId, bundleVersion;
    private final List<SelfTestCase> selfTests;
    private final DepthIrSettings depthIr;

    private EngineConfig(Builder b) {
        threads = b.threads; executionProvider = Objects.requireNonNull(b.executionProvider, "executionProvider"); detectorModel = b.detectorModel;
        detectorScoreThreshold = b.detectorScoreThreshold; detectorNmsThreshold = b.detectorNmsThreshold;
        antiSpoofModels = List.copyOf(b.antiSpoofModels); embeddingModel = b.embeddingModel; embeddingEveryFrames = b.embeddingEveryFrames;
        landmarkModel = b.landmarkModel; enabledSignals = Collections.unmodifiableSet(new LinkedHashSet<>(b.enabledSignals));
        calibration = Collections.unmodifiableMap(new LinkedHashMap<>(b.calibration)); sourceIsSystemCamera = b.sourceIsSystemCamera;
        bundleId = b.bundleId; bundleVersion = b.bundleVersion; selfTests = List.copyOf(b.selfTests);
        depthIr = Objects.requireNonNull(b.depthIr, "depthIr");
        if (threads < 1) throw new IllegalArgumentException("threads must be >= 1");
        if (embeddingEveryFrames < 1) throw new IllegalArgumentException("embeddingEveryFrames must be >= 1");
    }

    public static Builder builder() { return new Builder(); }

    public int threads() { return threads; }
    /** ONNX Runtime execution provider requested for every model; {@link ExecutionProvider#CPU} by default. */
    public ExecutionProvider executionProvider() { return executionProvider; }
    public Optional<Path> detectorModel() { return Optional.ofNullable(detectorModel); }
    public double detectorScoreThreshold() { return detectorScoreThreshold; }
    public double detectorNmsThreshold() { return detectorNmsThreshold; }
    public List<AntiSpoofModelConfig> antiSpoofModels() { return antiSpoofModels; }
    public Optional<Path> embeddingModel() { return Optional.ofNullable(embeddingModel); }
    public int embeddingEveryFrames() { return embeddingEveryFrames; }
    public Optional<Path> landmarkModel() { return Optional.ofNullable(landmarkModel); }
    public Set<String> enabledSignals() { return enabledSignals; }
    public boolean isEnabled(String signalId) { return enabledSignals.contains(signalId); }
    public PlattCalibration calibration(String signalId) { return calibration.getOrDefault(signalId, PlattCalibration.identity()); }
    public Map<String, PlattCalibration> calibrations() { return calibration; }
    public boolean sourceIsSystemCamera() { return sourceIsSystemCamera; }
    public String bundleId() { return bundleId; }
    public String bundleVersion() { return bundleVersion; }
    public List<SelfTestCase> selfTests() { return selfTests; }
    /** S8 depth / IR thresholds and plane conventions. */
    public DepthIrSettings depthIr() { return depthIr; }

    /** Loads a properties file; relative model paths resolve against the file's parent directory. */
    public static EngineConfig load(Path propertiesFile) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(propertiesFile)) {
            p.load(in);
        }
        Path base = propertiesFile.toAbsolutePath().getParent();
        return fromProperties(p, base);
    }

    public static EngineConfig fromProperties(Properties p, Path baseDir) {
        Builder b = builder();
        b.threads(intOf(p, "threads", b.threads));
        String ep = p.getProperty(PREFIX + "executionProvider");
        if (ep != null && !ep.isBlank()) b.executionProvider(ExecutionProvider.parse(ep));
        path(p, "detector.model", baseDir).ifPresent(b::detectorModel);
        b.detectorThresholds(doubleOf(p, "detector.scoreThreshold", b.detectorScoreThreshold), doubleOf(p, "detector.nmsThreshold", b.detectorNmsThreshold));
        for (String key : new String[] {"antispoof", "antispoof2"}) {
            path(p, key + ".model", baseDir).ifPresent(m -> {
                AntiSpoofModelConfig preset = AntiSpoofModelConfig.preset(m, intOf(p, key + ".inputSize", 0));
                String order = p.getProperty(PREFIX + key + ".colorOrder");
                b.antiSpoofModel(new AntiSpoofModelConfig(m,
                        intOf(p, key + ".inputSize", preset.inputSize()),
                        doubleOf(p, key + ".cropScale", preset.cropScale()),
                        order == null ? preset.bgr() : order.trim().equalsIgnoreCase("BGR"),
                        intOf(p, key + ".realClassIndex", preset.realClassIndex()),
                        (float) doubleOf(p, key + ".inputScale", preset.inputScale()),
                        Boolean.parseBoolean(p.getProperty(PREFIX + key + ".flip", String.valueOf(preset.flip())).trim()),
                        cropModeFit(p.getProperty(PREFIX + key + ".cropMode"), preset.fitCrop())));
            });
        }
        path(p, "embedding.model", baseDir).ifPresent(b::embeddingModel);
        b.embeddingEveryFrames(intOf(p, "embedding.everyFrames", b.embeddingEveryFrames));
        path(p, "landmarks.model", baseDir).ifPresent(b::landmarkModel);
        String signals = p.getProperty(PREFIX + "signals");
        if (signals != null && !signals.isBlank()) {
            Set<String> s = new LinkedHashSet<>();
            for (String id : signals.split(",")) if (!id.isBlank()) s.add(id.trim());
            b.enabledSignals(s);
        }
        b.sourceIsSystemCamera(Boolean.parseBoolean(p.getProperty(PREFIX + "injection.sourceIsSystemCamera", "false")));
        for (String id : ALL_SIGNALS) {
            String a = p.getProperty(PREFIX + "calibration." + id + ".a");
            if (a != null) {
                b.calibration(id, new PlattCalibration(Double.parseDouble(a.trim()), doubleOf(p, "calibration." + id + ".b", 0), doubleOf(p, "calibration." + id + ".threshold", 0.5)));
            }
        }
        b.bundle(p.getProperty(PREFIX + "bundle.id", "none"), p.getProperty(PREFIX + "bundle.version", "none"));
        b.depthIr(depthIrOf(p));
        for (int i = 0; i < 1000; i++) {
            String f = p.getProperty(PREFIX + "selftest." + i + ".file");
            if (f == null) continue;
            b.selfTest(new SelfTestCase(resolve(baseDir, f), p.getProperty(PREFIX + "selftest." + i + ".signal", Signals.CNN_TEXTURE),
                    doubleOf(p, "selftest." + i + ".min", 0), doubleOf(p, "selftest." + i + ".max", 1)));
        }
        return b.build();
    }

    /**
     * Builds a configuration from a verified, installed bundle. Models are looked up by {@code purpose}:
     * {@code face-detection}, {@code anti-spoofing} (and {@code anti-spoofing-2}), {@code embedding}, {@code landmarks}.
     * Calibration and self-tests come straight from the manifest.
     */
    public static EngineConfig fromBundle(InstalledBundle bundle, Builder base) {
        Builder b = base == null ? builder() : base;
        BundleManifest m = bundle.manifest();
        bundle.modelPathByPurpose("face-detection").ifPresent(b::detectorModel);
        for (String purpose : new String[] {"anti-spoofing", "anti-spoofing-2"}) {
            m.modelByPurpose(purpose).ifPresent(e -> {
                int[] shape = e.getInputShape();
                int hint = shape != null && shape.length == 4 ? shape[2] : 0;
                b.antiSpoofModel(AntiSpoofModelConfig.preset(bundle.resolve(e.getFile()), hint));
            });
        }
        bundle.modelPathByPurpose("embedding").ifPresent(b::embeddingModel);
        bundle.modelPathByPurpose("landmarks").ifPresent(b::landmarkModel);
        for (Map.Entry<String, BundleManifest.Calibration> e : m.getCalibration().entrySet()) {
            b.calibration(e.getKey(), new PlattCalibration(e.getValue().getPlattA(), e.getValue().getPlattB(), e.getValue().getThreshold()));
        }
        for (BundleManifest.SelfTestEntry s : m.getSelfTest()) {
            b.selfTest(new SelfTestCase(bundle.resolve(s.getFile()), s.getExpectedSignal(), s.getExpectedMin(), s.getExpectedMax()));
        }
        b.bundle(m.getBundleId(), m.getVersion());
        return b.build();
    }

    private static DepthIrSettings depthIrOf(Properties p) {
        DepthIrSettings d = DepthIrSettings.defaults();
        String order = p.getProperty(PREFIX + "depth.byteOrder");
        return DepthIrSettings.builder()
                .depthByteOrder(order != null && order.trim().equalsIgnoreCase("BIG_ENDIAN") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN)
                .depthScaleMm(doubleOf(p, "depth.scaleMm", d.depthScaleMm()))
                .depthRangeMm(doubleOf(p, "depth.minMm", d.minDepthMm()), doubleOf(p, "depth.maxMm", d.maxDepthMm()))
                .quadReliefMm(doubleOf(p, "depth.quadReliefMm", d.quadReliefMm()))
                .noseProtrusionMm(doubleOf(p, "depth.noseProtrusionMm", d.noseProtrusionMm()))
                .depthHardVeto(Boolean.parseBoolean(p.getProperty(PREFIX + "depth.hardVeto", String.valueOf(d.depthHardVeto())).trim()))
                .vetoPlaneReliefMm(doubleOf(p, "depth.vetoPlaneReliefMm", d.vetoPlaneReliefMm()))
                .vetoMinConfidence(doubleOf(p, "depth.vetoMinConfidence", d.vetoMinConfidence()))
                .depthMaxFusedProbability(doubleOf(p, "depth.maxFusedProbability", d.depthMaxFusedProbability()))
                .irFullScale16(doubleOf(p, "ir.fullScale16", d.irFullScale16()))
                .irDarkFraction(doubleOf(p, "ir.darkFraction", d.irDarkFraction()))
                .irFlatCv(doubleOf(p, "ir.flatCv", d.irFlatCv()))
                .irMaxFusedProbability(doubleOf(p, "ir.maxFusedProbability", d.irMaxFusedProbability()))
                .build();
    }

    private static boolean cropModeFit(String v, boolean def) {
        if (v == null || v.isBlank()) return def;
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "fit": return true;
            case "square": return false;
            default: throw new IllegalArgumentException("cropMode must be 'square' or 'fit', got '" + v + "'");
        }
    }

    private static Optional<Path> path(Properties p, String key, Path base) {
        String v = p.getProperty(PREFIX + key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(resolve(base, v.trim()));
    }

    private static Path resolve(Path base, String v) {
        Path p = Path.of(v);
        return p.isAbsolute() || base == null ? p : base.resolve(p);
    }

    private static int intOf(Properties p, String key, int def) {
        String v = p.getProperty(PREFIX + key);
        return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
    }

    private static double doubleOf(Properties p, String key, double def) {
        String v = p.getProperty(PREFIX + key);
        return v == null || v.isBlank() ? def : Double.parseDouble(v.trim());
    }

    @Override public String toString() {
        return "EngineConfig{threads=" + threads + ", provider=" + executionProvider + ", detector=" + detectorModel + ", antispoof=" + antiSpoofModels
                + ", embedding=" + embeddingModel + ", signals=" + enabledSignals + ", bundle=" + bundleId + "@" + bundleVersion + "}";
    }

    /** Mutable builder with the engine defaults. */
    public static final class Builder {
        // Two ORT threads on a 2-core machine measured about 2x slower than one (docs/testing/performance.md).
        private int threads = Runtime.getRuntime().availableProcessors() <= 2 ? 1 : 2;
        private ExecutionProvider executionProvider = ExecutionProvider.CPU;
        private Path detectorModel;
        private double detectorScoreThreshold = 0.7, detectorNmsThreshold = 0.3;
        private final List<AntiSpoofModelConfig> antiSpoofModels = new ArrayList<>();
        private Path embeddingModel;
        private int embeddingEveryFrames = 5;
        private Path landmarkModel;
        private Set<String> enabledSignals = new LinkedHashSet<>(ALL_SIGNALS);
        private final Map<String, PlattCalibration> calibration = new LinkedHashMap<>();
        private boolean sourceIsSystemCamera;
        private String bundleId = "none", bundleVersion = "none";
        private final List<SelfTestCase> selfTests = new ArrayList<>();
        private DepthIrSettings depthIr = DepthIrSettings.defaults();

        public Builder threads(int v) { threads = v; return this; }
        /** Opt-in hardware acceleration; see {@link ExecutionProvider}. Null means CPU. */
        public Builder executionProvider(ExecutionProvider v) { executionProvider = v == null ? ExecutionProvider.CPU : v; return this; }
        public Builder detectorModel(Path v) { detectorModel = v; return this; }
        public Builder detectorThresholds(double score, double nms) { detectorScoreThreshold = score; detectorNmsThreshold = nms; return this; }
        public Builder antiSpoofModel(AntiSpoofModelConfig v) { antiSpoofModels.add(Objects.requireNonNull(v)); return this; }
        public Builder clearAntiSpoofModels() { antiSpoofModels.clear(); return this; }
        public Builder embeddingModel(Path v) { embeddingModel = v; return this; }
        public Builder embeddingEveryFrames(int v) { embeddingEveryFrames = v; return this; }
        public Builder landmarkModel(Path v) { landmarkModel = v; return this; }
        public Builder enabledSignals(Set<String> v) { enabledSignals = new LinkedHashSet<>(v); return this; }
        public Builder disableSignal(String id) { enabledSignals.remove(id); return this; }
        public Builder calibration(String signalId, PlattCalibration c) { calibration.put(signalId, c); return this; }
        public Builder sourceIsSystemCamera(boolean v) { sourceIsSystemCamera = v; return this; }
        public Builder bundle(String id, String version) { bundleId = id == null ? "none" : id; bundleVersion = version == null ? "none" : version; return this; }
        public Builder selfTest(SelfTestCase c) { selfTests.add(c); return this; }
        public Builder depthIr(DepthIrSettings v) { depthIr = Objects.requireNonNull(v); return this; }
        public EngineConfig build() { return new EngineConfig(this); }

        /** Lower-case file name helper used by discovery. */
        static String lower(Path p) { return p.getFileName().toString().toLowerCase(Locale.ROOT); }
    }
}
