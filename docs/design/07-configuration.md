# 07 — Configuration design

Covers mandatory task 8 and the "configuration design" deliverable. The
authority for the Java shape is `io.mosip.vivid.api.LivenessPolicy`
(frozen); the `policy` module reads the documents described here and
produces one `LivenessPolicy` per `Workflow`. Key names below are the
contract the `policy` module implements; where the module is still being
written the names are *as designed* and the module's tests must use them.

## 1. Two representations, one document

The policy is a single JSON document. It can be delivered either as one
property holding the document, or flattened into individual properties so
it rides the existing `registration-default.properties` and global-param
sync without any change to the config framework.

### 1.1 JSON document

```json
{
  "schemaVersion": "1.0",
  "policyVersion": "2026.10-country-A",
  "issuedAt": "2026-10-01T00:00:00Z",
  "workflows": {
    "RESIDENT": {
      "enabled": true,
      "passiveThreshold": 0.997,
      "padHardThreshold": 0.90,
      "passiveWindowMillis": 2500,
      "passiveMinFrames": 8,
      "active": {
        "mode": "ALWAYS",
        "minChallenges": 2,
        "maxChallenges": 3,
        "pool": ["BLINK", "SMILE", "TURN_LEFT", "TURN_RIGHT", "OPEN_MOUTH"],
        "accessibilityPool": ["BLINK", "SMILE", "HOLD_STILL"],
        "requireDepthRevealingChallenge": true,
        "timeoutMillis": 8000,
        "minActionLatencyMillis": 150,
        "maxActionLatencyMillis": 3000
      },
      "maxRetries": 3,
      "onMaxRetries": "ASSISTED_CAPTURE_WITH_SUPERVISOR",
      "sessionTimeoutMillis": 60000,
      "minFaceQuality": 0.40,
      "identityDriftThreshold": 0.45,
      "requireIdentityContinuity": true,
      "signals": {
        "rppg":     { "enabled": true, "weight": 0.8 },
        "parallax": { "enabled": true, "weight": 1.2 }
      }
    },
    "OPERATOR":   { "enabled": true, "passiveThreshold": 0.997, "active": { "mode": "ALWAYS", "minChallenges": 2, "maxChallenges": 3 }, "maxRetries": 3, "onMaxRetries": "LOCK_AND_ALERT" },
    "SUPERVISOR": { "enabled": true, "passiveThreshold": 0.997, "active": { "mode": "ALWAYS", "minChallenges": 3, "maxChallenges": 3 }, "maxRetries": 2, "onMaxRetries": "LOCK_AND_ALERT" }
  },
  "device": { "minFps": 10, "maxQueuedFrames": 4, "stallTimeoutMillis": 3000, "reconnectAttempts": 5 },
  "diagnostics": { "mode": "OFF" },
  "model": { "bundleDir": "${user.home}/.mosip/liveness/bundles", "updateUrl": "" }
}
```

Omitted keys take the defaults in section 4. Workflow blocks are
independent; a key set in `RESIDENT` does not leak into `OPERATOR`.

### 1.2 Flattened properties

Rule: `mosip.registration.liveness.` + the JSON path with `.` separators,
workflow names lower-cased, arrays as comma-separated lists, signal weights
under `signals.<id>.weight` and `signals.<id>.enabled`.

```properties
mosip.registration.liveness.enabled=true
mosip.registration.liveness.policy.version=2026.10-country-A
mosip.registration.liveness.resident.enabled=true
mosip.registration.liveness.resident.passiveThreshold=0.997
mosip.registration.liveness.resident.active.mode=ALWAYS
mosip.registration.liveness.resident.active.minChallenges=2
mosip.registration.liveness.resident.active.maxChallenges=3
mosip.registration.liveness.resident.active.pool=BLINK,SMILE,TURN_LEFT,TURN_RIGHT,OPEN_MOUTH
mosip.registration.liveness.resident.active.timeoutMillis=8000
mosip.registration.liveness.resident.maxRetries=3
mosip.registration.liveness.resident.onMaxRetries=ASSISTED_CAPTURE_WITH_SUPERVISOR
mosip.registration.liveness.resident.signals.rppg.weight=0.8
mosip.registration.liveness.operator.passiveThreshold=0.997
mosip.registration.liveness.operator.active.mode=ALWAYS
mosip.registration.liveness.supervisor.passiveThreshold=0.997
mosip.registration.liveness.supervisor.maxRetries=2
mosip.registration.liveness.device.minFps=10
mosip.registration.liveness.diagnostics.mode=OFF
```

The escape hatch `mosip.registration.liveness.policy.json` holds the whole
document (one line, JSON) and `mosip.registration.liveness.policy.signature`
its detached signature. When both representations are present the
precedence in section 3 applies.

## 2. Signing

A tampered local properties file must not be able to disable liveness
silently.

