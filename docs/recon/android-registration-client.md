# Reconnaissance: MOSIP Android Registration Client (ARC)

Source: `https://github.com/mosip/android-registration-client`, branch **`release-1.2.x`**
(latest `release-*` branch; HEAD `240c50d9`, 2026-10-14). Other candidates were `develop`,
`develop-native`, `develop-java21`; tags stop at `v1.1.1`, so `release-1.2.x` is the newest line.
Read via a blobless, sparse checkout of `android/app/src/main/java`, `android/clientmanager/src/main/java`,
`android/keymanager/src/main/java`, `lib/`, `pigeon/`, `assets/l10n/`, the Gradle files and `pubspec.yaml`
(5.3 MB, 479 files). Everything below is cited to a path in that tree. "Unknown" means it is not
derivable from the checked-out sources.

## 1. Architecture

| Layer | Where | Notes |
|---|---|---|
| Flutter UI | `lib/` (Dart, Flutter SDK 3.10.4 pinned by `AGENTS.md`/CI; `provider` for state) | `lib/main.dart` builds a `MultiProvider` (`GlobalProvider`, `AuthProvider`, `BiometricCaptureControlProvider`, ...) and a `MaterialApp` with `AppLocalizations.localizationsDelegates`. Routing in `lib/app_router.dart`. |
| Dart SPI / impl | `lib/platform_spi/*.dart` (abstract) → `lib/platform_android/*_impl.dart` | Each impl wraps a Pigeon-generated `XxxApi()` call in try/catch(`PlatformException`). |
| Pigeon definitions | `pigeon/*.dart` (18 files, e.g. `pigeon/biometrics.dart`, `pigeon/auth_response.dart`, `pigeon/audit_response.dart`, `pigeon/global_config_settings.dart`) | `pigeon: ^10.0.1`. Only `@HostApi()` is used today; **no `@FlutterApi()` exists anywhere** (native never pushes events to Dart; Dart polls). |
| Generated code | Dart: `lib/pigeon/<name>_pigeon.dart`; native: `android/app/src/main/java/io/mosip/registration_client/model/<Name>Pigeon.java` | Both directories are **generated, not committed** (absent in the tree). Generation is `sh pigeon.sh` which runs `dart run pigeon --input pigeon/x.dart --dart_out lib/pigeon/x_pigeon.dart --java_out .../model/XPigeon.java --java_package io.mosip.registration_client.model` (Java output, not Kotlin). |
| Native host APIs | `android/app/src/main/java/io/mosip/registration_client/api_services/*Api.java` | One class per Pigeon HostApi, e.g. `BiometricsDetailsApi implements BiometricsPigeon.BiometricsApi`. Provided as `@Singleton` by Dagger in `android/app/src/main/java/io/mosip/registration_client/HostApiModule.java`; `AppComponent.inject(MainActivity)`. |
| Registration | `MainActivity.configureFlutterEngine()` (`MainActivity.java:394-423`) | `BiometricsPigeon.BiometricsApi.setup(flutterEngine.getDartExecutor().getBinaryMessenger(), biometricsDetailsApi); biometricsDetailsApi.setCallbackActivity(this);` -- same pattern for every API. |
| Native library modules | `android/clientmanager` (services, Room DB, SBI, audit, config), `android/keymanager`, `android/packetmanager`, `android/transliterationmanager` | `app → clientmanager, keymanager, packetmanager, transliterationmanager` (`android/AGENTS.md`). Business logic is Java (not Kotlin) although `kotlin-android` is applied with Kotlin 1.9.24. |
| DI | Dagger 2 (`AppComponent`, `AppModule`, `RoomModule`, `NetworkModule`, `HostApiModule`) | `MainActivity.onCreate` → `DaggerAppComponent.builder()...build().inject(this)` (`MainActivity.java:382-389`). |

**How a new API is added (observed recipe):**
1. Write `pigeon/<name>.dart` with `@HostApi()` (and, new for us, `@FlutterApi()`).
2. Add a `dart run pigeon ...` line to `pigeon.sh`.
3. Implement `io.mosip.registration_client.api_services.<Name>Api implements <Name>Pigeon.<Name>Api`.
4. Provide it in `HostApiModule` (`@Provides @Singleton`), `@Inject` it in `MainActivity`, call `<Name>Pigeon.<Name>Api.setup(messenger, impl)` in `configureFlutterEngine`.
5. Add `lib/platform_spi/<name>_service.dart` + `lib/platform_android/<name>_service_impl.dart`, consume from a provider/widget.

