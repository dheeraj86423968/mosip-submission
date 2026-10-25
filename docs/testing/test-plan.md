# Test plan

This plan follows the pyramid in PLAN.md §11.1. Test case identifiers are
stable and are referenced from CI job names, PR descriptions and the
results table. Status column values: **implemented** (test exists and runs
in CI), **source-only** (test exists, not yet green or not yet in CI),
**planned**. Update the status column in the same PR that changes the test.

## 1. Pyramid

| Layer | What | Tooling | Runs where |
|---|---|---|---|
| Formal | Orchestrator state machine properties | TLA+ / TLC | CI job `formal`, every push |
| Unit | Orchestrator, policy, challenge selection, fusion, bundle verifier, telemetry | JUnit 5, AssertJ, jqwik (property-based), ArchUnit | CI job `build` |
| Engine | Each signal against labelled frame sets with expected score ranges; calibration regression | JUnit 5 + golden files; self-test frames from the bundle | CI job `build` when models are present; always locally with models |
| Integration | Mock device scenarios end to end through the orchestrator | Scenario JSON + JUnit | CI job `build` |
| Client | Desktop UI flow (TestFX); Android (Espresso + Flutter integration tests on an emulator with the mock camera source) | TestFX, Espresso, `flutter_test` | manual / separate workflow (as designed) |
| Evaluation | ISO/IEC 30107-3 metrics on the held-out corpus, per platform, per PAI species; latency histograms | `testkit iso-report` | CI job `iso-report` when corpus and models are present; otherwise on an evaluation machine |
| Interop | Adapter conformance suite for any `FaceFrameSource` | `testkit` conformance module | CI job `build` for in-repo adapters; vendors run it and file the conformance issue template |
| Chaos | Random device faults, thermal throttling simulation, low-memory kills | Mock device fault injection; Android emulator | nightly (as designed) |

## 2. Test cases

### 2.1 Formal (FV)

| Id | Property | Config | Status |
|---|---|---|---|
| FV-001 | `PassedImpliesPolicy` invariant | default, supervisor | implemented (`formal/`) |
| FV-002 | `NoPassAfterVeto` action property | default, supervisor | implemented |
| FV-003 | `AttemptsBounded` invariant | default, supervisor | implemented |
| FV-004 | `TerminalIsFinal` action property | default, supervisor | implemented |
| FV-005 | `EventuallyTerminal` liveness under weak fairness | default, supervisor | implemented |
| FV-006 | Structural invariants (`TypeOK`, `FailedHasReason`, `LockedHasReason`, `ActiveNeedsPolicy`, `ChallengeSequenceBounded`, `AttemptStartedBeforeWork`) | default, supervisor | implemented |
| FV-007 | Mutation sanity: the seven mutations in `formal/README.md` each produce a counterexample | manual | implemented (manual procedure) |

### 2.2 Unit (UT)

