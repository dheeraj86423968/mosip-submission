# Android Registration Client: applying and building the liveness integration

Date: 24 Oct 2026. Scope: `integration-android/patches/*.patch`, the `vivid-android` module and the Dart files in
`integration-android/flutter/lib/liveness`, applied to the real MOSIP Android Registration Client (ARC) and built.
This is the Android counterpart of `desktop-mosip-compile.md`.

## Target

`https://github.com/mosip/android-registration-client`, branch **`release-1.2.x`**, commit
**`240c50d988e53b8fcfc86a654a2aeb2f5e48f64c`** (2026-10-14, "Merge pull request #1127"). This is the commit the
recon doc (`docs/recon/android-registration-client.md`) was written against, and it was still the branch head
when cloned. `apply-to-arc.sh` pins it (`ARC_COMMIT`) and fetches it by SHA if the branch has moved on.

## Result in one paragraph

All 8 patches apply with `git am` to `240c50d9`. In the patched app, `pigeon.sh` generates all 19 Pigeon files
(the 18 existing ones plus `liveness_pigeon.dart`); **`flutter analyze` on the whole app reports the same 146 issues as
the unpatched commit** (1 error, 42 warnings, 103 infos, all in ARC code, identical once line numbers are ignored);
ARC's own Flutter tests give the same results patched and unpatched (5 files pass, `widget_test.dart` fails to compile
in both); our 5 liveness tests pass inside ARC; and **`flutter build apk --debug` builds** a 355 MB debug APK. The
APK contains `vivid-android`, the `io.mosip.vivid` api/core/policy/telemetry classes, MediaPipe tasks-vision 0.10.14
and TFLite 2.16.1 natives for arm64-v8a, armeabi-v7a, x86 and x86_64, the model manifest, and the merged `CAMERA`
permission. **Nothing was run**: no device or emulator, no models in the APK, and the face-match thresholds are uncalibrated.

## The patch series

Created as commits on top of `240c50d9` and exported with `git format-patch`; `apply-to-arc.sh` replays them with
`git am`. Each patch names the real ARC classes and methods it changes.

| Patch | ARC files | What it does |
|---|---|---|
| 0001 audit | `clientmanager/.../constant/AuditEvent.java`, `Components.java` | `Components.LIVENESS("REG-MOD-111")`; `LIVENESS_SESSION_STARTED/PASSED/FAILED/LOCKED`, `LIVENESS_IDENTITY_DRIFT`, `LIVENESS_CAPTURE_BOUND`, `LIVENESS_DEVICE_FAULT`, `LIVENESS_POLICY_DISABLED`, `LIVENESS_FACE_MATCH_PASSED/FAILED`. Ids reuse the desktop's `REG-LIV-0xx` where the meaning matches; descriptions take (session id, detail) for `auditWithArguments`. |
| 0002 native wiring | `android/settings.gradle`, `android/build.gradle`, `android/app/build.gradle`, `AndroidManifest.xml`, `MainActivity.java`, `HostApiModule.java`, `pigeon.sh`, `pigeon/liveness.dart`, new `liveness/ArcLiveness.java`, `liveness/LivenessPolicySource.java` | `include ':vivid-android'` and `implementation project(':vivid-android')`; `mavenLocal()` restricted to `io.mosip.vivid`; `noCompress 'tflite','task'`; `pickFirst libtensorflowlite_jni.so`; `x86` dropped from `abiFilters`; `protobuf-java` excluded (see "What had to be fixed"); CAMERA permission; `LivenessPigeonApi.attach(...)` + `LivenessHostApi.Companion.setUp(...)` in `configureFlutterEngine`, `shutdown()` in `onDestroy`; Dagger provider; Pigeon line in `pigeon.sh`; policy from global params `mosip.registration.liveness.<workflow>.*`. |
| 0003 l10n | `assets/l10n/app_{en,fr,ar}.arb` | 66 `liveness_*` keys merged; `hi`, `kn`, `ta` fall back to English (gen-l10n lists them as untranslated). |
| 0004 Dart helper | new `lib/liveness/liveness_flow.dart` | `runLivenessGate(context, workflow, matchUserId:)`: policy summary, camera permission, full-screen `LivenessOverlay`, optional enrolled-face match, localised failure message. |
| 0005 resident capture | `biometric_capture_scan_block_portrait.dart` (`_scanBlock`, scan `onPressed`), `BiometricsDetailsApi.parseRCaptureResponse`, new `IsoFaceImages.java`, `LivenessCaptureBinder.java`, `HostApiModule`, `MainActivity` | RESIDENT liveness before `invokeDiscoverSbi` for the Face attribute; the signed ISO face from `rcapture` is matched off the main thread against the session's anchor embedding and stored only on a match, otherwise `invokeDiscoverSbi` resolves `LIVENESS_CAPTURE_MISMATCH`. |
| 0006 operator onboarding | `operator_biometric_capture_scan_block_view.dart` (`_scanBlock`), `BiometricsDetailsApi` (operator branch), `UserOnboardService.onboardOperator` | OPERATOR liveness before the onboarding face capture; the capture is bound to that session; operator templates are keyed by `preferred_username` (ARC bug, below). |
| 0007 operator login | `lib/ui/login_page.dart` (`_getLoginAction`), new `ArcEnrolledFaceProvider.java`, `HostApiModule` | After the password succeeds: OPERATOR liveness, then the live face is matched against the operator's onboarded face; on failure `logoutUser()` and a message. |
| 0008 supervisor / packet auth | `approve_packet/widget/authenticate_dialogbox.dart` (`_authenticatePacketPassword`), `process_ui/generic_process.dart` (`_authenticatePacket`) | Packet approval: SUPERVISOR liveness + face match. Operator authentication before packet creation: OPERATOR liveness + face match. These are the only two password dialogs besides login in 1.2.x. |

