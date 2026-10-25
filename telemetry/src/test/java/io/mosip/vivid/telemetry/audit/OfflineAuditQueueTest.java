package io.mosip.vivid.telemetry.audit;

import io.mosip.vivid.api.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfflineAuditQueueTest {

    @Test
    void appendsDrainsAndTruncates(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sub/audit.jsonl");
        OfflineAuditQueue q = new OfflineAuditQueue(f);
        assertThat(q.file()).isEqualTo(f);
        assertThat(q.pending()).isZero();
        assertThat(q.drain(r -> { })).isZero();
        q.accept(null);
        AuditRecord a = AuditRecord.builder(LivenessAuditEvent.SESSION_STARTED).sessionId("s1").workflow(Workflow.RESIDENT).attribute("attempt", 1).build();
        AuditRecord b = AuditRecord.builder(LivenessAuditEvent.SESSION_PASSED).sessionId("s1").evidenceHash("h").build();
        q.accept(a);
        q.accept(b);
        assertThat(Files.readAllLines(f)).hasSize(2);
        assertThat(q.pending()).isEqualTo(2);
        List<AuditRecord> got = new ArrayList<>();
        assertThat(q.drain(got::add)).isEqualTo(2);
        assertThat(got).containsExactly(a, b);
        assertThat(Files.size(f)).isZero();
        assertThat(q.pending()).isZero();
        assertThat(q.drain(got::add)).isZero();
    }

    @Test
    void toleratesCorruptLines(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("audit.jsonl");
        OfflineAuditQueue q = new OfflineAuditQueue(f);
        AuditRecord a = AuditRecord.builder(LivenessAuditEvent.SESSION_STARTED).sessionId("s1").build();
        q.accept(a);
        Files.writeString(f, "{\"event\":\"SESSION_PASSED\",\"timest", StandardOpenOption.APPEND);
        Files.writeString(f, "\n\n{not json}\n", StandardOpenOption.APPEND);
        q.accept(a);
        assertThat(q.pending()).isEqualTo(2);
        List<AuditRecord> got = new ArrayList<>();
        assertThat(q.drain(got::add)).isEqualTo(2);
        assertThat(q.corruptLinesSkipped()).isEqualTo(2);
        assertThat(got).containsExactly(a, a);
    }

    @Test
    void failingConsumerKeepsQueueForRedelivery(@TempDir Path dir) {
        Path f = dir.resolve("audit.jsonl");
        OfflineAuditQueue q = new OfflineAuditQueue(f);
        q.accept(AuditRecord.builder(LivenessAuditEvent.SESSION_STARTED).build());
        q.accept(AuditRecord.builder(LivenessAuditEvent.SESSION_FAILED).build());
        assertThatThrownBy(() -> q.drain(r -> { throw new IllegalStateException("sync down"); })).isInstanceOf(IllegalStateException.class);
        assertThat(q.pending()).isEqualTo(2);
        assertThat(q.drain(r -> { })).isEqualTo(2);
    }

    @Test
    void appendFailureIsLoggedNotThrown(@TempDir Path dir) throws Exception {
        Path asDir = dir.resolve("iamadir");
        Files.createDirectory(asDir);
        OfflineAuditQueue q = new OfflineAuditQueue(asDir);
        q.accept(AuditRecord.builder(LivenessAuditEvent.SESSION_STARTED).build());
        assertThatThrownBy(q::pending).isInstanceOf(java.io.UncheckedIOException.class);
        assertThatThrownBy(() -> q.drain(r -> { })).isInstanceOf(java.io.UncheckedIOException.class);
    }
}