## 2. Biometric capture (SBI)

| Item | Finding |
|---|---|
| Intent actions | `android/clientmanager/.../constant/RegistrationConstants.java:39-43`: `DISCOVERY_INTENT_ACTION = "io.sbi.device"`, `D_INFO_INTENT_ACTION = ".Info"`, `R_CAPTURE_INTENT_ACTION = ".rCapture"`, request extra key `"input"`, response extra key `"response"`. There is **no `.Capture` (auth-capture) action anywhere** - the Android client only ever performs registration capture. |
| Driver class | `android/app/src/main/java/io/mosip/registration_client/api_services/BiometricsDetailsApi.java` (1154 lines). Flow: `invokeDiscoverSbi(fieldId, modality)` (L134) → `discoverSBI()` (L910, `startActivityForResult(intent, 1)`) → `MainActivity.onActivityResult` case 1 → `parseDiscoverResponse` (L978) → `info(callbackId)` (L928, `callbackId + ".Info"`, request 2) → `parseDeviceInfoResponse` (L989) → `rcapture(callbackId, serialNo)` (L945, `callbackId + ".rCapture"`, request 3; builds `CaptureRequest` via `Biometrics095Service.getRCaptureRequest`) → `parseRCaptureResponse(Bundle)` (L1067) which reads a `Uri` from the result, calls `Biometrics095Service.handleRCaptureResponse` (JWT validation, transaction-id check, quality threshold) and stores `BiometricsDto`s into `RegistrationDto.addBiometric(fieldId, attribute, attempt, dto)` or `UserOnboardService.getOperatorBiometrics()`. Completion is signalled to Dart by resolving the stored `biometricResult` (`Result<String>` captured in `invokeDiscoverSbi`). |
| SBI service layer | `android/clientmanager/.../service/Biometrics095Service.java` (spec 0.9.5 only): `getRCaptureRequest` (L110), `handleRCaptureResponse` (L139/144), `handleDeviceInfoResponse` (L241), `handleDiscoveryResponse` (L306), `getModalityThreshold` (L332, FACE → global param `mosip.registration.face_threshold`), `getAttemptsCount` (L420, FACE → `mosip.registration.num_of_face_retries`). `Modality.FACE` has `deviceSubId 0`, `SingleType.FACE`. |
| Live preview during face capture | **None.** The client never opens a camera, has no `CAMERA` permission (`AndroidManifest.xml` permissions: storage, internet, network state, wake lock, boot, exact alarm, location only), no `/stream` equivalent, and no CameraX / camera plugin in `pubspec.yaml` or any `build.gradle`. The vendor SBI app owns the screen while the `.rCapture` activity runs; the client only receives the final signed image and renders it with `Image.memory` (decoded from the FaceBDIR in `extractImageValues`, `BiometricsDetailsApi.java:212-260`). Whether a vendor SBI shows its own preview is vendor-specific - unknown from this repo. |
| Face capture widget (resident) | `lib/ui/process_ui/widgets/generic_process_screen_content.dart:100` routes field type `"biometrics"` → `lib/ui/process_ui/widgets/biometric_capture_control.dart` `BiometricCaptureControl` → (after `BiometricCaptureControlInitialization` reads thresholds/retries) **only** `lib/ui/process_ui/widgets_mobile/biometric_capture_control_portrait.dart` `BiometricCaptureControlPortrait` (the landscape variant is commented out at L270-272, although `biometric_capture_control_landscape.dart` and `biometric_scan_middle_block.dart` still exist). The scan button for the selected modality (incl. "Face") is in `lib/ui/process_ui/widgets_mobile/biometric_capture_scan_block_portrait.dart` `_BiometricCaptureScanBlockPortraitState._scanBlock()`; the `onPressed` at **L595-645** does `invokeDiscoverSbi → getBestBiometrics → extractImageValues → incrementBioAttempt → getBioAttempt → ... isScanned = true → _showScanDialogBox(images)`. Per-attribute state (`attemptNo`, `noOfCapturesAllowed`, `qualityPercentage`, `listofImages`, `isScanned`) lives in `lib/provider/biometric_capture_control_provider.dart` (`BiometricAttributeData face`, `customSetterFace`). |
| Face capture widget (operator onboarding) | `lib/ui/onboard/widgets/operator_onboarding_biometrics_capture_control.dart` → `operator_biometrics_capture_view.dart` → `lib/ui/onboard/widgets/operator_biometric_capture_scan_block_view.dart` `_scanBlock()` with the SBI call at **L381-426** using `fieldId = "operatorBiometrics"`. Reached from `lib/ui/onboard/onboarding_page.dart:113` and `home_page.dart:343`. |
| Retry handling | Native counters: `RegistrationDto.getBioAttempt/incrementBioAttempt`, surfaced through `BiometricsApi.incrementBioAttempt/getBioAttempt`; Dart compares `attemptNo < noOfCapturesAllowed` (from `mosip.registration.num_of_face_retries` via `BiometricsApi().getMapValue(...)`, `biometric_capture_control.dart:120-122`). Operator onboarding has retries **disabled** (commented out, `operator_biometric_capture_scan_block_view.dart:398-402`). |
| Capture timeout | `BiometricsApi.getCaptureTimeout()` → `GlobalParamRepository.getCachedIntCaptureTimeout()` → key `mosip.registration.capture_time_out` default 10000 ms (`RegistrationConstants.java:117,142`). |
| Quality SDK | `android/clientmanager/.../util/MatchUtil.java` and `BioSdkProviderFactory.java` load vendor `IBioApiV2` SDKs from `android/clientmanager/src/main/assets/biosdk/*.aar` (dexified by `clientmanager/build.gradle` task `dexifyBiosdkAars`). Used for quality score and operator dedupe (`BIO_SDK_DEDUPE_MATCH`). No liveness anywhere. |

