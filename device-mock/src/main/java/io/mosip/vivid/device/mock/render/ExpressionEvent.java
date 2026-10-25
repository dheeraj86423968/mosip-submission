package io.mosip.vivid.device.mock.render;

import java.util.Objects;

/**
 * One scripted action on the {@link ExpressionTimeline}: either instantaneous ({@code atMs}, e.g. a blink
 * lasting {@code durationMs}) or sustained ({@code fromMs}..{@code toMs}, e.g. a yaw held at {@code degrees}).
 * Sustained actions ramp in over the first 30% of the window, hold, and ramp out over the last 30%, so the
 * resulting motion is smooth and challenge detectors see a realistic onset.
 */
public final class ExpressionEvent {
    /** What the face does. Mirrors the active challenge types so one event can satisfy one challenge. */
    public enum Action { BLINK, SMILE, OPEN_MOUTH, RAISE_EYEBROWS, YAW, PITCH, NOD, HOLD_STILL }

    /** Default length of a blink in milliseconds. */
    public static final long DEFAULT_BLINK_MS = 300;

    private final Action action;
    private final long fromMs;
    private final long toMs;
    private final double magnitude;

    private ExpressionEvent(Action action, long fromMs, long toMs, double magnitude) {
        this.action = Objects.requireNonNull(action, "action");
        if (fromMs < 0 || toMs < fromMs) throw new IllegalArgumentException("invalid window " + fromMs + ".." + toMs);
        this.fromMs = fromMs; this.toMs = toMs; this.magnitude = magnitude;
    }

    /** A blink centred on {@code atMs}. */
    public static ExpressionEvent blinkAt(long atMs) {
        return new ExpressionEvent(Action.BLINK, Math.max(0, atMs - DEFAULT_BLINK_MS / 2), atMs + DEFAULT_BLINK_MS / 2, 1.0);
    }

    /** A sustained action between two instants. Magnitude is degrees for YAW/PITCH/NOD and intensity [0,1] otherwise. */
    public static ExpressionEvent between(Action action, long fromMs, long toMs, double magnitude) {
        return new ExpressionEvent(action, fromMs, toMs, magnitude);
    }

    public Action action() { return action; }
    public long fromMs() { return fromMs; }
    public long toMs() { return toMs; }
    /** Degrees for YAW/PITCH/NOD, intensity in [0,1] for the others. */
    public double magnitude() { return magnitude; }
    public long durationMs() { return toMs - fromMs; }
    public boolean covers(long tMs) { return tMs >= fromMs && tMs < toMs; }

    /**
     * Activation envelope in [0,1] at time t: 0 outside the window; inside, a ramp-hold-ramp trapezoid
     * (or a triangle for very short events such as blinks).
     */
    public double envelope(long tMs) {
        if (!covers(tMs)) return 0;
        double len = Math.max(1, durationMs());
        double x = (tMs - fromMs) / len;              // 0..1
        double ramp = action == Action.BLINK ? 0.5 : 0.3;
        if (x < ramp) return x / ramp;
        if (x > 1 - ramp) return (1 - x) / ramp;
        return 1.0;
    }

    @Override public String toString() { return action + "[" + fromMs + ".." + toMs + "ms x" + magnitude + "]"; }
}
