# Red team in CI: synthetic attacks, regression gate and trend

**What this is.** A nightly job (`.github/workflows/redteam.yml`) renders simulated presentation attacks from
device-mock's drawn synthetic faces, scores them through the real `liveness-core` orchestrator and ONNX engine, and
records APCER per species, BPCER and ACER. It fails when an attack variant that used to be rejected is now accepted,
and writes each such bypass out as a regression fixture.

**What this is not.** It does not measure presentation attack detection. The faces are cartoons drawn with
`java.awt`, and the attacks are digital simulations of prints and screens. MiniFASNet, the S1 CNN, was trained on
photographs of real people, so on these frames its output has little to do with liveness: it vetoes 14 of 24 drawn
bona fide sessions (§4). The numbers below are change detectors for this code base. A change in a number means the
engine's behaviour on fixed inputs changed. The numbers are not error rates of the engine on people. Real-face figures
are in `docs/testing/proxy-eval/README.md`, and those are digital proxies too.

PLAN.md §17.5 describes a generative attack pipeline: diffusion face-swap, GAN print and screen synthesis, 3D-rendered
masks, and a loop that feeds confirmed bypasses back into training. **None of that exists here.** §6 lists what was
built and what was not.

## 1. Pipeline

```
RedTeamCorpusMain   draw variants (seeded) -> render 640x480 JPEG frames + timestamps.txt, capture-log.csv, variants.jsonl
CorpusScoreMain     --mode passive and --mode challenge, RESIDENT, YuNet threshold 0.35 -> scores_<mode>.jsonl
IsoReportMain       iso-<mode>/iso30107.{json,html} (threshold 0.80, no slices)
RedTeamGateMain     per-variant outcomes vs docs/testing/redteam/baseline.json -> fixtures/, trend.jsonl, trend.md, summary.md
```

Run it locally:

```sh
tools/redteam/run-redteam.sh                       # builds testkit (-am), renders 108 items, scores both modes, runs the gate
tools/redteam/run-redteam.sh --no-challenge --subjects 4 --out /tmp/rt     # quicker
MVN_ARGS=-o tools/redteam/run-redteam.sh --no-build                         # offline, testkit already compiled
tools/redteam/run-redteam.sh --update-baseline     # rewrites the committed baseline; review the diff first
```

The models must be in `liveness-engine-onnx/models` (`liveness-engine-onnx/models/README.md`). Without them the script
prints a notice and exits 0, and the workflow emits a `::notice`. Neither one scores anything. Exit codes: 0 means no
regression or skipped, 1 means a regression was found, 2 means a pipeline error.

The code is in `testkit/src/main/java/io/mosip/vivid/testkit/redteam/`: `AttackSpecies`, `RedTeamVariant`,
`RedTeamRenderer`, `RedTeamCorpusMain` and `RedTeamGateMain`. The renderer reads no media and writes no real faces.

### YuNet threshold 0.35

YuNet scores drawn faces near its decision boundary. Like `e2e-tests`, the red team runs the detector at 0.35. The
production threshold is 0.7. Every other model, signal and threshold is the production configuration, and the
passive threshold is RESIDENT's 0.80.

## 2. Species

Each synthetic subject (`P01`..`P12`) has a live session: 4 s at 15 fps, two blinks, a 2–9° head turn, an optional
slight smile, and a random face position, scale, background and mirroring. Each subject gives two bona fide takes
(`T1`, `T2`) and one variant of every attack species. All attack variants come from the subject's take-1 session.
Every species, bona fide included, passes through the same capture-camera model: Gaussian optics, a 2×2 pixel
aperture, exposure gain, room-light tint, read and shot noise, and JPEG at quality 85–95. The models are reduced Java
ports of `tools/proxy-eval/simulate_attacks.py`.