## 3. Operator login and supervisor authentication

| Item | Finding |
|---|---|
| Operator login | `lib/ui/login_page.dart` `LoginPage` → `UsernameComponent` + `PasswordComponent` only → `AuthProvider.authenticateUser(username, password, isConnected)` (`lib/provider/auth_provider.dart:188`) → `AuthResponseApi.login` (`pigeon/auth_response.dart`) → `AuthenticationApi.login` (`api_services/AuthenticationApi.java:200`) → online `doLogin` (Keycloak via `SyncRestService.login`) or `offlineLogin` (password hash via `LoginService.validatePassword`). **No biometric login exists on Android** (the desktop client has fingerprint/iris/face auth; ARC does not). `AuthResponse.isSupervisor/isOperator` roles come from `UserRoleRepository`. |
| Supervisor / packet authentication | Two password-only places: (a) packet approval `lib/ui/approve_packet/widget/authenticate_dialogbox.dart` `AuthenticateDialogBox` (strings `supervisor_auth_heading/subheading`) → `AuthProvider.authenticatePacket` → `PacketAuthApi.authenticate(username, password)` → `PacketAuthenticationApi.authenticate` (`offlineAuthentication` → `LoginService.validatePassword`); (b) end-of-registration review `lib/ui/process_ui/generic_process.dart:142-156 _authenticatePacket` (same provider call), with `lib/ui/post_registration/authentication_page.dart` `AuthenticationPage` as the password UI. |
| Operator onboarding (biometrics → IDA) | `android/clientmanager/.../service/UserOnboardService.java` builds the IDA auth request from `operatorBiometrics` captured via SBI; `BiometricsApi.startOperatorOnboarding / saveOperatorBiometrics`. |
| Consequence for us | There is no existing "face auth" flow to hook into for OPERATOR/SUPERVISOR workflows; the liveness overlay plus a face `rcapture`-free CameraX session must be **added** as an optional second factor next to password (see `integration-android/flutter/lib/liveness/integration_points.md`). |

## 4. Configuration (global params)

