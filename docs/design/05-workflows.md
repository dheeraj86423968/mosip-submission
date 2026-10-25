# 05 — Workflow integration flows

Covers mandatory tasks 5, 6 and 7 and the three "integration flow"
deliverables. Each diagram runs from "start capture" through SBI calls, the
liveness session, UI states, audit, to the packet or authentication gate,
and includes the retry and max-retry branches. Class names of the existing
client code are those identified in `docs/recon/`; the glue code lives in
`integration-desktop` and `integration-android` (as designed).

Common legend: `S` = `LivenessSession` (liveness-core), `Src` =
`FaceFrameSource` (adapter), `SBI` = device service, `Aud` = MOSIP audit
manager via `telemetry`.

## 1. Resident face capture

Policy defaults: `passiveThreshold 0.80`, `activeMode ALWAYS` (see 04 §2 for why; `ON_LOW_CONFIDENCE` is supported),
`minChallenges 2`, `maxChallenges 3`, `maxRetries 3`,
`onMaxRetries ASSISTED_CAPTURE_WITH_SUPERVISOR`.

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator
    actor Res as Resident
    participant UI as Face capture screen
    participant Int as integration (capture controller)
    participant Pol as policy
    participant Src as FaceFrameSource
    participant S as LivenessSession
    participant SBI as SBI device
    participant Aud as Audit
    participant Pkt as Packet

    Op->>UI: Start face capture
    UI->>Int: startFaceCapture()
    Int->>Pol: policyFor(RESIDENT)
    Pol-->>Int: LivenessPolicy (signed, or compiled defaults)
    Int->>Aud: POLICY_LOADED(version, signatureOk)
    Int->>S: open(policy, ctx{RESIDENT, sessionId, nonce, locale, online, assisted=false})
    Int->>Aud: SESSION_STARTED
    Int->>Src: capabilities(), start(sink → S.feed)
    Src->>SBI: GET /info, /device
    SBI-->>Src: digitalId, L0/L1, spec version
    Src->>SBI: POST /stream
    SBI-->>Src: multipart MJPEG
    S-->>UI: DEVICE_CONNECTING → FACE_ACQUIRING (hints)
    Res->>SBI: positions face
    S-->>UI: PASSIVE_EVALUATING "Checking face liveness…"
    alt passive score ≥ 0.80 and no veto
        S->>Aud: PASSIVE_PASSED(score)
        S-->>UI: PASSED "Good, face captured successfully"
    else passive below threshold
        S->>Aud: PASSIVE_BELOW_THRESHOLD(score)
        S->>Aud: CHALLENGE_ISSUED(TURN_LEFT, 1, nonce)
        S-->>UI: ACTIVE_CHALLENGE "Please turn your head to the left"
        Res->>SBI: performs action
        S->>Aud: CHALLENGE_PASSED(latencyMs)
        S->>Aud: CHALLENGE_ISSUED(BLINK, 2, nonce)
        S-->>UI: ACTIVE_CHALLENGE "Please blink" / "Please continue"
        Res->>SBI: blinks
        S->>Aud: CHALLENGE_PASSED(latencyMs)
        S-->>UI: PASSED
    end
    S->>Aud: SESSION_PASSED(scores, evidenceHash)
    Int->>Src: stop()
    Int->>SBI: POST /rcapture (signed)
    SBI-->>Int: signed ISO 19794-5 face record
    Int->>S: verifyCaptureContinuity(signedImage)
    alt embedding distance ≤ identityDriftThreshold
        Int->>Pkt: store BIR + liveness metadata (decision, scores, bundle, nonce, evidenceHash)
        UI-->>Op: capture accepted
    else drift at capture
        Int->>Aud: IDENTITY_DRIFT(stage=CAPTURE)
        UI-->>Op: "Face verification could not be completed. Please try again."
        Note over Int: treated as a FAILED attempt, retry path below
    end
