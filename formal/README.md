# Formal model of the liveness session orchestrator

This directory holds a TLA+ specification of the session state machine that
`liveness-core` implements and that every Registration Client renders. The
model exists to machine-check one sentence from PLAN.md section 17.7:

> no execution reaches `PASSED` unless the active policy's liveness, PAD,
> identity-continuity and challenge-count predicates all hold.

It is checked with TLC on every CI run (`.github/workflows/ci.yml`, job
`formal`), so a change to the orchestrator design that breaks the property is
caught before it reaches a client.

| File | Purpose |
|---|---|
| `LivenessOrchestrator.tla` | The specification: orchestrator transitions plus a nondeterministic environment. |
| `LivenessOrchestrator.cfg` | Default TLC configuration (operator-shaped policy: `maxRetries=2`, `minChallenges=1`, `maxChallenges=2`). |
| `LivenessOrchestrator-supervisor.cfg` | Second configuration (`minChallenges=2`, `maxChallenges=3`, stricter threshold, longer passive window). |
| `tlc-output-default.txt`, `tlc-output-supervisor.txt` | Verbatim TLC output of the last local run of each configuration. |
| `conformance.sh` | Model-based trace conformance: TLC state-graph dumps, regenerated tables, replay against the real `LivenessOrchestrator` (see "Checking the Java against the spec"). |
| `tools/gen_spec_tables.py` | Generates `liveness-core/src/test/java/io/mosip/vivid/core/session/GeneratedSpecTables.java` from the spec and the TLC dumps. |

## What is modelled

The spec mirrors the frozen `liveness-api` contract, not an idealised machine:

* **Phases** are exactly `SessionState.Phase`: `IDLE`, `DEVICE_CONNECTING`,
  `FACE_ACQUIRING`, `PASSIVE_EVALUATING`, `ACTIVE_CHALLENGE`, `PASSED`,
  `FAILED`, `LOCKED`, `CANCELLED`. Terminal phases are those for which
  `SessionState.isTerminal()` is true.
* **Failure reasons** are exactly `FailureReason`, including the thirteen
  error cases from the problem statement and the security vetoes
  (`PAD_DETECTED`, `IDENTITY_DRIFT`).
* **Policy** is reduced to the parameters that affect control flow:
  `activeMode` (`NEVER` / `ON_LOW_CONFIDENCE` / `ALWAYS`, chosen
  nondeterministically at `Init` so one run covers all three),
  `passiveThreshold`, `minChallenges`, `maxChallenges`, `maxRetries`,
  `passiveMinFrames`, the S6 timing window and the challenge timeout.
  Scores are integers `0..ScoreMax`; only their order relative to the
  threshold matters.
* **Attempts**: `attempt` counts attempts started, so `attempt =
  SessionState.attempt()` and `maxAttempts = MaxRetries`; `retry()` is enabled
  exactly when `SessionState.canRetry()` is true.
* **One step = one `feed(frame)` call.** In `PASSIVE_EVALUATING` and
  `ACTIVE_CHALLENGE` every step re-samples the fused score and may raise a PAD
  hard veto or an identity-drift flag. The orchestrator evaluates the flags
  *before* anything else in the same step (identity drift first, then the PAD
  veto, exactly as `LivenessOrchestrator.handle` does), which is the hard-veto
  rule of PLAN.md 4.3 and the "re-evaluate liveness during active
  verification" mandatory bullet.
* **Challenge sequence**: on entering `ACTIVE_CHALLENGE` a sequence length in
  `MinChallenges..MaxChallenges` is drawn (the `SecureRandom` draw). Each
  analysed challenge frame reports `NONE`, `PASSED` or `FAILED`; `NONE`
  advances a per-challenge tick counter, and at most `TimeoutTicks - 1` of
  them fit before the deadline. The deadline itself is a separate clock
  action, `ChallengeTimeout`, because the orchestrator checks timers before it
  analyses a frame. A `PASSED` outcome that arrives before `MinLatencyTicks`
  is the S6 timing veto and fails the attempt as `PAD_DETECTED` (a
  pre-recorded answer). After a verified challenge the next drawn challenge is
  issued; when the whole drawn sequence (at least `MinChallenges` long) is
  verified the session is `PASSED` only if the re-evaluated fused score of
  that frame is at or above the threshold, otherwise the attempt fails with
  `LIVENESS_BELOW_THRESHOLD`.