| Item | Finding |
|---|---|
| Storage | Room entity `android/clientmanager/.../entity/GlobalParam.java`, DAO `GlobalParamDao`, repository `android/clientmanager/.../repository/GlobalParamRepository.java` with a **static in-memory `Map<String,String> globalParamMap`** merged with local overrides (`LocalConfigDAO`, "permitted" keys) in `refreshConfigurationCache()`. Readers: `getCachedStringGlobalParam(key)`, `getCachedIntegerGlobalParam`, `getCachedBooleanGlobalParam`, plus typed helpers (`getCachedIntCaptureTimeout`, ...). `getGlobalParamsByPattern(prefix)` exists (used for `mosip.biometric.sdk.providers.*`). |
| Sync | `MasterDataServiceImpl.syncGlobalParamsData` (`service/MasterDataServiceImpl.java:356`) fetches `/configs`, decrypts `globalConfiguration` + `registrationConfiguration`, `saveGlobalParams(list)` → cache refresh. Scheduled by `jobs/ConfigDataSyncJob.java`. |
| Dart access | `pigeon/global_config_settings.dart` `GlobalConfigSettingsApi.getRegistrationParams()` (→ `MasterDataService.getRegistrationParams`, a map of selected keys) and the generic `BiometricsApi.getMapValue(key)` (`BiometricsDetailsApi.java:612`, reads `globalParamRepository.getCachedStringGlobalParam(key)`); `GlobalProvider.thresholdValuesMap` (`lib/provider/global_provider.dart:526`). |
| Offline defaults | None in code for face; `getCachedIntegerGlobalParam` returns 0 when a key is absent. Our policy keys (`mosip.registration.liveness.*`) therefore need compiled-in defaults on the native side (`LivenessPolicy.defaults(workflow)`), with `getGlobalParamsByPattern("mosip.registration.liveness")` as the override source. |

## 5. Audit

| Item | Finding |
|---|---|
| Service | SPI `android/clientmanager/.../spi/AuditManagerService.java`; impl `service/AuditManagerServiceImpl.java` writes Room entity `entity/Audit.java` (table `app_audit_log`: event_id, event_name, event_type, action_dtimes, host_name, app_id, app_name, session_user_id, ref_id, ref_id_type, ...). `auditWithArguments(event, moduleId, moduleName, String...)` formats `%s` placeholders in the event description. |
| Enums | `constant/AuditEvent.java` (id, type, name, description) e.g. `BIOMETRIC_CAPTURE("REG-EVT-107")`, `REG_BIO_SCAN("REG-EVT-092","Scan of %s")`, `R_CAPTURE_FAILED("REG-EVT-077")`, `BIO_SDK_DEDUPE_MATCH("REG-EVT-097")`; `constant/Components.java` (`LOGIN REG-MOD-101`, `REGISTRATION REG-MOD-103`, ... up to `CLEAN_UP REG-MOD-110`); `constant/AuditEventType.java` (`USER`, `SYSTEM`). |
| From Dart | `pigeon/audit_response.dart` `AuditResponseApi.audit(id, componentId, arguments)` → `api_services/AuditDetailsApi.java` matches by event id **or** name, then component id; Dart entry `GlobalProvider.performAudit` / `Audit().performAudit(id, componentId, [args])`. |
| Upload | Audit rows are uploaded with packets/sync (`DeleteAuditLogsJob` prunes). Offline queueing is therefore already present. |

## 6. i18n

- `l10n.yaml`: `arb-dir: assets/l10n`, template `app_en.arb`, output `app_localizations.dart`; `flutter: generate: true` in `pubspec.yaml`. Import is `package:flutter_gen/gen_l10n/app_localizations.dart`; usage `AppLocalizations.of(context)!.scan`.
- Locales present: `assets/l10n/app_{en,ar,fr,hi,kn,ta}.arb` (424 lines in `app_en.arb`). ICU select is used for multi-language labels (`biometricsScreenName`, `errors`).
- Keys are flat snake_case identifiers (e.g. `scan`, `rescan`, `attempts_left`, `face`, `supervisor_auth_heading`). ARB keys cannot contain dots, so our shared `liveness.hint.checking`-style ids are mapped to ARB identifiers `liveness_hint_checking` (see `liveness_messages.dart`).
- Native Android string resources (`R.string.global_config_sync_completed`) are also used for toasts in `clientmanager`.

## 7. Models, native libs, SDK levels