```

Failure, retry and max-retry branch (same participants):

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator
    actor Sup as Supervisor
    participant UI as Face capture screen
    participant Int as integration
    participant S as LivenessSession
    participant Aud as Audit

    S-->>UI: FAILED(reason) hint RETRY_AVAILABLE, attempt 1/3
    S->>Aud: SESSION_FAILED(reason) or PAD_DETECTED(class, score, signal)
    UI-->>Op: "We could not verify face liveness. Please try again." (+ guidance) — generic text if PAD/drift
    Op->>UI: Retry
    UI->>Int: retry()
    Int->>S: retry() → true
    S-->>UI: FACE_ACQUIRING (attempt 2/3)
    Note over S: ... second and third attempts fail ...
    S-->>UI: LOCKED, hint MAX_RETRIES_REACHED
    S->>Aud: MAX_RETRIES_REACHED(ASSISTED_CAPTURE_WITH_SUPERVISOR)
    UI-->>Op: "Face capture could not be completed. A supervisor is required to continue."
    Op->>UI: Request supervisor
    Sup->>UI: Supervisor authentication (own liveness session, see §3)
    UI->>Int: openAssistedSession(supervisorId)
    Int->>Aud: SUPERVISOR_OVERRIDE(supervisorId, residentSessionId)
    Int->>S: open(policy, ctx{RESIDENT, assisted=true}) → accessibility pool, stricter threshold
    Note over S: new session, must still reach PASSED on its own merits
    alt assisted session passes
        Int->>Int: rcapture + continuity + store BIR with assisted=true
    else assisted session also locks
        UI-->>Op: route to biometric exception flow with mandatory reason code
        Int->>Aud: SESSION_FAILED(MAX_RETRIES_EXCEEDED, exceptionReason)
    end
```

## 2. Operator authentication

Policy defaults: `passiveThreshold 0.85`, `activeMode ALWAYS`,
`minChallenges 1`, `maxChallenges 2`, `maxRetries 3`,
`onMaxRetries LOCK_AND_ALERT`.

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator
    participant UI as Auth dialog (face)
    participant Int as integration (auth controller)
    participant Src as FaceFrameSource
    participant S as LivenessSession
    participant SBI as SBI device
    participant Auth as Local auth service (1:1 match)
    participant Aud as Audit

    Op->>UI: choose face authentication
    UI->>Int: authenticate(OPERATOR, userId)
    Int->>S: open(policy OPERATOR, ctx{nonce, online?})
    Int->>Aud: SESSION_STARTED(OPERATOR, userId)
    Int->>Src: start(sink → S.feed)
    Src->>SBI: /info, POST /stream
    S-->>UI: FACE_ACQUIRING → PASSIVE_EVALUATING "Checking face liveness…"
    Note over S: ALWAYS → a challenge is issued even when passive is high
    S->>Aud: CHALLENGE_ISSUED(SMILE, 1, nonce)
    S-->>UI: ACTIVE_CHALLENGE "Please smile"
    Op->>SBI: smiles
    alt action verified in window and score ≥ 0.85
        S->>Aud: CHALLENGE_PASSED, SESSION_PASSED
        S-->>UI: PASSED
        Int->>Src: stop()
        Int->>SBI: POST /capture (signed, auth purpose)
        SBI-->>Int: signed face record
        Int->>S: verifyCaptureContinuity(signed)
        Int->>Auth: match(signed, enrolled template of userId)
        alt match
            Auth-->>UI: authenticated
            Int->>Aud: AUTH_SUCCESS(userId, livenessEvidenceHash)
        else no match
            UI-->>Op: authentication failed (existing message)
        end
    else challenge failed / timeout / veto
        S->>Aud: SESSION_FAILED(reason) / PAD_DETECTED
        S-->>UI: FAILED, attempt n/3, RETRY_AVAILABLE
        UI-->>Op: "Face verification could not be completed. Please try again."
        Op->>UI: Retry → S.retry()
    end
    opt attempt 3 fails
        S-->>UI: LOCKED
        S->>Aud: MAX_RETRIES_REACHED(LOCK_AND_ALERT)
        Int->>Int: lock face auth for this user for lockoutMillis, alert event
        UI-->>Op: "Face authentication is locked. Use password authentication or contact your supervisor."
    end
