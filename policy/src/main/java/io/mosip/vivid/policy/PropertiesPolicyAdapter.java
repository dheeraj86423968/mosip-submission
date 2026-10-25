package io.mosip.vivid.policy;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Flattens a {@link PolicyDocument} into {@code mosip.registration.liveness.*} property keys and back, so the
 * policy can ride the Registration Client's existing global-parameter sync (PLAN.md section 5.2).
 *
 * <p>Key layout (workflow names are lower-case):</p>
 * <pre>
 * mosip.registration.liveness.schema.version=1.0
 * mosip.registration.liveness.version=2026.03
 * mosip.registration.liveness.resident.enabled=true
 * mosip.registration.liveness.resident.passive.threshold=0.8
 * mosip.registration.liveness.resident.active.mode=ON_LOW_CONFIDENCE
 * mosip.registration.liveness.resident.active.pool=BLINK,SMILE,TURN_LEFT
 * mosip.registration.liveness.resident.signals.rppg.weight=0.8
 * mosip.registration.liveness.device.min.fps=10
 * mosip.registration.liveness.policy.json={...}        escape hatch: full document, takes precedence
 * mosip.registration.liveness.policy.signature=base64  detached signature over the escape-hatch document
 * </pre>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class PropertiesPolicyAdapter {

    /** Common prefix of every key. */
    public static final String PREFIX = "mosip.registration.liveness.";
    /** Key holding the full JSON document; when present it takes precedence over flattened keys. */
    public static final String JSON_KEY = PREFIX + "policy.json";
    /** Key holding the detached Base64 signature of {@link #JSON_KEY}. */
    public static final String SIGNATURE_KEY = PREFIX + "policy.signature";

    private static final String SCHEMA_VERSION = PREFIX + "schema.version";
    private static final String VERSION = PREFIX + "version";
    private static final String DEVICE_MIN_FPS = PREFIX + "device.min.fps";
    private static final String DEVICE_RECONNECT = PREFIX + "device.reconnect.attempts";
    private static final String DEVICE_STALL = PREFIX + "device.stall.timeout.ms";
    private static final String DIAGNOSTICS_MODE = PREFIX + "diagnostics.mode";

    private final PolicyLoader loader;

    /** Adapter using a default loader for the JSON escape hatch. */
    public PropertiesPolicyAdapter() {
        this(new PolicyLoader());
    }

    /** Adapter using the given loader for the JSON escape hatch. */
    public PropertiesPolicyAdapter(PolicyLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** Flattens a document into properties. Only non-null fields produce keys. */
    public Properties toProperties(PolicyDocument doc) {
        Objects.requireNonNull(doc, "document");
        Properties p = new Properties();
        put(p, SCHEMA_VERSION, doc.schemaVersion());
        put(p, VERSION, doc.version());
        for (Map.Entry<Workflow, WorkflowPolicy> e : doc.workflows().entrySet()) {
            writeWorkflow(p, PREFIX + e.getKey().name().toLowerCase(Locale.ROOT) + ".", e.getValue());
        }
        if (doc.device() != null) {
            put(p, DEVICE_MIN_FPS, doc.device().minFps());
            put(p, DEVICE_RECONNECT, doc.device().reconnectAttempts());
            put(p, DEVICE_STALL, doc.device().stallTimeoutMs());
        }
        if (doc.diagnostics() != null) {
            put(p, DIAGNOSTICS_MODE, doc.diagnostics().mode());
        }
        return p;
    }

    /**
     * Rebuilds a document from properties. If {@link #JSON_KEY} is present it is parsed and returned, ignoring
     * flattened keys; otherwise the flattened keys are unflattened. Returns empty when no liveness key is present.
     *
     * @throws PolicyException on malformed values
     */
    public Optional<PolicyDocument> fromProperties(Properties props) {
        Objects.requireNonNull(props, "properties");
        String json = props.getProperty(JSON_KEY);
        if (json != null && !Compat.isBlank(json)) {
            return Optional.of(loader.parse(json));
        }
        boolean any = props.stringPropertyNames().stream().anyMatch(k -> k.startsWith(PREFIX));
        if (!any) {
            return Optional.empty();
        }
        Map<Workflow, WorkflowPolicy> workflows = new EnumMap<>(Workflow.class);
        for (Workflow wf : Workflow.values()) {
            String prefix = PREFIX + wf.name().toLowerCase(Locale.ROOT) + ".";
            if (props.stringPropertyNames().stream().anyMatch(k -> k.startsWith(prefix))) {
                workflows.put(wf, readWorkflow(props, prefix));
            }
        }
        DevicePolicy device = null;
        if (hasAny(props, DEVICE_MIN_FPS, DEVICE_RECONNECT, DEVICE_STALL)) {
            device = new DevicePolicy(getInt(props, DEVICE_MIN_FPS), getInt(props, DEVICE_RECONNECT), getLong(props, DEVICE_STALL));
        }
        DiagnosticsPolicy diagnostics = null;
        if (props.getProperty(DIAGNOSTICS_MODE) != null) {
            diagnostics = new DiagnosticsPolicy(getEnum(props, DIAGNOSTICS_MODE, DiagnosticsPolicy.Mode.class));
        }
        String schema = props.getProperty(SCHEMA_VERSION, PolicyDocument.CURRENT_SCHEMA_VERSION);
        return Optional.of(new PolicyDocument(schema, props.getProperty(VERSION), workflows, device, diagnostics));
    }

    /** The detached signature stored next to the escape-hatch document, if any. */
    public Optional<String> signature(Properties props) {
        return Optional.ofNullable(props.getProperty(SIGNATURE_KEY)).filter(s -> !Compat.isBlank(s));
    }

    /** Stores a full document (and optional signature) under the escape-hatch keys. */
    public void putJson(Properties props, String json, String signatureBase64) {
        props.setProperty(JSON_KEY, Objects.requireNonNull(json));
        if (signatureBase64 != null) {
            props.setProperty(SIGNATURE_KEY, signatureBase64);
        } else {
            props.remove(SIGNATURE_KEY);
        }
    }

    // ---- flatten -------------------------------------------------------------------------------------------

    private static void writeWorkflow(Properties p, String prefix, WorkflowPolicy w) {
        if (w == null) {
            return;
        }
        put(p, prefix + "enabled", w.enabled());
        put(p, prefix + "passive.threshold", w.passiveThreshold());
        put(p, prefix + "pad.hard.threshold", w.padHardThreshold());
        put(p, prefix + "passive.window.ms", w.passiveWindowMs());
        put(p, prefix + "passive.min.frames", w.passiveMinFrames());
        put(p, prefix + "retries.max", w.maxRetries());
        put(p, prefix + "retries.action", w.onMaxRetries());
        put(p, prefix + "session.timeout.ms", w.sessionTimeoutMs());
        put(p, prefix + "quality.min", w.minFaceQuality());
        if (w.identity() != null) {
            put(p, prefix + "identity.required", w.identity().required());
            put(p, prefix + "identity.drift.threshold", w.identity().driftThreshold());
        }
        if (w.active() != null) {
            ActivePolicy a = w.active();
            put(p, prefix + "active.mode", a.mode());
            put(p, prefix + "active.min", a.minChallenges());
            put(p, prefix + "active.max", a.maxChallenges());
            putList(p, prefix + "active.pool", a.pool());
            putList(p, prefix + "active.accessibility.pool", a.accessibilityPool());
            put(p, prefix + "active.require.depth", a.requireDepthRevealing());
            put(p, prefix + "active.timeout.ms", a.timeoutMs());
            put(p, prefix + "active.latency.min.ms", a.minActionLatencyMs());
            put(p, prefix + "active.latency.max.ms", a.maxActionLatencyMs());
        }
        if (w.signals() != null) {
            for (Map.Entry<String, SignalPolicy> e : w.signals().entrySet()) {
                String sp = prefix + "signals." + e.getKey() + ".";
                if (e.getValue() != null) {
                    put(p, sp + "enabled", e.getValue().enabled());
                    put(p, sp + "weight", e.getValue().weight());
                }
            }
        }
    }

    private static void put(Properties p, String key, Object value) {
        if (value != null) {
            p.setProperty(key, String.valueOf(value));
        }
    }

    private static void putList(Properties p, String key, List<ChallengeType> list) {
        if (list != null) {
            p.setProperty(key, list.stream().map(Enum::name).collect(Collectors.joining(",")));
        }
    }

    // ---- unflatten -----------------------------------------------------------------------------------------

    private static WorkflowPolicy readWorkflow(Properties p, String prefix) {
        IdentityPolicy identity = null;
        if (hasAny(p, prefix + "identity.required", prefix + "identity.drift.threshold")) {
            identity = new IdentityPolicy(getBool(p, prefix + "identity.required"), getDouble(p, prefix + "identity.drift.threshold"));
        }
        ActivePolicy active = null;
        String ap = prefix + "active.";
        if (p.stringPropertyNames().stream().anyMatch(k -> k.startsWith(ap))) {
            active = new ActivePolicy(
                    getEnum(p, ap + "mode", LivenessPolicy.ActiveMode.class),
                    getInt(p, ap + "min"), getInt(p, ap + "max"),
                    getList(p, ap + "pool"), getList(p, ap + "accessibility.pool"),
                    getBool(p, ap + "require.depth"),
                    getLong(p, ap + "timeout.ms"), getLong(p, ap + "latency.min.ms"), getLong(p, ap + "latency.max.ms"));
        }
        Map<String, SignalPolicy> signals = null;
        String sp = prefix + "signals.";
        TreeSet<String> ids = p.stringPropertyNames().stream()
                .filter(k -> k.startsWith(sp))
                .map(k -> k.substring(sp.length()))
                .map(k -> k.substring(0, k.indexOf('.') < 0 ? k.length() : k.indexOf('.')))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!ids.isEmpty()) {
            signals = new LinkedHashMap<>();
            for (String id : ids) {
                signals.put(id, new SignalPolicy(getBool(p, sp + id + ".enabled"), getDouble(p, sp + id + ".weight")));
            }
        }
        return new WorkflowPolicy(
                getBool(p, prefix + "enabled"),
                getDouble(p, prefix + "passive.threshold"),
                getDouble(p, prefix + "pad.hard.threshold"),
                getLong(p, prefix + "passive.window.ms"),
                getInt(p, prefix + "passive.min.frames"),
                active,
                getInt(p, prefix + "retries.max"),
                getEnum(p, prefix + "retries.action", LivenessPolicy.OnMaxRetries.class),
                getLong(p, prefix + "session.timeout.ms"),
                getDouble(p, prefix + "quality.min"),
                identity,
                signals);
    }

    private static boolean hasAny(Properties p, String... keys) {
        return Arrays.stream(keys).anyMatch(k -> p.getProperty(k) != null);
    }

    private static String raw(Properties p, String key) {
        String v = p.getProperty(key);
        return v == null || Compat.isBlank(v) ? null : v.trim();
    }

    private static Boolean getBool(Properties p, String key) {
        String v = raw(p, key);
        if (v == null) {
            return null;
        }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(v);
        }
        throw new PolicyException(key + ": expected true or false but was '" + v + "'");
    }

    private static Integer getInt(Properties p, String key) {
        String v = raw(p, key);
        try {
            return v == null ? null : Integer.valueOf(v);
        } catch (NumberFormatException e) {
            throw new PolicyException(key + ": expected an integer but was '" + v + "'", e);
        }
    }

    private static Long getLong(Properties p, String key) {
        String v = raw(p, key);
        try {
            return v == null ? null : Long.valueOf(v);
        } catch (NumberFormatException e) {
            throw new PolicyException(key + ": expected an integer but was '" + v + "'", e);
        }
    }

    private static Double getDouble(Properties p, String key) {
        String v = raw(p, key);
        try {
            return v == null ? null : Double.valueOf(v);
        } catch (NumberFormatException e) {
            throw new PolicyException(key + ": expected a number but was '" + v + "'", e);
        }
    }

    private static <E extends Enum<E>> E getEnum(Properties p, String key, Class<E> type) {
        String v = raw(p, key);
        if (v == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PolicyException(key + ": '" + v + "' is not one of " + Arrays.toString(type.getEnumConstants()), e);
        }
    }

    private static List<ChallengeType> getList(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) {
            return null;
        }
        List<ChallengeType> out = new ArrayList<>();
        for (String item : v.split(",")) {
            String t = item.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(ChallengeType.valueOf(t.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new PolicyException(key + ": '" + t + "' is not a challenge type", e);
            }
        }
        return out;
    }
}
