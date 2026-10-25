# Proxy self-evaluation: real-face video with simulated attacks

**Date:** 24 Oct 2026. **Engine:** `mosip-vivid-onnx` 0.1.0, no model bundle (YuNet 2023mar, MiniFASNetV2-SE 128,
SFace), YuNet at the **production threshold of 0.7**. **Policy:** `builtin-1.0`, RESIDENT, replayed in passive mode (`active.mode = NEVER`; the RESIDENT default was
`ON_LOW_CONFIDENCE` when this ran).
**Commit:** scaffold commit `3587dbb` plus the uncommitted working tree of that day.

> **Headline.** This is a digital proxy, not an ISO/IEC 30107-3 evaluation. On 39 adults in openly licensed video,
> passive PAD **rejected most simulated screen attacks but accepted most simulated prints**. The worst species was the
> warped print, with an APCER of 84 % (31 of 37; Wilson 95 % CI 69–92 %). Flat prints were at 83 % and cut-out prints
> at 83 %. Screen replay was at 44 % and a still photo on a screen at 28 %. BPCER was 5.7 % (2 of 35, CI 1.6–19 %),
> and 4 of 39 bona fide clips (10 %) produced no decision. Raising the threshold from 0.80 to 0.90 changes almost
> nothing: accepted prints score 0.98–0.99. BPCER at APCER = 5 % is 66 %.
>
> In this proxy, passive PAD stops screens only through S1 (the CNN) and does not stop prints. Moiré (S2) did not
> separate any class. Colour (S3) separated only screens, and weakly. rPPG (S4) reported an attack on most real faces
> in compressed video. Under the RESIDENT default at the time (`ON_LOW_CONFIDENCE`), a print scoring 0.99 never saw a
> challenge. That default has since been changed to `ALWAYS`, and S4 can no longer veto on its own (section 7). On real faces, pitch is only partly registered through YuNet landmarks, and
> vertical gaze is not usable.

## 1. Why a proxy

The consented corpus in `docs/corpus-ethics.md` will not be collected for this submission. The substitute uses the
best real-face material that can be used without new consent: openly licensed video of real people facing a camera.
Attacks are generated digitally from that video. This gives the first measurements of the engine on real faces. It
does **not** give physical presentation attack figures. Section 6 lists what the numbers do and do not mean.

## 2. Bona fide source

* **Where.** Wikimedia Commons, found with the Commons search API (`filetype:video` with interview, Wikitongues,
  VOA interview and similar queries) and filtered by licence. 52 candidates were picked by hand for diversity and
  screened automatically.
* **Licences.** Every clip used is CC BY-SA 4.0 (29), CC BY 3.0 (8), CC BY 4.0 (1), CC0 (2), or public domain as
  a Voice of America production, a US Government work (4). Title, author, licence, licence URL, Commons page,
  window start times and the transcode used are in [`sources.csv`](sources.csv). No media is in the repository.
* **Used.** 44 clips: 39 for PAD (one subject per clip, `P01`–`P52` with gaps) and 41 windows for the pitch check
  (36 clips used for both). Eight candidates were dropped because no 6 s frontal window existed: interviewees looking
  at an off-camera interviewer, cutaways, or a face too small.
* **Screening** (`extract.py`, scratch only). The 720p Commons transcode was used where available, otherwise 480p.
  YuNet ran at 2 Hz over the first 300 s. A window was accepted when every sample had exactly one face with a score
  of at least 0.85, a nose offset under 0.12 IOD (about ±12° yaw), roll under 12° and an IOD of at least 40 px. The
  window is 6 s at the clip's **native frame rate** (24–30 fps), with `timestamps.txt` taken from the decoder's
  presentation times. A fixed 4:3 crop around the median face gives an IOD of about 80 px. It is resized to 640×480
  (downscaled or kept at scale for 23 clips, upscaled by up to 1.9× for the other 16) and saved as JPEG quality 90. A histogram test
  removed scene cuts. One PAD window was trimmed to 3.8 s and three pitch windows to 3.2–5.5 s.