```

The match never runs unless the session is `PASSED` and the signed capture
passes continuity. Offline: identical, because the policy, engine and
models are local; only the audit upload is deferred.

## 3. Supervisor authentication

Policy defaults: `passiveThreshold 0.90`, `activeMode ALWAYS`,
`minChallenges 2`, `maxChallenges 3`, `maxRetries 2`,
`onMaxRetries LOCK_AND_ALERT`.

```mermaid
sequenceDiagram
    autonumber
    actor Sup as Supervisor
    participant UI as Supervisor auth dialog
    participant Int as integration (auth controller)
    participant Src as FaceFrameSource
    participant S as LivenessSession
    participant SBI as SBI device
    participant Auth as Local auth service
    participant Aud as Audit
    participant Flow as Calling flow (packet approval / override / EOD)

    Flow->>UI: supervisor authentication required
    Sup->>UI: face authentication
    UI->>Int: authenticate(SUPERVISOR, userId, purpose)
    Int->>S: open(policy SUPERVISOR, ctx)
    Int->>Aud: SESSION_STARTED(SUPERVISOR, purpose)
    Int->>Src: start(sink → S.feed)
    Src->>SBI: /info, POST /stream
    S-->>UI: PASSIVE_EVALUATING "Checking face liveness…"
    S->>Aud: CHALLENGE_ISSUED(NOD, 1, nonce)
    S-->>UI: "Please nod"
    Sup->>SBI: nods
    S->>Aud: CHALLENGE_PASSED
    S->>Aud: CHALLENGE_ISSUED(TURN_RIGHT, 2, nonce)
    S-->>UI: "Please turn your head to the right"
    Sup->>SBI: turns
    alt both verified, parallax consistent, score ≥ 0.90, no drift
        S->>Aud: CHALLENGE_PASSED, SESSION_PASSED
        S-->>UI: PASSED
        Int->>SBI: POST /capture (signed)
        Int->>S: verifyCaptureContinuity(signed)
        Int->>Auth: match(signed, supervisor template)
        Auth-->>Flow: supervisor authenticated, liveness evidenceHash attached to the approval record
    else any failure
        S-->>UI: FAILED attempt 1/2 → retry once
        Note over S: attempt 2 fails → LOCKED
        S->>Aud: MAX_RETRIES_REACHED(LOCK_AND_ALERT)
        Int->>Int: lock supervisor face auth, alert
        Flow-->>UI: operation not approved, alternative supervisor or password path
    end
```

Why stricter: a supervisor's authentication authorises overrides, including
assisted capture after a resident lock-out. Two challenges, a higher
threshold and only two attempts raise the cost of a presentation attack at
the point where it would do the most damage.

## 4. Where the gates are

| Workflow | Gate | Condition to pass the gate |
|---|---|---|
| Resident | `rcapture` invocation | `session.state().phase == PASSED` |
| Resident | BIR stored in packet | continuity check on the signed image passes |
| Operator | `capture` + 1:1 match | `PASSED` and continuity |
| Supervisor | `capture` + 1:1 match | `PASSED` and continuity |
| All | policy `enabled = false` | the integration skips session creation, stores `livenessSkipped = POLICY_DISABLED` in metadata and emits `POLICY_LOADED(enabled=false)` so the omission is visible in audit |

## 5. Consistency across workflows and platforms

The three flows differ only in the `Workflow` passed to `open()`, the policy
it selects, and which signed SBI call follows `PASSED`. Everything between
`start()` and the terminal state is the same code path on desktop and
Android, which is how the "consistent behaviour" requirement is met rather
than merely intended.