* The JSON document is canonicalised (RFC 8785 JSON Canonicalization
  Scheme) and signed with the MOSIP keymanager key used for other signed
  config artefacts; the signature travels in
  `mosip.registration.liveness.policy.signature` (JWS compact, detached
  payload).
* The client verifies against the pinned public key before activation.
  Verification success or failure is audited as
  `POLICY_LOADED(version, signatureOk)`.
* On failure the client falls back to the **compiled defaults**
  (`LivenessPolicy.defaults(workflow)`), never to "disabled", and sets a
  diagnostic flag visible to the supervisor.
* Flattened properties are not individually signed. They are therefore
  allowed to **tighten** the signed document (lower `maxRetries`, higher
  thresholds, smaller pool) but any attempt to **loosen** it is ignored and
  audited (`POLICY_OVERRIDE_REJECTED(key)`). This keeps per-centre tuning
  possible without a signing round-trip while making the signed document
  the floor. "Loosen" is defined per key in the table (column *Direction*).

## 3. Precedence

From highest to lowest:

1. Signed JSON document from the config server (`policy.json` +
   `policy.signature`), when signature verification succeeds.
2. Flattened `mosip.registration.liveness.*` properties, applied on top of
   (1) only in the tightening direction; applied freely on top of (3) when
   (1) is absent.
3. Compiled defaults (`LivenessPolicy.defaults`), always present.

Policy is read at `LivenessSession.open()` and frozen for that session.
Changes take effect at the next session; nothing re-reads configuration
mid-session.

## 4. Defaults table (every key)

Global keys:

| Key | Type | Default | Direction (tightening) | Notes |
|---|---|---|---|---|
| `enabled` | boolean | `true` | `false→true` only | Master switch. `false` is honoured only from the signed document, and emits `POLICY_LOADED(enabled=false)` so it is visible in audit. |
| `policy.version` | string | `builtin-1.1` | — | Recorded in every `LivenessResult`. |
| `policy.json` / `policy.signature` | string | absent | — | Escape hatch for the full signed document. |
| `device.minFps` | int | `10` | increase | Below this the session reports `DEGRADED` and `BLINK` is swapped for `BLINK_TWICE`. |
| `device.maxQueuedFrames` | int | `4` | — | `StreamOptions.maxQueuedFrames`. |
| `device.stallTimeoutMillis` | long | `3000` | decrease | `StreamOptions.stallTimeoutMillis`. |
| `device.reconnectAttempts` | int | `5` | decrease | `StreamOptions.reconnectAttempts`. |
| `device.targetFps` | int | `15` | — | `StreamOptions.targetFps`. |
| `diagnostics.mode` | `OFF`, `SUPERVISOR`, `LAB` | `OFF` | `LAB→SUPERVISOR→OFF` | `LAB` allows raw-frame snapshots with an explicit consent toggle; never the default. |
| `model.bundleDir` | path | platform app-data dir `/liveness/bundles` | — | Where verified `.mlb` bundles live. |
| `model.updateUrl` | URL | empty | — | Online bundle channel; empty disables. |
| `model.publicKeyId` | string | keymanager alias `LIVENESS_MODEL_SIGNING` | — | Pinned key for bundle verification. |
| `model.keepPreviousBundles` | int | `2` | — | Rollback depth. |
| `audit.includeSignalScores` | boolean | `true` | — | Per-signal scores in `SESSION_*` events (never frames). |
| `metrics.enabled` | boolean | `true` | — | Anonymised per-centre aggregates. |
| `ui.audioPrompts` | boolean | `false` | — | Spoken challenge prompts. |
| `ui.showOnlineIndicator` | boolean | `true` | — | — |

Per-workflow keys (`<wf>` ∈ `resident`, `operator`, `supervisor`), with the
compiled defaults from `LivenessPolicy.defaults()` and
`LivenessPolicy.Builder`:

