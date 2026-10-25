package io.mosip.vivid.calibration;

import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.telemetry.calibration.ScoreBinning;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Sum of the noisy histograms of every client in one calibration group (a centre, or all centres) for one workflow.
 * Counts are kept as raw noisy sums, including negative values: clamping before summing would bias the totals.
 *
 * @param noiseVariancePerBin variance of the summed noise on each bin (sum over clients)
 */
public record AggregatedHistogram(String group, Workflow workflow, ScoreBinning binning, double[] bonaFide, double[] rejected,
                                  int clients, double noiseVariancePerBin, List<Double> thresholdsInForce) {

    /** Groups releases by {@code groupOf} and workflow and sums them. Releases with a different binning are rejected. */
    public static List<AggregatedHistogram> aggregate(Collection<ClientRelease> releases, Function<ClientRelease, String> groupOf) {
        Map<String, List<ClientRelease>> groups = new LinkedHashMap<>();
        for (ClientRelease r : releases) {
            groups.computeIfAbsent(groupOf.apply(r) + "\u0000" + r.workflow(), k -> new ArrayList<>()).add(r);
        }
        List<AggregatedHistogram> out = new ArrayList<>();
        for (List<ClientRelease> g : groups.values()) {
            ClientRelease first = g.get(0);
            ScoreBinning b = first.binning();
            double[] bf = new double[b.bins()];
            double[] rej = new double[b.bins()];
            double var = 0;
            TreeSet<Double> thresholds = new TreeSet<>();
            for (ClientRelease r : g) {
                if (!r.binning().equals(b)) {
                    throw new IllegalArgumentException("mixed binnings in group " + groupOf.apply(r) + ": " + b + " and " + r.binning());
                }
                for (int i = 0; i < b.bins(); i++) {
                    bf[i] += r.bonaFide()[i];
                    rej[i] += r.rejected()[i];
                }
                var += r.noiseVariance();
                if (r.threshold() != null) {
                    thresholds.add(r.threshold());
                }
            }
            out.add(new AggregatedHistogram(groupOf.apply(first), first.workflow(), b, bf, rej, g.size(), var, new ArrayList<>(thresholds)));
        }
        return out;
    }

    /** Builds a histogram directly from summed counts (simulation and tests). */
    public static AggregatedHistogram of(String group, Workflow wf, ScoreBinning b, double[] bf, double[] rej, int clients, double var) {
        return new AggregatedHistogram(group, wf, b, bf, rej, clients, var, List.of());
    }
}