| Item | Finding |
|---|---|
| SDK levels | `android/build.gradle` ext: `compileSdkVersion 34`, `targetSdkVersion 34`, **`minSdkVersion 28`**; AGP 8.3.2; Gradle 8.5; Kotlin 1.9.24; JDK 21 (`compileOptions` VERSION_21 in `app` and `clientmanager`), `coreLibraryDesugaringEnabled true` with `desugar_jdk_libs:2.0.4` (so `java.time`/`Optional` in our API jars are fine). Kotlin `jvmTarget` is synced to each module's `sourceCompatibility` (`android/build.gradle:161-170`). |
| ABIs | `abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'` in `app/build.gradle` release block. MediaPipe/TFLite ship arm64-v8a, armeabi-v7a, x86_64 (x86 32-bit is not shipped by MediaPipe tasks-vision; drop `x86` or accept a missing-native crash on that ABI). |
| Existing ML/camera deps | **None**: no `camera`, `tensorflow`, `mediapipe`, `onnx`, `opencv` strings in any `build.gradle` or `pubspec.yaml`. |
| Asset bundling | Flutter assets: `pubspec.yaml` `assets:` lists whole folders (`assets/`, `assets/images/`, `assets/svg/`, `assets/l10n/`). Native: vendor SDK AARs in `clientmanager/src/main/assets/biosdk/` (converted to DEX jars at build time); `keymanager/src/main/assets`, `packetmanager/src/main/assets` hold certificates/templates. Our `.task`/`.tflite` models go into the library module's own `src/main/assets/vivid/models/` (Android merges library assets), flagged `noCompress` for mmap. |
| APK size budget | Unknown from the repo (no size CI check). PLAN 8.3 budget: +25 MB. MediaPipe tasks-vision (~10 MB per ABI) + TFLite (~3 MB) + models (~5 MB) fit only with ABI splits or `arm64-v8a`-only release builds. |
| Build caveats (`AGENTS.md`) | `gradlew` is not committed; D8 can OOM with `-Xmx1536M`; release signing silently falls back to debug keys. |

## 8. Pigeon details that constrain our bridge

- Pigeon 10.x, Java output. Our module can still use `--kotlin_out`; Pigeon supports Java and Kotlin outputs side by side as long as package names differ.
- Host APIs are all `@async` and return through `Result<T>`; the existing code keeps the `Result` in a field until the SBI activity result arrives (`biometricResult`). Our bridge instead returns immediately and pushes state through a `@FlutterApi` (`LivenessFlutterApi.onState`) - the first FlutterApi in the app.
- `MainActivity` is a `FlutterActivity` (so a `LifecycleOwner` for CameraX) and already owns `onActivityResult` for SBI request codes 1-5; we do not need `onActivityResult`.

## 9. Requirement → injection point → change

