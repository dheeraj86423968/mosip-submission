# Corpus ethics and evaluation protocol

This document describes how the team collects, protects, uses and deletes a
small consented corpus for presentation attack detection (PAD) evaluation.
It also covers how the corpus becomes ISO/IEC 30107-3 figures through
`testkit`. `PLAN.md` §4.5, `docs/design/09-security-privacy.md` §4 and
`docs/design/10-iso-30107-alignment.md` refer to it.

It has two parts. §1–§9 cover the protocol and ethics: who takes part,
what is recorded, consent, storage and deletion. §10–§12 cover the
evaluation and its reporting. §13 is a one-page runbook for a
2–3 hour evaluation session.

Templates:

* `docs/corpus/consent-form.md`: participant information sheet and
  consent form. Print one per participant.
* `docs/corpus/capture-log-template.csv`: one row per recorded item.
  Copy it into the encrypted corpus volume. A filled log never goes in
  the repository.

> **Status, 24 Oct 2026.** This protocol has been written but not yet run.
> No corpus exists, and no APCER/BPCER figure in this repository comes
> from real people. When a figure is published, it will be labelled as a
> self-evaluation on this protocol (see `10-iso-30107-alignment.md` §5–§6).

---

## 1. Scope and purpose

| Item | Decision |
|---|---|
| Purpose | Evaluate the mosip-vivid PAD subsystem against Level 1 and, partly, Level 2 presentation attacks. Optionally fine-tune S1–S3, but only with a separate consent tick (§4). |
| Size | 10–50 adult participants. Ten is enough for a smoke evaluation. The PLAN §4.5 target is 20 or more. |
| Not in scope | Accredited laboratory testing, conformance claims and any public release of the data. |
| What is shared | Only aggregate metrics (`iso30107.json`, `iso30107.html`, the slice tables in §11). Raw items, frames and the filled capture log are never shared. |
| Legal frame | India's Digital Personal Data Protection Act 2023 (DPDP), which requires consent that is free, specific, informed, unconditional and unambiguous and that can be withdrawn as easily as it was given. It also applies the institution's ethics or research committee rules. Teams in other countries apply their local equivalent, such as GDPR Art. 9 explicit consent for biometric data. |
| Approval | Where the college has an Institutional Ethics Committee (IEC/IRB), submit this document and the consent form before the first session, and record the reference number in the capture log header. Where it has none, a faculty mentor signs off on the protocol, and that fact is stated in the report. |

## 2. Roles

| Role | Responsibility | Who |
|---|---|---|
| Data controller | Accountable for the corpus. Signs the deletion record. | Team lead, or the faculty mentor where the institution requires it |
| Corpus custodian (2 people) | Each holds one half of the access arrangement (§7). Performs ingest and deletion. Keeps the access log. | Two named team members |
| Capture operator | Runs the session, operates the camera and attack instruments, and fills the capture log | Any team member trained on §13 |
| Consent taker | Explains the information sheet, answers questions and countersigns | Must not be the participant's supervisor, teacher or manager |

## 3. Participants

* **Adults only.** Every participant must be 18 or older, checked by
  self-declaration on the consent form. **Minors are excluded** even with
  parental consent. DPDP §9 adds verifiable parental-consent obligations
  for children's data, and the evaluation gains nothing from them that
  justifies the risk.
* **Voluntary participation.** Team members may take part. Recruiting
  juniors, students the team grades or tutors, or employees of a team
  member's family business is not allowed, because those people cannot
  freely say no. Snacks or refreshments are fine. Payment that depends on
  completing every attack is not.
* **Capacity.** Do not enrol anyone who cannot understand the information
  sheet in a language they read. Translate the sheet, or have it read
  aloud and record that in the log.
* **Diversity targets.** These are aims, not quotas, and they serve the
  fairness reporting in §11.4:
  * skin tone spread across the Monk Skin Tone (MST) scale, recorded as
    three bands (MST 1–3, 4–7, 8–10) by self-selection against the
    printed scale;
  * age bands 18–29, 30–49 and 50+;
  * eyewear (none, glasses), head covering (none, any) and facial hair
    (none, any).
  
  Gender is optional and self-described. It is used only as a slice and
  never inferred.
