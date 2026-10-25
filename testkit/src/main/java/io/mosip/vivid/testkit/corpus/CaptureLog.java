package io.mosip.vivid.testkit.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reader for the corpus capture log ({@code docs/corpus/capture-log-template.csv}): lines starting with {@code #}
 * are comments, the first other line is the header, every later line is one recorded item keyed by {@code item_id}.
 * Plain CSV with optional double-quoted fields ({@code ""} escapes a quote).
 */
public final class CaptureLog {
    /** CSV demographic column to the group tag written to scores.jsonl. Only used when {@code consent_demographics=Y}. */
    public static final Map<String, String> DEMOGRAPHIC_COLUMNS = Map.of(
            "mst_band", "skinToneBand", "age_band", "ageBand", "gender_self", "gender",
            "eyewear", "eyewear", "head_covering", "headCovering", "facial_hair", "facialHair");
    /** Capture-condition columns, always written as group tags. */
    public static final Map<String, String> CONDITION_COLUMNS = Map.of("lighting", "lighting", "device", "device");

    private final Map<String, Map<String, String>> rows;

    private CaptureLog(Map<String, Map<String, String>> rows) { this.rows = Collections.unmodifiableMap(rows); }

    public static CaptureLog empty() { return new CaptureLog(Map.of()); }

    public static CaptureLog read(Path csv) throws IOException {
        List<String> header = null;
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        int n = 0;
        for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
            n++;
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            List<String> cells = split(line);
            if (header == null) {
                header = new ArrayList<>();
                for (String c : cells) header.add(c.trim().toLowerCase(Locale.ROOT));
                if (!header.contains("item_id")) throw new IllegalArgumentException(csv + ":" + n + ": header has no item_id column");
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) row.put(header.get(i), i < cells.size() ? cells.get(i).trim() : "");
            String id = row.get("item_id");
            if (id == null || id.isEmpty()) continue;
            if (rows.put(id, row) != null) throw new IllegalArgumentException(csv + ":" + n + ": duplicate item_id " + id);
        }
        return new CaptureLog(rows);
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cur.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** The row for an item, or null. Column names are lower case. */
    public Map<String, String> row(String itemId) { return rows.get(itemId); }

    public int size() { return rows.size(); }

    /**
     * Group tags for an item: lighting and device always; demographics only when the participant ticked consent
     * box 5 ({@code consent_demographics=Y}). Blank cells are left out.
     */
    public static Map<String, String> groups(Map<String, String> row) {
        Map<String, String> g = new TreeMap<>();
        if (row == null) return g;
        CONDITION_COLUMNS.forEach((col, tag) -> put(g, tag, row.get(col)));
        if ("Y".equalsIgnoreCase(row.getOrDefault("consent_demographics", "")))
            DEMOGRAPHIC_COLUMNS.forEach((col, tag) -> put(g, tag, row.get(col)));
        return g;
    }

    private static void put(Map<String, String> g, String tag, String v) { if (v != null && !v.isBlank()) g.put(tag, v.trim()); }
}
