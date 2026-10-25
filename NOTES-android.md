# NOTES-android.md — blockers and assumptions from the Android integration workstream

Owner: integration-android. The api contracts (`liveness-api`, `device-api`) were NOT modified.

## Assumptions about liveness-core (being written concurrently)

`integration-android/vivid-android/src/main/kotlin/io/mosip/vivid/android/bridge/CoreBindings.kt`
is the only file importing `io.mosip.vivid.core`. It assumes:

- `LivenessSessionFactory(engine: LivenessEngine)` with `open(policy: LivenessPolicy, ctx: SessionContext): LivenessSession`.
- `FrameSourceBridge(source: FaceFrameSource, session: LivenessSession)` with `start(options: StreamOptions)` and `stop()`;
  the bridge is expected to call `session.feed(frame)` on the source thread, map `DeviceHealth`
  changes to `SessionState.Hint.DEVICE_RECONNECTING/DEVICE_DISCONNECTED`, and call `session.tick()`
  when the source stalls (the Android bridge also ticks every 250 ms as a belt-and-braces).
- `LivenessSession.result()` returns a result (not throws) once the phase is FAILED or terminal.
- `LivenessSession.addListener` callbacks arrive on the feeding thread; the Android bridge hops to the main thread itself.

If any of these differ, only `CoreBindings.kt` and the `addListener` block in `LivenessPigeonApi.kt` need to change.

## Observations on the frozen api that affect Android (no change requested, just noted)

1. `FrameAnalyzer.onChallenge(Challenge)` has no "challenge ended" signal. The Android
   `ParallaxSignal` resets when the challenge type changes and relies on the orchestrator passing
   `null` (it accepts `onChallenge(null)`) or the next challenge. Suggest the orchestrator calls
   `onChallenge(null)` on challenge completion.
2. `FaceQuality.occlusion` cannot be estimated from MediaPipe; the engine reports 1.0.
3. `FaceFrame` copies nothing; CameraX reuses its buffers, so `FrameSink.onFrame` consumers must
   finish with the frame before returning. The core bridge must not queue `FaceFrame`s across
   threads without copying `pixels()`.
4. `SessionContext` has no field for the preview texture / UI surface; handled outside the api.
5. `DeviceCapabilities.nominalFps` is a `double`; the Android source reports the governor's
   current target (changes under thermal throttling) rather than a constant.

## Build status (24 Oct 2026; see docs/testing/android-compile.md)

- `vivid-android` **compiles**: Gradle 8.7, AGP 8.3.2, Kotlin 1.9.24, android-34, JDK 21, against the
  real liveness-api, device-api and liveness-core jars from mavenLocal. `./gradlew :vivid-android:testDebugUnitTest`
  runs 20 JVM tests and they pass. Debug and release AARs build.
- The Pigeon bindings were generated with Pigeon 10.1.6 (Dart 3.0.3 / Flutter 3.10.4) and are committed.
  `flutter analyze` reports no issues and `flutter test` passes 4 tests in a throwaway package named
  `registration_client`.
- Compile and test fixes: `StreamOptions` getters are methods, not fields. The Pigeon 10 FlutterApi callback is `() -> Unit`.
  `liveness_attempt_of` is a method in gen_l10n. `FrameSkipper` was over-throttling: its debt was charged
  on every candidate frame, so it ran at about 65 % of engine capacity. A state pushed before the
  `startSession` reply was dropped in Dart and is now buffered.
- Not run on any device or emulator.

## Blockers for other modules, found by dexing the jars with AGP 8.3.2's D8 (R8 8.3.37)

1. **liveness-core: `java.util.random.RandomGenerator`** (`ChallengeSelector`, `LivenessOrchestrator`
   constructor). This interface is absent from android-34's API database, and desugar_jdk_libs 2.0.4
   does not cover it, so the first challenge draw on Android fails with NoClassDefFoundError or
   NoSuchMethodError. Fix in core: type the field and parameter as `java.util.Random` (`SecureRandom` extends it).
2. **policy: `InputStream.readAllBytes()`** in `PolicyLoader` needs API 33, and desugaring does not cover it.
   Android uses `PolicyJson`, not `PolicyLoader`, so this only matters if core calls `PolicyLoader` on a
   device. Replacing the call with a small read loop would make it safe. `PolicyKeyTool` uses `Path.of`
   (API 34), but it is a CLI tool and never runs on Android.
3. The jars are compiled with `-parameters`. The standalone `d8` in build-tools 34.0.0 (R8 8.2.2) crashes
   with an NPE on their `MethodParameters` attributes. AGP 8.3.2's D8 (8.3.37) is fine, so ARC must not drop below AGP 8.3.

## ARC integration status (24 Oct 2026; see docs/testing/android-arc-integration.md)

- `integration-android/patches/0001..0008` apply with `git am` to ARC `release-1.2.x` @ `240c50d9` (the recon commit)
  and cover audit, native wiring (Gradle, Pigeon registration in `MainActivity`, Dagger, CAMERA, policy from global
  params), l10n, resident face capture, operator onboarding, operator login and supervisor / operator packet auth.
  `integration-android/apply-to-arc.sh` reproduces everything.
- Patched ARC: `flutter analyze` = 146 issues, the same set as unpatched; ARC's Flutter tests unchanged (5 files
  pass, `widget_test.dart` broken upstream); our 5 Dart tests pass in ARC; **`flutter build apk --debug` builds**
  (355 MB, 4 ABIs). Not run on a device; models not bundled.
- New in `vivid-android`: `face/` (FaceMatchPolicy, FaceMatcher, StillFaceEmbedder, EnrolledFaceProvider),
  `LivenessPigeonApi.policySummary / matchEnrolledFace / bindCapture`, anchors of PASSED sessions retained 5 min.
  28 JVM tests pass.
- Operator login and supervisor auth now **match the live face against the onboarded face**: ARC stores the raw
  ISO 19794-5 record in `user_biometric.bio_template` (no Android template extraction), so it can be decoded and
  embedded with our MobileFaceNet. Threshold 0.50 cosine similarity, uncalibrated.
- ARC bugs found: nothing writes the `user_id` preference, so onboarding stored every operator under `usr_id = ""`
  (fixed in patch 0006: falls back to `preferred_username`); `AuditDetailsApi.audit` never completes its Pigeon reply
  (not changed; we do not await it); `test/widget_test.dart` does not compile.
- Build fixes: ARC's libraries are Dart language 2.19 (`sdk: ">=2.19.6 <3.0.0"`), so the overlay's switch
  expression had to go; `protobuf-java` (biometrics-util) duplicates MediaPipe's `protobuf-javalite` and is
  excluded. MediaPipe 0.10.14 does ship x86 natives (an earlier note here was wrong).

## Registration-client facts the other workstreams should know (from docs/recon)

- ARC is Flutter + Java (not Kotlin) host APIs, Pigeon 10.x with **Java** codegen, no `@FlutterApi` anywhere yet.
- There is no camera use, no `CAMERA` permission and no live preview during face capture: the SBI
  vendor app owns the screen during `.rCapture`. CameraX is therefore the only frame source (PLAN 3.2 option 1).
- There is **no biometric operator login or supervisor auth on Android**; both are password-only.
  The patches add liveness plus a match against the operator's onboarded face (see above).
- Global params: `GlobalParamRepository.getCachedStringGlobalParam(key)`; no offline defaults for
  unknown keys (returns null / 0), so policy defaults must be compiled in.