* **Environment faults**: device failure while connecting or at any later
  point of a live attempt, face acquisition failure (the attempt timer expiring
  in `FACE_ACQUIRING`, reported with the last quality-gate reason: no face /
  multiple faces / poor quality), engine errors and invalid frames on any
  analysed frame, the attempt timer in the evaluating phases, and `cancel()`
  from any non-terminal phase. `retry()` after a device failure re-enters
  `DEVICE_CONNECTING`.
* **Lock-out**: `FAILED` with `attempt = MaxRetries` steps to `LOCKED`, keeping
  the last attempt's reason (the lock is conveyed by the phase, the
  `MAX_RETRIES_REACHED` hint and the audit event of the same name). What the client does next
  (`LivenessPolicy.OnMaxRetries`) is outside the session and therefore outside
  the model; every one of those paths starts a *new* session, which must pass
  on its own.

Not modelled: timing values (only their ordering), the content of challenges,
the accessibility pool (it only changes which challenges are drawn, not the
control flow), the `enabled = false` policy (the client integration skips
session creation entirely; see `docs/design/07-configuration.md`), and
concurrency (the API requires a single feeding thread).

## Properties checked

| Id | Kind | Statement (informal) |
|---|---|---|
| `PassedImpliesPolicy` | invariant | `phase = PASSED` implies `fusedScore >= Threshold`, no PAD veto, no identity drift, `challengesPassed >= RequiredChallenges`, and in `ALWAYS` mode `challengesPassed >= MinChallenges`. `RequiredChallenges` is `MinChallenges` whenever the policy is `ALWAYS` or the passive window fell below threshold, else 0. |
| `NoPassAfterVeto` | action property | `[][padVeto => phase' # "PASSED"]_vars`: once a PAD veto is raised in an attempt, no step of that attempt reaches `PASSED`. The flag is cleared only by `retry()`/`start()`. |
| `AttemptsBounded` | invariant | `attempt <= MaxRetries`, and `phase = LOCKED` implies `attempt = MaxRetries`. |
| `TerminalIsFinal` | action property | `[][(phase \in TerminalPhases) => vars' = vars]_vars`: `PASSED`, `LOCKED`, `CANCELLED` have no outgoing transitions. |
| `EventuallyTerminal` | liveness | `<>(phase \in TerminalPhases)` under weak fairness of environment, orchestrator and client steps. |
| `TypeOK`, `FailedHasReason`, `LockedHasReason`, `ActiveNeedsPolicy`, `ChallengeSequenceBounded`, `AttemptStartedBeforeWork` | invariants | Structural sanity: every `FAILED` and `LOCKED` carries a concrete reason, active challenges only when the policy allows them, `1 <= challengeIdx <= seqLen <= MaxChallenges` and `challengesPassed = challengeIdx - 1` while active, the challenge tick counter never reaches the timeout without failing. |

## Results

Both configurations of the current spec (revised on 24 Oct 2026 after the
trace-conformance work below) were checked locally with the `tla2tools.jar`
that CI downloads from the tlaplus `v1.8.0` release URL (it reports itself as
TLC2 version 2026.10.24, rev 2ef2af8) on OpenJDK 25.0.4, four workers.

| Configuration | States generated | Distinct states | Graph depth | Wall time | Result |
|---|---|---|---|---|---|
| `LivenessOrchestrator.cfg` (maxRetries 2, min 1, max 2, window 2 frames) | 26,061 | 6,955 | 15 | about 1 s | **No error found**; all 8 invariants and 3 temporal properties hold |
| `LivenessOrchestrator-supervisor.cfg` (maxRetries 2, min 2, max 3, window 3 frames) | 44,783 | 11,463 | 18 | about 1 s | **No error found** |

The first version of the spec (27,678 / 5,032 and 48,971 / 8,187 states) was
also error-free; the revision changes what the spec says, not whether it
verifies. The headline mutation for the revision, "pass after the last
challenge regardless of the re-evaluated score", is still caught by
`PassedImpliesPolicy` (8-state trace through `ALWAYS`, passive pass, one
challenge answered with a collapsed score).

Full output is in `tlc-output-default.txt` and `tlc-output-supervisor.txt`.

## What the model checker taught us

Writing the spec was not a formality; TLC rejected three versions before the
one in this directory.

1. **`cancel()` from `IDLE`.** A first structural invariant said "any phase
   other than `IDLE` has `attempt >= 1`". TLC produced a two-state trace:
   `IDLE --Cancel--> CANCELLED` with `attempt = 0`. The API allows cancelling
   a session that was opened but never started, so the invariant was wrong,
   not the machine. The orchestrator and the UI must accept a `CANCELLED`
   result with `attemptsUsed = 0`; the audit event `SESSION_FAILED(CANCELLED)`
   is still emitted so a session that was opened and abandoned leaves a trace.