| Label | Engine class | Simulates | Leaves out |
|---|---|---|---|
| `SIM_PRINT` | PRINT | One still frame printed as CMY clustered-dot halftone (15°/75°/0°) or 8×8 ordered dither, with ink spread, ink density, paper white, mottle and fibre noise; hand-held (three-sinusoid jitter), rotated, sheared and lit by a gradient in front of a wall | real ink and paper spectra, specular sheen, true perspective, paper edges held by fingers |
| `SIM_PRINT_WARPED` | PRINT | The same print bent around a vertical axis (35–80°) and flexing slowly, with cosine shading | creases, non-cylindrical bending |
| `SIM_PRINT_CUTOUT` | PRINT | The same print with elliptical eye holes, a shadowed rim, and the subject's own blinking eyes aligned behind each hole on every frame | the attacker's different eyes and skin, depth of the hole edge |
| `SIM_SCREEN_REPLAY` | VIDEO_REPLAY | Every live frame on an RGB-stripe panel (0.6–1.4 display px per camera px) with black-matrix rows; the grid is attenuated by the optics and aliases into moiré; display gamma, white point, black level, saturation, glare patch, rolling refresh banding, bezel, hand-held or stand | real panel subpixel layouts, polarisation, refresh flicker, true moiré from a physical lens |
| `SIM_SCREEN_PHOTO` | SCREEN_REPLAY | One still frame on the same panel | as above |
| `SIM_REPLAY_JITTER` | VIDEO_REPLAY | The recording on a fine-pitch panel (grid nearly invisible), played at 0.85–1.15× speed from an offset, quantised to source frames, with 2–15 % dropped frames and 4–25 ms timestamp jitter | codec artefacts of a real playback chain |
| `SIM_LOWRES_REPLAY` | VIDEO_REPLAY | The recording down-sampled 2.5–5×, JPEG-compressed at quality 25–55, shown full frame and re-captured out of focus (σ 0.8–1.6 px) at quality 50–75 | real phone camera pipelines |

Every parameter and its range is in `RedTeamVariant.draw`.

## 3. Variant ids, determinism, baseline and fixtures

* **Variant id** is `<label>-<10 hex>`, the first 40 bits of SHA-256 over the generator version
  (`RedTeamVariant.GENERATOR`, now `redteam-gen-1`), the species and the key-sorted parameter JSON. The parameters
  are drawn from `java.util.Random` (its algorithm is fixed by the JDK spec), seeded by SHA-256 of (seed, subject,
  take, species). They are then stored explicitly, so the renderer reads nothing else. The same variant id always
  means the same render inputs. A change to the generator or to a range gives new ids, and the gate treats those as
  new variants, not as regressions.
* **Determinism.** Rendering the same variant twice gives byte-identical JPEGs (`RedTeamVariantTest`). On this machine
  a 16-item corpus was byte-identical under OpenJDK 21 and 25. A second full run against the committed baseline (passive
only, 239 s) reproduced all 108 frame hashes and outcomes: 0 regressions, 0 render drift, 0 flips. CI
  uses Temurin 17, which has not been compared. Each baseline entry records the frames' SHA-256, and the gate reports
  "render drift" for each variant whose frames differ, marking any regression on such a variant. ONNX Runtime on a
  different CPU can also move a score near the threshold. If the first CI run flags drifted variants, re-baseline from
  that run instead of trusting the local baseline.
* **Baseline** (`docs/testing/redteam/baseline.json`) records, for each mode and variant id, the outcome
  (`ACCEPTED`, `REJECTED` or `NO_RESPONSE`), the score, the decision and the frames' SHA-256. Outcomes follow the
  `IsoReportMain` rules: a response is accepted when its effective score is at least 0.80, and a vetoed or
  challenge-failed session scores 0. Non-responses are `FACE_NOT_DETECTED`, `POOR_FACE_QUALITY`, `SESSION_TIMEOUT`
  and `ERROR`.
* **Regression.** An attack variant is a regression when the baseline records it as `REJECTED` or `NO_RESPONSE` in a
  mode and it is now `ACCEPTED` in that mode. The gate exits 1 and writes `fixtures/<variantId>-<mode>.json` with the
  full variant (every render parameter), the mode, the thresholds, the observed score, decision and per-signal scores,
  and the baseline outcome. The fixture contains no media. The workflow uploads the fixtures as an artifact and opens
  an issue labelled `redteam-bypass`, or comments on the open one. Accepted attacks that the baseline does not know
  are reported as *new bypasses* and do not fail the run. Bona fide flips are warnings.
