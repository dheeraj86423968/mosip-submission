package io.mosip.vivid.calibration;

import io.mosip.vivid.api.Workflow;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Guard rails that turn a threshold estimate into a policy change (design 14-dp-calibration.md section 7).
 *
 * <ol>
 *   <li><b>Evidence.</b> No change unless the group has at least {@code minBonaFide} (noisy) bona fide samples and
 *       the standard error of the BPCER estimate is at most {@code maxBpcerStdError}.</li>
 *   <li><b>Minimum change.</b> No change when the estimate is within {@code minChange} of the threshold in force:
 *       a new signed policy for noise-level movements would only churn clients.</li>
 *   <li><b>Step.</b> One update moves the threshold by at most {@code maxStep}; larger corrections take several
 *       release periods, each with fresh evidence.</li>
 *   <li><b>Bounds.</b> The result is clamped into the workflow's [floor, ceiling].</li>
 *   <li><b>Tightening only, unless authorised.</b> Raising the threshold (the tightening direction of
 *       07-configuration.md) is applied automatically. Lowering it weakens PAD: it is proposed but not emitted unless
 *       the job runs with {@code allowLoosening} (a human decision, recorded in the report), and even then it is
 *       refused when more than {@code maxRejectedShare} of the sessions rejected today fall between the new and the
 *       old threshold, because the attacks of the period are in that mass.</li>
 * </ol>
 */
public record CalibrationRules(Map<Workflow, double[]> bounds, double minChange, double maxStep, double minBonaFide,
                               double maxBpcerStdError, boolean allowLoosening, double maxRejectedShare) {

    /** What the job does for one group and workflow. */
    public enum Action { TIGHTEN, LOOSEN, UNCHANGED_SMALL_CHANGE, UNCHANGED_INSUFFICIENT_EVIDENCE, LOOSENING_NOT_AUTHORISED, LOOSENING_REFUSED_REJECTED_MASS }

    /** The decision with its reasons. {@code proposed} is the threshold to put in the signed policy (or the current one). */
    public record Decision(String group, Workflow workflow, double current, double estimate, double proposed, Action action,
                           double bpcerAtCurrent, double bpcerAtProposed, double bpcerStdError, double rejectedShare, List<String> notes) {
        public boolean changes() {
            return action == Action.TIGHTEN || action == Action.LOOSEN;
        }
    }

    /** Floors and ceilings around the compiled defaults (0.80 / 0.85 / 0.90), 1-point steps, tightening only. */
    public static CalibrationRules defaults() {
        Map<Workflow, double[]> b = new EnumMap<>(Workflow.class);
        b.put(Workflow.RESIDENT, new double[] {0.60, 0.97});
        b.put(Workflow.OPERATOR, new double[] {0.70, 0.97});
        b.put(Workflow.SUPERVISOR, new double[] {0.75, 0.98});
        return new CalibrationRules(b, 0.01, 0.05, 300, 0.01, false, 0.10);
    }

    public CalibrationRules withLoosening(boolean allow) {
        return new CalibrationRules(bounds, minChange, maxStep, minBonaFide, maxBpcerStdError, allow, maxRejectedShare);
    }

    public Decision decide(AggregatedHistogram h, double current, double targetBpcer) {
        ThresholdEstimator.Estimate e = ThresholdEstimator.estimate(h, targetBpcer);
        List<String> notes = new ArrayList<>();
        double bpcerNow = ThresholdEstimator.bpcerAt(h, current);
        if (e.empty() || e.bonaFideTotal() < minBonaFide || !(e.bpcerStdError() <= maxBpcerStdError)) {
            notes.add(String.format(Locale.ROOT, "evidence: %.0f bona fide (min %.0f), BPCER std error %.4f (max %.4f)",
                    e.bonaFideTotal(), minBonaFide, e.bpcerStdError(), maxBpcerStdError));
            return new Decision(h.group(), h.workflow(), current, e.threshold(), current, Action.UNCHANGED_INSUFFICIENT_EVIDENCE,
                    bpcerNow, bpcerNow, e.bpcerStdError(), Double.NaN, notes);
        }
        double[] fc = bounds.getOrDefault(h.workflow(), new double[] {0, 1});
        double target = e.threshold();
        if (Math.abs(target - current) < minChange) {
            notes.add(String.format(Locale.ROOT, "|%.4f - %.4f| < minimum change %.3f", target, current, minChange));
            return new Decision(h.group(), h.workflow(), current, target, current, Action.UNCHANGED_SMALL_CHANGE, bpcerNow, bpcerNow,
                    e.bpcerStdError(), Double.NaN, notes);
        }
        double proposed = target;
        if (Math.abs(proposed - current) > maxStep) {
            proposed = current + Math.signum(proposed - current) * maxStep;
            notes.add(String.format(Locale.ROOT, "step limited to %.3f", maxStep));
        }
        if (proposed < fc[0] || proposed > fc[1]) {
            proposed = Math.max(fc[0], Math.min(fc[1], proposed));
            notes.add(String.format(Locale.ROOT, "clamped to [%.2f, %.2f]", fc[0], fc[1]));
        }
        proposed = Math.round(proposed * 1000) / 1000.0;
        if (Math.abs(proposed - current) < minChange) {
            notes.add("bounded change below minimum");
            return new Decision(h.group(), h.workflow(), current, target, current, Action.UNCHANGED_SMALL_CHANGE, bpcerNow, bpcerNow,
                    e.bpcerStdError(), Double.NaN, notes);
        }
        double bpcerNew = ThresholdEstimator.bpcerAt(h, proposed);
        if (proposed > current) {
            return new Decision(h.group(), h.workflow(), current, target, proposed, Action.TIGHTEN, bpcerNow, bpcerNew, e.bpcerStdError(), 0, notes);
        }
        double share = ThresholdEstimator.rejectedShareBetween(h, proposed, current);
        if (!allowLoosening) {
            notes.add("lowering the threshold weakens PAD; rerun with --allow-loosening after review");
            return new Decision(h.group(), h.workflow(), current, target, current, Action.LOOSENING_NOT_AUTHORISED, bpcerNow, bpcerNew,
                    e.bpcerStdError(), share, notes);
        }
        if (share > maxRejectedShare) {
            notes.add(String.format(Locale.ROOT, "%.1f%% of today's rejected sessions lie between %.3f and %.3f (max %.1f%%)",
                    100 * share, proposed, current, 100 * maxRejectedShare));
            return new Decision(h.group(), h.workflow(), current, target, current, Action.LOOSENING_REFUSED_REJECTED_MASS, bpcerNow, bpcerNew,
                    e.bpcerStdError(), share, notes);
        }
        return new Decision(h.group(), h.workflow(), current, target, proposed, Action.LOOSEN, bpcerNow, bpcerNew, e.bpcerStdError(), share, notes);
    }
}
