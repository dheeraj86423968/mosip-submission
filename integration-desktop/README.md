# integration-desktop

Face liveness / PAD gate for the **MOSIP Desktop Registration Client** (`mosip/registration-client`, recon done on
branch `release-1.2.0.x`, commit `c1cc4e7f`; see `../docs/recon/desktop-registration-client.md` for every file and line referenced here).

## Layout

| Path | Compiled by this module? | Depends on |
|---|---|---|
| `src/main/java/io/mosip/vivid/desktop/` | yes | `liveness-api`, `device-api`, `slf4j-api` |
| `src/main/java/io/mosip/vivid/desktop/ui/` | yes | + JavaFX 21.0.5 (`provided`; the client ships its own JavaFX) |
| `src/main/resources/fxml/LivenessOverlay.fxml`, `liveness-overlay.css` | resource | |
| `src/main/resources/i18n/liveness_{en,fr,ar}.properties` | resource | |
| `src/mosip/java/io/mosip/vivid/desktop/mosip/` | **no** (profile `mosip` only); compiled inside a registration-client checkout, see below | `registration-services` 1.2.0.x, `liveness-core`, bio-utils, Spring |
| `src/mosip/patches/000N-*.patch` | n/a | unified diffs against `registration-client` |

`src/main/java` has **no** compile dependency on `liveness-core`, `policy` or `telemetry`. The one thing it needs from
core is hidden behind `spi.LivenessSessionProvider` (`(SessionContext, LivenessPolicy) -> LivenessSession`), and the
identity distance function is injected into `CaptureBinding` as a `BiFunction<float[],float[],Double>`.

### Main classes

* `LivenessGate` — the single entry point: `LivenessResult verify(Workflow, FaceFrameSource, LivenessUiBinding[, Options])`.
  Blocking (call from a JavaFX `Task`), owns nonce generation, policy, source start/stop, tick timer, retries with delay,
  lock on max retries, device-fault mapping, audit of every transition, and `cancel()` from any thread.
  If the policy for a workflow is disabled it returns `LIVE` with `failure = POLICY_DISABLED` and audits `POLICY_DISABLED`.
* `LivenessUiBinding` — what the JavaFX controller implements: `showState`, `showChallenge`, `showHint`,
  `showFailure(reason, messageKey)`, `showRetry(attempt,max)`, `showLocked()` (+ defaults `showPassed`, `showFrame`,
  `showDeviceHealth`, `showDiagnostics`).
* `LivenessMessages` — enum → i18n **key** for every `SessionState.Phase`, `SessionState.Hint`, `ChallengeType`,
  `FailureReason`, `DeviceHealth.State`. `PAD_DETECTED` and `IDENTITY_DRIFT` (and `null`) map to
  `liveness.failure.generic` = "Face verification could not be completed. Please try again."; liveness-type failures map to
  "We could not verify face liveness. Please try again."
* `CaptureBinding` (+ `Iso19794Face`) — decodes the signed `rcapture` `bioValue` (ISO 19794-5:2011 or raw JPEG; JPEG 2000
  only through an injected decoder, which the MOSIP side supplies via bio-utils `FaceDecoder`), runs one analyzer pass,
  compares with `LivenessResult.anchorEmbedding()`. Outcomes: `BOUND`, `IDENTITY_DRIFT`, `NO_ANCHOR`, `NO_FACE`,
  `MULTIPLE_FACES`, `PAD_VETO`, `UNSUPPORTED_IMAGE`, `ENGINE_ERROR`, `SESSION_NOT_LIVE`, `NOT_REQUIRED`.
* `DesktopLivenessConfig` — reads `mosip.registration.liveness.*` from a `Map<String,Object>` (shape of `ApplicationContext.map()`).
* `LivenessAuditAdapter` + `DesktopAuditEvent` (`REG-LIV-001..018`, component `REG-MOD-153 LIVENESS`).
* `stream.MjpegInputStreamFrameSource` — `FaceFrameSource` over the `InputStream` returned by `BioService.getStream(...)`;
  same framing as the client's `Streamer.retrieveNextImage` (`Content-Length:` then JPEG from the next `0xFF`).
* `ui.LivenessOverlayController` + `LivenessOverlay.fxml` — oval guide (grey/amber/blue/green/red **plus** a text badge,
  never colour alone), hint label, challenge card with glyph placeholder (`icon-<type>` style class for real icons),
  progress dots, retry counter, device chip, online/offline chip, cancel, failure + locked panes, hidden diagnostics pane
  (only when `diagnostics.enabled`), high-contrast class, RTL for Arabic.

## Build

