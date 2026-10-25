# PR draft: face liveness and PAD in the Desktop Registration Client

**Repository:** `mosip/registration-client`, base `release-1.2.0.x` (patches made against `c1cc4e7`)
**Patches:** `integration-desktop/src/mosip/patches/0001..0007`, applied by `integration-desktop/src/mosip/compile-against-client.sh`

## Summary

This PR adds hybrid passive and active face liveness with presentation attack detection to three places: resident
face capture, operator login, and supervisor and operator biometric authentication. Liveness runs on the SBI
`/stream` preview before the existing `RCAPTURE`. The signed capture is then matched to the face that passed
liveness. Everything runs on the client, so behaviour is the same online and offline.

## Changes

| Patch | Change |
|---|---|
| 0001 | Spring component scan for `io.mosip.vivid.desktop.mosip` |
| 0002 | Resident face capture is gated on liveness in `GenericBiometricsController.scan()` |
| 0003 | Operator and supervisor biometric authentication is gated on liveness |
| 0004 | Login face authentication: `FaceAuthLivenessGuard` interface in registration-services, an optional bean |
| 0005 | Audit component `LIVENESS` and its events |
| 0006 | `mosip.registration.liveness.*` configuration keys and UI messages |
| 0007 | Dependencies: the vivid jars and ONNX Runtime, with the client's slf4j kept |

## Compatibility

- **Java:** the vivid modules compile to Java 11 bytecode and load on the client's Zulu 11 runtime.
- **JavaFX:** the overlay uses only JavaFX 11 APIs.
- **Default:** `mosip.registration.liveness.enabled=false` keeps today's behaviour exactly. When enabled with the
  engine or models missing, face capture fails closed and the rest of the client keeps working.

## Testing

- `javac` succeeds on both client modules.
- The client's existing tests for the touched services pass: 59 run, 0 failures.
- The vivid modules carry 500+ unit and end-to-end tests.
- The client was not started with a database and a real device. That needs a MOSIP sandbox.

## Reviewer notes

The decision logic is a separate library, `mosip-vivid`, MPL-2.0, model-checked with TLA+. This PR contains only
the integration. Engines only produce scores and the orchestrator makes every decision, so an adopter can swap in a
vendor engine through the `LivenessEngine` SPI.
