# liveness-engine-onnx

The desktop implementation of `io.mosip.vivid.api.LivenessEngine`. It scores camera frames for presentation
attacks and extracts face geometry for active challenges. It does **not** decide: fusion, policy, retries and
challenge verification live in `liveness-core`. Inference uses ONNX Runtime 1.20 (CPU by default, opt-in
accelerators below). Every image operation
is plain Java on `java.desktop`, with no OpenCV dependency.

## Architecture

```
FaceFrame (RGB888 | BGR888 | NV21 | YUV420 | GRAY8 | GRAY16 | MJPEG)
  └─ FrameDecoder ─────────────► RgbImage
       └─ YuNetDetector (ONNX) ► List<FaceDetection> (box, score, 5 landmarks), NMS
            ├─ 0 or >1 faces ──► FaceQuality(faceCount), face = null, signals.onFaceLost(), injection only
            └─ 1 face ─────────► FaceContext (shared lazy crops / luma)
                 ├─ ObservationExtractor ─► FaceObservation (pose, EAR, MAR, smile, gaze, landmarks)
                 ├─ QualitySignal (S9) ───► FaceQuality
                 ├─ Signals S1..S5, injection ► List<SignalScore>
                 └─ SFaceEmbedder every K frames ► float[128] (L2-normalised)
  ─► FrameAnalysis (quality, face, signals, embedding, processingNanos)
```

| Package | Contents |
|---|---|
| `io.mosip.vivid.engine.onnx` | `OnnxLivenessEngine`, `OnnxFrameAnalyzer`, `EngineConfig`, `OnnxEngineFactory`, `OrtModel`, `AntiSpoofModelConfig` |
| `io.mosip.vivid.engine.image` | `RgbImage`, `FloatImage`, `FrameDecoder`, `ImageOps` (resize, crop with padding, CHW tensor, Laplacian variance, HSV, warpAffine), radix-2 `Fft` |
| `io.mosip.vivid.engine.detect` | `FaceDetector`, `YuNetDetector`, `YuNetDecoder`, `Nms`, `FaceDetection` |
| `io.mosip.vivid.engine.pose` | `HeadPose`, `ObservationExtractor`, `Homography` (DLT + affine), `SimilarityTransform`, `LinearSolver` |
| `io.mosip.vivid.engine.embed` | `FaceEmbedder`, `SFaceEmbedder` (5-point alignment to the ArcFace 112x112 template) |
| `io.mosip.vivid.engine.signal` | `Signal`, `FaceContext`, `PlattCalibration` and the seven signals below |

Threading: one `OnnxLivenessEngine` loads each ONNX session once. ONNX Runtime sessions are thread-safe and shared.
`newAnalyzer()` is cheap and returns per-session state (rPPG buffer, parallax reference). Each analyzer is fed by
a single thread. When a signal fails, the error is logged and the signal reports a neutral score. Undecodable frames
throw `InvalidFrameException`, which maps to `FailureReason.INVALID_FRAME_DATA`.

## Usage

```java
try (LivenessEngine engine = OnnxEngineFactory.create();           // ./models, -Dvivid.models or $MOSIP_VIVID_MODELS
     FrameAnalyzer analyzer = engine.newAnalyzer()) {
    FrameAnalysis a = analyzer.analyze(frame);
    analyzer.onChallenge(challenge);                               // enables parallax on head-turn challenges
}
// production: only a signed, verified bundle
LivenessEngine engine = OnnxEngineFactory.fromStore(bundleStore);
```

Configuration comes from `EngineConfig.builder()`, an `engine.properties` file in the model directory (see the
`EngineConfig` Javadoc for every key), or the manifest of an installed `.mlb` bundle (`EngineConfig.fromBundle`).
Bundle models are found by `purpose`: `face-detection`, `anti-spoofing`, `anti-spoofing-2`, `embedding`.
The manifest's `calibration` block (`platt_a`, `platt_b`, `threshold` per signal id) overrides each signal's
default mapping. Its `selfTest` images drive `engine.selfTest()`.

## Hardware acceleration (opt-in)

The engine runs on the ONNX Runtime **CPU** provider by default, and that default has not changed. To try an
accelerator, set `vivid.engine.executionProvider` in `engine.properties` or call
`EngineConfig.builder().executionProvider(ExecutionProvider.CUDA)`. Accepted values are `cpu`, `cuda`, `dnnl`,
`openvino`, `directml`, `coreml` and `auto`.

```properties
vivid.engine.executionProvider=cuda
```

