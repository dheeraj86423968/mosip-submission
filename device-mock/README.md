# device-mock

A mock L0/L1 face capture device for MOSIP Vivid (bonus task: *mock L0/L1 device*). With it, everything from
unit tests to the Attack Lab demo runs without camera hardware. It comes in two forms that share one
scenario engine:

| Form | Class | Use it for |
|---|---|---|
| In-process `FaceFrameSource` | `io.mosip.vivid.device.mock.MockFaceFrameSource` | Unit and integration tests, orchestrator development, benchmarks |
| Mock SBI HTTP service | `io.mosip.vivid.device.mock.sbi.MockSbiServer` / `MockSbiMain` | Exercising `device-sbi-stream`, the Desktop Registration Client and Attack Lab end to end |

Depends only on `device-api`, `jackson-databind` and `slf4j-api`. It renders frames with `java.awt` in
headless mode and serves HTTP with the JDK's `com.sun.net.httpserver`.

## Scenarios

A scenario is JSON: an ordered list of segments that play back to back, optionally looping.

```json
{
  "name": "live-blink-smile-turn",
  "width": 640, "height": 480, "loop": true,
  "segments": [{
    "source": "synthetic-face",              // or "blank", "noise", or a directory of JPEG/PNG frames
    "durationMs": 16000, "fps": 15,
    "label": "LIVE",                          // LIVE | PRINT | SCREEN_REPLAY | VIDEO_REPLAY | MASK | NONE
    "faceCount": 1,                           // 0 = empty scene, 2 = two subjects
    "overlay": "NONE",                        // NONE | PRINT_TEXTURE | MOIRE_GRID
    "timelineLoops": false,                   // true = replayed recording that ignores prompts
    "timeline": [
      {"action": "BLINK", "atMs": 1200},
      {"action": "YAW", "fromMs": 7500, "toMs": 9000, "degrees": 25},
      {"action": "SMILE", "fromMs": 2500, "toMs": 4000, "intensity": 1.0}
    ],
    "faults": [{"atMs": 3000, "kind": "DISCONNECT"}, {"atMs": 5500, "kind": "RECONNECT"}]
  }]
}
```

Timeline actions: `BLINK`, `SMILE`, `OPEN_MOUTH`, `RAISE_EYEBROWS`, `YAW` (+ = subject's left, matching
`FaceObservation.yaw`), `PITCH` (+ = up), `NOD`, `HOLD_STILL`. Sustained actions ramp in over the first 30% of
their window, hold, then ramp out, so challenge detectors see a realistic onset.

Directory sources are resolved relative to the scenario file. Frames play in filename order, are scaled to the
scenario size and cycle when the directory runs out.

### Faults

| Kind | Params | In-process effect | Over HTTP |
|---|---|---|---|
| `DISCONNECT` | `permanent` (bool) | health `DISCONNECTED`, frames stop. Delivery ends if no `RECONNECT` follows; `permanent` → `FAILED` | stream socket closed; `STREAM` answers SBI error `202` until reconnect |
| `RECONNECT` | — | `RECONNECTING` → `CONNECTED`, frames resume | device accepts `STREAM` again |
| `STALL` | `durationMs` (2000) | `DEGRADED` for the duration, no frames | socket stays open and quiet |
| `CORRUPT_FRAME` | `count` (1), `fraction` (0.5) | the next frames are truncated to `fraction` of their bytes | same, on the JPEG |
| `LATENCY_SPIKE` | `delayMs` (500) | the next frame is late | the device thread pauses |

### Bundled scenarios (`src/main/resources/scenarios/`)

| Name | Label | What it exercises |
|---|---|---|
| `live-pass` | LIVE | Still subject with natural blinks: passive pass |
| `live-blink-smile-turn` | LIVE | Performs every active challenge in sequence |
| `print-attack` | PRINT | Static face, paper grain and halftone, no blinks |
| `screen-replay` | SCREEN_REPLAY | Pixel-grid/moiré overlay, blue cast, looping clip |
| `video-replay` | VIDEO_REPLAY | 5 s expression loop that ignores prompts |
| `disconnect-mid-session` | LIVE | Unplug at 3 s, replug at 5.5 s |
| `stall` | LIVE | No frames for 4 s at 2 s |
| `multiple-faces` | LIVE | Two subjects in frame |
| `no-face` | NONE | Empty scene, then blank, then sensor noise |

Load them with `Scenarios.bundled("print-attack")`, `Scenarios.load(path)` or `Scenarios.resolve(nameOrPath)`.

