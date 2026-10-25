# End-to-end findings

This document records what the `e2e-tests` module found when the real `liveness-core` orchestrator and the real
`liveness-engine-onnx` engine were first run together against the mock device. It lists the defects that were
fixed, the measurements behind each design change, and the limits of what these tests can show.

## How the tests work

| Test | What it drives | What it checks |
|---|---|---|
| `RealPipelineScenarioTest` | The nine bundled mock scenarios through `testkit`'s `ScenarioRunner`, under the default policy and an always-challenge policy | Nothing crashes or hangs. Outcomes are reported, not asserted, because scripted scenarios cannot answer randomly drawn prompts. |
| `InteractiveChallengeTest` | A closed loop. A simulated person watches the session and performs each prompted action 700 ms after it appears. A simulated replay follows a fixed schedule of blinks, left turns and smiles and ignores prompts. The `...WithPitchChallenges` tests add nod, look up and look down to the pool. | Live pass counts and replay pass counts over seeded trials. |
| `PoseProbeTest` | Single rendered poses and expressions through the engine | Diagnostic log of the yaw, pitch, eye openness, mouth openness and quality the engine reports, a pitch sweep, and a trace of drawn-landmark pitch against YuNet pitch and gaze. |

Each session gets its own clock, advanced to each frame's device timestamp, so passive windows and challenge
timeouts run on scenario time and the tests finish in minutes.

The mock renders cartoon faces. YuNet scores drawn faces near its decision boundary, so these tests run the
detector at a score threshold of 0.35 instead of the production 0.7. Every other model, signal and threshold is the
production configuration. See `SyntheticSubjectEngine`.

## Defects found and fixed

1. **The quality gate rejected the frames a head-turn challenge needs.** Overall face quality includes a pose term
   that falls as the head turns. At the peak of a right turn, quality fell to 0.22, below the 0.4 minimum, so the
   gate discarded those frames and the turn detector never saw the turn. Left turns passed only because one frame
   at 22° still scored 0.51. Real faces are affected the same way. `QualityGate.evaluate(analysis, ignorePose)` now
   leaves out the pose term while a head-movement challenge is active, and keeps every other check.
   Regression test: `QualityGateAndEvidenceTest.headMotionChallengeIgnoresPoseButKeepsOtherChecks`.

2. **The engine read low detector confidence as occlusion.** Occlusion was the YuNet score mapped from a fixed 0.6
   to 0.9. `QualitySignal` now places that floor 0.1 below the configured detector threshold, capped at 0.6. At the
   production threshold of 0.7 the behaviour is unchanged.

3. **The mock rendered a right turn as a malformed face.** The nose was drawn as a one-sided curve that collapsed
   at negative yaw. A right turn is now rendered as the mirror image of the matching left turn.

4. **A blink counted as a full challenge.** A recording that blinks every few seconds always has a blink inside any
   response window, so a blink proves nothing against a replay. `ChallengeSelector` now guarantees the minimum
   number of non-spontaneous challenges wherever the pool allows. Blinks and holding still may still be drawn as
   extras. Regression test: `ChallengeSelectorTest.spontaneousActionsDoNotCountTowardTheMinimum`.

## Design changes made from measurements

### Unprompted-action check

A replay can pass when its own schedule puts the requested gesture inside the response window. Such a recording
nearly always performs other gestures too. `UnpromptedActionMonitor` watches every other distinctive action in the
pool while a challenge is active. The challenge fails with `ACTIVE_CHALLENGE_FAILED`, audited as
`UNPROMPTED_ACTION`, if one of those actions completes before the requested one. Blinks and holding still are
exempt. Related actions are exempt from watching each other: smile and open mouth, and nod, look up and look down.
`ActionThresholds.rejectUnpromptedActions` turns the check off.

### Response latency limit lowered from 6 s to 3 s

The default `maxActionLatencyMillis` was 6000 ms. Most people begin a requested movement within about 1.5 s, and
3 s still allows a slow responder twice that.

### Higher challenge minimums for operators and supervisors

| Workflow | Old default | New default |
|---|---|---|
| Resident, only when passive confidence is low | 2 to 3 | 2 to 3, unchanged |
| Operator, every login | 1 to 2 | 2 to 3 |
| Supervisor, every authentication | 2 to 3 | 3 |

### Measurements

Thirty seeded trials per row, simulated person and simulated replay as described above. Rows are in the order the
changes were made.

