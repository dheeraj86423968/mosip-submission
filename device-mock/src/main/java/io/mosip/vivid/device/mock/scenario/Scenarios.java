package io.mosip.vivid.device.mock.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.vivid.device.mock.render.ExpressionEvent;
import io.mosip.vivid.device.mock.render.ExpressionTimeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@link Scenario}s from JSON. The schema (all keys optional unless noted):
 * <pre>{@code
 * {
 *   "name": "live-blink-smile-turn",            // required
 *   "description": "...",
 *   "width": 640, "height": 480,
 *   "loop": false,
 *   "sensors": {"depth": true, "ir": true, "surface": "AUTO"},   // optional, see SensorConfig
 *   "segments": [{                               // required, at least one
 *     "source": "synthetic-face" | "blank" | "noise" | "<directory of JPEG/PNG frames>",
 *     "durationMs": 6000,                        // required
 *     "fps": 15,
 *     "label": "LIVE" | "PRINT" | "SCREEN_REPLAY" | "VIDEO_REPLAY" | "MASK" | "NONE",
 *     "faceCount": 1,
 *     "overlay": "NONE" | "PRINT_TEXTURE" | "MOIRE_GRID",
 *     "seed": 42,
 *     "timelineLoops": false,
 *     "timeline": [
 *       {"action": "BLINK", "atMs": 1200},
 *       {"action": "YAW", "fromMs": 2000, "toMs": 3000, "degrees": 25},
 *       {"action": "SMILE", "fromMs": 3500, "toMs": 4500, "intensity": 1.0}
 *     ],
 *     "faults": [{"atMs": 1500, "kind": "STALL", "params": {"durationMs": 2000}}]
 *   }]
 * }
 * }</pre>
 * Bundled scenarios live under {@code /scenarios/<name>.json} on the classpath; see {@link #bundledNames()}.
 */
public final class Scenarios {
    /** Names of the scenarios shipped in this module, in a sensible demo order. */
    private static final List<String> BUNDLED = List.of(
            "live-pass", "live-blink-smile-turn", "print-attack", "screen-replay", "video-replay",
            "disconnect-mid-session", "stall", "multiple-faces", "no-face");

    /**
     * L1-style scenarios with depth and IR planes (S8). Loadable with {@link #bundled} and {@link #resolve} but kept
     * out of {@link #bundledNames()} so suites keyed on that list (and their expectations) are unchanged.
     */
    private static final List<String> SENSOR_SCENARIOS = List.of(
            "l1-live-depth-ir", "l1-print-depth-ir", "l1-curved-print-depth-ir", "l1-screen-depth-ir");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Scenarios() {}

    /** Names of bundled scenarios. */
    public static List<String> bundledNames() { return BUNDLED; }

    /** Names of the bundled L1 depth / IR scenarios. */
    public static List<String> sensorScenarioNames() { return SENSOR_SCENARIOS; }

    /** Load a bundled scenario by name (without the {@code .json} suffix). */
    public static Scenario bundled(String name) {
        String resource = "/scenarios/" + name + ".json";
        try (InputStream in = Scenarios.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalArgumentException("no bundled scenario '" + name + "' (known: " + BUNDLED + ")");
            return parse(MAPPER.readTree(in), null);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
    }

    /** Load a scenario from a file. Relative frame-directory sources resolve against the file's directory. */
    public static Scenario load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Path base = file.toAbsolutePath().getParent();
            return parse(MAPPER.readTree(in), base);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read scenario " + file, e);
        }
    }

    /** Load a scenario from a JSON string (no base directory). */
    public static Scenario fromJson(String json) {
        try {
            return parse(MAPPER.readTree(json), null);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid scenario JSON", e);
        }
    }

    /**
     * Resolve a CLI-style reference: a bundled name, or a path to a JSON file.
     */
    public static Scenario resolve(String ref) {
        if (BUNDLED.contains(ref) || SENSOR_SCENARIOS.contains(ref)) return bundled(ref);
        Path p = Path.of(ref);
        if (Files.isRegularFile(p)) return load(p);
        throw new IllegalArgumentException("'" + ref + "' is neither a bundled scenario " + BUNDLED + " nor a readable file");
    }

    /** Parse an already-read JSON tree. */
    public static Scenario parse(JsonNode root, Path baseDir) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("scenario must be a JSON object");
        Scenario.Builder b = Scenario.builder(required(root, "name").asText());
        b.description(root.path("description").asText(""));
        b.size(root.path("width").asInt(640), root.path("height").asInt(480));
        b.loop(root.path("loop").asBoolean(false));
        b.baseDir(baseDir);
        if (root.hasNonNull("sensors")) b.sensors(SensorConfig.parse(root.get("sensors")));
        JsonNode segs = required(root, "segments");
        if (!segs.isArray() || segs.isEmpty()) throw new IllegalArgumentException("'segments' must be a non-empty array");
        int i = 0;
        for (JsonNode s : segs) {
            try { b.segment(parseSegment(s)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("segments[" + i + "]: " + e.getMessage(), e); }
            i++;
        }
        return b.build();
    }

    private static Segment parseSegment(JsonNode s) {
        Segment.Builder b = Segment.builder();
        b.source(s.path("source").asText(Segment.SOURCE_SYNTHETIC_FACE));
        b.durationMs(required(s, "durationMs").asLong());
        b.fps(s.path("fps").asDouble(15));
        b.label(enumValue(PresentationLabel.class, s.path("label"), PresentationLabel.LIVE));
        b.faceCount(s.path("faceCount").asInt(1));
        b.overlay(enumValue(OverlayKind.class, s.path("overlay"), OverlayKind.NONE));
        b.seed(s.path("seed").asLong(42));
        b.timelineLoops(s.path("timelineLoops").asBoolean(false));
        b.timeline(parseTimeline(s.path("timeline")));
        for (JsonNode f : s.path("faults")) b.fault(parseFault(f));
        return b.build();
    }

    /** Parse a timeline array; exposed so expectation files and tests can reuse the vocabulary. */
    public static ExpressionTimeline parseTimeline(JsonNode arr) {
        if (arr == null || arr.isMissingNode() || arr.isNull()) return ExpressionTimeline.empty();
        List<ExpressionEvent> events = new ArrayList<>();
        for (JsonNode e : arr) {
            ExpressionEvent.Action action = enumValue(ExpressionEvent.Action.class, e.path("action"), null);
            if (action == null) throw new IllegalArgumentException("timeline event needs an 'action'");
            if (action == ExpressionEvent.Action.BLINK && e.has("atMs")) {
                events.add(ExpressionEvent.blinkAt(e.get("atMs").asLong()));
                continue;
            }
            long from = e.has("fromMs") ? e.get("fromMs").asLong() : e.path("atMs").asLong(0);
            long to = e.has("toMs") ? e.get("toMs").asLong() : from + e.path("durationMs").asLong(ExpressionEvent.DEFAULT_BLINK_MS);
            double magnitude = e.has("degrees") ? e.get("degrees").asDouble() : e.path("intensity").asDouble(1.0);
            events.add(ExpressionEvent.between(action, from, to, magnitude));
        }
        return ExpressionTimeline.of(events);
    }

    private static Fault parseFault(JsonNode f) {
        FaultKind kind = enumValue(FaultKind.class, f.path("kind"), null);
        if (kind == null) throw new IllegalArgumentException("fault needs a 'kind' in " + List.of(FaultKind.values()));
        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode p = f.path("params");
        if (p.isObject()) p.fields().forEachRemaining(en -> params.put(en.getKey(), scalar(en.getValue())));
        return new Fault(f.path("atMs").asLong(0), kind, params);
    }

    private static Object scalar(JsonNode n) {
        if (n.isBoolean()) return n.asBoolean();
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isNumber()) return n.asDouble();
        return n.asText();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode n, E dflt) {
        if (n == null || n.isMissingNode() || n.isNull()) return dflt;
        String text = n.asText().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try { return Enum.valueOf(type, text); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("'" + n.asText() + "' is not one of " + List.of(type.getEnumConstants())); }
    }

    private static JsonNode required(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) throw new IllegalArgumentException("missing required field '" + field + "'");
        return v;
    }
}
