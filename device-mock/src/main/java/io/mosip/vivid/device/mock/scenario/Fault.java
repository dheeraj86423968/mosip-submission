package io.mosip.vivid.device.mock.scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One scheduled fault: a {@link FaultKind} fired at {@code atMs} into the enclosing segment, with free-form parameters. */
public final class Fault {
    private final long atMs;
    private final FaultKind kind;
    private final Map<String, Object> params;

    public Fault(long atMs, FaultKind kind, Map<String, Object> params) {
        if (atMs < 0) throw new IllegalArgumentException("atMs must be >= 0");
        this.atMs = atMs;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** Convenience for programmatic injection (time is irrelevant when injected immediately). */
    public static Fault of(FaultKind kind) { return new Fault(0, kind, Map.of()); }

    /** Convenience for programmatic injection with one parameter. */
    public static Fault of(FaultKind kind, String param, Object value) { return new Fault(0, kind, Map.of(param, value)); }

    /** Offset from the start of the segment, in milliseconds. */
    public long atMs() { return atMs; }
    public FaultKind kind() { return kind; }
    public Map<String, Object> params() { return params; }

    /** Numeric parameter as long, or the default if absent or not numeric. */
    public long longParam(String name, long dflt) {
        Object v = params.get(name);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) { try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return dflt; } }
        return dflt;
    }

    /** Numeric parameter as double, or the default if absent or not numeric. */
    public double doubleParam(String name, double dflt) {
        Object v = params.get(name);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) { try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return dflt; } }
        return dflt;
    }

    /** Boolean parameter, or the default if absent. */
    public boolean boolParam(String name, boolean dflt) {
        Object v = params.get(name);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return dflt;
    }

    @Override public String toString() { return kind + "@" + atMs + "ms" + (params.isEmpty() ? "" : params); }
}