| Configuration | Live passes | Replay passes |
|---|---|---|
| Challenge-response only, 6 s window, before the fixes | 12 of 12 | about 23% over 90 trials |
| Plus unprompted-action check | 30 of 30 | 8 of 30 |
| Plus blinks no longer counting toward the minimum | 30 of 30 | 4 of 30 |
| Plus 3 s latency limit, two challenges | 30 of 30 | 5 of 30 |
| Three challenges | 30 of 30 | 0 of 30 |

Every replay pass in the two-challenge rows drew the same sequence: a left turn then a smile. That is exactly the
order the recording performs them in, and its smile arrives within human reaction time of the prompt. Timing alone
cannot separate that from a person. Three challenges close it, because this recording only ever performs two
distinct gestures. A recording with more distinct gestures would need longer sequences still. That is an inherent
property of challenge-response with a small action pool.

## Pitch challenges (NOD, LOOK_UP, LOOK_DOWN)

Until this round, a rendered 10° to 22° downward tilt read back as about 0°, so `NOD` and `LOOK_DOWN` were left out
of the closed-loop pool. There were four separate causes.

1. **The mock did not render pitch as a rotation.** Eyes, nose and mouth all slid down by the same amount, with
   the mouth at 0.8 times that. The nose did not move relative to the eyes, so no landmark-based estimator could
   see a tilt. `SyntheticFaceRenderer` now rotates each feature about the head's horizontal axis at its own depth:
   eyes and mouth corners 0.20, nose tip 0.36 and nose bridge 0.26 head heights, so the nose protrudes about 0.43
   eye-to-mouth distances, as on a real face. The hairline follows too. The neutral face is unchanged. For a
   downward tilt the nose now drops further than the eyes and the eye-to-mouth span foreshortens.
   `SyntheticFaceRenderer.landmarks(FaceState)` publishes the five points as drawn.
2. **YuNet does not localise a drawn nose.** This limits the test, not the product. With the renderer fixed,
   YuNet's pitch on the cartoon still reads about a quarter of the rendered angle: -30° reads -7°, -20° reads 0°,
   and +15° reads +10°. Moving only the drawn nose by 80 px moved YuNet's nose point by about 30 px and dragged
   its eye points along. Bolder nostrils and a shaded nose did not help. Neither did a ray-traced, Lambert-shaded
   3-D head: its pitch gain was about 0.4, while yaw stayed above 1. YuNet's five points on drawn faces follow a
   learned face layout more than the drawn features. Its eye points also sit about 7 px below the drawn irises, so
   the eye-band gaze reads -0.78 at rest and jumps to 0 during turns and smiles.
3. **The absolute pitch had a large per-person bias.** `HeadPose` turns the nose's position between the eye line
   and the mouth line into an angle using population constants. On a projected 3-D head (`HeadPoseTest`) with
   plausible spreads of nose length and depth, mouth depth, distance, and a laptop camera 15° below the eyes, a
   neutral head read from about -26° to +18°. `HeadMotionDetector` only takes its baseline from a frame within
   ±10°, so many real users would never have started a pitch challenge. The fix is `PitchBaseline`.
   `OnnxFrameAnalyzer` learns the subject's neutral during the passive phase. Only near-frontal frames count, and
   the baseline is the median of the last 30. It is frozen when the first challenge is issued, so pitch never
   jumps mid-challenge, and reset when the analyzer resets. From then on pitch is reported relative to that
   neutral. If there are too few frames, they disagree, or the result is implausible, the population constants are
   kept. Yaw and roll are computed exactly as before.
4. **The mouth line moves without the head.** A broad smile lifts the mouth corners by 5 to 10 mm, which alone read
   as 7° to 10° downward on real proportions and 10° on the cartoon. That is enough to complete an unprompted
   `NOD`. `HeadPose.estimateRelative` therefore checks the mouth-referenced angle against a second estimate that
   uses only the nose's drop below the eye line, in inter-ocular distances. The mouth-referenced angle is capped at
   4 times that estimate plus 2°, and replaced by it when the signs disagree. A smile or an open mouth now reads
   within 2°.

On the 3-D head model, after calibration, ±20° reads beyond ±15° for every anatomy and view tested, and a 15° nod
reads beyond 10°. A 30° turn reads within ±7° of pitch. The weakest case is a flat nose (18 mm) with deep-set mouth
corners (12 mm behind the eyes) seen from 30 cm. Its downward reading stops growing at about -20°, where it reads
-16° to -18°.

Two core detector fixes followed from the closed loop:

- `HeadMotionDetector` compared gaze with an absolute threshold. With the cartoon's -0.78 resting gaze, `LOOK_DOWN`
  "started" with no movement. Gaze is now measured from its value at the baseline, like the angle.
