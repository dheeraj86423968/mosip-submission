# Passive PAD improvement on the real-face proxy, November 2026

**Date:** 1 Nov 2026. **Engine:** `mosip-vivid-onnx` 0.1.0, no model bundle. **Policy:** `builtin-1.0` (before) and
`builtin-1.1` (after), RESIDENT, replayed in passive mode (`active.mode = NEVER`). **Data:** the proxy corpus of
[`README.md`](README.md): real adults in openly licensed video, with digitally simulated attacks.

> **Headline.** On the 15 held-out people, the new configuration lowers the APCER of every print species from
> 75–92 % to 17–25 %. Screen replay falls from 43 % to 0 %, and still photos on a screen from 25 % to 8 %. The largest
> species APCER falls from 92 % to 25 %, and ACER from 46 % to 16 %. BPCER went from 0 of 13 to 1 of 13: the
> difference is +7.7 points, with a subject-bootstrap interval of 0 to +25 points. That is not a demonstrated increase,
> and it is not a demonstrated absence of one either. What ships:
> * S1 crops the bundled anti-spoof model at 1.2× the face box instead of 1.5×.
> * Moiré and colour fusion weights drop to 0.05.
> * The passive threshold rises from 0.80 to 0.997.
>
> The original two-model Silent-Face ensemble was converted from its Apache-2.0 weights and evaluated. It did
> **not** help, and neither did a mirrored-crop view, so neither ships. These are still digital attacks on compressed
> video, with about 13 people per held-out rate.

## 1. Method

1. **Corpus rebuilt** from [`sources.csv`](sources.csv) with
   [`tools/proxy-eval/fetch_and_extract.sh`](../../../tools/proxy-eval/fetch_and_extract.sh) and
   [`extract_windows.py`](../../../tools/proxy-eval/extract_windows.py). Each clip was downloaded once, its window
   extracted, and the clip deleted. The 24 Oct extraction script had been kept in scratch only, so this is a
   re-implementation from the description in `README.md` §2. The window is 6 s at native fps from
   `pad_window_start_s`, cropped 4:3 around the median YuNet face so the inter-ocular distance is about 80 px, saved
   at 640×480 as JPEG q90. At a scene cut the longest shot is kept: P43 gives 3.8 s, the same length the 24 Oct
   report gave for its one trimmed window.
   * **38 of the 39 people** were used. **P18 is excluded.** Its Commons transcode, which has not changed since
     February 2024, decodes at about 2.5 fps in the window with OpenCV's FFmpeg. Neither the original `.ogg` nor the
     transcode yields 30 fps there, so that window cannot be reproduced.
2. **Attacks regenerated** with the unchanged, seeded
   [`simulate_attacks.py`](../../../tools/proxy-eval/simulate_attacks.py) (seed `2026`): 5 species × 38 people.
   * The print method alternates halftone and dither by the index of the bona fide item in the corpus listing.
     Without P18, people after P18 get the other method than on 24 Oct.
3. **Analyse once, replay many.** The new
   [`PadReplayMain`](../../../testkit/src/main/java/io/mosip/vivid/testkit/corpus/PadReplayMain.java) (testkit)
   works in two steps:
   * `dump` feeds every frame through the production `OnnxFrameAnalyzer` once and stores each `FrameAnalysis`: every
     signal score, quality, observation and embedding. It contains measurements, no pixels. On the analyzer's own
     face box it also stores P(real) for every S1 candidate view.
   * `replay` runs any configuration through the real `LivenessOrchestrator`. A stub engine returns the stored
     analyses frame by frame. S1 can optionally be recomputed from the views, exactly as `CnnTextureSignal` does,
     with any policy weights and thresholds.
   * **Check:** replaying the baseline gives the same score, decision, failure reason and frame count as
     `CorpusScoreMain` on all 152 batch-A items (0 differences).
   * The final configuration was also re-run with the real engine and the shipped defaults (§6).
4. **Statistics.** [`pad_study.py`](../../../tools/proxy-eval/pad_study.py) uses the `IsoReportMain` conventions: a
   veto, challenge or identity rejection scores 0, and non-responses are excluded. It reports:
   * APCER per species with Wilson 95 % intervals;
   * BPCER, ACER = (max APCER + BPCER) / 2, and BPCER@APCER ≤ 5 % / 10 %;
   * a **paired subject-level bootstrap** of every difference (2000 resamples of people; all of a person's items
     move together).

## 2. Split

