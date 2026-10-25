package io.mosip.vivid.device.mock;

/**
 * How scenario (virtual) time maps onto wall-clock time during in-process replay.
 * Frame timestamps always follow scenario time; pacing only decides how long the delivery thread sleeps.
 */
public final class Pacing {
    private final double speed;

    private Pacing(double speed) { this.speed = speed; }

    /** One scenario millisecond per wall millisecond: behaves like a real camera. */
    public static Pacing realTime() { return new Pacing(1.0); }

    /** Never sleep: deliver frames as fast as the sink accepts them. Use in unit tests. */
    public static Pacing unthrottled() { return new Pacing(Double.POSITIVE_INFINITY); }

    /** Scenario runs {@code factor} times faster than wall time (2.0 = twice as fast, 0.5 = slow motion). */
    public static Pacing speed(double factor) {
        if (!(factor > 0)) throw new IllegalArgumentException("factor must be > 0");
        return new Pacing(factor);
    }

    /** True if no sleeping happens at all. */
    public boolean isUnthrottled() { return Double.isInfinite(speed); }

    /** Wall-clock milliseconds to wait for {@code virtualMs} of scenario time. */
    public long wallMillis(long virtualMs) {
        if (isUnthrottled() || virtualMs <= 0) return 0;
        return Math.round(virtualMs / speed);
    }

    @Override public String toString() { return isUnthrottled() ? "unthrottled" : "x" + speed; }
}
