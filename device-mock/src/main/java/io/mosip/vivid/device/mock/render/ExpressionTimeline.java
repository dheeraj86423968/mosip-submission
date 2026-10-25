package io.mosip.vivid.device.mock.render;

import io.mosip.vivid.device.mock.render.ExpressionEvent.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A script of expressions and head movements over time, e.g. "blink at 1200 ms, yaw to 25 degrees between
 * 2000 and 3000 ms". {@link #stateAt(long)} resolves it into a {@link FaceState} for the renderer, so automated
 * tests can exercise every active challenge without real video.
 *
 * <p>Overlapping events of the same action take the maximum envelope; different actions compose freely
 * (a subject can smile while turning).
 */
public final class ExpressionTimeline {
    private static final ExpressionTimeline EMPTY = new ExpressionTimeline(List.of());

    private final List<ExpressionEvent> events;
    private final long durationMs;

    private ExpressionTimeline(List<ExpressionEvent> events) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        long end = 0;
        for (ExpressionEvent e : events) end = Math.max(end, e.toMs());
        this.durationMs = end;
    }

    /** A timeline with no events: the face stays neutral. */
    public static ExpressionTimeline empty() { return EMPTY; }

    public static ExpressionTimeline of(List<ExpressionEvent> events) { return new ExpressionTimeline(events); }

    public static Builder builder() { return new Builder(); }

    public List<ExpressionEvent> events() { return events; }

    /** End of the last event in ms; 0 for an empty timeline. */
    public long durationMs() { return durationMs; }

    /** Resolve the face state at {@code tMs}. Times beyond {@link #durationMs()} yield neutral. */
    public FaceState stateAt(long tMs) {
        double blink = 0, smile = 0, mouth = 0, brow = 0, yaw = 0, pitch = 0;
        for (ExpressionEvent e : events) {
            double env = e.envelope(tMs);
            if (env <= 0) continue;
            switch (e.action()) {
                case BLINK -> blink = Math.max(blink, env * e.magnitude());
                case SMILE -> smile = Math.max(smile, env * e.magnitude());
                case OPEN_MOUTH -> mouth = Math.max(mouth, env * e.magnitude());
                case RAISE_EYEBROWS -> brow = Math.max(brow, env * e.magnitude());
                case YAW -> yaw = absMax(yaw, env * e.magnitude());
                case PITCH -> pitch = absMax(pitch, env * e.magnitude());
                case NOD -> {
                    // one full down-up-down cycle across the window, amplitude = magnitude degrees
                    double x = (tMs - e.fromMs()) / (double) Math.max(1, e.durationMs());
                    pitch = absMax(pitch, -Math.sin(x * 2 * Math.PI) * e.magnitude());
                }
                case HOLD_STILL -> { /* explicit neutral; nothing to add */ }
            }
        }
        return new FaceState(yaw, pitch, 1.0 - blink, mouth, smile, brow);
    }

    private static double absMax(double a, double b) { return Math.abs(b) > Math.abs(a) ? b : a; }

    /** Fluent builder mirroring the JSON vocabulary. */
    public static final class Builder {
        private final List<ExpressionEvent> events = new ArrayList<>();
        public Builder event(ExpressionEvent e) { events.add(Objects.requireNonNull(e)); return this; }
        public Builder blinkAt(long atMs) { return event(ExpressionEvent.blinkAt(atMs)); }
        public Builder smile(long fromMs, long toMs) { return event(ExpressionEvent.between(Action.SMILE, fromMs, toMs, 1.0)); }
        public Builder openMouth(long fromMs, long toMs) { return event(ExpressionEvent.between(Action.OPEN_MOUTH, fromMs, toMs, 1.0)); }
        public Builder raiseEyebrows(long fromMs, long toMs) { return event(ExpressionEvent.between(Action.RAISE_EYEBROWS, fromMs, toMs, 1.0)); }
        /** Positive degrees = turn to the subject's left (camera right). */
        public Builder yaw(long fromMs, long toMs, double degrees) { return event(ExpressionEvent.between(Action.YAW, fromMs, toMs, degrees)); }
        /** Positive degrees = look up. */
        public Builder pitch(long fromMs, long toMs, double degrees) { return event(ExpressionEvent.between(Action.PITCH, fromMs, toMs, degrees)); }
        public Builder nod(long fromMs, long toMs, double degrees) { return event(ExpressionEvent.between(Action.NOD, fromMs, toMs, degrees)); }
        public Builder holdStill(long fromMs, long toMs) { return event(ExpressionEvent.between(Action.HOLD_STILL, fromMs, toMs, 0)); }
        public ExpressionTimeline build() { return new ExpressionTimeline(events); }
    }
}