* **Minors.** None. Every clip was checked by eye, and all subjects are adults.
* **Apparent spread** (by eye; not self-reported and not consented demographics, so it is not in `scores.jsonl`):
  24 male, 15 female. Apparent age 18–29: 13, 30–49: 17, 50 or over: 9, including two people over 90. Apparent
  Monk skin tone 1–3: 12, 4–7: 15, 8–10: 12. Seven wear glasses, and three wear a hat or head covering. Settings are
  mixed: indoor, outdoor, studio, a green screen, one backlit outdoor shot and two dim rooms. Speakers come from
  Africa, South and East Asia, Europe, Latin America and the Pacific. With n = 39, no per-group rate means anything.
  `IsoReportMain` suppresses slices below 5 subjects, and no demographic tags were given to it.

## 3. Attack simulation

[`tools/proxy-eval/simulate_attacks.py`](../../../tools/proxy-eval/simulate_attacks.py) contains code only. Each bona
fide window yields one item per species. The item has the same frame count and timestamps and the name
`Pnn-SIM_<SPECIES>-L0-D0-T1`. `L0` means uncontrolled source lighting and `D0` the source camera. Every random draw
is seeded by item and species and written to `params.jsonl`. All ranges are in the `RANGES` table at the top of the
script.

| Species (label, engine class) | What is simulated |
|---|---|
| `SIM_PRINT` (PRINT) | One still frame: the most confidently detected face in the middle 60 % of the window. It is printed at 4× resolution, either as a CMYK clustered-dot halftone at 15/75/0/45° with a 1.0–1.75 camera-px cell, or as an 8×8 ordered dither, alternating by item. The ink model is subtractive with fixed ink transmittances and paper white 0.86–0.97, which compresses the gamut. Ink spread is σ 0.8–1.4 print px. Paper mottle is 1.5–4 % and fibre noise 0.5–1.5 %. Room lighting has a 0–25 % gradient and a warm/cool tint. The print fills 85–110 % of the frame width, with a perspective tilt of up to 6 % of the width and ±4° rotation, so the edge and a plain wall show on most items. Hand-held jitter is three sinusoids per axis in 0.4–2.5 Hz plus drift: RMS 1.5–5 px, 0.2–0.8° and 0.3–1.2 % scale. There is **no facial motion**. |
| `SIM_PRINT_WARPED` (PRINT) | The same print bent around a vertical axis by an arc of 35–80°. It flexes by up to ±10° at 0.2–0.7 Hz, with cosine shading. |
| `SIM_PRINT_CUTOUT` (PRINT) | The same print with elliptical eye holes, 0.42–0.52 × 0.20–0.28 IOD, and a darkened rim. Each frame shows the live clip's own eyes through the holes, aligned to the print's eye landmarks, so blinks and eye movements are real. A physical attack shows the attacker's eyes instead. |
| `SIM_SCREEN_REPLAY` (VIDEO_REPLAY) | Every frame shown on a simulated RGB-stripe panel. The panel has 0.6–1.4 display pixels per camera pixel and a black-matrix row at 45–70 % brightness. The display pipeline applies gamma 0.85–1.15, a white point running blue (R 0.90–0.98, B 1.02–1.10), a black level of 0.02–0.05 and a saturation of 0.9–1.2. The screen fills 80–112 % of the width; the bezel is visible on 28 of 78 screen items. A Gaussian specular glare patch peaks at 8–30 % and is 35–90 px wide. Rolling refresh bands are 1.5–6 % deep, with a 120–480 px period, moving at 0.2–1.5 Hz. Half the screens are hand-held, and the rest stand with 15 % of the jitter. |
| `SIM_SCREEN_PHOTO` (SCREEN_REPLAY) | The same display showing the still frame. |

Every species then goes through the same capture camera. The camera applies Gaussian optics of σ 0.30–0.55 px:
in panel or print units before sampling, so moiré arises only from real aliasing. It samples at 2× and averages a
2×2 pixel aperture. Auto exposure settles within ±10 % of the bona fide frame mean. The RGGB Bayer mosaic carries
read noise of 1–2.5 DN and shot noise of 1.7–4 DN at mid grey, followed by a bilinear demosaic and JPEG quality
85–95.