* **Exclusions.** Exclude anyone under 18, anyone who has not signed, and
  anyone who withdraws (§6). Also exclude anyone who asks not to be filmed
  in the presence of others; offer them a private slot instead.

## 4. Consent

Consent is written, informed and **granular**. The form
(`docs/corpus/consent-form.md`) has separate tick boxes, and each box is
optional unless marked as required:

1. *(required)* Recording of my face on video for PAD evaluation.
2. *(required)* Making attack instruments from my image: prints, screen
   replays and a paper mask. I understand they will be destroyed on the
   day.
3. *(optional)* Using my recordings to **train or fine-tune** models. If
   unticked, my items go only into the held-out evaluation split.
4. *(optional)* Making a **deepfake / face-swap** from my own video for
   evaluation only. If unticked, no synthetic media of me is created.
5. *(optional)* Recording my demographic attributes (skin tone band, age
   band, eyewear, head covering, facial hair, gender) for fairness
   reporting.
6. *(optional)* Being contacted again for a follow-up session.

The consent taker reads the information sheet with the participant and
waits for questions. Signing must never be the participant's first look
at the form, so the sheet is given at least 10 minutes before signing.
The participant keeps a copy that shows their **participant ID** and the
withdrawal contact.

Paper forms carry the participant's real name and signature, so they are
the only document linking a name to an ID. They are kept in a locked
drawer or envelope held by the data controller and are **never** scanned
into the corpus volume, a phone or the cloud folder. A scan is allowed
only into a separately encrypted archive held by the data controller.

## 5. Attack instruments (PAI species)

Every label below is used verbatim in the capture log and as the `label`
field of `scores.jsonl` (§10). The engine attack class is the coarser
class the engine reports (`10-iso-30107-alignment.md` §1). The attack
potential follows the 30107-3 factors (elapsed time, expertise, knowledge
of the target, equipment and window of opportunity) using the levels of
`10-iso-30107-alignment.md` §4.

| PAI species label | Instrument | How to make it | Engine class | Attack potential | Priority |
|---|---|---|---|---|---|
| `PRINT_A4_MATTE` | A4 colour print on plain 80 gsm paper | Frame from the participant's bona fide clip, printed at full page on an office inkjet or laser printer | PRINT | Level 1 (minutes, no expertise, office printer) | must |
| `PRINT_GLOSSY_PHOTO` | 6×4 in or A5 glossy photo print | Same frame on glossy photo paper | PRINT | Level 1 | must |
| `PRINT_CUTOUT_EYES` | A4 matte print with the eyes cut out, held so the attacker's real eyes show through (blinks pass) | Scissors or a craft knife | PRINT | Level 1 (upper) | must |
| `SCREEN_PHONE_PHOTO` | Still photo shown on a phone at full brightness | Photo transferred to a team phone, displayed full screen | SCREEN_REPLAY | Level 1 | must |
| `SCREEN_TABLET_PHOTO` | Still photo on a tablet | As above | SCREEN_REPLAY | Level 1 | should |
| `SCREEN_LAPTOP_PHOTO` | Still photo on a laptop screen | As above | SCREEN_REPLAY | Level 1 | should |
| `VIDEO_REPLAY_PHONE` | The participant's bona fide clip played on a phone | Clip played full screen in a loop | VIDEO_REPLAY | Level 1 | must |
| `VIDEO_REPLAY_TABLET` | The same clip on a tablet | As above | VIDEO_REPLAY | Level 1 | should |
| `VIDEO_REPLAY_LAPTOP` | The same clip on a laptop or monitor | As above | VIDEO_REPLAY | Level 1 | should |
| `MASK_PAPER` | Rigid paper mask: the A4 matte print glued to card, cut to the face outline with eye holes, worn by another team member | Card, glue, elastic | MASK | Level 1–2 (low end of Level 2) | should |
| `MASK_SILICONE` | Commercial or hobby silicone mask | Bought or cast. Not made of the participant without the optional consent | MASK | Level 2 | stretch |
| `DEEPFAKE_SELF_SWAP` | Face-swap video generated **only** from the participant's own clips with an offline open-source tool, then replayed on a screen | Offline only. Never uploaded to an online service | DEEPFAKE | Level 2–3 | stretch, needs consent box 4 |

