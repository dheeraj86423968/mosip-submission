# Test plan

This plan follows the pyramid in PLAN.md §11.1. Test case identifiers are
stable and are referenced from CI job names, PR descriptions and the
results table. Status column values:

* **implemented**: a test checks what the row describes. The cell names it as
  `ClassName#method` or `ClassName`. Unless the cell says otherwise, the test
  runs in `mvn install` and in the CI job `build`.
* **partial**: a test covers part of the row. The cell says what is missing.
* **not implemented**: no test covers the row.

Markers used in the status cells: *needs models* means the test is skipped by a
JUnit assumption when the ONNX models are absent, which is the case in the CI
job `build` because the models are not committed; *not in CI* means no workflow
runs it (the Android and Flutter tests); *manual* means a documented procedure
rather than an automated test; *scripted* means a scripted `FrameAnalysis`
sequence (`liveness-core` test `Scenarios`, `FakeFrameAnalyzer`) fed through the
real orchestrator. Update the status column in the same PR that changes the test.

## Status summary (2026-11-01)

Every row below was checked against the test sources under `*/src/test`,
`formal/`, `testkit`, `e2e-tests` and `integration-android` on 2026-11-01. The
full build (`mvn install`) runs 633 tests and CI is green; skipped tests are not
counted as evidence for "implemented" unless the cell says *needs models*.

| Layer | Rows | implemented | partial | not implemented |
|---|---|---|---|---|
| Formal (FV) | 7 | 7 | 0 | 0 |
| Unit (UT) | 31 | 24 | 5 | 2 |
| Engine (ENG) | 10 | 4 | 4 | 2 |
| Integration (IT) | 28 | 16 | 8 | 4 |
| Client (CL) | 6 | 0 | 4 | 2 |
| Evaluation (EV) | 3 | 1 | 2 | 0 |
| Interop (IO) | 6 | 1 | 5 | 0 |
| Chaos (CH) | 3 | 0 | 1 | 2 |
| **Total** | **94** | **53** | **29** | **12** |

Points that affect how far the statuses can be trusted:

* The bundled mock scenarios run through the real engine in
  `RealPipelineScenarioTest` (needs models). It asserts that no scenario crashes,
  that no attack or unusable-capture scenario (print, screen replay, video replay,
  multiple faces, no face) is ever accepted, and that the printed photo ends as
  `ATTACK`. The finer expectations in `testkit/src/main/resources/expectations/`
  are reported, not asserted, because scripted live scenarios cannot answer
  random prompts; the interactive cases are in `InteractiveChallengeTest`.
* Seven red-team regression fixtures are committed; five of them (passive
  `SIM_PRINT`, `SIM_PRINT_CUTOUT`, `SIM_PRINT_WARPED`, `SIM_SCREEN_REPLAY`,
  `SIM_LOWRES_REPLAY`) are marked `knownBypass`, so `RedTeamFixtureReplayTest`
  reports them as skipped while they are still accepted in passive mode.
* The Android Kotlin unit tests and the Flutter tests are not run by any CI
  workflow.

## 1. Pyramid

| Layer | What | Tooling | Runs where |
|---|---|---|---|
| Formal | Orchestrator state machine properties | TLA+ / TLC | CI job `formal`, every push |
| Unit | Orchestrator, policy, challenge selection, fusion, bundle verifier, telemetry | JUnit 5, AssertJ (seeded randomised tests in place of a property-based library) | CI job `build` |
| Engine | Each signal against synthetic frames with expected score ranges | JUnit 5 | CI job `build` (models are downloaded and checksum-verified in CI) |
| Integration | Mock device scenarios end to end through the orchestrator | Scenario JSON + JUnit | CI job `build` |
| Client | Desktop gate and UI binding with a scripted session; Android Kotlin JVM tests and Flutter widget tests | JUnit 5, `flutter_test` | Desktop in CI job `build`; Android via `integration-android/apply-to-arc.sh` (not in CI) |
| Evaluation | ISO/IEC 30107-3 metrics per PAI species, with Wilson intervals and non-response rates | `CorpusScoreMain` and `IsoReportMain` | CI job `iso-report` when a corpus is present; nightly red team on synthetic attacks; proxy evaluation on real-face video (`docs/testing/proxy-eval/`) |
| Interop | Adapter conformance suite for any `FaceFrameSource` | `testkit` conformance module | CI job `build` for in-repo adapters; vendors run it and file the conformance issue template |
| Chaos | Device faults (disconnect, stall, corrupt frame, latency spike) | Mock device fault injection | CI job `build`; thermal throttling and low-memory kills are not covered |

## 2. Test cases

### 2.1 Formal (FV)

| Id | Property | Config | Status |
|---|---|---|---|
| FV-001 | `PassedImpliesPolicy` invariant | default, supervisor | implemented: TLC on `formal/LivenessOrchestrator.tla`, CI job `formal` |
| FV-002 | `NoPassAfterVeto` action property | default, supervisor | implemented: TLC, CI job `formal` |
| FV-003 | `AttemptsBounded` invariant | default, supervisor | implemented: TLC, CI job `formal` |
| FV-004 | `TerminalIsFinal` action property | default, supervisor | implemented: TLC, CI job `formal` |
| FV-005 | `EventuallyTerminal` liveness under weak fairness | default, supervisor | implemented: TLC, CI job `formal` |
| FV-006 | Structural invariants (`TypeOK`, `FailedHasReason`, `LockedHasReason`, `ActiveNeedsPolicy`, `ChallengeSequenceBounded`, `AttemptStartedBeforeWork`) | default, supervisor | implemented: TLC, CI job `formal` |
| FV-007 | Mutation sanity: the seven mutations in `formal/README.md` each produce a counterexample | manual | implemented (manual procedure in `formal/README.md`) |

