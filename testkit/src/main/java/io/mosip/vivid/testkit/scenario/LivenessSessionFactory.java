package io.mosip.vivid.testkit.scenario;

import io.mosip.vivid.api.LivenessSession;
import io.mosip.vivid.api.SessionContext;

/**
 * Opens a {@link LivenessSession} for a context. Keeps the testkit independent of any orchestrator or engine:
 * liveness-core users pass {@code ctx -> engine.open(policy, ctx)}; tests pass a stub.
 */
@FunctionalInterface
public interface LivenessSessionFactory {
    LivenessSession open(SessionContext context);
}
