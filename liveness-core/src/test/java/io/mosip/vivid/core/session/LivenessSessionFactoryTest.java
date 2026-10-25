package io.mosip.vivid.core.session;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.core.testing.FakeFrameAnalyzer;
import io.mosip.vivid.core.testing.FakeTime;
import io.mosip.vivid.core.testing.Scenarios;
import io.mosip.vivid.telemetry.audit.InMemoryAuditSink;
import io.mosip.vivid.telemetry.audit.LivenessAuditEvent;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LivenessSessionFactoryTest {

    static final class FakeEngine implements LivenessEngine {
        final FakeFrameAnalyzer analyzer = new FakeFrameAnalyzer();
        int analyzersCreated;

        @Override
        public EngineInfo info() {
            return new EngineInfo("fake", "1", "b", "7", EnumSet.allOf(ChallengeType.class), Set.of(), true);
        }

        @Override
        public FrameAnalyzer newAnalyzer() {
            analyzersCreated++;
            return analyzer;
        }
    }

    @Test
    void opensSessionsWithFreshIdsNoncesAndWorkflowPolicy() {
        FakeEngine engine = new FakeEngine();
        InMemoryAuditSink audit = new InMemoryAuditSink();
        LivenessMetrics metrics = new LivenessMetrics();
        FakeTime time = new FakeTime();
        LivenessSessionFactory factory = new LivenessSessionFactory(engine, wf -> Scenarios.policy(wf).build(), audit, metrics,
                time, new SecureRandom(), ActionThresholds.defaults());
        Set<String> ids = new HashSet<>();
        Set<String> nonces = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            SessionContext ctx = factory.newContext(Workflow.OPERATOR, Locale.FRENCH, false, false);
            assertThat(ctx.nonce()).hasSize(LivenessSessionFactory.NONCE_BYTES);
            ids.add(ctx.sessionId());
            nonces.add(EvidenceHash.toHex(ctx.nonce()));
        }
        assertThat(ids).hasSize(50);
        assertThat(nonces).hasSize(50);

        LivenessOrchestrator s = factory.open(Workflow.SUPERVISOR, Locale.ENGLISH, true, true);
        assertThat(s.context().workflow()).isEqualTo(Workflow.SUPERVISOR);
        assertThat(s.context().assistedCapture()).isTrue();
        assertThat(s.policy().policyVersion()).isEqualTo("test-1");
        assertThat(s.start().phase()).isEqualTo(SessionState.Phase.DEVICE_CONNECTING);
        assertThat(audit.events()).containsExactly(LivenessAuditEvent.SESSION_STARTED);
        assertThat(audit.records().get(0).attributes()).containsEntry("engine", "fake");
        assertThat(metrics.snapshot().sessionsStarted()).isEqualTo(1);
        assertThat(engine.analyzersCreated).isEqualTo(1);
        assertThat(factory.engine()).isSameAs(engine);
        assertThat(factory.metrics()).isSameAs(metrics);
        assertThat(s.captureDiagnostics().modelBundleVersion()).isEqualTo("7");
    }

    @Test
    void convenienceConstructorAndExplicitPolicy() {
        FakeEngine engine = new FakeEngine();
        LivenessSessionFactory factory = new LivenessSessionFactory(engine);
        SessionContext ctx = factory.newContext(Workflow.RESIDENT, null, true, false);
        assertThat(factory.open(ctx).policy().policyVersion()).isEqualTo("builtin-1.0");
        LivenessPolicy custom = LivenessPolicy.builder(Workflow.RESIDENT).policyVersion("custom").build();
        assertThat(factory.open(custom, ctx).policy().policyVersion()).isEqualTo("custom");
        assertThatThrownBy(() -> factory.open(LivenessPolicy.defaults(Workflow.OPERATOR), ctx)).isInstanceOf(IllegalArgumentException.class);
        LivenessSessionFactory noPolicy = new LivenessSessionFactory(engine, wf -> null, io.mosip.vivid.telemetry.audit.AuditSink.discarding(), new LivenessMetrics());
        assertThatThrownBy(() -> noPolicy.open(ctx)).isInstanceOf(NullPointerException.class).hasMessageContaining("RESIDENT");
        assertThatThrownBy(() -> new LivenessSessionFactory(null)).isInstanceOf(NullPointerException.class);
    }
}
