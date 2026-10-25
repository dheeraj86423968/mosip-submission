package io.mosip.vivid.core.identity;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;

import java.util.Objects;
import java.util.Optional;

/**
 * Signal S7, identity continuity: guards against swapping the subject between the passive phase, the active
 * challenges and the final capture.
 *
 * <p>The first {@code anchorFrames} good-quality embeddings are averaged into a session anchor. Every later
 * embedding is compared to the anchor by cosine distance; a distance above the policy threshold on
 * {@value #CONSECUTIVE_FRAMES_FOR_VETO} consecutive frames is a hard veto. Transient detector glitches on a single
 * frame therefore never fail a session.</p>
 *
 * <p>The signal is <em>veto-only</em>: the orchestrator reports its score but does not fuse it, because identity
 * agreement says nothing about liveness (a photo is perfectly identity-consistent), and a single drifting frame must
 * not trip the generic hard-veto threshold ahead of the consecutive-frame rule.</p>
 *
 * <p>The anchor is exposed so the Registration Client can bind the signed capture image to the session with
 * {@link #compare(float[], float[])}. Embeddings live only for the session and are never persisted here.</p>
 *
 * <p>Not thread-safe; the orchestrator serialises access.</p>
 */
public final class IdentityContinuity {

    /** Consecutive drifting frames required before the veto fires. */
    public static final int CONSECUTIVE_FRAMES_FOR_VETO = 3;
    /** Default number of embeddings averaged into the anchor. */
    public static final int DEFAULT_ANCHOR_FRAMES = 5;

    private final double driftThreshold;
    private final int anchorFrames;
    private float[] anchorSum;
    private int anchored;
    private float[] anchor;
    private int consecutiveDrift;
    private double lastDistance = Double.NaN;

    /** Continuity check with the default anchor size. */
    public IdentityContinuity(double driftThreshold) {
        this(driftThreshold, DEFAULT_ANCHOR_FRAMES);
    }

    /** Continuity check averaging {@code anchorFrames} embeddings into the anchor. */
    public IdentityContinuity(double driftThreshold, int anchorFrames) {
        if (!(driftThreshold > 0)) {
            throw new IllegalArgumentException("driftThreshold must be positive");
        }
        if (anchorFrames < 1) {
            throw new IllegalArgumentException("anchorFrames must be at least 1");
        }
        this.driftThreshold = driftThreshold;
        this.anchorFrames = anchorFrames;
    }

    /**
     * Observes one embedding.
     *
     * @param embedding   identity embedding of the primary face, or {@code null} if the engine produced none
     * @param goodQuality whether the frame passed the quality gate; only good frames build the anchor
     * @return the identity signal for this frame, empty while no comparison was possible
     */
    public Optional<SignalScore> observe(float[] embedding, boolean goodQuality) {
        if (embedding == null || embedding.length == 0) {
            return Optional.empty();
        }
        if (anchor == null) {
            if (!goodQuality) {
                return Optional.empty();
            }
            addToAnchor(embedding);
            return Optional.of(SignalScore.of(Signals.IDENTITY, 0.0, 0.0));
        }
        double distance = compare(anchor, embedding);
        lastDistance = distance;
        if (distance > driftThreshold) {
            consecutiveDrift++;
        } else {
            consecutiveDrift = 0;
        }
        boolean veto = consecutiveDrift >= CONSECUTIVE_FRAMES_FOR_VETO;
        double p = Math.max(0.0, Math.min(1.0, distance / (2 * driftThreshold)));
        return Optional.of(new SignalScore(Signals.IDENTITY, veto ? 1.0 : p, goodQuality ? 0.9 : 0.4, veto, null));
    }

    private void addToAnchor(float[] e) {
        if (anchorSum == null) {
            anchorSum = new float[e.length];
        } else if (anchorSum.length != e.length) {
            throw new IllegalArgumentException("embedding dimension changed from " + anchorSum.length + " to " + e.length);
        }
        for (int i = 0; i < e.length; i++) {
            anchorSum[i] += e[i];
        }
        anchored++;
        if (anchored >= anchorFrames) {
            anchor = new float[anchorSum.length];
            for (int i = 0; i < anchorSum.length; i++) {
                anchor[i] = anchorSum[i] / anchored;
            }
        }
    }

    /** The session anchor once {@code anchorFrames} good embeddings were seen. */
    public Optional<float[]> anchor() {
        return anchor == null ? Optional.empty() : Optional.of(anchor.clone());
    }

    /** True once the anchor is established and comparisons are being made. */
    public boolean anchored() {
        return anchor != null;
    }

    /** Last cosine distance measured, NaN before the first comparison. */
    public double lastDistance() {
        return lastDistance;
    }

    /** Threshold in force. */
    public double driftThreshold() {
        return driftThreshold;
    }

    /** Drops the drift streak (but keeps the anchor) at the start of a new attempt. */
    public void resetStreak() {
        consecutiveDrift = 0;
    }

    /**
     * Cosine distance {@code 1 - cos(a, b)} in [0,2]; 0 means identical direction. Used by the Registration Client
     * to bind the signed capture image's embedding to {@link #anchor()}.
     *
     * @throws IllegalArgumentException if lengths differ or either vector is zero
     */
    public static double compare(float[] a, float[] b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.length != b.length || a.length == 0) {
            throw new IllegalArgumentException("embeddings must have the same non-zero length");
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            throw new IllegalArgumentException("zero-length embedding vector");
        }
        double cos = dot / (Math.sqrt(na) * Math.sqrt(nb));
        return 1.0 - Math.max(-1.0, Math.min(1.0, cos));
    }
}