* The provider is applied to every model session (YuNet, anti-spoofing, SFace) in `OrtModel.load`.
* If the loaded ONNX Runtime build does not contain the provider, the model loads on the CPU and a warning is
  logged, for example `ONNX Runtime execution provider CUDA unavailable, falling back to CPU: ...`. The same
  happens if the provider is compiled in but fails to register (`Failed to find CUDA shared provider`, missing
  CUDA/cuDNN or OpenVINO runtime) or the session cannot be created with it. An accelerator that is missing never
  stops the engine from loading.
* `auto` tries CUDA, DirectML, CoreML, OpenVINO and DNNL, in that order. It uses the first one that registers,
  otherwise the CPU, and logs the choice at INFO.
* `OrtModel.executionProvider()` reports the provider requested and the one actually used.

The stock `com.microsoft.onnxruntime:onnxruntime` jar this module depends on is CPU-only on Linux and Windows.
To enable a provider, replace that dependency with the matching artifact. Use one ONNX Runtime artifact only:
two would load conflicting native libraries.

| Provider | ORT artifact / build | Platforms | Notes |
|---|---|---|---|
| `cpu` | `com.microsoft.onnxruntime:onnxruntime` (default) | Windows x64, Linux x64/aarch64, macOS x64/arm64 | Shipped default |
| `cuda` | `com.microsoft.onnxruntime:onnxruntime_gpu` (same version, 1.20.0 is on Maven Central) | Windows x64, Linux x64 | Needs an NVIDIA driver plus the CUDA 12 and cuDNN 9 runtime libraries that ORT 1.20 is built against. The jar also contains TensorRT, which this setting does not use |
| `coreml` | stock `onnxruntime` jar | macOS | The macOS native libraries in the 1.20.0 jar contain the CoreML provider. We confirmed this from the library's symbols but have not run it on a Mac |
| `directml` | No Maven artifact. Build ORT from source with `--use_dml --build_java` and install the resulting jar | Windows 10+ (any DX12 GPU) | NuGet `Microsoft.ML.OnnxRuntime.DirectML` is .NET only |
| `openvino` | No Maven artifact. Build ORT from source with `--use_openvino --build_java` against an OpenVINO runtime | Intel CPU, iGPU and NPU on Windows and Linux | Uses OpenVINO's default device |
| `dnnl` | No Maven artifact. Build ORT from source with `--use_dnnl --build_java` | x64 Windows and Linux | oneDNN CPU kernels |

What was verified: the CPU fallback, with the stock jar, on this machine. A requested CUDA provider loads YuNet
on the CPU and runs inference (`ExecutionProviderTest`). CUDA, DNNL and OpenVINO registration failures fall back
to CPU. None of the accelerated paths has been run, because there was no GPU, no OpenVINO and no Mac. The
models are small (YuNet 0.2 MB, MiniFASNetV2-SE 1.9 MB, SFace 39 MB), and per-frame cost is
dominated by 640x640 detection and the embedding. Expect a GPU to help mainly the embedding, and measure
before relying on it (see `docs/testing/performance.md`).

## Signals: maths and target attacks

Every signal returns `SignalScore(attackProbability, confidence, hardVeto, attackClass)`. A signal with no evidence
returns `0.5 @ confidence 0`. Every probability is `sigmoid(a * raw + b)`, where `raw` is the signal's attack logit and
`(a, b) = (1, 0)` unless a bundle calibrates it. **The hand-crafted weights below are untrained defaults.** They were
chosen on synthetic inputs and one real photograph, and they must be fitted on the project corpus (PLAN.md 4.5)
before thresholds mean anything.

