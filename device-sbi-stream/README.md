# device-sbi-stream

Desktop `FaceFrameSource` for MOSIP SBI (Secure Biometric Interface, formerly MDS) face devices. It finds a
registration face device on `127.0.0.1:4501-4600`, opens its `STREAM /stream` preview and delivers each MJPEG
part as a `PixelFormat.MJPEG` `FaceFrame`. Decoding is left to the engine. Faults are handled as
`DeviceHealth` transitions, with reconnection.

Depends on `device-api`, `jackson-databind` and `slf4j-api`. HTTP is a small raw-socket client: SBI uses custom
verbs, some services answer HTTP/1.0 without framing, and stall detection needs a per-read timeout.

## Usage

```java
// scan 4501-4600, first Ready "Registration" face device
FaceFrameSource src = SbiStreamFrameSource.discoverDefault();

// or pin it down
FaceFrameSource src = new SbiStreamFrameSource(SbiStreamConfig.builder()
        .endpoint(SbiEndpoint.local(4501))       // skip scanning
        .deviceId("FACE-123")                     // match deviceId or deviceCode
        .deviceSubId(0)
        .backoff(250, 5000)                       // reconnect backoff: initial, cap (ms)
        .build());

DeviceCapabilities caps = src.capabilities();     // vendor/model/L0-L1/spec version from discovery
src.start(sink, new StreamOptions(15, 4, 3000, 5)); // fps, ring size, stall timeout ms, reconnect attempts
```

You can also use the building blocks on their own:

- `SbiDiscovery`: parallel port scan with short timeouts, plus `select(devices, deviceId, preferRegistration)`
- `SbiClient`: `discover`, `info` (decodes the JWT payload *without* verifying it), `openStream`, `capture`
- `SbiCaptureHash`: the RCAPTURE hash chain exactly as MockMDS computes it (raw digest bytes, not hex strings)
- `MultipartStreamReader`: tolerant `multipart/x-mixed-replace` parser for any `InputStream`
- `DigitalId`, `SbiDeviceInfo`, `JpegDimensions`

## Behaviour

| Concern | Behaviour |
|---|---|
| Threads | A reader thread owns the socket. A delivery thread calls the sink. They are connected by a ring of `StreamOptions.maxQueuedFrames()` that **drops the oldest frame** when full, so a slow engine never blocks the socket. `framesDropped()` counts drops |
| Capabilities | Vendor and model come from the decoded `digitalId` (`make`/`model`). The certification level comes from `certification`, the spec version from the highest `specVersion` entry. `signsFrames=false` always. SBI does not advertise resolution or fps, so they read 0 until the first frame and then report the observed JPEG size and smoothed fps |
| Stall | No bytes for `stallTimeoutMillis` → `DEGRADED("no data for N ms")`, then `RECONNECTING` |
| Socket reset, EOF, HTTP error | `RECONNECTING("attempt i/N in d ms: cause")` |
| Backoff | `min(cap, initial·2^(i-1)) + jitter(0..25%)`, capped. The attempt counter resets after any frame arrives |
| Give up | After `reconnectAttempts` consecutive failures → `FAILED(...)`, then `onEnd` |
| Service moved | Every reconnect re-runs discovery. A scanned device is looked for again across the range (the service may have moved port). A pinned endpoint is re-probed (the service may now expose a different `deviceId`; streaming a stale id gets SBI error 605 forever) |
| SBI refusals | MockMDS refuses `STREAM` with HTTP 200 and a JSON error body. `SbiClient.openStream` classifies by SBI code: `110/111/607` (not ready, busy) → `CAMERA_UNAVAILABLE`; `100/106/601/604/605/606` → `UNAVAILABLE`; others → `PROTOCOL_ERROR` |
| Stream end | The SBI error a service appends after the last part (MockMDS: `609 Live Stream timeout`) is kept in `MultipartStreamReader.trailer()` and shown in the `RECONNECTING` reason |
| `stop()` | Idempotent. Closes the socket, reports `DISCONNECTED("stopped")`, calls `onEnd` exactly once per start, and sends no callbacks after `onEnd` |
| Multipart | Takes the boundary from Content-Type (quoted or not). For a boundary that already starts with `--`, it accepts both the MockMDS delimiter (`--BoundaryString`) and the RFC 2046 one (`----BoundaryString`). Handles CRLF or bare LF, header lines with or without spaces, and a missing or unparsable Content-Length (it scans to the next delimiter). A Content-Length that is wrong is trusted, and the parser resyncs on the next delimiter. Zero-length parts are skipped and garbage between parts is discarded (`bytesSkipped()`). Chunked transfer encoding and HTTP/1.0 close-delimited bodies are supported |

