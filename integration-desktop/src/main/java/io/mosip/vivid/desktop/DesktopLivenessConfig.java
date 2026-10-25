package io.mosip.vivid.desktop;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.device.StreamOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code mosip.registration.liveness.*} from the shape of {@code ApplicationContext.map()} in the Registration
 * Client: a {@code Map<String,Object>} whose values are (trimmed) strings synced from {@code global_param}.
 * <p>
 * Resolution order for a setting {@code X} and workflow {@code W}:
 * <ol>
 *   <li>{@code mosip.registration.liveness.<w>.X} (w = resident|operator|supervisor)</li>
 *   <li>{@code mosip.registration.liveness.X}</li>
 *   <li>compiled-in default, which is {@link LivenessPolicy#defaults(Workflow)} for policy values</li>
 * </ol>
 * Unparseable values are logged and ignored (the default wins), so a typo in a synced config can never switch
 * liveness off or make the policy more permissive by accident. The map is read once; call {@link #fromApplicationMap}
 * again after a config sync.
 */
public final class DesktopLivenessConfig {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopLivenessConfig.class);

    /** Every key this module understands. Suffixes are appended to {@link #ROOT} (optionally with a workflow segment). */
    public static final class Keys {
        private Keys() {}
        public static final String ROOT = "mosip.registration.liveness.";
        public static final String ENABLED = "enabled";
        public static final String POLICY_VERSION = "policy.version";
        public static final String PASSIVE_THRESHOLD = "passive.threshold";
        public static final String PAD_HARD_THRESHOLD = "pad.hard_threshold";
        public static final String PASSIVE_WINDOW_MS = "passive.window_ms";
        public static final String PASSIVE_MIN_FRAMES = "passive.min_frames";
        public static final String ACTIVE_MODE = "active.mode";
        public static final String CHALLENGE_MIN = "challenge.min";
        public static final String CHALLENGE_MAX = "challenge.max";
        public static final String CHALLENGE_POOL = "challenge.pool";
        public static final String CHALLENGE_ACCESSIBILITY_POOL = "challenge.accessibility_pool";
        public static final String CHALLENGE_REQUIRE_DEPTH = "challenge.require_depth";
        public static final String CHALLENGE_TIMEOUT_MS = "challenge.timeout_ms";
        public static final String ACTION_MIN_LATENCY_MS = "action.min_latency_ms";
        public static final String ACTION_MAX_LATENCY_MS = "action.max_latency_ms";
        public static final String RETRIES = "retries";
        public static final String ON_MAX_RETRIES = "on_max_retries";
        public static final String SESSION_TIMEOUT_MS = "session.timeout_ms";
        public static final String FACE_MIN_QUALITY = "face.min_quality";
        public static final String IDENTITY_DRIFT_THRESHOLD = "identity.drift_threshold";
        public static final String IDENTITY_REQUIRED = "identity.required";
        public static final String SIGNAL_WEIGHT_PREFIX = "signal.weight.";
        public static final String RETRY_DELAY_MS = "retry.delay_ms";
        public static final String TICK_MS = "tick_ms";
        public static final String DIAGNOSTICS = "diagnostics.enabled";
        public static final String AUDIO_CUES = "audio_cues";
        public static final String HIGH_CONTRAST = "ui.high_contrast";
        public static final String LOCALE = "locale";
        public static final String ENGINE = "engine";
        public static final String MODEL_DIR = "model.dir";
        public static final String STREAM_TARGET_FPS = "stream.target_fps";
        public static final String STREAM_STALL_TIMEOUT_MS = "stream.stall_timeout_ms";
        public static final String STREAM_RECONNECT_ATTEMPTS = "stream.reconnect_attempts";
        public static final String STREAM_MAX_QUEUED = "stream.max_queued_frames";
        /** Existing Registration Client key reused as the retries fallback (RegistrationConstants.FACE_RETRY_COUNT). */
        public static final String MOSIP_FACE_RETRIES = "mosip.registration.num_of_face_retries";
        /** Existing key reused for the locale fallback. */
        public static final String MOSIP_APP_LANGUAGE = "mosip.registration.application_language";
    }

    private final Map<String, String> values;

    private DesktopLivenessConfig(Map<String, String> values) { this.values = values; }

    /** Snapshot of {@code ApplicationContext.map()}; non-string values are converted with {@code toString()}. */
    public static DesktopLivenessConfig fromApplicationMap(Map<String, ?> applicationMap) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (applicationMap != null) {
            for (Map.Entry<String, ?> e : applicationMap.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (e.getKey().startsWith(Keys.ROOT) || e.getKey().equals(Keys.MOSIP_FACE_RETRIES) || e.getKey().equals(Keys.MOSIP_APP_LANGUAGE)) {
                    copy.put(e.getKey(), String.valueOf(e.getValue()).trim());
                }
            }
        }
        return new DesktopLivenessConfig(Collections.unmodifiableMap(copy));
    }

    public static DesktopLivenessConfig defaults() { return fromApplicationMap(Map.of()); }

    /** The raw liveness-related entries that were read (for diagnostics). */
    public Map<String, String> rawEntries() { return values; }

    // ---- lookup -------------------------------------------------------------------------------------------------

    private Optional<String> raw(Workflow w, String suffix) {
        if (w != null) {
            String v = values.get(Keys.ROOT + w.name().toLowerCase(Locale.ROOT) + "." + suffix);
            if (v != null && !v.isEmpty()) return Optional.of(v);
        }
        String v = values.get(Keys.ROOT + suffix);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v);
    }

    private boolean bool(Workflow w, String suffix, boolean dflt) {
        return raw(w, suffix).map(DesktopLivenessConfig::parseBool).filter(Objects::nonNull).orElse(dflt);
    }

    static Boolean parseBool(String v) {
        switch (v.trim().toUpperCase(Locale.ROOT)) {
            case "Y": case "YES": case "TRUE": case "1": case "ON": case "ENABLE": case "ENABLED": return Boolean.TRUE;
            case "N": case "NO": case "FALSE": case "0": case "OFF": case "DISABLE": case "DISABLED": return Boolean.FALSE;
            default: LOG.warn("liveness config: unparseable boolean '{}', ignoring", v); return null;
        }
    }

    private int integer(Workflow w, String suffix, int dflt) {
        return raw(w, suffix).map(v -> {
            try { return Integer.valueOf(v.trim()); }
            catch (NumberFormatException e) { LOG.warn("liveness config: unparseable integer '{}' for {}, ignoring", v, suffix); return null; }
        }).filter(Objects::nonNull).orElse(dflt);
    }

    private long longVal(Workflow w, String suffix, long dflt) {
        return raw(w, suffix).map(v -> {
            try { return Long.valueOf(v.trim()); }
            catch (NumberFormatException e) { LOG.warn("liveness config: unparseable long '{}' for {}, ignoring", v, suffix); return null; }
        }).filter(Objects::nonNull).orElse(dflt);
    }

    private double dbl(Workflow w, String suffix, double dflt) {
        return raw(w, suffix).map(v -> {
            try { return Double.valueOf(v.trim()); }
            catch (NumberFormatException e) { LOG.warn("liveness config: unparseable number '{}' for {}, ignoring", v, suffix); return null; }
        }).filter(Objects::nonNull).orElse(dflt);
    }

    private <E extends Enum<E>> E enumVal(Workflow w, String suffix, Class<E> type, E dflt) {
        return raw(w, suffix).map(v -> {
            try { return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT).replace('-', '_')); }
            catch (IllegalArgumentException e) { LOG.warn("liveness config: unknown {} '{}', ignoring", type.getSimpleName(), v); return null; }
        }).filter(Objects::nonNull).orElse(dflt);
    }

    private Set<ChallengeType> challengeSet(Workflow w, String suffix, Set<ChallengeType> dflt) {
        Optional<String> v = raw(w, suffix);
        if (v.isEmpty()) return dflt;
        EnumSet<ChallengeType> out = EnumSet.noneOf(ChallengeType.class);
        for (String token : v.get().split("[,;\\s]+")) {
            if (token.isEmpty()) continue;
            try { out.add(ChallengeType.valueOf(token.trim().toUpperCase(Locale.ROOT).replace('-', '_'))); }
            catch (IllegalArgumentException e) { LOG.warn("liveness config: unknown challenge '{}' in {}, skipped", token, suffix); }
        }
        if (out.isEmpty()) { LOG.warn("liveness config: {} parsed to an empty set, using default", suffix); return dflt; }
        return out;
    }

    // ---- public accessors ---------------------------------------------------------------------------------------

    /** Global switch AND per-workflow switch; default on. */
    public boolean enabled(Workflow w) {
        if (!bool(null, Keys.ENABLED, true)) return false;
        return bool(w, Keys.ENABLED, true);
    }

    /**
     * Max liveness attempts per capture. Order: {@code liveness.<w>.retries}, {@code liveness.retries},
     * the client's existing {@code mosip.registration.num_of_face_retries}, then the policy default.
     */
    public int maxRetries(Workflow w) {
        int policyDefault = LivenessPolicy.defaults(w).maxRetries();
        Optional<String> own = raw(w, Keys.RETRIES);
        if (own.isPresent()) return integer(w, Keys.RETRIES, policyDefault);
        String mosip = values.get(Keys.MOSIP_FACE_RETRIES);
        if (mosip != null && w == Workflow.RESIDENT) {
            try { int n = Integer.parseInt(mosip.trim()); if (n > 0) return n; }
            catch (NumberFormatException e) { LOG.warn("liveness config: unparseable {}='{}'", Keys.MOSIP_FACE_RETRIES, mosip); }
        }
        return policyDefault;
    }

    /** Effective policy for the workflow: compiled-in defaults overridden by whatever keys are present. */
    public LivenessPolicy policy(Workflow w) {
        LivenessPolicy d = LivenessPolicy.defaults(w);
        LivenessPolicy.Builder b = LivenessPolicy.builder(w)
                .policyVersion(raw(w, Keys.POLICY_VERSION).orElse(values.isEmpty() ? d.policyVersion() : "global-param"))
                .enabled(enabled(w))
                .passiveThreshold(clamp01(dbl(w, Keys.PASSIVE_THRESHOLD, d.passiveThreshold()), d.passiveThreshold()))
                .padHardThreshold(clamp01(dbl(w, Keys.PAD_HARD_THRESHOLD, d.padHardThreshold()), d.padHardThreshold()))
                .passiveWindowMillis(longVal(w, Keys.PASSIVE_WINDOW_MS, d.passiveWindowMillis()))
                .passiveMinFrames(integer(w, Keys.PASSIVE_MIN_FRAMES, d.passiveMinFrames()))
                .activeMode(enumVal(w, Keys.ACTIVE_MODE, LivenessPolicy.ActiveMode.class, d.activeMode()))
                .minChallenges(Math.max(0, integer(w, Keys.CHALLENGE_MIN, d.minChallenges())))
                .maxChallenges(integer(w, Keys.CHALLENGE_MAX, d.maxChallenges()))
                .challengePool(challengeSet(w, Keys.CHALLENGE_POOL, d.challengePool()))
                .accessibilityPool(challengeSet(w, Keys.CHALLENGE_ACCESSIBILITY_POOL, d.accessibilityPool()))
                .requireDepthRevealingChallenge(bool(w, Keys.CHALLENGE_REQUIRE_DEPTH, d.requireDepthRevealingChallenge()))
                .challengeTimeoutMillis(longVal(w, Keys.CHALLENGE_TIMEOUT_MS, d.challengeTimeoutMillis()))
                .minActionLatencyMillis(longVal(w, Keys.ACTION_MIN_LATENCY_MS, d.minActionLatencyMillis()))
                .maxActionLatencyMillis(longVal(w, Keys.ACTION_MAX_LATENCY_MS, d.maxActionLatencyMillis()))
                .maxRetries(maxRetries(w))
                .onMaxRetries(enumVal(w, Keys.ON_MAX_RETRIES, LivenessPolicy.OnMaxRetries.class, d.onMaxRetries()))
                .sessionTimeoutMillis(longVal(w, Keys.SESSION_TIMEOUT_MS, d.sessionTimeoutMillis()))
                .minFaceQuality(clamp01(dbl(w, Keys.FACE_MIN_QUALITY, d.minFaceQuality()), d.minFaceQuality()))
                .identityDriftThreshold(dbl(w, Keys.IDENTITY_DRIFT_THRESHOLD, d.identityDriftThreshold()))
                .requireIdentityContinuity(bool(w, Keys.IDENTITY_REQUIRED, d.requireIdentityContinuity()))
                .signalWeights(d.signalWeights());
        for (Map.Entry<String, String> e : values.entrySet()) {
            String k = e.getKey();
            String wfPrefix = Keys.ROOT + w.name().toLowerCase(Locale.ROOT) + "." + Keys.SIGNAL_WEIGHT_PREFIX;
            String globalPrefix = Keys.ROOT + Keys.SIGNAL_WEIGHT_PREFIX;
            String signal = k.startsWith(wfPrefix) ? k.substring(wfPrefix.length()) : (k.startsWith(globalPrefix) && !k.substring(globalPrefix.length()).contains(".") ? k.substring(globalPrefix.length()) : null);
            if (signal == null || signal.isEmpty()) continue;
            if (k.startsWith(globalPrefix) && values.containsKey(wfPrefix + signal)) continue; // workflow override wins
            try { b.signalWeight(signal, Double.parseDouble(e.getValue())); }
            catch (NumberFormatException ex) { LOG.warn("liveness config: bad signal weight {}={}", k, e.getValue()); }
        }
        try {
            return b.build();
        } catch (IllegalArgumentException invalid) {
            LOG.error("liveness config: invalid combination ({}); falling back to compiled defaults for {}", invalid.getMessage(), w);
            return d;
        }
    }

    private static double clamp01(double v, double dflt) {
        if (Double.isNaN(v) || v < 0 || v > 1) { LOG.warn("liveness config: value {} out of [0,1], using {}", v, dflt); return dflt; }
        return v;
    }

    public long retryDelayMillis(Workflow w) { return Math.max(0, longVal(w, Keys.RETRY_DELAY_MS, 1500)); }
    public long tickMillis() { return Math.max(50, longVal(null, Keys.TICK_MS, 200)); }
    public boolean diagnosticsEnabled() { return bool(null, Keys.DIAGNOSTICS, false); }
    public boolean audioCues() { return bool(null, Keys.AUDIO_CUES, false); }
    public boolean highContrast() { return bool(null, Keys.HIGH_CONTRAST, false); }
    public String engineId() { return raw(null, Keys.ENGINE).orElse("onnx"); }
    public String modelDir() { return raw(null, Keys.MODEL_DIR).orElse("models"); }

    /** Locale for prompts: {@code liveness.locale}, else the client's application language, else English. */
    public Locale locale() {
        String tag = raw(null, Keys.LOCALE).orElseGet(() -> values.getOrDefault(Keys.MOSIP_APP_LANGUAGE, "eng"));
        return toLocale(tag);
    }

    /** MOSIP uses ISO 639-2 codes (eng, fra, ara); map them to Java locales. */
    static Locale toLocale(String tag) {
        if (tag == null || tag.isBlank()) return Locale.ENGLISH;
        String t = tag.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "eng": case "en": return Locale.ENGLISH;
            case "fra": case "fre": case "fr": return Locale.FRENCH;
            case "ara": case "ar": return new Locale("ar");
            default: return Locale.forLanguageTag(t.length() == 3 ? t.substring(0, 2) : t);
        }
    }

    public StreamOptions streamOptions() {
        StreamOptions d = StreamOptions.defaults();
        return new StreamOptions(
                integer(null, Keys.STREAM_TARGET_FPS, d.targetFps()),
                integer(null, Keys.STREAM_MAX_QUEUED, d.maxQueuedFrames()),
                longVal(null, Keys.STREAM_STALL_TIMEOUT_MS, d.stallTimeoutMillis()),
                integer(null, Keys.STREAM_RECONNECT_ATTEMPTS, d.reconnectAttempts()));
    }

    /** Documentation table of keys with defaults, used by the README generator and the diagnostics pane. */
    public static Map<String, String> documentedDefaults() {
        LivenessPolicy r = LivenessPolicy.defaults(Workflow.RESIDENT);
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Keys.ROOT + Keys.ENABLED, "true");
        m.put(Keys.ROOT + "<workflow>." + Keys.ENABLED, "true");
        m.put(Keys.ROOT + Keys.POLICY_VERSION, r.policyVersion());
        m.put(Keys.ROOT + Keys.PASSIVE_THRESHOLD, "0.80 resident / 0.85 operator / 0.90 supervisor");
        m.put(Keys.ROOT + Keys.PAD_HARD_THRESHOLD, String.valueOf(r.padHardThreshold()));
        m.put(Keys.ROOT + Keys.PASSIVE_WINDOW_MS, String.valueOf(r.passiveWindowMillis()));
        m.put(Keys.ROOT + Keys.PASSIVE_MIN_FRAMES, String.valueOf(r.passiveMinFrames()));
        m.put(Keys.ROOT + Keys.ACTIVE_MODE, "ALWAYS (all workflows; ON_LOW_CONFIDENCE allowed for resident)");
        m.put(Keys.ROOT + Keys.CHALLENGE_MIN, "2 resident / 1 operator / 2 supervisor");
        m.put(Keys.ROOT + Keys.CHALLENGE_MAX, "3 resident / 2 operator / 3 supervisor");
        m.put(Keys.ROOT + Keys.CHALLENGE_POOL, "BLINK,SMILE,TURN_LEFT,TURN_RIGHT,NOD");
        m.put(Keys.ROOT + Keys.CHALLENGE_ACCESSIBILITY_POOL, "BLINK,SMILE,HOLD_STILL");
        m.put(Keys.ROOT + Keys.CHALLENGE_REQUIRE_DEPTH, String.valueOf(r.requireDepthRevealingChallenge()));
        m.put(Keys.ROOT + Keys.CHALLENGE_TIMEOUT_MS, String.valueOf(r.challengeTimeoutMillis()));
        m.put(Keys.ROOT + Keys.ACTION_MIN_LATENCY_MS, String.valueOf(r.minActionLatencyMillis()));
        m.put(Keys.ROOT + Keys.ACTION_MAX_LATENCY_MS, String.valueOf(r.maxActionLatencyMillis()));
        m.put(Keys.ROOT + Keys.RETRIES, "mosip.registration.num_of_face_retries (resident) else 3 / 3 / 2");
        m.put(Keys.ROOT + Keys.ON_MAX_RETRIES, "ASSISTED_CAPTURE_WITH_SUPERVISOR resident / LOCK_AND_ALERT operator, supervisor");
        m.put(Keys.ROOT + Keys.SESSION_TIMEOUT_MS, String.valueOf(r.sessionTimeoutMillis()));
        m.put(Keys.ROOT + Keys.FACE_MIN_QUALITY, String.valueOf(r.minFaceQuality()));
        m.put(Keys.ROOT + Keys.IDENTITY_DRIFT_THRESHOLD, String.valueOf(r.identityDriftThreshold()));
        m.put(Keys.ROOT + Keys.IDENTITY_REQUIRED, String.valueOf(r.requireIdentityContinuity()));
        m.put(Keys.ROOT + Keys.SIGNAL_WEIGHT_PREFIX + "<signalId>", "1.0 (0 disables the signal)");
        m.put(Keys.ROOT + Keys.RETRY_DELAY_MS, "1500");
        m.put(Keys.ROOT + Keys.TICK_MS, "200");
        m.put(Keys.ROOT + Keys.DIAGNOSTICS, "false");
        m.put(Keys.ROOT + Keys.AUDIO_CUES, "false");
        m.put(Keys.ROOT + Keys.HIGH_CONTRAST, "false");
        m.put(Keys.ROOT + Keys.LOCALE, "mosip.registration.application_language else eng");
        m.put(Keys.ROOT + Keys.ENGINE, "onnx");
        m.put(Keys.ROOT + Keys.MODEL_DIR, "models");
        m.put(Keys.ROOT + Keys.STREAM_TARGET_FPS, "15");
        m.put(Keys.ROOT + Keys.STREAM_MAX_QUEUED, "4");
        m.put(Keys.ROOT + Keys.STREAM_STALL_TIMEOUT_MS, "3000");
        m.put(Keys.ROOT + Keys.STREAM_RECONNECT_ATTEMPTS, "5");
        return Collections.unmodifiableMap(m);
    }
}
