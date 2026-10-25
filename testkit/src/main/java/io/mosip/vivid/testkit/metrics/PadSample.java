package io.mosip.vivid.testkit.metrics;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One labelled presentation: ground truth (bona fide or an attack of a given PAI species) and the liveness
 * score the system produced for it. Scores are in [0,1] with 1 = certainly live; a presentation is accepted
 * when {@code score >= threshold}. Decision-only systems use {@link #accepted(boolean)} style scores (1 or 0).
 * <p>
 * A presentation may also be a <b>non-response</b> (no PAD decision, see {@link NonResponse}); it then has no
 * meaningful score and counts only towards APNRR/BPNRR. Optional {@linkplain #subject() subject} and
 * {@linkplain #groups() group tags} (for example {@code skinToneBand=4-7}) drive the per-group slices.
 */
public final class PadSample {
    /** Species label used for bona fide samples in per-species maps. */
    public static final String BONA_FIDE = "BONA_FIDE";

    private final String pais;      // null = bona fide
    private final double score;     // NaN only for non-responses
    private final String id;        // optional identifier for reports (subject/file), may be null
    private final boolean responded;
    private final String subject;   // nullable; explicit participant id
    private final Map<String, String> groups;

    private PadSample(String pais, double score, String id, boolean responded, String subject, Map<String, String> groups) {
        if (responded && Double.isNaN(score)) throw new IllegalArgumentException("score is NaN");
        this.pais = pais; this.score = responded ? Math.max(0, Math.min(1, score)) : Double.NaN; this.id = id;
        this.responded = responded; this.subject = subject == null || subject.isBlank() ? null : subject;
        this.groups = groups == null || groups.isEmpty() ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(groups));
    }

    public static PadSample bonaFide(double livenessScore) { return new PadSample(null, livenessScore, null, true, null, null); }
    public static PadSample bonaFide(double livenessScore, String id) { return new PadSample(null, livenessScore, id, true, null, null); }
    /** @param pais presentation attack instrument species, e.g. "PRINT", "SCREEN_REPLAY" */
    public static PadSample attack(String pais, double livenessScore) { return attack(pais, livenessScore, null); }
    public static PadSample attack(String pais, double livenessScore, String id) {
        if (pais == null || pais.isBlank()) throw new IllegalArgumentException("attack samples need a PAIS");
        return new PadSample(pais, livenessScore, id, true, null, null);
    }
    /**
     * A presentation that produced no PAD decision (failure to acquire, timeout, engine fault).
     * @param pais the attack species, or {@code null} for a bona fide presentation
     */
    public static PadSample nonResponse(String pais, String id) {
        if (pais != null && pais.isBlank()) throw new IllegalArgumentException("blank PAIS");
        return new PadSample(pais, Double.NaN, id, false, null, null);
    }
    /** A binary decision as a score: accepted = 1.0, rejected = 0.0 (evaluate with threshold 0.5). */
    public static double accepted(boolean accepted) { return accepted ? 1.0 : 0.0; }

    /** Copy with an explicit participant id (used to count subjects per group slice). */
    public PadSample withSubject(String subject) { return new PadSample(pais, score, id, responded, subject, groups); }
    /** Copy with group tags (key, e.g. {@code ageBand}, to value, e.g. {@code 30-49}). */
    public PadSample withGroups(Map<String, String> groups) { return new PadSample(pais, score, id, responded, subject, groups); }

    public boolean isBonaFide() { return pais == null; }
    /** The species, or {@link #BONA_FIDE}. */
    public String species() { return pais == null ? BONA_FIDE : pais; }
    /** The liveness score; NaN for a non-response. */
    public double score() { return score; }
    public String id() { return id; }
    /** False when the presentation produced no PAD decision. */
    public boolean responded() { return responded; }
    /** Group tags, sorted by key; empty when none. */
    public Map<String, String> groups() { return groups; }
    /**
     * The participant: the explicit subject if set, otherwise the part of {@link #id()} before the first '-'
     * (corpus item naming {@code P07-PRINT_A4_MATTE-L1-D1-T1}), otherwise null.
     */
    public String subject() {
        if (subject != null) return subject;
        if (id == null) return null;
        int dash = id.indexOf('-');
        return dash > 0 ? id.substring(0, dash) : null;
    }
    /** Accepted as bona fide at the threshold; always false for a non-response. */
    public boolean acceptedAt(double threshold) { return responded && score >= threshold; }

    @Override public boolean equals(Object o) {
        return o instanceof PadSample p && Objects.equals(pais, p.pais) && Double.compare(score, p.score) == 0 && Objects.equals(id, p.id)
                && responded == p.responded && Objects.equals(subject, p.subject) && groups.equals(p.groups);
    }
    @Override public int hashCode() { return Objects.hash(pais, score, id, responded, subject, groups); }
    @Override public String toString() { return species() + "=" + (responded ? String.valueOf(score) : "no-response"); }
}