**Where the simulation is easier than a physical attack:**

* The print is a perfect flat-field render with no printer banding.
* Lighting is uniform apart from a gradient.
* Hands, fingers and the attacker's body are never in shot.
* There is no real lens defocus from a print held close.
* Screen glare is a single smooth patch.
* The bona fide frame is already compressed, so the attack and the real video share the same source artefacts.

**Where it is harder for the detector than a physical attack:**

* The moiré is weak. The pixel-grid contrast left after the optics has a median of 0.25 % (10th–90th percentile
  0.01–6 %). Real recaptures at the wrong distance often show far stronger moiré.
* There is no real paper gloss, no specular highlight that moves with tilt, and no mismatch between ambient light on
  the paper and on the room.
* The colour gamut model is generic.

**What is not simulated at all:** masks; deepfakes; injection; depth or IR; the attacker's response to challenges;
a person holding the instrument. The cut-out eyes are the victim's own.

## 4. Commands

```bash
# build once (MAVEN_OPTS=-Xmx1g on this machine)
mvn -q -pl testkit -am -DskipTests install
mvn -q -pl testkit dependency:build-classpath -Dmdep.outputFile=/tmp/testkit.cp
CP="testkit/target/classes:$(cat /tmp/testkit.cp)"

# attacks (two batches, so the scratch disk never held more than about 1.1 GB of frames)
python3 tools/proxy-eval/simulate_attacks.py --corpus $SCRATCH/corpus/bf --out $SCRATCH/corpus/attA \
    --species print,screen_replay,screen_photo --yunet liveness-engine-onnx/models/face_detection_yunet_2023mar.onnx \
    --log $SCRATCH/corpus/attA/log.csv
python3 tools/proxy-eval/simulate_attacks.py --corpus $SCRATCH/corpus/bf --out $SCRATCH/corpus/attB \
    --species print_warped,print_cutout --yunet liveness-engine-onnx/models/face_detection_yunet_2023mar.onnx \
    --log $SCRATCH/corpus/attB/log.csv

# score: production detector threshold (no --detector-threshold), RESIDENT defaults
for m in passive challenge; do
  java -Xmx1500m -cp "$CP" io.mosip.vivid.testkit.corpus.CorpusScoreMain --corpus $SCRATCH/run \
       --out $SCRATCH/out/scores_$m.jsonl --models liveness-engine-onnx/models --mode $m --workflow RESIDENT
done
# per-frame signals and pose (new; see testkit/README.md)
java -Xmx1500m -cp "$CP" io.mosip.vivid.testkit.corpus.FrameTraceMain --corpus $SCRATCH/run \
     --out $SCRATCH/out/trace.csv --models liveness-engine-onnx/models --calib-ms 2500

# reports
java -cp "$CP" io.mosip.vivid.testkit.metrics.IsoReportMain --scores $SCRATCH/out/scores_passive.jsonl \
     --out docs/testing/proxy-eval --threshold 0.80 --title "mosip-vivid PROXY self-evaluation ..." \
     --context mode=passive-replay --context attacks=digital-simulation-not-physical-PAI ...
java -cp "$CP" io.mosip.vivid.testkit.metrics.IsoReportMain --scores $SCRATCH/out/scores_challenge.jsonl \
     --out docs/testing/proxy-eval/challenge --threshold 0.80 ...
```

Thresholds 0.85 and 0.90 were evaluated with `IsoReportMain --threshold` on the same RESIDENT scores, not by
replaying with the OPERATOR or SUPERVISOR workflow.

## 5. Results

### 5.1 Passive replay, RESIDENT, threshold 0.80 ([`iso30107.html`](iso30107.html), [`iso30107.json`](iso30107.json))