Not in the patches, copied by the script: `integration-android/vivid-android` -> `android/vivid-android`,
`integration-android/flutter/lib/liveness/liveness_{controller,overlay,messages}.dart` -> `lib/liveness/`, and
`integration-android/flutter/test/liveness/*` -> `test/liveness/`.

## Operator face matching (login and supervisor authentication)

What ARC stores: operator onboarding (`UserOnboardService.save`) writes one `user_biometric` row per captured
attribute. `getExtractedTemplates` does **not** extract templates on Android (the SDK call is a TODO), so for the
face `bio_template` holds `BIR.getBdb()`, which `RegistrationServiceImpl.buildBIR` sets to the base64url-decoded SBI
`bioValue`: the raw **ISO/IEC 19794-5** face record. The Room database is SQLCipher-encrypted on disk, but Room
returns plaintext, and ARC already decodes the same records for display (`FaceBDIR` + `JP2Decoder` in
`BiometricsDetailsApi.extractImageValues`). So the stored data **is usable**.

The match: `ArcEnrolledFaceProvider` loads the user's FACE rows (case-insensitive `usr_id`) and decodes them
(`IsoFaceImages`: `FaceBDIR`, then JPEG 2000 via `JP2Decoder`, or JPEG via `BitmapFactory`).
`vivid-android`'s `StillFaceEmbedder` finds the face with MediaPipe FaceLandmarker (IMAGE mode), applies the live
engine's crop (square, side = max landmark box side, 112x112) and runs the same MobileFaceNet. `FaceMatcher` takes
the best cosine similarity against the anchor embedding the PASSED session already computed (kept for 5 minutes
after the session is released). Policy (`faceMatch` object in the workflow policy, from global params
`mosip.registration.liveness.<workflow>.faceMatch.*`):

| Key | Default | Meaning |
|---|---|---|
| `enabled` | true | off = liveness only |
| `threshold` | **0.50** cosine similarity | accept when best similarity >= threshold. **Not calibrated.** The live anchor is from the tablet front camera, the reference from the SBI camera; in-session continuity uses 0.45 cosine distance (= 0.55 similarity) on one camera, so the cross-camera default is set lower. Calibrate per deployment. |
| `requireEnrolment` | false | true = a user with no usable enrolled face is refused instead of passing on liveness alone |

Outcomes: `MATCHED`, `NOT_MATCHED`, `NO_ENROLLED_FACE`, `ENROLMENT_UNUSABLE`, `NO_ANCHOR`, `NO_FACE`, `UNAVAILABLE`,
`DISABLED`. Missing models or a decoder failure fail closed (`UNAVAILABLE`); every decision is audited
(`LIVENESS_FACE_MATCH_PASSED/FAILED` with the similarity and threshold). The decision logic has 8 JVM unit tests
(`FaceMatcherTest`).

