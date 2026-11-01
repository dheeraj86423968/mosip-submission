# API documentation

This page documents every interface mosip-vivid exposes or consumes:

1. The two Java SPIs that devices and engines implement.
2. The session API that Registration Client integrations call.
3. The SBI HTTP endpoints the device adapter uses, including the optional signed preview extension.
4. The mock device's control API.
5. The Android Pigeon channel.
6. Configuration keys.
7. Audit events.
8. Command-line tools.

All Java types are in Javadoc'd sources; the paths are given with each section. The modules that run inside the
clients compile to Java 11 bytecode.

## 1. Device SPI (`device-api`, package `io.mosip.vivid.device`)

A frame source is anything that can deliver face frames: an SBI `/stream` endpoint, an Android camera, a vendor
SDK or the mock device.

```java
public interface FaceFrameSource extends AutoCloseable {
    String adapterId();                                  // "sbi-stream", "android-camerax", "mock", ...
    DeviceCapabilities capabilities();                   // may block briefly to query the device
    void start(FrameSink sink, StreamOptions options);   // returns immediately; frames arrive on a delivery thread
    void stop();                                         // idempotent; sink.onEnd() is called once per start
    DeviceHealth health();
}

public interface FrameSink {
    void onFrame(FaceFrame frame);
    default void onHealth(DeviceHealth health) {}
    default void onEnd() {}
}
```