The stream is unsigned. Only `RCAPTURE` output is signed by the device. Binding the stream to the signed capture
(identity continuity) is the orchestrator's job (PLAN §3.1). `SbiClient.capture` returns the raw JSON for that
purpose and does not verify signatures.

## SBI protocol: what was confirmed

Sources checked on 2026-10-23:

1. MOSIP docs, *MDS Specification* (0.9.5):
   https://docs.mosip.io/1.2.0/id-lifecycle-management/supporting-components/biometrics/mds-specification.md
2. MOSIP docs, *SBISpec* (SBI 1.0 draft):
   https://docs.mosip.io/1.2.0/id-lifecycle-management/supporting-components/biometrics/sbispec.md
3. MOSIP reference mock, `mosip/mosip-mock-services` → `MockMDS/.../SBIConstant.java` and
   `service/SBIServiceResponse.java`, `devicehelper/SBIDeviceHelper.java` (master)

(The URL `docs.mosip.io/1.2.0/biometrics/secure-biometric-interface` from the brief returns 404.)

### Transport — confirmed
- The service binds to **`127.0.0.1`** (the spec says this explicitly, not `localhost`) on a port in **4501–4600**.
- 0.9.5 uses custom verbs: `MOSIPDISC /device`, `MOSIPDINFO /info`, `STREAM /stream`, `RCAPTURE /capture`
  (registration), `CAPTURE /capture` (auth). The MockMDS constants are `"MOSIPDISC /device HTTP"`,
  `"MOSIPDINFO /info HTTP"`, `"STREAM /stream HTTP"`, `"RCAPTURE /capture HTTP"` and `"CAPTURE /capture HTTP"`.
- **Note:** the info verb is `MOSIPDINFO` (with a D) in both the 0.9.5 spec and MockMDS. `MOSIPINFO`, as written
  in the brief and the plan, is wrong. This adapter sends `MOSIPDINFO`; the mock accepts both.
- The SBI 1.0 draft (`sbispec`) uses `POST` on `/device`, `/info`, `/stream`, `/rcapture`. **Not implemented:**
  this adapter speaks 0.9.5 verbs only. Adding a 1.0 fallback is a small change in `SbiVerbs`/`SbiClient`.

### Discovery — confirmed
```http
MOSIPDISC /device HTTP/1.1
Host: 127.0.0.1:4501
Content-Type: application/json

{"type": "Face"}
```
`type` accepts `"Biometric Device"`, `"Finger"`, `"Face"` or `"Iris"`. The response is a JSON **array**:
```json
[{
  "deviceId": "...", "deviceStatus": "Ready", "certification": "L0",
  "serviceVersion": "...", "deviceSubId": ["0"], "callbackId": "http://127.0.0.1:4501/",
  "digitalId": "<base64url of the unsigned digital-id JSON>", "deviceCode": "<serial>",
  "specVersion": ["0.9.5"], "purpose": "Registration",
  "error": {"errorCode": "0", "errorInfo": "Success"}
}]
```
- `deviceStatus` is one of `Ready`, `Busy`, `Not Ready`, `Not Registered`. `certification` is `L0` or `L1`.
  `purpose` is `Auth` or `Registration` (empty when unregistered).
- MockMDS encodes the discovery `digitalId` as **base64url JSON (not a JWT)**. It sets error `110` for Not
  Ready, `111` for Busy and `100` for Not Registered, in which case it blanks `deviceId`/`deviceCode`/`purpose`.
- The 1.0 draft lists `serialNo` and `jwk` and omits `deviceId`/`deviceCode`. `SbiDeviceInfo` falls back to
  `deviceCode` when `deviceId` is missing.

### Device info — confirmed
```http
MOSIPDINFO /info HTTP/1.1
Host: 127.0.0.1:4501
```
The response is `[{"deviceInfo": "<JWT signed with the device key>", "error": {...}}]`. The decoded payload has
`deviceStatus, deviceId, firmware, certification, serviceVersion, deviceSubId, callbackId, digitalId (signed),
deviceCode, env, purpose, specVersion`. Unregistered devices return the payload unsigned (base64url). The spec's
own examples disagree on whether `deviceInfo` is an object or a string; `SbiClient.info` accepts either.

### Stream — confirmed request, partly confirmed response
```http
STREAM /stream HTTP/1.1
Host: 127.0.0.1:4501
Content-Type: application/json

{"deviceId": "<from discovery>", "deviceSubId": 0, "timeout": 300000}
```
- `timeout` is in milliseconds and optional (spec default: 5 minutes). Streaming is available for
  **registration** devices only, at **≥ 3 fps M-JPEG**. The spec gives error `202` "No Device Connected." as
  an example.