[`split-2026-11.csv`](split-2026-11.csv) holds 23 training people and 15 held-out people. The order is by
`sha256("mosip-vivid-proxy-split-2026-11|<id>")`, and the split was fixed before any score was looked at. Every
choice below (S1 candidate, fusion weights, threshold) was made on the training people with a rule stated in
advance: keep the training BPCER at or below the baseline (2 of 20), then minimise ACER. The held-out people were
scored once, for the configurations listed in `tools/proxy-eval/configs-2026-11/cfg-final.jsonl`.

## 3. Baseline reproduction (all 38 people, threshold 0.80)

| Species | 24 Oct (39 people) | Rebuilt (38 people) |
|---|---|---|
| SIM_PRINT | 83.3 % (30/36) | 82.4 % (28/34) |
| SIM_PRINT_WARPED | 83.8 % (31/37) | 88.2 % (30/34) |
| SIM_PRINT_CUTOUT | 82.9 % (29/35) | 82.4 % (28/34) |
| SIM_SCREEN_REPLAY | 43.6 % (17/39) | 36.1 % (13/36) |
| SIM_SCREEN_PHOTO | 28.2 % (11/39) | 31.4 % (11/35) |
| BPCER | 5.7 % (2/35; P23, P26) | 6.1 % (2/33; P23, P26) |
| Bona fide non-responses | 4 | 5 (P06, P12, P13, P28, P38) |

Every figure is inside the earlier Wilson interval. The same two people are rejected by the same S1 veto.

## 4. S1 candidates (training people only)

**Item-level AUC.** Each item's statistic is its mean confidence-weighted S1 attack log-odds over the passive window.
AUC is the probability that an attack item scores above a bona fide item.

| Candidate | Print | Warped | Cut-out | Screen replay | Screen photo | All prints | All screens |
|---|---|---|---|---|---|---|---|
| A15: bundled model, crop 1.5× (baseline) | 0.868 | 0.877 | 0.897 | 0.951 | 0.953 | 0.881 | 0.952 |
| A15f: + mirrored view (TTA) | 0.874 | 0.883 | 0.903 | 0.958 | 0.964 | 0.887 | 0.961 |
| **A12: bundled model, crop 1.2×** | **0.923** | **0.923** | **0.945** | **0.986** | **0.994** | **0.930** | **0.990** |
| A20: crop 2.0× | 0.806 | 0.866 | 0.864 | 0.939 | 0.941 | 0.845 | 0.940 |
| A12+A15: two crops, mean P(real) | 0.903 | 0.893 | 0.913 | 0.966 | 0.975 | 0.903 | 0.971 |
| A15+A27: two crops | 0.753 | 0.838 | 0.779 | 0.903 | 0.947 | 0.790 | 0.926 |
| B27: Silent-Face V2, 2.7×, upstream crop | 0.791 | 0.757 | 0.769 | 0.860 | 0.896 | 0.772 | 0.878 |
| C40: Silent-Face V1SE, 4.0×, upstream crop | 0.860 | 0.779 | 0.824 | 0.872 | 0.907 | 0.821 | 0.890 |
| B27+C40: Silent-Face ensemble | 0.844 | 0.798 | 0.794 | 0.870 | 0.921 | 0.812 | 0.896 |
| B27f+C40f: ensemble + mirrored views | 0.838 | 0.792 | 0.806 | 0.875 | 0.917 | 0.812 | 0.897 |
| A15+B27 | 0.832 | 0.810 | 0.840 | 0.943 | 0.964 | 0.827 | 0.954 |
| A15+C40 | 0.879 | 0.830 | 0.848 | 0.945 | 0.968 | 0.852 | 0.957 |
| A15+B27+C40 | 0.858 | 0.822 | 0.828 | 0.937 | 0.962 | 0.836 | 0.950 |
| B27sq / C40sq: square reflect-padded crop | 0.729 / 0.605 | 0.731 / 0.545 | 0.727 / 0.557 | 0.852 / 0.759 | 0.922 / 0.875 | 0.729 / 0.569 | 0.888 / 0.818 |

