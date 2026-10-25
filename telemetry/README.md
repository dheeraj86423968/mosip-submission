# telemetry

Audit events, anonymised metrics, diagnostic snapshots and an offline audit queue for MOSIP Vivid
(PLAN.md §10). It depends only on `liveness-api` and `slf4j-api`. JSON is written by a small built-in writer and
parser (`json.Json`), so the module ships unchanged on Android and desktop.

## Audit

* `audit.LivenessAuditEvent`: the 16 events of PLAN.md §10.1, from `SESSION_STARTED` to `MODEL_BUNDLE_REJECTED`.
  Each has a `Severity` (INFO, WARN, ERROR, SECURITY) and a description. They are recorded under the Registration
  Client audit component `LIVENESS`.
* `audit.AuditRecord`: immutable record of event, sessionId, workflow, timestamp, attributes and evidenceHash.
  It serialises to and from one JSON line.
* `audit.AuditRedactor`: applied on every record. It drops attribute keys matching
  `frame|pixel|image|embedding|landmark|biometric` and truncates long values, so biometric data cannot reach an
  audit log even by mistake.
* Sinks implement `audit.AuditSink`. Implementations must not throw.

| Sink | Use |
|------|-----|
| `LoggingAuditSink` | SLF4J logger `io.mosip.vivid.audit.LIVENESS`; level follows severity |
| `InMemoryAuditSink` | Bounded ring for tests and the diagnostic screen |
| `CompositeAuditSink` | Fan-out; a failing sink is logged and skipped |
| `OfflineAuditQueue` | Append-only JSON-lines file, fsynced on each append. `drain(consumer)` replays and truncates on the next sync (at-least-once). Torn or corrupt lines are skipped and counted |

```java
AuditSink audit = CompositeAuditSink.of(new LoggingAuditSink(), new OfflineAuditQueue(dataDir.resolve("liveness-audit.jsonl")));
```

## Metrics

`metrics.LivenessMetrics` is a thread-safe, counters-only aggregator: average passive time, average challenge
completion time, retry rate, failure rate by reason, PAD rate by attack class and device-fault rate. `snapshot()`
returns an immutable `MetricsSnapshot`, and `toJson()` gives the export form for the existing sync. No session,
resident or operator identifiers ever enter it.

## Diagnostics

`diagnostics.DiagnosticSnapshot` captures last per-signal scores, fps, engine and model-bundle versions, policy
version, device health and phase. `diagnostics.DiagnosticSource` is implemented by the orchestrator.
`diagnostics.FpsMeter` is a deterministic sliding-window fps estimator.

## Build

`mvn -q -f telemetry/pom.xml install`
