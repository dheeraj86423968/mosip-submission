package io.mosip.vivid.bundle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code manifest.json} of a model bundle. Mutable Jackson bean on purpose: the tool fills hashes in
 * before signing, after which the verifier treats the raw bytes, not this object, as the signed artefact.
 *
 * <pre>{@code
 * {
 *   "bundleId": "mosip-vivid-desktop",
 *   "version": "1.2.0",
 *   "platform": "jvm",
 *   "minEngineVersion": "0.1.0",
 *   "models": [ { "name": "yunet", "file": "face_detection_yunet_2023mar.onnx", "sha256": "...",
 *                 "inputShape": [1,3,320,320], "purpose": "face-detection" } ],
 *   "calibration": { "cnn_texture": { "platt_a": -4.2, "platt_b": 1.1, "threshold": 0.5 } },
 *   "selfTest": [ { "file": "selftest/live_01.jpg", "expectedSignal": "cnn_texture", "expectedMin": 0.0, "expectedMax": 0.3 } ]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class BundleManifest {
    /** Platform identifiers accepted in {@link #platform}. */
    public static final String PLATFORM_JVM = "jvm";
    public static final String PLATFORM_ANDROID = "android";

    static final String MANIFEST_ENTRY = "manifest.json";
    static final String SIGNATURE_ENTRY = "manifest.sig";

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private String bundleId;
    private String version;
    private String platform;
    private String minEngineVersion;
    private String description;
    private String createdAt;
    private List<ModelEntry> models = new ArrayList<>();
    private Map<String, Calibration> calibration = new LinkedHashMap<>();
    private List<SelfTestEntry> selfTest = new ArrayList<>();

    /** One model file inside the bundle. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ModelEntry {
        private String name;
        private String file;
        private String sha256;
        private int[] inputShape;
        private String purpose;

        public ModelEntry() {}

        public ModelEntry(String name, String file, String sha256, int[] inputShape, String purpose) {
            this.name = name; this.file = file; this.sha256 = sha256; this.inputShape = inputShape; this.purpose = purpose;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public String getSha256() { return sha256; }
        public void setSha256(String sha256) { this.sha256 = sha256; }
        public int[] getInputShape() { return inputShape; }
        public void setInputShape(int[] inputShape) { this.inputShape = inputShape; }
        /** Free-form role, e.g. {@code face-detection}, {@code anti-spoofing}, {@code embedding}, {@code landmarks}. */
        public String getPurpose() { return purpose; }
        public void setPurpose(String purpose) { this.purpose = purpose; }
    }

    /** Platt scaling parameters for one signal: {@code p = sigmoid(a * raw + b)} and the decision threshold. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Calibration {
        @JsonProperty("platt_a") private double plattA = 1.0;
        @JsonProperty("platt_b") private double plattB = 0.0;
        @JsonProperty("threshold") private double threshold = 0.5;

        public Calibration() {}

        public Calibration(double plattA, double plattB, double threshold) {
            this.plattA = plattA; this.plattB = plattB; this.threshold = threshold;
        }

        public double getPlattA() { return plattA; }
        public void setPlattA(double v) { plattA = v; }
        public double getPlattB() { return plattB; }
        public void setPlattB(double v) { plattB = v; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double v) { threshold = v; }

        /** Applies the calibration to a raw score (logit or any monotonic score). */
        public double apply(double raw) {
            return 1.0 / (1.0 + Math.exp(-(plattA * raw + plattB)));
        }
    }

    /** A packaged image that must produce a signal score inside a known range after loading. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class SelfTestEntry {
        private String file;
        private String expectedSignal;
        private double expectedMin;
        private double expectedMax = 1.0;

        public SelfTestEntry() {}

        public SelfTestEntry(String file, String expectedSignal, double expectedMin, double expectedMax) {
            this.file = file; this.expectedSignal = expectedSignal; this.expectedMin = expectedMin; this.expectedMax = expectedMax;
        }

        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public String getExpectedSignal() { return expectedSignal; }
        public void setExpectedSignal(String expectedSignal) { this.expectedSignal = expectedSignal; }
        public double getExpectedMin() { return expectedMin; }
        public void setExpectedMin(double expectedMin) { this.expectedMin = expectedMin; }
        public double getExpectedMax() { return expectedMax; }
        public void setExpectedMax(double expectedMax) { this.expectedMax = expectedMax; }
    }

    public String getBundleId() { return bundleId; }
    public void setBundleId(String bundleId) { this.bundleId = bundleId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getMinEngineVersion() { return minEngineVersion; }
    public void setMinEngineVersion(String minEngineVersion) { this.minEngineVersion = minEngineVersion; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    /** ISO-8601 creation timestamp written by the tool; informational only. */
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public List<ModelEntry> getModels() { return models; }
    public void setModels(List<ModelEntry> models) { this.models = models == null ? new ArrayList<>() : models; }
    public Map<String, Calibration> getCalibration() { return calibration; }
    public void setCalibration(Map<String, Calibration> calibration) { this.calibration = calibration == null ? new LinkedHashMap<>() : calibration; }
    public List<SelfTestEntry> getSelfTest() { return selfTest; }
    public void setSelfTest(List<SelfTestEntry> selfTest) { this.selfTest = selfTest == null ? new ArrayList<>() : selfTest; }

    /** Looks a model up by its logical name. */
    public Optional<ModelEntry> model(String name) {
        return models.stream().filter(m -> name.equals(m.getName())).findFirst();
    }

    /** Looks a model up by purpose (first match). */
    public Optional<ModelEntry> modelByPurpose(String purpose) {
        return models.stream().filter(m -> purpose.equals(m.getPurpose())).findFirst();
    }

    /** Calibration for a signal id, if the bundle ships one. */
    public Optional<Calibration> calibration(String signalId) {
        return Optional.ofNullable(calibration.get(signalId));
    }

    /** Every relative file path the archive must contain (models and self-test images). */
    public List<String> referencedFiles() {
        List<String> out = new ArrayList<>();
        for (ModelEntry m : models) out.add(m.getFile());
        for (SelfTestEntry s : selfTest) out.add(s.getFile());
        return out;
    }

    /**
     * Structural validation. Hash values are checked for shape only; the verifier checks their correctness.
     *
     * @throws BundleRejectedException with {@link BundleRejectedException.Reason#MALFORMED_MANIFEST}
     */
    public void validate() throws BundleRejectedException {
        require(bundleId != null && ID_PATTERN.matcher(bundleId).matches(), "bundleId must match " + ID_PATTERN);
        require(version != null && SemanticVersion.isValid(version), "version must be semver, got " + version);
        require(PLATFORM_JVM.equals(platform) || PLATFORM_ANDROID.equals(platform), "platform must be jvm or android, got " + platform);
        require(minEngineVersion != null && SemanticVersion.isValid(minEngineVersion), "minEngineVersion must be semver, got " + minEngineVersion);
        require(!models.isEmpty(), "at least one model is required");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ModelEntry m : models) {
            require(m.getName() != null && !m.getName().isBlank(), "model name required");
            require(names.add(m.getName()), "duplicate model name " + m.getName());
            require(m.getFile() != null && isSafeRelativePath(m.getFile()), "unsafe or missing model file path " + m.getFile());
            require(m.getSha256() != null && SHA256_PATTERN.matcher(m.getSha256()).matches(), "model " + m.getName() + " needs a 64-hex sha256");
        }
        for (SelfTestEntry s : selfTest) {
            require(s.getFile() != null && isSafeRelativePath(s.getFile()), "unsafe or missing self-test file " + s.getFile());
            require(s.getExpectedSignal() != null && !s.getExpectedSignal().isBlank(), "self-test expectedSignal required");
            require(s.getExpectedMin() <= s.getExpectedMax(), "self-test expectedMin must be <= expectedMax");
        }
        for (Map.Entry<String, Calibration> e : calibration.entrySet()) {
            require(e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null, "calibration entries need a signal id and parameters");
        }
    }

    private static void require(boolean ok, String msg) throws BundleRejectedException {
        if (!ok) throw new BundleRejectedException(BundleRejectedException.Reason.MALFORMED_MANIFEST, msg);
    }

    /** Relative, forward-slash path with no {@code ..}, no absolute prefix and no drive letter. */
    static boolean isSafeRelativePath(String p) {
        if (p == null || p.isEmpty() || p.length() > 255) return false;
        if (p.startsWith("/") || p.startsWith("\\") || p.contains("\\") || p.contains(":") || p.contains("\0")) return false;
        for (String part : p.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    /** Parses manifest JSON bytes. Does not validate; call {@link #validate()}. */
    public static BundleManifest parse(byte[] json) throws BundleRejectedException {
        try {
            return MAPPER.readValue(json, BundleManifest.class);
        } catch (IOException e) {
            throw new BundleRejectedException(BundleRejectedException.Reason.MALFORMED_MANIFEST, e.getMessage(), e);
        }
    }

    /** Serialises to the canonical pretty-printed JSON bytes that get signed. */
    public byte[] toJsonBytes() {
        try {
            return MAPPER.writeValueAsBytes(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest not serialisable", e);
        }
    }

    @Override public String toString() {
        return "BundleManifest{" + bundleId + "@" + version + " " + platform + ", " + models.size() + " models}";
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BundleManifest)) return false;
        BundleManifest other = (BundleManifest) o;
        return java.util.Arrays.equals(toJsonBytes(), other.toJsonBytes());
    }

    @Override public int hashCode() { return Objects.hash(bundleId, version); }
}