**ARC bug found on the way (fixed in patch 0006):** nothing in release-1.2.x writes the `user_id` preference that
`UserOnboardService.onboardOperator` reads, so every operator's templates were saved under `usr_id = ""`, and each
onboarding deleted the previous operator's rows (`clearUserBiometrics("")`). The same empty key also makes
`MatchUtil.validateBiometricData` skip the operator dedupe check (`Biometrics095Service`, not changed here). The patch
falls back to `preferred_username` (the IDA individual id and the `user_detail` id). Rows written before the fix
cannot be attributed to an operator and are ignored, so those operators count as "no enrolled face" until they
onboard again.

If a deployment ever stores vendor templates instead (after a real `IBioApiV2.extractTemplate`), decoding fails,
the outcome is `ENROLMENT_UNUSABLE`, and an SDK-backed comparison can be plugged in behind the
`io.mosip.vivid.android.face.EnrolledFaceProvider` interface. That path is documented, not implemented.

## Commands (rerunnable)

```sh
mvn -q install -DskipTests                       # mosip-vivid root: io.mosip.vivid jars into ~/.m2
export ANDROID_HOME=<sdk with platforms;android-34, build-tools;34.0.0>
export FLUTTER_ROOT=<Flutter 3.10.4>             # default ~/.cache/vivid-android/flutter
integration-android/apply-to-arc.sh <work> baseline                     # unpatched: pigeon.sh, analyze, tests
integration-android/apply-to-arc.sh <work> patch pub analyze test       # patched
ARC_BUILD_DIR=/some/tmpfs/dir integration-android/apply-to-arc.sh <work> apk
```

Flutter was installed with `git clone --depth 1 -b 3.10.4 https://github.com/flutter/flutter` (Dart 3.0.3, engine
`2a3401c9bb`). `pub`, `pigeon.sh` (which runs `build_runner` and all 19 Pigeon files) and `gen-l10n` ran from
pub.dev and Google storage without problems.

## Results

