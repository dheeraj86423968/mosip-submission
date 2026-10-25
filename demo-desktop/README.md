# demo-desktop: Attack Lab

Attack Lab is a Swing desktop demo of MOSIP Vivid. You pick a workflow and what is in front of the camera: a live
person, a printed photo, a video replay, a screen replay or an injected stream. You then watch the real pipeline
decide. You can also switch the subject partway through a session.

What is real and what is simulated:

| Part | Real or simulated |
|---|---|
| Orchestrator | Real: `liveness-core` `LivenessSessionFactory`, `LivenessOrchestrator` and `FrameSourceBridge` |
| Engine | Real: `liveness-engine-onnx` (YuNet, MiniFASNet anti-spoof, SFace, signals S1–S7 and S9) |
| Audit events and evidence hash | Real: the orchestrator's own `LIVENESS` audit records and SHA-256 evidence hash |
| Frames | Simulated: drawn by `device-mock`. Print, video replay and screen replay are the bundled `print-attack`, `video-replay` and `screen-replay` scenarios, played by `MockFaceFrameSource`. The live person and the injected stream use device-mock's `SyntheticFaceRenderer`, driven by a simulated subject that reacts to each prompt after 700 ms. This is the same closed-loop approach as `e2e-tests/InteractiveChallengeTest`. |

## Run

You need JDK 17 or newer, and the ONNX models in `liveness-engine-onnx/models` (see the README there).
The demo looks for the models in this order:
1. `--models DIR`
2. `$MOSIP_VIVID_MODELS`
3. `../liveness-engine-onnx/models`
4. `./liveness-engine-onnx/models`

```sh
# from the repository root
mvn -pl demo-desktop -am install -DskipTests   # once, or install the modules it depends on
mvn -pl demo-desktop exec:java                 # opens the window
mvn -pl demo-desktop package                   # builds demo-desktop/target/vivid-demo.jar (fat jar, ~95 MB with ONNX Runtime natives)
java -jar demo-desktop/target/vivid-demo.jar --workflow operator --subject live
java -jar demo-desktop/target/vivid-demo.jar --help
```

### Headless recording

The recorder paints the same view offscreen. It writes PNG screenshots of key states and a Motion-JPEG AVI. It
needs no display.

```sh
java -Djava.awt.headless=true -jar demo-desktop/target/vivid-demo.jar --record docs/demo --scenario demo-reel
java -Djava.awt.headless=true -jar demo-desktop/target/vivid-demo.jar --record out --scenario print --workflow resident
java -Djava.awt.headless=true -jar demo-desktop/target/vivid-demo.jar --record out --scenario live-then-injected --workflow operator
```

Scenarios:
- `demo-reel`: five takes into `vivid-demo.avi`: live resident, operator challenge, print attack, video replay, and injection mid-session.
- `live`
- `print`, `video`, `screen`, `injected`
- `live-then-<print|video|screen|injected>`: the subject switches at 4 s.

Options:
- `--fps`: default 5.
- `--video-width`: default 1024.
- `--quality`: default 0.55.
- `--hold-ms`: default 4000.
- `--title-ms`: default 4000 for `demo-reel`, 0 otherwise.
- `--video none`: skips the video.

Recordings run on the frame clock, so challenge draws are seeded and the results reproduce.

### Real SBI device

```sh
java -jar demo-desktop/target/vivid-demo.jar --sbi 127.0.0.1:4501 --workflow operator
```

This mode reads frames from an SBI 0.9.5 `/stream` endpoint through `device-sbi-stream` instead of the attack-lab
subjects. The subject selector is disabled. The defaults change to match real faces:
- Production detector threshold (0.7)
- Compiled-in workflow policies
- Wall-clock time

