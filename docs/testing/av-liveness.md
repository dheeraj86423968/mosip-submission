# Spoken-code audio-visual liveness: test results

Module `liveness-av`, design in [`docs/design/13-audio-visual-liveness.md`](../design/13-audio-visual-liveness.md).
Run on 24–25 Oct 2026 on a 4-core, 7 GB laptop with no camera and no microphone, CPU only.

> **Read this first.** No real person was recorded or tested. All speech is synthetic text-to-speech (TTS), and all
> mouth-opening tracks are synthetic, built from the speech envelope. The live tracks are therefore an idealisation
> that is easier than real lips. The numbers below show that the pipeline works end to end and how it separates
> *these* attacks from *this* idealised bona fide speech. They are not APCER/BPCER estimates for real residents.

## 1. What was tested

| Test class | Tests | What it covers | Needs |
|---|---|---|---|
| `SpokenCodeTest` | 3 | Code format and display; chi-square uniformity of the digits over 10,000 draws | — |
| `asr.DigitLexiconTest` | 4 | All four lexicons cover 0–9; grammar JSON; parsing Vosk results across segments | — |
| `sync.SpeechActivityTest` | 2 | Silence and noise are not speech; onset within ±60 ms; trailing silence | — |
| `sync.LipSyncScorerTest` | 25 | Synced lips pass (10 random runs); the lag is recovered (−100 ms ± 40); still face → `STATIC_MOUTH`; lips shifted 450–800 ms → not synced (10 random runs); 2 fps → `INSUFFICIENT_VIDEO`; no speech; mouth-track interpolation | — |
| `audio.AudioSourcesTest` | 4 | Clock-driven array source and wipe on close; WAV downmix and resample; Java Sound either captures or throws `AudioUnavailableException` | — |
| `AvFrameAnalyzerTest` | 8 | The engine decorator: no signal outside the carrier; prompt, pending signal at confidence 0, pass at confidence 1, audio wiped; veto for a photo plus a speaker (`PRINT`), wrong code (`VIDEO_REPLAY`) and silence (decided at 6.0–6.1 s); early carrier end forces a decision; a retry draws a fresh code; one round per attempt; missing microphone fails closed, or not when configured | — |
| `AvSetupTest` | 4 | Carrier hold time; unsafe wiring is reported; head-motion carriers are refused; `SpokenCodeRequirement` | — |
| `AvOrchestratorEndToEndTest` | 3 | Real `LivenessOrchestrator`, `ChallengeSelector`, `HoldStillDetector`, fusion and veto, with scripted camera/face and microphone. Live resident passes; photo, replay and silence fail with `PAD_DETECTED`, `vetoSignal=av_sync` and the right attack class; retry; a fresh code each attempt; the last case uses real Vosk with TTS in 4 languages | Vosk + corpus for the last case |
| `AvCorpusEvaluationTest` | 1 | §3: corpus × 8 scenarios × 5 models through the real verifier | Vosk + corpus, opt-in `-Dvivid.av.eval=true` |

**54 tests, all passing** (`mvn -o -pl liveness-av install`; with models and corpus present, including the opt-in
evaluation). Without models or corpus (CI), the Vosk part of the end-to-end test and the corpus evaluation are
skipped, and everything else runs. `integration-desktop`'s 87 tests still pass with the new message key. Its
`LivenessMessagesTest` checks that the key is present in en, fr and ar.

Java Sound on this machine opened a capture line even though there is no physical microphone, presumably the
PulseAudio default source. It returned 16 kHz samples (6,400 in 0.5 s). No speech was ever captured through it.

## 2. Test material

* **Speech:** `liveness-av/tools/synth_corpus.py` wrote 156 clips, 12 per voice, each reading a 4-digit code drawn
  with Python's `secrets` module ("4, 8, 1, 7"), into `~/.cache/vivid-av/corpus` (13 MB, not in the repository).
  Voices: Piper neural TTS for `en_US-lessac`, `en_US-l2arctic` speakers ASI, RRBI, SVBI and TNI (English by
  Hindi-L1 speakers), `hi_IN-pratham`, `hi_IN-priyamvada`, `te_IN-maya` and `te_IN-venkatesh`; eSpeak 1.48 for
  `en-us`, `hi`, `te` and `gu`. Speaking rate is randomised per clip. Licences are in
  [`liveness-av/models/README.md`](../../liveness-av/models/README.md).
