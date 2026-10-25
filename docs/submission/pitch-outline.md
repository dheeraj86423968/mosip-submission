# Pitch outline — 7 slides

Target: 5 minutes spoken, jury of MOSIP engineers and programme staff.
Every number on a slide must be traceable to a file in this repository or
to the generated evaluation report; nothing is rounded up.

## Slide 1 — The gap

* Title: *A photo can enrol in a national ID today.*
* One image: a registration desk with a printed photo held to the SBI
  device.
* Three bullets: the Registration Clients accept any face the device
  captures; L0/L1 devices sign the capture, not the person; nothing
  checks liveness in resident capture, operator login or supervisor
  approval.
* Speaker: state the problem statement number and the three workflows.

## Slide 2 — What we built

* Title: *mosip-vivid: liveness and PAD for both Registration Clients.*
* Diagram: the component diagram from `docs/design/01-architecture.md`
  reduced to five boxes: device adapters → `FaceFrameSource` SPI →
  orchestrator (liveness-core) ← `LivenessEngine` SPI ← engines; policy
  and audit on the side.
* Bullets: two small SPIs; the orchestrator decides, engines only score;
  same code path on desktop and Android; works offline by construction.
* Speaker: "Any vendor device, any engine, one behaviour."

## Slide 3 — How a decision is made

* Title: *Passive first, active when needed, re-checked throughout.*
* The state diagram from `docs/design/04-passive-to-active-flow.md`
  (simplified to PASSIVE → PASSED / ACTIVE → PASSED / FAILED → retry /
  LOCKED).
* Right column: the signal list S1–S9 as icons with the attack class each
  targets; the fusion sentence: "weighted log-odds fusion with hard
  vetoes".
* Speaker: challenge selection by `SecureRandom`, nonce in audit, timing
  window, identity continuity across the session and into the signed
  capture.

## Slide 4 — Proof, not promise

* Title: *Formally checked. Measured, not asserted.*
* Left: the TLA+ property in one line
  (`PASSED ⇒ score ≥ θ ∧ ¬veto ∧ ¬drift ∧ challenges ≥ required`) and
  the TLC result (6,955 / 11,463 distinct states, no error, 7/7 mutations
  caught) from `formal/README.md`.
* Right: the ISO/IEC 30107-3 metric table **from the generated report** if
  one exists at pitch time; otherwise the empty template with "self-
  evaluation on our consented corpus; no lab certification claimed".
* Speaker: be explicit about what is self-evaluated.

## Slide 5 — Live demo pointer (Attack Lab)

* Title: *Four attacks, one mock device, zero bypasses.*
* Screenshot strip: live pass; print rejected; screen replay rejected;
  video replay failing the challenge; identity swap caught at capture.
* Footer: "No hardware required: `device-mock` speaks SBI."
* Speaker: hand over to the video or the live run.

## Slide 6 — Fit with MOSIP

* Title: *Built to be merged, not bolted on.*
* Table: MOSIP principle → how it is honoured (modularity: SPIs;
  scalability: on-device, quantised; security: fusion, signed policy and
  bundles, generic failure messages; privacy: frames never persisted,
  scores only).
* Bullets: configuration through `mosip.registration.liveness.*`; audit
  through the existing manager with component `LIVENESS`; MPL-2.0; the
  SBI signed-preview extension proposal (`docs/design/02-frame-streaming.md`
  §6) as the upstream contribution.
* Speaker: the integration modules are patches against
  `registration-client` and `android-registration-client`.

## Slide 7 — Status and ask

* Title: *Where it stands, what is next.*
* The status table from the root README, verbatim (implemented /
  source-only / designed per module).
* Next: corpus collection to 20+ subjects, Android engine, upstream PRs,
  signed-preview extension proposal to the SBI spec, lab evaluation.
* Ask: MOSIP review of the two SPIs and of the SBI extension.
* Speaker: thank the jury; team names and roles.

## Appendix slides (not presented unless asked)

* A1: configuration keys and defaults table.
* A2: error-handling matrix (the 13 cases).
* A3: STRIDE table.
* A4: ISO/IEC 30107 terminology mapping and attack-potential levels.