Rules for instruments:

* Instruments are made **from that participant's own bona fide capture**,
  on the day, on team devices. No photo is taken from social media or
  sent by the participant.
* Every print and paper mask is **cross-cut shredded before the session
  ends**. Screen instruments are deleted from the display device in front
  of the participant, including any "recently deleted" or trash folder.
  The capture log records each destruction (`instrument_destroyed`
  column).
* The attacker for `PRINT_CUTOUT_EYES` and `MASK_PAPER` is a consenting
  team member. Their bona fide items are recorded under their own
  participant ID.
* Public research datasets such as OULU-NPU, SiW, CASIA-FASD and
  CelebA-Spoof may be used for **evaluation only, where their licence
  allows**. They are never used for shipped weights, never copied into the
  corpus volume and never committed (PLAN §4.5).

## 6. Withdrawal

* Participants may withdraw at any time without giving a reason, by
  telling the consent taker on the day or by emailing or messaging the
  withdrawal contact printed on their copy, quoting their participant ID.
* On withdrawal the custodians delete every item with that ID from the
  corpus volume and from any derived `scores.jsonl` **within 7 days**,
  confirm it to the participant and record it in the deletion log (§8).
  Any model fine-tuned with those items (consent box 3) is either
  retrained without them or deleted.
* Aggregate reports already published are not recalled, because they
  contain no personal data and one person cannot be extracted from them.
  Every later report excludes the withdrawn participant. The consent form
  says this.
* Withdrawal of the optional boxes alone is allowed. For example,
  withdrawing demographics deletes those columns for that ID and keeps the
  recordings.

## 7. Data minimisation, storage and encryption

**Collect less.**

* Record video only. **Disable audio** on the recording app, or strip it
  on ingest (`ffmpeg -i in.mp4 -an -c:v copy out.mp4`).
* Keep each clip to 10 s (bona fide) or 10 s per attack take. Recording
  continuously through a session is not allowed.
* Strip metadata on ingest so that no GPS, device serial or owner name
  remains (`ffmpeg ... -map_metadata -1`; check with `exiftool`).
* Never write names in the corpus. Files carry only the pseudonymous item
  ID (§10.1). Demographics are coarse bands (§3) and are stored only in
  the capture log, not in file names.
* Do not frame other people, ID documents, name badges or screens showing
  personal information.

**Store safely.**

* The corpus lives on **one encrypted volume**: a LUKS-encrypted USB SSD
  (Linux) or a VeraCrypt container (Windows/macOS), with a passphrase of
  at least 6 random words. Optionally use a 2-of-2 scheme, where each
  custodian holds half of the passphrase.
* Capture phones are temporary. At the end of each participant's slot,
  move the clips over USB cable to the encrypted volume, check the file
  count against the capture log, then delete them from the phone
  **including the "recently deleted" album**. Cloud photo backup (Google
  Photos, iCloud, OneDrive) and messaging apps are **switched off** on
  capture phones for the whole day. Check this before the first
  participant arrives.
* Frames extracted for the mock device (§10.2) are written **only inside
  the encrypted volume**. No copies go to `/tmp`, the Downloads folder or
  a synced folder.
* An access log (`access-log.csv` on the volume: date, person, purpose)
  records every time the volume is mounted.
* The volume is never attached to a CI runner. The CI job
  (`.github/workflows/ci.yml`, `iso-report`) runs only on a
  self-hosted runner controlled by a custodian, or not at all.

**Never in the repository.**

* No face image, frame, video, embedding or filled capture log is ever
  committed. `.gitignore` already excludes `corpus/`, `*.mp4` and
  `*.avi`. Before every push, the custodian runs
  `git ls-files | grep -Ei '\.(mp4|avi|mov|jpg|jpeg|png|webp|heic|npy|npz)$'`
  and reviews each hit. The only images allowed are the UX mockups in
  `docs/ux/` (vector drawings, no photographs) and the synthetic renderer
  output in `testkit`. This check is also listed in
  `docs/submission/submission-checklist.md`.
