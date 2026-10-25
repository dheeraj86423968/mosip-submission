# 12 — UI/UX specification

Covers mandatory task 10 and the UI/UX design deliverable. The copy is the
problem statement's where it gives it; everything else is specified here
and goes through i18n keys so desktop (JavaFX) and Android (Flutter) share
one set of strings. Figma exports, when produced, go in `docs/ux/`; this
document is the authority for behaviour.

## 1. Design principles

1. The resident does nothing during passive verification. No button, no
   instruction beyond positioning.
2. One instruction at a time, in large type, with an icon that does not
   rely on colour.
3. Technical liveness information (scores, signals, model names) never
   appears outside supervisor-gated diagnostics.
4. Security-sensitive failures (PAD, identity drift) show the generic
   message. Every other failure shows actionable guidance.
5. Desktop and Android render the same `SessionState`; the layout differs,
   the content and the order of information do not.

## 2. Screen inventory and states

Every screen renders one of these states, selected from
`SessionState.phase` + `hint` + `failure` + `attempt/maxAttempts`:

| State id | Phase / hint | Visual | Primary copy (i18n key) | Secondary copy |
|---|---|---|---|---|
| `S0 Idle` | `IDLE` | preview off, oval grey | `liveness.start` "Start face capture" (button) | — |
| `S1 Connecting` | `DEVICE_CONNECTING` | preview off, spinner | `liveness.device.connecting` "Connecting to the face capture device…" | — |
| `S1r Reconnecting` | any, hint `DEVICE_RECONNECTING` | preview frozen with overlay | `liveness.device.reconnecting` "Reconnecting to device…" | `liveness.device.wait` "Please wait" |
| `S1e Device error` | `FAILED` with `DEVICE_*` / `CAMERA_UNAVAILABLE` / `INVALID_FRAME_DATA` | preview off, device icon | per `08-error-handling.md` | "Retry", "Diagnostics" (if enabled) |
| `S2 Positioning` | `FACE_ACQUIRING` + hint | live preview, oval amber | hint text: `liveness.hint.moveCloser` "Move closer", `moveBack` "Move back", `centerFace` "Centre your face", `lookAtCamera` "Look directly at the camera", `improveLighting` "Improve lighting", `holdStill` "Hold still", `onlyOneFace` "Only one face should be visible" | — |
| `S3 Checking` | `PASSIVE_EVALUATING`, hint `CHECKING_LIVENESS` | live preview, oval amber with animated sweep | `liveness.checking` **"Checking face liveness…"** | `liveness.checking.sub` "Keep looking at the camera" |
| `S4 Challenge` | `ACTIVE_CHALLENGE` + challenge | live preview, oval green outline, challenge card with icon, progress dots `i/n`, countdown ring | `liveness.challenge.BLINK` **"Please blink"**, `BLINK_TWICE` "Please blink twice", `SMILE` **"Please smile"**, `TURN_LEFT` **"Please turn your head to the left"**, `TURN_RIGHT` "Please turn your head to the right", `LOOK_UP` "Please look up", `LOOK_DOWN` "Please look down", `NOD` "Please nod", `OPEN_MOUTH` "Please open your mouth", `RAISE_EYEBROWS` "Please raise your eyebrows", `HOLD_STILL` "Please hold still" | feedback line (next row) |
| `S4f Challenge feedback` | `ACTIVE_CHALLENGE` + hint | as S4, feedback line changes | `liveness.feedback.PLEASE_CONTINUE` **"Please continue"**, `ACTION_DETECTED` **"Action detected"**, `HOLD_STILL` **"Hold still"** | — |
| `S5 Passed` | `PASSED`, hint `CAPTURED_OK` | preview with green oval and check mark, 1 s, then the calling screen continues | `liveness.passed` **"Good, face captured successfully"** (resident) / `liveness.verified` "Face verified" (auth) | — |
| `S6 Liveness failed` | `FAILED` with a non-sensitive reason, hint `RETRY_AVAILABLE` | preview paused, oval red outline, guidance list | `liveness.failed` **"We could not verify face liveness. Please try again."** | up to three of: `liveness.guidance.lookAtCamera` **"Look directly at the camera."**, `faceVisible` **"Ensure your face is clearly visible."**, `lighting` **"Improve lighting."**, `captureArea` **"Keep your face within the capture area."**, `followPrompt` **"Follow the prompted action."**; button `liveness.retry` "Try again (attempt n of max)" |
| `S7 PAD / generic failure` | `FAILED` with `PAD_DETECTED` or `IDENTITY_DRIFT` | preview paused, neutral icon (not a warning triangle) | `liveness.generic` **"Face verification could not be completed. Please try again."** | button "Try again (attempt n of max)" only |
| `S8 Max retries` | `LOCKED`, hint `MAX_RETRIES_REACHED` | preview off, lock icon | resident: `liveness.locked.resident` "Face capture could not be completed. A supervisor is required to continue." operator/supervisor: `liveness.locked.auth` "Face authentication is locked. Use another authentication method or contact your supervisor." | button per `onMaxRetries`: "Request supervisor", "Use password", "Biometric exception", "Close" |
| `S9 Cancelled` | `CANCELLED` | return to calling screen | — | — |
| `S10 Assisted` | any phase with `assistedCapture = true` | small "Assisted capture" chip on the overlay; accessibility pool prompts | as S2–S8 | chip tooltip: `liveness.assisted` "Assisted capture: supervisor attested" |
| Status strip | all | online/offline dot, attempt counter, "Liveness check: on" | `liveness.status.online` "Online", `offline` "Offline", `disabled` "Liveness check: disabled by policy" | — |

