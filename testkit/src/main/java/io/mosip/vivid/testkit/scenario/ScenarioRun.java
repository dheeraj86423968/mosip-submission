package io.mosip.vivid.testkit.scenario;

import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.mock.scenario.Scenario;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Everything collected while running one scenario through a session. */
public final class ScenarioRun {
    private final Scenario scenario;
    private final LivenessResult result;        // null if the session never became terminal
    private final SessionState finalState;
    private final List<StateTransition> transitions;
    private final List<DeviceHealth> healthEvents;
    private final int framesFed;
    private final long scenarioMillis;
    private final long wallMillis;
    private final boolean timedOut;
    private final Throwable error;              // nullable

    ScenarioRun(Scenario scenario, LivenessResult result, SessionState finalState, List<StateTransition> transitions,
                List<DeviceHealth> healthEvents, int framesFed, long scenarioMillis, long wallMillis, boolean timedOut, Throwable error) {
        this.scenario = scenario; this.result = result; this.finalState = finalState;
        this.transitions = Collections.unmodifiableList(List.copyOf(transitions));
        this.healthEvents = Collections.unmodifiableList(List.copyOf(healthEvents));
        this.framesFed = framesFed; this.scenarioMillis = scenarioMillis; this.wallMillis = wallMillis; this.timedOut = timedOut; this.error = error;
    }

    public Scenario scenario() { return scenario; }
    public Optional<LivenessResult> result() { return Optional.ofNullable(result); }
    public SessionState finalState() { return finalState; }
    /** Every state change in order, with the scenario time at which it happened. */
    public List<StateTransition> trace() { return transitions; }
    public List<DeviceHealth> healthEvents() { return healthEvents; }
    public int framesFed() { return framesFed; }
    /** Scenario (virtual) time covered by the frames fed. */
    public long scenarioMillis() { return scenarioMillis; }
    public long wallMillis() { return wallMillis; }
    /** True if the run stopped because of the wall-clock or scenario-time limit rather than a terminal state. */
    public boolean timedOut() { return timedOut; }
    public Optional<Throwable> error() { return Optional.ofNullable(error); }

    /** Decision as text: the result's decision, else "NONE" (plus "/TIMEOUT" or "/ERROR"). */
    public String decisionText() {
        if (result != null) return result.decision().name();
        return "NONE" + (error != null ? "/ERROR" : timedOut ? "/TIMEOUT" : "");
    }
}