- A blink moves the eye band, so it read as a vertical gaze excursion. That completed unprompted `LOOK_UP`s during
  other challenges. Gaze is now ignored while eye openness is below `blinkOpenEar`. A real user blinking during any
  challenge would have hit this.

The closed loop's simulated nod is now a 20° dip and return within 1 s. The mock's `NOD` action is a full sine
cycle that swings up as far as it went down, which then reads as an unprompted `LOOK_UP`.

### Closed-loop measurements with pitch challenges

Pool: blink, smile, both turns, open mouth, nod, look up, look down. Same simulated person and replay as above. In
the "drawn landmarks" rows, `DrawnLandmarkPitchEngine` wraps the production engine and replaces only pitch and
vertical gaze. They are computed by the engine's own code (`HeadPose`, `PitchBaseline`, `ObservationExtractor`)
from the five points the mock drew, instead of YuNet's. Everything else is unchanged: detection, yaw, eye and
mouth measures, quality, signals and the orchestrator.

| Configuration | Live passes | Replay passes |
|---|---|---|
| YuNet landmarks, before these fixes, three challenges (partial run) | 0 of 21 | not run |
| YuNet landmarks, after these fixes, two challenges | 2 of 10 | not run |
| YuNet landmarks, after these fixes, three challenges | 0 of 10 | not run |
| Drawn landmarks, two challenges | 30 of 30 | 0 of 30 |
| Drawn landmarks, three challenges | 30 of 30 | 0 of 30 |

The two-challenge replay rate falls from 5 of 30 to 0 of 30. With eight actions in the pool, the draw rarely
matches the recording's left turn then smile, and any nod or look drawn first is failed by the recording's own turn
or smile. A recording that also nods would need checking. The original five-action pool, with YuNet landmarks,
reran unchanged in the same session: 30 of 30 live and 5 of 30 replay passes for two challenges, all on the left
turn then smile draw, and 30 of 30 and 0 of 30 for three.

## Findings about the PAD signals

- **rPPG and parallax reject a drawn face, but only in long sessions.** With all signals enabled, the pulse
  detector vetoed the drawn subject at 95% attack probability, and parallax scored it 97% planar. Both are correct:
  a drawing has no pulse and no depth. The rPPG veto only fires once its window fills, at about 8.5 s. A session
  that finishes sooner is not protected by it, and in one 30-trial run a responsive drawn subject passed 11 times.
  Holding a decision until slow signals reach confidence would close that, at the cost of longer captures. That
  trade-off is left to policy and is not implemented.
- **Print attack.** The mock's printed-texture scenario is now vetoed as an attack on the first frame.
- **Screen replay.** The mock's moiré overlay stops the detector from finding a face, so the session ends as
  `FACE_NOT_DETECTED` after the 60 s session timeout. It is rejected, but not for the right reason.

## What these tests cannot show

- **Passive PAD error rates.** The live and replay scenarios use the same renderer, so their pixels are
  indistinguishable to passive signals. APCER and BPCER need a consented corpus of real people and real attack
  instruments, run through `testkit`'s `IsoReportMain`.
- **Pitch on real faces.** `NOD`, `LOOK_UP` and `LOOK_DOWN` now pass the closed loop, but with pitch and vertical
  gaze taken from the drawn landmarks, because YuNet cannot localise a drawn nose (see "Pitch challenges" above).
  Whether YuNet's nose landmark tracks pitch on real faces well enough has **not** been measured. The pose estimator
  and its per-session baseline are checked only against a projected 3-D head model. Check all three challenges on
  real faces, across nose shapes and camera heights, before release, or remove them from the default pool.
- **Update, 24 Oct 2026.** `docs/testing/proxy-eval/README.md` §8 ran the pose path on 80 windows of openly
  licensed real-face video (natural movement, no prompted nods, no reference angle). `PitchBaseline` calibrated in 79
  of 80. Clear look-downs registered in some clips (-14 and -15 degrees) but read near 0 or upward in others, talking
  alone reached the 10 degree NOD threshold in 5 of 39 clips, and vertical gaze moved by 0.5 or more in 17 of 27
  talking clips. The three pitch challenges stay out of the default pool.
- **Gaze on real faces.** `LOOK_UP` and `LOOK_DOWN` also accept a vertical gaze change. The eye-band gaze estimate
  is measured around the eye landmarks and is unreliable on the drawn face. Its behaviour on real faces, including
  with glasses, is unmeasured.
- **Real reaction times.** The simulated person always reacts in 700 ms. The 3 s limit has not been validated with
  real users, including older users and users with motor impairments.
- **Real devices.** All frames come from the mock. No vendor SBI device has been used.