* **Presentation:** 400–900 ms of reaction silence, then the clip, then 1.5 s of tail, plus white noise at about
  −54 dBFS.
* **Mouth tracks** (`LipTracks`), sampled at 25 fps with ±3 ms jitter:
  * *live*: speech RMS smoothed over 90 ms, normalised, raised to the power 0.7, mapped to `mouthAspect`
    0.06–0.36, leading the audio by 0–150 ms; a random articulation gain of 0.4–1.6 per 200 ms; Gaussian noise with
    sd 0.02;
  * *still face*: 0.08 plus noise with sd 0.004.

## 3. Scenarios

| Scenario | Audio heard | Face / mouth | Attack? |
|---|---|---|---|
| `LIVE` | the shown code, by the resident | follows that audio | bona fide |
| `STATIC_FACE` | the shown code (loudspeaker) | still (photo or mask) | yes |
| `REPLAY_WRONG_CODE` | an old recording of the same voice with another code | follows that recording (a replayed video) | yes |
| `DUBBED_SHIFTED` | the shown code | follows the audio but shifted 400–800 ms either way | yes |
| `DUBBED_OTHER_UTTERANCE` | the shown code | follows another utterance of the same voice (other digits) | yes |
| `SILENCE` | noise only, 5 s | still | yes |
| `TWO_SPEAKERS` | the shown code by an accomplice off camera, mixed with the on-camera person saying other digits (gain 0.8) | follows the on-camera person | yes |
| `BACKGROUND_TALKER` | the resident reads the code; another voice says other digits 12 dB quieter | follows the resident | bona fide (robustness) |

## 4. Results: corpus evaluation (`AvCorpusEvaluationTest`)

Each cell is the number of correct decisions out of the number of presentations: accepted for `LIVE` and
`BACKGROUND_TALKER`, rejected for the attacks. Default thresholds; 1,728 presentations in total.

| Model | Voice | LIVE | STATIC_FACE | REPLAY_WRONG_CODE | DUBBED_SHIFTED | DUBBED_OTHER_UTT. | SILENCE | TWO_SPEAKERS | BACKGROUND_TALKER |
|---|---|---|---|---|---|---|---|---|---|
| en (US) | piper lessac | 12/12 | 12/12 | 12/12 | 12/12 | 8/12 | 12/12 | 11/12 | 4/12 |
| en (US) | piper l2arctic ASI | 12/12 | 12/12 | 12/12 | 12/12 | 8/12 | 12/12 | 12/12 | 2/12 |
| en (US) | piper l2arctic RRBI | 10/12 | 12/12 | 12/12 | 11/12 | 11/12 | 12/12 | 12/12 | 2/12 |
| en (US) | piper l2arctic SVBI | 10/12 | 12/12 | 12/12 | 12/12 | 10/12 | 12/12 | 12/12 | 3/12 |
| en (US) | piper l2arctic TNI | 12/12 | 12/12 | 12/12 | 12/12 | 6/12 | 12/12 | 12/12 | 1/12 |
| en (US) | espeak en-us | 8/12 | 12/12 | 12/12 | 12/12 | 10/12 | 12/12 | 12/12 | 3/12 |
| en-IN | piper l2arctic ASI | 5/12 | 12/12 | 12/12 | 12/12 | 11/12 | 12/12 | 12/12 | 2/12 |
| en-IN | piper l2arctic RRBI | 2/12 | 12/12 | 12/12 | 12/12 | 11/12 | 12/12 | 12/12 | 0/12 |
| en-IN | piper l2arctic SVBI | 0/12 | 12/12 | 12/12 | 12/12 | 12/12 | 12/12 | 12/12 | 0/12 |
| en-IN | piper l2arctic TNI | 2/12 | 12/12 | 12/12 | 12/12 | 12/12 | 12/12 | 12/12 | 0/12 |
| en-IN | espeak en-us | 0/12 | 12/12 | 12/12 | 12/12 | 12/12 | 12/12 | 12/12 | 0/12 |
| hi | piper pratham | 12/12 | 12/12 | 12/12 | 11/12 | 10/12 | 12/12 | 12/12 | 4/12 |
| hi | piper priyamvada | 10/12 | 12/12 | 12/12 | 12/12 | 7/12 | 12/12 | 12/12 | 4/12 |
| hi | espeak hi | 4/12 | 12/12 | 12/12 | 12/12 | 10/12 | 12/12 | 12/12 | 0/12 |
| te | piper venkatesh | 9/12 | 12/12 | 12/12 | 12/12 | 8/12 | 12/12 | 12/12 | 1/12 |
| te | piper maya | 4/12 | 12/12 | 12/12 | 12/12 | 11/12 | 12/12 | 12/12 | 1/12 |
| te | espeak te | 0/12 | 12/12 | 12/12 | 12/12 | 12/12 | 12/12 | 12/12 | 0/12 |
| gu | espeak gu | 2/12 | 12/12 | 12/12 | 12/12 | 12/12 | 12/12 | 12/12 | 0/12 |