2. **Primed variables in branch conditions.** Two drafts read `padVeto'`
   inside an `IF`. The TLC build we used refuses to evaluate that, which is a
   good discipline anyway: the spec now binds the new flag values with `LET`
   and branches on those, so the order "flags first, then score, then action
   outcome" is explicit in the text.
3. **Function-constructor ambiguity.** `[][phase \in T => ...]_vars` is parsed
   by SANY as the start of a function `[x \in S |-> e]`; the fix is to
   parenthesise the antecedent. Trivial, but it is the kind of thing that
   makes a CI job fail at 2 a.m., so it is recorded here.

To check that the properties are not vacuous we also ran seven deliberate
mutations of the spec (kept out of the repository; the commands are
reproducible from the descriptions below). Every mutation was caught:

| Mutation | Models the bug | Caught by | Trace length |
|---|---|---|---|
| Ignore the PAD veto flag during `ACTIVE_CHALLENGE` | engine raises a hard veto on the same frame the action completes, orchestrator passes anyway | `PassedImpliesPolicy` | 9 states |
| Remove the `attempt < MaxRetries` guard on `retry()` | retry allowed past the policy limit | `TypeOK` (then `AttemptsBounded`) | 6 states |
| Treat `ALWAYS` like `ON_LOW_CONFIDENCE` | operator/supervisor pass on a high passive score with zero challenges | `PassedImpliesPolicy` | 7 states |
| Drop the fairness conjunct | session can stall forever in `FAILED` or mid-window | `EventuallyTerminal` | 7 states (lasso) |
| Ignore `MinChallenges` after a verified challenge | one blink suffices for a supervisor | `PassedImpliesPolicy` under the supervisor configuration (equivalent to the original when `MinChallenges = 1`, which is why the second configuration exists) | 10 states |
| Pass after the last challenge regardless of the re-evaluated score | challenge success overrides a collapsing passive score | `PassedImpliesPolicy` | 9 states |
| Allow `retry()` from `PASSED` | a terminal session reopened | `TerminalIsFinal` | 8 states |

The first of these is the trace reproduced below, because it is precisely the
class of bug the "re-evaluate liveness during active verification" requirement
exists to prevent. With the veto check removed from the active-challenge step,
TLC finds in under a second:

```
IDLE -> DEVICE_CONNECTING -> FACE_ACQUIRING -> PASSIVE_EVALUATING (2 frames, score 0)
     -> ACTIVE_CHALLENGE (passiveBelow, seqLen 1) -> tick
     -> ChallengeFrame{outcome=PASSED, score=3, veto=TRUE}  ==>  phase = PASSED, padVeto = TRUE
```

## Running it yourself

```sh
curl -fsSL -o tla2tools.jar https://github.com/tlaplus/tlaplus/releases/latest/download/tla2tools.jar
cd formal
java -XX:+UseParallelGC -cp ../tla2tools.jar tlc2.TLC -workers auto -config LivenessOrchestrator.cfg LivenessOrchestrator.tla
java -XX:+UseParallelGC -cp ../tla2tools.jar tlc2.TLC -workers auto -config LivenessOrchestrator-supervisor.cfg LivenessOrchestrator.tla
```

Any JDK 11 or newer works. Both runs finish in a few seconds on a laptop.

## Relationship to the Java implementation

The spec is the design authority for `liveness-core`'s orchestrator. The
mapping is one-to-one and is used by the test plan
(`docs/testing/test-plan.md`, cases `FV-*`):