* `scores.jsonl` contains only pseudonymous IDs and numbers. It is still
  kept on the volume, because the IDs map to people through the paper
  forms. Only `iso30107.json`, `iso30107.html` and the slice tables in
  §11 leave the volume.

## 8. Retention and deletion

| Data | Retention | Deletion method |
|---|---|---|
| Prints, glossy photos, paper masks | Session day only | Cross-cut shredder before the participant leaves |
| Clips on capture phones and display devices | Until the end of that participant's slot | App delete plus "recently deleted" purge, checked by a second person |
| Corpus volume (clips, extracted frames, `scores.jsonl`, capture log) | Until **31 January 2027** (end of the Decode evaluation plus about 90 days), unless the participant withdraws earlier | Delete the volume's encryption key (`cryptsetup luksErase` or delete the VeraCrypt container), then reformat the drive |
| Fine-tuned weights (consent box 3 only) | Until the shipped bundle is superseded | Deleted with the bundle. Weights are not personal data in themselves, but they are treated as derived data |
| Paper consent forms | **3 years**, as evidence of consent for the institution's ethics record | Shredding |
| Aggregate reports | Indefinite | Not personal data |
| Deletion log (`deletion-log.csv`: date, what, method, two signatures) | Kept with the consent forms | — |

Extending retention past 31 January 2027 needs **fresh consent**. A new
protocol version alone is not enough.

## 9. Security incident