| Id | Module | Case | Mandatory task | Status |
|---|---|---|---|---|
| UT-SM-001 | liveness-core | Every transition in `formal/LivenessOrchestrator.tla` is exercised by a scripted `FrameAnalysis` sequence; 100 % branch coverage of the orchestrator's phase logic | 2, 4, 5 | source-only |
| UT-SM-002 | liveness-core | PASSED is unreachable with a hard veto on any frame of the attempt (randomised, 10,000 runs) | 3 | source-only |
| UT-SM-003 | liveness-core | `retry()` returns false when `attempt == maxRetries`; `LOCKED` follows with `MAX_RETRIES_EXCEEDED` | 5, 9 | source-only |
| UT-SM-004 | liveness-core | `tick()` fires challenge and session timeouts without frames | 9 | source-only |
| UT-SM-005 | liveness-core | `cancel()` from every non-terminal phase yields `CANCELLED`; from `IDLE` gives `attemptsUsed = 0` | 9 | source-only |
| UT-SM-006 | liveness-core | ALWAYS mode never passes without `minChallenges` verified; ON_LOW_CONFIDENCE passes passively at or above threshold; NEVER fails below threshold | 2, 4 | source-only |
| UT-SM-007 | liveness-core | Re-evaluation: veto on the same frame as action completion fails the attempt | 4 | source-only |
| UT-FU-001 | liveness-core | Fusion equation on hand-computed vectors (weights, confidences, clipping, normalisation) | 2, 3 | source-only |
| UT-FU-002 | liveness-core | Temporal smoothing: one outlier frame cannot cross the threshold in either direction | 2 | source-only |
| UT-FU-003 | liveness-core | Hard veto after `vetoFrames` consecutive frames above `padHardThreshold`, not before | 3 | source-only |
| UT-CH-001 | liveness-core | Sequence length in `[minChallenges, maxChallenges]`, without replacement, from the effective pool | 4 | source-only |
| UT-CH-002 | liveness-core | `requireDepthRevealingChallenge` guarantees one depth-revealing type when available | 4 | source-only |
| UT-CH-003 | liveness-core | Chi-square test on 10,000 draws: uniform over permutations at p > 0.01; different nonces give different sequences | 4 | source-only |
| UT-CH-004 | liveness-core | Accessibility pool used when `assistedCapture`; motor challenges never issued | 4, 10 | source-only |
| UT-CH-005 | liveness-core | Low fps replaces BLINK with BLINK_TWICE | 1, 4 | planned |
| UT-AV-001..011 | liveness-core | One case per `ChallengeType`: onset, completion, hold, anti-replay (action already in progress at issue time is ignored), opposite direction fails | 4 | source-only |
| UT-TM-001 | liveness-core | S6: onset before `minActionLatencyMillis` fails; after `maxActionLatencyMillis` times out | 4 | source-only |
| UT-ID-001 | liveness-core | S7: anchor from first N embeddings; drift above threshold on two consecutive samples fails; capture-time check against a provided embedding | 3 | source-only |
| UT-RS-001 | liveness-core | `LivenessResult.evidenceHash` is deterministic over the canonical record and changes when any field changes | — | source-only |
| UT-PO-001 | policy | JSON document parses to `LivenessPolicy` per workflow with the defaults table from `docs/design/07-configuration.md` | 8 | source-only |
| UT-PO-002 | policy | Flattened properties round-trip the JSON document | 8 | source-only |
| UT-PO-003 | policy | Precedence: signed document > tightening properties > defaults; loosening property rejected and audited | 8 | source-only |
| UT-PO-004 | policy | Signature failure falls back to compiled defaults, never to disabled | 8 | source-only |
| UT-PO-005 | policy | Validation rejects: threshold outside [0,1], `minChallenges > maxChallenges`, motor challenge in accessibility pool, empty pool with active mode | 8 | source-only |
| UT-PO-006 | policy | Unknown keys reported, policy still loads | 8 | planned |
| UT-MB-001 | model-bundle | Bundle with valid signature and hashes loads; any altered byte in any file rejects with `HASH`; altered manifest rejects with `SIGNATURE` | bonus | planned |
| UT-MB-002 | model-bundle | Incompatible engine version rejected; zip-slip path rejected; size cap enforced | bonus | planned |
| UT-MB-003 | model-bundle | Rollback keeps N previous bundles and re-verifies on activation | bonus | planned |
| UT-TE-001 | telemetry | Every `FailureReason` and phase change maps to exactly one audit event id; events never contain frames or embeddings (reflection scan of event payload types) | — | source-only |
| UT-TE-002 | telemetry | Metrics aggregator produces per-centre counts and means without per-session identifiers | good-to-have | planned |
| UT-ARCH-001 | all | ArchUnit: `java.net`, `java.nio.file` write APIs and logging of `FaceFrame`/`float[]` embeddings are absent from `liveness-core`, `liveness-engine-*`, `policy`, `telemetry` | — | planned |

### 2.3 Engine (ENG)