```
mvn -q -f integration-desktop/pom.xml install          # standalone: main + tests, no MOSIP artifacts downloaded
```
The `mosip` profile (`-Pmosip -Dmosip.version=1.2.0.2`) adds `src/mosip/java` and `registration-services` as
`provided`/`optional`. That profile has not been run. The MOSIP-side code is compiled inside a real registration-client checkout instead:
```
RUN_TESTS=1 integration-desktop/src/mosip/compile-against-client.sh <work-dir>   # clone, apply patches, build, tests
```
Result (see `../docs/testing/desktop-mosip-compile.md`): `release-1.2.0.x@c1cc4e7f` with all 7 patches applied.
registration-services compiles with JDK 11. registration-client compiles with our classes using JDK 21 `javac --release 11`. 59 existing registration-services tests pass.
The shipped mosip-vivid jars are now Java 11 bytecode (class file 55), so JDK 11 javac can read them, and the 1.2.0.x
client's Zulu 11 JRE can load them. Neither has been re-run against the client yet; see the follow-up in that doc.

## Applying to registration-client

```
git clone -b release-1.2.0.x https://github.com/mosip/registration-client && cd registration-client
for p in /path/to/mosip-vivid/integration-desktop/src/mosip/patches/*.patch; do git apply "$p"; done
cp -r /path/to/mosip-vivid/integration-desktop/src/mosip/java/io/mosip/vivid \
      registration/registration-client/src/main/java/io/mosip/          # hooks live in the client module
mvn -f /path/to/mosip-vivid install                                      # liveness-api, device-api, core, engine, integration-desktop
mvn -f registration/pom.xml package
mkdir -p registration/registration-client/target/models && cp -r <signed model bundle> registration/registration-client/target/models/
```
All seven patches apply against `release-1.2.0.x@c1cc4e7f` (files use CRLF; the patches do too), and the patched client compiles (see Build).
The `mvn ... package` line above needs JDK 21 with `-Dlombok.version=1.18.30 -Dmaven.compiler.release=11` for `registration-client`.

| Patch | Files | Change |
|---|---|---|
| 0001 | `config/AppConfig.java` | add `io.mosip.vivid.desktop.mosip` to `@ComponentScan` |
| 0002 | `controller/device/GenericBiometricsController.java`, `dto/RegistrationDTO.java`, `service/packet/impl/PacketHandlerServiceImpl.java` | FACE: `ResidentFaceLivenessHook.run(...)` replaces `rCaptureTaskService(); streamer.startStream(...)`; rcapture only on PASSED; `LivenessCaptureVerifier` after `isValidBiometric`; `livenessSummary` into packet `metaInfo` |
| 0003 | `controller/BaseController.java`, `controller/auth/AuthenticationController.java` | `captureAndValidateFace(..., ImageView)` overload: OPERATOR / SUPERVISOR (`isReviewer`) liveness before `captureModalityForAuth`, binding before `authValidator` |
| 0004 | `context/SessionContext.java`, new `service/bio/FaceAuthLivenessGuard.java` | login `validateFace`: optional `FaceAuthLivenessGuard` bean (implemented by `OperatorAuthLivenessHook`) runs OPERATOR liveness before and binding after `captureModalityForAuth`; services has no compile dependency on mosip-vivid |
| 0005 | `constants/AuditEvent.java`, `constants/Components.java` | `LIVENESS_*` events (`REG-LIV-0nn`) and `Components.LIVENESS("REG-MOD-153")` |
| 0006 | `constants/RegistrationConstants.java`, `messages_en.properties` | key constants; alert texts for `generateAlert` (fr/ar to be added the same way) |
| 0007 | `registration-client/pom.xml` | dependencies so `copy-dependencies` puts our jars (with in-jar natives) into `target/lib`; excludes our `slf4j-api` 2.x so the client keeps its own slf4j/logback |

## Call sequence (resident capture)

```
GenericBiometricsController.scan()
  deviceSearchTask -> bioService.getStream(device, "FACE_FULL FACE")         (existing)
  ResidentFaceLivenessHook.run(device, urlStream, biometricImage, onPassed, onBlocked)
    LivenessOverlayInstaller.install(biometricImage)    StackPane wrap, overlay on top
    Task: LivenessGate.verify(RESIDENT, MjpegInputStreamFrameSource(urlStream), overlay)
          sessions.open(ctx{nonce}, policy) -> LivenessOrchestrator
          source.start -> onFrame -> session.feed -> listener -> overlay.show*  ; timer -> session.tick
          FAILED & canRetry -> showRetry, retry after retry.delay_ms ; else showLocked
    accepted -> onPassed -> rCaptureTaskService() -> bioService.captureModality (RCAPTURE, signed)
    rejected -> generateAlert(messages key)  (generic text for PAD / drift)
  rCaptureTaskService.onSucceeded
    isValidBiometric -> LivenessCaptureVerifier.verify(lastResult, dtos)   -> CaptureBinding
    !accepted -> generic alert, capture discarded ;  accepted -> existing de-dup / ATTEMPTS / fxControl.setData
PacketHandlerServiceImpl.setMetaData -> metaInfo["livenessSummary"]
```
Operator / supervisor: `AuthenticationController.executeFaceValidationTask` → `streamer.stop()` →
`BaseController.captureAndValidateFace(userId, true, isReviewer, faceImage)` → `OperatorAuthLivenessHook.verify(OPERATOR|SUPERVISOR)`
→ `captureModalityForAuth` → `LivenessCaptureVerifier` → `authValidator` (local SDK match). Login: `SessionContext.validateFace` → `FaceAuthLivenessGuard`
(`OperatorAuthLivenessHook.beforeFaceAuthCapture` / `afterFaceAuthCapture`), headless.