Progress (`SessionState.progress`) drives a thin bar under the preview on
both platforms: 0.1 at connected, 0.3 at face acquired, 0.6 at end of
passive, then linear across challenges, 1.0 at `PASSED`.

## 3. Wireframes

### 3.1 Desktop (JavaFX, registration client capture pane, 1280×800 reference)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Resident registration › Biometrics › Face                     ● Online  2/3 │
├───────────────────────────────────┬─────────────────────────────────────────┤
│                                   │                                         │
│      ┌───────────────────┐        │   Checking face liveness…               │
│      │                   │        │   Keep looking at the camera            │
│      │     ╭───────╮     │        │                                         │
│      │    ╱         ╲    │        │   ────────────────────────── 60 %       │
│      │   │   face    │   │        │                                         │
│      │   │  preview  │   │        │   Liveness check: on                    │
│      │    ╲         ╱    │        │                                         │
│      │     ╰───────╯     │        │                                         │
│      │                   │        │                                         │
│      └───────────────────┘        │                                         │
│         (oval: amber)             │                                         │
├───────────────────────────────────┴─────────────────────────────────────────┤
│  [ Cancel ]                                                    Diagnostics  │
└─────────────────────────────────────────────────────────────────────────────┘

State S4 (challenge), right pane:
│   ┌───────────────────────────────────────┐                                 │
│   │  ◀  Please turn your head to the left │   ● ● ○     ⏱ 6 s               │
│   └───────────────────────────────────────┘                                 │
│   Please continue                                                           │

State S6 (liveness failed), right pane:
│   We could not verify face liveness. Please try again.                      │
│   • Look directly at the camera.                                            │
│   • Improve lighting.                                                       │
│   • Keep your face within the capture area.                                 │
│   [ Try again (attempt 2 of 3) ]                                            │

State S7 (PAD), right pane:
│   Face verification could not be completed. Please try again.               │
│   [ Try again (attempt 2 of 3) ]                                            │

State S8 (locked, resident), right pane:
│   Face capture could not be completed.                                      │
│   A supervisor is required to continue.                                     │
│   [ Request supervisor ]        [ Close ]                                    │

State S1e (device error), whole pane:
│   Face capture device not found.                                            │
│   Check that the device is connected and switched on, then try again.       │
│   [ Retry ]                                                     Diagnostics │
```

The operator and supervisor authentication dialogs reuse the same right
pane inside the existing modal: title "Operator authentication" /
"Supervisor authentication", no packet breadcrumb, the "Use password"
alternative visible from the start.

### 3.2 Android (Flutter, portrait, 800×1280 reference)

```
┌──────────────────────────────┐
│ ‹  Face capture      ● Online│
│                         2/3  │
│                              │
│        ╭──────────╮          │
│       ╱            ╲         │
│      │   face       │        │
│      │  preview     │        │
│      │  (full       │        │
│      │   bleed)     │        │
│       ╲            ╱         │
│        ╰──────────╯          │
│                              │
│  ────────────────── 60 %     │
│                              │
│  Checking face liveness…     │
│  Keep looking at the camera  │
│                              │
│                              │
│                              │
│            [ Cancel ]        │
└──────────────────────────────┘

S4 (challenge) lower sheet:
│ ┌──────────────────────────┐ │
│ │ ◀ Please turn your head  │ │
│ │   to the left            │ │
│ └──────────────────────────┘ │
│   ● ● ○            ⏱ 6 s     │
│   Please continue            │