- The spec describes the response only as an "HTTP chunk of frames". **It does not specify a Content-Type or
  framing.** The framing below was confirmed from MockMDS `SBIServiceResponse`, not from the spec:
  ```
  HTTP/1.0 200 OK
  Access-Control-Allow-Origin:*
  Connection: close
  Content-Type: multipart/x-mixed-replace; boundary=--BoundaryString

  --BoundaryString
  Access-Control-Allow-Origin:*
  Content-Type:image/jpeg
  Content-Length:<n>

  <jpeg bytes>\r\n\r\n
  ```
  Note that the advertised boundary already contains `--` and the delimiter on the wire is `--BoundaryString`,
  not `----BoundaryString`. The parser handles both forms; see `MultipartStreamReaderTest.mockMdsFramingWithDashesInBoundaryAndNoSpaces`.
- **Confirmed by running MockMDS v1.2.0.2** (2026-10-24, `docs/testing/interop-mockmds.md`): this framing, a
  490x360 JPEG repeated at about 32 fps, and a second HTTP response with error `609` appended after the last part
  when `timeout` expires.
- **Unconfirmed:** what certified vendor SBIs send. Real services may use other boundaries, chunked encoding
  or no Content-Length. The parser is built to tolerate all of these, but it has only been tested against the
  mock and hand-built byte streams.

### Registration capture — confirmed shape (used by the mock, not by this adapter)
```http
RCAPTURE /capture HTTP/1.1
Host: 127.0.0.1:4501
Content-Type: application/json

{"env": "Developer", "purpose": "Registration", "specVersion": "0.9.5", "timeout": 10000,
 "captureTime": "<ISO-8601>", "transactionId": "<id>",
 "bio": [{"type": "Face", "count": 1, "bioSubType": [], "exception": [], "requestedScore": 40,
          "deviceId": "<id>", "deviceSubId": 0, "previousHash": ""}],
 "customOpts": null}
```
The response is `{"biometrics": [{"specVersion": "0.9.5", "data": "<JWT>", "hash": "<HEX SHA-256>", "error": {...}}]}`.
The `data` payload has `digitalId, bioType, deviceCode, deviceServiceVersion, bioSubType, purpose, env,
bioValue (base64url ISO data), transactionId, timestamp, requestedScore, qualityScore`.
- **Confirmed against MockMDS:** the hash chaining is `HEX(SHA-256(prev || SHA-256(bioValue)))` over raw digest
  bytes, with `prev = SHA-256(empty)` for the first entry and `hexDecode(previousHash)` after that
  (`SbiCaptureHash`, checked on a recorded response). `device-mock` hashes the hex strings instead and gives a
  different value. It has not been checked against a certified device.
- **Unconfirmed:** the full 0.9.5 error-code table for `STREAM`. The table seen lists 0, 100–112 and 5xx
  (custom), and `202` appears only as an example in the stream section.

## Tests

`mvn -q -f device-sbi-stream/pom.xml install` runs 51 tests. 38 always run, including 9 for the signed preview stream (`SignedPreviewTest`); 13 are opt-in against a live MockMDS:

- **Multipart unit tests on hand-built byte streams:** standard framing; MockMDS framing; bare LF; missing,
  too-small and absurd Content-Length; zero-length parts; preamble and garbage; truncation → `EOFException`;
  300 KB parts across 1 KB buffers. Also covered: chunked decoding, JPEG SOF parsing, and ring drop-oldest
  with health priority.
- **Integration tests against `MockSbiServer`** (test-scoped dependency on `device-mock`): happy path with
  capabilities from discovery; MockMDS framing; a port-range scan and select by deviceId; `/info` JWT decode;
  stall → `DEGRADED` → `RECONNECTING` → `CONNECTED` with frames recovering; permanent disconnect → 2 ×
  `RECONNECTING` → `FAILED` with a single `onEnd`; device absent → `DeviceException(UNAVAILABLE)` and
  `CONNECTING → RECONNECTING → FAILED`.
- **`SbiStreamConformanceTest`:** testkit's `FrameSourceConformanceTest` against `MockSbiServer` in MockMDS framing.
- **`MockMdsFixtureTest`:** responses recorded from MOSIP's MockMDS (`src/test/resources/mockmds`), replayed by
  `RecordedSbiServer`. Each interoperability fix has a test here.
- **Opt-in, `-Dvivid.interop.mockmds=<port>`:** `MockMdsInteropTest` (discovery scan, info, stream, timeout
  recovery, busy refusal, RCAPTURE plus hash chain) and `MockMdsConformanceTest` (the contract suite with the
  adapter scanning 4501-4600). `tools/interop-mockmds.sh` clones, builds and starts MockMDS and runs both.