### 2.2 Unit (UT)

| Id | Module | Case | Mandatory task | Status |
|---|---|---|---|---|
| UT-SM-001 | liveness-core | Every transition in `formal/LivenessOrchestrator.tla` is exercised by a scripted `FrameAnalysis` sequence; 100 % branch coverage of the orchestrator's phase logic | 2, 4, 5 | partial: `SpecConformanceTest#orchestratorRefinesTheSpecOnRandomTraces` and `#generatedTablesMatchTheApiAndCoverObservedTransitions` replay random spec traces against the orchestrator and require every generated phase transition to be observed. Missing: JaCoCo only reports coverage; nothing gates branch coverage at 100 %. |
| UT-SM-002 | liveness-core | PASSED is unreachable with a hard veto on any frame of the attempt (randomised, 10,000 runs) | 3 | implemented: `SpecConformanceTest#orchestratorRefinesTheSpecOnRandomTraces` (3,000 traces per configuration in `build`, 10,000 in CI job `formal`; frames carry random vetoes and `PASSED` is checked to have no veto signal); `LivenessOrchestratorTest#photoAttackIsVetoedByCnnTexture`, `#padDuringChallengeFailsSession` |
| UT-SM-003 | liveness-core | `retry()` returns false when `attempt == maxRetries`; `LOCKED` follows, keeping the last attempt's failure reason, with hint and audit event `MAX_RETRIES_REACHED` | 5, 9 | implemented: `LivenessOrchestratorTest#challengeTimeoutThenRetryThenLock` |
| UT-SM-004 | liveness-core | `tick()` fires challenge and session timeouts without frames | 9 | implemented: `LivenessOrchestratorTest#challengeTimeoutThenRetryThenLock`, `#sessionTimeoutDuringPassive`, `#deviceConnectTimeout`, `#noFaceGuidanceThenTimeout` |
| UT-SM-005 | liveness-core | `cancel()` from every non-terminal phase yields `CANCELLED`; from `IDLE` the state's `attempt` stays 0 | 9 | implemented: `SpecConformanceTest` (all six `Cancel` transitions observed, attempt compared with the spec); `LivenessOrchestratorTest#cancelAndClose` |
| UT-SM-006 | liveness-core | ALWAYS mode never passes without `minChallenges` verified; ON_LOW_CONFIDENCE passes passively at or above threshold; NEVER fails below threshold | 2, 4 | implemented: `SpecConformanceTest` (`checkResultInvariants`); `LivenessOrchestratorTest#alwaysModeRunsChallengesAfterPassivePass`, `#liveSubjectPassesPassively`, `#activeModeNeverFailsBelowThreshold` |
| UT-SM-007 | liveness-core | Re-evaluation: veto on the same frame as action completion fails the attempt | 4 | implemented: `SpecConformanceTest` (a challenge frame carries score, veto and detector outcome together); `LivenessOrchestratorTest#replayFailsChallengeTiming` |
| UT-FU-001 | liveness-core | Fusion equation on hand-computed vectors (weights, confidences, clipping, normalisation) | 2, 3 | implemented: `ScoreFusionTest#combineIsWeightedMeanOfLogOdds`, `#combineIgnoresDisabledAndQualitySignalsAndDefaultsToHalf`, `#certainSignalsAreClampedNotInfinite`, `#sessionSignalsParticipateInEveryUpdate` |
| UT-FU-002 | liveness-core | Temporal smoothing: one outlier frame cannot cross the threshold in either direction | 2 | partial: `ScoreFusionTest#oneBadFrameDoesNotFailAndOneGoodFrameDoesNotPass`, `#exponentialMovingAverageSmoothsFrames`. Missing: the test bounds are 0.7 and 0.4, not the 0.8 passive threshold; with the default alpha 0.25 one attack frame after live frames brings the smoothed score to about 0.75, below 0.8. |
| UT-FU-003 | liveness-core | Hard veto on a single frame when a signal sets `hardVeto`, or its attack probability is at or above `padHardThreshold` with confidence at least 0.5; disabled signals and quality never veto (there is no `vetoFrames` setting) | 3 | implemented: `ScoreFusionTest#hardVetoRules` |
| UT-CH-001 | liveness-core | Sequence length in `[minChallenges, maxChallenges]`, without replacement, from the effective pool | 4 | implemented: `ChallengeSelectorTest#lengthIsUniformBetweenMinAndMax`, `#intersectsWithEngineSupport`, `#zeroLengthAndEmptyPools` |
| UT-CH-002 | liveness-core | `requireDepthRevealingChallenge` guarantees one depth-revealing type when available | 4 | implemented: `ChallengeSelectorTest#depthRevealingChallengeIsGuaranteed`, `#forcedReplacementWhenRedrawsAreExhausted`, `#depthConstraintIsSkippedWhenPoolHasNone` |
| UT-CH-003 | liveness-core | Chi-square test on 20,000 draws (seeded `Random` and `SecureRandom`): first element and ordered pairs uniform at p = 0.001. Sequences come from the session's random source, not from the nonce | 4 | implemented: `ChallengeSelectorTest#firstElementAndPermutationsAreUniform_seeded`, `#firstElementAndPermutationsAreUniform_secureRandom` |
| UT-CH-004 | liveness-core | Accessibility pool used when `assistedCapture`; motor challenges never issued | 4, 10 | implemented: `ChallengeSelectorTest#assistedCaptureUsesAccessibilityPoolWithoutMotorChallenges`; `LivenessOrchestratorTest#assistedCaptureUsesAccessibilityPool` |
| UT-CH-005 | liveness-core | Low fps replaces BLINK with BLINK_TWICE | 1, 4 | not implemented (the selector has no fps rule) |
| UT-AV-001..011 | liveness-core | One case per `ChallengeType`: onset, completion, hold, anti-replay (action already in progress at issue time is ignored), opposite direction fails | 4 | partial: `ActionDetectorsTest#everyChallengeCompletesFromItsScriptedPerformance` and `#neutralFacesNeverComplete` cover onset and completion for all 11 types. Missing per type: anti-replay is tested only for BLINK, SMILE and head turns (`#blinkRequiresOpenEyesAfterPromptBeforeClosureCounts`, `#smileFallbacks`, `#headMotionNeedsNeutralBaselineAndReturn`), opposite direction only for TURN_LEFT, hold interruption only for SMILE and HOLD_STILL. |
| UT-TM-001 | liveness-core | S6: onset before the prompt or faster than half of `minActionLatencyMillis` (150 ms default) is a hard veto (video replay); faster than the minimum is suspicious; slower than `maxActionLatencyMillis` (3000 ms default) is mildly suspicious, not a timeout | 4 | implemented: `ChallengeTimingTest`; `LivenessOrchestratorTest#replayFailsChallengeTiming`, `#timingVetoIgnoredWhenTimingSignalDisabled` |
| UT-ID-001 | liveness-core | S7: anchor from first N embeddings; drift above threshold on three consecutive frames fails; capture-time check against a provided embedding | 3 | implemented: `IdentityContinuityTest#anchorIsMeanOfFirstGoodEmbeddings`, `#driftOnThreeConsecutiveFramesVetoes`, `#streakResetsOnMatchingFrameAndOnRetry`; capture check in `CaptureBindingTest#sameIdentityBindsAndDifferentIdentityIsDrift` (integration-desktop) |
| UT-RS-001 | liveness-core | `LivenessResult.evidenceHash` is deterministic over the canonical record and changes when any field changes | — | implemented: `QualityGateAndEvidenceTest#evidenceHashIsDeterministicAndSensitive` (canonical string pins every field); `LivenessOrchestratorTest#liveSubjectPassesPassively` (`EvidenceHash.verify`) |
| UT-PO-001 | policy | JSON document parses to `LivenessPolicy` per workflow; built-in defaults equal `LivenessPolicy.defaults` (all three workflows `ALWAYS`; pool BLINK, SMILE, TURN_LEFT, TURN_RIGHT, OPEN_MOUTH, so NOD, LOOK_UP and LOOK_DOWN are not in the default pool) | 8 | implemented: `PolicyDocumentTest#parsesThePlanExampleDocument`, `PolicyLoaderTest#builtinDefaultsMatchApiDefaultsForEveryWorkflow`, `#everyFieldIsApplied`, `PropertiesPolicyAdapterTest#flattensToDocumentedKeys`. No test compares against the table in `docs/design/07-configuration.md`. |
| UT-PO-002 | policy | Flattened properties round-trip the JSON document | 8 | implemented: `PropertiesPolicyAdapterTest#roundTripPreservesDocument`, `#unflattensPartialKeys` |
| UT-PO-003 | policy | Precedence: signed document > tightening properties > defaults; loosening property rejected and audited | 8 | implemented: `DesktopLivenessConfigTest#localConfigurationCanTightenButNotLoosen` (loosening values are ignored and logged via `PolicyTightening`; `enabled` stays a free switch), `PropertiesPolicyAdapterTest#jsonKeyTakesPrecedence`, `PolicyLoaderTest#partialDocumentMergesOverDefaults`. Logged, not yet written to the audit trail |
| UT-PO-004 | policy | Signature failure falls back to compiled defaults, never to disabled | 8 | implemented: `PolicyLoaderTest#loadVerifiedFallsBackOnTamperedDocument`, `#loadVerifiedRejectsUnsignedWhenKeyConfigured`; `SigningTest` |
| UT-PO-005 | policy | Validation rejects: threshold outside [0,1], `minChallenges > maxChallenges`, motor challenge in accessibility pool, empty pool with active mode | 8 | implemented: `PolicyValidatorTest#thresholdsMustBeInRange`, `#minChallengesMustNotExceedMax`, `#poolMustNotBeEmptyWhenActive`, `#accessibilityPoolRejectsHeadMovementChallenges` |
| UT-PO-006 | policy | Unknown keys are rejected with a `PolicyException` naming the key; `loadVerified` then falls back to compiled defaults | 8 | implemented: `PolicyDocumentTest#rejectsUnknownTopLevelField`, `#rejectsUnknownNestedField`, `#rejectsUnknownWorkflowKey`; `PolicyLoaderTest#loadVerifiedFallsBackOnMissingMalformedOrInvalidDocument` |
| UT-MB-001 | model-bundle | Bundle with valid signature and hashes loads; an altered model byte rejects with `HASH_MISMATCH`; altered manifest rejects with `BAD_SIGNATURE` | bonus | implemented: `BundleVerifierTest#goodBundleVerifiesWithEitherKeyInTrustSet`, `#tamperedModelFileIsRejectedWithHashMismatch`, `#tamperedManifestIsRejected`, `#directoryVerificationDetectsOnDiskTampering` |
| UT-MB-002 | model-bundle | Incompatible engine version rejected; zip-slip path rejected; size cap enforced | bonus | partial: `BundleStoreTest#incompatibleBundleIsRefusedAtInstall`, `BundleVerifierTest#missingPiecesAreTyped` (`UNSAFE_PATH`). Missing: no size-cap test (the verifier only caps the manifest at 1 MiB). |
| UT-MB-003 | model-bundle | Rollback keeps N previous bundles and re-verifies on activation | bonus | implemented: `BundleStoreTest#rollbackWalksHistoryAndPruningKeepsN`, `#secondRollbackGoesFurtherBack`, `#onDiskTamperingAfterInstallIsDetectedOnActivation` |
| UT-TE-001 | telemetry | Every `FailureReason` and phase change maps to exactly one audit event id; events never contain frames or embeddings (reflection scan of event payload types) | — | partial: `AuditRecordTest#enumHasExactlyThePlannedEventsWithSeverityAndDescription` pins the event list; `#builderRedactsBiometricKeysAndIsImmutable` drops frame, image, embedding and landmark keys. Missing: no check of the `FailureReason`-to-event mapping and no reflection scan. |
| UT-TE-002 | telemetry | Metrics aggregator (one per centre) produces counts and means without per-session identifiers | good-to-have | implemented: `LivenessMetricsTest#aggregatesCountersAndAverages`, `#isThreadSafe` |
| UT-ARCH-001 | all | ArchUnit: `java.net`, `java.nio.file` write APIs and logging of `FaceFrame`/`float[]` embeddings are absent from `liveness-core`, `liveness-engine-*`, `policy`, `telemetry` | — | not implemented (no ArchUnit dependency or equivalent check) |

