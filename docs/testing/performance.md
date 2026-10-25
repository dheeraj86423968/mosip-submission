# Low-resource performance (desktop proxy)

Measured on 2026-10-24. These numbers show how the engine and the full orchestrator behave on one and two CPU
cores with a small Java heap. They are a **proxy** for low-end registration hardware. **They are not Android
measurements.** Section 5 explains why the proxy can be wrong in either direction.

## 1. Method

| Item | Value |
|---|---|
| Machine | VM with 4 vCPUs (one core per socket, no SMT) on a 12th Gen Intel Core i9-12900H host, 7.7 GiB RAM, Linux 7.0 (Kali), OpenJDK 25.0.4, ONNX Runtime 1.20.0 CPU provider |
| Constraint | `taskset -c 3` (1 core) or `taskset -c 2,3` (2 cores). The JVM sees `availableProcessors()` = 1 or 2. Java heap `-Xmx256m` or `-Xmx512m`. ONNX Runtime allocates natively, outside the Java heap, so peak RSS is reported separately |
| Tool | `testkit` `io.mosip.vivid.testkit.bench.PerfBenchMain`, run by `tools/perf-lowres.sh` (raw output: `summary.txt` plus one JSON file per configuration) |
| Models | YuNet 2023mar (640x640 letterbox), MiniFASNetV2-SE 128 px, SFace fp32: the bundled set from `liveness-engine-onnx/models` |
| Input | 640x480 frames of `device-mock`'s `live-blink-smile-turn` scenario, rendered before timing starts. 48 distinct frames are cycled with fresh sequence numbers and 15 fps timestamps (rendering 330 VGA frames up front would not fit in 256 MiB). RGB888, plus one MJPEG run to include JPEG decoding. The detector threshold is 0.35, because drawn faces need it (production default 0.7). A face was found in 98 % of frames, so nearly every frame takes the full path |
| Engine run | `FrameAnalyzer.analyze()` timed per frame: 300 measured frames after 30 warm-up frames |
| Orchestrator run | A real `liveness-core` session (`LivenessSessionFactory`, RESIDENT default policy, frame clock), timing `feed()` + `tick()` per frame. A new session opens when one ends (6 to 9 sessions per run). Session open is not timed |
| Embedding | **K=1**: SFace on every frame (the worst case). **K=5**: the default `embedding.everyFrames=5`, also split into frames with and without an embedding |
| FPS | Measured frames / summed per-frame time, for one stream processed serially. This is the analysis throughput ceiling, not camera fps |
| Background load | **Another build (Flutter/Android) ran on the same VM throughout.** The 1-minute load average was 2.0 to 5.2. Absolute numbers are therefore pessimistic, and run-to-run variation is about ±10 % on medians and more on p99 |

## 2. Results

Times in ms per frame. Peak RSS is that of the whole JVM, including ONNX Runtime.

### 1 core

| Config | Pipeline | K | Median | p90 | FPS | Peak RSS |
|---|---|---|---|---|---|---|
| 1 core, `-Xmx256m`, ORT threads 1 | engine | 1 | 34.7 | 38.6 | 28.4 | 383 MiB |
| | orchestrator | 1 | 34.5 | 37.2 | 29.0 | |
| | engine | 5 | 16.6 | 34.4 | 49.2 | |
| | orchestrator | 5 | 18.4 | 37.7 | 44.0 | |
| 1 core, `-Xmx512m`, ORT threads 1 | engine | 1 | 37.9 | 46.7 | 25.5 | 370 MiB |
| | orchestrator | 1 | 35.0 | 37.4 | 28.7 | |
| | engine | 5 | 17.3 | 35.4 | 47.3 | |
| | orchestrator | 5 | 17.3 | 35.3 | 46.8 | |

At K=5 the frames without an embedding take a median 16.4 ms (p90 17.4) and the frames with one take 34.5 ms
(p90 37.9), at 1 core and 256 MiB. The SFace embedding therefore costs about 18 ms on one core, roughly as much as
everything else together.

### 2 cores

| Config | Pipeline | K | Median | p90 | FPS | Peak RSS |
|---|---|---|---|---|---|---|
| 2 cores, `-Xmx512m`, **ORT threads 1** | engine | 1 | 35.3 | 38.0 | 28.3 | 418 MiB |
| | orchestrator | 1 | 41.0 | 47.5 | 23.6 | |
| | engine | 5 | 18.8 | 38.1 | 42.8 | |
| | orchestrator | 5 | 18.7 | 37.0 | 45.0 | |
| 2 cores, `-Xmx256m`, ORT threads 2 | engine | 1 | 65.8 | 76.7 | 15.2 | 369 MiB |
| | orchestrator | 1 | 59.6 | 70.7 | 16.6 | |
| | engine | 5 | 32.2 | 60.2 | 26.7 | |
| | orchestrator | 5 | 33.5 | 65.3 | 25.1 | |
| 2 cores, `-Xmx512m`, ORT threads 2 | engine | 1 | 70.0 | 87.6 | 14.0 | 380 MiB |
| | orchestrator | 1 | 69.9 | 113.2 | 12.9 | |
| | engine | 5 | 28.3 | 50.0 | 32.5 | |
| | orchestrator | 5 | 30.8 | 54.1 | 29.7 | |
| 2 cores, `-Xmx512m`, ORT threads 2, **MJPEG input** | engine | 1 | 72.1 | 92.5 | 13.4 | 387 MiB |
| | orchestrator | 1 | 68.2 | 79.1 | 14.6 | |
| | engine | 5 | 38.1 | 67.2 | 22.8 | |
| | orchestrator | 5 | 36.4 | 63.0 | 24.6 | |

### Reference: all 4 vCPUs

