# Android integration: compile and test record

Date: 24 Oct 2026. Scope: `integration-android/` (Kotlin library `vivid-android`, Pigeon bindings, Flutter
overlay). Machine: Kali x86_64 with 7.7 GB RAM. No device or emulator was used.

## Result summary

| Item | Tool | Result |
|---|---|---|
| `vivid-android` Kotlin, 21 main files + generated Pigeon file | Gradle 8.7, AGP 8.3.2, Kotlin 1.9.24, compileSdk 34, JDK 21 | **Compiles.** Only 2 deprecation warnings remain (`setTargetResolution`) |
| JVM unit tests (`src/test/kotlin`) | JUnit 4.13.2, `testDebugUnitTest` | **20/20 pass** |
| AAR packaging | `assembleDebug`, `assembleRelease` | **Builds** (~170 KB; models not bundled) |
| Pigeon codegen | Pigeon 10.1.6 (resolved from `^10.0.1`, as ARC pins), Dart 3.0.3 | **Generated** Kotlin and Dart, both committed |
| Dart (`flutter/lib/liveness`, `flutter/lib/pigeon`, tests) | Flutter 3.10.4 (ARC's pin), `flutter_lints` 2.0 | **`flutter analyze`: no issues** |
| Flutter tests (`flutter/test/liveness`) | `flutter test` | **4/4 pass** |
| `gen-l10n` with the liveness ARBs merged into `app_{en,fr,ar}.arb` | Flutter 3.10.4 | **Generates** |
| Our Java jars on Android (API check) | D8 from R8 8.3.37 (AGP 8.3.2) plus an `api-versions.xml` scan | Dexes. **3 API problems found**, see below |
| On-device behaviour (CameraX, MediaPipe, TFLite, thermal) | none | **Not verified** |

## Fixes the compilers forced

- `CameraXFrameSource`: `StreamOptions` exposes `targetFps()` and similar as methods. The fields are private.
- `LivenessPigeonApi.publish`: in Pigeon 10.x a FlutterApi void method takes `callback: () -> Unit`, not
  `(Result<Unit>) -> Unit`.
- `PolicyJson`: replaced `optString(key, null)` with a null-safe helper, because the android.jar fallback
  parameter is non-null. `SessionDtoMapper`: removed unneeded casts.
- `liveness_messages.dart`: gen_l10n turns `liveness_attempt_of` (which has placeholders) into a method, not a getter.
- `tools/lint.sh` now skips generated, test and build directories.

## Bugs the tests caught

- `FrameSkipper` charged the "processing debt" on every candidate frame, including frames that arrived
  right after a skip. With a 120 ms engine it throttled to about 5.3 fps when the engine could sustain
  8.3 fps. The effective interval is now `max(1/targetFps, EMA(processing time))`.
- `LivenessController` (Dart): native publishes the first `SessionState` before the `startSession` reply,
  and Dart dropped it because no listener was registered yet. Early states are now buffered and replayed.
  The first Flutter test covers this.

## Android API-level check of the monorepo jars (cross-module findings)

The liveness-api, device-api, liveness-core, policy and telemetry jars were dexed with
`--min-api 24` and `--min-api 28` (ARC's minSdk). Every `java.*` and `android.*` reference was then
compared with `platforms/android-34/data/api-versions.xml`. At minSdk 28:

| Reference | Where | Impact |
|---|---|---|
| `java.util.random.RandomGenerator.nextInt(I)` | liveness-core `ChallengeSelector`, `LivenessOrchestrator` constructor | **Runtime blocker.** Android has no such interface, and desugar_jdk_libs 2.0.4 does not provide it (checked its `desugar.json`). The challenge draw throws. Fix in core: use `java.util.Random`. |
| `InputStream.readAllBytes()` (API 33) | policy `PolicyLoader` | Crashes below Android 13 if core calls `PolicyLoader` on a device. Android itself uses `PolicyJson`. |
| `Path.of` (API 34) | policy `PolicyKeyTool` | CLI only, harmless |

At minSdk 24, `java.time`, `java.nio.file` and `Base64` (API 26) also show up. Core library
desugaring covers `java.time`, but not `java.nio.file`. The library declares minSdk 24, but ARC's minSdk is 28.

The jars are built with `-parameters`. The standalone `d8` in build-tools 34.0.0 (R8 8.2.2) throws an
NPE on their `MethodParameters` attribute. AGP 8.3.2's R8 8.3.37 dexes them fine, so keep AGP at 8.3 or later.

## How to reproduce

The SDKs live outside the repo. Below, `$SDK` is an Android SDK with `platforms;android-34` and
`build-tools;34.0.0` installed, and `$FLUTTER` is Flutter 3.10.4. Install the jars first with
`mvn -q install -DskipTests` in the monorepo root.

```sh
cd integration-android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=$SDK
./gradlew --no-daemon :vivid-android:compileDebugKotlin :vivid-android:testDebugUnitTest :vivid-android:assembleRelease
```

The Flutter embedding is a `compileOnly` dependency at revision `vivid.flutterEngineVersion` in
`gradle.properties` (engine `2a3401c9bb…`, the engine of Flutter 3.10.4). It is fetched from
`storage.googleapis.com/download.flutter.io`, the same repository ARC's Flutter Gradle plugin adds.

To regenerate Pigeon and run the Dart checks, build a throwaway package that imports the code the
way ARC does:

```sh
mkdir -p /tmp/arc/{lib,pigeon,assets/l10n} && cd /tmp/arc
# pubspec.yaml: name: registration_client; deps flutter, flutter_localizations, intl, provider ^6;
#   dev: flutter_test, pigeon ^10.0.1, flutter_lints ^2.0.0; flutter: generate: true
# l10n.yaml: arb-dir assets/l10n, template-arb-file app_en.arb, output-localization-file app_localizations.dart
# assets/l10n/app_{en,fr,ar}.arb = liveness_{en,fr,ar}.arb (+ any ARC keys)
cp <repo>/integration-android/vivid-android/pigeons/liveness.dart pigeon/
$FLUTTER/bin/flutter pub get
$FLUTTER/bin/dart run pigeon --input pigeon/liveness.dart \
  --dart_out lib/pigeon/liveness_pigeon.dart \
  --kotlin_out gen/LivenessPigeon.kt --kotlin_package io.mosip.vivid.android.bridge.gen
cp -r <repo>/integration-android/flutter/lib/liveness lib/ && cp -r <repo>/integration-android/flutter/test .
$FLUTTER/bin/flutter analyze && $FLUTTER/bin/flutter test -j 1
```

Pigeon writes the package name into the channel names
(`dev.flutter.pigeon.registration_client.…`), so the package must be named `registration_client`.

## Environment notes

- The installed `/usr/bin/gradle` is 4.4.1 and is unusable, so the build uses the committed wrapper (8.7).
  JDK 21 runs the build; JDK 25 is too new for Gradle 8.7.
- Footprint outside the repo: about 300 MB of SDK (platform, build-tools, platform-tools), a 1.2 GB Gradle
  cache, about 800 MB of Flutter after removing its git history, and a 160 MB pub cache. Use
  `org.gradle.jvmargs=-Xmx1536m` and `--no-daemon` on machines with 8 GB or less. One earlier run was
  OOM-killed while a Gradle daemon and a Flutter test run were up at the same time.
- Nothing in `integration-android/.gitignore`d paths (`.gradle/`, `build/`, `local.properties`, SDKs,
  `.dart_tool/`, model binaries) belongs in git.

## Not done

- No lint (`lintDebug`), no APK built with MediaPipe or TFLite natives, and no instrumentation tests.
  These need an app module and a device or emulator.
- Thresholds, YUV handling, the MediaPipe matrix conventions and TFLite tensor types are still the open
  items in `integration-android/README.md`, "Unverified".

## Follow-up (24 Oct 2026): Java 11 / Android API

The jars Android uses (liveness-api, device-api, liveness-core, policy, telemetry) are now Java 11 bytecode
(`--release 11`, class file 55). The three findings above are resolved:

| Finding | Fix |
|---|---|
| `java.util.random.RandomGenerator` in `ChallengeSelector` / `LivenessOrchestrator` | The public signatures now take `java.util.Random` (`SecureRandom` extends it). The Kotlin bridge already passes a `SecureRandom`, so it needs no change. The core tests now use seeded `new Random(n)` instead of `SplittableRandom`. |
| `InputStream.readAllBytes()` in `PolicyLoader` | Replaced by a package-private loop, `policy/Compat.readAllBytes`. `Files.readString` in `PolicyLoader.parse(Path)` became `new String(Files.readAllBytes(..), UTF_8)`. |
| `Path.of` / `Files.readString` / `Files.writeString` in `PolicyKeyTool` | Now `Paths.get`, `Files.readAllBytes` and `Files.write`. |

`String.isBlank()` was used in policy (`PolicyLoader`, `PolicyValidator`, `PropertiesPolicyAdapter`) and telemetry
(`LivenessMetrics`, `OfflineAuditQueue`). All of these now use small `Character.isWhitespace` helpers with the same
semantics. No `strip`, `repeat`, `lines` or Java 12+ calls remain in the five modules. device-sbi-stream still uses
`isBlank`, but it is desktop-only.

Rechecked as before, but with sources compiled by JDK 11 `javac --release 11`. JDK 21+ `javac` writes a
`MethodParameters` attribute with null names for enum constructors, and the standalone build-tools 34 `d8` crashes on
it. AGP 8.3's R8 handles it. The dex was built with `d8 --min-api 28` and scanned against `api-versions.xml`:
**294 framework references, 0 above API 28.** `List.of`/`copyOf` and `Objects.requireNonNullElse` (API 30) are still
in the source. D8 rewrites them into `$$ExternalSyntheticBackport` helpers, so they reach the device as backports, not
framework calls. Not re-run: the Gradle build of `vivid-android`.