### 2.3 Engine (ENG)

| Id | Signal | Case | Mandatory task | Status |
|---|---|---|---|---|
| ENG-S9-001 | quality | Golden frames: blur, dark, off-centre, two faces, ideal → expected `FaceQuality` components within ±0.05 | 9 | partial: `ParallaxInjectionQualityTest#qualityRewardsSharpWellLitFaces` (synthetic sharp, dark, featureless and two-face frames, implausible landmarks); off-centre handled at the gate in `QualityGateAndEvidenceTest#faceCountAndGeometryHints`. Missing: no golden frames, no blurred-face case, no ±0.05 tolerance. |
| ENG-S1-001 | cnn_texture | Self-test frames from the bundle: live < 0.3, print > 0.8, screen > 0.8 attack probability | 3 | not implemented: no test asserts CNN texture probabilities. `OnnxEngineIntegrationTest#selfTestWithBundledImage` runs only with a user-supplied image (needs models) and checks that the self-test is not skipped. |
| ENG-S2-001 | moire | Synthetic moiré overlay on a live frame raises `p2` above 0.7; blurred version lowers confidence | 3 | partial: `MoireAndColorSignalTest#periodicScreenPatternScoresAsMoireAttack` (synthetic grating above 0.5 with class SCREEN, skin texture below 0.5), `#flatPatchHasNoConfidence`. Missing: overlay on a live frame, the 0.7 bound, the blurred-version confidence check. |
| ENG-S3-001 | color_stats | Flat-reflectance print frames vs live: separation at the calibrated threshold | 3 | implemented (synthetic frames, 0.5 boundary): `MoireAndColorSignalTest#colourStatsSeparatesSkinFromDesaturatedPrint`, `#blueClippedScreenIsClassedAsScreen`, `#calibrationShiftsProbability` |
| ENG-S4-001 | rppg | Synthetic pulse sequence at 72 bpm gives SNR > threshold; constant-colour sequence gives low SNR; confidence 0 below 3 s of data | 3 | implemented: `RppgSignalTest#periodicPulseIsDetectedAsLive`, `#noPulseIsAnAttack`, `#confidenceRampsWithBufferAndGapsResetIt`, `#scoresThroughTheSignalInterfaceFromFrames` |
| ENG-S5-001 | parallax | Landmark trajectories from a planar homography give residual ≈ 0; from a 3-D head model give residual above the live floor | 4 | implemented: `ParallaxInjectionQualityTest#realHeadTurnHasParallaxPlanarPhotoDoesNot`, `#translationOnlyGivesNoConfidence`, `#neutralWithoutDepthChallengeAndVerdictIsSticky` |
| ENG-S8-001 | depth | Plane-fit residual on synthetic planar and face-shaped depth maps | 3 | implemented (synthetic planes only): `DepthIrSignalTest`; wiring in `DepthIrWiringTest` |
| ENG-FA-001 | FaceObservation | Yaw/pitch/roll, eye and mouth aspect ratios from landmark fixtures within ±2° / ±0.02 | 4 | partial: `GeometryTest#headPoseSignsAndMagnitudes`, `HeadPoseTest#calibratedPitchTracksUpAndDownForEveryAnatomyAndView`, `ObservationExtractorTest`. Missing: the checks are signs and ranges (a 25° yaw reads 15-40°, pitch gain 0.7-2.1, eye and mouth ratios by direction); only roll is exact. |
| ENG-CAL-001 | all learned | Expected calibration error on the validation split below 0.05 per signal; regression gate | 3 | not implemented (no calibration-error computation in the code) |
| ENG-PERF-001 | all | Per-frame `processingNanos` p95 ≤ 80 ms at 480p on CI CPU (desktop); recorded, gate at +20 % regression | bonus | partial: `OnnxEngineIntegrationTest#fullFrameLatencyOn640x480` logs median and p90 and gates the median below 250 ms (needs models); `LatencyBenchmarkTest` checks the percentile code. Missing: p95 ≤ 80 ms gate and +20 % regression gate. |

