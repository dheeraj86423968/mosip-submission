# NOTES-core: liveness-core / policy / telemetry

## Real public API (for platform bindings)

Packages are `io.mosip.vivid.core.session` and `io.mosip.vivid.core.bridge`, **not** `io.mosip.vivid.core`.
`integration-android/.../bridge/CoreBindings.kt` currently imports `io.mosip.vivid.core.FrameSourceBridge` and
`io.mosip.vivid.core.LivenessSessionFactory`. Change the imports to:

```kotlin
import io.mosip.vivid.core.session.LivenessSessionFactory
import io.mosip.vivid.core.session.LivenessOrchestrator
import io.mosip.vivid.core.bridge.FrameSourceBridge
```

Signatures (all exist exactly as written):

```java
// io.mosip.vivid.core.session.LivenessSessionFactory
LivenessSessionFactory(LivenessEngine engine)       // defaults policies, discarding audit, private metrics
LivenessSessionFactory(LivenessEngine, Function<Workflow, LivenessPolicy>, AuditSink, LivenessMetrics)
LivenessSessionFactory(LivenessEngine, Function<Workflow, LivenessPolicy>, AuditSink, LivenessMetrics, TimeSource, SecureRandom, ActionThresholds)
LivenessOrchestrator open(LivenessPolicy policy, SessionContext ctx)   // policy.workflow() must equal ctx.workflow()
LivenessOrchestrator open(SessionContext ctx)
LivenessOrchestrator open(Workflow, Locale, boolean online, boolean assistedCapture)
SessionContext newContext(Workflow, Locale, boolean online, boolean assistedCapture)   // UUID id + 16-byte SecureRandom nonce

// io.mosip.vivid.core.bridge.FrameSourceBridge  (AutoCloseable)
FrameSourceBridge(FaceFrameSource source, LivenessOrchestrator session)
FrameSourceBridge(FaceFrameSource, LivenessOrchestrator, StreamOptions)
FrameSourceBridge(FaceFrameSource, LivenessOrchestrator, StreamOptions, ScheduledExecutorService /* nullable */)
void start(StreamOptions options); void start(); void stop(); void close();
```

**Difference from the Kotlin assumption.** The bridge takes a `LivenessOrchestrator`, not the
`LivenessSession` interface. It needs orchestrator-only device controls (`start()`, `deviceReady()`,
`deviceReconnecting()`, `deviceFault()`) that the frozen `LivenessSession` interface does not expose.
`factory.open(...)` returns `LivenessOrchestrator`, so keep the variable typed as `LivenessOrchestrator` (or
`val session = factory.open(policy, ctx)` with type inference) rather than `LivenessSession`.

## Frozen-contract observations (no blocker; worked around)

1. `LivenessSession` has no `start()` and no device-lifecycle callbacks. The orchestrator adds `start()`,
   `deviceReady()`, `deviceReconnecting(String)` and `deviceFault(FailureReason, String)` as public methods.
   `feed()` auto-starts from IDLE, so generic callers still work.
2. `LivenessSession.currentChallenge()` is in PLAN.md §2.2 but not in the frozen interface. The orchestrator provides
   it as a public method; `SessionState.challenge()` carries the same information.
3. `LivenessResult` has no field for "passed because the policy is disabled". The orchestrator emits decision
   `LIVE` with `failure = POLICY_DISABLED` and audits `SESSION_PASSED(policyDisabled=true)`. Callers that must
   distinguish a bypass from a verified pass should check `result.failure()`.
4. `LivenessPolicy` has no `toBuilder()`. The policy module copies every field by hand (`PolicyLoader.copy`). If a
   field is added to `LivenessPolicy`, update that method.
5. `SessionState` exposes no per-action progress. The orchestrator folds detector progress into `progress()` and
   also exposes `actionProgress()`.

## Behaviour worth knowing

* Identity continuity (S7) is veto-only (3 consecutive frames over the threshold). It is not fused, because a
  matching identity is not liveness evidence.
* Challenge timing (S6) is a hard veto only when the onset comes before the prompt or under `minActionLatencyMillis / 2`.
  Otherwise its score is pinned into fusion. Setting the policy weight of `challenge_timing` to 0 disables the veto.
* `tick()` must be called periodically (the bridge does this every 200 ms). Without it, challenge, session and
  device-connect timeouts only fire when frames arrive.