| TLA+ action | `liveness-core` behaviour |
|---|---|
| `Start` | `LivenessSession` created and started by the client integration; `attempt = 1`. |
| `DeviceConnected` / `DeviceFailed` | `deviceReady()` / `deviceFault()` from `FrameSink.onHealth` transitions of the `FaceFrameSource` (`DeviceException.Kind` maps to `FailureReason.DEVICE_*`), and the connection timer. |
| `FaceAcquired` / `AcquireFailed` | S9 quality gate over `FaceQuality`; hints `MOVE_CLOSER`, `CENTER_FACE`, `ONLY_ONE_FACE`, `IMPROVE_LIGHTING`. |
| `PassiveFrame` | `feed()` in `PASSIVE_EVALUATING`: fusion update, hard-veto check, S7 drift check. |
| `PassiveDecide` | end of the passive window (`passiveMinFrames` and `passiveWindowMillis` both satisfied). |
| `StartActive` | `SecureRandom` draw of the challenge sequence, `CHALLENGE_ISSUED` audit event with the session nonce. |
| `ChallengeFrame` | `feed()` in `ACTIVE_CHALLENGE`: action detector, S6 timing window, fusion update, veto and drift checks. |
| `ChallengeTimeout` | the challenge deadline, checked by `tick()` and at the start of `feed()` before the frame is analysed. |
| `EngineFault` / `SessionTimeout` | analyzer exceptions or `null` analyses, and the `tick()`-driven attempt timer. |
| `Retry` / `Cancel` / `Lock` | `retry()`, `cancel()`, and the automatic step to `LOCKED` when `canRetry()` is false. |

## Checking the Java against the spec

Generating the whole orchestrator from TLA+ is not practical: the Java class
also owns fusion, the quality gate, identity continuity, audit, metrics,
evidence hashing and diagnostics, none of which belong in a control-flow
model. PLAN.md 17.7 is closed instead with **model-based trace
conformance** plus a small generated artifact.

