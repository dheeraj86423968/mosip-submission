package io.mosip.vivid.telemetry.audit;

import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.telemetry.json.Json;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One immutable audit entry. Carries identifiers, scores and timings, never biometric data; attribute keys that
 * look biometric are removed by {@link AuditRedactor} at construction.
 */
public final class AuditRecord {

    private final LivenessAuditEvent event;
    private final String sessionId;
    private final Workflow workflow;
    private final Instant timestamp;
    private final Map<String, String> attributes;
    private final String evidenceHash;

    private AuditRecord(Builder b) {
        this.event = Objects.requireNonNull(b.event, "event");
        this.sessionId = Objects.requireNonNullElse(b.sessionId, "");
        this.workflow = b.workflow;
        this.timestamp = Objects.requireNonNull(b.timestamp, "timestamp");
        this.attributes = Collections.unmodifiableMap(AuditRedactor.redact(b.attributes));
        this.evidenceHash = b.evidenceHash;
    }

    /** Starts a record for the given event. */
    public static Builder builder(LivenessAuditEvent event) {
        return new Builder(event);
    }

    public LivenessAuditEvent event() { return event; }
    /** Session id, or empty string for events outside a session (policy and model events). */
    public String sessionId() { return sessionId; }
    public Optional<Workflow> workflow() { return Optional.ofNullable(workflow); }
    public Instant timestamp() { return timestamp; }
    /** Redacted, unmodifiable attributes in insertion order. */
    public Map<String, String> attributes() { return attributes; }
    /** Tamper-evidence hash of the decision record, present on terminal events. */
    public Optional<String> evidenceHash() { return Optional.ofNullable(evidenceHash); }

    /** Serialises to one line of JSON (no embedded newlines), the format used by {@link OfflineAuditQueue}. */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", event.name());
        m.put("severity", event.severity().name());
        m.put("sessionId", sessionId);
        m.put("workflow", workflow == null ? null : workflow.name());
        m.put("timestamp", timestamp.toString());
        m.put("attributes", attributes);
        m.put("evidenceHash", evidenceHash);
        return Json.write(m);
    }

    /**
     * Parses a line produced by {@link #toJson()}.
     *
     * @throws IllegalArgumentException if the line is not a valid record
     */
    public static AuditRecord fromJson(String line) {
        Map<String, Object> m = Json.parseObject(line);
        Object ev = m.get("event");
        Object ts = m.get("timestamp");
        if (!(ev instanceof String) || !(ts instanceof String)) {
            throw new IllegalArgumentException("audit line lacks event or timestamp");
        }
        Builder b = builder(LivenessAuditEvent.valueOf((String) ev)).at(Instant.parse((String) ts));
        Object sid = m.get("sessionId");
        if (sid instanceof String) {
            b.sessionId((String) sid);
        }
        Object wf = m.get("workflow");
        if (wf instanceof String) {
            b.workflow(Workflow.valueOf((String) wf));
        }
        Object attrs = m.get("attributes");
        if (attrs instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) attrs).entrySet()) {
                b.attribute(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        Object hash = m.get("evidenceHash");
        if (hash instanceof String) {
            b.evidenceHash((String) hash);
        }
        return b.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditRecord)) {
            return false;
        }
        AuditRecord that = (AuditRecord) o;
        return event == that.event && sessionId.equals(that.sessionId) && workflow == that.workflow
                && timestamp.equals(that.timestamp) && attributes.equals(that.attributes)
                && Objects.equals(evidenceHash, that.evidenceHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(event, sessionId, workflow, timestamp, attributes, evidenceHash);
    }

    @Override
    public String toString() {
        return event + "[" + sessionId + "]" + attributes;
    }

    /** Mutable builder; {@link #build()} applies redaction. */
    public static final class Builder {
        private final LivenessAuditEvent event;
        private String sessionId;
        private Workflow workflow;
        private Instant timestamp = Instant.now();
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private String evidenceHash;

        private Builder(LivenessAuditEvent event) {
            this.event = Objects.requireNonNull(event, "event");
        }

        public Builder sessionId(String v) { sessionId = v; return this; }
        public Builder workflow(Workflow v) { workflow = v; return this; }
        public Builder at(Instant v) { timestamp = v; return this; }
        /** Adds one attribute; values are stringified, numbers rendered with {@link String#valueOf}. */
        public Builder attribute(String key, Object value) { attributes.put(Objects.requireNonNull(key, "key"), value); return this; }
        public Builder attributes(Map<String, ?> values) { if (values != null) attributes.putAll(values); return this; }
        public Builder evidenceHash(String v) { evidenceHash = v; return this; }
        public AuditRecord build() { return new AuditRecord(this); }
    }
}