* **Silent-Face ensemble.** Source and licence:
  * MiniFASNetV2 at 2.7× and MiniFASNetV1SE at 4.0× from minivision-ai/Silent-Face-Anti-Spoofing, Apache-2.0,
    commit `b6d5f04`.
  * No ONNX export with checkable provenance was found, so the official `.pth` weights were converted with
    [`convert_silentface.py`](../../../tools/proxy-eval/convert_silentface.py): torch 2.15.0+cpu, opset 13.
    ONNX Runtime matches PyTorch to 1e-7, and the export is byte-reproducible.
  * SHA-256 values are in [`liveness-engine-onnx/models/README.md`](../../../liveness-engine-onnx/models/README.md).

  Two preprocessing details are essential:
  * Upstream feeds raw 0..255 BGR values. The engine's preset had divided by 255.
  * Upstream crops the scaled box kept inside the frame (`_get_new_box`). The engine's reflect-padded square drops
    V1SE from 0.82 to 0.57 on prints.

  With the faithful recipe, the ensemble is still weaker than the bundled model alone, and averaging it into the
  bundled model makes prints worse (0.881 → 0.827–0.852). **Not shipped.** The engine now has the correct recipe
  (`inputScale`, `cropMode=fit`) for anyone who wants to try it on their own data. Discovery uses these files only
  when no other anti-spoof model is present.
* **Test-time augmentation** (mirrored crop) gives at most +0.01 AUC. In the decision replay it accepted more prints
  (58/65 against 54/65 at the default policy). **Not shipped.** The engine supports it as `flip=true`.
* **Multi-crop.** Adding a wider crop hurts. A tighter single crop, 1.2×, is clearly better on the training people.
  This was the candidate taken forward.

## 5. Learned fusion (training people only)

**Fit.** Per item, the features are the mean `c·logit(p)` of each fusable passive signal over the passive window
(`pad_study.py fit`). The model is L2-regularised logistic regression, class-balanced, with weights constrained to be
non-negative. Grouped by person: 5-fold cross-validation, repeated 20 times. The L2 strength was chosen by
cross-validated log-loss.

| S1 | Window | L2 | Weight S1 | Weight moiré | Weight colour | Weight rPPG | CV spread of S1 weight (mean ± sd, min–max) |
|---|---|---|---|---|---|---|---|
| A15 | passive | 1.0 | 0.290 | 0.000 | 0.000 | – | 0.293 ± 0.031 (0.256–0.395) |
| **A12** | passive | 0.1 | **0.491** | **0.000** | **0.000** | – | 0.499 ± 0.043 (0.435–0.643) |
| A15 | full 6 s | 1.0 | 0.287 | 0.000 | 0.000 | 0.000 | 0.290 ± 0.030 (0.252–0.385) |

* **Moiré and colour carry no information here.** Their cross-validated weights never exceed 0.008 and 0.023.
  Unconstrained, they come out *negative* (moiré −0.25, colour −0.63 relative to S1), which would make the fusion
  non-monotone: more attack evidence would raise the liveness score. The non-negative fit was kept.
* **rPPG and parallax.** Their confidence is 0 in the 2.5 s passive window, so no passive weight can be learned for
  them. On the full 6 s window, rPPG also gets 0. Their policy weights were left unchanged, because they act only in
  longer and challenge sessions, which this corpus cannot score.
* **What ships.** The fusion is a weighted *mean*, so only weight ratios matter. S1 stays at 1.0. Moiré and colour
  are set to **0.05**, not 0: a weight of 0 also disables their hard veto, and 0.05 keeps the veto while removing
  almost all of their dilution of S1. The injection signal (1.0, `p = 0`, `c = 0.3`) is a constant offset that the
  threshold absorbs.
* **Threshold.** The logistic intercept assumes a plain sum, while the orchestrator uses an exponential moving
  average of a sigmoid. So the threshold was set by exact replay on the training people over a grid
  (`cfg-grid*.jsonl`), using the rule from §2.
  * A12 with weights 0.05 meets the rule at 0.996–0.997. At 0.998 a third training bona fide is rejected.
  * **0.997** was chosen. It sits above the two bona fide training rejections (fused 0.987 and 0.990) and below the
    next bona fide score (0.9976), rather than at the edge.

## 6. Results

Configurations (all RESIDENT, passive replay, `padHardThreshold` 0.90):

| Configuration | S1 | Moiré / colour weight | Passive threshold |
|---|---|---|---|
| baseline (`builtin-1.0`) | bundled, crop 1.5× | 1.0 / 1.0 (compiled defaults) | 0.80 |
| **new (`builtin-1.1`)** | bundled, crop 1.2× | 0.05 / 0.05 | **0.997** |

### 6.1 Held-out people (15 people; 13 bona fide responses)