| Check | Unpatched `240c50d9` | Patched |
|---|---|---|
| `sh pigeon.sh` (pub get, build_runner, Pigeon 10.1.6) | ok, 18 files in `lib/pigeon` | ok, 19 (adds `liveness_pigeon.dart`; Kotlin side written into `android/vivid-android`) |
| `flutter analyze` (whole app) | 146 issues: 1 error, 42 warnings, 103 infos | 146 issues: 1 error, 42 warnings, 103 infos; same set as unpatched (diffed without line numbers). 0 in `lib/liveness` or the patched widgets |
| `flutter test` (ARC's own) | approve_packets_provider 2, login 24, machine_details 3, reject_dialog 2, sync_provider 5 pass; `widget_test.dart` fails to compile | identical to unpatched |
| `flutter test test/liveness` | n/a | 5 pass (controller, policy/face-match calls, i18n keys, overlay widget) |
| `vivid-android` JVM tests (standalone Gradle) | n/a | 28 pass (20 earlier + 8 `FaceMatcherTest`) |
| `flutter build apk --debug` | not built | **builds** (`app-debug.apk`, 355 MB, 4 ABIs; first full build about 9 min, incremental 25 s) |

The one `flutter analyze` error is pre-existing: `test/widget_test.dart:166` calls `PasswordComponent` without the
required `onTapForgotPassword`, so that test file never compiles, patched or not.

None of ARC's Flutter tests covers the patched widgets: `test/` has login validator tests, `approve_packets_provider`,
`reject_dialog`, `machine_details`, `sync_provider` and the broken `widget_test.dart`. There are no tests for
`LoginPage`, `AuthenticateDialogBox`, `GenericProcess` or the two scan blocks, and no JVM tests in `android/app`.

## What had to be fixed

1. **Dart language version.** ARC's `pubspec.yaml` says `sdk: ">=2.19.6 <3.0.0"`, so its libraries are analysed at
   language 2.19 even on Dart 3.0.3. The overlay's switch expression was a Dart 3 feature; it is now a classic
   switch. The stand-in package used for the earlier compile pass had a Dart 3 constraint, which is why this was not caught before.
2. **`use_build_context_synchronously`.** The Dart 3.0 linter does not accept `context.mounted` as a guard; the gate
   helper takes the `NavigatorState` before its first `await`.
3. **Duplicate protobuf classes** (`checkDebugDuplicateClasses`): `io.mosip.biometric.util:biometrics-util:1.3.0`
   pulls `protobuf-java 3.16.3`, MediaPipe `tasks-core` pulls `protobuf-javalite 4.26.1`. Neither biometrics-util's
   classes nor ARC's sources reference `com.google.protobuf`, so patch 0002 excludes `protobuf-java` from the app's
   configurations.
4. **x86 is fine.** The integration notes said MediaPipe ships no 32-bit x86 natives and told ARC to drop `x86`
   from `abiFilters`. The debug APK has `lib/x86/libmediapipe_tasks_vision_jni.so` (tasks-vision 0.10.14), so the
   patches leave ARC's `abiFilters` alone.
5. **Pigeon audit reply.** `AuditDetailsApi.audit` never calls `result.success`, so a Dart `await` on
   `AuditResponseApi.audit` never completes. Existing ARC callers do not await it; the gate helper does not either
   (`unawaited`). Not changed in ARC.

## Not verified

- After the final ARC build, `LivenessPigeonApi.kt` got one refactor (workflow-name parsing moved into a helper so
  `tools/lint.sh` passes). It was recompiled and its 28 JVM tests rerun in the standalone build, not rebuilt inside ARC.

- Nothing ran on a device or emulator: CameraX preview into the Flutter texture, MediaPipe and TFLite inference, the
  FaceBDIR/JP2 decode of real SBI images, the capture-binding and face-match thresholds, and the 5-minute anchor
  retention are all unexercised.
- The models are not in the APK. `face_landmarker.task`, the MiniFASNet int8 and MobileFaceNet `.tflite` files are
  not in the repo; `VIVID_MODELS_DIR` copies them in and writes their SHA-256 into the manifest. Without them the
  engine fails at session start, and every gate fails closed with "camera not available" (`engineError`).
- The face-match threshold (0.50) and capture-binding threshold are uncalibrated (see above).
- Release build, R8/ProGuard (`consumer-rules.pro` is untested with minify), APK size and D8 memory with
  `minifyEnabled` were not checked. ARC's release type has `minifyEnabled false`.
- `hi`, `kn`, `ta` show English liveness strings.
- The RESIDENT `ASSISTED_CAPTURE_WITH_SUPERVISOR` max-retries policy has no supervisor-override UI on Android: the
  overlay is opened without `onSupervisorOverride`, so a resident who exhausts attempts goes back to the scan screen
  (ARC's own biometric exception marking still applies).
- ARC's clientmanager JVM tests (e.g. `UserOnboardServiceTest`, which touches the changed `onboardOperator`) were not
  run, to stay within memory; see "Environment".

## Environment

- Kali x86_64, 7.7 GB RAM, JDK 21, Android SDK platform 34 / build-tools 34.0.0, Gradle 8.5 (ARC's wrapper
  properties; Flutter wrote the wrapper scripts), AGP 8.3.2, Kotlin 1.9.24, Flutter 3.10.4 / Dart 3.0.3.
- Gradle ran with `org.gradle.daemon=false`, `-Xmx1536m`, 2 workers, Kotlin in-process (written to
  `GRADLE_USER_HOME/gradle.properties` by the script), one heavy process at a time. No OOM; D8 did not run out of
  memory at `-Xmx1536m` for the debug build.
- **Disk was the limit, not memory.** The first APK attempt filled `/` (5.1 GB free at the start, 379 MB at the end):
  the Gradle cache grew from 1.2 GB to 5.5 GB (ARC's dependencies, the Gradle 8.5 distribution, instrumented
  build-script jars of ~30 Flutter plugin projects), plus about 750 MB of Flutter engine artifacts. That attempt also
  hit the protobuf duplicate. The successful builds put ARC's `build/` (every module's Gradle buildDir, about 2 GB)
  on the RAM tmpfs through `ARC_BUILD_DIR` (a symlink).
- After the run, the ARC clone, its build directory, the Flutter SDK and the pub cache were deleted, and the Gradle
  cache was trimmed (instrumented `jars-9`, `transforms-3`, Gradle 8.5 caches and distribution removed);
  `~/.cache/vivid-android` is back to 2.1 GB (it was 1.2 GB before; the rest is ARC's downloaded dependencies in
  `modules-2`). The logs of the final run are kept in `~/.cache/vivid-android/logs/`. Rerunning `apply-to-arc.sh` downloads them again
  (Flutter about 1.2 GB with its engine artifacts, pub about 300 MB).
