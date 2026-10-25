---------------------------- MODULE LivenessOrchestrator ----------------------------
(***************************************************************************)
(* TLA+ model of the mosip-vivid liveness session orchestrator.            *)
(*                                                                         *)
(* Source of truth for phases and failure reasons:                         *)
(*   liveness-api: io.mosip.vivid.api.SessionState.Phase                   *)
(*                 io.mosip.vivid.api.FailureReason                        *)
(*                 io.mosip.vivid.api.LivenessPolicy (ActiveMode, retries) *)
(*   PLAN.md section 2.3 (session state machine)                           *)
(*                                                                         *)
(* The model is the ORCHESTRATOR + its ENVIRONMENT.  The environment       *)
(* (device, engine signals, the person in front of the camera, and the     *)
(* Registration Client that may call retry()/cancel()) is fully            *)
(* nondeterministic: every frame may carry any fused score, may raise a    *)
(* PAD hard veto, may raise an identity-drift flag, and every challenge    *)
(* frame may report any action outcome.  The orchestrator is deterministic *)
(* given those inputs.                                                     *)
(*                                                                         *)
(* One "step" in PASSIVE_EVALUATING / ACTIVE_CHALLENGE corresponds to one  *)
(* call of LivenessSession.feed(frame): the engine's FrameAnalysis is      *)
(* consumed and the new SessionState is decided atomically.                *)
(*                                                                         *)
(* Counting convention: `attempt` is the number of attempts STARTED, so    *)
(* SessionState.attempt() = attempt and SessionState.maxAttempts() =       *)
(* MaxRetries; canRetry() = (phase = FAILED /\ attempt < MaxRetries).      *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS
    MaxRetries,         \* LivenessPolicy.maxRetries   (total attempts allowed)
    MinChallenges,      \* LivenessPolicy.minChallenges
    MaxChallenges,      \* LivenessPolicy.maxChallenges
    ScoreMax,           \* fused liveness score is modelled on the integer scale 0..ScoreMax
    Threshold,          \* LivenessPolicy.passiveThreshold on the same scale
    PassiveMinFrames,   \* LivenessPolicy.passiveMinFrames (frames observed before deciding)
    MinLatencyTicks,    \* S6 timing window: an action completing earlier than this is "too fast"
    TimeoutTicks        \* LivenessPolicy.challengeTimeoutMillis, in frames

ASSUME /\ MaxRetries \in Nat \ {0}
       /\ MinChallenges \in Nat \ {0}
       /\ MaxChallenges \in Nat /\ MinChallenges <= MaxChallenges
       /\ ScoreMax \in Nat /\ Threshold \in 0..ScoreMax
       /\ PassiveMinFrames \in Nat \ {0}
       /\ MinLatencyTicks \in Nat /\ TimeoutTicks \in Nat
       /\ MinLatencyTicks < TimeoutTicks

\* SessionState.Phase
Phases == {"IDLE", "DEVICE_CONNECTING", "FACE_ACQUIRING", "PASSIVE_EVALUATING",
           "ACTIVE_CHALLENGE", "PASSED", "FAILED", "LOCKED", "CANCELLED"}
TerminalPhases == {"PASSED", "LOCKED", "CANCELLED"}         \* SessionState.isTerminal()
EvaluatingPhases == {"PASSIVE_EVALUATING", "ACTIVE_CHALLENGE"}

\* LivenessPolicy.ActiveMode
Modes == {"NEVER", "ON_LOW_CONFIDENCE", "ALWAYS"}

\* FailureReason (plus NONE for "no failure recorded")
DeviceReasons  == {"DEVICE_UNAVAILABLE", "DEVICE_CONNECTION_FAILURE", "CAMERA_UNAVAILABLE"}
AcquireReasons == {"FACE_NOT_DETECTED", "MULTIPLE_FACES", "POOR_FACE_QUALITY"}
EngineReasons  == {"INVALID_FRAME_DATA", "ENGINE_ERROR"}
Reasons == DeviceReasons \cup AcquireReasons \cup EngineReasons \cup
           {"LIVENESS_BELOW_THRESHOLD", "PAD_DETECTED", "ACTIVE_CHALLENGE_FAILED",
            "CHALLENGE_TIMEOUT", "MAX_RETRIES_EXCEEDED", "IDENTITY_DRIFT",
            "SESSION_TIMEOUT", "CANCELLED", "NONE"}

\* Action-detector status as observed on one analysed frame ("NONE" = action not yet complete).
\* A challenge TIMEOUT is not a frame outcome: it is a clock event (ChallengeTimeout below) that
\* the orchestrator detects before it analyses the next frame.
Outcomes == {"NONE", "PASSED", "FAILED"}

Scores == 0..ScoreMax

VARIABLES
    phase,              \* current SessionState.Phase
    failure,            \* FailureReason of the current/last failure, or "NONE"
    attempt,            \* attempts started so far (SessionState.attempt)
    activeMode,         \* policy.activeMode, fixed for the session
    fusedScore,         \* last fused liveness score (SessionState.passiveScore), higher = more live
    padVeto,            \* a signal raised SignalScore.hardVeto in this attempt (sticky per attempt)
    identityDrift,      \* S7 identity continuity failed in this attempt (sticky per attempt)
    passiveBelow,       \* passive decision in this attempt saw fusedScore < Threshold
    frames,             \* frames consumed in the passive window of this attempt
    seqLen,             \* length of the challenge sequence drawn for this attempt (0 = none drawn)
    challengeIdx,       \* 1-based index of the challenge currently issued (0 = none)
    challengesPassed,   \* challenges verified in this attempt
    ticks               \* frames elapsed since the current challenge was issued

vars == <<phase, failure, attempt, activeMode, fusedScore, padVeto, identityDrift,
          passiveBelow, frames, seqLen, challengeIdx, challengesPassed, ticks>>

TypeOK ==
    /\ phase \in Phases
    /\ failure \in Reasons
    /\ attempt \in 0..MaxRetries
    /\ activeMode \in Modes
    /\ fusedScore \in Scores
    /\ padVeto \in BOOLEAN
    /\ identityDrift \in BOOLEAN
    /\ passiveBelow \in BOOLEAN
    /\ frames \in 0..PassiveMinFrames
    /\ seqLen \in 0..MaxChallenges
    /\ challengeIdx \in 0..MaxChallenges
    /\ challengesPassed \in 0..MaxChallenges
    /\ ticks \in 0..TimeoutTicks

(***************************************************************************)
(* Policy predicate: how many verified challenges the policy demands        *)
(* before PASSED is admissible in the current attempt.                      *)
(***************************************************************************)
RequiredChallenges ==
    IF activeMode = "NEVER" THEN 0
    ELSE IF activeMode = "ALWAYS" \/ passiveBelow THEN MinChallenges
    ELSE 0

(***************************************************************************)
(* Initial state: a session has been opened (LivenessEngine.newAnalyzer +   *)
(* orchestrator constructed) but start() not yet called.                    *)
(***************************************************************************)
Init ==
    /\ phase = "IDLE"
    /\ failure = "NONE"
    /\ attempt = 0
    /\ activeMode \in Modes
    /\ fusedScore = 0
    /\ padVeto = FALSE
    /\ identityDrift = FALSE
    /\ passiveBelow = FALSE
    /\ frames = 0
    /\ seqLen = 0
    /\ challengeIdx = 0
    /\ challengesPassed = 0
    /\ ticks = 0

\* Per-attempt bookkeeping cleared on start and on retry.
ResetAttempt ==
    /\ padVeto' = FALSE
    /\ identityDrift' = FALSE
    /\ passiveBelow' = FALSE
    /\ frames' = 0
    /\ seqLen' = 0
    /\ challengeIdx' = 0
    /\ challengesPassed' = 0
    /\ ticks' = 0
    /\ fusedScore' = 0

\* Helper: record a failure of the current attempt.
Fail(reason) ==
    /\ phase' = "FAILED"
    /\ failure' = reason

(***************************************************************************)
(* CLIENT steps: the Registration Client drives start/retry/cancel.         *)
(***************************************************************************)
Start ==
    /\ phase = "IDLE"
    /\ attempt' = 1
    /\ phase' = "DEVICE_CONNECTING"
    /\ failure' = "NONE"
    /\ ResetAttempt
    /\ UNCHANGED activeMode

\* LivenessSession.retry(): allowed only while canRetry() holds.
Retry ==
    /\ phase = "FAILED"
    /\ attempt < MaxRetries
    /\ attempt' = attempt + 1
    /\ phase' = IF failure \in DeviceReasons THEN "DEVICE_CONNECTING" ELSE "FACE_ACQUIRING"
    /\ failure' = "NONE"
    /\ ResetAttempt
    /\ UNCHANGED activeMode

\* LivenessSession.cancel(): from any non-terminal phase.
Cancel ==
    /\ phase \notin TerminalPhases
    /\ phase' = "CANCELLED"
    /\ failure' = "CANCELLED"
    /\ UNCHANGED <<attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow,
                   frames, seqLen, challengeIdx, challengesPassed, ticks>>

(***************************************************************************)
(* DEVICE steps.                                                            *)
(***************************************************************************)
DeviceConnected ==
    /\ phase = "DEVICE_CONNECTING"
    /\ phase' = "FACE_ACQUIRING"
    /\ UNCHANGED <<failure, attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow,
                   frames, seqLen, challengeIdx, challengesPassed, ticks>>

\* The device can fail while connecting (including the connection timeout, which reports
\* DEVICE_CONNECTION_FAILURE) and at any later point of a live attempt (camera unplugged mid-session).
DeviceFailed ==
    /\ phase \in {"DEVICE_CONNECTING", "FACE_ACQUIRING"} \cup EvaluatingPhases
    /\ \E r \in DeviceReasons : Fail(r)
    /\ UNCHANGED <<attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow,
                   frames, seqLen, challengeIdx, challengesPassed, ticks>>

(***************************************************************************)
(* FACE ACQUISITION steps (quality gate S9).                                *)
(***************************************************************************)
FaceAcquired ==
    /\ phase = "FACE_ACQUIRING"
    /\ phase' = "PASSIVE_EVALUATING"
    /\ frames' = 0
    /\ UNCHANGED <<failure, attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow,
                   seqLen, challengeIdx, challengesPassed, ticks>>

\* The attempt times out in FACE_ACQUIRING; the reported reason is the last quality-gate failure
\* (FACE_NOT_DETECTED when no frame was ever gated).
AcquireFailed ==
    /\ phase = "FACE_ACQUIRING"
    /\ \E r \in AcquireReasons : Fail(r)
    /\ UNCHANGED <<attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow,
                   frames, seqLen, challengeIdx, challengesPassed, ticks>>

(***************************************************************************)
(* PASSIVE window.  Each frame re-samples the fused score and may raise a   *)
(* veto or drift flag.  Flags are evaluated BEFORE the score (hard-veto     *)
(* rule, PLAN 4.3).  Identity drift is checked before the PAD veto because  *)
(* the orchestrator compares the embedding before it fuses the signals;     *)
(* both end the attempt with an ATTACK decision.                            *)
(***************************************************************************)
PassiveFrame ==
    /\ phase = "PASSIVE_EVALUATING"
    /\ frames < PassiveMinFrames
    /\ \E s \in Scores, v \in BOOLEAN, d \in BOOLEAN :
        LET veto  == padVeto \/ v
            drift == identityDrift \/ d
        IN
        /\ fusedScore' = s
        /\ padVeto' = veto
        /\ identityDrift' = drift
        /\ frames' = frames + 1
        /\ IF drift THEN Fail("IDENTITY_DRIFT")
           ELSE IF veto THEN Fail("PAD_DETECTED")
           ELSE phase' = "PASSIVE_EVALUATING" /\ failure' = failure
    /\ UNCHANGED <<attempt, activeMode, passiveBelow, seqLen, challengeIdx, challengesPassed, ticks>>

\* Orchestrator decision at the end of the passive window (no new frame involved).
StartActive ==
    /\ \E n \in MinChallenges..MaxChallenges :        \* SecureRandom draw of the sequence length
        /\ seqLen' = n
    /\ challengeIdx' = 1
    /\ challengesPassed' = 0
    /\ ticks' = 0
    /\ phase' = "ACTIVE_CHALLENGE"
    /\ failure' = failure

PassiveDecide ==
    /\ phase = "PASSIVE_EVALUATING"
    /\ frames = PassiveMinFrames
    /\ ~padVeto /\ ~identityDrift
    /\ IF fusedScore >= Threshold
       THEN IF activeMode = "ALWAYS"
            THEN passiveBelow' = FALSE /\ StartActive
            ELSE phase' = "PASSED" /\ failure' = failure
                 /\ UNCHANGED <<passiveBelow, seqLen, challengeIdx, challengesPassed, ticks>>
       ELSE IF activeMode = "NEVER"
            THEN passiveBelow' = TRUE /\ Fail("LIVENESS_BELOW_THRESHOLD")
                 /\ UNCHANGED <<seqLen, challengeIdx, challengesPassed, ticks>>
            ELSE passiveBelow' = TRUE /\ StartActive
    /\ UNCHANGED <<attempt, activeMode, fusedScore, padVeto, identityDrift, frames>>

(***************************************************************************)
(* ACTIVE challenge.  One frame = one observation of the action detector    *)
(* plus the continuously re-evaluated passive signals ("re-evaluate         *)
(* liveness during active verification").                                   *)
(***************************************************************************)
ChallengeFrame ==
    /\ phase = "ACTIVE_CHALLENGE"
    /\ \E o \in Outcomes, s \in Scores, v \in BOOLEAN, d \in BOOLEAN :
        LET tooFast == o = "PASSED" /\ ticks < MinLatencyTicks   \* S6: faster than a human can react
            veto    == padVeto \/ v
            drift   == identityDrift \/ d
            passed  == challengesPassed + 1
        IN
        /\ o = "NONE" => ticks + 1 < TimeoutTicks      \* the deadline is ChallengeTimeout's job
        /\ fusedScore' = s
        /\ identityDrift' = drift
        /\ IF drift THEN
                Fail("IDENTITY_DRIFT") /\ padVeto' = veto /\ UNCHANGED <<challengeIdx, challengesPassed, ticks>>
           ELSE IF veto \/ tooFast THEN
                \* a hard veto, or the S6 timing veto (pre-recorded answer): PAD_DETECTED
                Fail("PAD_DETECTED") /\ padVeto' = TRUE /\ UNCHANGED <<challengeIdx, challengesPassed, ticks>>
           ELSE IF o = "NONE" THEN
                /\ padVeto' = veto
                /\ ticks' = ticks + 1
                /\ phase' = "ACTIVE_CHALLENGE" /\ failure' = failure
                /\ UNCHANGED <<challengeIdx, challengesPassed>>
           ELSE IF o = "FAILED" THEN
                \* the detector rejected the action (wrong or unprompted action)
                padVeto' = veto /\ Fail("ACTIVE_CHALLENGE_FAILED") /\ UNCHANGED <<challengeIdx, challengesPassed, ticks>>
           ELSE \* o = "PASSED" inside the human timing window
                /\ padVeto' = veto
                /\ challengesPassed' = passed
                /\ IF challengeIdx < seqLen THEN
                        /\ challengeIdx' = challengeIdx + 1      \* issue the next drawn challenge
                        /\ ticks' = 0
                        /\ phase' = "ACTIVE_CHALLENGE" /\ failure' = failure
                   ELSE IF s >= Threshold THEN
                        \* whole drawn sequence verified (seqLen >= MinChallenges) and the
                        \* re-evaluated fused score still clears the threshold
                        phase' = "PASSED" /\ failure' = failure
                        /\ UNCHANGED <<challengeIdx, ticks>>
                   ELSE \* sequence exhausted and fused liveness below threshold
                        Fail("LIVENESS_BELOW_THRESHOLD") /\ UNCHANGED <<challengeIdx, ticks>>
    /\ UNCHANGED <<attempt, activeMode, passiveBelow, frames, seqLen>>

\* The current challenge's deadline passes.  Detected by tick() or by the next feed() before the
\* frame is analysed, so the frame's score and flags play no part.
ChallengeTimeout ==
    /\ phase = "ACTIVE_CHALLENGE"
    /\ Fail("CHALLENGE_TIMEOUT")
    /\ UNCHANGED <<attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow,
                   frames, seqLen, challengeIdx, challengesPassed, ticks>>

(***************************************************************************)
(* Faults that can hit any evaluating phase.                                *)
(***************************************************************************)
\* The analyzer throws (ENGINE_ERROR) or returns nothing (INVALID_FRAME_DATA) on any analysed
\* frame, including the frames of the acquisition phase.
EngineFault ==
    /\ phase \in {"FACE_ACQUIRING"} \cup EvaluatingPhases
    /\ \E r \in EngineReasons : Fail(r)
    /\ UNCHANGED <<attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow,
                   frames, seqLen, challengeIdx, challengesPassed, ticks>>

\* The per-attempt timer expires once a face was acquired (in FACE_ACQUIRING the same timer is
\* reported as the acquisition failure, see AcquireFailed).
SessionTimeout ==
    /\ phase \in EvaluatingPhases
    /\ Fail("SESSION_TIMEOUT")
    /\ UNCHANGED <<attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow,
                   frames, seqLen, challengeIdx, challengesPassed, ticks>>

(***************************************************************************)
(* Retry exhaustion: FAILED with no retries left becomes LOCKED.            *)
(* LivenessPolicy.onMaxRetries decides what the client does next (lock and  *)
(* alert, assisted capture with supervisor, exception flow); all of those   *)
(* happen outside this session, which is terminal.                          *)
(***************************************************************************)
\* The session keeps the last attempt's reason (SessionState.failure() and LivenessResult.failure());
\* the lock itself is conveyed by the phase, the MAX_RETRIES_REACHED hint and the audit event
\* MAX_RETRIES_REACHED(lastReason).  The orchestrator takes this step in the same call that
\* failed the last attempt, so a client never observes FAILED with no retries left.
Lock ==
    /\ phase = "FAILED"
    /\ attempt = MaxRetries
    /\ phase' = "LOCKED"
    /\ UNCHANGED <<failure, attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow,
                   frames, seqLen, challengeIdx, challengesPassed, ticks>>

\* Terminal states stutter forever (keeps TLC's deadlock check meaningful).
Done ==
    /\ phase \in TerminalPhases
    /\ UNCHANGED vars

ClientNext == Start \/ Retry \/ Cancel
EnvNext    == DeviceConnected \/ DeviceFailed \/ FaceAcquired \/ AcquireFailed
              \/ PassiveFrame \/ ChallengeFrame \/ ChallengeTimeout \/ EngineFault \/ SessionTimeout
CoreNext   == PassiveDecide \/ Lock

Next == ClientNext \/ EnvNext \/ CoreNext \/ Done

\* Weak fairness: anything that stays enabled eventually happens.  This models
\* "frames keep arriving", "the orchestrator always finishes a decision" and
\* "the client eventually retries or cancels a failed session".
Fairness == WF_vars(EnvNext) /\ WF_vars(CoreNext) /\ WF_vars(ClientNext)

Spec == Init /\ [][Next]_vars /\ Fairness

(***************************************************************************)
(* PROPERTIES                                                               *)
(***************************************************************************)

\* (a) SAFETY: no execution reaches PASSED unless the policy predicates hold.
PassedImpliesPolicy ==
    phase = "PASSED" =>
        /\ fusedScore >= Threshold
        /\ ~padVeto
        /\ ~identityDrift
        /\ challengesPassed >= RequiredChallenges
        /\ (activeMode = "ALWAYS" => challengesPassed >= MinChallenges)

\* (b) once a PAD veto is raised in an attempt, that attempt can never reach PASSED
NoPassAfterVeto == [][padVeto => phase' # "PASSED"]_vars

\* (c) attempts never exceed the policy limit; LOCKED only at the limit
AttemptsBounded ==
    /\ attempt <= MaxRetries
    /\ (phase = "LOCKED" => attempt = MaxRetries)

\* (d) terminal phases have no outgoing transitions
TerminalIsFinal == [][(phase \in TerminalPhases) => vars' = vars]_vars

\* (e) LIVENESS: every fair execution reaches a terminal phase
EventuallyTerminal == <>(phase \in TerminalPhases)

\* Additional structural invariants
FailedHasReason == phase = "FAILED" => failure \notin {"NONE", "MAX_RETRIES_EXCEEDED", "CANCELLED"}
LockedHasReason == phase = "LOCKED" => failure \notin {"NONE", "MAX_RETRIES_EXCEEDED", "CANCELLED"}
ActiveNeedsPolicy == phase = "ACTIVE_CHALLENGE" => activeMode # "NEVER"
ChallengeSequenceBounded ==
    phase = "ACTIVE_CHALLENGE" =>
        /\ seqLen \in MinChallenges..MaxChallenges
        /\ challengeIdx \in 1..seqLen
        /\ challengesPassed = challengeIdx - 1
        /\ ticks < TimeoutTicks
AttemptStartedBeforeWork == phase \notin {"IDLE", "CANCELLED"} => attempt >= 1

=============================================================================
