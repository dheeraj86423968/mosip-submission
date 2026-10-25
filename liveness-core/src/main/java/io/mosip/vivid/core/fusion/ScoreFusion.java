package io.mosip.vivid.core.fusion;

import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Weighted log-odds fusion of per-signal attack probabilities with temporal smoothing and hard-veto rules
 * (PLAN.md section 4.3).
 *
 * <h2>Per frame</h2>
 * <ol>
 *   <li>Each signal {@code i} contributes {@code w_i * c_i * logit(p_i)} where {@code p_i} is its calibrated attack
 *       probability, {@code c_i} its confidence and {@code w_i} the policy weight ({@code 0} disables the signal).
 *       The quality gate signal is never fused.</li>
 *   <li>The sum is normalised by {@code sum(w_i * c_i)} so the scale does not depend on how many signals an engine
 *       provides; the same policy threshold then means the same thing for every engine.</li>
 *   <li>Instantaneous liveness is {@code 1 - sigmoid(fused logit)}.</li>
 *   <li>Temporal smoothing is an exponential moving average with factor {@code alpha}: one bad frame cannot fail a
 *       session, one good frame cannot pass it.</li>
 *   <li>Hard veto: any enabled signal with {@link SignalScore#hardVeto()}, or with attack probability at or above
 *       {@link LivenessPolicy#padHardThreshold()} and confidence at least {@value #VETO_MIN_CONFIDENCE}, vetoes the
 *       frame regardless of the fused score.</li>
 * </ol>
 *
 * <p>Session-level signals (challenge timing, identity) can be pinned with {@link #setSessionSignal} so they
 * take part in every subsequent frame's fusion.</p>
 *
 * <p>Not thread-safe; the orchestrator serialises access.</p>
 */
public final class ScoreFusion {

    /** Default EMA factor; about 8 frames to reach 90 % of a step change. */
    public static final double DEFAULT_ALPHA = 0.25;
    /** Minimum confidence for a threshold-based (non-flagged) veto. */
    public static final double VETO_MIN_CONFIDENCE = 0.5;
    /** Probabilities are clamped into [EPS, 1-EPS] before the logit so a certain signal cannot produce infinities. */
    static final double EPS = 1e-4;

    private final ToDoubleFunction<String> weightOf;
    private final double padHardThreshold;
    private final double alpha;
    private final Map<String, SignalScore> sessionSignals = new LinkedHashMap<>();
    private final Map<String, Double> lastAttackProbabilities = new LinkedHashMap<>();
    private double ema = Double.NaN;
    private int framesFused;

    /** Fusion configured from a policy with the default smoothing factor. */
    public ScoreFusion(LivenessPolicy policy) {
        this(policy, DEFAULT_ALPHA);
    }

    /** Fusion configured from a policy with an explicit smoothing factor in (0,1]. */
    public ScoreFusion(LivenessPolicy policy, double alpha) {
        this(Objects.requireNonNull(policy, "policy")::signalWeight, policy.padHardThreshold(), alpha);
    }

    /** Fully explicit configuration, for tests and non-policy callers. */
    public ScoreFusion(ToDoubleFunction<String> weightOf, double padHardThreshold, double alpha) {
        if (!(alpha > 0 && alpha <= 1)) {
            throw new IllegalArgumentException("alpha must be in (0,1]");
        }
        this.weightOf = Objects.requireNonNull(weightOf, "weightOf");
        this.padHardThreshold = padHardThreshold;
        this.alpha = alpha;
    }

    /**
     * Pure single-frame combination: returns the instantaneous liveness score in [0,1] for the given signals, or
     * {@code 0.5} (no evidence) when no fusable signal is present.
     */
    public static double combine(List<SignalScore> signals, ToDoubleFunction<String> weightOf) {
        double num = 0;
        double den = 0;
        for (SignalScore s : signals) {
            if (!fusable(s)) {
                continue;
            }
            double w = weightOf.applyAsDouble(s.signalId()) * s.confidence();
            if (w <= 0) {
                continue;
            }
            num += w * logit(s.attackProbability());
            den += w;
        }
        if (den <= 0) {
            return 0.5;
        }
        return 1.0 - sigmoid(num / den);
    }

    /**
     * Finds a vetoing signal: flagged {@code hardVeto}, or attack probability at or above {@code padHardThreshold}
     * with confidence at least {@value #VETO_MIN_CONFIDENCE}. Signals with weight 0 are disabled and cannot veto.
     *
     * @return the vetoing signal or {@code null}
     */
    public static SignalScore findVeto(List<SignalScore> signals, ToDoubleFunction<String> weightOf, double padHardThreshold) {
        for (SignalScore s : signals) {
            if (!fusable(s) || weightOf.applyAsDouble(s.signalId()) <= 0) {
                continue;
            }
            if (s.hardVeto() || (s.attackProbability() >= padHardThreshold && s.confidence() >= VETO_MIN_CONFIDENCE)) {
                return s;
            }
        }
        return null;
    }

    /** Folds one frame's signals (plus pinned session signals) into the running score. */
    public FusionResult update(List<SignalScore> frameSignals) {
        List<SignalScore> all = new ArrayList<>(Objects.requireNonNull(frameSignals, "frameSignals"));
        all.addAll(sessionSignals.values());
        for (SignalScore s : all) {
            lastAttackProbabilities.put(s.signalId(), s.attackProbability());
        }
        double inst = combine(all, weightOf);
        boolean hasEvidence = all.stream().anyMatch(s -> fusable(s) && weightOf.applyAsDouble(s.signalId()) * s.confidence() > 0);
        if (hasEvidence) {
            ema = Double.isNaN(ema) ? inst : alpha * inst + (1 - alpha) * ema;
            framesFused++;
        }
        SignalScore veto = findVeto(all, weightOf, padHardThreshold);
        double score = Double.isNaN(ema) ? inst : ema;
        return new FusionResult(score, inst, framesFused, veto == null ? null : veto.signalId(),
                veto == null ? null : (veto.attackClass() == null ? Signals.ATTACK_UNKNOWN : veto.attackClass()));
    }

    /** Pins a session-level signal that is included in every following {@link #update}. Replaces any with the same id. */
    public void setSessionSignal(SignalScore signal) {
        sessionSignals.put(Objects.requireNonNull(signal, "signal").signalId(), signal);
    }

    /**
     * Records a veto-only signal for reporting ({@link #lastAttackProbabilities()}) without fusing it. Used for
     * signals such as identity continuity whose "no attack" reading is not evidence of liveness: a printed photo is
     * perfectly identity-consistent, so letting a matching identity raise the fused score would weaken PAD.
     */
    public void recordAuxiliary(SignalScore signal) {
        Objects.requireNonNull(signal, "signal");
        lastAttackProbabilities.put(signal.signalId(), signal.attackProbability());
    }

    /** Removes a pinned session signal. */
    public void clearSessionSignal(String signalId) {
        sessionSignals.remove(signalId);
    }

    /** Current smoothed liveness, or {@code 0.5} before any evidence. */
    public double livenessScore() {
        return Double.isNaN(ema) ? 0.5 : ema;
    }

    /** Frames that contributed evidence so far. */
    public int framesFused() {
        return framesFused;
    }

    /** Last attack probability seen per signal id, including session signals; for results and diagnostics. */
    public Map<String, Double> lastAttackProbabilities() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lastAttackProbabilities));
    }

    /** Clears the running average and last scores but keeps pinned session signals (they describe the session, not the attempt). */
    public void reset() {
        ema = Double.NaN;
        framesFused = 0;
        lastAttackProbabilities.clear();
    }

    /** Hard-veto threshold in force. */
    public double padHardThreshold() {
        return padHardThreshold;
    }

    private static boolean fusable(SignalScore s) {
        return s != null && !Signals.QUALITY.equals(s.signalId());
    }

    static double logit(double p) {
        double c = Math.max(EPS, Math.min(1 - EPS, p));
        return Math.log(c / (1 - c));
    }

    static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