| Type | Contents |
|---|---|
| `FaceFrame` | `sequence`, `timestampNanos`, `width`, `height`, `format` (`PixelFormat`: RGB888, BGR888, NV21, YUV420, GRAY8, GRAY16, MJPEG), `pixels`. Optional: `depth` (GRAY16, mm), `ir`, `deviceSignature` (present only when a signing device's frame verified). |
| `DeviceCapabilities` | vendor, model, deviceId, SBI spec version, certification level (L0/L1), resolution, nominal fps, formats, `hasDepth`, `hasIr`, `signsFrames` |
| `DeviceHealth` | state `CONNECTING`, `CONNECTED`, `DEGRADED`, `RECONNECTING`, `DISCONNECTED`, `FAILED`, plus a reason |
| `StreamOptions` | target fps, max queued frames (drop-oldest ring), stall timeout, reconnect attempts |
| `DeviceException` | kinds `UNAVAILABLE`, `CONNECTION_FAILED`, `CAMERA_UNAVAILABLE`, `PROTOCOL_ERROR`, `INVALID_FRAME` |

**Implementations:**
- `SbiStreamFrameSource` (`device-sbi-stream`)
- `MockFaceFrameSource` (`device-mock`)
- `CameraXFrameSource` (`integration-android`)
- `AbstractFrameSourceAdapter` with `VendorSdk` (`device-vendor-sdk`, a template for vendors that ship their own SDK)

`testkit`'s conformance suite checks any implementation.

## 2. Engine SPI (`liveness-api`, package `io.mosip.vivid.api`)

Engines score frames and extract face geometry. They never decide.

```java
public interface LivenessEngine extends AutoCloseable {
    EngineInfo info();                       // id, version, model bundle, supported challenges, signals
    FrameAnalyzer newAnalyzer();             // one per session; models are shared
    default SelfTestResult selfTest() { ... }
}

public interface FrameAnalyzer extends AutoCloseable {
    FrameAnalysis analyze(FaceFrame frame);
    default void onChallenge(Challenge challenge) {}   // lets motion signals know what to expect
    void reset();
}
```

`FrameAnalysis` holds:
- `quality` (`FaceQuality`: face count, sharpness, illumination, pose, occlusion, size, overall);
- `face` (`FaceObservation`: box, landmarks, yaw, pitch, roll, eye openness, mouth openness and smile);
- `signals`, a list of `SignalScore`;
- an optional identity `embedding`;
- `processingNanos`.

A `SignalScore` is `(signalId, attackProbability ∈ [0,1], confidence ∈ [0,1], hardVeto, attackClass)`. Signal ids
are constants in `Signals`: `cnn_texture`, `moire`, `color_stats`, `rppg`, `parallax`, `challenge_timing`,
`identity`, `depth`, `ir`, `quality`, `injection`.

**Implementations:**
- `OnnxLivenessEngine` (`liveness-engine-onnx`, created via `OnnxEngineFactory`)
- the MediaPipe/TFLite engine (`integration-android`)
- `AvLivenessEngine` (`liveness-av`, wraps any engine and adds spoken-code liveness)

## 3. Session API (`liveness-api` contract, `liveness-core` implementation)

```java
LivenessEngine engine = OnnxEngineFactory.create();                        // or any LivenessEngine
LivenessSessionFactory factory = new LivenessSessionFactory(engine);       // default policies
LivenessOrchestrator session = factory.open(Workflow.RESIDENT, Locale.ENGLISH, /*online*/ true, /*assisted*/ false);
session.addListener((prev, cur) -> ui.render(cur));                       // phase, hint, challenge, progress
FrameSourceBridge bridge = new FrameSourceBridge(frameSource, session);   // device -> session, ticks, auto-stop
bridge.start();
// ... when cur.isTerminal() or cur.phase() == FAILED:
LivenessResult r = session.result();        // r.accepted() is true only for Decision.LIVE
if (!r.accepted() && session.state().canRetry()) session.retry();
```

The policy-aware factory constructor is
`LivenessSessionFactory(engine, Function<Workflow, LivenessPolicy>, AuditSink, LivenessMetrics)`.

`LivenessSession` methods:
- `feed(frame)`
- `tick()`
- `state()`
- `retry()`
- `cancel()`
- `result()`
- `addListener(...)`
- `context()`

The orchestrator also exposes `start()`, `deviceReady()`, `deviceReconnecting(reason)`,
`deviceFault(reason, detail)`, `frameSignatureInvalid(detail)` and `currentChallenge()`.

| Type | Values |
|---|---|
| `Workflow` | `RESIDENT`, `OPERATOR`, `SUPERVISOR` |
| `SessionState.Phase` | `IDLE`, `DEVICE_CONNECTING`, `FACE_ACQUIRING`, `PASSIVE_EVALUATING`, `ACTIVE_CHALLENGE`, `PASSED`, `FAILED`, `LOCKED`, `CANCELLED` |
| `SessionState.Hint` | `NONE`, `MOVE_CLOSER`, `MOVE_BACK`, `CENTER_FACE`, `LOOK_AT_CAMERA`, `IMPROVE_LIGHTING`, `HOLD_STILL`, `ONLY_ONE_FACE`, `CHECKING_LIVENESS`, `PLEASE_CONTINUE`, `ACTION_DETECTED`, `CAPTURED_OK`, `TRY_AGAIN`, `RETRY_AVAILABLE`, `MAX_RETRIES_REACHED`, `DEVICE_RECONNECTING`, `DEVICE_DISCONNECTED` |
| `ChallengeType` | `BLINK`, `BLINK_TWICE`, `SMILE`, `OPEN_MOUTH`, `RAISE_EYEBROWS`, `TURN_LEFT`, `TURN_RIGHT`, `LOOK_UP`, `LOOK_DOWN`, `NOD`, `HOLD_STILL` |
| `LivenessResult.Decision` | `LIVE`, `NOT_LIVE`, `ATTACK`, `ERROR`, `CANCELLED` |
| `FailureReason` | `DEVICE_UNAVAILABLE`, `DEVICE_CONNECTION_FAILURE`, `CAMERA_UNAVAILABLE`, `FACE_NOT_DETECTED`, `MULTIPLE_FACES`, `POOR_FACE_QUALITY`, `LIVENESS_BELOW_THRESHOLD`, `PAD_DETECTED`, `ACTIVE_CHALLENGE_FAILED`, `CHALLENGE_TIMEOUT`, `MAX_RETRIES_EXCEEDED`, `INVALID_FRAME_DATA`, `ENGINE_ERROR`, `IDENTITY_DRIFT`, `SESSION_TIMEOUT`, `CANCELLED`, `POLICY_DISABLED` |

`LivenessResult` carries:
- session id and workflow;
- decision and failure;
- fused score and per-signal scores;
- veto signal and attack class;
- challenge outcomes, with latency and duration for each;
- attempts, timings and frames analysed;
- engine id, model bundle version and policy version;
- the session nonce;
- the anchor embedding, used for identity continuity into the signed capture;
- `evidenceHash`, a SHA-256 over the canonical decision record and written to audit.

## 4. SBI endpoints consumed (`device-sbi-stream`)

The adapter talks to an SBI 0.9.5 service on `127.0.0.1`, ports 4501 to 4600:

| Verb and path | Use |
|---|---|
| `MOSIPDISC /device` | Discovery of face devices (port scan or pinned endpoint) |
| `MOSIPDINFO /info` | Device info and certification level; `streamSigning` advertisement (below) |
| `STREAM /stream` | `multipart/x-mixed-replace` MJPEG preview. Both RFC 2046 framing and MockMDS framing are accepted. |
| `RCAPTURE /capture` | Signed capture after liveness passes; identity continuity is checked against the session's anchor |

Error handling:
- An HTTP 200 JSON refusal (e.g. 111 busy, 605 unknown device) is mapped to `DeviceException` kinds.
- A stall triggers a reconnect with backoff.
- Every reconnect re-runs discovery.

Interoperability with MOSIP's MockMDS 1.2.0.2 is recorded in `docs/testing/interop-mockmds.md`.

**Signed preview extension (proposed, `vivid-sp-v1`).** This extension is optional and additive.

1. **Advertisement.** The device advertises `"streamSigning": {"supported": true, "alg": "vivid-sp-v1",
   "deviceKey": "<base64 SPKI P-256>"}` in `deviceInfo`.
2. **Request.** The client adds `"signedPreview": {"alg", "clientKey", "clientNonce"}` to the STREAM body.
3. **Session start.** The first part, `X-SBI-Part: stream-init`, carries `sessionId`, `clientNonce`,
   `deviceNonce` and an ECDSA signature by the device key over `alg|sessionId|clientNonce|deviceNonce`.
4. **Session key.** Both sides derive `HKDF-SHA256(clientNonce||deviceNonce, ECDH, "vivid-sp-v1 " + sessionId)`.
5. **Per frame.** Each frame carries `X-SBI-Frame-Seq`, `X-SBI-Frame-Time` and
   `X-SBI-Frame-Signature = HMAC-SHA256(key, seq|time|hex(SHA-256(jpeg)))`, with a strictly increasing sequence.

Configure it with `SbiStreamConfig.signedPreview(OFF | IF_SUPPORTED | REQUIRED)` and an optional
`trustedDeviceKey`. Full text: `docs/design/02-frame-streaming.md` §6 and `docs/upstream/sbi-signed-preview-change-request.md`.

## 5. Mock device control API (`device-mock`, `MockSbiMain`)

The mock serves the SBI verbs above, and these endpoints for tests and demos:

| Request | Body | Effect |
|---|---|---|
| `POST /mock/scenario` | `{"name": "print-attack"}` or `{"path": "/file.json"}` | Switch the running scenario |
| `POST /mock/fault` | `{"kind": "DISCONNECT", "params": {...}}` | Inject `DISCONNECT`, `RECONNECT`, `STALL`, `CORRUPT_FRAME`, `LATENCY_SPIKE`, ... |
| `GET /mock/status` | none | Scenario, plug state, frames sent, active streams, last frame label |

The bundled scenarios are:
- `live-pass` and `live-blink-smile-turn`;
- `print-attack`, `screen-replay` and `video-replay`;
- `multiple-faces` and `no-face`;
- `stall` and `disconnect-mid-session`;
- the L1 depth/IR scenarios `l1-live-depth-ir`, `l1-print-depth-ir`, `l1-curved-print-depth-ir` and `l1-screen-depth-ir`.

## 6. Android channel (`integration-android`, Pigeon 10)

```dart
@HostApi() abstract class LivenessHostApi {
  String startSession(String workflow, String policyJson, bool assisted);   // returns sessionId
  void cancel(String sessionId);
  bool retry(String sessionId);
  LivenessResultDto result(String sessionId);
  int previewTextureId(String sessionId);                // Flutter Texture for the camera preview
  PolicySummaryDto policySummary(String workflow);
  FaceMatchDto matchEnrolledFace(String sessionId, String userId);   // live face vs the operator's enrolled face
}
@FlutterApi() abstract class LivenessFlutterApi {
  void onState(String sessionId, SessionStateDto state);
}
```

The DTOs mirror section 3. Source: `integration-android/vivid-android/pigeons/liveness.dart`.

## 7. Configuration

Policy is one signed JSON document (`policy/src/main/resources/policy-default.json`). It can also be flattened into
`mosip.registration.liveness.<workflow>.*` properties, which travel through the Registration Client's config sync.
Local properties can only tighten the signed policy.

Main keys, per workflow:
- `enabled`
- `passiveThreshold`
- `padHardThreshold`
- `active.mode` (`NEVER`, `ON_LOW_CONFIDENCE` or `ALWAYS`; the default is `ALWAYS`)
- `active.minChallenges`, `active.maxChallenges`, `active.pool` and `active.timeoutMillis`
- `maxRetries` and `onMaxRetries`
- `signals.<id>.enabled`, `signals.<id>.weight` and `signals.<id>.hardThreshold`

Engine keys use `vivid.engine.*`:
- `threads`
- `executionProvider` (`cpu`, `cuda`, `dnnl`, `openvino`, `directml`, `coreml` or `auto`)
- model paths and the `depth.*` / `ir.*` thresholds

Every key, its default and its tightening direction are in `docs/design/07-configuration.md`.

## 8. Audit events (`telemetry`, component `LIVENESS`)

Events:
- `SESSION_STARTED`
- `PASSIVE_PASSED` and `PASSIVE_BELOW_THRESHOLD`
- `CHALLENGE_ISSUED`, `CHALLENGE_PASSED` and `CHALLENGE_TIMEOUT`
- `UNPROMPTED_ACTION`
- `PAD_DETECTED` and `IDENTITY_DRIFT`
- `SESSION_PASSED` and `SESSION_FAILED`
- `MAX_RETRIES_REACHED` and `SUPERVISOR_OVERRIDE`
- `DEVICE_FAULT`
- `POLICY_LOADED`, `MODEL_BUNDLE_LOADED` and `MODEL_BUNDLE_REJECTED`
- `CALIBRATION_RELEASED` and `CALIBRATION_BUDGET_EXHAUSTED`

Records carry scores and hashes only. Frames and images are never logged. Offline, records queue in
`OfflineAuditQueue` until sync.

## 9. Command-line tools

| Tool (main class) | Purpose | Usage |
|---|---|---|
| `io.mosip.vivid.demo.DemoApp` (`demo-desktop/target/vivid-demo.jar`) | Attack Lab: real engine and orchestrator on the mock device, live attack switching, headless recorder | `java -jar vivid-demo.jar [--workflow resident\|operator\|supervisor] [--subject live\|print\|video\|screen\|injected] [--models DIR] [--sbi HOST:PORT] [--record DIR --scenario demo-reel]` |
| `io.mosip.vivid.device.mock.sbi.MockSbiMain` | SBI-compatible mock face device | `[--port N] [--scenario NAME\|FILE] [--fps N] [--framing standard\|mock-mds] [--certification L0\|L1] [--list]` |
| `io.mosip.vivid.testkit.corpus.CorpusScoreMain` | Replays a frame-folder corpus through the real pipeline and writes `scores.jsonl` | `--corpus DIR --out scores.jsonl [--log capture-log.csv] [--mode passive\|challenge] [--workflow W] [--models DIR]` |
| `io.mosip.vivid.testkit.metrics.IsoReportMain` | ISO/IEC 30107-3 report (APCER per species, BPCER, ACER, Wilson intervals, non-response rates, group slices) | `--scores FILE --out DIR [--threshold T] [--min-subjects 5] [--max-acer X]` |
| `io.mosip.vivid.policy.signing.PolicyKeyTool` | Generates keys, signs and verifies policy documents | `gen \| sign \| verify ...` |
| `io.mosip.vivid.bundle.BundleTool` | Builds, signs, verifies and activates `.mlb` model bundles with rollback | `bundle-tool <command> [options]` |
| `io.mosip.vivid.calibration.CalibrationJobMain` | Aggregates differentially private score histograms and emits a signed policy update | see `docs/design/14-dp-calibration.md` |