| Config | Pipeline | K | Median | p90 | FPS | Peak RSS |
|---|---|---|---|---|---|---|
| 4 cores, `-Xmx512m`, ORT threads 2 (engine default) | engine | 1 | 30.0 | 66.0 | 26.6 | 401 MiB |
| | orchestrator | 1 | 31.3 | 59.2 | 26.2 | |
| | engine | 5 | 13.7 | 26.5 | 60.1 | |
| | orchestrator | 5 | 15.0 | 29.7 | 53.7 | |

The 2-core ORT-threads-1 row and the 1-core repeat came from a second, shorter pass (200 measured frames) run
right after the matrix. In that pass, 1 core with ORT threads 1 gave engine K=1 median 36.0 ms and K=5 median
18.1 ms, consistent with the matrix. Two cores with ORT threads 2 were again slow: K=1 median 70.7 ms on cores
2-3 and 64.2 ms on cores 0-1.

## 3. Findings

1. **The pipeline runs in real time on one core.** With the default embedding cadence (K=5), the full
   orchestrator takes a median of about 17-18 ms per frame and a p90 of about 35-38 ms on one core (about 45 fps
   of analysis capacity), against a 15 fps camera budget of 66 ms. The worst case, an embedding on every frame,
   has a median of about 35 ms and a p90 of about 37-47 ms, still under 66 ms at the median and the p90.
2. **The orchestrator adds almost nothing.** Orchestrator and engine medians agree to within the run-to-run noise
   (about ±2 ms, sometimes in the orchestrator's favour). Fusion, challenge logic and identity continuity are
   negligible next to inference.
3. **The embedding is the single biggest cost.** It accounts for about half of an embedding frame (about 18 ms
   of 35 ms on one core). Keeping `embedding.everyFrames` at 5 roughly halves the median cost per frame.
   Detection on the 640x640 letterbox is most of the rest.
4. **A 256 MiB heap is enough.** The 256 MiB and 512 MiB runs differ by no more than the noise. Peak RSS is
   370-420 MiB in every configuration. That figure is dominated by native ONNX Runtime memory, the 39 MB fp32
   SFace weights and the JVM itself. Budget about 400 MiB of RAM for the process, not just the Java heap. (The
   first harness version kept 330 rendered frames, about 300 MiB, and ran out of memory at 256 MiB. That was the
   benchmark's own buffering, now fixed by cycling a 48-frame pool. The engine was never the cause.)
5. **Do not give ONNX Runtime as many threads as there are cores on small machines.** With 2 cores and the
   engine's default `vivid.engine.threads=2`, every run was about **2x slower** than with 1 thread: K=1 median
   60-72 ms against 35 ms. It was also slower than a single pinned core. This reproduced on two different core
   pairs. A likely cause, not verified: ONNX Runtime's intra-op worker spin-waits and competes with the JVM's own
   threads (GC, the orchestrator thread) and with the background build for the same two cores. On all 4 vCPUs,
   2 threads are fine (K=5 median 13.7 ms). **Recommendation: `vivid.engine.threads=1` on machines with 2 cores
   or fewer.** The default was not changed in this pass. A follow-up could default to
   `min(2, availableProcessors - 1)`.
6. **MJPEG input adds roughly 5-10 ms per frame for JPEG decoding** (K=5 median 38.1 ms against 28.3 ms for
   RGB888 in the same slow 2-thread configuration, from separate runs, so the gap is approximate). SBI `/stream` delivers MJPEG, so this is the realistic Desktop
   input.

## 4. Reproduce

```sh
tools/perf-lowres.sh target/perf            # matrix: 1/2 cores x 256/512 MiB, MJPEG, 4-core reference
# one configuration by hand:
taskset -c 3 java -Xmx256m -cp testkit/target/classes:$(cat target/perf/cp.txt) \
    io.mosip.vivid.testkit.bench.PerfBenchMain --models liveness-engine-onnx/models --threads 1 --frames 300
```
Environment overrides: `FRAMES`, `WARMUP`, `CORE1`, `CORE2`, `MODELS`. Run it on an idle machine to get cleaner
numbers than these.

## 5. Caveats (why this is not an Android result)

- **Different CPU.** These are cores of a recent high-end x86 laptop CPU, on AVX2/AVX-VNNI code paths. A
  low-end Android tablet's ARM Cortex-A5x/A7x cores at lower clocks, with NEON, are typically several times
  slower per core. Thermal throttling, which a VM on a laptop does not show over a 30-second run, cuts sustained
  throughput further. Expect the Android numbers to be worse than these.
- **Different engine.** Android runs the MediaPipe/TFLite engine in `integration-android`, not this ONNX
  engine. These numbers bound the Desktop path. On Android they say only that the orchestrator's own overhead is
  negligible.
- **Different runtime.** ART is not HotSpot. JIT warm-up, GC and the native/managed boundary all behave
  differently.
- **Contended VM.** A concurrent build loaded the VM (load average 2-5). `taskset` limits our process but does
  not reserve the cores, and the hypervisor schedules vCPUs as it likes. Medians are fairly stable; p99s are not
  reliable.
- **Drawn faces.** Synthetic faces at a lowered detector threshold exercise the same code paths as real faces,
  but not real image statistics. A frame with no face is about twice as cheap, because there is no crop, no CNN
  and no embedding.
- **Heap only.** `-Xmx` limits the Java heap, not the process. Native memory was not capped, so a device with
  512 MB of RAM in total would still need checking.

What would turn this into a real low-end result: run the Android engine on an entry-level device (2-3 GB RAM,
Cortex-A53/A55 class) with a timing harness like this one, and run this Desktop matrix on a real 2-core
registration laptop.
