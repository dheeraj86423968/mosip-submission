# Flutter integration points (android-registration-client, `release-1.2.x`)

> **Implemented (24 Oct 2026).** These integration points are now real patches against ARC `release-1.2.x` @
> `240c50d9`: `integration-android/patches/000{1..8}-*.patch`, applied and built by
> `integration-android/apply-to-arc.sh` (results in `docs/testing/android-arc-integration.md`). Where the patches
> differ from the plan below, the patches win:
> - Policy is not read through `GlobalConfigSettingsApi`. The native bridge gets a policy source
>   (`LivenessPolicySource`, global params `mosip.registration.liveness.<workflow>.*`) and Dart asks
>   `LivenessHostApi.policySummary(workflow)`.
> - All call sites use one helper, `lib/liveness/liveness_flow.dart` `runLivenessGate(...)` (patch 0004), not the
>   `runLiveness` sketch below.
> - `generic_process.dart` `_authenticatePacket` is the **operator** re-authenticating before packet creation
>   (`REG_OPERATOR_AUTH_PASSWORD`), so it uses `LivenessWorkflow.operator`; only packet approval is SUPERVISOR.
> - Operator login and both packet-auth dialogs also match the live face against the user's onboarded face
>   (`LivenessHostApi.matchEnrolledFace`), and the resident / onboarding SBI face capture is bound to the
>   preceding session natively (`LivenessCaptureBinder`), not through a new `BiometricsApi` method.

Paths are relative to the registration client repo. Line numbers are from the recon in
`docs/recon/android-registration-client.md` and will drift; the widget/method names are the stable anchors.

## 0. Plumbing (once)

| Step | File | Change |
|---|---|---|
| Pigeon | `pigeon/liveness.dart` (copy of `integration-android/vivid-android/pigeons/liveness.dart`), `pigeon.sh` | Add the `dart run pigeon --input pigeon/liveness.dart --dart_out lib/pigeon/liveness_pigeon.dart --kotlin_out android/vivid-android/src/main/kotlin/io/mosip/vivid/android/bridge/gen/LivenessPigeon.kt --kotlin_package io.mosip.vivid.android.bridge.gen` line. |
| Dart sources | `lib/liveness/` | Copy `liveness_overlay.dart`, `liveness_controller.dart`, `liveness_messages.dart`. |
| i18n | `assets/l10n/app_en.arb`, `app_fr.arb`, `app_ar.arb` (+ `hi/kn/ta` fallback) | Merge the keys from `l10n/liveness_*.arb` (same identifiers; `l10n.yaml` uses a single template per locale). Run `flutter gen-l10n`. |
| Native | `android/settings.gradle`, `android/app/build.gradle`, `MainActivity.java`, `HostApiModule.java` | See `integration-android/README.md`. |
| Permission | `android/app/src/main/AndroidManifest.xml` | `CAMERA` is merged from the library manifest; request at runtime with `permission_handler` (`Permission.camera.request()`) before `LivenessController.start`. |
| Policy | `pigeon/global_config_settings.dart` + `GlobalConfigSettingsApi.java` | Add `@async String getLivenessPolicy(String workflow)` returning JSON built from `GlobalParamRepository.getGlobalParamsByPattern("mosip.registration.liveness." + workflow.toLowerCase())`, `"{}"` when none. |

A single helper keeps the three call sites identical:

```dart
/// Returns true when liveness passed (or is disabled by policy); false when the user gave up.
Future<bool> runLiveness(BuildContext context, LivenessWorkflow wf, {bool assisted = false}) async {
  final policy = await GlobalConfigService().getLivenessPolicy(wf.wireName);
  if (policy.contains('"enabled":false')) return true;
  if (!await Permission.camera.request().isGranted) return false;
  final controller = LivenessController();
  final sessionId = await controller.start(wf, policyJson: policy, assisted: assisted);
  if (sessionId == null) { controller.dispose(); return false; }
  final ok = await Navigator.of(context).push<bool>(MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) => LivenessOverlay(
      controller: controller,
      onPassed: (r) => Navigator.of(context).pop(true),
      onGaveUp: (r) => Navigator.of(context).pop(false),
      onSupervisorOverride: wf == LivenessWorkflow.resident ? () => Navigator.of(context).pop(false) : null,
    ),
  ));
  final result = controller.result;
  await Audit().performAudit(
      ok == true ? 'LIVENESS_PASSED' : 'LIVENESS_FAILED', 'REG-MOD-111',
      [sessionId, result?.decision ?? '', result?.fusedLivenessScore.toStringAsFixed(3) ?? '']);
  controller.dispose();
  return ok == true;
}
```