### 2.4 Integration (IT) — mock device scenarios through the orchestrator

The bundled mock scenarios are in `device-mock/src/main/resources/scenarios/`.
The file names in the second column are those files where one exists;
otherwise the column says how the case is driven.

| Id | Scenario | Expected outcome | Mandatory task | Status |
|---|---|---|---|---|
| IT-LIVE-001 | `live-pass.json`; scripted | With `active.mode = ON_LOW_CONFIDENCE`: `PASSED` passively, no challenge, `rcapture` continuity accepted. The RESIDENT default is `ALWAYS`, so the default policy issues 2-3 challenges | 2, 5 | implemented (scripted): `LivenessOrchestratorTest#liveSubjectPassesPassively`, `FrameSourceBridgeTest#framesFlowToSessionAndSourceStopsOnTerminalState`; continuity in `CaptureBindingTest#sameIdentityBindsAndDifferentIdentityIsDrift` |
| IT-LIVE-002 | scripted (low-confidence signals) | `ON_LOW_CONFIDENCE`: passive below threshold → challenges → `PASSED` | 2, 4, 5 | implemented (scripted): `LivenessOrchestratorTest#lowConfidenceSubjectPassesViaChallenges`; `LivenessGateTest#passiveThenChallengeThenPassedDrivesUiAndAudit` |
| IT-LIVE-003 | scripted, OPERATOR policy | ALWAYS: the drawn challenges (default 2-3) then `PASSED`; match gate opened | 6 | partial: `LivenessOrchestratorTest#timingVetoIgnoredWhenTimingSignalDisabled` (OPERATOR, ALWAYS, one challenge, `PASSED`); `HeadlessSessionTest#liveSubjectPassesTheOperatorChallengesAndIsRecorded` (needs models). Missing: no OPERATOR run with the default 2-3 challenges in `build`; the match gate is tested only in `FaceMatcherTest` (Android, not in CI). |
| IT-LIVE-004 | scripted, SUPERVISOR policy | three challenges (default min = max = 3), parallax consistent, `PASSED` | 7 | partial: `LivenessOrchestratorTest#alwaysModeRunsChallengesAfterPassivePass` (SUPERVISOR, one depth-revealing challenge). Missing: no run with three challenges and parallax; parallax is tested only at signal level (ENG-S5-001). |
| IT-PAD-001 | `print-attack.json`; scripted | `FAILED(PAD_DETECTED)` class PRINT, generic UI message, audit has detail | 3 | implemented (scripted): `LivenessOrchestratorTest#photoAttackIsVetoedByCnnTexture`; generic message in `LivenessMessagesTest#securitySensitiveReasonsMapToTheGenericMessage`, `LivenessGateTest#failedAttemptsRetryUntilLockedWithGenericMessageForPad`; real pipeline in `CorpusScoreMainTest#scoresSyntheticCorpusWithRealPipeline` (needs models; asserts only "not LIVE") |
| IT-PAD-002 | `screen-replay.json`; scripted | `FAILED(PAD_DETECTED)` class SCREEN_REPLAY | 3 | implemented (scripted): `LivenessOrchestratorTest#screenAttackIsVetoedByMoire` |
| IT-PAD-003 | `video-replay.json`; e2e replay subject | fails by veto, challenge timing (`ACTIVE_CHALLENGE_FAILED` / `CHALLENGE_TIMEOUT`) or threshold; never `PASSED` | 3, 4 | partial: `LivenessOrchestratorTest#replayFailsChallengeTiming` (scripted); `InteractiveChallengeTest#threeChallengeSequences` (needs models) never passes a prompt-ignoring replay. Missing: with two challenges the replay passes in up to a quarter of draws, and `#twoChallengeSequences` asserts only that bound. |
| IT-PAD-004 | none | paper mask: never `PASSED` | 3 | not implemented |
| IT-PAD-005 | none (scripted sources) | deepfake or injected stream: never `PASSED`; injection or rPPG signal recorded | 3 | partial: injection in `FrameSourceBridgeTest#unsignedFrameFromSigningDeviceFailsAsInjection`, `ParallaxInjectionQualityTest#injectionVetoesOnlyForSystemCameraWithVirtualDevice`, `SignedPreviewTest`, `HeadlessSessionTest#injectedStreamIsVetoedAsAnAttack` (needs models). Missing: no deepfake content and no rPPG assertion at session level. |
| IT-SWAP-001 | scripted (impostor embedding) | `FAILED(IDENTITY_DRIFT)` | 3 | implemented (scripted): `LivenessOrchestratorTest#identitySwapMidChallengeFails` |
| IT-SWAP-002 | none (desktop `CaptureBinding`) | session `PASSED`, capture continuity rejects, audit `IDENTITY_DRIFT` | 3, 5 | implemented: `CaptureBindingTest#sameIdentityBindsAndDifferentIdentityIsDrift`, `#edgeCasesAreReportedNotThrown` (fake engine) |
| IT-DEV-001 | none (closed port, scripted source) | `FAILED(DEVICE_UNAVAILABLE)` | 1, 9 | implemented: `FrameSourceBridgeTest#deviceExceptionsOnStartFailTheAttempt`, `#streamEndFailsLiveAttempt`, `#healthEventsMapToHintsAndFailures`; `SbiStreamFrameSourceTest#deviceAbsentIsReportedAsUnavailableAndFails`; `LivenessGateTest#deviceDisconnectBecomesADeviceFailureResult` |
| IT-DEV-002 | `disconnect-mid-session.json`; scripted health events | `RECONNECTING` hint, reconnect within timeout → continues; beyond → `CHALLENGE_TIMEOUT` | 1, 9 | partial: `LivenessOrchestratorTest#deviceLifecycle`, `FrameSourceBridgeTest#healthEventsMapToHintsAndFailures` (hint set and cleared); timeout without frames in `#challengeTimeoutThenRetryThenLock`. Missing: no test reconnects during `ACTIVE_CHALLENGE`; the scenario's expectation is not asserted. |
| IT-DEV-003 | `stall.json`; mock and SBI stall fault | `DEGRADED` then reconnect; `tick()` keeps timers | 1, 9 | implemented: `SbiStreamFrameSourceTest#stallIsDetectedThenReconnectsAndRecovers`, `MockFaceFrameSourceTest#stallAndDisconnectFaultsBecomeHealthTransitions`, `FrameSourceBridgeTest#tickFiresTimeoutsWithoutFrames`, `ScenarioRunnerTest#healthEventsTickTheSession` |
| IT-DEV-004 | mock `CORRUPT_FRAME` fault; scripted | a frame the analyzer returns no analysis for fails the attempt with `INVALID_FRAME_DATA`; isolated corrupt frames are not skipped | 1, 9 | implemented: `LivenessOrchestratorTest#engineErrorsAndInvalidFrames` (an analyzer that rejects the frame with `IllegalArgumentException`, as the ONNX decoder does, now fails the attempt with `INVALID_FRAME_DATA`; other exceptions are `ENGINE_ERROR`), `MockFaceFrameSourceTest#corruptFrameTruncatesPixels`, `ImageOpsTest` |
| IT-DEV-005 | none | wrong SBI spec version: `FAILED(DEVICE_UNAVAILABLE)` with protocol detail in audit | 1 | not implemented (the SBI adapter reads `specVersion` but does not reject it) |
| IT-ERR-001 | `no-face.json`; scripted | `FACE_NOT_DETECTED` after acquisition timeout | 9 | implemented (scripted): `LivenessOrchestratorTest#noFaceGuidanceThenTimeout`, `FrameSourceBridgeTest#tickFiresTimeoutsWithoutFrames` |
| IT-ERR-002 | `multiple-faces.json`; scripted | `MULTIPLE_FACES`; hint `ONLY_ONE_FACE` during | 9 | implemented (scripted): `LivenessOrchestratorTest#multipleFacesGuidanceThenTimeout` |
| IT-ERR-003 | scripted (poor quality) | hints during; `POOR_FACE_QUALITY` after timeout | 9 | implemented (scripted): `LivenessOrchestratorTest#poorQualityGivesPositioningHints`, `QualityGateAndEvidenceTest#worstSubScoreChoosesHint`; `POOR_FACE_QUALITY` failure via `SpecConformanceTest` (`AcquireFailed`) |
| IT-ERR-004 | scripted | `CHALLENGE_TIMEOUT` | 9 | implemented (scripted): `LivenessOrchestratorTest#challengeTimeoutThenRetryThenLock` |
| IT-ERR-005 | scripted detector | wrong action: `ACTIVE_CHALLENGE_FAILED` | 9 | partial: `UnpromptedActionMonitorTest#reportsAWrongHeadTurn` (monitor level); `SpecConformanceTest` maps a failed detector outcome to `ACTIVE_CHALLENGE_FAILED`. Missing: no orchestrator test feeds a wrong action and asserts the failure. |
| IT-ERR-006 | scripted (analyzer throws) | `ENGINE_ERROR`; no capture | 9 | implemented (scripted): `LivenessOrchestratorTest#engineErrorsAndInvalidFrames`, `LivenessGateTest#sessionOpenFailureIsReportedAsEngineError`; capture refused for a non-LIVE result in `CaptureBindingTest#edgeCasesAreReportedNotThrown` |
| IT-RET-001 | none | attempts 1 and 2 fail, 3 passes; `attemptsUsed = 3` | 5 | partial: `SpecConformanceTest` random traces include retries that end in `PASSED`, with attempt counts compared with the spec. Missing: no deterministic fail, fail, pass test. |
| IT-RET-002 | scripted | `LOCKED`, `MAX_RETRIES_REACHED(onMaxRetries)` | 5, 9 | implemented (scripted): `LivenessOrchestratorTest#challengeTimeoutThenRetryThenLock`; `LivenessGateTest#failedAttemptsRetryUntilLockedWithGenericMessageForPad` |
| IT-RET-003 | none | supervisor session passes, resident assisted session with accessibility pool passes, audit has `SUPERVISOR_OVERRIDE` | 5, 10 | partial: `LivenessGateTest#supervisorOverrideIsAuditedAndRunsAnAssistedSession` (override audited as `SUPERVISOR_OVERRIDE` with both references; the new session is an assisted capture). Missing: the supervisor's own liveness session before the override is not chained in one test |
| IT-OFF-001 | full suite | run with networking disabled at OS level; identical outcomes; no socket opened by liveness modules | 2, 6, 7 | not implemented |
| IT-POL-001 | scripted, `enabled = false` | no session opened (desktop gate) or immediate `PASSED` with `POLICY_DISABLED`; audited (`POLICY_DISABLED` on desktop, `SESSION_PASSED(policyDisabled=true)` in core); camera never started | 8 | implemented: `LivenessGateTest#disabledPolicyBypassesWithAnAuditedResult`, `LivenessOrchestratorTest#policyDisabledPassesImmediatelyButIsAuditable`, `FrameSourceBridgeTest#disabledPolicyNeverStartsTheSource` |
| IT-POL-002 | per-workflow policies | same corpus item yields different outcomes under RESIDENT vs SUPERVISOR policy as predicted | 8 | not implemented |