| Id | Signal | Targets | Maths |
|---|---|---|---|
| `cnn_texture` (S1) | CNN texture | print, screen, mask | Square crop of the box's longer side x scale (1.5 for the bundled model; 2.7 and 4.0 for Silent-Face), resized and /255. Softmax over the logits gives `P(real)`, averaged over up to two models. `raw = ln((1-P)/P)`. Confidence ramps with inter-ocular distance (IOD) from 16 to 40 px. Veto only if calibrated `p >= 0.995` and the bundle threshold is above 0.5. |
| `moire` (S2) | Frequency-domain moire | screen replay | 64x64 luma face crop, mean removed, 2-D Hann window, radix-2 2-D FFT. `hfRatio` = AC energy at radius >= 16 / total AC energy. `peakScore` = log-mapped max over radius >= 6 of `P(u,v) / mean(P on that ring)`, between 8 (noise) and 80 (strong periodicity). `raw = 6 (peakScore - 0.45) + 4 (hfRatio - 0.45)`. Confidence = size x sharpness (blur hides moire). |
| `color_stats` (S3) | Specular and colour statistics | print, screen | On a 0.8x-box crop: HSV saturation mean and std, mean V, specular ratio (V > 0.93 and S < 0.2), R-G and G-B correlation, chromaticity spread `std(r) + std(g)` with r = R/(R+G+B), blue shift `(B - R)/255`, clipping. `raw = 5(0.25 - sMean) + 20(0.02 - chroma) + 6 blueShift + 8(spec - 0.05) + 5 clip - 0.4`. Class `SCREEN_REPLAY` when blue shift or clipping dominate, otherwise `PRINT`. |
| `rppg` (S4) | Remote photoplethysmography | photo, video replay, mask | Mean RGB over forehead and two cheek ROIs placed from the eye landmarks, in a 6 s time-stamped ring buffer. A gap over 0.5 s resets it. Samples are linearly resampled to a uniform 20 Hz grid (tolerates irregular camera timing), then channel-normalised. Two pulse signals: green, and CHROM `S = X - (sigma_X/sigma_Y) Y` with `X = 3R - 2G`, `Y = 1.5R + G - 1.5B`. Each is detrended with a 1 s moving average, Hann-windowed and FFT'd (256+ points). SNR = power within +/-0.15 Hz of the strongest 0.7-3.0 Hz bin / remaining band power. `raw = 2 (1.5 - SNR_best)`. Confidence grows linearly from 3 s to 6 s of data and halves under large head motion. Class `PRINT` when the trace is constant, otherwise `VIDEO_REPLAY`. |
| `parallax` (S5) | Motion parallax / planarity | print, screen (planar) | Active only during depth-revealing challenges (`ChallengeType.revealsDepth()`). The first face frame's 5 landmarks are the reference. Each later frame fits the least-squares affine map from reference to current landmarks and records the RMS residual / IOD (normalised DLT homography residual kept for diagnostics). A plane under weak perspective moves affinely, giving a residual at landmark-noise level. A turning head moves its nose tip (about 0.5 IOD in front of the eye/mouth plane) off that model. `raw = (0.02 - maxResidual) / 0.006`. Confidence = observed motion, `max(|dyaw|/15 deg, |dpitch|/12 deg, |1 - IOD/IOD0|/0.12)`, so translation alone gives no verdict. The verdict is kept after the challenge ends. |
| `quality` (S9) | Quality gate | (garbage-in) | Sharpness `min(1, LaplacianVar(96 px crop) / 120)`. Illumination: triangle on mean luma (best 90-170) x `(1 - 2 x clipped)`. Pose `1 - max(|yaw|/45, |pitch|/35)`. Occlusion: detector score (0.6 to 0.9) x landmark plausibility. Size: IOD 20 to 50 px ramps up, 160 to 260 px ramps down. Reported as `FaceQuality`. As a `SignalScore` it carries `1 - overall` with confidence 0, so fusion ignores it. |
| `injection` | Virtual camera presence | injection | Linux: reads `/sys/class/video4linux/video*/name` and matches known virtual drivers (v4l2loopback / "Dummy video device", OBS, ManyCam, DroidCam, akvcam, ...). Windows: a documented stub; the host passes device names through the constructor. Raises a hard veto (`INJECTION`) only when the config declares `injection.sourceIsSystemCamera=true` and a virtual device exists. Reported even when no face is visible. |

S6 (challenge timing) and S7 (identity continuity) are computed by the orchestrator. This engine provides the inputs:
`FaceObservation` per frame and an L2-normalised 128-d SFace embedding every `embedding.everyFrames` face frames
(default 5).

### `FaceObservation` from 5 landmarks

No OSI-licensed dense landmark model was available (see `models/README.md`), so everything comes from YuNet's five
points plus local image analysis (`pose.ObservationExtractor`, `pose.HeadPose`):

* **roll**: angle of the eye line.
* **yaw**: after de-rolling, `atan(noseOffsetX / (0.5 IOD))`. The nose tip is modelled 0.5 IOD in front of the eye
  plane. Positive = subject turned to their left.
* **pitch**: `atan((0.6 - t) / 0.45)`, where t is the nose's position between the eye line (0) and the mouth line (1).
  Positive = looking up.
* **eye aspect ratio (blink)**: in a 0.5 x 0.4 IOD patch around each eye centre, the height of the dark iris/pupil
  band divided by the patch width. A row-intensity profile is thresholded at 35% between its min and max. Open eyes
  give about 0.25-0.35, closed eyes about 0.05-0.1. A patch with no contrast reports "open", so no blink is invented.
* **mouth aspect ratio**: the same dark-band method on a patch spanning the mouth corners, divided by mouth width.
* **smile**: mouth-corner distance / IOD, mapped linearly from 0.75 to 1.10.
* **gaze**: iris-band centroid inside the eye patch, mapped to [-1, 1].
* **brow raise**: eye-to-eyebrow-band distance / IOD. Weak, so `RAISE_EYEBROWS` is **not** advertised in
  `EngineInfo.supportedChallenges`. Every other `ChallengeType` is.

