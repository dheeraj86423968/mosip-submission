package io.mosip.vivid.core.session;

import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.telemetry.audit.AuditSink;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Builds {@link LivenessOrchestrator}s for the Registration Client: one factory per process, one session per
 * capture. Generates the session id (UUID) and the 16-byte challenge nonce from {@link SecureRandom}, looks up
 * the policy for the workflow at session start (policy changes therefore apply to the next session, never
 * mid-session), and shares the audit sink and metrics across sessions.
 *
 * <p>Thread-safe.</p>
 */
public final class LivenessSessionFactory {

    /** Length of the per-session nonce in bytes. */
    public static final int NONCE_BYTES = 16;

    private final LivenessEngine engine;
    private final Function<Workflow, LivenessPolicy> policies;
    private final AuditSink audit;
    private final LivenessMetrics metrics;
    private final TimeSource time;
    private final SecureRandom random;
    private final ActionThresholds thresholds;

    /**
     * Minimal factory: policies are the compiled-in {@link LivenessPolicy#defaults(Workflow)}, audit is discarded and
     * metrics are private. Intended for embedding and tests; production wires a real policy provider and audit sink.
     */
    public LivenessSessionFactory(LivenessEngine engine) {
        this(engine, LivenessPolicy::defaults, AuditSink.discarding(), new LivenessMetrics());
    }

    /** Factory with system time, a fresh {@link SecureRandom} and default action thresholds. */
    public LivenessSessionFactory(LivenessEngine engine, Function<Workflow, LivenessPolicy> policies, AuditSink audit, LivenessMetrics metrics) {
        this(engine, policies, audit, metrics, TimeSource.system(), new SecureRandom(), ActionThresholds.defaults());
    }

    /** Fully explicit factory. */
    public LivenessSessionFactory(LivenessEngine engine, Function<Workflow, LivenessPolicy> policies, AuditSink audit,
                                  LivenessMetrics metrics, TimeSource time, SecureRandom random, ActionThresholds thresholds) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.time = Objects.requireNonNull(time, "time");
        this.random = Objects.requireNonNull(random, "random");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    /** Creates a context with a fresh UUID session id and random nonce. */
    public SessionContext newContext(Workflow workflow, Locale locale, boolean online, boolean assistedCapture) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return new SessionContext(workflow, UUID.randomUUID().toString(), nonce, locale, online, assistedCapture);
    }

    /** Opens a session for a workflow; the orchestrator is in {@code IDLE} until {@link LivenessOrchestrator#start()}. */
    public LivenessOrchestrator open(Workflow workflow, Locale locale, boolean online, boolean assistedCapture) {
        return open(newContext(workflow, locale, online, assistedCapture));
    }

    /** Opens a session for a caller-supplied context (for example when the client already allocated the id). */
    public LivenessOrchestrator open(SessionContext context) {
        Objects.requireNonNull(context, "context");
        return open(Objects.requireNonNull(policies.apply(context.workflow()), "no policy for " + context.workflow()), context);
    }

    /**
     * Opens a session with an explicit policy, bypassing the provider (used by platform bindings that resolve the
     * policy themselves). The policy's workflow must match the context's.
     */
    public LivenessOrchestrator open(LivenessPolicy policy, SessionContext context) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        return LivenessOrchestrator.builder(engine.newAnalyzer(), policy, context)
                .timeSource(time).random(random).auditSink(audit).metrics(metrics)
                .engineInfo(engine.info()).actionThresholds(thresholds).build();
    }

    /** The engine sessions are opened on. */
    public LivenessEngine engine() {
        return engine;
    }

    /** Shared metrics aggregator. */
    public LivenessMetrics metrics() {
        return metrics;
    }
}