| Species | Baseline APCER | Wilson 95 % CI | New APCER | Wilson 95 % CI | Paired difference (bootstrap 95 %) |
|---|---|---|---|---|---|
| SIM_PRINT | 75.0 % (9/12) | 46.8–91.1 % | 25.0 % (3/12) | 8.9–53.2 % | −50.0 pts (−77.8 to −22.2) |
| SIM_PRINT_WARPED | 92.3 % (12/13) | 66.7–98.6 % | 23.1 % (3/13) | 8.2–50.3 % | −69.2 pts (−92.3 to −41.7) |
| SIM_PRINT_CUTOUT | 91.7 % (11/12) | 64.6–98.5 % | 16.7 % (2/12) | 4.7–44.8 % | −75.0 pts (−100 to −46.2) |
| SIM_SCREEN_REPLAY | 42.9 % (6/14) | 21.4–67.4 % | 0.0 % (0/14) | 0.0–21.5 % | −42.9 pts (−69.2 to −16.7) |
| SIM_SCREEN_PHOTO | 25.0 % (3/12) | 8.9–53.2 % | 7.7 % (1/13) | 1.4–33.3 % | −17.3 pts (−50.0 to +14.3) |
| **BPCER** | **0.0 % (0/13)** | 0.0–22.8 % | **7.7 % (1/13)** | 1.4–33.3 % | **+7.7 pts (0.0 to +25.0)** |
| Max APCER | 92.3 % | | 25.0 % | | −67.3 pts (−91.7 to −38.5) |
| ACER | 46.2 % | | 16.3 % | | −29.8 pts (−42.6 to −15.8) |
| BPCER@APCER ≤ 5 % | 30.8 % | | 46.2 % | | |
| BPCER@APCER ≤ 10 % | 23.1 % | | 38.5 % | | |
| Bona fide non-responses | 2 (P12, P38) | | 2 (same) | | |

**What is and is not shown.**
* **Prints.** The warped and cut-out print APCERs move outside each other's Wilson intervals. The flat-print
  intervals overlap (46.8–91.1 % against 8.9–53.2 %), but the paired bootstrap of every print species excludes 0,
  and so does the bootstrap of all prints pooled: −64.9 pts (−87.1 to −39.1).
* **Screens.** Screen replay improves, with a paired interval that excludes 0. The screen-photo improvement is
  **not** shown: its interval crosses 0.
* **BPCER.** The one new held-out rejection is P43, the 3.8 s window of a 90-year-old in a VOA interview (fused
  0.955). The change in BPCER is inside both Wilson intervals, and its bootstrap interval touches 0. **No increase is
  shown, but with 13 bona fide responses no increase below about 25 points could be excluded.**
* **BPCER@APCER ≤ 5 % / 10 % got worse on the held-out people.** These single-threshold sweep figures are very noisy
  with 12–14 attacks per species: one accepted attack is already 8 %. On the training people and on the full set
  they improved (§6.2). This is reported as it came out.

### 6.2 All 38 people (continuity with the 24 Oct report)

| Species | Baseline APCER (Wilson 95 %) | New APCER (Wilson 95 %) |
|---|---|---|
| SIM_PRINT | 82.4 % (28/34; 66.5–91.7) | 26.5 % (9/34; 14.6–43.1) |
| SIM_PRINT_WARPED | 88.2 % (30/34; 73.4–95.3) | 32.4 % (11/34; 19.1–49.2) |
| SIM_PRINT_CUTOUT | 82.4 % (28/34; 66.5–91.7) | 26.5 % (9/34; 14.6–43.1) |
| SIM_SCREEN_REPLAY | 36.1 % (13/36; 22.5–52.4) | 0.0 % (0/36; 0.0–9.6) |
| SIM_SCREEN_PHOTO | 31.4 % (11/35; 18.6–48.0) | 2.8 % (1/36; 0.5–14.2) |
| BPCER | 6.1 % (2/33; 1.7–19.6) | 9.1 % (3/33; 3.1–23.6) |
| ACER | 47.1 % | 20.7 % |
| BPCER@APCER ≤ 5 % / 10 % | 66.7 % / 39.4 % | 33.3 % / 27.3 % |

* All-person paired differences: ACER −26.4 pts (−34.3 to −18.2) and BPCER +3.0 pts (−6.5 to +13.9).
* These figures include the 23 training people the choices were made on, so they are optimistic. The held-out
  table in §6.1 is the evidence.
* The bona fide rejections changed:
  * baseline: P23 and P26, S1 vetoes;
  * new: P23 (training, fused 0.987), P25 (training, 0.990) and P43 (held out, 0.955), all below the threshold.