### 2.5 Client (CL) — as designed

| Id | Platform | Case | Mandatory task | Status |
|---|---|---|---|---|
| CL-D-001 | Desktop TestFX | Resident capture: every state S0–S9 of the UX spec rendered with the exact copy; `rcapture` not invoked before `PASSED` | 5, 10 | partial: no TestFX. `LivenessGateTest#passiveThenChallengeThenPassedDrivesUiAndAudit` drives a recording UI; `LivenessMessagesTest#problemStatementTextsArePresentInEnglish` checks the copy; `CaptureBindingTest#edgeCasesAreReportedNotThrown` refuses binding before a LIVE result. Missing: rendering of each state. |
| CL-D-002 | Desktop | Operator auth: match service not called before `PASSED`; lock after max retries | 6, 10 | partial: lock in `LivenessGateTest#failedAttemptsRetryUntilLockedWithGenericMessageForPad` (OPERATOR). Missing: match-service ordering. |
| CL-D-003 | Desktop | Supervisor auth: same with stricter policy | 7, 10 | not implemented |
| CL-A-001..003 | Android Espresso / Flutter | Same three flows with the mock camera source on an emulator | 5–7, 10 | partial (not in CI): `liveness_controller_test.dart` (controller and overlay against a fake host), `SessionDtoMapperTest`, `FaceMatcherTest`, `PolicyJsonTest`. Missing: Espresso, emulator, mock camera, the three flows. |
| CL-I18N-001 | both | Every `liveness.*` key present in every supported locale bundle | 10 | partial: desktop in `LivenessMessagesTest#allBundlesContainEveryKeyAndNothingIsMissingInTranslations` (en, fr, ar). Missing: the Flutter ARB files are not checked for completeness. |
| CL-A11Y-001 | both | Screen-reader announcements on every state change; no colour-only information (automated contrast and pattern check) | 10 | not implemented |