| Requirement | Injection point (path · class/widget · method) | What we change |
|---|---|---|
| Live frames for liveness on Android (PLAN 3.2 option 1) | New: `vivid-android` `CameraXFrameSource` bound to `MainActivity` lifecycle | Add `CAMERA` permission + `<uses-feature android:name="android.hardware.camera.front" android:required="false"/>` to `android/app/src/main/AndroidManifest.xml`; request runtime permission in Dart (`permission_handler` already in `pubspec.yaml`). |
| Resident face liveness before `rcapture` | `lib/ui/process_ui/widgets_mobile/biometric_capture_scan_block_portrait.dart` · `_BiometricCaptureScanBlockPortraitState._scanBlock()` · scan `onPressed` (L595) | When `biometricAttributeData.title == "Face"` (and for `"Exception"` photo when policy says so): show `LivenessOverlay` (full-screen route) driven by `LivenessController.start(workflow: RESIDENT)`; only on `PASSED` continue with the existing `invokeDiscoverSbi` chain; on `FAILED`/`LOCKED` apply policy (`retry`, exception flow, supervisor). Record `liveness sessionId` for audit. |
| Bind liveness to the signed capture (identity continuity) | `android/app/src/main/java/io/mosip/registration_client/api_services/BiometricsDetailsApi.java` · `parseRCaptureResponse(Bundle)` (L1067) | After `handleRCaptureResponse`, decode the FaceBDIR image (same code as `extractImageValues` L242) and compare its embedding to `LivenessSessions.anchorEmbedding(sessionId)` from the vivid module; reject (`SBIError`-style `BiometricsServiceException`) above the drift threshold, audit `LIVENESS_IDENTITY_MISMATCH`. |
| Operator login liveness (PLAN 8.2) | `lib/ui/login_page.dart` · `_LoginPageState` · after `authProvider.authenticateUser(...)` succeeds (L299) and before navigating (L374) | If `policy(OPERATOR).enabled`: push `LivenessOverlay(workflow: OPERATOR)`; on `PASSED` continue; on `LOCKED` call `AuthResponseApi.logout()` and show `liveness_failure_locked`. Audit `LIVENESS_OPERATOR_*`. Native side: nothing in `AuthenticationApi.java` changes; the gate is Dart-side because Android has no face-auth capture to match against (the SBI `rcapture` of the operator's face plus `UserBiometricRepository` match is a follow-up). |
| Supervisor authentication liveness | `lib/ui/approve_packet/widget/authenticate_dialogbox.dart` · `_AuthenticateDialogBoxState._authenticatePacketPassword()` (L64) and `lib/ui/process_ui/generic_process.dart` · `_authenticatePacket` (L142) | Same pattern as login with `workflow: SUPERVISOR`, after the password check succeeds and before `supervisorReview` / packet creation. |
| Policy from global params | `android/clientmanager/.../repository/GlobalParamRepository.java` · `getGlobalParamsByPattern("mosip.registration.liveness")` | New `LivenessPolicyProvider` in `app` builds the JSON passed to `LivenessHostApi.startSession(workflow, policyJson, assisted)`; defaults from `LivenessPolicy.defaults(workflow)` when keys are absent. Expose via a `getLivenessPolicy(workflow)` method added to `GlobalConfigSettingsApi` (`pigeon/global_config_settings.dart`). |
| Audit | `android/clientmanager/.../constant/AuditEvent.java`, `Components.java`; `AuditDetailsApi` | Add `Components.LIVENESS("REG-MOD-111", "Liveness")` and events `LIVENESS_SESSION_STARTED/PASSED/FAILED/LOCKED/PAD_DETECTED/IDENTITY_MISMATCH/DEVICE_ERROR` (`REG-LIV-0xx`). Native bridge audits directly through `AuditManagerService` (injected into `LivenessPigeonApi` via `HostApiModule`); Dart uses `Audit().performAudit("LIVENESS_...", "REG-MOD-111", [...])` for UI-only events (retry pressed). |
| i18n | `assets/l10n/app_{en,ar,fr,hi,kn,ta}.arb`, `l10n.yaml` | Merge `integration-android/flutter/lib/liveness/l10n/liveness_{en,fr,ar}.arb` keys into the app ARBs (single template file per `l10n.yaml`); `hi/kn/ta` fall back to English until translated. |
| Bridge registration | `MainActivity.configureFlutterEngine`, `HostApiModule`, `pigeon.sh`, `android/settings.gradle`, `android/app/build.gradle` | `include ':vivid-android'`; `implementation project(':vivid-android')`; `LivenessHostApi.setUp(messenger, livenessPigeonApi)`; add the Pigeon line for `pigeons/liveness.dart` with `--kotlin_out`. |
| Performance / low-resource | `vivid-android` `ThermalGovernor`, `FrameSkipper` | No change to ARC; governor lowers target fps and disables rPPG under `PowerManager` thermal status. |

## 10. Unknowns / to verify on a real device

1. Whether any production SBI vendor app on Android shows a camera preview during `.rCapture` (nothing in ARC depends on it).
2. Actual APK size and dex-method impact of MediaPipe tasks-vision + TFLite on top of the existing Jackson/BouncyCastle/Room stack (D8 OOM risk noted in `AGENTS.md`).
3. Whether `MainActivity` (a `FlutterActivity`) survives the vendor SBI activity being on top while a CameraX session is bound; we stop the camera before invoking `rcapture` precisely to avoid two camera clients.
4. MediaPipe facial-transformation-matrix sign conventions for yaw with the front camera (mirrored); we assume subject-left-positive and document the check.
5. The `develop-native` branch name suggests a native (non-Flutter) rewrite may be in progress; the injection points above are for the Flutter line.