If a clip, a frame or the capture log is found outside the encrypted
volume (a phone gallery, a laptop's Downloads folder, a chat, a commit),
the custodians:

1. delete it (and, for a commit, rewrite history and force-push
   immediately, then ask GitHub support to purge cached views);
2. record the incident;
3. tell the affected participant(s) within 72 hours;
4. where DPDP or institutional rules require it, notify the Data
   Protection Board or the ethics committee.

---

## 10. From recordings to ISO/IEC 30107-3 metrics

### 10.1 Item naming

```
<participant>-<label>-<lighting>-<device>-T<take>
P07-VIDEO_REPLAY_PHONE-L2-D1-T1
```

* `participant`: `P01`–`P50`.
* `label`: `BONA_FIDE` or a PAI species from §5.
* `lighting`: `L1` even office light, `L2` window or backlight, `L3` dim
  (one lamp).
* `device`: `D1` laptop webcam, `D2` Android phone front camera, `D3` SBI
  device (if available).
* `take`: from 1.

### 10.2 Scoring each item

`IsoReportMain` scores nothing itself. It reads one liveness score per
presentation. The score is `LivenessResult.fusedLivenessScore` in [0, 1],
where 1 means live, from one full session per item. There are two ways to
produce it:

* **Replay a recording (desktop engine), with `CorpusScoreMain`.** Extract
  frames inside the volume, one folder per item named by its item ID:
  `ffmpeg -i P07-...mp4 -an -map_metadata -1 -vf fps=15 frames/P07-.../%05d.jpg`.
  Optionally add `timestamps.txt` (one `<millis>` per frame, or
  `<file> <millis>` lines). Without it, frames are spaced at `--fps`
  (default 15). Copy the capture log to `capture-log.csv` next to
  `frames/`. Then run `io.mosip.vivid.testkit.corpus.CorpusScoreMain`
  (§10.3). It replays every item through the real `liveness-core`
  orchestrator and the real ONNX engine, one session per item, first
  attempt only. A fake clock is advanced to each frame's timestamp, so
  passive windows and timeouts behave as in real time. When the recording
  ends before a decision, the clock keeps running until the session times
  out, which is recorded as a non-response. This measures **passive** PAD. A
  recorded clip cannot answer randomly drawn challenges, so the default
  `--mode passive` runs the workflow's default policy with `active.mode`
  set to `NEVER` (`07-configuration.md`). `--mode challenge` sets it to
  `ALWAYS`, which shows only that prompt-ignoring replays fail; bona fide
  recordings fail too in that mode. Report which mode was used.
* **Live presentation at the capture device.** The participant, or the
  attacker holding the instrument, faces the running client. The session
  result is logged directly. This is the end-to-end figure (passive plus
  active) and the closest to 30107-3 testing "through the production
  interface". It needs a working client build on the capture device,
  which is currently the Android app (source only, not yet compiled) or
  the desktop client with an SBI device.

Either way, `scores.jsonl` on the volume has one line per presentation.
`CorpusScoreMain` writes it for replays. For live presentations, write the
same fields from each `LivenessResult`:

```json
{"id":"P07-BONA_FIDE-L1-D1-T1","subject":"P07","label":"BONA_FIDE","score":0.91,"decision":"LIVE","failure":null,"response":true,"groups":{"skinToneBand":"4-7","ageBand":"18-29","eyewear":"none","headCovering":"none","facialHair":"none","lighting":"L1","device":"D1"}}
{"id":"P07-VIDEO_REPLAY_PHONE-L2-D1-T1","subject":"P07","label":"VIDEO_REPLAY_PHONE","paiClass":"VIDEO_REPLAY","score":0.18,"decision":"ATTACK","failure":"PAD_DETECTED","response":true,"groups":{"lighting":"L2","device":"D1"}}
{"id":"P07-BONA_FIDE-L3-D1-T1","subject":"P07","label":"BONA_FIDE","score":0.5,"decision":"NOT_LIVE","failure":"FACE_NOT_DETECTED","response":false,"groups":{"lighting":"L3","device":"D1"}}
```

| Field | Meaning |
|---|---|
| `label` | **required.** `BONA_FIDE` (synonyms `LIVE`, `BONAFIDE`) or a §5 PAI species. Any other label is treated as an attack species and upper-cased. |
| `score` | `LivenessResult.fusedLivenessScore`. Required when `response` is true. |
| `decision`, `failure` | `LivenessResult.decision` and `failure` (null when none). A response rejected for any reason other than `LIVENESS_BELOW_THRESHOLD` (a hard PAD veto, a failed or timed-out challenge, identity drift) is classified as rejected at every threshold: `IsoReportMain` scores it 0, because the fused score is computed before a veto is applied and can be high. |
| `response` | `false` marks a **non-response**: the session produced no PAD decision. When the field is absent, it is derived from `decision`/`failure`. `decision = ERROR`, or a failure of `FACE_NOT_DETECTED`, `MULTIPLE_FACES`, `POOR_FACE_QUALITY` (failure to acquire), `SESSION_TIMEOUT`, `DEVICE_UNAVAILABLE`, `DEVICE_CONNECTION_FAILURE`, `CAMERA_UNAVAILABLE`, `INVALID_FRAME_DATA` or `ENGINE_ERROR`, is a non-response. Everything else is a response, including `PAD_DETECTED`, `LIVENESS_BELOW_THRESHOLD`, `CHALLENGE_TIMEOUT`, `ACTIVE_CHALLENGE_FAILED` and `IDENTITY_DRIFT`. Non-responses count towards APNRR/BPNRR only, never towards APCER/BPCER. Lines with `decision` or `failure` `CANCELLED` (operator abort) are excluded from every rate and counted in the report context. |
| `subject` | Participant ID. Defaults to the part of `id` before the first `-`. Used only to count participants per slice. |
| `groups` | Slice tags. `CorpusScoreMain` always writes `lighting` and `device`. It adds `skinToneBand` (`mst_band`), `ageBand` (`age_band`), `gender` (`gender_self`), `eyewear`, `headCovering` (`head_covering`) and `facialHair` (`facial_hair`) **only when `consent_demographics` is `Y`**. These keys are also accepted at the top level of a line. |
| `paiClass`, `detectedAttackClass`, `vetoSignal`, `signals`, `frames`, `framesFed`, `framesAnalyzed`, `mode`, `policy`, `engine`, `bundle` | Diagnostics written by `CorpusScoreMain`. `signals` maps each signal id to the attack probability the session result reports for it (`LivenessResult.signalScores`). `IsoReportMain` ignores them. |

Older files with only `label`, `score` and `id` still load. Every line in
those files counts as a response.

### 10.3 Running `CorpusScoreMain` and `IsoReportMain`

Both CLIs are classes in the `testkit` module (`testkit/README.md`,
`docs/testing/test-plan.md` §6):

```bash
# once: build testkit and resolve its classpath
mvn -q -pl testkit -am -DskipTests install
mvn -q -pl testkit dependency:build-classpath -Dmdep.outputFile=/tmp/testkit.cp
CP=testkit/target/classes:$(cat /tmp/testkit.cp)

# replay every item (frames/<item id>/ + capture-log.csv on the volume) -> scores.jsonl
java -Xmx2g -cp "$CP" io.mosip.vivid.testkit.corpus.CorpusScoreMain \
  --corpus /media/corpus --out /media/corpus/scores.jsonl \
  --models liveness-engine-onnx/models --mode passive --workflow RESIDENT

# whole corpus
java -cp "$CP" \
  io.mosip.vivid.testkit.metrics.IsoReportMain \
  --scores /media/corpus/scores.jsonl \
  --out    /media/corpus/reports/all \
  --threshold 0.5 \
  --title "mosip-vivid self-evaluation, corpus v1, desktop ONNX, passive" \
  --context corpus=v1 --context subjects=20 --context engine=onnx \
  --context bundle=<bundle version> --context policy=<policyVersion> \
  --context commit=$(git rev-parse --short HEAD) --context mode=passive-replay
```

| Option | Meaning |
|---|---|
| `--scores <file.jsonl>` | required; one presentation per line, `#` comments allowed |
| `--out <dir>` | required; receives `iso30107.json` and `iso30107.html` |
| `--threshold <t>` | accept as live when `score >= t`; default 0.5. Use the workflow's `passiveThreshold` under test (resident 0.80, operator 0.85, supervisor 0.90 by default) |
| `--title <text>` | report title |
| `--context key=value` | repeatable; written into the report header. Always give corpus version, engine, bundle, policy, commit and mode |
| `--max-acer <x>` | exit code 1 if ACER > x (regression gate) |
| `--min-subjects <n>` | participants needed before a group slice is shown; default 5, and lower values are refused (§11.4) |
| `--no-slices` | leave out the per-group slices |

Exit codes: 0 for success, 1 when ACER is above `--max-acer`, 2 for a
usage or input error. `CorpusScoreMain` options are listed in
`docs/testing/test-plan.md` §6. It exits with 3 when no model directory is found.
On 24 Oct 2026 the chain `CorpusScoreMain` → `IsoReportMain` was checked
end to end on a synthetic corpus rendered by `device-mock` (cartoon faces,
no real people), with the real orchestrator and ONNX engine. A test does the
same (`CorpusScoreMainTest`).

The CI job `iso-report` (`.github/workflows/ci.yml`) runs the same two
commands. It runs only when model files and a corpus directory are present
on the runner. Otherwise it skips with a notice. It uploads only
`iso30107.json` and `iso30107.html`, never `scores.jsonl`.

## 11. What is reported

### 11.1 Metrics and how each is obtained

| Metric | Source | Notes |
|---|---|---|
| **APCER per PAI species** | `apcerBySpecies` in `iso30107.json` (`apcer`, `count`, `errors` and `ci95` per species) | One row per §5 label that was actually collected. Species with fewer than 10 presentations are marked "indicative". |
| **Worst-case APCER** | `maxApcer`, `worstSpecies`, `maxApcerCi95` | Headline figure, as 30107-3 asks. Reported next to its species. |
| **APCER per engine class / attack potential level** | Run `IsoReportMain` again on a copy of `scores.jsonl` with labels mapped to the §5 engine class (the `paiClass` field), or to `L1` and `L2`. A one-line `jq` or `sed` does this. | Lets the reader see "Level 1: worst APCER x %, Level 2: y %". |
| **BPCER** | `bpcer`, `bpcerErrors`, `bpcerCi95` | Overall, and per slice (`slices`, §11.4). |
| **ACER** | `acer` = (max APCER + BPCER) / 2 | Reported, never used alone. |
| **BPCER@APCER ≤ 5 %, ≤ 10 %** | `bpcerAtApcer5`, `bpcerAtApcer10`, and the thresholds `bpcerAtApcer5Threshold`, `bpcerAtApcer10Threshold` | `null` when no threshold reaches the target. Report it as "not reached". |
| **EER, DET curve** | `eer`, `eerThreshold`, `detCurve`, and the SVG in `iso30107.html` | |
| **APNRR / BPNRR** | `apnrr`, `bpnrr`, and `nonResponse` (`attack`, `bonaFide`, `bySpecies`: `rate`, `count`, `of`, `ci95`) | Non-responses ÷ attempted presentations. Non-responses are the lines with `response: false` (§10.2). They are excluded from APCER/BPCER. |
| **95 % confidence intervals** | `ci95` / `…Ci95` fields everywhere a rate is reported (Wilson score interval, z = 1.96), and the "95% CI" columns in `iso30107.html` | Computed by `IsoReportMain` (`Rate.wilson95()`) |
| **Processing time** | `LatencyBenchmark` (`testkit/README.md`) | p50 and p95 per platform |

For example, 3 errors out of 40 presentations is reported as 7.5 %
[2.6 %, 19.9 %]. With 20 participants, one take per species and one lighting condition,
each species has n = 20. A result of 0 errors gives an upper 95 % bound of
about 16 %. Say so in the report: a small corpus cannot demonstrate low
error rates. It can only fail to find high ones.

### 11.2 Presentation versus transaction level

Report the first attempt only (presentation level) as the main table.
Where live sessions with retries were run, add a second table that counts
an attack as successful if any attempt up to `maxRetries` was accepted
(transaction level). See `10-iso-30107-alignment.md` §3.

### 11.3 Report template

```
mosip-vivid PAD self-evaluation (not an accredited laboratory test)
Corpus v1: 20 participants (adults, consented, protocol docs/corpus-ethics.md),
  3 lighting conditions, devices D1/D2, collected <date>
Product: commit <sha>, engine <id>, bundle <ver>, policy <ver>, threshold 0.50,
  mode <passive-replay | live end-to-end>

| PAI species          | Level | n  | APCER  | 95 % CI        |
|----------------------|-------|----|--------|----------------|
| PRINT_A4_MATTE       | 1     | 60 | x.x %  | [ , ]          |
| ...                  |       |    |        |                |
| worst-case           |       |    | x.x %  | (species)      |

BPCER x.x % (n = , CI [ , ]);  ACER x.x %;  BPCER@APCER5 x.x %;  BPCER@APCER10 x.x %
APNRR x.x %;  BPNRR x.x %;  EER x.x % at threshold t
Per-slice BPCER: table §11.4
Limitations: subject-disjoint split yes/no; sample size; species not collected.
```

### 11.4 Demographic fairness reporting

* One `IsoReportMain` run produces every slice. `CorpusScoreMain` copies
  the slice tags (MST band, age band, eyewear, head covering, facial hair,
  gender where given, lighting, capture device) from the capture log into
  each line's `groups`. The report's `slices` section (JSON) and its
  "Per-group slices" tables (HTML) give, for every tag value, BPCER and
  BPNRR over its bona fide lines and APCER per species over its attack
  lines. Per-slice APCER counts get very small, so publish it only for
  `PRINT_A4_MATTE` and `VIDEO_REPLAY_PHONE`.
* Each slice is reported as **BPCER, n presentations, n participants and the
  Wilson 95 % CI**. It also has `bpcerRatioToLowest`, the ratio of the slice's BPCER to
  the lowest slice BPCER for the same tag, so that disparity is visible (the
  "differential performance" idea of ISO/IEC 19795-10). `moreThanTwiceLowest`
  (⚑ in the HTML) marks the slices that the next bullet but one requires
  you to name.
* **Slices with fewer than 5 participants are suppressed by the tool.** A
  suppressed slice keeps only its value and `"suppressed": true`, with no
  counts or rates. `--min-subjects` can raise the floor but cannot lower it.
  To report such a group, merge it into a neighbouring band in the capture
  log before scoring, or report "< 5, suppressed". This protects against
  re-identification and against noise.
* Overlapping intervals are **not** evidence of fairness at this sample
  size. The report must say the corpus is too small to establish parity
  and must name any slice whose point estimate is more than twice the
  lowest.
* Demographic attributes are used only for this table. They never become
  model inputs, and they are never used to pick thresholds per group.
* The inclusion mitigations (accessibility pool, assisted capture,
  `09-security-privacy.md` §4) are noted next to the table.

### 11.5 Train/test separation

Use a subject-disjoint split. Participants who ticked box 3 can be in the
training or calibration split. Participants who did not tick it are always
in the evaluation split. The evaluation split is never used for training,
calibration or threshold selection. The report states the split (for
example "P01–P12 train, P13–P20 evaluation").

## 12. Before publishing any figure

- [ ] Every figure comes from a named `iso30107.json` with its context
      block (commit, engine, bundle, policy, mode).
- [ ] It is labelled "self-evaluation" and states no conformance claim
      (`10-iso-30107-alignment.md` §6).
- [ ] The sample sizes and intervals are shown next to the rates.
- [ ] No slice with fewer than 5 participants appears.
- [ ] The report contains no per-participant rows, IDs or images.
- [ ] The report goes to the cloud folder (submission checklist), not to
      the repository, if it contains corpus details beyond aggregates.

---

## 13. Evaluation day runbook (2–3 hours, 10–12 participants)

**Kit.** A laptop with webcam (D1). One or two Android phones (D2), one
for capture and one as the replay screen. A tablet (optional). A colour
printer with A4 plain and glossy photo paper. Scissors, a craft knife,
card, glue and elastic. A cross-cut shredder. A desk lamp, and a window
or a second lamp for backlight. The encrypted USB SSD. Printed consent
forms (two copies per person), the printed MST scale and the printed
capture log. A pen.

**T – 1 day.**
1. Get IEC or mentor sign-off (§1). Book a room with a closable door.
2. Prepare the encrypted volume. Copy in `capture-log-template.csv` as
   `capture-log.csv` and fill the header (date, protocol version, approval
   reference).
3. On capture phones, switch off cloud photo backup and messaging media
   auto-save, and test that the "recently deleted" purge works.
4. Assign the roles: one consent taker, one capture operator, one
   instrument maker, and two custodians, who can double up with the other
   roles.

**T – 30 min.** Set up the stations and check the lighting conditions
L1, L2 and L3 with a team member. Test the USB transfer to the volume. Put
the shredder next to the printer.

**Per participant (about 12 minutes, two run in parallel).**

| Min | Step | Who |
|---|---|---|
| 0–3 | Give the information sheet and answer questions. Sign the form with its tick boxes. Assign the next ID `Pnn`. The participant takes their copy. The participant self-selects demographics (box 5) only if they ticked it. | consent taker |
| 3–5 | **Bona fide**: 10 s clip on D1 and on D2 under L1, looking at the camera, one blink, small head turn. Repeat under L2 and L3 if time allows. Log each row. | capture operator |
| 5–7 | **Make instruments** from the L1 D2 clip: one A4 matte print, one glossy print and one cut-out. Load the photo and clip onto the replay phone or tablet. | instrument maker |
| 7–11 | **Attacks**: present each instrument to D1 and D2 for 10 s each, in the order `PRINT_A4_MATTE`, `PRINT_GLOSSY_PHOTO`, `PRINT_CUTOUT_EYES` (team member as attacker), `SCREEN_PHONE_PHOTO`, `VIDEO_REPLAY_PHONE`, then `MASK_PAPER` if time allows. Move the instrument slightly as an attacker would. Log each row. | capture operator |
| 11–12 | **Destroy**: shred all prints in front of the participant. Delete the photo and clip from the replay devices and purge the recycle bin. Copy the clips from the capture phones to the volume, check the count against the log, delete them from the phones and purge. Tick `instrument_destroyed` and `phone_purged`. | instrument maker + custodian |

Expected yield for 10 participants: about 40 bona fide clips (2 devices ×
2 lighting conditions) and about 100 attack clips (5 species × 2
devices).

**Close (20 min).**
1. Check that the count of files on the volume equals the count of log
   rows.
2. Do a final purge of every phone and tablet. A second person checks the
   galleries.
3. Shred the leftover paper. Lock the consent forms away.
4. Unmount the volume and record the session in `access-log.csv`.

**Within a week.** Strip audio and metadata (§7), extract frames, score
with `CorpusScoreMain` (§10.2), run `IsoReportMain` (§10.3), and write the report (§11.3) with
the fairness table (§11.4). Publish aggregates only. Put a calendar
reminder for the 31 January 2027 crypto-erase (§8).