### 6.3 Ablations and alternatives (pre-specified, threshold as listed)

| Configuration | Train BPCER | Train prints accepted | Train screens accepted | Held-out BPCER | Held-out prints accepted | Held-out screens accepted | Held-out ACER |
|---|---|---|---|---|---|---|---|
| baseline | 2/20 | 54/65 | 15/45 | 0/13 | 32/37 | 9/26 | 46.2 % |
| policy-default.json weights of `builtin-1.0` (moiré 1.0, colour 0.6, rPPG 0.8, parallax 1.2), 0.80 | 2/20 | 54/65 | 15/45 | 0/13 | 32/37 | 9/26 | 46.2 % |
| S1 crop 1.2× only (0.80) | 0/20 | 50/65 | 16/45 | 0/13 | 32/37 | 10/27 | 46.2 % |
| weights 0.05 and threshold 0.997 only (crop 1.5×) | 4/20 | 25/65 | 2/45 | 2/13 | 14/37 | 2/26 | 30.8 % |
| **new** (both) | 2/20 | 21/65 | 0/45 | 1/13 | 8/37 | 1/27 | 16.3 % |
| Silent-Face ensemble (0.80) | 0/20 | 60/65 | 25/45 | 0/13 | 31/37 | 16/26 | 46.2 % |
| Mirrored view (0.80) | 2/20 | 58/65 | 17/45 | 0/13 | 33/37 | 11/26 | 45.8 % |

**Reading the ablations.**
* Neither part works alone.
  * The tighter crop alone hardly changes decisions at the old threshold: accepted prints still score high on the
    diluted fused score.
  * Re-weighting and the higher threshold alone reject many attacks, but double the training BPCER.
  * Together they keep the training BPCER at the baseline and give the largest drop in APCER.
* On held-out people, the S1 AUC gain from the tighter crop is small: all prints 0.915 → 0.933, all screens
  0.972 → 0.980. Most of the improvement comes from **letting S1 decide**. Before, the fused score was an average in
  which moiré, colour and the injection prior pulled every face, including prints, to about 0.98–0.99.

### 6.4 Real engine with the shipped defaults

[`CorpusScoreMain`](../../../testkit/src/main/java/io/mosip/vivid/testkit/corpus/CorpusScoreMain.java) was re-run
over all 228 items with the shipped code: discovery, crop 1.2× and `LivenessPolicy.defaults` `builtin-1.1`
(policy string `builtin-1.1+passive-replay`). It matches the replayed "new" configuration **exactly**: 228 of 228
items have the same decision and failure reason, with a maximum fused-score difference of 0.0. The tables above are
therefore the numbers of the engine as shipped.

## 7. What changed in the code and policy

* **`liveness-engine-onnx`**
  * `AntiSpoofModelConfig`: the bundled model's crop is now 1.2× (`BUNDLED_CROP_SCALE`).
  * New per-model `inputScale` and `flip` (mirrored-view TTA, off by default).
  * New `cropMode` = `square` | `fit` (the upstream Silent-Face crop, `ImageOps.cropFaceFit`,
    `FaceContext.faceCropFit`).
  * The Silent-Face preset now uses raw 0..255 input and the `fit` crop. **This fixes a bug:** the old preset
    divided by 255 and padded a square crop.
  * `engine.properties` keys `antispoof[2].inputScale`, `.flip` and `.cropMode`.
  * `OnnxEngineFactory.discover` no longer mixes Silent-Face files into the bundled model; it uses them only when they
    are the only anti-spoof models present.
* **`liveness-api` `LivenessPolicy.defaults` and `policy/.../policy-default.json`** (`builtin-1.1`):
  * moiré and colour weights 0.05;
  * passive threshold 0.997 for RESIDENT, OPERATOR and SUPERVISOR.

  OPERATOR and SUPERVISOR previously had 0.85 and 0.90. On the new score scale those would be far *looser* than
  RESIDENT, so they take the only value validated here. Their extra strictness stays in the challenge count, the
  retries and the lock. A deployment may still tighten locally.
* **`testkit`**: new `PadReplayMain` (dump and replay).
* **`tools/proxy-eval`**: `extract_windows.py`, `fetch_and_extract.sh`, `convert_silentface.py`, `pad_study.py`,
  `run_improvement_2026_11.sh` and `configs-2026-11/`.