| Key | Type | RESIDENT | OPERATOR | SUPERVISOR | Direction (tightening) | Maps to |
|---|---|---|---|---|---|---|
| `<wf>.enabled` | boolean | `true` | `true` | `true` | `false→true` | `enabled()` |
| `<wf>.passiveThreshold` | double [0,1] | `0.997` | `0.997` | `0.997` | increase | `passiveThreshold()` |
| `<wf>.padHardThreshold` | double [0,1] | `0.90` | `0.90` | `0.90` | decrease | `padHardThreshold()` |
| `<wf>.passiveWindowMillis` | long | `2500` | `2500` | `2500` | increase | `passiveWindowMillis()` |
| `<wf>.passiveMinFrames` | int | `8` | `8` | `8` | increase | `passiveMinFrames()` |
| `<wf>.active.mode` | `NEVER`, `ON_LOW_CONFIDENCE`, `ALWAYS` | `ALWAYS` | `ALWAYS` | `ALWAYS` | towards `ALWAYS` | `activeMode()` |
| `<wf>.active.minChallenges` | int ≥ 0 | `2` | `2` | `3` | increase | `minChallenges()` |
| `<wf>.active.maxChallenges` | int ≥ min | `3` | `3` | `3` | — | `maxChallenges()` |
| `<wf>.active.pool` | list of `ChallengeType` | `BLINK,SMILE,TURN_LEFT,TURN_RIGHT,OPEN_MOUTH` | same | same | subset | `challengePool()` |
| `<wf>.active.accessibilityPool` | list, non-motor only | `BLINK,SMILE,HOLD_STILL` | same | same | subset | `accessibilityPool()` |
| `<wf>.active.requireDepthRevealingChallenge` | boolean | `true` | `true` | `true` | `false→true` | `requireDepthRevealingChallenge()` |
| `<wf>.active.timeoutMillis` | long | `8000` | `8000` | `8000` | decrease | `challengeTimeoutMillis()` |
| `<wf>.active.minActionLatencyMillis` | long | `150` | `150` | `150` | increase | `minActionLatencyMillis()` |
| `<wf>.active.maxActionLatencyMillis` | long | `3000` | `3000` | `3000` | decrease | `maxActionLatencyMillis()` |
| `<wf>.maxRetries` | int ≥ 1 | `3` | `3` | `2` | decrease | `maxRetries()` (total attempts) |
| `<wf>.onMaxRetries` | `RETRY_ONLY`, `LOCK_AND_ALERT`, `ASSISTED_CAPTURE_WITH_SUPERVISOR`, `FALLBACK_TO_EXCEPTION_FLOW` | `ASSISTED_CAPTURE_WITH_SUPERVISOR` | `LOCK_AND_ALERT` | `LOCK_AND_ALERT` | — (signed only) | `onMaxRetries()` |
| `<wf>.lockoutMillis` | long | — | `900000` | `900000` | increase | used by the integration for `LOCK_AND_ALERT` |
| `<wf>.assistedThresholdDelta` | double | `0.05` | — | — | increase | added to `passiveThreshold` when `assistedCapture` |
| `<wf>.sessionTimeoutMillis` | long | `60000` | `60000` | `60000` | decrease | `sessionTimeoutMillis()` |
| `<wf>.acquisitionTimeoutMillis` | long | `15000` | `15000` | `15000` | decrease | `acquisitionTimeoutMillis()`; Desktop global parameter `mosip.registration.liveness[.<wf>].acquisition.timeout_ms` (the signed JSON uses the compiled default). Time allowed in `FACE_ACQUIRING` before `FACE_NOT_DETECTED` / `POOR_FACE_QUALITY` |
| `<wf>.minFaceQuality` | double [0,1] | `0.40` | `0.40` | `0.40` | increase | `minFaceQuality()` |
| `<wf>.identityDriftThreshold` | double | `0.45` | `0.45` | `0.45` | decrease | `identityDriftThreshold()` |
| `<wf>.requireIdentityContinuity` | boolean | `true` | `true` | `true` | `false→true` | `requireIdentityContinuity()` |
| `<wf>.vetoFrames` | int | `3` | `3` | `3` | decrease | consecutive frames above `padHardThreshold` for a veto |
| `<wf>.signals.<id>.enabled` | boolean | `true` | `true` | `true` | `false→true` | weight 0 when false |
| `<wf>.signals.<id>.weight` | double ≥ 0 | `1.0` (PLAN: `rppg 0.8`, `parallax 1.2` for resident) | `1.0` | `1.0` | — | `signalWeight(id)` |
| `<wf>.signals.<id>.hardThreshold` | double | `padHardThreshold` | same | same | decrease | per-signal override of the veto threshold |

Signal ids: `cnn_texture`, `moire`, `color_stats`, `rppg`, `parallax`,
`challenge_timing`, `identity`, `depth`, `ir`, `quality`, `injection`
(`Signals.java`).

## 5. Validation

The `policy` module validates before building a `LivenessPolicy`, and
`LivenessPolicy`'s constructor validates again:

* thresholds in [0, 1]; `minChallenges ≤ maxChallenges`; `maxChallenges ≤
  |pool|`; non-empty pool when `active.mode ≠ NEVER` and `minChallenges >
  0`;
* every accessibility-pool entry has `requiresMotorAbility() == false`;
* `minActionLatencyMillis < maxActionLatencyMillis ≤ timeoutMillis`;
* `maxRetries ≥ 1`;
* unknown keys are reported, not ignored silently (a typo in a key name
  must not silently fall back to a default), and the policy still loads;
* a validation failure of the signed document is treated like a signature
  failure (compiled defaults, audit).

## 6. What is intentionally not configurable

* The hard-veto precedence over fusion and over a completed action.
* Identity continuity at the signed-capture boundary can be disabled per
  workflow (`requireIdentityContinuity`) but only from the signed document.
* The generic PAD message shown to users.
* Logging of frames (never, regardless of configuration, outside
  `diagnostics.mode = LAB` with consent).