| Id | Signal | Case | Mandatory task | Status |
|---|---|---|---|---|
| ENG-S9-001 | quality | Golden frames: blur, dark, off-centre, two faces, ideal → expected `FaceQuality` components within ±0.05 | 9 | source-only |
| ENG-S1-001 | cnn_texture | Self-test frames from the bundle: live < 0.3, print > 0.8, screen > 0.8 attack probability | 3 | source-only (needs models) |
| ENG-S2-001 | moire | Synthetic moiré overlay on a live frame raises `p2` above 0.7; blurred version lowers confidence | 3 | planned |
| ENG-S3-001 | color_stats | Flat-reflectance print frames vs live: separation at the calibrated threshold | 3 | planned |
| ENG-S4-001 | rppg | Synthetic pulse sequence at 72 bpm gives SNR > threshold; constant-colour sequence gives low SNR; confidence 0 before 4 s | 3 | planned |
| ENG-S5-001 | parallax | Landmark trajectories from a planar homography give residual ≈ 0; from a 3-D head model give residual above the live floor | 4 | planned |
| ENG-S8-001 | depth | Plane-fit residual on synthetic planar and face-shaped depth maps | 3 | planned |
| ENG-FA-001 | FaceObservation | Yaw/pitch/roll, eye and mouth aspect ratios from landmark fixtures within ±2° / ±0.02 | 4 | source-only |
| ENG-CAL-001 | all learned | Expected calibration error on the validation split below 0.05 per signal; regression gate | 3 | planned |
| ENG-PERF-001 | all | Per-frame `processingNanos` p95 ≤ 80 ms at 480p on CI CPU (desktop); recorded, gate at +20 % regression | bonus | planned |

### 2.4 Integration (IT) — mock device scenarios through the orchestrator

| Id | Scenario file | Expected outcome | Mandatory task | Status |
|---|---|---|---|---|
| IT-LIVE-001 | `live-pass-resident.json` | `PASSED` passively, no challenge, `rcapture` continuity accepted | 2, 5 | source-only |
| IT-LIVE-002 | `live-low-light-resident.json` | passive below threshold → challenges → `PASSED` | 2, 4, 5 | source-only |
| IT-LIVE-003 | `live-operator.json` | ALWAYS: one challenge then `PASSED`; match gate opened | 6 | source-only |
| IT-LIVE-004 | `live-supervisor.json` | two challenges, parallax consistent, `PASSED` | 7 | source-only |
| IT-PAD-001 | `print-a4.json` | `FAILED(PAD_DETECTED)` class PRINT, generic UI message, audit has detail | 3 | source-only |
| IT-PAD-002 | `screen-photo.json` | `FAILED(PAD_DETECTED)` class SCREEN_REPLAY | 3 | source-only |
| IT-PAD-003 | `video-replay.json` | fails by veto, challenge timing (`ACTIVE_CHALLENGE_FAILED` / `CHALLENGE_TIMEOUT`) or threshold; never `PASSED` | 3, 4 | source-only |
| IT-PAD-004 | `paper-mask.json` | never `PASSED` | 3 | planned |
| IT-PAD-005 | `deepfake-injected.json` | never `PASSED`; injection or rPPG signal recorded | 3 | planned |
| IT-SWAP-001 | `swap-mid-challenge.json` | `FAILED(IDENTITY_DRIFT)` | 3 | source-only |
| IT-SWAP-002 | `swap-at-rcapture.json` | session `PASSED`, capture continuity rejects, `IDENTITY_DRIFT(stage=CAPTURE)` | 3, 5 | source-only |
| IT-DEV-001 | `device-absent.json` | `FAILED(DEVICE_UNAVAILABLE)` | 1, 9 | source-only |
| IT-DEV-002 | `disconnect-mid-challenge.json` | `RECONNECTING` hint, reconnect within timeout → continues; beyond → `CHALLENGE_TIMEOUT` | 1, 9 | source-only |
| IT-DEV-003 | `stalled-stream.json` | `DEGRADED` then reconnect; `tick()` keeps timers | 1, 9 | source-only |
| IT-DEV-004 | `corrupt-frames.json` | isolated frames skipped; sustained → `FAILED(INVALID_FRAME_DATA)` | 1, 9 | source-only |
| IT-DEV-005 | `wrong-spec-version.json` | `FAILED(DEVICE_UNAVAILABLE)` with protocol detail in audit | 1 | planned |
| IT-ERR-001 | `no-face.json` | `FACE_NOT_DETECTED` after acquisition timeout | 9 | source-only |
| IT-ERR-002 | `two-faces.json` | `MULTIPLE_FACES`; hint `ONLY_ONE_FACE` during | 9 | source-only |
| IT-ERR-003 | `poor-quality.json` | hints during; `POOR_FACE_QUALITY` after timeout | 9 | source-only |
| IT-ERR-004 | `challenge-timeout.json` | `CHALLENGE_TIMEOUT` | 9 | source-only |
| IT-ERR-005 | `wrong-action.json` | `ACTIVE_CHALLENGE_FAILED` | 9 | source-only |
| IT-ERR-006 | `engine-throws.json` | `ENGINE_ERROR`; no capture | 9 | source-only |
| IT-RET-001 | `fail-twice-then-pass.json` | attempts 1 and 2 fail, 3 passes; `attemptsUsed = 3` | 5 | source-only |
| IT-RET-002 | `fail-three.json` | `LOCKED`, `MAX_RETRIES_REACHED(onMaxRetries)` | 5, 9 | source-only |
| IT-RET-003 | `assisted-after-lock.json` | supervisor session passes, resident assisted session with accessibility pool passes, audit has `SUPERVISOR_OVERRIDE` | 5, 10 | planned |
| IT-OFF-001 | full suite | run with networking disabled at OS level; identical outcomes; no socket opened by liveness modules | 2, 6, 7 | planned |
| IT-POL-001 | `policy-disabled.json` | no session, `POLICY_LOADED(enabled=false)`, metadata flag | 8 | planned |
| IT-POL-002 | per-workflow policies | same corpus item yields different outcomes under RESIDENT vs SUPERVISOR policy as predicted | 8 | source-only |

