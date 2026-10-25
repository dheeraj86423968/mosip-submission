package io.mosip.vivid.core.fusion;

import java.util.Optional;

/**
 * Outcome of one fusion step.
 *
 * @param livenessScore       smoothed fused liveness in [0,1]; 1 = certainly live
 * @param instantaneousScore  liveness of this frame alone, before temporal smoothing, in [0,1]
 * @param framesFused         number of frames folded into the smoothed score so far
 * @param vetoSignal          id of the signal that vetoed, or {@code null}
 * @param attackClass         attack class reported by the vetoing signal, or {@code null}
 */
public final class FusionResult {
    private final double livenessScore;
    private final double instantaneousScore;
    private final int framesFused;
    private final String vetoSignal;
    private final String attackClass;

    public FusionResult(double livenessScore, double instantaneousScore, int framesFused, String vetoSignal, String attackClass) {
        this.livenessScore = livenessScore;
        this.instantaneousScore = instantaneousScore;
        this.framesFused = framesFused;
        this.vetoSignal = vetoSignal;
        this.attackClass = attackClass;
    }

    public double livenessScore() { return livenessScore; }
    public double instantaneousScore() { return instantaneousScore; }
    public int framesFused() { return framesFused; }
    public String vetoSignal() { return vetoSignal; }
    public String attackClass() { return attackClass; }

    /** True when a signal vetoed the frame regardless of the fused score. */
    public boolean vetoed() {
        return vetoSignal != null;
    }

    /** Fused attack probability, the complement of {@link #livenessScore()}. */
    public double attackProbability() {
        return 1.0 - livenessScore;
    }

    public Optional<String> vetoSignalOptional() {
        return Optional.ofNullable(vetoSignal);
    }

    public Optional<String> attackClassOptional() {
        return Optional.ofNullable(attackClass);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FusionResult)) return false;
        FusionResult other = (FusionResult) o;
        return Double.compare(livenessScore, other.livenessScore) == 0
                && Double.compare(instantaneousScore, other.instantaneousScore) == 0
                && framesFused == other.framesFused
                && java.util.Objects.equals(vetoSignal, other.vetoSignal)
                && java.util.Objects.equals(attackClass, other.attackClass);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + Double.hashCode(livenessScore);
        h = 31 * h + Double.hashCode(instantaneousScore);
        h = 31 * h + Integer.hashCode(framesFused);
        h = 31 * h + java.util.Objects.hashCode(vetoSignal);
        h = 31 * h + java.util.Objects.hashCode(attackClass);
        return h;
    }

    @Override
    public String toString() {
        return "FusionResult[livenessScore=" + livenessScore + ", instantaneousScore=" + instantaneousScore + ", framesFused=" + framesFused + ", vetoSignal=" + vetoSignal + ", attackClass=" + attackClass + "]";
    }
}
