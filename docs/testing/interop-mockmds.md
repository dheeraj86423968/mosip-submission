# Interoperability with MOSIP's reference mock SBI (MockMDS)

Run date: 2026-10-24. This run tested `device-sbi-stream` against MOSIP's own mock SBI rather than our
`device-mock`.

## What was run

| Item | Value |
|---|---|
| Reference implementation | `github.com/mosip/mosip-mock-services`, module `MockMDS`, tag **`v1.2.0.2`** (commit `5084570`), the newest tag on the 1.2.0.x line. Its `pom.xml` version is `1.2.0.2`, and every device reports `specVersion ["0.9.5"]` |
| Build | Shallow clone, `mvn package` with **JDK 11** (Spring Boot 2.0.2 parent) into an isolated Maven repository. Javadoc, GPG, git-commit-id and source plugins were skipped. The clone and the Maven cache were deleted afterwards |
| Started as | `TestMockSBI "mosip.mock.sbi.device.purpose=Registration" "mosip.mock.sbi.biometric.type=Biometric Device"` (what its `run.sh` does): one registration Finger (slap), Face and Iris (double) device behind a single port |
| Our side | `SbiStreamFrameSource`, `SbiClient`, `SbiDiscovery` from `device-sbi-stream`, plus `testkit`'s `FrameSourceConformanceTest` |
| Reproduce | `tools/interop-mockmds.sh` (add `--record DIR` to capture raw responses, `--keep` to keep the clone) |
| Tests | `MockMdsInteropTest` (7 tests) and `MockMdsConformanceTest` (6 contract tests). Both are opt-in through `-Dvivid.interop.mockmds=<port>` and skipped otherwise. `MockMdsFixtureTest` (6 tests) replays the recorded responses and always runs |

## Result

All 13 opt-in tests passed against the live MockMDS. That includes the full frame-source conformance suite with
the adapter scanning the standard 4501-4600 range, exactly as in production. Final run, after the fixes below:
`MockMdsInteropTest` 7/7, `MockMdsConformanceTest` 6/6.

| Verb | Works with our adapter | Notes |
|---|---|---|
| `MOSIPDISC /device` | yes | Scan of 4501-4600 finds the face device on 4501 in under a second. `{"type":"Face"}` returns only the face device. `"Biometric Device"` returns all three |
| `MOSIPDINFO /info` | yes | Signed JWS per device, decoded without verification |
| `STREAM /stream` (Face) | **yes**: MockMDS streams face frames | MJPEG, 490x360, about 32 fps observed, one still image repeated |
| `RCAPTURE /capture` (Face) | yes, through `SbiClient.capture` | Signed `data`, ISO 19794-5 `bioValue`, hash chain verified byte for byte. `SbiStreamFrameSource` itself does not use RCAPTURE: the Registration Client takes the signed capture |
| `CAPTURE /capture` (auth) | not tested | Needs MockMDS started with `purpose=Auth`, which disables `STREAM` (error 601) |

## Wire details observed (what differs from a textbook HTTP service)

### Port and discovery
- MockMDS probes 4501..4600 by trying to *connect* to each port, then binds the first port where nothing answers.
  It binds to `127.0.0.1` (`server.serveripaddress`). With our `device-mock` already on 4501 it moves to 4502, and
  our scan finds it there.
- One port serves every modality. `MOSIPDISC` with `type` `"Face"` returns one entry, `"Biometric Device"` returns
  three (Finger id `2`, Face id `3`, Iris id `1`). A missing or unknown `type` gives error `502`.
  `MOSIPDINFO` is **not** filtered: it always returns all three devices. `SbiDiscovery` keeps only `Face`
  entries (by the decoded `digitalId.type`).
- Discovery `digitalId` is unsigned base64url JSON. Inside `MOSIPDINFO` and `RCAPTURE` payloads it is a JWS
  with an `x5c` header. `deviceSubId` is an array of **strings** (`["0"]`), and `deviceId` is a string (`"3"`).