### 4.1 Attacks accepted (all models together)

| Attack | Accepted | Main rejection reason |
|---|---|---|
| Photo + loudspeaker (`STATIC_FACE`) | **0/216** | `STATIC_MOUTH` 119, `CODE_MISMATCH` 97 |
| Replay with wrong code | **0/216** | `CODE_MISMATCH` 216; the lips were in sync in 213 of them, so the code check alone stops replay |
| Lips shifted 400–800 ms | **2/216** | `OUT_OF_SYNC` 117, `CODE_MISMATCH` 97 |
| Lips of another utterance | **35/216** | `OUT_OF_SYNC` 82–83, `CODE_MISMATCH` 98–99 (one presentation was transcribed differently in two runs) |
| Silence | **0/216** | `NO_SPEECH` 216 |
| Two speakers | **1/216** | `CODE_MISMATCH` 213 |

Many attack rejections are code mismatches caused by recognition errors, not by the attack being detected. The
synchrony channel judged on its own is the fairer view:

| Scenario | Judged synchronised (channel b alone) |
|---|---|
| LIVE | 208/216 (96 %) |
| STATIC_FACE | 0/216 |
| DUBBED_SHIFTED | 3/216 (1.4 %) |
| DUBBED_OTHER_UTTERANCE | 59/216 (27 %) |
| TWO_SPEAKERS | 115/216: the mixed envelope still follows the on-camera talker; the code channel stops this attack |
| BACKGROUND_TALKER | 210/216 |

**Dubbing with lips from a different digit utterance of the same voice is the weak case.** Digits have similar
rhythm, so lips from "2 9 0 4" line up partly with "8 3 7 5". Among the 117–118 such presentations where the code was
heard correctly, 35 passed (30 %).

### 4.2 Bona fide speech

| Model | Voices | LIVE accepted | Code recognised exactly (channel a alone) |
|---|---|---|---|
| en (US) | 5 Piper voices | 56/60 | 57/60 |
| en (US) | eSpeak | 8/12 | 9/12 |
| en-IN | 4 Piper L2-ARCTIC + eSpeak | 9/60 | 9/60 (mostly dropped digits) |
| hi | 2 Piper voices | 22/24 | 23/24 |
| hi | eSpeak | 4/12 | 4/12 |
| te | 2 Piper voices | 13/24 | 14/24 (mostly one digit dropped) |
| te | eSpeak ("test" voice) | 0/12 | 0/12 |
| gu | eSpeak ("test" voice) | 2/12 | 2/12 |

With `BACKGROUND_TALKER`, only 27 of 216 bona fide presentations passed, because the recogniser also transcribes the
quieter voice's digits.

### 4.3 Synchrony operating points

A separate sweep used the 108 Piper clips: synthetic live lips at several noise levels against "other utterance" and
"shifted" lips. The default chosen is the third row.

| peak ≥ | z ≥ | peak − max baseline ≥ | live, noise 0.01 | live, noise 0.05 | live, articulation gain + 0.02 | other utterance | shifted |
|---|---|---|---|---|---|---|---|
| 0.35 | 2.0 | 0.0 | 98 % | 98 % | 97 % | 48 % | 1.9 % |
| 0.5 | 2.0 | 0.1 | 98 % | 98 % | 97 % | 39 % | 1.9 % |
| **0.6** | **2.0** | **0.1** | 98 % | 98 % | 97 % | **25 %** | 1.9 % |
| 0.7 | 2.0 | 0.1 | 98 % | 98 % | 96 % | 15 % | 0.9 % |

Synthetic live peaks sit at 0.8–0.97 because the tracks are generated from the envelope. Real lips will score lower
(§9 of the design doc). A threshold high enough to cut the other-utterance attack further would very likely reject
real people. This trade-off has to be settled on real recordings.

## 5. Results: through the orchestrator with Vosk (`AvOrchestratorEndToEndTest.withVoskAndTtsVoices`)