You can point it at `device-mock`'s SBI server (`java -jar device-mock/target/device-mock-*.jar --port 4501
--scenario live-pass`). It has not been tried on vendor hardware.

## What the window shows

| Area | Content |
|---|---|
| Control bar | **Workflow**: Resident, Operator or Supervisor. **Subject**. **Start session**. **Try again**: calls `retry()`, which uses up an attempt. **Stop**. **Switch to**: Live, Print, Video, Screen or Injected. This replaces the subject while the session keeps running. That is the attack-lab feature. |
| Camera preview | The frame the engine analysed. Also shows: <ul><li>the oval guide, coloured by phase: grey idle, amber acquiring or checking, green challenge or passed, red failed</li><li>the detected face box</li><li>the phase, the session time and the subject in front of the camera</li></ul> |
| Session | Workflow, attempt counter, frames analysed and a phase stepper: Connect, Acquire, Passive, Challenge, Decision. |
| What the person is asked to do | The challenge prompt, using the copy from `docs/design/12-ux-spec.md`, for example "Please turn your head to the right". Also shows challenge *i of n*, the action-detector progress bar and the countdown. Outside a challenge it shows the phase message ("Checking face liveness…"). After a failure it shows the failure message and guidance. |
| Fused signals | The fused liveness score with the workflow's passive threshold. Below it, each signal's latest attack probability: S1 CNN texture, S2 moiré, S3 colour, S4 rPPG, S5 parallax, S6 challenge timing, S7 identity, injection and S9 quality. The vetoing signal is marked VETO. |
| Decision | <ul><li>A banner: LIVE, ATTACK, NOT LIVE or LOCKED.</li><li>The message the person would see. PAD and identity failures are generic: "Face verification could not be completed. Please try again."</li><li>The audit-only detail: failure reason, veto signal, attack class, fused score and number of challenges.</li><li>The evidence hash.</li></ul> |
| Audit trail | The orchestrator's `LIVENESS` events as they are emitted, coloured by severity. Subject switches are logged as `ATTACK_LAB` lines. |

## Results with the current engine

These are the results of the committed `docs/demo` recording.

| Take | Outcome |
|---|---|
| Live person, Resident | LIVE after the 2.5 s passive window. No challenge, because confidence is high. |
| Live person, Operator | LIVE. Three challenges, all passed: TURN_RIGHT, BLINK, SMILE. |
| Printed photo, Operator | ATTACK on the first frame. CNN texture (S1) veto. |
| Video replay, Operator | NOT LIVE (`ACTIVE_CHALLENGE_FAILED`). The recording turned left while asked to turn right, audited as `UNPROMPTED_ACTION`. |
| Live, then injected stream at 4 s | ATTACK (`PAD_DETECTED`, injection veto). |
| Screen replay (not in the reel) | The moiré overlay stops YuNet finding a face. The session fails with `FACE_NOT_DETECTED` at the 60 s session timeout. It is rejected, but not for the right reason. See `docs/testing/e2e-findings.md`. |

## Honest limits

- **Frames are synthetic.** Live and attack subjects come from the same cartoon renderer. Passive PAD on these frames
  says nothing about real attack instruments, and no APCER or BPCER can be measured here.
- **Demo policy.** In the synthetic modes the demo uses the compiled-in workflow defaults with three changes:
  - YuNet threshold 0.35 instead of 0.7. YuNet scores drawn faces near its boundary.
  - rPPG and parallax weight 0. A drawn face has no pulse and no depth, so they would also reject the live subject.
  - NOD and LOOK_DOWN removed from the challenge pool. Downward pitch is not measurable on the drawn face.

  These are the same adjustments `e2e-tests` makes. They are shown in the UI as "off" bars and in the policy
  version `attack-lab-synthetic-1`.
- **Injected stream.** This machine has no virtual camera. While "Injected stream" is selected, the demo passes the
  engine's real `InjectionSignal` a simulated device list containing "OBS Virtual Camera". The veto logic is the
  engine's own. The pixels are the same as a live subject's, and that subject even answers prompts, which is the
  point: challenge-response cannot catch a responsive injection.
- **Results vary by engine version.** The engine was being changed while this module was written. With one earlier
  build, the mock's printed photo scored S1 at only 0.80, below the 0.9 veto. It then passed the Resident workflow
  passively (fused score 0.86 against a 0.80 threshold) and was rejected under Operator only because it could not
  answer a challenge.
- **Random challenges.** In the window, challenges are drawn from `SecureRandom`, so a video replay can occasionally
  pass a two-challenge Operator sequence that matches its own gestures. This is the residual weakness measured in
  `docs/testing/e2e-findings.md`.
- **The frame clock.** The session clock follows frame timestamps. If inference is slower than 15 fps, the preview
  runs slower than real time rather than timing out challenges unfairly.

## Tests

`mvn -pl demo-desktop verify` runs three test classes:
- `AviMjpegWriterTest`: RIFF structure, `avih`/`strh` fields, `idx1` offsets, decodable frames, padding.
- `AttackLabSourceTest`: switching subjects keeps one continuous stream, the swap is hidden from the session, and
  the clock leads the frames.
- `HeadlessSessionTest`: a full Operator session with a live subject reaches LIVE and is recorded to PNG and AVI,
  and an injected stream is vetoed. It is skipped when the models are missing.