- While a stream is open, discovery reports the face device as `"deviceStatus":"Busy"`.
- Request parsing is position-based. MockMDS reads `Content-Length` as `line.substring(16)`, so it needs
  exactly `Content-Length: ` with one space; `Content-Length:15` breaks the header loop. It concatenates all
  request lines and dispatches on substrings such as `"STREAM /stream HTTP"`, and it reads the body with a single
  `Reader.read(char[], 0, len)`. Our client sends `Content-Length: N` and now writes headers and body in one
  `write` (hardening; a split body was never observed).

### Responses
- JSON responses: `HTTP/1.1 200 OK`, `Content-Length` = character count, `Connection: close`, then the JSON
  **followed by `\r\n\r\n` beyond `Content-Length`**. Errors from the worker itself use `HTTP/1.1 405 OK`.
- **Refusals come back as HTTP 200 with an SBI error body**, not as an HTTP error:
  `{"errorCode":"111","errorInfo":"Device is busy"}`, `605` (unknown deviceId), `601` (auth device), `609`
  (stream timeout), and so on.

### Stream
```
HTTP/1.0 200 OK
Server: http://127.0.0.1:4501
Access-Control-Allow-Origin:*
Connection: close
Max-Age: 0
Expires: 0
Cache-Control: no-cache, private
Pragma: no-cache
Content-Type: multipart/x-mixed-replace; boundary=--BoundaryString

--BoundaryString
Access-Control-Allow-Origin:*
Content-Type:image/jpeg
Content-Length:39543

<JPEG, JFIF 1.01, baseline, 490x360, 3 components>\r\n\r\n
--BoundaryString
...
```
- HTTP/1.0, with no transfer framing: the body runs until the connection closes.
- The boundary parameter already contains the dashes (`--BoundaryString`), and the delimiter on the wire is
  `--BoundaryString`, not the RFC 2046 `----BoundaryString`. Part headers have no space after the colon, and
  each JPEG is followed by `\r\n\r\n`.
- Frame content: the single file `Biometric Devices/Face/Stream Image/0.jpeg`, a photograph of a person with
  quality overlays, sent unchanged about every 30 ms (`Thread.sleep(30)` plus the configured delay). We measured
  about 32 fps and 48 parts in 1.5 s. Every frame is byte-identical, so the stream exercises transport only: it
  is a static image, and from a PAD point of view it is a replay.
- `timeout` (ms) in the request is honoured. When it expires, MockMDS appends a **complete second HTTP response**
  to the same socket after the last part (`HTTP/1.1 200 OK ... {"errorCode":"609","errorInfo":"Live Stream
  timeout"}`) and closes. With no `timeout`, it streams until the client goes away.
- The device is `Busy` while streaming. A second `STREAM` gets `111`. When the client closes the socket, MockMDS
  only notices on its next frame write (within about 30 ms) and then sets the device back to `Ready`.

### RCAPTURE
- The response is `{"biometrics":[{"specVersion":"0.9.5","data":"<JWS>","hash":"<HEX>","error":{...}}]}`. The
  `data` payload has `digitalId` (JWS), `bioType` `Face`, `bioValue` (base64url ISO 19794-5 record starting `FAC\0`,
  67 087 bytes), `transactionId`, `timestamp`, `requestedScore`, `qualityScore` (`"44"`, from
  `mosip.mock.sbi.quality.score`).
- It works with or without an open stream.
- **Hash chain**: `hash = HEX(SHA-256(prev ‖ SHA-256(bioValue)))` over **raw digest bytes**. `prev` is
  `SHA-256(empty)` when `previousHash` is empty, otherwise `hexDecode(previousHash)`. This matches the recorded
  response byte for byte. `SbiCaptureHash` implements it.
  **`device-mock` differs**: it hashes the hex *strings* (`SHA-256(previousHash + hex(SHA-256(bioValue)))`), which
  gives a different value for the same input. `device-mock` is outside this change set. Its owner should switch it
  to `SbiCaptureHash` semantics so that the mock's captures validate like MockMDS's.

## Interoperability defects found and fixed

