package io.mosip.vivid.telemetry.calibration;

import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.telemetry.json.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client-side accumulator of per-workflow fused-score histograms for privacy-preserving calibration
 * (PLAN.md 17.10, design 14-dp-calibration.md).
 *
 * <p>Each completed session contributes at most one sample, its first attempt's fused liveness score, to the
 * {@link CalibrationLabel#BONA_FIDE} or {@link CalibrationLabel#REJECTED} histogram of its workflow. Contributions are
 * bounded twice:</p>
 * <ul>
 *   <li>per subject: at most {@link Config#maxPerSubject(Workflow)} samples per release period from one subject key
 *       (the session id for residents; a pseudonymous operator id for operator and supervisor authentication, where
 *       the same person authenticates many times a day). This cap is the L1 sensitivity the DP noise is scaled to;</li>
 *   <li>per client: at most {@link Config#maxSamplesPerRelease()} samples per workflow and release, which limits how
 *       much one registration machine can move the central estimate.</li>
 * </ul>
 *
 * <p>Subject keys are kept only as salted SHA-256 digests with a salt drawn per release period and discarded on
 * {@link #reset()}; frames, embeddings and identities are never seen here. Thread-safe.</p>
 */
public final class CalibrationAccumulator {

    /** Contribution bounds and binning. */
    public static final class Config {
        private final ScoreBinning binning;
        private final Map<Workflow, Integer> maxPerSubject;
        private final int maxSamplesPerRelease;

        public Config(ScoreBinning binning, Map<Workflow, Integer> maxPerSubject, int maxSamplesPerRelease) {
            this.binning = Objects.requireNonNull(binning, "binning");
            this.maxPerSubject = new EnumMap<>(Workflow.class);
            for (Workflow w : Workflow.values()) {
                int m = maxPerSubject.getOrDefault(w, 1);
                if (m < 1) {
                    throw new IllegalArgumentException("maxPerSubject must be at least 1");
                }
                this.maxPerSubject.put(w, m);
            }
            if (maxSamplesPerRelease < 1) {
                throw new IllegalArgumentException("maxSamplesPerRelease must be at least 1");
            }
            this.maxSamplesPerRelease = maxSamplesPerRelease;
        }

        /** Residents once per session; operators and supervisors at most three times per release period; 2000 samples. */
        public static Config defaults() {
            Map<Workflow, Integer> m = new EnumMap<>(Workflow.class);
            m.put(Workflow.RESIDENT, 1);
            m.put(Workflow.OPERATOR, 3);
            m.put(Workflow.SUPERVISOR, 3);
            return new Config(ScoreBinning.DEFAULT, m, 2000);
        }

        public ScoreBinning binning() { return binning; }
        public int maxPerSubject(Workflow w) { return maxPerSubject.get(w); }
        public int maxSamplesPerRelease() { return maxSamplesPerRelease; }
    }

    private final Config config;
    private final Map<Workflow, long[][]> histograms = new EnumMap<>(Workflow.class);
    private final Map<Workflow, Integer> totals = new EnumMap<>(Workflow.class);
    private final Map<String, Integer> perSubject = new HashMap<>();
    private byte[] salt;
    private long droppedSubjectCap;
    private long droppedClientCap;

    public CalibrationAccumulator(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        reset();
    }

    public Config config() {
        return config;
    }

    /**
     * Records one completed session.
     *
     * @param workflow   workflow of the session
     * @param subjectKey session id (residents) or pseudonymous operator id; never a name or biometric
     * @param firstAttemptScore fused liveness score of the session's first attempt, in [0, 1]
     * @param label      whether the session was later confirmed live
     * @return true if the sample was counted, false if a contribution bound dropped it
     */
    public synchronized boolean record(Workflow workflow, String subjectKey, double firstAttemptScore, CalibrationLabel label) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(subjectKey, "subjectKey");
        Objects.requireNonNull(label, "label");
        if (Double.isNaN(firstAttemptScore) || firstAttemptScore < 0 || firstAttemptScore > 1) {
            throw new IllegalArgumentException("score must be in [0,1]");
        }
        String key = digest(workflow.name() + "\u0000" + subjectKey);
        int used = perSubject.getOrDefault(key, 0);
        if (used >= config.maxPerSubject(workflow)) {
            droppedSubjectCap++;
            return false;
        }
        int total = totals.get(workflow);
        if (total >= config.maxSamplesPerRelease()) {
            droppedClientCap++;
            return false;
        }
        perSubject.put(key, used + 1);
        totals.put(workflow, total + 1);
        histograms.get(workflow)[label.ordinal()][config.binning().bin(firstAttemptScore)]++;
        return true;
    }

    /** Copy of the raw (not yet privatised) counts. Never leaves the client. */
    public synchronized long[] counts(Workflow workflow, CalibrationLabel label) {
        return histograms.get(workflow)[label.ordinal()].clone();
    }

    /** Samples counted for a workflow in the current period. */
    public synchronized int samples(Workflow workflow) {
        return totals.get(workflow);
    }

    /** Samples dropped by the per-subject and per-client bounds since construction. */
    public synchronized long[] dropped() {
        return new long[] {droppedSubjectCap, droppedClientCap};
    }

    /** L1 sensitivity of one release of {@code workflow}: the most one subject can change the counts. */
    public int sensitivity(Workflow workflow) {
        return config.maxPerSubject(workflow);
    }

    /** Starts a new release period: clears counts, subject digests and the salt. */
    public synchronized void reset() {
        for (Workflow w : Workflow.values()) {
            histograms.put(w, new long[CalibrationLabel.values().length][config.binning().bins()]);
            totals.put(w, 0);
        }
        perSubject.clear();
        salt = new byte[16];
        new SecureRandom().nextBytes(salt);
    }

    /** Serialises the period's state so it survives a client restart (stored next to the offline audit queue). */
    public synchronized String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("binning", config.binning().id());
        m.put("salt", Base64.getEncoder().encodeToString(salt));
        Map<String, Object> h = new LinkedHashMap<>();
        for (Workflow w : Workflow.values()) {
            Map<String, Object> labels = new LinkedHashMap<>();
            for (CalibrationLabel l : CalibrationLabel.values()) {
                labels.put(l.name(), toList(histograms.get(w)[l.ordinal()]));
            }
            h.put(w.name(), labels);
        }
        m.put("histograms", h);
        m.put("subjects", new LinkedHashMap<>(perSubject));
        return Json.write(m);
    }

    /** Restores state written by {@link #toJson()}; a binning mismatch starts a fresh period. */
    @SuppressWarnings("unchecked")
    public static CalibrationAccumulator fromJson(String json, Config config) {
        CalibrationAccumulator a = new CalibrationAccumulator(config);
        Map<String, Object> m = Json.parseObject(json);
        if (!config.binning().id().equals(m.get("binning"))) {
            return a;
        }
        a.salt = Base64.getDecoder().decode((String) m.get("salt"));
        Map<String, Object> h = (Map<String, Object>) m.get("histograms");
        for (Workflow w : Workflow.values()) {
            Map<String, Object> labels = (Map<String, Object>) h.get(w.name());
            int total = 0;
            for (CalibrationLabel l : CalibrationLabel.values()) {
                List<Object> list = (List<Object>) labels.get(l.name());
                for (int i = 0; i < list.size(); i++) {
                    long v = ((Number) list.get(i)).longValue();
                    a.histograms.get(w)[l.ordinal()][i] = v;
                    total += (int) v;
                }
            }
            a.totals.put(w, total);
        }
        Map<String, Object> subjects = (Map<String, Object>) m.get("subjects");
        for (Map.Entry<String, Object> e : subjects.entrySet()) {
            a.perSubject.put(e.getKey(), ((Number) e.getValue()).intValue());
        }
        return a;
    }

    private String digest(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            return Base64.getEncoder().encodeToString(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Long> toList(long[] a) {
        List<Long> out = new ArrayList<>(a.length);
        for (long v : a) {
            out.add(v);
        }
        return out;
    }
}
