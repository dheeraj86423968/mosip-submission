# PR draft: face liveness, PAD and operator face matching in the Android Registration Client

**Repository:** `mosip/android-registration-client`, base `release-1.2.x` (patches made against `240c50d`)
**Patches:** `integration-android/patches/0001..0008`, applied by `integration-android/apply-to-arc.sh`

## Summary

This PR brings the Desktop liveness behaviour to Android: CameraX preview, an on-device MediaPipe/TFLite engine, the
same orchestrator through a Pigeon bridge, and a Flutter overlay. It also gives operator login a real face check by
matching the live face against the operator's enrolled ISO 19794-5 face.

## Changes

| Patch | Change |
|---|---|
| 0001 | Audit component `LIVENESS` (REG-MOD-111) and `LIVENESS_*` events |
| 0002 | Native wiring: `:vivid-android` module, Pigeon host API in `MainActivity`, Dagger provider, CAMERA permission, policy from global params |
| 0003 | l10n: 66 keys in en, fr and ar |
| 0004 | `runLivenessGate` helper shared by every call site |
| 0005 | Resident face capture gated; the stored face must match the face that passed liveness |
| 0006 | Operator onboarding gated, plus a fix for an existing bug (below) |
| 0007 | Operator login: liveness, then a match against the enrolled face |
| 0008 | Supervisor packet approval and operator packet authentication |

## Existing bug fixed in 0006

Nothing writes the `user_id` preference, so every operator's onboarding templates are saved under an empty user id.
Each new onboarding deletes the previous operator's templates. The patch falls back to `preferred_username`.

## Testing

| Check | Result |
|---|---|
| `flutter analyze` | The same 146 existing issues before and after; none added |
| Existing Flutter tests | Unchanged |
| New tests | 5 Dart, 28 Kotlin JVM |
| `flutter build apk --debug` | Succeeds |

Not run on a device. Thresholds, including face match at 0.50 cosine, need calibration on real hardware.
Hindi, Kannada and Tamil strings fall back to English until translated.