| # | Defect | Effect against MockMDS | Fix | Reproduced by (recorded fixtures) |
|---|---|---|---|---|
| 1 | With a pinned endpoint (`SbiStreamConfig.endpoint(...)`), a reconnect reused the cached `deviceId`. The design (`02-frame-streaming.md` §2.1) says a reconnect re-reads the device | If MockMDS restarts with another configuration, or another SBI service takes the port, every `STREAM` gets `605` until `FAILED` | Every reconnect re-runs discovery, for pinned endpoints too (`SbiStreamFrameSource`) | `MockMdsFixtureTest.pinnedEndpointRediscoversWhenTheServiceComesBackWithAnotherDeviceId` fails before the fix (FAILED after 3 attempts) and passes after it |
| 2 | `STREAM` refusals returned as HTTP 200 + JSON were all classified `PROTOCOL_ERROR` | "Device busy" (`111`) looked like a protocol bug. The `CAMERA_UNAVAILABLE` / `UNAVAILABLE` distinction in the fault table of `02-frame-streaming.md` §2.4 was lost | `SbiClient.openStream` maps the SBI code: `110/111/607` → `CAMERA_UNAVAILABLE`, `100/106/601/604/605/606` → `UNAVAILABLE`, others → `PROTOCOL_ERROR` | `MockMdsFixtureTest.streamRefusalsInHttp200JsonBodiesAreClassifiedBySbiCode` (recorded `111` and `605` bodies) |
| 3 | The SBI error MockMDS appends after a timed-out stream was discarded as garbage | Health said only "stream ended by device" | `MultipartStreamReader.trailer()` keeps up to 8 KiB after the last part, and the reconnect reason now reads `stream ended by device: SBI error 609 Live Stream timeout` | `MockMdsFixtureTest.recordedStreamYieldsJpegPartsAndThe609Trailer` and the pinned-endpoint test |
| 4 | Found while checking MockMDS framing: a boundary that itself starts with `--` was matched **only** in the MockMDS form | An RFC 2046-compliant service advertising `boundary=--x` and writing `----x` would have yielded no frames | The reader accepts both forms and locks onto whichever appears first | `MockMdsFixtureTest.rfcDelimiterIsAcceptedWhenTheBoundaryItselfStartsWithDashes` |

Hardening with no observed failure: request headers and body are now sent in one write, because MockMDS reads the
body with a single `read` call.

Already compatible before this run (the adapter had been written from MockMDS's source): the `MOSIPDINFO` spelling,
the `--BoundaryString` delimiter, part headers without spaces, the HTTP/1.0 close-delimited body, string
`deviceSubId`s, unsigned discovery `digitalId`s, and the trailing bytes after JSON bodies.

## Behaviour worth knowing (not defects)

- **One stream consumer per device.** If the Registration Client itself holds the MockMDS face stream, our adapter
  gets `111` and keeps retrying with backoff, now classified as `CAMERA_UNAVAILABLE`. The Desktop integration
  must therefore either own the stream or tap the client's (`integration-desktop`'s `MjpegInputStreamFrameSource`).
- **Restart after stop.** Because MockMDS releases the device only on its next write, a `stop()` followed at once
  by `start()` can hit `111` once. The adapter recovers on the first backoff (100-250 ms). The conformance
  restart test passes with no delay inserted between cycles.
- **Periodic stream end.** The adapter asks for `timeout` = 5 min (`streamRequestTimeoutMs`). MockMDS then ends
  the stream with `609`, and the adapter reports one `RECONNECTING` and reopens. A liveness session is far
  shorter than this.
- **PAD on MockMDS frames is meaningless.** The stream is one repeated photograph. It tests transport and timing,
  not liveness.

## Recorded fixtures

`device-sbi-stream/src/test/resources/mockmds/` holds raw responses captured from this run with
`tools/interop-mockmds.sh --record`. MockMDS is MPL-2.0, like this project.

| File | Content |
|---|---|
| `disc-face.http`, `disc-all.http` | `MOSIPDISC` for `Face` and `Biometric Device` |
| `info.http` | `MOSIPDINFO`, all three devices, signed |
| `stream-timeout.http` | `STREAM` headers, the first two parts, and the `609` trailer exactly as sent (the 46 middle parts are cut out to keep the fixture at 80 KB) |
| `stream-busy.http`, `stream-baddevice.http` | `111` and `605` refusals |
| `rcapture-face.http` | `RCAPTURE` for Face (125 KB, MockMDS's mock certificate and sample face) |
