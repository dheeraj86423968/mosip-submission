package io.mosip.vivid.testkit.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ISO/IEC 30107-3 presentation attack detection metrics computed from labelled samples.
 * <ul>
 *   <li><b>APCER</b> (attack presentation classification error rate), per PAI species: fraction of attacks of
 *       that species accepted as bona fide. The reported overall APCER is the <em>maximum</em> over species,
 *       as 30107-3 requires when summarising across PAI.</li>
 *   <li><b>BPCER</b> (bona fide presentation classification error rate): fraction of bona fide presentations
 *       rejected.</li>
 *   <li><b>ACER</b> = (max APCER + BPCER) / 2, reported for comparability with older PAD literature.</li>
 *   <li><b>BPCER@APCER≤5%</b> and <b>≤10%</b>: the lowest BPCER achievable at a threshold whose max APCER
 *       does not exceed the target, found by sweeping the threshold (NaN if no threshold reaches the target),
 *       with the threshold that achieves it (the highest such threshold when several tie).</li>
 *   <li><b>APNRR / BPNRR</b> (attack / bona fide presentation non-response rate): fraction of presentations that
 *       produced no PAD decision ({@link NonResponse}). Non-responses are excluded from APCER/BPCER and reported
 *       separately so they cannot hide inside, or flatter, the error rates.</li>
 *   <li><b>Wilson 95 % confidence intervals</b> for every per-species APCER, BPCER, APNRR and BPNRR ({@link Rate}).</li>
 *   <li><b>EER</b>: the point where max APCER and BPCER are closest.</li>
 *   <li>A <b>DET curve</b>: (threshold, max APCER, BPCER) for every distinct score.</li>
 * </ul>
 * A sample is accepted when {@code score >= threshold}. Immutable; build with {@link #compute}.
 */
public final class Iso30107Metrics {
    private final double threshold;
    private final int bonaFideCount;
    private final int attackCount;
    private final Map<String, Integer> attackCountBySpecies;
    private final Map<String, Double> apcerBySpecies;
    private final Map<String, Rate> apcerRateBySpecies;
    private final double maxApcer;
    private final String worstSpecies;
    private final Rate bpcerRate;
    private final double acer;
    private final double bpcerAtApcer5, bpcerAtApcer5Threshold;
    private final double bpcerAtApcer10, bpcerAtApcer10Threshold;
    private final Map<String, Rate> apnrrBySpecies;
    private final Rate apnrr;
    private final Rate bpnrr;
    private final double eer;
    private final double eerThreshold;
    private final List<DetPoint> detCurve;

    private Iso30107Metrics(double threshold, int bonaFideCount, int attackCount, Map<String, Integer> attackCountBySpecies,
                            Map<String, Rate> apcerRates, String worstSpecies, Rate bpcerRate,
                            DetPoint at5, DetPoint at10, Map<String, Rate> apnrrBySpecies, Rate apnrr, Rate bpnrr,
                            double eer, double eerThreshold, List<DetPoint> detCurve) {
        this.threshold = threshold; this.bonaFideCount = bonaFideCount; this.attackCount = attackCount;
        this.attackCountBySpecies = Collections.unmodifiableMap(attackCountBySpecies);
        this.apcerRateBySpecies = Collections.unmodifiableMap(apcerRates);
        Map<String, Double> values = new LinkedHashMap<>();
        apcerRates.forEach((k, v) -> values.put(k, v.value()));
        this.apcerBySpecies = Collections.unmodifiableMap(values);
        this.worstSpecies = worstSpecies; this.maxApcer = apcerRates.get(worstSpecies).value();
        this.bpcerRate = bpcerRate;
        this.acer = (maxApcer + bpcerRate.value()) / 2.0;
        this.bpcerAtApcer5 = at5 == null ? Double.NaN : at5.bpcer(); this.bpcerAtApcer5Threshold = at5 == null ? Double.NaN : at5.threshold();
        this.bpcerAtApcer10 = at10 == null ? Double.NaN : at10.bpcer(); this.bpcerAtApcer10Threshold = at10 == null ? Double.NaN : at10.threshold();
        this.apnrrBySpecies = Collections.unmodifiableMap(apnrrBySpecies); this.apnrr = apnrr; this.bpnrr = bpnrr;
        this.eer = eer; this.eerThreshold = eerThreshold;
        this.detCurve = Collections.unmodifiableList(detCurve);
    }

    /**
     * Compute all metrics for the given operating threshold. Requires at least one responded bona fide and one
     * responded attack sample; non-responses may be present in any number.
     */
    public static Iso30107Metrics compute(List<PadSample> samples, double threshold) {
        Objects.requireNonNull(samples);
        if (threshold < 0 || threshold > 1) throw new IllegalArgumentException("threshold must be in [0,1]");
        List<PadSample> responded = new ArrayList<>();
        int bona = 0, bonaNoResp = 0;
        Map<String, Integer> perSpecies = new TreeMap<>();
        Map<String, Integer> presentedPerSpecies = new TreeMap<>(), noRespPerSpecies = new TreeMap<>();
        for (PadSample s : samples) {
            if (s.isBonaFide()) {
                if (s.responded()) bona++; else bonaNoResp++;
            } else {
                presentedPerSpecies.merge(s.species(), 1, Integer::sum);
                if (s.responded()) perSpecies.merge(s.species(), 1, Integer::sum);
                else noRespPerSpecies.merge(s.species(), 1, Integer::sum);
            }
            if (s.responded()) responded.add(s);
        }
        if (bona == 0) throw new IllegalArgumentException("no bona fide samples with a PAD decision");
        if (perSpecies.isEmpty()) throw new IllegalArgumentException("no attack samples with a PAD decision");
        int attacks = perSpecies.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, Rate> apcer = apcerRates(responded, perSpecies, threshold);
        String worst = null; double max = -1;
        for (Map.Entry<String, Rate> e : apcer.entrySet()) if (e.getValue().value() > max) { worst = e.getKey(); max = e.getValue().value(); }
        Rate bpcer = new Rate(rejectedBonaFide(responded, threshold), bona);

        Map<String, Rate> apnrrBySpecies = new LinkedHashMap<>();
        int attackNoResp = 0, attackPresented = 0;
        for (Map.Entry<String, Integer> e : presentedPerSpecies.entrySet()) {
            int nr = noRespPerSpecies.getOrDefault(e.getKey(), 0);
            apnrrBySpecies.put(e.getKey(), new Rate(nr, e.getValue()));
            attackNoResp += nr; attackPresented += e.getValue();
        }

        // DET sweep over every distinct score, plus 0 (accept everything)
        TreeSet<Double> thresholds = new TreeSet<>();
        thresholds.add(0.0);
        for (PadSample s : responded) thresholds.add(s.score());
        thresholds.add(Math.nextUp(thresholds.last()));   // reject everything
        List<DetPoint> det = new ArrayList<>();
        for (double t : thresholds) {
            double a = apcerRates(responded, perSpecies, t).values().stream().mapToDouble(Rate::value).max().orElse(0);
            det.add(new DetPoint(t, a, rejectedBonaFide(responded, t) / (double) bona));
        }
        DetPoint eerPoint = det.get(0);
        for (DetPoint p : det) if (Math.abs(p.apcer() - p.bpcer()) < Math.abs(eerPoint.apcer() - eerPoint.bpcer())) eerPoint = p;
        double eer = (eerPoint.apcer() + eerPoint.bpcer()) / 2.0;

        return new Iso30107Metrics(threshold, bona, attacks, perSpecies, apcer, worst, bpcer,
                bpcerAt(det, 0.05), bpcerAt(det, 0.10), apnrrBySpecies, new Rate(attackNoResp, attackPresented),
                new Rate(bonaNoResp, bona + bonaNoResp), eer, eerPoint.threshold(), det);
    }

    private static Map<String, Rate> apcerRates(List<PadSample> responded, Map<String, Integer> counts, double t) {
        Map<String, Integer> accepted = new TreeMap<>();
        for (PadSample s : responded) if (!s.isBonaFide() && s.acceptedAt(t)) accepted.merge(s.species(), 1, Integer::sum);
        Map<String, Rate> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) out.put(e.getKey(), new Rate(accepted.getOrDefault(e.getKey(), 0), e.getValue()));
        return out;
    }

    private static int rejectedBonaFide(List<PadSample> responded, double t) {
        int rejected = 0;
        for (PadSample s : responded) if (s.isBonaFide() && !s.acceptedAt(t)) rejected++;
        return rejected;
    }

    /** Lowest-BPCER sweep point with max APCER ≤ target; among equal BPCER, the highest threshold (lowest APCER). */
    private static DetPoint bpcerAt(List<DetPoint> det, double maxApcer) {
        DetPoint best = null;
        for (DetPoint p : det) {
            if (p.apcer() > maxApcer + 1e-12) continue;
            if (best == null || p.bpcer() < best.bpcer() || (p.bpcer() == best.bpcer() && p.threshold() > best.threshold())) best = p;
        }
        return best;
    }

    public double threshold() { return threshold; }
    /** Bona fide presentations with a PAD decision (the BPCER denominator). */
    public int bonaFideCount() { return bonaFideCount; }
    /** Attack presentations with a PAD decision. */
    public int attackCount() { return attackCount; }
    /** Responded attack presentations per species (the APCER denominators). */
    public Map<String, Integer> attackCountBySpecies() { return attackCountBySpecies; }
    /** APCER per PAI species at {@link #threshold()}. */
    public Map<String, Double> apcerBySpecies() { return apcerBySpecies; }
    /** APCER per PAI species as error count, denominator and Wilson interval. */
    public Map<String, Rate> apcerRateBySpecies() { return apcerRateBySpecies; }
    /** Highest per-species APCER: the 30107-3 summary APCER. */
    public double maxApcer() { return maxApcer; }
    public String worstSpecies() { return worstSpecies; }
    /** Wilson 95 % interval of the worst species' APCER. */
    public Rate.Interval maxApcerCi95() { return apcerRateBySpecies.get(worstSpecies).wilson95(); }
    public double bpcer() { return bpcerRate.value(); }
    /** BPCER as rejected count, denominator and Wilson interval. */
    public Rate bpcerRate() { return bpcerRate; }
    public double acer() { return acer; }
    /** Lowest BPCER at any threshold with max APCER ≤ 5%; NaN if unattainable. */
    public double bpcerAtApcer5() { return bpcerAtApcer5; }
    /** Threshold at which {@link #bpcerAtApcer5()} is reached; NaN if unattainable. */
    public double bpcerAtApcer5Threshold() { return bpcerAtApcer5Threshold; }
    /** Lowest BPCER at any threshold with max APCER ≤ 10%; NaN if unattainable. */
    public double bpcerAtApcer10() { return bpcerAtApcer10; }
    /** Threshold at which {@link #bpcerAtApcer10()} is reached; NaN if unattainable. */
    public double bpcerAtApcer10Threshold() { return bpcerAtApcer10Threshold; }
    /** APNRR over all attack presentations (non-responses / attack presentations attempted). */
    public Rate apnrr() { return apnrr; }
    /** APNRR per species, over every attempted presentation of that species (including species with no decision at all). */
    public Map<String, Rate> apnrrBySpecies() { return apnrrBySpecies; }
    /** BPNRR (non-responses / bona fide presentations attempted). */
    public Rate bpnrr() { return bpnrr; }
    public double eer() { return eer; }
    public double eerThreshold() { return eerThreshold; }
    public List<DetPoint> detCurve() { return detCurve; }
}
