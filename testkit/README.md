# testkit

Evaluation and interoperability tooling for MOSIP Vivid (PLAN §11). The `metrics`, `conformance`, `scenario` and
`bench` packages depend only on **`liveness-api`**, `device-api` and `device-mock`, so they can evaluate any
implementation of the API. The `corpus` package (`CorpusScoreMain`) and `bench.PerfBenchMain` run the real
`liveness-core` orchestrator and the ONNX engine. Those two are **optional** Maven dependencies of testkit: there is
no cycle (neither depends on testkit), and they do not reach modules that depend on testkit for the conformance
suite.

| Package | What |
|---|---|
| `io.mosip.vivid.testkit.metrics` | `Iso30107Metrics`, `GroupSlices`, `Rate` (Wilson CI), `NonResponse`, `Iso30107Report` (JSON and self-contained HTML), `IsoReportMain` CLI |
| `io.mosip.vivid.testkit.corpus` | `CorpusScoreMain` CLI (recorded corpus → `scores.jsonl`), `FrameTraceMain` CLI (per-frame signal and pose trace), `CorpusItem`, `CaptureLog` |
| `io.mosip.vivid.testkit.conformance` | `FrameSourceConformanceTest`: a JUnit 5 contract every `FaceFrameSource` must pass |
| `io.mosip.vivid.testkit.scenario` | `ScenarioRunner`, `ScenarioExpectation`, `ScenarioSuite`, `SuiteReport` |
| `io.mosip.vivid.testkit.redteam` | `RedTeamCorpusMain` (synthetic attack corpus), `RedTeamRenderer`, `RedTeamVariant` (stable variant ids), `RedTeamGateMain` (APCER/BPCER/ACER trend, regression gate, fixtures) |
| `io.mosip.vivid.testkit.bench` | `LatencyBenchmark`, `LatencyStats` (p50/p95/p99), `PerfBenchMain` (engine and orchestrator latency CLI) |

## ISO/IEC 30107-3 metrics

```java
List<PadSample> samples = List.of(PadSample.bonaFide(0.93), PadSample.attack("PRINT", 0.12), ...);
Iso30107Metrics m = Iso30107Metrics.compute(samples, 0.5);   // accepted when score >= threshold
m.apcerBySpecies(); m.maxApcer(); m.bpcer(); m.acer(); m.bpcerAtApcer5(); m.bpcerAtApcer10(); m.eer(); m.detCurve();
m.apcerRateBySpecies().get("PRINT").wilson95(); m.bpcerRate().wilson95(); m.apnrr(); m.bpnrr(); m.bpcerAtApcer5Threshold();
GroupSlices g = GroupSlices.compute(samples, 0.5, 5);       // samples tagged with withGroups(...), withSubject(...)
new Iso30107Report(m, g, "Desktop engine v0.3", Map.of("bundle", "pad-2026.10")).write(outDir, "iso30107");
```

- **Decisions win over scores.** `IsoReportMain` classifies by `score >= threshold`, except that a response the session
  rejected for a reason the passive threshold does not control (`decision = ATTACK` after a hard veto, a failed or
  timed-out challenge, identity drift) is scored 0 and so is rejected at every threshold. The fused score of a vetoed
  session is computed before the veto and can be above the threshold.
- **APCER** is computed per PAI species. The summary APCER is the **maximum** over species, as 30107-3 requires.
- **BPCER** is the fraction of bona fide presentations rejected. **ACER** = (max APCER + BPCER) / 2.
- **BPCER@APCER ≤ 5% and ≤ 10%** come from a threshold sweep over every distinct score. The value is `NaN`
  (`null` in JSON, `n/a` in HTML) if no threshold reaches the target.
- **Wilson 95 % intervals** (`Rate.wilson95()`) are given for every per-species APCER, BPCER, APNRR and BPNRR and
  for every slice rate.
- **APNRR / BPNRR**: `PadSample.nonResponse(species, id)` marks a presentation with no PAD decision. `NonResponse`
  defines which outcomes count: `ERROR`, failure to acquire, timeout, and device or engine faults. Non-responses are
  excluded from APCER/BPCER and reported separately. `CANCELLED` lines are excluded from every rate.
- **Group slices** (`GroupSlices`): BPCER, BPNRR and per-species APCER for each tag value. A slice with fewer than 5
  distinct participants is suppressed, with no counts exposed. Each slice also has its BPCER ratio to the lowest
  slice.
- **DET points** are (threshold, max APCER, BPCER) from accept-all to reject-all. The **EER** is the point
  where the two are closest.
- The HTML uses inline CSS and an inline SVG DET curve: no scripts and no external assets. It supports light
  and dark mode.

### CLI (used by CI)

