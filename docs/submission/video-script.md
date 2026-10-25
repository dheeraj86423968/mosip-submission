# Video script — 4 minutes

Format: screen recording with voice-over; one presenter; captions burned
in for every state change so the jury can follow without audio. Record at
1080p, 30 fps. Timings are targets; the total must not exceed 5:00.

Setup before recording: `device-mock` running with the demo corpus;
desktop client (or the console demo) started with the RESIDENT policy;
`attack-lab` open on the second half of the screen; Wi-Fi on for the
first three minutes.

| Time | Beat | On screen | Voice-over |
|---|---|---|---|
| 0:00–0:20 | Problem | Title card, then a still of a printed photo held to a face device | "MOSIP Registration Clients capture a face through a certified device, and the device signs the image. But nothing checks whether a live person was there. A printed photo, a phone screen or a replayed video can enrol. This is problem statement 04, and this is mosip-vivid." |
| 0:20–0:45 | Architecture in 25 s | The five-box diagram; highlight boxes as named | "Two small interfaces: a frame source for any device, and a liveness engine that only scores. The orchestrator in the middle decides: passive liveness first, active challenges when needed, policy per workflow, audit for everything. The same code runs on desktop and Android, and nothing on the hot path touches the network." |
| 0:45–1:20 | Live pass (resident) | Start capture. Caption each state: *Connecting*, *Positioning* (hint "Move closer"), *Checking face liveness…*, challenge card "Please turn your head to the left" with countdown and dots, "Action detected", "Please blink", *Good, face captured successfully*. Audit pane shows `SESSION_STARTED`, `PASSIVE_BELOW_THRESHOLD`, `CHALLENGE_ISSUED(TURN_LEFT, nonce…)`, `CHALLENGE_PASSED(640 ms)`, `SESSION_PASSED` | "A resident under poor light. Passive liveness lands below threshold, so the client issues challenges it chose itself with a secure random generator; the nonce is in the audit trail. Head turn gives parallax; blink gives timing. Passed. Then the signed capture is checked against the session's face before the packet accepts it." |
| 1:20–1:45 | Attack Lab beat 1: printed photo | In `attack-lab`, pick *Print A4* for the same subject; it streams through the mock device. Client shows *Checking face liveness…* then "Face verification could not be completed. Please try again." Audit pane: `PAD_DETECTED(PRINT, 0.12, cnn_texture)` | "Attack Lab replays a printed photo through the mock device, which speaks the SBI protocol like a real one. The texture signal vetoes within the passive window. The resident sees a generic message; the audit record has the class, the score and the signal that caught it." |
| 1:45–2:10 | Attack Lab beat 2: screen replay | Pick *Phone screen (photo)*. Rejection; audit `PAD_DETECTED(SCREEN_REPLAY, …, moire)`; diagnostics panel briefly shows the per-signal bars with moiré high | "A photo on a phone screen. The moiré detector sees the pixel grid; colour statistics agree. Diagnostics, which only a supervisor can open, shows each signal; the resident never sees this." |
| 2:10–2:40 | Attack Lab beat 3: video replay | Pick *Video replay (tablet)*. Passive is borderline; challenge "Please smile" issued; the replayed video smiles at the wrong time; `ACTIVE_CHALLENGE_FAILED` / `CHALLENGE_TIMEOUT`; retry counter 1 of 3 | "A recorded video of the same person. It moves, it blinks, it may even smile, but it cannot answer a prompt it has never seen. The timing window fails it. Retry is offered; the retry budget is enforced." |
| 2:40–3:05 | Attack Lab beat 4: identity swap | Pick *Swap at capture*: live accomplice passes challenges, the mock's `rcapture` returns a different person. Client: session `PASSED`, then capture rejected; audit `IDENTITY_DRIFT(stage=CAPTURE)` | "The subtle one. The preview stream is not signed by today's SBI devices. An accomplice passes liveness, and a different face is presented to the signed capture. Identity continuity compares the session's face to the signed image and rejects. Our proposal for signed preview frames would close this at the device; the repository has the spec." |
| 3:05–3:25 | Offline | Turn Wi-Fi off on screen (OS indicator). Run a live resident capture again: identical states; status strip shows *Offline*; audit queue counter increments | "Now with the network off. Same behaviour, same states, same audit, queued for the next sync. Policy and models were already local and signed." |
| 3:25–3:45 | Operator and supervisor | Quick cut: operator login with "Please smile" (ALWAYS mode); supervisor auth with two challenges; the max-retry lock screen on a third failed attempt | "Operators and supervisors go through the same engine with stricter policies: always a challenge, higher threshold, fewer attempts, lock and alert on exhaustion." |
| 3:45–4:00 | Proof and close | TLC output (`No error has been found`, state counts); README status table; team card | "The state machine is modelled in TLA+ and checked in CI: no path reaches PASSED without the policy's conditions. Everything is MPL-2.0, built as modules MOSIP can merge. Thank you." |

Captions to burn in (exact copy): "Checking face liveness…", "Please turn
your head to the left", "Action detected", "Please blink", "Good, face
captured successfully", "Face verification could not be completed. Please
try again.", "We could not verify face liveness. Please try again.",
"Offline".

Do not show: raw per-signal scores to the resident view; any real
resident's face (use team members under the corpus consent or synthetic
subjects); any claim of certification.