### 2.5 Client (CL) — as designed

| Id | Platform | Case | Mandatory task | Status |
|---|---|---|---|---|
| CL-D-001 | Desktop TestFX | Resident capture: every state S0–S9 of the UX spec rendered with the exact copy; `rcapture` not invoked before `PASSED` | 5, 10 | planned |
| CL-D-002 | Desktop | Operator auth: match service not called before `PASSED`; lock after max retries | 6, 10 | planned |
| CL-D-003 | Desktop | Supervisor auth: same with stricter policy | 7, 10 | planned |
| CL-A-001..003 | Android Espresso / Flutter | Same three flows with the mock camera source on an emulator | 5–7, 10 | planned |
| CL-I18N-001 | both | Every `liveness.*` key present in every supported locale bundle | 10 | planned |
| CL-A11Y-001 | both | Screen-reader announcements on every state change; no colour-only information (automated contrast and pattern check) | 10 | planned |

### 2.6 Evaluation (EV)

| Id | Case | Status |
|---|---|---|
| EV-001 | `CorpusScoreMain` → `scores.jsonl` → `IsoReportMain` on the held-out subject-disjoint split: APCER per PAI species with Wilson 95 % intervals, BPCER overall and per group slice (suppressed below 5 participants), ACER, BPCER@APCER 5 % and 10 % with thresholds, APNRR/BPNRR. Metric code is unit-tested against hand-computed values (`RateTest`, `Iso30107MetricsTest`, `GroupSlicesTest`, `IsoReportMainTest`). The scorer is tested end to end with the real orchestrator and ONNX engine on a synthetic device-mock corpus (`CorpusScoreMainTest`). | implemented (tooling). No consented corpus. A digital proxy (39 adults in openly licensed video, 5 simulated attack species, passive and challenge replay) was run on 24 Oct 2026: `docs/testing/proxy-eval/README.md`. It is not physical PAI testing. |
| EV-002 | Processing-time histograms per platform | planned |
| EV-003 | Regression gate: fail CI if worst-species APCER or BPCER rises more than 2 points against the last published report | planned |

### 2.7 Interop (IO)

| Id | Case | Status |
|---|---|---|
| IO-001 | Conformance: frames delivered at or near `targetFps` with monotonic `sequence` and `timestampNanos` | source-only |
| IO-002 | Conformance: declared `PixelFormat` matches buffer size and layout | source-only |
| IO-003 | Conformance: `stop()` idempotent; `onEnd()` exactly once per `start()` | source-only |
| IO-004 | Conformance: faults are signalled through `onHealth` and `DeviceException.Kind`, never by throwing from `onFrame` | source-only |
| IO-005 | Conformance: reconnect honours `reconnectAttempts` and backoff bounds | planned |
| IO-006 | `device-mock` and `device-sbi-stream` (against `device-mock`) both pass IO-001..005 | source-only |