* **Replay test.** `RedTeamFixtureReplayTest` (testkit, needs the models) re-renders each committed fixture in
  `testkit/src/test/resources/redteam/fixtures/` and replays it through the real engine. Each fixture must be
  rejected unless it is marked `"knownBypass": true`. A known bypass that is still accepted is reported as
  **skipped**, with the reason, and never as passed. Seven fixtures are committed:
  * **5 known bypasses**, one per species that had any accepted variant at baseline time: `SIM_PRINT-147da99687`,
    `SIM_PRINT_WARPED-ce4cc032dc`, `SIM_PRINT_CUTOUT-64d5045b99`, `SIM_SCREEN_REPLAY-f9af939b33` and
    `SIM_LOWRES_REPLAY-c05274c7d8`. All 5 are still accepted, so the test skips them.
  * **2 guard fixtures**, `SIM_SCREEN_PHOTO-a9341fa839` and `SIM_REPLAY_JITTER-8992cf5b73`. These were not found as
    bypasses: both were rejected (S1 veto) at baseline time. They are committed so that the must-reject path runs. If
    the engine starts accepting either one, the test fails.

## 4. Trend columns

`trend.jsonl` gets one JSON object per run. `trend.md` is rendered from the whole file. The workflow restores the
previous night's `redteam-trend` artifact (90-day retention). The first run starts from
`docs/testing/redteam/trend.jsonl`, which holds the local baseline row below.

| Column | Meaning |
|---|---|
| Date, Commit, Source | UTC date; `git rev-parse --short HEAD`, with `-dirty` when the working tree had changes; `ci` or `local` |
| Items (BF/attack) | rendered items: bona fide takes / attack variants |
| PRINT … LOWRES_REPLAY | passive APCER per species as accepted/responded (%); non-responses are excluded and counted in `apnrr` in the JSON |
| Max APCER | the highest per-species APCER (ISO/IEC 30107-3 summary) |
| BPCER | drawn bona fide sessions rejected / responded |
| ACER | (max APCER + BPCER) / 2 |
| Challenge max APCER | the same items with `active.mode = ALWAYS`. Recordings cannot answer randomly drawn prompts, so this shows only that prompt-ignoring replays fail. BPCER in this mode is 100 % by construction and is not reported in the table |
| Regressions | previously rejected attack variants now accepted (the gate) |
| Run time | render + scoring of both modes, excluding the Maven build |

The JSON also holds `apnrr`, `bpnrr`, the challenge-mode per-species counts, `newBypasses`, `fixed` (accepted at
baseline and now rejected), `bonaFideFlips` and `renderDrift`.

## 5. Local baseline row

The first full run took place on 24 Oct 2026 on an i9-12900H laptop limited to 4 cores and 7 GB, under OpenJDK 25,
with 3 render threads and other builds running at the same time. It used the default 12 subjects, 2 bona fide takes
and seed `redteam-2026`, which gives 108 items. The engine was `mosip-vivid-onnx` 0.1.0 with the models in
`liveness-engine-onnx/models/README.md`, at the scaffold commit `3587dbb` plus the uncommitted working tree.

| Date | Commit | Source | Items (BF/attack) | PRINT | PRINT_WARPED | PRINT_CUTOUT | SCREEN_REPLAY | SCREEN_PHOTO | REPLAY_JITTER | LOWRES_REPLAY | Max APCER | BPCER | ACER | Challenge max APCER | Regressions | Run time |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-10-24 | 3587dbb-dirty | local | 108 (24/84) | 4/12 (33 %) | 8/12 (67 %) | 3/12 (25 %) | 1/11 (9 %) | 0/12 (0 %) | 0/12 (0 %) | 8/10 (80 %) | 80.0 % | 14/24 (58 %) | 69.2 % | 0.0 % | 0 | 399 s |

* Run time: 228 s to render 108 items (3 threads, roughly 6 s of CPU per item), then 171 s to score both modes.
* Non-responses: 3 of 84 attacks. Two `SIM_LOWRES_REPLAY` items gave `POOR_FACE_QUALITY` and `SESSION_TIMEOUT`,
  and one `SIM_SCREEN_REPLAY` item gave `FACE_NOT_DETECTED`. No bona fide session was a non-response.
