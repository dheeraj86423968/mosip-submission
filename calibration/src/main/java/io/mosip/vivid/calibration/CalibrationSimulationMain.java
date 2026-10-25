package io.mosip.vivid.calibration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Runs {@link CalibrationSimulation} and writes {@code simulation.md} and {@code simulation.csv}.
 *
 * <pre>CalibrationSimulationMain [--out dir] [--reps 20] [--sessions 400]</pre>
 */
public final class CalibrationSimulationMain {

    private CalibrationSimulationMain() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of("target", "calibration-simulation");
        CalibrationSimulation.Config d = CalibrationSimulation.Config.defaults();
        int reps = d.reps();
        int sessions = d.sessionsPerClient();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--reps" -> reps = Integer.parseInt(args[++i]);
                case "--sessions" -> sessions = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        CalibrationSimulation.Config cfg = d.withReps(reps, sessions);
        long t0 = System.nanoTime();
        CalibrationSimulation sim = new CalibrationSimulation(cfg);
        List<CalibrationSimulation.Cell> cells = sim.run();
        StringBuilder md = new StringBuilder("# Calibration simulation\n\n");
        md.append(String.format(Locale.ROOT, "Base: %d bona fide and %d attack scores from the proxy evaluation; target BPCER %.2f; "
                        + "threshold in force %.2f; %d sessions per client per release; attack rate %.2f; seed %d.%n%n",
                sim.baseBonaFide(), sim.baseAttacks(), cfg.targetBpcer(), cfg.currentThreshold(), cfg.sessionsPerClient(), cfg.attackRate(), cfg.seed()));
        md.append("## Centres\n\n| Centre | Condition | Logit transform | True t* (BPCER 5 %) | True BPCER at 0.80 |\n|---|---|---|---|---|\n");
        for (CalibrationSimulation.Centre c : CalibrationSimulation.CENTRES) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %.2f·z %+.1f | %.4f | %.1f %% |%n", c.name(), c.condition(), c.scale(), c.shift(),
                    sim.trueThreshold(c), 100 * sim.trueBpcer(c, cfg.currentThreshold())));
        }
        md.append("\n## Recovery error\n\n").append(CalibrationSimulation.toMarkdown(cells, cfg));
        md.append("\n## Bias and evidence\n\n| ε | clients | mean signed BPCER error (pp) | p90 |t̂ − t*| | releases the rules reject as insufficient evidence |\n|---|---|---|---|---|\n");
        StringBuilder csv = new StringBuilder("epsilon,clients,mean_abs_threshold_error,p90_abs_threshold_error,mean_abs_bpcer_error,mean_bpcer_bias,insufficient_rate\n");
        for (CalibrationSimulation.Cell c : cells) {
            md.append(String.format(Locale.ROOT, "| %s | %d | %+.2f | %.3f | %.0f %% |%n", c.epsilon(), c.clients(), 100 * c.meanBpcerBias(),
                    c.p90AbsThresholdError(), 100 * c.insufficientEvidenceRate()));
            csv.append(String.format(Locale.ROOT, "%s,%d,%.6f,%.6f,%.6f,%.6f,%.4f%n", c.epsilon(), c.clients(), c.meanAbsThresholdError(),
                    c.p90AbsThresholdError(), c.meanAbsBpcerError(), c.meanBpcerBias(), c.insufficientEvidenceRate()));
        }
        md.append("\n## Decisions per centre at ε = 1, 25 clients (one draw)\n\n")
          .append("| Centre | t* | estimate | true BPCER at estimate | tightening-only job | with --allow-loosening |\n|---|---|---|---|---|---|\n");
        for (CalibrationSimulation.CentreDetail d2 : sim.details("1", 25)) {
            md.append(String.format(Locale.ROOT, "| %s | %.4f | %.4f | %.1f %% | %s → %.3f | %s → %.3f |%n", d2.centre().name(), d2.trueThreshold(),
                    d2.estimate(), 100 * d2.trueBpcerAtEstimate(), d2.tighteningOnly().action(), d2.tighteningOnly().proposed(),
                    d2.withLoosening().action(), d2.withLoosening().proposed()));
        }
        md.append("\n## Label selection bias (no noise, 100 clients per centre)\n\n")
          .append("Share of bona fide sessions that failed every attempt and were then accepted through supervisor-assisted capture "
                  + "(and therefore labelled bona fide). 0 means only sessions that passed liveness are labelled bona fide.\n\n")
          .append("| assisted acceptance | mean |t̂ − t*| | mean |BPCER(t̂) − target| (pp) | mean signed BPCER error (pp) |\n|---|---|---|---|\n");
        for (double p : new double[] {0.0, 0.5, 0.9, 1.0}) {
            CalibrationSimulation.Config bc = new CalibrationSimulation.Config(cfg.targetBpcer(), cfg.currentThreshold(), cfg.sessionsPerClient(),
                    cfg.attackRate(), cfg.maxAttempts(), cfg.withinSubjectSd(), cfg.kernelBandwidth(), cfg.bonaFideChallengePass(),
                    cfg.attackChallengePass(), cfg.attackFlaggedLater(), p, Math.max(3, cfg.reps() / 4), cfg.seed(), List.of("inf"), List.of(100));
            CalibrationSimulation.Cell c = new CalibrationSimulation(bc).run().get(0);
            md.append(String.format(Locale.ROOT, "| %.1f | %.3f | %.1f | %+.1f |%n", p, c.meanAbsThresholdError(), 100 * c.meanAbsBpcerError(),
                    100 * c.meanBpcerBias()));
        }
        md.append(String.format(Locale.ROOT, "%nRun time %.1f s.%n", (System.nanoTime() - t0) / 1e9));
        Files.createDirectories(out);
        Files.writeString(out.resolve("simulation.md"), md.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("simulation.csv"), csv.toString(), StandardCharsets.UTF_8);
        System.out.println(md);
    }
}