Limitations: absolute yaw and pitch carry roughly +/-8 degrees of per-person bias from the anthropometric constants,
so the orchestrator should verify head turns as changes from the session's first frames. The intensity-based EAR is
sensitive to glasses glare, heavy make-up and very dark lighting. Use it for blink *events* (a drop relative to the
running baseline), not absolute thresholds.

## Models and swapping them

`models/README.md` lists provenance, licences and SHA-256 of the bundled models:

* YuNet 2023mar (MIT)
* MiniFASNetV2-SE 128 px (Apache-2.0)
* SFace fp32 (Apache-2.0)

To swap a model:

* **Anti-spoofing**: any square RGB/BGR classifier with N-class logits works. Set `vivid.engine.antispoof.model`,
  `.inputSize`, `.cropScale`, `.colorOrder` and `.realClassIndex`. Files named like the Silent-Face exports
  (`2.7_80x80_MiniFASNetV2.onnx`, `4_0_0_80x80_MiniFASNetV1SE.onnx`) are recognised automatically. A second model
  goes in `antispoof2` and is averaged with the first.
* **Embedding**: any 112x112 ArcFace-template model whose input is raw 0..255 RGB. For (x - 127.5) / 128 models,
  construct `SFaceEmbedder(model, bgr, 127.5f, 128f)`.
* **Detector**: `FaceDetector` is an interface. YuNet is the shipped implementation.
* **Signals**: enable or disable them with `vivid.engine.signals`. Calibrate them through the bundle manifest.

## Performance (measured)

The machine was a 4-core x86-64 laptop running Linux, JDK 25 and ONNX Runtime 1.20.0 on the CPU with 2 intra-op
threads. Other builds ran concurrently, so treat the tail numbers as pessimistic. `OnnxEngineIntegrationTest`
logs these figures on every run.

| Stage (640x480 RGB frame) | ms |
|---|---|
| decode RGB888 | 0.2 |
| YuNet detection (640x640 letterbox) | 12-18 |
| observation + quality | 0.7 |
| S1 CNN (128 px) | 5-6 |
| S2 moire (64x64 FFT) | 0.6 |
| S3 + S4 + S5 + injection | < 0.5 |
| SFace embedding (fp32) | 16-21 (int8 variant: 50-60) |
| **Full frame, embedding every frame (worst case)** | **median 48, p90 63** |
| Full frame without embedding (4 out of 5 frames at K = 5) | about 27 |
| Real 750x500 photograph, end to end | 31 |

## Known limitations (honest list)

* Signal weights for S2-S5 are hand-set and **uncalibrated**. Expect high APCER/BPCER until they are fitted on a real
  attack corpus and shipped as Platt parameters in a bundle. The CNN baseline was trained by a third party on public
  data and has not been evaluated on MOSIP-like capture conditions.
* There is only one CNN scale (the original Silent-Face 2.7x / 4.0x ensemble was not obtainable as a trustworthy ONNX file).
* Expression features come from 5 landmarks plus intensity heuristics (above). The engine accepts a dense landmark
  model in the configuration but only logs that it is not yet used.
* Parallax uses 5 points (4 residual degrees of freedom). Strongly curved (cylinder-bent) prints can raise the residual.
  It complements S1, it does not replace it.
* rPPG needs at least 3 s of steady, well-lit face. Strong compression (MJPEG at low quality) and auto-exposure
  changes degrade it. Its confidence reflects this.
* The injection check is heuristic. It cannot see which device the source actually opened, and Windows enumeration is
  a stub. SBI devices that sign frames are the real fix.
* S8 (depth/IR) is not implemented. Frame `depth()` and `ir()` planes are ignored.

## Tests

`mvn -f liveness-engine-onnx/pom.xml test`:

* Unit tests cover:
  * ImageOps, every pixel-format decoder, and the FFT against a naive DFT plus Parseval.
  * The homography, affine and similarity solvers, NMS, YuNet decoding of hand-built tensors, and head-pose signs.
  * The EAR and MAR measures on drawn eyes and mouths.
  * Each signal on synthetic input: a grating vs skin texture for moire, grey vs skin vs blue-clipped colour stats,
    a 1.2 Hz pulse with jittered timestamps vs noise or a constant for rPPG, 3-D vs planar projected landmarks for
    parallax, and a fake sysfs for injection.
* The integration test runs only when `models/` is populated. Add `-Dvivid.testFaceImage=/path/face.jpg` to push a
  real photograph through the pipeline and the self-test.