| Species | Responses | Accepted | APCER | Wilson 95 % CI | Non-responses |
|---|---|---|---|---|---|
| SIM_PRINT_WARPED | 37 | 31 | **83.8 %** | 68.9–92.3 % | 2 / 39 |
| SIM_PRINT | 36 | 30 | 83.3 % | 68.1–92.1 % | 3 / 39 |
| SIM_PRINT_CUTOUT | 35 | 29 | 82.9 % | 67.3–91.9 % | 4 / 39 |
| SIM_SCREEN_REPLAY | 39 | 17 | 43.6 % | 29.3–59.0 % | 0 / 39 |
| SIM_SCREEN_PHOTO | 39 | 11 | 28.2 % | 16.5–43.8 % | 0 / 39 |
| **Bona fide** | 35 | 2 rejected | **BPCER 5.7 %** | 1.6–18.6 % | **4 / 39 (BPNRR 10.3 %, CI 4.1–23.6 %)** |

ACER is 44.7 %. BPCER@APCER ≤ 5 % is 65.7 % (threshold 0.998) and BPCER@APCER ≤ 10 % is 54.3 %. The EER is
29 %, at threshold 0.994. Over the 195 attacks, APNRR is 4.6 %. At thresholds 0.85 and 0.90 every figure is the same,
except that screen-replay APCER drops to 41.0 % (16 of 39) at 0.90.

The fused score separates bona fide from screens (AUC 0.94–0.95) but not from prints (AUC 0.78–0.82). The median
fused score of an accepted print is 0.98–0.99, against a bona fide median of 0.997. No threshold below 1 rejects
prints without rejecting most real people.

Which mechanism decided:

* All 68 attack rejections were hard vetoes: S1 in 66, S3 in 1 and S4 in 1. None came from the fused score falling
  below the threshold.
* The two bona fide rejections were S1 vetoes on P23 and P26, both well-lit frontal faces.
* The four bona fide non-responses failed the quality gate or timed out: a backlit outdoor face (P12), two dim rooms
  (P28, P38) and a hand moving in front of the face (P06). Three of the four have apparent skin tone 8–10, and the
  fourth is in a dim room. With n = 4 this is a pointer to check, not a finding.

### 5.2 Challenge replay ([`challenge/iso30107.html`](challenge/iso30107.html))

With `active.mode = ALWAYS`, every attack fails, so APCER is 0 % for every species (upper CI bound 8.9–9.9 %). Every
bona fide recording fails too (BPCER 100 %), because no recording can answer randomly drawn prompts. Most sessions
end in `CHALLENGE_TIMEOUT`. This shows only that prompt-ignoring replays do not pass; it is not evidence of PAD.
The longer sessions also exposed **three bona fide clips vetoed by S4 rPPG**, which the 2.5 s passive sessions end
before (see 5.3).

### 5.3 Which signals separate the classes ([`signal-summary.json`](signal-summary.json))

Each item's figure is the confidence-weighted mean attack probability over its frames, from `FrameTraceMain`. AUC is
the probability that an attack item scores above a bona fide item. 0.5 means no separation.

| Signal | Bona fide mean | Print / warped / cut-out AUC | Screen replay AUC | Screen photo AUC | Notes |
|---|---|---|---|---|---|
| S1 CNN texture | 0.03 | 0.84 / 0.87 / 0.88 | 0.96 | 0.97 | The only signal that works. At the veto point (p ≥ 0.9, confidence ≥ 0.5) it catches 28 of 39 of each screen species but only 6–7 of 39 prints, and it vetoed 2 of 39 bona fide clips. |
| S2 moiré | 0.03 | 0.45–0.49 | 0.47 | 0.48 | No separation. The simulated moiré is weak (see section 3), so this is not proof that S2 fails on real screens. It never fired on real skin. |
| S3 colour | 0.05 | 0.47 | 0.76 | 0.77 | Weak separation for screens (blue shift), none for prints. |
| S4 rPPG | **0.67** | 0.52–0.61 | 0.51 | 0.35 | **Fires on real skin.** 32 of 39 bona fide clips have a mean attack probability ≥ 0.5, and 3 had veto-level frames. VP9-compressed 24–30 fps video probably destroys the pulse, and 6 s windows never fill the 8.5 s buffer. Either way, S4 gives no evidence here, and in longer sessions it would reject real people. |
| S5 parallax | 0.26 | 0.61–0.66 | 0.42 | 0.66 | Measured on free natural motion after a NOD challenge is signalled at 2.5 s, not on a prompted turn. Attacks move only by hand jitter, so confidence is low. This is weak and inconclusive. A prompted head turn is the case S5 was designed for and was not testable. |