* Challenge mode: every attack was rejected (0 of 81 responded). All 24 bona fide recordings were rejected too, as
  expected.
* There are no regressions because no baseline existed before this run. This run *is* the baseline, and its 24
  accepted attacks are the known bypasses.

**How to read it.** All 14 rejected bona fide sessions and all 57 rejected attacks were hard vetoes by S1
(`cnn_texture`, `PAD_DETECTED`). No session was rejected by the fused score falling below the threshold, so colour,
moiré and rPPG decided nothing. In passive mode, the outcome of a drawn face is
whatever MiniFASNet makes of a cartoon under a given blur, noise and colour. No single render parameter separated
accepted from rejected bona fide takes. Blurred content (`SIM_LOWRES_REPLAY`) and bent prints were accepted more often
than sharp bona fide frames, which shows how far this proxy is from real faces. With 12 presentations per species, one
flipped variant moves an APCER by 8.3 points. The value of the run is the per-variant record: an engine or policy
change that turns a rejected variant into an accepted one is caught by id and can be re-rendered exactly.

### Baseline refresh, 1 Nov 2026 (`builtin-1.1`)

The passive-PAD change in [`proxy-eval/improvement-2026-11.md`](proxy-eval/improvement-2026-11.md) (S1 crop 1.2x,
moiré and colour weight 0.05, passive threshold 0.997) changes red-team decisions, so the baseline was regenerated
with `tools/redteam/run-redteam.sh --update-baseline` after review. The gate threshold in the script follows the
RESIDENT default and is now 0.997; at the old 0.80 the gate counted prints that the orchestrator rejects as below
threshold (fused 0.89–0.97) as accepted.

| Date | Commit | Source | Items (BF/attack) | PRINT | PRINT_WARPED | PRINT_CUTOUT | SCREEN_REPLAY | SCREEN_PHOTO | REPLAY_JITTER | LOWRES_REPLAY | Max APCER | BPCER | ACER | Challenge max APCER | Regressions | Run time |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-11-01 | bdeb105-dirty | local | 108 (24/84) | 0/12 (0 %) | 0/12 (0 %) | 0/12 (0 %) | 0/11 (0 %) | 0/12 (0 %) | 0/12 (0 %) | 3/10 (30 %) | 30.0 % | 24/24 (100 %) | 65.0 % | 0.0 % | 0 | 282 s |

Against the old baseline: no regression, 21 previously accepted attack variants now rejected, 10 bona fide flips.
**Every drawn bona fide take is now rejected.** That is not a finding about real people: S1 already called 14 of the
24 cartoons attacks, and the remaining ones score 0.84–0.98, below a threshold calibrated on real faces. The gate
therefore still catches an attack variant that becomes accepted, but bona fide flips on drawn faces carry no
information; BPCER here must not be read as a real-face rate. The e2e drawn-face harness pins the S1 crop at 1.5x for
the same reason (`e2e-tests/.../SyntheticSubjectEngine.java`).

## 6. Built and not built (PLAN.md §17.5)

| §17.5 element | Status |
|---|---|
| Loop runs nightly | Built: `schedule` 02:17 UTC + `workflow_dispatch`. It skips with a notice when the models cannot be downloaded or fail their SHA-256 check. |
| Every bypass becomes a regression test | Partly built. The gate writes a fixture for each regression and the workflow files an issue. A person still copies the fixture into `testkit/src/test/resources/redteam/fixtures/`; nothing commits automatically, because the workflow has `contents: read`. |
| ACER trend published | Built as `trend.jsonl` + `trend.md` artifacts plus the job summary. There is no hosted dashboard. |
| Diffusion face-swap, GAN print/screen synthesis, 3D-rendered masks | **Not built.** The species are hand-written image transforms of a drawn face. |
| Replayed sessions with timing jitter | Built as `SIM_REPLAY_JITTER`, but as a recording only. No closed-loop attacker answers prompts. |
| Adaptive or closed-loop attackers (a cut-out print that blinks on command, a real-time screen relay of a responsive subject) | Not built. Challenge mode only replays fixed recordings. |
| Bypasses feed back into training | Not built. No model is trained in this repository. |