S7 (PAD) lower sheet:
│  Face verification could not │
│  be completed.               │
│  Please try again.           │
│  [ Try again (2 of 3) ]      │

S8 (locked, operator auth):
│  Face authentication is      │
│  locked.                     │
│  Use another authentication  │
│  method or contact your      │
│  supervisor.                 │
│  [ Use password ]  [ Close ] │
```

## 4. Challenge icons and motion

| Challenge | Icon | Animation (1.2 s loop, respects reduced-motion setting) |
|---|---|---|
| BLINK / BLINK_TWICE | eye | eyelid closes once / twice |
| SMILE | mouth corners up | neutral → smile |
| TURN_LEFT / TURN_RIGHT | head outline with arrow | head rotates 25° in the direction |
| LOOK_UP / LOOK_DOWN | head with arrow | head tilts |
| NOD | head with double arrow | tilt down, up |
| OPEN_MOUTH | mouth | opens |
| RAISE_EYEBROWS | brows | brows up |
| HOLD_STILL | face in frame with pause bars | none |

Direction convention: "left" is the **subject's** left. The arrow is drawn
as the subject sees themselves (mirrored preview), so the arrow points the
way the subject should turn on screen. `FaceObservation.yaw > 0` means the
subject turned to their left.

## 5. Accessibility

* Text at minimum 18 pt (desktop) / 20 sp (Android) for prompts; 24 pt /
  28 sp for the challenge prompt.
* No information conveyed by colour alone: the oval changes thickness and
  pattern (dashed → solid → solid with check) as well as colour; the
  countdown is a ring **and** a number.
* Screen-reader labels on every state change (`announce` on Android,
  accessible text on JavaFX nodes). The challenge prompt is announced when
  issued and the feedback line when it changes.
* Optional audio prompts (`ui.audioPrompts`) using the same i18n strings
  through the platform TTS, for low-literacy contexts.
* High-contrast theme follows the client's existing theme switch.
* Reduced motion: animations replaced by static icons with the arrow.
* Assisted capture (`S10`): accessibility pool only (no head movement);
  longer passive window; operator can read prompts aloud. The chip is
  visible so the record is transparent.
* Right-to-left locales: the direction arrows are **not** mirrored by RTL
  layout; they follow the subject convention above.

## 6. Internationalisation

* All strings are keys under `liveness.*` in the existing bundles
  (`registration-client` `messages_*.properties`; Android `app_*.arb`).
  The key list in §2 is the complete set plus the device and error
  messages in `08-error-handling.md`.
* Pluralisation and the attempt counter use ICU message format
  (`"Try again (attempt {n} of {max})"`).
* Challenge prompts must stay under 40 characters in every language or
  wrap to two lines of equal weight; translators are given the icon so
  they can keep the verb first.
* Numbers and the countdown use locale digits.

## 7. Consistent desktop / Android behaviour

| Behaviour | Desktop | Android | Same? |
|---|---|---|---|
| States and transitions | `SessionState` from liveness-core | same object via Pigeon | yes |
| Copy | i18n keys in §2 | same keys | yes |
| Preview | SBI stream rendered in the capture pane | CameraX preview, full bleed | layout differs |
| Oval guide colours and patterns | grey / amber / green / red; dashed → solid | same | yes |
| Challenge card | right pane | bottom sheet | position differs |
| Progress indicator | bar under preview | bar under preview | yes |
| Countdown | ring + seconds | ring + seconds | yes |
| Retry button text | "Try again (attempt n of max)" | same | yes |
| PAD message | generic | generic | yes |
| Max-retry actions | per `onMaxRetries` | same | yes |
| Device error | device-error pane with Retry and Diagnostics | same with OS permission shortcut | one extra affordance on Android |
| Online/offline indicator | status strip | app bar | yes in content |
| Diagnostics | link, supervisor-gated | menu item, supervisor-gated | yes |
| Cancel | button in footer | button at bottom | yes |

## 8. Diagnostic mode (supervisor-gated, `diagnostics.mode ≠ OFF`)

A panel overlaying the preview, toggled from the "Diagnostics" link:

* per-signal attack probability and confidence as live bars with the
  signal id; the fused score with the threshold line;
* frame rate, dropped and corrupted frame counters, device health, adapter
  id, resolution;
* engine id and version, bundle id and version, policy version, signature
  status;
* the current challenge, elapsed and remaining time, onset latency of the
  last action;
* "Capture diagnostic snapshot" saving scores and the above as JSON
  (no frames; raw frames only in `LAB` mode after the consent toggle).

Nothing in this panel is visible to the resident view; on desktop it is
rendered on the operator's monitor only.