### 2.6 Evaluation (EV)

| Id | Case | Status |
|---|---|---|
| EV-001 | `CorpusScoreMain` → `scores.jsonl` → `IsoReportMain` on the held-out subject-disjoint split: APCER per PAI species with Wilson 95 % intervals, BPCER overall and per group slice (suppressed below 5 participants), ACER, BPCER@APCER 5 % and 10 % with thresholds, APNRR/BPNRR. Metric code is unit-tested against hand-computed values (`RateTest`, `Iso30107MetricsTest`, `GroupSlicesTest`, `IsoReportMainTest`). The scorer is tested end to end with the real orchestrator and ONNX engine on a synthetic device-mock corpus (`CorpusScoreMainTest`). | implemented (tooling; the end-to-end scorer test needs models). No consented corpus. A digital proxy (39 adults in openly licensed video, 5 simulated attack species, passive and challenge replay) was run on 24 Oct 2026: `docs/testing/proxy-eval/README.md`. It is not physical PAI testing. |
| EV-002 | Processing-time histograms per platform | partial: `LatencyBenchmarkTest` checks the percentile code; `OnnxEngineIntegrationTest#fullFrameLatencyOn640x480` logs desktop latency (needs models). Missing: no per-platform histogram. |
| EV-003 | Regression gate: fail CI if worst-species APCER or BPCER rises more than 2 points against the last published report | partial: `RedTeamGateMainTest` (nightly `redteam.yml` fails when a variant the baseline rejected is now accepted, and keeps an APCER/BPCER/ACER trend); `IsoReportMainTest#writesReportsAndGatesOnAcer` (`--max-acer`). Missing: no 2-point APCER/BPCER delta gate against a published report. |

