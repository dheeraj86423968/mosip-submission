# liveness-core

The platform-neutral heart of MOSIP Vivid: the session state machine plus everything it needs to decide. That
covers score fusion, challenge selection, action verification, challenge timing (S6), identity continuity (S7), the
quality gate (S9) and the bridge from a device frame source. Engines only score frames. This module turns those
scores into a decision under policy, identically for every engine and platform.

Dependencies: `liveness-api`, `device-api`, `policy`, `telemetry`, `slf4j-api`.

## Packages

| Package | Contents |
|---------|----------|
| `core.session` | `LivenessOrchestrator` (implements `LivenessSession`), `LivenessSessionFactory`, `QualityGate`, `EvidenceHash`, `TimeSource` |
| `core.fusion` | `ScoreFusion` (weighted log-odds, EMA, hard veto) and `FusionResult` |
| `core.challenge` | `ChallengeSelector` (random draw without replacement, depth and accessibility rules) and `ChallengeTiming` (S6) |
| `core.action` | `ActionDetector`, `ActionDetectors` factory, `ActionThresholds`, and detectors for blink, sustained expression, head motion and hold-still |
| `core.identity` | `IdentityContinuity` (S7 anchor, cosine distance, 3-frame veto, `compare(a, b)`) |
| `core.bridge` | `FrameSourceBridge` (`FaceFrameSource` to session, health mapping, 200 ms timeout tick, stop on terminal) |

The state machine, every transition, its guard, audit event and UI hint are documented in
[`docs/core-state-machine.md`](docs/core-state-machine.md).

## Usage

```java
LivenessSessionFactory factory = new LivenessSessionFactory(engine, workflow -> loaded.effective(workflow), audit, metrics);
LivenessOrchestrator session = factory.open(Workflow.RESIDENT, Locale.ENGLISH, online, assistedCapture);
session.addListener((prev, cur) -> ui.render(cur));            // phase, hint, challenge, progress
try (FrameSourceBridge bridge = new FrameSourceBridge(frameSource, session)) {
    bridge.start(StreamOptions.defaults());
    // ... wait for a terminal state or FAILED; on FAILED offer session.retry()
}
LivenessResult result = session.result();                      // decision, scores, challenge log, evidenceHash, anchor
boolean sameSubject = IdentityContinuity.compare(result.anchorEmbedding().orElseThrow(), captureEmbedding) < threshold;
```

## Design notes

* **Deterministic time.** All timing uses an injected `TimeSource` (`Clock` and `LongSupplier` adapters provided).
  Frame timestamps from the device are never trusted, because a replayed stream controls them.
* **Unpredictable challenges.** The orchestrator uses `SecureRandom` in production. The sequence and the session
  nonce are audited. Uniformity is checked by chi-square tests over 20,000 draws at p = 0.001.
* **Anti-replay.** Every detector needs a neutral observation after the prompt before an action can start. S6 vetoes
  an onset earlier than half the human minimum latency.
* **Defence in depth during challenges.** Passive fusion, PAD vetoes and identity continuity keep running. S7 is
  veto-only, because a matching identity is not evidence of liveness.
* **No biometrics leave the session.** Results and audit records carry scores, timings and hashes. The anchor
  embedding is returned to the caller in memory only, to bind the signed capture.

## Tests

`mvn -q -f liveness-core/pom.xml install` runs unit tests for every class. It also runs scenario tests driven by
`FakeFrameAnalyzer` and `Scenarios` (under `src/test`): live pass, low confidence passing via challenges, photo and
screen attacks, replay caught by timing, identity swap, timeout then retry then lock, multiple faces, no face,
policy disabled and assisted capture. JaCoCo reports go to `target/site/jacoco/`.