`formal/conformance.sh` (run in CI's `formal` job after the two TLC checks):

1. TLC model-checks both configurations again with
   `-dump dot,actionlabels`, writing the full state graph of each.
2. `tools/gen_spec_tables.py` regenerates
   `liveness-core/src/test/java/io/mosip/vivid/core/session/GeneratedSpecTables.java`:
   the spec's phase enum, failure reasons, the 14 actions with the actor that
   takes them, and the 31 phase-level transitions (`FROM -Action-> TO`) TLC
   explored. CI fails if the regenerated file differs from the committed one.
3. `SpecConformanceTest` (liveness-core tests) then
   * checks that `SpecModel`, a Java transliteration of `Next` with every
     existential witness explicit, reaches **exactly** TLC's states and edges
     (6,955 states / 16,652 edges and 11,463 / 28,444); the transliteration
     is therefore not a second, unchecked model;
   * replays random walks of that relation against the real
     `LivenessOrchestrator` with a fake clock, a scripted engine
     (`FakeFrameAnalyzer`) and scripted action detectors (a test seam,
     `Builder.actionDetectorFactory`). Every spec action is mapped to concrete
     inputs: `PassiveFrame(s, v, d)` and `ChallengeFrame(o, s, v, d)` become
     frames whose signal makes the fused score equal the concrete value of `s`
     (the pinned S6 timing signal is solved for), a hard-veto signal for `v`,
     and an embedding that completes a run of three drifting frames for `d`;
     `o` becomes the detector status and an onset inside or below the human
     latency window; timeouts become clock jumps followed by `tick()` or
     `feed()`; device failures become `deviceFault()` or the connection
     timer; engine faults become an analyzer that throws or returns null;
   * after every step asserts that the orchestrator's observable state
     (phase, failure reason, attempt counter, fused score, passive frame
     count, drawn sequence length, current challenge index, verified
     challenges, result decision) equals the spec state reached by the action
     followed by the orchestrator's internal steps (`PassiveDecide`, `Lock`),
     which the Java code takes in the same call. The `SecureRandom` length draw
     is resolved by matching the Java choice against the spec's options;
   * between steps feeds stuttering inputs (gated frames, ticks, refused
     retries, a second `start()`, device blips, input after a terminal phase)
     that must not change the state, and checks `PassedImpliesPolicy` directly
     on every `LivenessResult` that passed;
   * asserts every walked edge is an edge of TLC's graph and every observed
     phase transition is in `GeneratedSpecTables`, and that the Java phase and
     reason enums match the generated ones.

Results of the last local run (`formal/conformance.sh --check-generated
10000`, seed 20261024, about 25 s):

| Configuration | Traces | Spec steps replayed | Stuttering inputs | Final phase PASSED / LOCKED / CANCELLED | Distinct spec states / edges walked | Phase transitions |
|---|---|---|---|---|---|---|
| default | 10,000 | 96,875 | 23,525 | 2,834 / 6,181 / 985 | 1,806 of 6,955 / 4,831 of 16,652 | 31 of 31 |
| supervisor | 10,000 | 110,317 | 28,156 | 1,059 / 7,757 / 1,184 | 2,325 of 11,463 / 6,306 of 28,444 | 31 of 31 |

All 14 failure reasons the model can produce were reached in both
configurations, including `IDENTITY_DRIFT` (290 / 732 times), and **no
divergence remains**. The normal `mvn verify` runs the same test with 3,000
traces per configuration and without the TLC cross-check (no dump present).

What the replay cannot reach, by construction rather than by sampling: spec
states in `PASSIVE_EVALUATING` with zero frames (the Java orchestrator scores
the very frame that passes the quality gate, so `FaceAcquired` and the first
`PassiveFrame` are one `feed()`), identity drift on frames before the
three-frame streak can exist, `Cancel` between a failure and the `Lock` that
the orchestrator takes in the same call, and the many `FAILED` states that
differ only in variables the failure makes irrelevant. These are places where
the spec's environment is more permissive than the implementation, which is
the safe direction for a refinement check.

### Divergences found, and what was done

Writing the mapping and replaying traces found six places where the Java
code and the verified spec disagreed. Two were bugs in the Java code; four
were the spec modelling the implementation too coarsely.

| # | Divergence | Verdict | Resolution |
|---|---|---|---|
| 1 | After the last challenge the orchestrator passed the session **regardless of the re-evaluated fused score**: a subject whose passive score passed (mode `ALWAYS`) and then collapsed during the challenge, or who never cleared it (`ON_LOW_CONFIDENCE`), was accepted as soon as the actions were performed. This violates `PassedImpliesPolicy` and the design (04-passive-to-active-flow.md section 5); it is exactly mutation 6 of the table above, which the spec had been catching all along while the code had the bug. | **Bug (security)** | Fixed in `LivenessOrchestrator.driveChallenge`: after the final challenge the session passes only if the fused score of that frame clears `passiveThreshold`, otherwise `FAILED(LIVENESS_BELOW_THRESHOLD)` with a `PASSIVE_BELOW_THRESHOLD` audit event. Regression test `LivenessOrchestratorTest.challengesDoNotReplaceLivenessEvidence`; three existing tests that performed challenges with signals that never cleared the threshold now use recovering signals. |
| 2 | `retry()` after a device failure went straight to `FACE_ACQUIRING`; the spec re-enters `DEVICE_CONNECTING`. With the camera still unplugged the new attempt timed out as `FACE_NOT_DETECTED` (wrong reason, wrong guidance) instead of a device failure. | **Bug** | Fixed in `LivenessOrchestrator.retry`: device reasons retry into `DEVICE_CONNECTING`, where the connection timer applies and the first frame reconnects implicitly. Regression test `retryAfterDeviceFailureReconnectsTheDevice`. |
| 3 | The spec passed early once `MinChallenges` were verified and the score cleared the threshold; the code always runs the whole drawn sequence. | Spec too permissive (code stricter, and the selector guarantees informative challenges only over the whole sequence) | Spec changed: the drawn sequence must be completed. |
| 4 | Spec: a `PASSED` action before `MinLatencyTicks` fails with `ACTIVE_CHALLENGE_FAILED`, and the challenge deadline is a frame outcome evaluated after the vetoes. Code: the S6 timing veto is `PAD_DETECTED` (attack class video), and timers are checked before a frame is analysed. | Spec too coarse | Spec changed: `tooFast` raises the PAD veto; `ChallengeTimeout` is a separate clock action. |
| 5 | Device failures, engine faults and invalid frames can occur in more phases than the spec allowed (camera unplugged mid-challenge; the analyzer throwing on the first acquisition frame); the attempt timer in `FACE_ACQUIRING` reports the last quality-gate reason, not `SESSION_TIMEOUT`; drift is checked before the PAD veto. | Spec too narrow / ordering | Spec changed accordingly. |
| 6 | Spec: `LOCKED` carries `MAX_RETRIES_EXCEEDED`. Code: `LOCKED` keeps the last attempt's reason, which the desktop gate, the demo and existing tests rely on; the lock is signalled by the phase, the `MAX_RETRIES_REACHED` hint and audit event. | Documentation mismatch | Spec changed to keep the reason (`LockedHasReason` now requires a concrete reason). `docs/design/08-error-handling.md` row 11 still names `MAX_RETRIES_EXCEEDED` as the error case; the client integrations derive it from `LOCKED`. |

All properties still hold on the revised spec, and both bugs are caught by
the conformance test when re-introduced (checked by temporarily reverting
each fix: divergence on trace 10 and trace 3 of the default configuration
respectively).

Run it yourself (needs Python 3 and a JDK; downloads `tla2tools.jar` to
`~/.cache/vivid-tla` if absent):

```sh
formal/conformance.sh --check-generated 10000
```