```
java -cp testkit.jar:<deps> io.mosip.vivid.testkit.metrics.IsoReportMain \
     --scores scores.jsonl --out target/iso-report \
     [--threshold 0.5] [--title "..."] [--context engine=onnx]... [--max-acer 0.10]
```
`scores.jsonl` has one presentation per line. `LIVE` and `BONA_FIDE` are synonyms. Lines starting with `#` are
ignored. Options added since the first version are `[--min-subjects 5] [--no-slices]`. The full record format is in
`docs/corpus-ethics.md` §10.2.
```
{"label":"BONA_FIDE","score":0.93,"id":"subj01"}
{"label":"PRINT","score":0.12}
{"bonaFide":false,"pais":"SCREEN_REPLAY","score":0.40}
{"id":"P07-BONA_FIDE-L3-D1-T1","label":"BONA_FIDE","decision":"NOT_LIVE","failure":"FACE_NOT_DETECTED","response":false,"groups":{"lighting":"L3"}}
```
The CLI writes `iso30107.json` and `iso30107.html`. Exit codes: `0` ok, `1` ACER above `--max-acer` (CI
regression gate), `2` usage or input error.

### Corpus scorer (`CorpusScoreMain`)

```
java -cp testkit/target/classes:<deps> io.mosip.vivid.testkit.corpus.CorpusScoreMain \
     --corpus <dir> --out scores.jsonl [--log capture-log.csv] [--mode passive|challenge] \
     [--workflow RESIDENT] [--fps 15] [--models <dir>] [--passive-threshold x] [--detector-threshold x]
```
Every folder of numbered `.jpg`/`.jpeg`/`.png` frames under `--corpus` is one item, named by its
`docs/corpus-ethics.md` §10.1 item ID. An optional `timestamps.txt` gives the frame times; otherwise frames are
spaced at `--fps`. Each item is replayed as one session through the real orchestrator and ONNX engine. A fake
`TimeSource` is advanced to each frame timestamp (frame clock, as in `e2e-tests/FrameClockSession`). Passive mode
uses `active.mode = NEVER`. The scorer writes one JSON line per item: label, participant, PAI class, decision,
failure, fused score, response flag, and group tags from `capture-log.csv`. Demographic tags are written only with
`consent_demographics=Y`. Exit codes: `0` ok, `2` usage or input error, `3` no models. The full contract is in
`docs/testing/test-plan.md` §6.

### Frame trace (`FrameTraceMain`)

```
java -cp testkit/target/classes:<deps> io.mosip.vivid.testkit.corpus.FrameTraceMain \
     --corpus <dir> --out trace.csv [--models <dir>] [--fps 15] [--calib-ms 2500] [--freeze-challenge NOD|NONE] [--detector-threshold x]
```
Same corpus layout. Each item goes frame by frame through one fresh `OnnxFrameAnalyzer` (no orchestrator). After
`--calib-ms` the analyzer is told a challenge started, which freezes `PitchBaseline` (pitch becomes relative to the
learned neutral) and, for `NOD`, starts S5 tracking. One CSV row per frame: face count, quality, yaw, engine pitch,
absolute pitch, nose drop and fraction, vertical gaze, eye and mouth aspect, smile, and every signal's attack
probability and confidence. Measurements only, no pixels. Used by `docs/testing/proxy-eval/`.

## Red team (`redteam`)

```
java -cp testkit/target/classes:<deps> io.mosip.vivid.testkit.redteam.RedTeamCorpusMain --out <dir> [--subjects 12] [--takes 2] [--seed s] [--threads 2]
java -cp ... io.mosip.vivid.testkit.redteam.RedTeamGateMain --variants <dir>/variants.jsonl --scores passive=<scores.jsonl> \
     [--scores challenge=<file>] [--baseline baseline.json] [--fixtures-out dir] [--trend trend.jsonl] [--trend-md trend.md] ...
```
`RedTeamCorpusMain` renders bona fide sessions and seven simulated attack species (print, warped print, cut-out print,
screen replay, screen photo, replay with timing jitter, low-resolution replay) from device-mock's drawn face only, in
the `CorpusScoreMain` layout plus `variants.jsonl`. Each variant has a stable id (species + hash of the generator
version and every render parameter) and renders byte-identically. `RedTeamGateMain` compares per-variant outcomes with
`docs/testing/redteam/baseline.json`. It exits 1 and writes a re-renderable fixture for each attack that was rejected
and is now accepted, and it appends a trend row. `tools/redteam/run-redteam.sh` runs the whole loop, and
`.github/workflows/redteam.yml` runs it nightly. `RedTeamFixtureReplayTest` replays the committed fixtures in
`src/test/resources/redteam/fixtures/` and reports known bypasses as skipped. These are weak proxies, not PAD
performance: see `docs/testing/redteam-trend.md`.

## Frame source conformance

```java
class MyAdapterConformanceTest extends FrameSourceConformanceTest {
    @Override protected FaceFrameSource createSource() { return new MyAdapter(...); }
    @Override protected long frameTimeoutMillis() { return 20_000; }   // optional hooks
}
```
It checks the following:
- `capabilities()` is non-null and declares at least one format.
- Every frame's format is in the declared formats, and frames match the reported resolution once one is known.
- Sequence numbers increase strictly and timestamps never decrease.
- `start`/`stop` are idempotent, with `onEnd` exactly once per start, including over a restart cycle.
- Health starts at `CONNECTING`/`CONNECTED` and follows the allowed state graph, is usable while frames flow,
  and is not usable after stop.
