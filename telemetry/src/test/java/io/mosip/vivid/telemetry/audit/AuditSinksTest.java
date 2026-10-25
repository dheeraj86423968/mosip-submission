package io.mosip.vivid.telemetry.audit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditSinksTest {

    private static AuditRecord rec(LivenessAuditEvent e) {
        return AuditRecord.builder(e).sessionId("s").build();
    }

    @Test
    void inMemorySinkIsBoundedAndQueryable() {
        InMemoryAuditSink sink = new InMemoryAuditSink(3);
        sink.accept(null);
        sink.accept(rec(LivenessAuditEvent.SESSION_STARTED));
        sink.accept(rec(LivenessAuditEvent.PASSIVE_PASSED));
        sink.accept(rec(LivenessAuditEvent.SESSION_PASSED));
        sink.accept(rec(LivenessAuditEvent.SESSION_STARTED));
        assertThat(sink.size()).isEqualTo(3);
        assertThat(sink.dropped()).isEqualTo(1);
        assertThat(sink.events()).containsExactly(LivenessAuditEvent.PASSIVE_PASSED, LivenessAuditEvent.SESSION_PASSED, LivenessAuditEvent.SESSION_STARTED);
        assertThat(sink.find(LivenessAuditEvent.SESSION_STARTED)).hasSize(1);
        assertThat(sink.last(LivenessAuditEvent.SESSION_STARTED)).isPresent();
        assertThat(sink.last(LivenessAuditEvent.PAD_DETECTED)).isEmpty();
        assertThat(sink.records()).hasSize(3);
        sink.clear();
        assertThat(sink.size()).isZero();
        assertThatThrownBy(() -> new InMemoryAuditSink(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new InMemoryAuditSink().size()).isZero();
    }

    @Test
    void compositeIsolatesFailuresAndClosesAll() {
        InMemoryAuditSink ok = new InMemoryAuditSink();
        AtomicInteger closed = new AtomicInteger();
        AuditSink failing = new AuditSink() {
            @Override public void accept(AuditRecord record) { throw new IllegalStateException("disk full"); }
            @Override public void close() { closed.incrementAndGet(); throw new IllegalStateException("close failed"); }
        };
        CompositeAuditSink composite = CompositeAuditSink.of(failing, ok, failing);
        composite.accept(rec(LivenessAuditEvent.SESSION_STARTED));
        assertThat(ok.size()).isEqualTo(1);
        composite.close();
        assertThat(closed.get()).isEqualTo(2);
        assertThat(composite.sinks()).hasSize(3);
        AuditSink.discarding().accept(rec(LivenessAuditEvent.SESSION_STARTED));
        AuditSink.discarding().close();
    }

    @Test
    void loggingSinkRoutesBySeverity() {
        List<String> lines = new ArrayList<>();
        Logger real = LoggerFactory.getLogger("test");
        Logger spy = (Logger) java.lang.reflect.Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[] {Logger.class}, (proxy, method, args) -> {
            if (args != null && args.length == 2 && args[0] instanceof String && (method.getName().equals("info") || method.getName().equals("warn") || method.getName().equals("error"))) {
                lines.add(method.getName() + ":" + args[1]);
                return null;
            }
            if (method.getName().startsWith("is")) {
                return true;
            }
            return method.getReturnType() == void.class ? null : method.invoke(real, args);
        });
        LoggingAuditSink sink = new LoggingAuditSink(spy);
        sink.accept(null);
        sink.accept(rec(LivenessAuditEvent.SESSION_STARTED));
        sink.accept(rec(LivenessAuditEvent.CHALLENGE_TIMEOUT));
        sink.accept(rec(LivenessAuditEvent.PAD_DETECTED));
        sink.accept(rec(LivenessAuditEvent.DEVICE_FAULT));
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).startsWith("info:").contains("SESSION_STARTED");
        assertThat(lines.get(1)).startsWith("warn:");
        assertThat(lines.get(2)).startsWith("error:").contains("PAD_DETECTED");
        assertThat(lines.get(3)).startsWith("error:");
        new LoggingAuditSink().accept(rec(LivenessAuditEvent.SESSION_PASSED));
    }
}
