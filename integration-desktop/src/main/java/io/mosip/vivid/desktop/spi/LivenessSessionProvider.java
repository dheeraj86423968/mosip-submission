package io.mosip.vivid.desktop.spi;

import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessSession;
import io.mosip.vivid.api.SessionContext;

/**
 * The only thing {@link io.mosip.vivid.desktop.LivenessGate} needs from liveness-core. In the Registration Client this
 * is implemented by the Spring configuration in src/mosip/java as
 * {@code (ctx, policy) -> new LivenessOrchestrator(engine, policy, ctx)} (or via {@code LivenessSessionFactory}),
 * keeping this module compilable against liveness-api + device-api alone.
 */
@FunctionalInterface
public interface LivenessSessionProvider {
    LivenessSession open(SessionContext context, LivenessPolicy policy);
}