> The synthetic attacks imitate the *cues* each species leaves: static texture, moiré and replay timing. They
> are for exercising pipelines and the orchestrator. They are not a substitute for a real PAD corpus when you
> measure APCER/BPCER.

## In-process source

```java
MockFaceFrameSource src = MockFaceFrameSource.builder(Scenarios.bundled("live-blink-smile-turn"))
        .format(PixelFormat.RGB888)          // or MJPEG
        .pacing(Pacing.unthrottled())        // realTime() (default) or speed(4.0)
        .certification(DeviceCapabilities.CertificationLevel.L1)
        .build();
src.start(sink, StreamOptions.defaults());
src.inject(Fault.of(FaultKind.STALL, "durationMs", 1000));   // programmatic fault
```

Frame timestamps follow **scenario time** (`start + scenarioMs`), whatever the pacing. An unthrottled test
therefore sees the same timestamps as a real-time run, so time-based challenge logic stays deterministic.
`onEnd` fires exactly once per `start`. The source passes `testkit`'s `FrameSourceConformanceTest`.

`SyntheticFaceRenderer` and `ExpressionTimeline` can also be used on their own, for example to render a single
pose for an engine golden test.

## Mock SBI service

```
java -cp device-mock.jar:<deps> io.mosip.vivid.device.mock.sbi.MockSbiMain \
     --port 4501 --scenario live-blink-smile-turn [--fps 15] [--framing standard|mock-mds] \
     [--certification L0|L1] [--clock first-stream|start] [--list]
```

The service binds to `127.0.0.1` only, as the SBI specification requires. Scenario time starts with the first
`STREAM` request (`--clock start` starts it at boot instead).

| Verb and path | Notes |
|---|---|
| `MOSIPDISC /device` | Body `{"type":"Face"}`. A request for any other type gets `[]` |
| `MOSIPDINFO /info` | `MOSIPINFO` is also accepted. `deviceInfo` is an **unsigned** JWT (`alg: none`) |
| `STREAM /stream` | `multipart/x-mixed-replace` MJPEG. Body `{"deviceId","deviceSubId","timeout"}` |
| `RCAPTURE /capture`, `CAPTURE /capture` | Returns the last frame as a base64url JPEG `bioValue` inside an unsigned `data` JWT |
| `POST /mock/fault` | `{"kind":"STALL","params":{"durationMs":3000}}` |
| `POST /mock/scenario` | `{"name":"print-attack"}` or `{"path":"/abs/file.json"}` |
| `GET /mock/status` | Simulator state: plugged, scenario time, frames sent, last label |

`--framing mock-mds` reproduces the wire format of MOSIP's reference MockMDS (`boundary=--BoundaryString`,
header lines without spaces). `standard` uses RFC 2046 framing (`boundary=mosipvividframe`).

**Everything the mock produces is marked as mock:** `"mock": true` appears in the digitalId, the deviceInfo and
the capture data. Signatures are absent (`alg: none`), and `bioValue` holds a plain JPEG rather than an
ISO 19794-5 record. Do not point a production Registration Client at it.

Example session (captured from this build):

```
$ curl -s -X MOSIPDISC http://127.0.0.1:4599/device -d '{"type":"Face"}'
[{"deviceId":"MOCK-FACE-0001","deviceStatus":"Ready","certification":"L0","serviceVersion":"0.9.5",
  "deviceSubId":[0],"callbackId":"http://127.0.0.1:4599/","digitalId":"eyJzZXJpYWxObyI6Ik1PQ0tTTjAw...",
  "deviceCode":"MOCKSN0001","specVersion":["0.9.5"],"purpose":"Registration",
  "error":{"errorCode":"0","errorInfo":"Success"}}]

$ curl -s -X STREAM -D - http://127.0.0.1:4599/stream -d '{"deviceId":"MOCK-FACE-0001","deviceSubId":0,"timeout":500}'
HTTP/1.1 200 OK
Content-type: multipart/x-mixed-replace; boundary=mosipvividframe
...
--mosipvividframe
Content-Type: image/jpeg
Content-Length: 81920
<jpeg bytes>
```

## Tests

`mvn -q -f device-mock/pom.xml install` runs 39 tests. They cover scenario parsing (every bundled scenario
loads and renders), timeline maths, renderer determinism and overlays, frame-exact fault behaviour in-process,
and the HTTP verbs, stream, capture and admin endpoints.
