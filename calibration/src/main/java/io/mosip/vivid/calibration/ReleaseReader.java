package io.mosip.vivid.calibration;

import io.mosip.vivid.telemetry.audit.AuditRecord;
import io.mosip.vivid.telemetry.audit.LivenessAuditEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads calibration releases out of synced audit records (JSON lines, one {@link AuditRecord#toJson()} per line, as
 * exported from the MOSIP audit store or drained from an {@code OfflineAuditQueue}). Non-calibration events are
 * ignored, malformed lines are counted, and duplicates of the same (client, release, workflow) are dropped: the sync
 * is at-least-once, and counting a release twice would also count its noise twice.
 */
public final class ReleaseReader {

    private final Map<String, ClientRelease> releases = new LinkedHashMap<>();
    private int malformed;
    private int duplicates;
    private int budgetExhausted;

    public ReleaseReader read(Path jsonl) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    accept(line);
                }
            }
        }
        return this;
    }

    public ReleaseReader accept(String jsonLine) {
        AuditRecord rec;
        try {
            rec = AuditRecord.fromJson(jsonLine);
        } catch (RuntimeException e) {
            malformed++;
            return this;
        }
        return accept(rec);
    }

    public ReleaseReader accept(AuditRecord rec) {
        if (rec.event() == LivenessAuditEvent.CALIBRATION_BUDGET_EXHAUSTED) {
            budgetExhausted++;
            return this;
        }
        if (rec.event() != LivenessAuditEvent.CALIBRATION_RELEASED) {
            return this;
        }
        try {
            ClientRelease c = ClientRelease.fromAudit(rec);
            if (releases.putIfAbsent(c.key(), c) != null) {
                duplicates++;
            }
        } catch (RuntimeException e) {
            malformed++;
        }
        return this;
    }

    public List<ClientRelease> releases() {
        return new ArrayList<>(releases.values());
    }

    public int malformed() { return malformed; }
    public int duplicates() { return duplicates; }
    public int budgetExhausted() { return budgetExhausted; }
}