### 2.7 Interop (IO)

| Id | Case | Status |
|---|---|---|
| IO-001 | Conformance: frames delivered at or near `targetFps` with monotonic `sequence` and `timestampNanos` | partial: `FrameSourceConformanceTest#framesHaveMonotonicSequenceAndTimestamps`. Missing: the suite does not check the rate (only per adapter: `MockFaceFrameSourceTest#deliversAllFramesOfANonLoopingScenarioThenEnds`, `VendorSdkFrameSourceTest#slowSinkDropsOldestAndTargetFpsDecimates`). |
| IO-002 | Conformance: declared `PixelFormat` matches buffer size and layout | partial: `FrameSourceConformanceTest#capabilitiesAreNonNullAndConsistentWithFrames` (format declared, dimensions match, buffer not empty). Missing: buffer size against format and dimensions (checked only in `MockFaceFrameSourceTest`). |
| IO-003 | Conformance: `stop()` idempotent; `onEnd()` exactly once per `start()` | implemented: `FrameSourceConformanceTest#startAndStopAreIdempotentAndOnEndFiresOncePerStart`, `#stopWhileDeliveringDoesNotThrowOrDeliverAfterEnd`, `#closeBehavesLikeStop` |
| IO-004 | Conformance: faults are signalled through `onHealth` and `DeviceException.Kind`, never by throwing from `onFrame` | partial: `FrameSourceConformanceTest#healthTransitionsAreSane` (legal transitions, reasons present). Missing from the suite: fault injection; covered per adapter in `SbiStreamFrameSourceTest`, `MockFaceFrameSourceTest`, `VendorSdkFrameSourceTest`. |
| IO-005 | Conformance: reconnect honours `reconnectAttempts` and backoff bounds | partial: `SbiStreamFrameSourceTest#permanentDisconnectEndsInFailedAfterRetries` (two attempts, then `FAILED`), `VendorSdkFrameSourceTest#openFailuresAreRetriedThenGiveUpWithOneOnEnd`. Missing: not in the conformance suite; backoff timing not checked. |
| IO-006 | `device-mock` and `device-sbi-stream` (against `device-mock`) both pass IO-001..005 | partial: the suite runs for `MockFaceFrameSourceConformanceTest`, `MockMjpegConformanceTest`, `SbiStreamConformanceTest` and `VendorSdkConformanceTest` (`MockMdsConformanceTest` is opt-in). Missing: the suite lacks the IO-001 rate check and IO-005. |

