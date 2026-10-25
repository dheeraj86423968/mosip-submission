package io.mosip.vivid.testkit.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-group slices (demographic fairness and capture-condition reporting, {@code docs/corpus-ethics.md} §11.4).
 * For every group key found in the samples' {@linkplain PadSample#groups() tags} (for example {@code skinToneBand},
 * {@code ageBand}, {@code eyewear}, {@code lighting}, {@code device}) and every value of that key, the slice holds
 * BPCER and BPNRR over its bona fide presentations and APCER per species over its attack presentations, each with
 * a Wilson 95 % interval, plus the number of distinct participants.
 * <p>
 * A slice with fewer than {@link #MIN_SUBJECTS_FLOOR} participants (or the configured higher minimum) is
 * <b>suppressed</b>: it keeps only its key, value and the suppressed flag; no counts or rates are exposed, so
 * small groups cannot be re-identified. Participants are counted with {@link PadSample#subject()}; samples whose
 * participant is unknown do not count towards the minimum. For each key, every unsuppressed slice also carries
 * its BPCER relative to the lowest unsuppressed slice BPCER of that key (the "differential performance" ratio)
 * and a flag when it is more than twice the lowest.
 */
public final class GroupSlices {
    /** Ethics floor: no slice with fewer participants is ever reported. */
    public static final int MIN_SUBJECTS_FLOOR = 5;

    /** One group value's figures. Rates are null when the slice is suppressed. */
    public record Slice(String key, String value, boolean suppressed, int subjects,
                        Rate bpcer, Rate bpnrr, Map<String, Rate> apcerBySpecies, Map<String, Rate> apnrrBySpecies,
                        Double bpcerRatioToLowest, boolean moreThanTwiceLowest) {
        /** Highest per-species APCER in this slice, or NaN when it has no responded attacks (or is suppressed). */
        public double maxApcer() {
            return apcerBySpecies == null ? Double.NaN : apcerBySpecies.values().stream().mapToDouble(Rate::value).filter(v -> !Double.isNaN(v)).max().orElse(Double.NaN);
        }
    }

    private final int minSubjects;
    private final double threshold;
    private final Map<String, List<Slice>> byKey;

    private GroupSlices(int minSubjects, double threshold, Map<String, List<Slice>> byKey) {
        this.minSubjects = minSubjects; this.threshold = threshold; this.byKey = Collections.unmodifiableMap(byKey);
    }

    /**
     * @param minSubjects minimum distinct participants per reported slice; values below {@link #MIN_SUBJECTS_FLOOR}
     *                    are rejected
     */
    public static GroupSlices compute(List<PadSample> samples, double threshold, int minSubjects) {
        Objects.requireNonNull(samples);
        if (minSubjects < MIN_SUBJECTS_FLOOR) throw new IllegalArgumentException("minSubjects must be at least " + MIN_SUBJECTS_FLOOR + " (corpus-ethics.md §11.4)");
        // key -> value -> samples
        Map<String, Map<String, List<PadSample>>> index = new TreeMap<>();
        for (PadSample s : samples)
            s.groups().forEach((k, v) -> index.computeIfAbsent(k, x -> new TreeMap<>()).computeIfAbsent(v, x -> new ArrayList<>()).add(s));

        Map<String, List<Slice>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<PadSample>>> key : index.entrySet()) {
            List<Slice> raw = new ArrayList<>();
            for (Map.Entry<String, List<PadSample>> val : key.getValue().entrySet()) raw.add(slice(key.getKey(), val.getKey(), val.getValue(), threshold, minSubjects));
            double lowest = raw.stream().filter(s -> !s.suppressed() && s.bpcer().n() > 0).mapToDouble(s -> s.bpcer().value()).min().orElse(Double.NaN);
            List<Slice> finished = new ArrayList<>();
            for (Slice s : raw) {
                if (s.suppressed() || s.bpcer().n() == 0 || Double.isNaN(lowest)) { finished.add(s); continue; }
                double b = s.bpcer().value();
                Double ratio;                                   // null: lowest is 0 and this one is not (unbounded)
                if (lowest > 0) ratio = b / lowest; else ratio = b == 0 ? Double.valueOf(1.0) : null;
                finished.add(new Slice(s.key(), s.value(), false, s.subjects(), s.bpcer(), s.bpnrr(), s.apcerBySpecies(), s.apnrrBySpecies(),
                        ratio, b > 2 * lowest));
            }
            out.put(key.getKey(), Collections.unmodifiableList(finished));
        }
        return new GroupSlices(minSubjects, threshold, out);
    }

    private static Slice slice(String key, String value, List<PadSample> samples, double threshold, int minSubjects) {
        Set<String> subjects = new HashSet<>();
        for (PadSample s : samples) if (s.subject() != null) subjects.add(s.subject());
        if (subjects.size() < minSubjects) return new Slice(key, value, true, 0, null, null, null, null, null, false);
        int bf = 0, bfErr = 0, bfNr = 0;
        Map<String, int[]> att = new TreeMap<>();   // species -> {presented, responded, accepted}
        for (PadSample s : samples) {
            if (s.isBonaFide()) {
                if (!s.responded()) bfNr++;
                else { bf++; if (!s.acceptedAt(threshold)) bfErr++; }
            } else {
                int[] c = att.computeIfAbsent(s.species(), x -> new int[3]);
                c[0]++;
                if (s.responded()) { c[1]++; if (s.acceptedAt(threshold)) c[2]++; }
            }
        }
        Map<String, Rate> apcer = new LinkedHashMap<>(), apnrr = new LinkedHashMap<>();
        att.forEach((sp, c) -> { if (c[1] > 0) apcer.put(sp, new Rate(c[2], c[1])); apnrr.put(sp, new Rate(c[0] - c[1], c[0])); });
        return new Slice(key, value, false, subjects.size(), new Rate(bfErr, bf), new Rate(bfNr, bf + bfNr),
                Collections.unmodifiableMap(apcer), Collections.unmodifiableMap(apnrr), null, false);
    }

    public int minSubjects() { return minSubjects; }
    public double threshold() { return threshold; }
    /** Group key to its slices, keys and values sorted. Empty when no sample carries group tags. */
    public Map<String, List<Slice>> byKey() { return byKey; }
    public boolean isEmpty() { return byKey.isEmpty(); }
}
