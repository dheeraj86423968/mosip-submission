package io.mosip.vivid.desktop.core;

import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.core.identity.IdentityContinuity;
import io.mosip.vivid.core.session.LivenessSessionFactory;
import io.mosip.vivid.desktop.spi.LivenessSessionProvider;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Binds the desktop integration to liveness-core. This is the only class in integration-desktop that references
 * core types, and it is compiled and tested here, so the MOSIP-side Spring configuration only has to call it.
 */
public final class CoreLivenessWiring {
    private CoreLivenessWiring() {}

    /** Session provider backed by the core orchestrator. Each call opens a fresh session for the given policy. */
    public static LivenessSessionProvider sessionProvider(LivenessEngine engine) {
        return sessionProvider(new LivenessSessionFactory(Objects.requireNonNull(engine, "engine")));
    }

    /** Session provider over a fully configured factory (audit sink, metrics, time source, thresholds). */
    public static LivenessSessionProvider sessionProvider(LivenessSessionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return (ctx, policy) -> factory.open(policy, ctx);
    }

    /** Cosine distance used by {@link io.mosip.vivid.desktop.CaptureBinding} to bind the signed capture to the session anchor. */
    public static BiFunction<float[], float[], Double> identityDistance() {
        return IdentityContinuity::compare;
    }
}