### 2.8 Chaos (CH) — as designed

| Id | Case | Status |
|---|---|---|
| CH-001 | Random fault injection across 1,000 scenario runs; invariant: never `PASSED` on an attack item, never a hang beyond `sessionTimeoutMillis` | partial: `SpecConformanceTest` injects random device faults, engine faults and timeouts at orchestrator level and checks the invariants. Missing: no random fault injection across mock-device scenario runs. |
| CH-002 | Thermal throttling simulation on Android: fps drops, rPPG disabled, outcomes unchanged for live items | not implemented (`FrameSkipperTest` covers frame skipping only) |
| CH-003 | Low-memory kill mid-session: on restart the session is gone, audit has `SESSION_FAILED(CANCELLED)` or nothing; no partial packet | not implemented |

### 2.x Suites added after the plan was written

These suites cover work beyond the original plan. They are not counted in the status summary above.

| ID | Suite | What it checks | Status |
|---|---|---|---|
| SP-001 | `device-sbi-stream` `SignedPreviewTest`; `liveness-core` `FrameSourceBridgeTest#unsignedFrameFromSigningDeviceFailsAsInjection` | Signed preview stream end to end over HTTP: signed frames verify; modified, replayed, stripped and impostor streams lose their signature; an unsigned frame from a signing device ends the session as `ATTACK`/`INJECTION` | implemented |
| IO-MDS-001 | `MockMdsFixtureTest` (always), `MockMdsInteropTest`, `MockMdsConformanceTest` (opt-in, live MockMDS 1.2.0.2) | Interoperability with MOSIP's reference MockMDS: discovery, device info, face stream framing, refusals, reconnect | implemented (fixtures in CI; live run via `tools/interop-mockmds.sh`) |
| FV-CONF-001 | `liveness-core` `SpecConformanceTest`; `formal/conformance.sh` | The Java orchestrator replayed against random walks of the TLA+ spec, state compared after every step; generated phase/transition tables kept in sync | implemented |
| RT-001 | `testkit` `RedTeamVariantTest`, `RedTeamGateMainTest`, `RedTeamFixtureReplayTest`; `.github/workflows/redteam.yml` | Nightly synthetic attack rendering, scoring, regression gate against the baseline, trend | implemented (nightly in CI) |
| CAL-001 | `calibration` `CalibrationJobTest`, `EndToEndCalibrationLoopTest`; `telemetry` `CalibrationTelemetryTest` | Differentially private score histograms, budget ledger, threshold re-estimation, signed policy update verified by the client | implemented (simulation) |
| AV-001 | `liveness-av` `SpokenCodeTest`, `AvFrameAnalyzerTest`, `AvOrchestratorEndToEndTest`, `AvCorpusEvaluationTest` (opt-in) | Spoken-code audio-visual liveness: code generation, digit recognition, lip/audio synchrony, verdict through the orchestrator | implemented (synthetic speech; corpus run opt-in) |
| POL-TIGHT-001 | `integration-desktop` `DesktopLivenessConfigTest#localConfigurationCanTightenButNotLoosen` | Local configuration may only tighten the policy; loosening values are ignored and logged | implemented |
| ACQ-001 | `liveness-core` `LivenessOrchestratorTest#acquisitionTimeoutFailsFasterThanTheSessionTimeout` | A faceless attempt ends at the acquisition timeout (15 s) rather than the session timeout (60 s) | implemented |

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

* `device-mock` scenario files: `device-mock/src/main/resources/scenarios/`,
  with expectations in `testkit/src/main/resources/expectations/` (names as in
  §2.4 where a file exists).
* Corpus: not in the repository. Each item is a folder of numbered
  `.jpg`/`.png` frames named by its item ID (`docs/corpus-ethics.md` §10.1),
  with an optional `timestamps.txt`; labels, PAI species and consented group
  tags come from the capture log CSV (`docs/corpus/capture-log-template.csv`).
  `CorpusScoreMainTest` renders a small synthetic corpus from `device-mock`
  (no real people) for smoke tests. Commands are in §6.
* Model files: not committed. Download them into `liveness-engine-onnx/models/`
  as described in its `README.md`, or install a signed bundle
  (`docs/design/11-model-lifecycle.md`). Tests that need them are skipped
  without them.
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
