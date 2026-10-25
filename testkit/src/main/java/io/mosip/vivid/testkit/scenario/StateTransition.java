package io.mosip.vivid.testkit.scenario;

import io.mosip.vivid.api.SessionState;

/**
 * One observed session state change.
 *
 * @param scenarioMs scenario time of the frame that caused it (-1 if caused by a tick/health event before any frame)
 * @param wallMs     wall-clock milliseconds since the run started
 */
public record StateTransition(long scenarioMs, long wallMs, SessionState previous, SessionState current) {
    @Override public String toString() { return "@" + scenarioMs + "ms " + previous.phase() + " -> " + current; }
}
