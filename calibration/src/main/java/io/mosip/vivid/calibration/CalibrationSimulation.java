package io.mosip.vivid.calibration;

import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.telemetry.calibration.DpMechanism;
import io.mosip.vivid.telemetry.calibration.ScoreBinning;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Threshold-recovery simulation for privacy-preserving calibration (design 14-dp-calibration.md section 9).
 *
 * <p>Synthetic registration centres differ by a lighting/device transform of the score distribution in logit space,
 * {@code z' = scale * z + shift}. The base distributions are the fused passive scores of the proxy PAD evaluation
 * (real faces in openly licensed video, simulated attacks), smoothed with a Gaussian kernel in logit space. Each client
 * of a centre runs sessions through the real retry/label logic: a bona fide subject has a per-subject level and
 * per-attempt noise; a session is labelled bona fide only if some attempt passes at the threshold in force (so the
 * labels carry the same selection bias as in the field); a small share of sessions are attacks, some of which pass
 * and are never flagged. Every client's histogram is privatised with the production {@link DpMechanism}, summed, and
 * fed to the production {@link ThresholdEstimator}. The error is measured against the centre's true first-attempt
 * bona fide quantile.</p>
 */
public final class CalibrationSimulation {

    /** A synthetic centre: logit-space transform of the base score distributions. */
    public record Centre(String name, String condition, double scale, double shift) {}

    /** Simulation parameters. */
    public record Config(double targetBpcer, double currentThreshold, int sessionsPerClient, double attackRate, int maxAttempts,
                         double withinSubjectSd, double kernelBandwidth, double bonaFideChallengePass, double attackChallengePass,
                         double attackFlaggedLater, double assistedAcceptance, int reps, long seed, List<String> epsilons,
                         List<Integer> clientCounts) {

        public static Config defaults() {
            return new Config(0.05, 0.80, 400, 0.02, 3, 0.5, 0.35, 0.97, 0.05, 0.5, 0.9, 20, 20261024L,
                    List.of("0.1", "0.25", "0.5", "1", "2", "inf"), List.of(1, 5, 10, 25, 50, 100));
        }

        public Config withAssistedAcceptance(double p) {
            return new Config(targetBpcer, currentThreshold, sessionsPerClient, attackRate, maxAttempts, withinSubjectSd, kernelBandwidth,
                    bonaFideChallengePass, attackChallengePass, attackFlaggedLater, p, reps, seed, epsilons, clientCounts);
        }

        public Config withReps(int r, int sessions) {
            return new Config(targetBpcer, currentThreshold, sessions, attackRate, maxAttempts, withinSubjectSd, kernelBandwidth,
                    bonaFideChallengePass, attackChallengePass, attackFlaggedLater, assistedAcceptance, r, seed, epsilons, clientCounts);
        }
    }

    public static final List<Centre> CENTRES = List.of(
            new Centre("C1", "reference (proxy-eval conditions)", 1.00, 0.0),
            new Centre("C2", "dim indoor lighting", 0.90, -1.2),
            new Centre("C3", "strong backlight", 0.85, -2.0),
            new Centre("C4", "low-end USB webcam", 0.95, -0.7),
            new Centre("C5", "bright, good camera", 1.05, 0.4),
            new Centre("C6", "outdoor camp, mixed light", 0.80, -1.6));

    /** One cell of the result grid. */
    public record Cell(String epsilon, int clients, double meanAbsThresholdError, double p90AbsThresholdError,
                       double meanAbsBpcerError, double meanBpcerBias, double insufficientEvidenceRate) {}

    /** Per-centre detail at one (epsilon, clients) point. */
    public record CentreDetail(Centre centre, double trueThreshold, double trueBpcerAtCurrent, double estimate, double trueBpcerAtEstimate,
                               CalibrationRules.Decision tighteningOnly, CalibrationRules.Decision withLoosening) {}

    private final Config cfg;
    private final ScoreBinning binning = ScoreBinning.DEFAULT;
    private final double[] bonaFide;
    private final double[] attacks;
    private final Map<String, double[]> truth = new LinkedHashMap<>();

    public CalibrationSimulation(Config cfg) {
        this.cfg = cfg;
        List<Double> bf = new ArrayList<>();
        List<Double> at = new ArrayList<>();
        try (InputStream in = CalibrationSimulation.class.getResourceAsStream("/proxy-eval-passive-scores.csv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") || line.startsWith("label")) continue;
                String[] p = line.split(",");
                double z = logit(Double.parseDouble(p[1]));
                (p[0].equals("BONA_FIDE") ? bf : at).add(z);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        bonaFide = bf.stream().mapToDouble(Double::doubleValue).toArray();
        attacks = at.stream().mapToDouble(Double::doubleValue).toArray();
        Random r = new Random(cfg.seed() ^ 0x5eed);
        for (Centre c : CENTRES) {
            double[] first = new double[400_000];
            for (int i = 0; i < first.length; i++) {
                first[i] = sigmoid(subject(r, bonaFide, c) + r.nextGaussian() * cfg.withinSubjectSd());
            }
            Arrays.sort(first);
            truth.put(c.name(), first);
        }
    }

    public int baseBonaFide() { return bonaFide.length; }
    public int baseAttacks() { return attacks.length; }

    /** True first-attempt BPCER quantile of a centre. */
    public double trueThreshold(Centre c) {
        double[] s = truth.get(c.name());
        return s[(int) Math.floor(cfg.targetBpcer() * s.length)];
    }

    /** True first-attempt BPCER of a centre at threshold t. */
    public double trueBpcer(Centre c, double t) {
        double[] s = truth.get(c.name());
        int i = Arrays.binarySearch(s, t);
        if (i < 0) i = -i - 1;
        return (double) i / s.length;
    }

    /** Receives one simulated session: first-attempt score and whether it ends up labelled bona fide. */
    @FunctionalInterface
    public interface SessionSink {
        void accept(double firstAttemptScore, boolean bonaFideLabel);
    }

    /** One client's raw [label][bin] histogram for one release period. */
    long[][] client(Random r, Centre c) {
        long[][] h = new long[2][binning.bins()];
        sessions(r, c, cfg.sessionsPerClient(), (first, bf) -> h[bf ? 0 : 1][binning.bin(first)]++);
        return h;
    }

    /** Simulates {@code count} sessions at a centre with the retry and labelling logic described above. */
    public void sessions(Random r, Centre c, int count, SessionSink sink) {
        for (int n = 0; n < count; n++) {
            boolean attack = r.nextDouble() < cfg.attackRate();
            double level = subject(r, attack ? attacks : bonaFide, c);
            double first = Double.NaN;
            boolean passed = false;
            for (int k = 0; k < cfg.maxAttempts() && !passed; k++) {
                double s = sigmoid(level + r.nextGaussian() * cfg.withinSubjectSd());
                if (k == 0) first = s;
                passed = s >= cfg.currentThreshold() && r.nextDouble() < (attack ? cfg.attackChallengePass() : cfg.bonaFideChallengePass());
            }
            boolean bonaFideLabel;
            if (passed) {
                // an attack that got through is caught later (back-office adjudication, deduplication) only sometimes
                bonaFideLabel = !(attack && r.nextDouble() < cfg.attackFlaggedLater());
            } else {
                // every attempt failed: onMaxRetries = ASSISTED_CAPTURE_WITH_SUPERVISOR. A supervisor attests a live
                // resident (the capture is accepted, so the session is labelled bona fide) unless the resident gives
                // up first; a supervisor refuses an artefact.
                bonaFideLabel = !attack && r.nextDouble() < cfg.assistedAcceptance();
            }
            sink.accept(first, bonaFideLabel);
        }
    }

    /** Runs the grid; returns one cell per (epsilon, clients). */
    public List<Cell> run() {
        int maxK = cfg.clientCounts().stream().mapToInt(Integer::intValue).max().orElse(1);
        int cells = cfg.epsilons().size() * cfg.clientCounts().size();
        double[][] absT = new double[cells][];
        double[][] absB = new double[cells][];
        double[][] bias = new double[cells][];
        int[] insufficient = new int[cells];
        int[] fill = new int[cells];
        int samples = cfg.reps() * CENTRES.size();
        for (int i = 0; i < cells; i++) {
            absT[i] = new double[samples];
            absB[i] = new double[samples];
            bias[i] = new double[samples];
        }
        CalibrationRules rules = CalibrationRules.defaults();
        Random r = new Random(cfg.seed());
        for (int rep = 0; rep < cfg.reps(); rep++) {
            for (Centre c : CENTRES) {
                long[][][] raw = new long[maxK][][];
                for (int k = 0; k < maxK; k++) {
                    raw[k] = client(r, c);
                }
                for (int e = 0; e < cfg.epsilons().size(); e++) {
                    String eps = cfg.epsilons().get(e);
                    DpMechanism m = eps.equals("inf") ? null : DpMechanism.discreteLaplace(eps);
                    double var = m == null ? 0 : m.noiseVariance(1);
                    double[] bf = new double[binning.bins()];
                    double[] rej = new double[binning.bins()];
                    int k = 0;
                    for (int ci = 0; ci < cfg.clientCounts().size(); ci++) {
                        int upto = cfg.clientCounts().get(ci);
                        for (; k < upto; k++) {
                            long[] b = m == null ? raw[k][0] : m.privatize(raw[k][0], 1, r);
                            long[] j = m == null ? raw[k][1] : m.privatize(raw[k][1], 1, r);
                            for (int i = 0; i < bf.length; i++) {
                                bf[i] += b[i];
                                rej[i] += j[i];
                            }
                        }
                        AggregatedHistogram h = AggregatedHistogram.of(c.name(), Workflow.RESIDENT, binning, bf.clone(), rej.clone(), upto, upto * var);
                        ThresholdEstimator.Estimate est = ThresholdEstimator.estimate(h, cfg.targetBpcer());
                        int cell = e * cfg.clientCounts().size() + ci;
                        double t = est.empty() ? cfg.currentThreshold() : est.threshold();
                        double tb = trueBpcer(c, t);
                        absT[cell][fill[cell]] = Math.abs(t - trueThreshold(c));
                        absB[cell][fill[cell]] = Math.abs(tb - cfg.targetBpcer());
                        bias[cell][fill[cell]] = tb - cfg.targetBpcer();
                        fill[cell]++;
                        if (rules.decide(h, cfg.currentThreshold(), cfg.targetBpcer()).action() == CalibrationRules.Action.UNCHANGED_INSUFFICIENT_EVIDENCE) {
                            insufficient[cell]++;
                        }
                    }
                }
            }
        }
        List<Cell> out = new ArrayList<>();
        for (int e = 0; e < cfg.epsilons().size(); e++) {
            for (int ci = 0; ci < cfg.clientCounts().size(); ci++) {
                int cell = e * cfg.clientCounts().size() + ci;
                double[] t = absT[cell].clone();
                Arrays.sort(t);
                out.add(new Cell(cfg.epsilons().get(e), cfg.clientCounts().get(ci), mean(absT[cell]), t[(int) Math.floor(0.9 * (t.length - 1))],
                        mean(absB[cell]), mean(bias[cell]), (double) insufficient[cell] / samples));
            }
        }
        return out;
    }

    /** Per-centre decisions at one grid point (single draw). */
    public List<CentreDetail> details(String epsilon, int clients) {
        Random r = new Random(cfg.seed() + 7);
        DpMechanism m = epsilon.equals("inf") ? null : DpMechanism.discreteLaplace(epsilon);
        List<CentreDetail> out = new ArrayList<>();
        for (Centre c : CENTRES) {
            double[] bf = new double[binning.bins()];
            double[] rej = new double[binning.bins()];
            for (int k = 0; k < clients; k++) {
                long[][] raw = client(r, c);
                long[] b = m == null ? raw[0] : m.privatize(raw[0], 1, r);
                long[] j = m == null ? raw[1] : m.privatize(raw[1], 1, r);
                for (int i = 0; i < bf.length; i++) {
                    bf[i] += b[i];
                    rej[i] += j[i];
                }
            }
            AggregatedHistogram h = AggregatedHistogram.of(c.name(), Workflow.RESIDENT, binning, bf, rej, clients, m == null ? 0 : clients * m.noiseVariance(1));
            ThresholdEstimator.Estimate est = ThresholdEstimator.estimate(h, cfg.targetBpcer());
            out.add(new CentreDetail(c, trueThreshold(c), trueBpcer(c, cfg.currentThreshold()), est.threshold(), trueBpcer(c, est.threshold()),
                    CalibrationRules.defaults().decide(h, cfg.currentThreshold(), cfg.targetBpcer()),
                    CalibrationRules.defaults().withLoosening(true).decide(h, cfg.currentThreshold(), cfg.targetBpcer())));
        }
        return out;
    }

    private double subject(Random r, double[] base, Centre c) {
        double z = base[r.nextInt(base.length)] + r.nextGaussian() * cfg.kernelBandwidth();
        return c.scale() * z + c.shift();
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return a.length == 0 ? Double.NaN : s / a.length;
    }

    static double logit(double s) {
        double c = Math.max(1e-4, Math.min(1 - 1e-4, s));
        return Math.log(c / (1 - c));
    }

    static double sigmoid(double z) {
        return 1 / (1 + Math.exp(-z));
    }

    /** Markdown rendering of a grid. */
    public static String toMarkdown(List<Cell> cells, Config cfg) {
        StringBuilder sb = new StringBuilder();
        List<Integer> ks = cfg.clientCounts();
        sb.append("Mean |t̂ − t*| (score units) / mean |BPCER(t̂) − target| (percentage points); ")
          .append(cfg.reps()).append(" repetitions × ").append(CENTRES.size()).append(" centres per cell.\n\n");
        sb.append("| ε per release \\ clients per centre |");
        for (int k : ks) sb.append(' ').append(k).append(" |");
        sb.append("\n|---|");
        for (int i = 0; i < ks.size(); i++) sb.append("---|");
        sb.append('\n');
        for (String e : cfg.epsilons()) {
            sb.append("| ").append(e.equals("inf") ? "∞ (no noise)" : e).append(" |");
            for (int k : ks) {
                Cell c = cells.stream().filter(x -> x.epsilon().equals(e) && x.clients() == k).findFirst().orElseThrow();
                sb.append(String.format(Locale.ROOT, " %.3f / %.1f |", c.meanAbsThresholdError(), 100 * c.meanAbsBpcerError()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
