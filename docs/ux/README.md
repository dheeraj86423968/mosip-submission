# UI mockups and user flow

These are high-fidelity mockups of the face liveness screens for the MOSIP
Desktop Registration Client (JavaFX) and the Android Registration Client
(Flutter). They are drawn from the states, copy and accessibility rules in
[`docs/design/12-ux-spec.md`](../design/12-ux-spec.md), which is the
authority for behaviour.

**Reading a mockup.** All resident-facing text is the spec's i18n copy,
word for word. Two kinds of label are added for reviewers and are not part
of the product:

* the grey "Mockup · S*n*" tag at the bottom, which names the spec state;
* the purple dashed **Design note** boxes.

The face is a vector illustration. These files contain no photographs of
real people.

The visual system is the same in every file:

* navy and MOSIP-blue chrome, with a sans-serif typeface;
* the status strip (Online/Offline, attempt counter, "Liveness check: on")
  sits in the breadcrumb bar on desktop and in the app bar on Android;
* the progress bar sits under the preview on both platforms;
* the oval guide changes **pattern and thickness as well as colour**:
  grey dashed for idle, amber dashed with a sweep while checking, green
  solid for a challenge, green thick with a check mark for passed, red
  dotted for an actionable failure, neutral grey for a security failure;
* the countdown is a ring **and** a number;
* the challenge prompt is set at 28 px or larger.

## Files

Each mockup is an SVG with a PNG rendered next to it in `png/`.

| File | State (spec §2) | Caption |
|---|---|---|
| [desktop-01-resident-passive-check.svg](desktop-01-resident-passive-check.svg) | S3 Checking | Desktop resident capture: live preview, amber oval guide with sweep, "Checking face liveness…", 60 % progress, no resident action. |
| [desktop-02-challenge-prompt.svg](desktop-02-challenge-prompt.svg) | S4 + S4f | Desktop challenge: "Please turn your head to the left" card with head-and-arrow icon, dots 2/3, 6 s countdown ring, feedback "Please continue". |
| [desktop-03-result-success.svg](desktop-03-result-success.svg) | S5 Passed | Desktop success: thick green oval with check mark and "Good, face captured successfully", shown for 1 s. |
| [desktop-04-liveness-failed-guidance.svg](desktop-04-liveness-failed-guidance.svg) | S6 Liveness failed | Desktop actionable failure: paused preview, red dotted oval, three guidance lines, "Try again (attempt 2 of 3)". |
| [desktop-05-pad-failure-retry.svg](desktop-05-pad-failure-retry.svg) | S7 PAD / generic | Desktop PAD failure: generic "Face verification could not be completed. Please try again.", neutral icon, retry only, no attack detail. |
| [desktop-06-max-retries-supervisor.svg](desktop-06-max-retries-supervisor.svg) | S8 Max retries | Desktop max retries for a resident: preview off with lock, "A supervisor is required to continue.", "Request supervisor" and "Close". |
| [desktop-07-device-error.svg](desktop-07-device-error.svg) | S1e Device error | Desktop device error: "Face capture device not found." with "Retry" and the Diagnostics link. |
| [desktop-08-operator-login-liveness.svg](desktop-08-operator-login-liveness.svg) | S4 in the operator auth dialog | Operator login: "Operator authentication" modal, no breadcrumb, "Please blink" (1/2, 3 s), "Action detected", "Use password" visible from the start. |
| [desktop-09-supervisor-auth-liveness.svg](desktop-09-supervisor-auth-liveness.svg) | S4 + S4f in the supervisor auth dialog | Supervisor packet approval: "Supervisor authentication" modal over the approval list, "Please turn your head to the right" (2/3), "Please continue", "Use password". |
| [desktop-10-auth-locked.svg](desktop-10-auth-locked.svg) | S8, authentication variant | Operator or supervisor after the last attempt: preview off, "Face authentication is locked. Use another authentication method or contact your supervisor.", "Use password" and "Close". |
| [android-01-resident-capture.svg](android-01-resident-capture.svg) | S3 Checking | Android resident capture in a phone frame: full-bleed preview, amber oval, progress, "Checking face liveness…", Cancel. |
| [android-02-challenge.svg](android-02-challenge.svg) | S4 + S4f | Android challenge in a bottom sheet: "Please turn your head to the left", dots 2/3, 6 s ring, "Please continue". |
| [android-03-offline.svg](android-03-offline.svg) | Offline + S5 | Android offline: "Offline" in the app bar. The same on-device checks pass with "Good, face captured successfully", and the audit record is queued for sync. |
| [android-04-operator-login.svg](android-04-operator-login.svg) | S4 + S4f after password | Android operator login: "Please smile" (1/2), "Action detected", "Use password". |
| [android-05-supervisor-locked.svg](android-05-supervisor-locked.svg) | S8, authentication variant | Android supervisor approval locked: "Face authentication is locked…", "Use password", "Close". |
| [flow-liveness-user-flow.svg](flow-liveness-user-flow.svg) | S0–S10 | User flow: start → connecting (device-error branch) → positioning → passive check → decision → active challenges → pass, actionable failure or generic PAD failure → retry while attempts remain → max retries → supervisor, password, exception or close. |

## Regenerating

The SVGs come from one script, so that tokens, components and copy stay
consistent across files:

```bash
python3 docs/ux/generate_mockups.py docs/ux
# PNGs (headless Chromium; rsvg-convert or Inkscape work too)
for f in docs/ux/*.svg; do b=$(basename "$f" .svg); w=1280; h=800
  case $b in android*) w=412; h=892;; flow*) h=900;; esac
  chromium --headless=new --hide-scrollbars --window-size=$w,$h \
    --screenshot="$PWD/docs/ux/png/$b.png" "file://$PWD/$f"; done
```

Every SVG parses with `python3 -c "import xml.etree.ElementTree as E; E.parse('docs/ux/<file>.svg')"`.
If the copy in `12-ux-spec.md` changes, change the strings in the script
and regenerate.

## Not drawn here (covered in the spec)

These states use the same components and are specified with exact copy in
`12-ux-spec.md` §2–§5:

* S0 Idle, S1 Connecting, S1r Reconnecting and S2 Positioning hints;
* S10 "Assisted capture" (the generator includes a chip helper for it);
* the supervisor-gated diagnostics panel (§8).