### 2.8 Chaos (CH) — as designed

| Id | Case | Status |
|---|---|---|
| CH-001 | Random fault injection across 1,000 scenario runs; invariant: never `PASSED` on an attack item, never a hang beyond `sessionTimeoutMillis` | planned |
| CH-002 | Thermal throttling simulation on Android: fps drops, rPPG disabled, outcomes unchanged for live items | planned |
| CH-003 | Low-memory kill mid-session: on restart the session is gone, audit has `SESSION_FAILED(CANCELLED)` or nothing; no partial packet | planned |

## 3. Mapping to the problem statement's mandatory tasks

| Mandatory task | Test cases |
|---|---|
| 1 L0/L1 device integration | IT-DEV-001..005, IO-001..006, UT-CH-005 |
| 2 Passive liveness | UT-SM-001, UT-SM-006, UT-FU-001..002, IT-LIVE-001..002, IT-OFF-001 |
| 3 PAD | UT-SM-002, UT-FU-003, UT-ID-001, ENG-S1..S8, IT-PAD-001..005, IT-SWAP-001..002, EV-001 |
| 4 Active liveness | UT-SM-006..007, UT-CH-001..004, UT-AV-001..011, UT-TM-001, ENG-S5-001, ENG-FA-001, IT-LIVE-002..004 |
| 5 Resident registration | IT-LIVE-001..002, IT-RET-001..003, IT-SWAP-002, CL-D-001, CL-A-001 |
| 6 Operator authentication | IT-LIVE-003, CL-D-002, CL-A-002, IT-OFF-001 |
| 7 Supervisor authentication | IT-LIVE-004, CL-D-003, CL-A-003, IT-OFF-001 |
| 8 Configuration | UT-PO-001..006, IT-POL-001..002 |
| 9 Error handling | UT-SM-003..005, ENG-S9-001, IT-DEV-001..004, IT-ERR-001..006, IT-RET-002 |
| 10 UI/UX | UT-CH-004, CL-D-001..003, CL-A-001..003, CL-I18N-001, CL-A11Y-001, IT-RET-003 |
| Bonus: mock device | IO-006, every IT case |
| Bonus: automated liveness / PAD scenarios | IT-LIVE-*, IT-PAD-* |
| Bonus: interoperability framework | IO-* |
| Bonus: model versioning / offline updates | UT-MB-001..003 |
| Bonus: low-resource Android | ENG-PERF-001, CH-002 |

## 4. Test data

* `device-mock` scenario files: `device-mock/src/test/resources/scenarios/`
  (names as in §2.4).
* Corpus: not in the repository. Each item is a folder of numbered
  `.jpg`/`.png` frames named by its item ID (`docs/corpus-ethics.md` §10.1),
  with an optional `timestamps.txt`; labels, PAI species and consented group
  tags come from the capture log CSV (`docs/corpus/capture-log-template.csv`).
  `CorpusScoreMainTest` renders a small synthetic corpus from `device-mock`
  (no real people) for smoke tests. Commands are in §6.
* Model files: the YuNet detector is in `liveness-engine-onnx/models/`;
  other models arrive through a signed bundle (`docs/design/11-model-lifecycle.md`).
* Credentials: none. The mock device needs no authentication; the demo
  runs without a MOSIP server.

## 5. Results table (template)

Copy this table into `docs/testing/results-<date>.md` for each test run
that is reported in the submission. Fill every row; "not run" is an
acceptable value and is more useful than an empty cell.

