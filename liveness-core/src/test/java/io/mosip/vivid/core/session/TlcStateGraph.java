package io.mosip.vivid.core.session;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the state graph TLC writes with {@code -dump dot,actionlabels <file>}: one node per distinct state, labelled
 * with the variable assignment, and one edge per (state, successor) pair, labelled with the action name.
 */
final class TlcStateGraph {

    private static final Pattern NODE = Pattern.compile("^(-?\\d+) \\[label=\"(.*)\"(?:,style = filled)?\\];?$");
    private static final Pattern EDGE = Pattern.compile("^(-?\\d+) -> (-?\\d+) \\[label=\"([^\"]*)\"");

    /** State keys ({@link SpecModel.State#key()}) of every reachable state. */
    final Set<String> states = new HashSet<>();
    /** "fromKey -> toKey" for every non-self-loop edge. */
    final Set<String> edges = new HashSet<>();
    /** "fromKey -Action-> toKey" for every labelled non-self-loop edge (TLC keeps one label per node pair). */
    final Set<String> labelledEdges = new HashSet<>();

    static TlcStateGraph read(Path dot) throws IOException {
        TlcStateGraph g = new TlcStateGraph();
        Map<String, String> keyById = new HashMap<>();
        Map<String, String[]> pendingEdges = new HashMap<>();
        int pending = 0;
        try (BufferedReader r = Files.newBufferedReader(dot, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher e = EDGE.matcher(line);
                if (e.find()) {
                    pendingEdges.put("e" + pending++, new String[] {e.group(1), e.group(2), e.group(3)});
                    continue;
                }
                Matcher n = NODE.matcher(line);
                if (n.matches()) {
                    String key = toKey(n.group(2));
                    keyById.put(n.group(1), key);
                    g.states.add(key);
                }
            }
        }
        for (String[] e : pendingEdges.values()) {
            String from = keyById.get(e[0]);
            String to = keyById.get(e[1]);
            if (from == null || to == null) {
                throw new IllegalStateException("edge to unknown node in " + dot + ": " + String.join(" ", e));
            }
            if (!from.equals(to)) {
                g.edges.add(from + " -> " + to);
                g.labelledEdges.add(from + " -" + e[2] + "-> " + to);
            }
        }
        return g;
    }

    /** Converts a TLC node label ({@code /\ phase = "IDLE"\n/\ attempt = 0 ...}, dot-escaped) into a state key. */
    static String toKey(String label) {
        Map<String, String> v = new HashMap<>();
        for (String part : label.split("\\\\n")) {
            String p = part.replace("\\\"", "\"").replace("\\\\", "\\").trim();
            if (p.startsWith("/\\")) {
                p = p.substring(2).trim();
            }
            int eq = p.indexOf(" = ");
            if (eq < 0) {
                continue;
            }
            String val = p.substring(eq + 3).trim();
            if (val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            v.put(p.substring(0, eq).trim(), val);
        }
        SpecModel.State s = new SpecModel.State(v.get("phase"), v.get("failure"), Integer.parseInt(v.get("attempt")), v.get("activeMode"),
                Integer.parseInt(v.get("fusedScore")), Boolean.parseBoolean(v.get("padVeto").toLowerCase()),
                Boolean.parseBoolean(v.get("identityDrift").toLowerCase()), Boolean.parseBoolean(v.get("passiveBelow").toLowerCase()),
                Integer.parseInt(v.get("frames")), Integer.parseInt(v.get("seqLen")), Integer.parseInt(v.get("challengeIdx")),
                Integer.parseInt(v.get("challengesPassed")), Integer.parseInt(v.get("ticks")));
        return s.key();
    }
}