* **Synthetic drawn faces.**
  * e2e pins the S1 crop at 1.5× in `SyntheticSubjectEngine`. The tighter crop of a cartoon is pure drawn texture,
    which the network reads as an attack, and drawn faces cannot evaluate passive PAD.
  * The Attack Lab needed no change: its headless test still passes a live operator and vetoes the print.
  * The red-team gate threshold follows the RESIDENT default, now 0.997, and its baseline was regenerated with
    `--update-baseline`. The result has no regression and 21 attack variants newly rejected, but all 24 drawn
    bona fide takes are now rejected (14 before); see [`../redteam-trend.md`](../redteam-trend.md) §5.

## 8. Limits

* **Digital attacks, not presentation attack instruments.** Every attack is a simulation (`README.md` §3). The
  threshold is now very close to 1. That reflects how S1 scores *this* material, and **it must be re-calibrated on
  real webcam captures and physical prints and screens before a deployment relies on it**. Through the signed-policy
  path, this is a policy update, not a code change.
* **Compressed video.** Bona fide frames are VP9 transcodes of interview footage, re-scaled and JPEG-saved. Live
  webcam frames carry more skin texture. That should make S1 *more* confident on live faces, but this is untested.
* **Small n.** There are 15 held-out people and 12–14 presentations per held-out species, so one item moves a rate by
  7–8 points. The BPCER interval allows up to 33 %.
* **The threshold is on the edge of the score scale.** Bona fide fused scores cluster at 0.9998, while the logit is
  clipped at 1e-4. Small changes to S1 (another model, a bundle calibration) or to the injection prior shift where
  0.997 falls. Any such change needs a new calibration run, which `PadReplayMain` makes cheap.
* **Passive window only.** rPPG and parallax are silent in 2.5 s, and challenge sessions cannot be replayed from
  recordings. Their weights were not learned.
* **Re-implemented extraction.** It differs in detail from the 24 Oct scratch script. Baseline figures reproduce
  within their intervals (§3), and P18 is missing.
* **Fairness.** Nothing here supports or refutes a fairness claim. The one new held-out rejection is a very old person
  in a short, close window, and the earlier non-responses were dark-skinned or dim-room clips. Check this on a real
  corpus.

## 9. Commands

```sh
# once: build, classpath, and (optional, for the Silent-Face views) the converted models in $W/onnx
MAVEN_OPTS=-Xmx1g mvn -o -q install -DskipTests
W=~/.cache/vivid-eval            # outside the repository; about 3 GB free while frames exist
# Silent-Face: clone minivision-ai/Silent-Face-Anti-Spoofing, then in a venv with torch (CPU), onnx and onnxruntime:
#   python tools/proxy-eval/convert_silentface.py <checkout> $W/onnx

# everything (extract, simulate, dump, replay, analyse); or name steps
tools/proxy-eval/run_improvement_2026_11.sh $W
tools/proxy-eval/run_improvement_2026_11.sh $W analyse

# individual pieces
java -Xmx1500m -cp "$CP" io.mosip.vivid.testkit.corpus.PadReplayMain dump --corpus $W/runA --out $W/out/dumpA.jsonl.gz \
     --models liveness-engine-onnx/models --log $W/attA/log.csv --features $W/out/framesA.csv --view A12=antispoof_minifasnetv2se_128.onnx@1.2 ...
java -Xmx1500m -cp "$CP" io.mosip.vivid.testkit.corpus.PadReplayMain replay --dump $W/out/dumpA.jsonl.gz --dump $W/out/dumpB.jsonl.gz \
     --configs tools/proxy-eval/configs-2026-11/cfg-final.jsonl --out-dir $W/out/final
python3 tools/proxy-eval/pad_study.py metrics $W/out/final/baseline.jsonl $W/out/final/new.jsonl \
     --split docs/testing/proxy-eval/split-2026-11.csv --which heldout
python3 tools/proxy-eval/pad_study.py compare $W/out/final/baseline.jsonl $W/out/final/new.jsonl \
     --split docs/testing/proxy-eval/split-2026-11.csv --which heldout
python3 tools/proxy-eval/pad_study.py fit --features $W/out/framesA.csv $W/out/framesB.csv \
     --split docs/testing/proxy-eval/split-2026-11.csv --signals cnn_texture moire color_stats --nonneg --s1 A12
tools/proxy-eval/run_improvement_2026_11.sh $W clean   # delete frames and attacks
```

Aggregate figures are in [`improvement-2026-11.json`](improvement-2026-11.json). No media, frames or per-frame
dumps are in the repository.