| Id | Layer | Date | Commit | Environment (OS, JDK, device/mock, models, policy) | Result (pass / fail / not run) | Evidence (CI run URL, report path, log) | Notes |
|---|---|---|---|---|---|---|---|
| FV-001..006 | Formal | 2026-10-23 | — | Linux, OpenJDK 25, TLC 2026.10.23 | pass (5,032 and 8,187 distinct states) | `formal/tlc-output-*.txt` | both configurations |
| FV-007 | Formal | 2026-10-23 | — | same | pass (7/7 mutations caught) | `formal/README.md` | manual |
| UT-* | Unit | | | | | | |
| ENG-* | Engine | | | | | | |
| IT-* | Integration | | | | | | |
| CL-* | Client | | | | | | |
| EV-001 | Evaluation | | | corpus version, subject count, species list, mode (passive / challenge replay) | | `target/iso-report/iso30107.html`, `iso30107.json` | APCER per species with CI, BPCER with CI, ACER, BPCER@5 %/10 %, APNRR/BPNRR, slices |
| IO-* | Interop | | | | | | |
| CH-* | Chaos | | | | | | |

## 6. `testkit` command-line contract

The CI job `iso-report` and the evaluation procedure use exactly these
commands. Both are plain `main` classes in the `testkit` module; there is no
`testkit.jar iso-report` wrapper. Build once and resolve the classpath:

```
mvn -q -pl testkit -am -DskipTests install
mvn -q -pl testkit dependency:build-classpath -Dmdep.outputFile=/tmp/testkit.cp
CP="testkit/target/classes:$(cat /tmp/testkit.cp)"
```

**Step 1: corpus → `scores.jsonl`** (`io.mosip.vivid.testkit.corpus.CorpusScoreMain`)

```
java -Xmx2g -cp "$CP" io.mosip.vivid.testkit.corpus.CorpusScoreMain \
     --corpus <dir> --out <scores.jsonl> [--log <capture-log.csv>] \
     [--mode passive|challenge] [--workflow RESIDENT|OPERATOR|SUPERVISOR] \
     [--fps 15] [--models <dir>] [--passive-threshold x] [--detector-threshold x]
```

* Corpus layout: every directory under `--corpus` that contains frames is
  one item. The directory name is the item id from
  `docs/corpus-ethics.md` §10.1 (`P07-VIDEO_REPLAY_PHONE-L2-D1-T1`). Frames
  are numbered `.jpg`, `.jpeg` or `.png` files sorted by name (the
  `ffmpeg -vf fps=15 .../%05d.jpg` output). An optional `timestamps.txt` holds
  one `<millis>` per frame or `<file> <millis>` lines. Without it, frames are
  spaced at `--fps`. Video files are not read directly, so extract frames first.
* `capture-log.csv` (columns of `docs/corpus/capture-log-template.csv`) is
  read from `--log` or `<corpus>/capture-log.csv`. It supplies `label`,
  `participant_id`, `attack_class` and the group tags. When an item has no
  row, the label, participant, lighting and device are parsed from its name.
* Each item is one session through the real `liveness-core` orchestrator and
  the real ONNX engine. A fake `TimeSource` is advanced to each frame's
  timestamp (frame clock). Only the first attempt is recorded
  (presentation level). `passive` (the default) uses the workflow's default policy with
  `active.mode = NEVER`. `challenge` uses `ALWAYS`, which shows that a replay ignoring the
  prompts does not pass, so bona fide recordings fail in this mode too.
* `--models` defaults to `-Dvivid.models`, `$MOSIP_VIVID_MODELS` or
  `./models`. `--detector-threshold` lowers the detector threshold for
  synthetic (drawn) faces only. Never use it for real corpora.
* Exit codes: 0 ok, 2 usage or input error (or nothing written), 3 no model directory.

**Step 2: `scores.jsonl` → report** (`io.mosip.vivid.testkit.metrics.IsoReportMain`)

```
java -cp "$CP" io.mosip.vivid.testkit.metrics.IsoReportMain \
     --scores <scores.jsonl> --out <dir> [--threshold 0.5] [--title T] \
     [--context key=value]... [--max-acer x] [--min-subjects 5] [--no-slices]
```

Outputs: `<dir>/iso30107.json` and `<dir>/iso30107.html`. Exit codes: 0 ok, 1 ACER above `--max-acer`,
2 usage or input error. `--min-subjects` cannot be set below 5. The
`scores.jsonl` record format, including how non-responses and group tags are
represented, is documented in `docs/corpus-ethics.md` §10.2.

The scenario runner and the conformance suite are JUnit APIs
(`ScenarioSuite`, `FrameSourceConformanceTest`; see `testkit/README.md`),
run by the `build` job's tests. They have no CLI.
