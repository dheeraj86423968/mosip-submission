# device-vendor-sdk

Template adapter for face cameras that ship their **own SDK** instead of an SBI service. A vendor implements one
small interface, `VendorSdk`, and gets a complete `device-api` `FaceFrameSource` that the Desktop and Android
Registration Client integrations already consume. Threads, buffering, stall detection, reconnect and health
reporting are done once, in `AbstractFrameSourceAdapter`, and checked by `testkit`'s conformance suite.

Main code compiles to Java 11 bytecode (like every module that ships inside a client) and depends only on
`device-api` and `slf4j-api`.

| Class | Role |
|---|---|
| `VendorSdk` | **You implement this.** `open()`, `startPreview(listener)`, `stopPreview()`, `close()`, optional `describe()` |
| `VendorFrame` | One preview frame: size, `PixelFormat`, pixels, optional depth / IR planes. `copyOf(...)` for SDK-owned buffers |
| `VendorDeviceInfo` | What the device is (vendor, model, serial, L0/L1, resolution, fps, formats, depth/IR); maps to `DeviceCapabilities` |
| `VendorSdkException` | Wraps SDK failures, keeps the SDK's native error code |
| `VendorSdkFrameSource` | The `FaceFrameSource` you hand to the client: `new VendorSdkFrameSource("acme-face", new AcmeSdk())` |
| `AbstractFrameSourceAdapter` | Generic base: delivery thread, drop-oldest ring, stall watchdog, reconnect with backoff, health, `onEnd` exactly once. Usable directly for other device APIs (three hooks: `connect`, `startFrames`, `disconnect`) |
| `FrameRing` | The drop-oldest frame ring with priority health events (public, reusable) |

## What the adapter guarantees

| Concern | Behaviour |
|---|---|
| Threads | Your SDK calls the listener on its own thread. The adapter never blocks that thread: frames go into a ring of `StreamOptions.maxQueuedFrames()` that drops the **oldest** frame when full (`framesDropped()`). A delivery thread calls the sink. A supervisor thread opens, watches and reopens the device. The adapter never calls `VendorSdk` methods concurrently |
| Frames | Sequence numbers are assigned by the adapter and strictly increase across reconnects. Timestamps are `System.nanoTime()` on arrival and never decrease. Frames arriving faster than `StreamOptions.targetFps()` are skipped (`framesDecimated()`; `targetFps` 0 = no limit). Empty or malformed frames are ignored |
| Health | `CONNECTING` on start → `CONNECTED` on the first frame of each connection. No frame for `stallTimeoutMillis` → `DEGRADED` → `RECONNECTING`. `listener.onError` or an exception from `open`/`startPreview` → `RECONNECTING`. `reconnectAttempts` consecutive failures → `FAILED` and `onEnd`. `stop()` → `DISCONNECTED("stopped")` and `onEnd`. The sink receives health events in order, on the delivery thread |
| Reconnect | Backoff `min(max, initial·2^(n-1))` plus up to 25 % jitter (default 250 ms doubling to 4 s, as in `docs/design/02-frame-streaming.md`). The counter resets once a connection delivers a frame. Frames from an abandoned connection are dropped |
| Capabilities | Before `start()`: `VendorSdk.describe()` (default: `open()` then `close()`). While streaming: what the last `open()` returned. No device: `DeviceException(UNAVAILABLE)` |
| Lifecycle | `start` while running is ignored, `stop` is idempotent, `close()` = `stop()`, `onEnd` fires exactly once per `start`, nothing reaches the sink after `onEnd` |

## Step-by-step guide for vendors

1. **Add the dependency.** Your adapter module depends on `io.mosip.vivid:device-vendor-sdk` (and through it on
   `device-api`). Compile your main code for Java 11 (`maven.compiler.release` 11): the Desktop client 1.2.0.x runs
   on Java 11 and Android's D8 needs Java 11 bytecode.

2. **Describe the device.** In `open()`, open the camera through your SDK and return a `VendorDeviceInfo`:
   ```java
   return VendorDeviceInfo.builder("Acme Biometrics", "FaceCam 3000")
           .serial(nativeSerial()).certification(DeviceCapabilities.CertificationLevel.L1)
           .resolution(640, 480).fps(30).formats(EnumSet.of(PixelFormat.NV21))
           .hasDepth(false).hasIr(true)          // true only if frames carry the plane
           .build();
   ```
   Report the format you actually deliver. The engines accept `RGB888`, `BGR888`, `NV21`, `YUV420`, `GRAY8`,
   `GRAY16` and `MJPEG`. Delivering the camera's native format avoids a conversion in your callback.
   Set `signsFrames(true)` only if each frame carries a signature the client can verify. No SDK does this today;
   see the signed-preview proposal in `docs/design/02-frame-streaming.md` §6.