Each session is a full assisted-capture session: face acquisition, 2.5 s passive window, `HOLD_STILL` carrier, the
spoken-code round, and the orchestrator's decision. The analyzer's code draw was scripted (`QueuedDigitsRandom`) so the
drawn code matches a pre-synthesised whole-utterance clip; production uses `SecureRandom`. Concatenating single-digit
TTS clips was tried first and abandoned: isolated Hindi digit words from Piper were often misheard, which was an
artefact of the test material.

| Language / voice | LIVE passed | STATIC_FACE | REPLAY_WRONG_CODE | DUBBED_SHIFTED (600 ms) | SILENCE |
|---|---|---|---|---|---|
| en / piper lessac | 6/6 | 0/3 | 0/3 | 0/3 | 0/3 |
| hi / piper pratham | 5/6 | 0/3 | 0/3 | 0/3 | 0/3 |
| te / piper venkatesh | 4/6 | 0/3 | 0/3 | 0/3 | 0/3 |
| gu / espeak gu | 0/6 | 0/3 | 0/3 | 0/3 | 0/3 |

Every failure ended as `FAILED(PAD_DETECTED)` with `vetoSignal=av_sync`. Every pass satisfied
`SpokenCodeRequirement`. The verdict always arrived before the 6.5 s carrier hold could complete (asserted). Verdicts
for live speakers came 2.5–4.0 s after the prompt, at the end of speech plus 700 ms.

## 6. Cost

Measured on this machine, single thread, `-Xmx512m`:

| Model | Load | Recognition of a 2.2–3.8 s clip (median / max) | Process RSS after load |
|---|---|---|---|
| en-us small | 1.1 s | 50 / 81 ms | ~310 MB |
| hi small | 0.8 s | 54 / 78 ms | ~340 MB |
| te small | 0.9 s | 69 / 75 ms | ~420 MB |
| gu small | 3.4 s | 54 / 105 ms | ~920 MB (the 332 MB model) |

The RSS figures are for the whole JVM process and accumulate as models load one after another. Lip-sync scoring is
under 1 ms per utterance.

## 7. Languages actually exercised

* **Exercised and working on synthetic neural speech:** English (US model) and Hindi.
* **Exercised, partly working:** Telugu, 13/24 bona fide on Piper voices; failures are almost all one dropped digit.
* **Exercised, not working as tested:** Gujarati, eSpeak test-quality voice only, 2/12; and the Indian English small
  model on these voices, 9/60.
* **Not exercised:** Tamil, Kannada, Malayalam, Marathi and Bengali (no Vosk small model), and any real human voice.

PLAN.md §17.8 asks for at least three Indian languages working. That is **not met**. Only Hindi works fully; Telugu
works partly and Gujarati does not work as tested.

## 8. Reproduce

```sh
# models (see liveness-av/models/README.md) and synthetic corpus
python3 -m venv ~/.cache/vivid-av/venv && ~/.cache/vivid-av/venv/bin/pip install piper-tts==1.8.0
~/.cache/vivid-av/venv/bin/python -I liveness-av/tools/synth_corpus.py --per-voice 12
# tests (the corpus evaluation is opt-in, about 3-5 minutes)
MAVEN_OPTS=-Xmx1g mvn -o -pl liveness-av install -DforkCount=1 -DargLine=-Xmx1g -Dvivid.av.eval=true
cat liveness-av/target/av-eval/results.md liveness-av/target/av-eval/e2e.txt   # tables above; trials.tsv has every trial
```

The corpus codes are drawn fresh on every synthesis run, so a regenerated corpus gives slightly different counts.
Mouth tracks and noise use fixed seeds, so the evaluation is nearly deterministic for a given corpus. Two runs differed in
one transcription, which moved one presentation between two rejection reasons; the accept/reject tables were identical.

## 9. What would make these numbers meaningful

1. Record consented residents reading codes in each language, in a realistic hall, through the actual Desktop webcam
   and microphone ([corpus process](../corpus-ethics.md)). Measure real lip–envelope peaks, then recalibrate
   `SyncConfig`.
2. Record attack presentations: a photo with a phone speaker, a tablet replay, and lips dubbed with a
   wav2lip-style model.
3. Measure the Desktop `mouthAspect` heuristic and MediaPipe `jawOpen` against speech, then choose `minMouthStd` per
   engine.
4. Try the Vosk big models, or another offline engine behind `DigitRecognizer`, for Telugu and Gujarati.