- Stopping during delivery does not throw, and there are no callbacks after `onEnd`.
- `close()` behaves like `stop()`.

The module includes two implementations: `MockFaceFrameSource` in RGB888 at 4× speed, and in MJPEG unthrottled.

## Scenario runner and suites

```java
LivenessSessionFactory sessions = ctx -> engine.open(policy, ctx);    // liveness-core wiring lives in the caller
ScenarioRun run = new ScenarioRunner(sessions).run(Scenarios.bundled("print-attack"),
        ScenarioRunner.Options.defaults().workflow(Workflow.OPERATOR).maxScenarioMillis(30_000));
run.result(); run.trace(); run.healthEvents();
```
- Frames are fed on the mock's delivery thread. Every device health event triggers `session.tick()`, so
  timeouts still fire while the device stalls or is unplugged.
- Each `StateTransition` records the scenario time of the frame that caused it.
- The default pacing is **unthrottled**: a 16 s scenario runs in well under a second. Frame timestamps still
  follow scenario time, so an orchestrator that uses frame timestamps behaves exactly as in real time. Use
  `Pacing.realTime()` if the session under test uses the wall clock.
- A run stops on a terminal state; on `FAILED` (or after `retry()` with `retryOnFailure(true)`); at the end of
  the scenario; or at `maxScenarioMillis` / `maxWallMillis`.

Expectations live in `<scenario>.expect.json`:
```json
{"scenario":"print-attack","expectedDecision":["ATTACK","NOT_LIVE"],
 "allowedFailureReasons":["PAD_DETECTED","ACTIVE_CHALLENGE_FAILED","CHALLENGE_TIMEOUT"],
 "expectedAttackClass":"PRINT","maxDurationMs":60000}
```
The module ships expectations for all nine bundled scenarios in `src/main/resources/expectations/`.

```java
@TestFactory Stream<DynamicTest> scenarios() {
    return ScenarioSuite.bundled().dynamicTests(sessions, ScenarioRunner.Options.defaults());
}
// or, for a report:
SuiteReport r = ScenarioSuite.load(Path.of("my-scenarios")).run(sessions, ScenarioRunner.Options.defaults());
System.out.print(r.summary());     // JUnit-style text
r.write(Path.of("target/scenario-report"), "suite");   // suite.json + suite.html (with state traces)
```

## Latency benchmark

```java
LatencyBenchmark.Result r = new LatencyBenchmark(10)   // 10 warm-up frames
        .run(engine.newAnalyzer(), "onnx-desktop", Scenarios.bundled("live-pass"), 300, PixelFormat.MJPEG);
r.wall().p95(); r.reported().p99(); r.toJsonNode();
```
Frames are rendered before timing starts. The benchmark reports two sets of nanosecond percentiles: wall time
around `analyze()`, and the analyzer's own `FrameAnalysis.processingNanos()`.

### Low-resource benchmark (`PerfBenchMain`)

```
taskset -c 3 java -Xmx256m -cp testkit/target/classes:<deps> io.mosip.vivid.testkit.bench.PerfBenchMain \
     --models liveness-engine-onnx/models --threads 1 --frames 300 [--format MJPEG] [--json out.json]
```
It measures per-frame latency (median, p90, p99) and throughput of the ONNX engine alone and of a full
`liveness-core` orchestrator session over it. Each is measured twice: with an SFace embedding on every frame
(K=1) and at the default cadence (K=5, also split into frames with and without an embedding). It reports peak
RSS as well. `tools/perf-lowres.sh` runs the 1-core and 2-core, 256/512 MiB matrix. Results and method are in
`docs/testing/performance.md`.

## Notes

- `junit-jupiter-api` has **compile** scope on purpose, because `FrameSourceConformanceTest` and
  `ScenarioSuite.dynamicTests` are shipped API for adapter and engine authors.
- `mvn -q -pl testkit verify` runs 41 tests. Metrics are checked against hand-computed values: APCER PRINT 1/2,
  SCREEN_REPLAY 2/3, BPCER 1/4, BPCER@APCER≤10% = 1/2 at threshold 0.90, and BPCER@APCER≤5% 1/4 at 0.65 against
  ≤10% 0 at 0.50. Wilson bounds are checked against values computed independently. APNRR 3/8 and BPNRR 1/5 are
  checked on a fixture with non-responses. Slices are checked on a 10-participant fixture covering suppression, the
  ratio-to-lowest and the unbounded ratio. `CorpusScoreMainTest` renders a tiny synthetic corpus with `device-mock`
  and scores it with the real engine; it is skipped when `liveness-engine-onnx/models` is absent. The runner and suite
  are tested with a stub `LivenessSession`; the conformance suite runs against the mock.
- On a low-memory machine, build with `MAVEN_OPTS=-Xmx768m mvn -q -pl testkit verify -DargLine=-Xmx1g`.