## 1. Resident face capture (workflow RESIDENT)

- Widget: `lib/ui/process_ui/widgets_mobile/biometric_capture_scan_block_portrait.dart`,
  class `_BiometricCaptureScanBlockPortraitState`, method `_scanBlock()`, the `ElevatedButton.icon`
  `onPressed` (around L595) that today starts with `await BiometricsApi().invokeDiscoverSbi(...)`.
- Change: at the top of that `onPressed`, when `biometricAttributeData.title == "Face"`
  (and, if `mosip.registration.liveness.resident.exception_photo=true`, also `"Exception"`):

  ```dart
  if (biometricAttributeData.title == "Face") {
    final assisted = biometricAttributeData.exceptions.isNotEmpty; // operator marked motor exception
    final live = await runLiveness(context, LivenessWorkflow.resident, assisted: assisted);
    if (!live) { setState(() {}); return; }   // retry counter untouched: liveness has its own
  }
  await BiometricsApi().invokeDiscoverSbi(...);   // unchanged chain
  ```
- Native binding: `BiometricsDetailsApi.parseRCaptureResponse` compares the rcapture face
  embedding with `LivenessPigeonApi.anchorEmbedding(sessionId)`; pass the session id through a
  new `BiometricsApi.setLivenessSession(String sessionId)` host method (or a static holder) before `invokeDiscoverSbi`.
- The landscape widget (`biometric_scan_middle_block.dart`) is dead code in 1.2.x; patch it the
  same way only if it is revived.

## 2. Operator onboarding (workflow RESIDENT policy, operator's own face)

- Widget: `lib/ui/onboard/widgets/operator_biometric_capture_scan_block_view.dart`,
  `_OperatorBiometricCaptureScanBlockViewState._scanBlock()`, `onPressed` around L381
  (`invokeDiscoverSbi("operatorBiometrics", ...)`).
- Same guard as section 1 for `"Face"`; use `LivenessWorkflow.operator` policy (stricter) because
  the captured template becomes the operator's reference biometric.

## 3. Operator login (workflow OPERATOR)

- Widget: `lib/ui/login_page.dart`, `_LoginPageState`, in the method that awaits
  `authProvider.authenticateUser(username, password, isConnected)` (around L299) and before the
  navigation decision `if (authProvider.isOnboarded || authProvider.isDefault)` (around L374).
- Change:

  ```dart
  if (authProvider.isLoggedIn) {
    final live = await runLiveness(context, LivenessWorkflow.operator);
    if (!live) {
      await authProvider.logoutUser();
      _showInSnackBar(LivenessMessages.text(context, 'liveness.failure.locked'));
      return;
    }
  }
  ```
- Android has no biometric login today, so this adds a liveness-only second factor; matching the
  live face against the onboarded operator template (`UserBiometricRepository` + `MatchUtil`) is
  a native follow-up that reuses the anchor embedding.

## 4. Supervisor authentication (workflow SUPERVISOR)

- (a) Packet approval: `lib/ui/approve_packet/widget/authenticate_dialogbox.dart`,
  `_AuthenticateDialogBoxState`, after `_authenticatePacketPassword()` returns true (around L82)
  and before the approve/reject action runs.
- (b) Registration review: `lib/ui/process_ui/generic_process.dart`, `_authenticatePacket(context)`
  (around L142-156): after `authProvider.authenticatePacket(username, password)` succeeds, call
  `runLiveness(context, LivenessWorkflow.supervisor)` and treat `false` as authentication failure.
- Policy default `OnMaxRetries.LOCK_AND_ALERT`: the overlay shows `liveness.failure.locked`, the
  dialog closes, and the audit event `LIVENESS_LOCKED` is written natively.

## 5. Settings / diagnostics (optional)

- `lib/ui/settings/widgets/global_config_settings_tab.dart` already lists registration params;
  the `mosip.registration.liveness.*` keys appear automatically once the server config contains them.