3. **Stream frames.** In `startPreview(listener)`, register your SDK's preview callback and return immediately.
   In the callback, wrap each frame and pass it on:
   ```java
   sdk.setPreviewCallback((buf, w, h) -> listener.onFrame(VendorFrame.copyOf(w, h, PixelFormat.NV21, buf)));
   ```
   Use `VendorFrame.copyOf` when the SDK reuses its buffer after the callback returns, which most native SDKs do.
   Use `VendorFrame.of` only for a buffer the SDK hands over for good. Depth and IR go in through `withDepth` /
   `withIr` (GRAY16 depth in millimetres, or as configured in `vivid.engine.depth.*`).
   If your SDK is **pull-based** (`grabFrame(timeoutMs)`), start a daemon thread here that loops
   `grabFrame` → `listener.onFrame` until `stopPreview()` clears a flag, then join it in `stopPreview()`.

4. **Report failures, don't hide them.** Call `listener.onError(new VendorSdkException("...", nativeCode, cause))`
   for connection-level errors (unplugged, USB reset, driver error). Throw `VendorSdkException` from `open` or
   `startPreview` if they fail. The adapter disconnects, backs off and reopens, and reports every step as
   `DeviceHealth` that the client turns into "Reconnecting to device". Don't retry inside the SDK wrapper: the
   policy belongs to the adapter, so every device behaves the same way. A camera that stops sending frames
   without an error is caught by the stall watchdog.

5. **Release cleanly.** `stopPreview()` must stop callbacks before it returns. `close()` releases the device.
   Both must be idempotent and must not throw: the adapter calls them after failures too, and may call them
   twice.

6. **Wire it in.** Pass `new VendorSdkFrameSource("acme-face-sdk", new AcmeVendorSdk(...))` wherever a
   `FaceFrameSource` is taken:
   - Desktop: `LivenessGate.verify(workflow, source, ui)` in `integration-desktop`.
   - Android: `CoreBindings.bridge(source, session, options)` in `integration-android`, in place of
     `CameraXFrameSource`.
   The adapter id appears in audit events, so keep it stable.

7. **Prove it.** Add this test to your module. It must pass unchanged:
   ```java
   class AcmeConformanceTest extends FrameSourceConformanceTest {
       @Override protected FaceFrameSource createSource() {
           return new VendorSdkFrameSource("acme-face-sdk", new AcmeVendorSdk(...));
       }
   }
   ```
   (`io.mosip.vivid:testkit`, test scope.) Then run your camera through `testkit`'s scenario runner and the corpus
   scorer (`testkit/README.md`) to measure APCER/BPCER with your sensor. A device that passes conformance can
   still be a weak PAD sensor.

8. **Remember what the stream does not prove.** Preview frames from an SDK are unsigned, like SBI `/stream`.
   The final biometric must still come from the signed SBI capture (`RCAPTURE`), and the orchestrator's identity
   continuity check binds the two (`docs/design/02-frame-streaming.md` §5).

## Example: the fake SDK used in the tests

`src/test/java/.../FakeVendorSdk.java` is a complete `VendorSdk` that behaves like a callback-style camera SDK.
It produces RGB888 gradient frames on its own thread and reuses one buffer, so the adapter must copy. It can
inject faults: failing `open()` N times, an error after N frames, or silence after N frames. Read it as a worked
example.

## Reuse of device-sbi-stream code

`device-sbi-stream` contains the same ring and reconnect logic, but it cannot be reused as it stands:

- `DeliveryQueue` is package-private.
- The reconnect loop is fused with the SBI socket and the rediscovery logic inside `SbiStreamFrameSource.Session`.
- Depending on `device-sbi-stream` would pull Jackson and an HTTP client into every vendor adapter.

So this module has the generic version:

- `FrameRing` is `DeliveryQueue` made public.
- `AbstractFrameSourceAdapter` is the reconnect, stall and lifecycle logic behind three hooks.

`device-sbi-stream` was **not** migrated onto it, so as not to destabilise a tested adapter before the submission
deadline. That leaves about 100 lines of ring code duplicated. The follow-up is to move `FrameRing` into
`device-api`, which is frozen for this release, or to make `SbiStreamFrameSource` extend
`AbstractFrameSourceAdapter`, with `connect` = discovery and `startFrames` = a reader thread over
`STREAM /stream`.

## Tests

`mvn -pl device-vendor-sdk test` runs 11 tests:

- `VendorSdkConformanceTest`: testkit's `FrameSourceConformanceTest`, all six contract tests.
- `VendorSdkFrameSourceTest`:
  - capabilities come from the SDK, and frames are copied out of the SDK's reused buffer;
  - an SDK error leads to `RECONNECTING` → `CONNECTED`, with sequence numbers still increasing;
  - a stall goes `DEGRADED` → `RECONNECTING` → `CONNECTED`;
  - `open()` failures are retried and then `FAILED` with one `onEnd`, and `capabilities()` throws `UNAVAILABLE`;
  - a slow sink drops the oldest frames, and `targetFps` decimates.

This is a template. It has not been run against a real vendor SDK.