Retries compose with the client's: liveness attempts happen *inside* one scan click and never touch
`RegistrationDTO.ATTEMPTS` / `num_of_face_retries` boxes, which still count successful rcaptures.

## Configuration (`ApplicationContext.map()`, synced global params)

Lookup: `mosip.registration.liveness.<resident|operator|supervisor>.<key>` → `mosip.registration.liveness.<key>` →
compiled default (`LivenessPolicy.defaults(workflow)`). Unparseable / out-of-range values are logged and ignored, never weakening policy.
Booleans accept `Y/N/true/false/1/0`.

| Key (prefix `mosip.registration.liveness.`) | Default |
|---|---|
| `enabled`, `<workflow>.enabled` | `true` (global `false` wins) |
| `policy.version` | `builtin-1.0` (`global-param` once any key is synced) |
| `passive.threshold` | 0.80 resident / 0.85 operator / 0.90 supervisor |
| `pad.hard_threshold` | 0.9 |
| `passive.window_ms` / `passive.min_frames` | 2500 / 8 |
| `active.mode` | `ON_LOW_CONFIDENCE` resident, `ALWAYS` operator/supervisor |
| `challenge.min` / `challenge.max` | 2/3 resident, 1/2 operator, 2/3 supervisor |
| `challenge.pool` | `BLINK,SMILE,TURN_LEFT,TURN_RIGHT,NOD` |
| `challenge.accessibility_pool` | `BLINK,SMILE,HOLD_STILL` |
| `challenge.require_depth` | true |
| `challenge.timeout_ms` | 8000 |
| `action.min_latency_ms` / `action.max_latency_ms` | 150 / 6000 |
| `retries` | resident: `mosip.registration.num_of_face_retries` if set, else 3; operator 3; supervisor 2 |
| `on_max_retries` | `ASSISTED_CAPTURE_WITH_SUPERVISOR` resident, `LOCK_AND_ALERT` operator/supervisor |
| `session.timeout_ms` | 60000 |
| `face.min_quality` | 0.4 |
| `identity.drift_threshold` / `identity.required` | 0.45 / true |
| `signal.weight.<signalId>` | 1.0 (0 disables) |
| `retry.delay_ms` / `tick_ms` | 1500 / 200 |
| `diagnostics.enabled` / `audio_cues` / `ui.high_contrast` | false / false / false |
| `locale` | `mosip.registration.application_language` (eng/fra/ara), else English |
| `engine` / `model.dir` | `onnx` / `models` |
| `stream.target_fps` / `stream.max_queued_frames` / `stream.stall_timeout_ms` / `stream.reconnect_attempts` | 15 / 4 / 3000 / 5 |

None of these match the client's restart-triggering patterns (`kernel`, `mosip.primary`, `mosip.biometric.sdk`, `languages`),
so they apply on the next capture after a config sync.

## Verified vs. unverified

**Verified (by running it):**
* `mvn -q -f integration-desktop/pom.xml install` passes on JDK 25 / `release 17`: 85 tests, 0 failures (messages completeness for every
  enum constant in en/fr/ar with no stray keys, generic mapping of PAD/drift, problem-statement texts, config parsing incl.
  overrides, fallbacks and bad values, MJPEG framing/stop/protocol errors, ISO 19794-5 extraction, capture binding outcomes,
  gate flows: pass via challenge, PAD retry → lock, device disconnect, disabled policy, cancel, engine open failure).
* All seven patches apply to `registration-client` `release-1.2.0.x@c1cc4e7f`. The patched registration-services compiles (JDK 11), and
  registration-client compiles together with `src/mosip/java` (JDK 21 `javac --release 11`). Five registration-services test classes pass:
  59 tests, 0 failures. See `../docs/testing/desktop-mosip-compile.md`.
* `src/main/java` (including the JavaFX overlay) also compiles with `javac --release 11` against openjfx 11.0.2, so it uses no Java 12+
  or JavaFX 12+ API.

**Unverified:**
* **Runtime on Zulu 11:** the shipped mosip-vivid jars are now compiled with `--release 11`, which removes the earlier
  `UnsupportedClassVersionError` blocker. All classes load on JDK 11.0.31, but the patched client itself has still not been started.
* Spring wiring and FX threading in `src/mosip/java` are type-checked only. The JavaFX overlay has not been rendered (no display).
* Liveness alert texts are added to `messages_en.properties` only; other client languages show the raw key.
* Behaviour against a real SBI or MockMDS: whether the SBI tolerates a `STREAM` connection being closed and an `RCAPTURE` opened
  afterwards (today the client keeps both open), actual stream fps/resolution, and JPEG 2000 ISO payloads.
* Server-side tolerance of the new `livenessSummary` metaInfo key and of the new audit event ids during audit sync.
* French strings are reasonable; Arabic strings are placeholder translations needing native review.
* `onMaxRetries` actions beyond lock/retry (supervisor-assisted capture, exception flow) are only described, not wired.