Injection (always 0) and quality are not attack signals.

## 6. What the numbers mean and do not mean

* **A digital proxy, not ISO/IEC 30107-3 testing.** No physical presentation attack instrument was made or
  presented to a camera. The species are simulations, labelled `SIM_*` so that they are never confused with the
  corpus-ethics §5 species. A laboratory evaluation needs real instruments, real capture devices and an independent
  operator (`10-iso-30107-alignment.md` §5).
* **Small n.** There are 39 subjects and 35–39 presentations per species, so every rate has a confidence interval
  roughly ±12–15 points wide. The 5.7 % BPCER is compatible with anything from 1.6 % to 19 %.
* **The bona fide material is not webcam capture.** It was filmed by many cameras, often professional, then encoded
  as VP9 by Commons, re-scaled and saved as JPEG by us. Compression removes the skin micro-texture that S1 and S4
  depend on, and it is the same base the attacks were built from. This probably makes bona fide look more like an
  attack than live webcam frames would (S4 in particular). It also makes the attacks look more like bona fide (S1 on
  prints).
* **Interview footage is not enrolment.** Subjects talk, gesture and look away. Frontal windows were chosen, but the
  pose and expression variation is larger than at a registration counter, and the lighting is whatever the filmmaker
  chose.
* **Demographic spread** is apparent only, and too small for slices. Nothing here supports or refutes a fairness
  claim.
* **The passive window is 2.5 s.** Most sessions decide on about 60 frames, before S4 can contribute. This is the
  RESIDENT passive behaviour at the time of the run, and it is what was measured.

## 7. Threshold implications

* **Do not tune the passive threshold on these numbers, and it would not help anyway.** Accepted prints score
  0.98–0.99. Any threshold that rejects them rejects most real people: BPCER@APCER 5 % is 66 %.
* **Passive-only acceptance is not safe against prints in this engine.** The RESIDENT default when this ran
  (`ON_LOW_CONFIDENCE`) never triggered a challenge for a print, because the print looks confidently live.
  **Applied after this run:** the RESIDENT default is now `active.mode = ALWAYS`, like OPERATOR and SUPERVISOR
  (`LivenessPolicy.defaults`, `docs/design/04-passive-to-active-flow.md`). `ON_LOW_CONFIDENCE` stays available for a
  deployment with a stronger passive engine. The passive figures in section 5.1 are still the passive component of
  every workflow, because the replay forces `NEVER`. They were not re-run under the new default: a recording cannot
  answer challenges, so a re-run would reproduce section 5.2.
* **S4 rPPG should not veto on its own** until it has been measured on uncompressed webcam capture. On compressed
  real video it reported attacks for most real faces. Consider a calibration that keeps its confidence low unless the
  pulse SNR is clearly measurable, or a weight of 0 for S4 in RESIDENT until a corpus says otherwise. **Applied after
  this run:** `RppgSignal` is now fusion-only and never vetoes on its own. The three
  S4 bona fide vetoes in section 5.2 would not occur under that build. This has not been re-measured.
* **S2 moiré** neither helped nor hurt here. Its value against real screens is still unmeasured.

## 8. Pitch and gaze on real faces ([`pitch-summary.json`](pitch-summary.json))

This is the first time the pose path has seen real faces. `FrameTraceMain` runs the production `OnnxFrameAnalyzer`.
It signals a `NOD` challenge at 2.5 s, which freezes `PitchBaseline` exactly as the orchestrator does, so pitch after
that point is `HeadPose.estimateRelative` against the subject's learned neutral. Material:

* 41 windows selected for the largest YuNet nose-drop range in each clip: the most nod-like passages available;
* the 39 talking-head PAD windows as controls.

None of the clips contains a prompted nod. Movements are natural: glances down at notes, chin lifts while thinking,
laughs. There is no ground-truth angle. The extreme frames of every window were inspected by eye.

* **The baseline works.** `PitchBaseline` accepted a neutral in 79 of 80 windows. Absolute pitch from the
  population constants varied from −7° to +13° for people looking straight ahead, which confirms the per-person
  bias that `PitchBaseline` was written for.
* **Talking alone gives ±5–10° pitch noise.** In the talking windows the median per-window extremes are −5.4° and
  +4.6°. Five of 39 reached −10° (the NOD threshold) and 2 of 39 reached +10°, mostly on laughs and wide mouth
  opening, despite the mouth-movement cap in `estimateRelative`.
* **Real downward head movement registers only partly.** Two clear look-downs read −14° and −15° (P33, P19). Others
  that are obvious by eye read close to 0 or even upward:
  * P24's head is strongly bowed and rolled, and reads +1°;
  * P38 looks down at her notes and reads −3° to +8°;
  * P31 and P35 look down with lowered eyelids and read +3° and +5°.
  Upward chin lifts read correctly in sign (+9° to +17°: P17, P24, P30), though below the 15° LOOK threshold in most
  windows.
* **Vertical gaze is not usable.** Even with blink frames removed (eye aspect below 0.25), 17 of 27 talking windows
  move the gaze estimate by at least 0.5 from its neutral. 0.5 is the LOOK_UP / LOOK_DOWN gaze threshold, and about
  a third of the frames exceed it. Downcast eyes sometimes read as fully "up" (P38: +1.0).

**Conclusion.** On real faces, downward and upward pitch through YuNet's five landmarks has the right sign for
clear, large head movements. It misses or inverts some real look-downs, especially when the eyelids lower. Talking
alone produces excursions near the NOD threshold. Vertical gaze is noise. These clips support keeping `NOD`,
`LOOK_UP` and `LOOK_DOWN` **out** of the default pool, as they are now. The pool was not changed. A prompted test
with real people and a reference angle, for example a phone IMU on the head, is still needed.

## 9. Engine and testkit changes made during this evaluation

* **Bug fixed: `IsoReportMain` counted hard-vetoed attacks as accepted.** It classified only by
  `score >= threshold`. The fused score of a vetoed session is computed before the veto and was 0.8 or higher in
  45 of the 70 vetoed presentations here, so screen APCER would have been reported far too high. Fix:
  `IsoReportMain.effectiveScore`. A response rejected for any reason other than `LIVENESS_BELOW_THRESHOLD` (hard
  veto, challenge failure, identity drift) is scored 0 and rejected at every threshold. This matches the
  `decision != LIVE` definition in `10-iso-30107-alignment.md` §3. Regression test:
  `IsoReportMainTest.vetoedOrChallengeFailedResponsesAreRejectedAtEveryThreshold`.
* **`CorpusScoreMain` writes per-signal scores.** A new `signals` field (`LivenessResult.signalScores`) is documented
  in `corpus-ethics.md` §10.2.
* **New `FrameTraceMain`** (testkit `corpus` package). It writes a per-frame CSV of detection, quality, yaw, engine
  pitch, absolute pitch, nose drop and fraction, vertical gaze, eye and mouth aspect, and every signal's probability
  and confidence. It contains measurements only, no pixels. Test: `CorpusScoreMainTest.frameTraceWritesOneRowPerFrame`.

## 10. Data handling

The downloaded videos, extracted frames, simulated attacks, contact sheets, `scores.jsonl` and traces were kept
under the session scratch directory and deleted when the evaluation finished. The repository holds only
`sources.csv` (attribution), the two generated reports, `signal-summary.json` and `pitch-summary.json` (aggregate
numbers per item id), and the simulator code. The reports contain no images. Their only graphic is the inline SVG
DET curve.
