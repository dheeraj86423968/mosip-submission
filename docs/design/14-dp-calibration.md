# 14 — Privacy-preserving continual calibration

PLAN.md section 17.10: thresholds drift by centre, lighting and device. Instead
of shipping frames anywhere, each client contributes differentially private
score histograms through the existing sync, and a central job re-estimates the
threshold and ships a signed policy update.

**Status (24 Oct 2026).** Implemented and tested in process: the client side
in `telemetry` (`io.mosip.vivid.telemetry.calibration`), the central job and
the simulation in the new `calibration` module. **There is no MOSIP sandbox in
this environment**, so "the end-to-end loop running against a MOSIP sandbox"
from PLAN.md is not done. The loop runs in one JVM instead (section 10): real
accumulators, ledgers and `OfflineAuditQueue` files per simulated machine, a
JSONL export standing in for the MOSIP audit sync, the real job, and a client
verifying the result through the unchanged `PolicyLoader` and the MOSIP
config-properties path. No Registration Client integration calls the
accumulator yet; section 11 lists what that needs.

## 1. Data flow

```
Registration Client (per machine)                         Central (per country)
─────────────────────────────────                         ─────────────────────
session ends ──► CalibrationAccumulator.record(           audit store export (JSONL)
                   workflow, subjectKey,                          │
                   firstAttemptScore, label)                      ▼
                       │  bounded contributions            ReleaseReader  (dedupe at-least-once)
end of period ──► CalibrationReleaser.release()                   │
   1. PrivacyBudgetLedger.tryCharge  (fsync first)                ▼
   2. DpMechanism.privatize          (exact discrete noise) AggregatedHistogram (per centre × workflow)
   3. CALIBRATION_RELEASED audit records ─► OfflineAuditQueue ─► │
   4. reset period                       (existing audit sync)    ▼
                                                           ThresholdEstimator + CalibrationRules
policy sync ◄── <centre>.policy.envelope.json ◄── PolicyUpdater (validate, PolicySigner)
   │
   ▼
PolicyLoader.loadVerified (unchanged): signature VALID → applied; anything else → compiled defaults
```

## 2. What is collected, and what never is

Per workflow and release period, two histograms of the **first attempt's
fused passive liveness score** of each completed session:

* **`BONA_FIDE`**: the session was later confirmed live: its face capture was
  accepted into the registration (either because liveness passed, possibly on
  a retry, or because a supervisor attested an assisted capture after the
  attempts ran out, `onMaxRetries = ASSISTED_CAPTURE_WITH_SUPERVISOR`), and the
  decision was not overridden or flagged later (back-office adjudication,
  deduplication).
* **`REJECTED`**: every other completed session (locked, abandoned, refused by
  the supervisor, flagged later). A mixture of attacks and bona fide.

Why the first attempt, and why assisted captures count as bona fide: the
target is the first-attempt BPCER, and labels must not depend on the threshold
being calibrated. If only sessions that *passed* were labelled bona fide, the
histogram would be truncated at the threshold in force and the estimate would
always land above it, so the job would keep raising the threshold for the
centres that suffer most. The simulation measures this: with no
assisted-capture labels the threshold error is +8 BPCER points (section 9.3).

The score is binned on a fixed, public grid: 60 bins uniform in logit space
between z = −4 and z = 8 (scores 0.018 to 0.99966). Live subjects' fused scores
saturate close to 1 (0.99+ in the proxy evaluation), so a uniform grid on
[0, 1] would put nearly every bona fide sample into one bin.

**Never collected:** frames, embeddings, landmarks, per-signal scores,
identities, timestamps of individual sessions. Subject keys (the session id
for residents, a pseudonymous operator id for operator and supervisor
authentication) exist only as salted SHA-256 digests on the machine, with a
salt drawn per period and discarded at release; they bound contributions and
are never released. A release carries: release id, machine pseudonym, centre
id, mechanism and parameters, binning id, the threshold and policy version in
force, and the noisy counts.

## 3. Bounded contributions

Two bounds, enforced by `CalibrationAccumulator.record`:

| Bound | Default | Purpose |
|---|---|---|
| samples per subject per period, `m` | RESIDENT 1, OPERATOR 3, SUPERVISOR 3 | the L1 sensitivity the noise is scaled to. Operators authenticate many times a day, so their contribution must be capped per person, not per session. |
| samples per workflow per machine per period | 2,000 | limits how far one machine (or one compromised machine) can move a centre's estimate. |

Samples beyond a bound are dropped, counted locally, and never released.

## 4. Mechanism

`DpMechanism` adds independent integer noise to every count of every
histogram of a release:

* **Discrete Laplace (default), pure ε-DP.** P(X = x) ∝ exp(−|x|·ε/m).
* **Discrete Gaussian (option), ρ-zCDP.** P(X = x) ∝ exp(−x²/2σ²) with
  σ² = m²/2ρ, configured from a target (ε, δ) through
  ε = ρ + 2√(ρ ln 1/δ).

Both are sampled **exactly** (Canonne, Kamath and Steinke, NeurIPS 2020,
Algorithms 1 to 3) from uniform integers and rational arithmetic
(`ExactSampling`); no floating-point value is ever turned into noise, which
closes the floating-point side channel of textbook Laplace sampling (Mironov,
CCS 2012), and noisy counts are integers. Production passes a `SecureRandom`.
Writing the tests found one trap worth recording: drawing bounded integers
from the *low* bits of `java.util.Random` (which the simulation uses for
reproducibility) visibly skewed the discrete Laplace tails (P(−5) 0.0021
instead of 0.0031 over 400,000 draws); the sampler now takes the top bits, and
`CalibrationTelemetryTest` checks the shape, the variance and an empirical
e^ε likelihood-ratio bound.

### 4.1 Sensitivity and the privacy argument (sketch)

*Release.* For one workflow, a release is the vector of 2 × 60 counts
(label × bin). Every counted sample increments exactly one cell.

*Neighbouring inputs.* Two period datasets are neighbours if they differ by
all the sessions of one subject (add/remove). With at most `m` samples per
subject, the count vectors of neighbours differ by at most `m` in L1 norm (and
at most `m` in L2 norm, the worst case being all `m` samples in one cell).

*Laplace.* Adding independent discrete Laplace noise with scale m/ε to each
cell gives, for any output vector y and neighbours c, c′:
P[y | c] / P[y | c′] = Πᵢ exp(ε/m·(|yᵢ − c′ᵢ| − |yᵢ − cᵢ|)) ≤ exp(ε/m·‖c − c′‖₁) ≤ e^ε
by the triangle inequality. So each workflow release is ε-DP for every
subject. (Under replace-one adjacency the L1 distance is 2m and the guarantee
is 2ε.)

*Gaussian.* With L2 sensitivity Δ₂ ≤ m, the discrete Gaussian with
σ² = m²/2ρ is ρ-zCDP (CKS20, Theorem 4).

*Post-processing.* Everything the server does (summing, clamping, CDF
inversion, rules, signing) touches only noisy outputs and public
parameters, so it costs nothing further.

*What is not protected.* The bounds of section 3 must hold: a resident
enrolled twice in one period under two session ids contributes 2 samples.
That is rare for residents and is why operators are keyed by person. The
threshold and policy version in a release are public configuration; the
machine pseudonym and centre id are not personal data but do link a
machine's releases over time, which is why accounting is per machine
(section 5).

## 5. Epsilon accounting and the budget ledger

* **Per release.** Each release covers *all three* workflows, always, whether
  or not a workflow had sessions (so a release does not reveal which
  workflows a machine used). The ledger charges the three workflow releases
  by **sequential composition**: 3ε (or 3ρ). Residents of different workflows
  are mostly different people, so parallel composition would justify ε, but
  operators also enrol as residents, and the conservative charge costs only a
  constant factor.
* **Across releases.** Pure-DP charges add up; zCDP charges add up as ρ. The
  ledger enforces ε_total = Σεᵢ + ρ + 2√(ρ ln 1/δ) ≤ budget at δ = 10⁻⁶.
* **Lifetime budget, refuse when spent.** `PrivacyBudgetLedger.tryCharge` is
  called *before* any noise is drawn; it writes the entry to a temporary
  file, forces it to disk and atomically moves it over the ledger, so a crash
  after charging wastes budget but cannot spend it twice. A release id can be
  charged once (re-noising the same data would let a recipient average the
  noise away). When the next release would exceed the budget the ledger
  refuses; the releaser then emits only a `CALIBRATION_BUDGET_EXHAUSTED`
  record and **discards** the period's counts rather than keeping them for
  later. Renewing a budget is a policy decision (new operator pseudonyms, a
  new consent period) and is deliberately not made in code.
* **Defaults and the trade-off.** The demo uses ε = 1 per workflow (3 per
  release) and a lifetime budget of 6, so a machine contributes to two
  releases. A deployment that releases quarterly for a year would set the
  budget to 12. Residents appear in one period only, so for a resident the
  effective loss is that of one release (3ε at most); the lifetime figure is
  the bound for an operator who works at the machine throughout.

## 6. Central estimation

`ReleaseReader` reads `CALIBRATION_RELEASED` records from a JSONL export of
the audit store (one `AuditRecord.toJson()` per line), skips other events,
counts malformed lines, and **drops duplicates** of the same (machine,
release, workflow): the audit sync is at-least-once, and a duplicate would
double that release's noise and weight. `AggregatedHistogram` sums the noisy
counts per centre (or across all centres, `--group all`) and workflow, keeping
negative values (clamping before summing would bias every total upward), and
sums the per-bin noise variance.

`ThresholdEstimator` forms the bona fide CDF from the raw noisy cumulative
sums divided by the noisy total (both unbiased), makes it monotone with a
running maximum (post-processing), and inverts it at the target BPCER with
linear interpolation in logit space inside the crossing bin. Its standard
error combines the DP noise of the cumulative sum (k bins × per-bin noise
variance) with binomial sampling error.

## 7. From estimate to signed policy: rules, and the tightening-only principle

`CalibrationRules` (defaults in brackets):

1. **Evidence**: no change unless the centre has at least [300] noisy bona
   fide samples and the BPCER standard error is at most [0.01].
2. **Minimum change**: no change when the estimate is within [0.01] of the
   threshold in force; noise-level movements must not churn signed policies.
3. **Step**: one update moves the threshold by at most [0.05]; larger
   corrections take several periods, each with fresh evidence.
4. **Bounds**: clamp into the workflow's [floor, ceiling]: RESIDENT
   [0.60, 0.97], OPERATOR [0.70, 0.97], SUPERVISOR [0.75, 0.98].
5. **Tightening only unless authorised.** 07-configuration.md makes the
   signed document the floor and lets unsigned local properties only
   *tighten* it (for `passiveThreshold`, raise it). A calibration update is a
   signed document, so the letter of that rule does not forbid lowering the
   threshold, but lowering it is exactly the loosening the rule exists to
   keep out of automatic channels. The job therefore applies raises
   automatically and treats a decrease as a proposal (`LOOSENING_NOT_AUTHORISED`
   in the report) unless it runs with `--allow-loosening`, which an operator of
   the job passes after review. Even then it refuses (`LOOSENING_REFUSED_REJECTED_MASS`)
   when more than [10 %] of the sessions rejected today score between the new
   and the old threshold, because the attacks of the period are in that mass.
   Local tightening on a client still applies on top of the new document, as
   07-configuration.md specifies.

`PolicyUpdater` starts from the policy in force, **refuses if it does not
verify**, changes only `workflows.<WF>.passiveThreshold` and `version`
(`<old>+cal.<tag>.<centre>`), runs the same `PolicyValidator` the clients run,
and signs with `PolicySigner` (the code behind `PolicyKeyTool sign`). Clients
need nothing new: `PolicyLoader.loadVerified` accepts it when the signature is
valid and falls back to the compiled defaults otherwise. One signed document
per centre assumes the config server can deliver centre-specific values
(MOSIP global parameters can be overridden per centre or machine); with
`--group all` one document serves the country.

CLI: `calibration` module, `io.mosip.vivid.calibration.CalibrationJobMain`
(usage in its Javadoc); it writes `<centre>.policy.envelope.json` for every
centre with a change, plus `calibration-report.json` and `.md` with every
decision and its reasons.

## 8. Threat model

| Adversary | What they can do | Mitigation | Residual |
|---|---|---|---|
| Curious central operator or anyone who reads the audit store | Read every machine's release | ε-DP per release per subject, lifetime ledger per machine, nothing but noisy counts released | A resident's presence changes the released counts by at most e^ε in likelihood; group privacy degrades linearly with group size |
| Network observer of the sync | Same as above, plus timing | Releases happen at period end for all workflows regardless of activity | The number of releases per machine is visible |
| Compromised registration machine | Fabricate histograms, e.g. many low "bona fide" scores to pull a threshold down | Per-machine sample cap, centre aggregation over many machines, tightening-only default, step limit, floors, rejected-mass guard, human review of any loosening | A machine can still shift its centre's estimate by up to cap / total; the server does not yet clip or robustly aggregate per-machine histograms (median-of-means, trimmed sums) |
| Attacker who wants a centre's threshold lower to let prints through | Poison labels (get attacks accepted and unflagged) | Same as above; attacks accepted unflagged are exactly what PAD and adjudication exist to stop, and the simulation includes them (2 % attacks, half of the accepted ones never flagged) | Labels are only as good as adjudication |
| Tampering with the update in transit | Change the threshold | ECDSA signature over the canonical document; `PolicyLoader` falls back to compiled defaults on any failure (shown in section 10, step 6) | Replay of an older, validly signed policy: `PolicyLoader` does not enforce version monotonicity (pre-existing; not introduced here) |
| Theft of the policy signing key | Sign anything | Out of scope here; same key management as every policy (MOSIP keymanager) | — |
| Re-randomisation (asking a client to release the same period twice) | Average noise away | Release ids are charged once; counts are discarded after a release | — |

## 9. Simulation

`CalibrationSimulationMain` (calibration module). Synthetic centres apply a
lighting/device transform z′ = a·z + b in logit space to the **real fused
passive scores of the proxy evaluation** (39 adults in openly licensed video
and 195 digitally simulated attacks, `calibration/src/main/resources/proxy-eval-passive-scores.csv`,
scores only), smoothed with a Gaussian kernel (bandwidth 0.35 logits). Each
machine runs 400 resident sessions per period through the real retry and
labelling logic: per-subject level plus per-attempt noise (SD 0.5 logits), 3
attempts at the threshold in force (0.80), challenges passed with probability
0.97 (bona fide) and 0.05 (attacks), 2 % attack sessions, half of the
accepted attacks never flagged, 90 % of bona fide residents who fail every
attempt accepted through supervisor-assisted capture. Each machine's
histograms are privatised with the production `DpMechanism`, summed, and
estimated with the production `ThresholdEstimator`. Error is measured against
the centre's true first-attempt 5 % quantile t*, both as a threshold
distance and as the BPCER actually obtained at the estimate. 20 repetitions ×
6 centres per cell, seed 20261024, about 5 s.

### 9.1 Centres

| Centre | Condition | Logit transform | True t* (BPCER 5 %) | True BPCER at 0.80 |
|---|---|---|---|---|
| C1 | reference (proxy-eval conditions) | 1.00·z +0.0 | 0.7268 | 5.7 % |
| C2 | dim indoor lighting | 0.90·z −1.2 | 0.4254 | 11.2 % |
| C3 | strong backlight | 0.85·z −2.0 | 0.2372 | 15.8 % |
| C4 | low-end USB webcam | 0.95·z −0.7 | 0.5447 | 8.5 % |
| C5 | bright, good camera | 1.05·z +0.4 | 0.8082 | 4.9 % |
| C6 | outdoor camp, mixed light | 0.80·z −1.6 | 0.3121 | 14.4 % |

C1 reproduces the proxy evaluation's measured BPCER at 0.80 (5.7 %).

### 9.2 Threshold recovery versus ε and number of clients

Each cell: mean |t̂ − t*| in score units / mean |BPCER(t̂) − 5 %| in
percentage points. ε is per workflow and release (the ledger charges 3ε per
release).

| ε \ machines per centre | 1 | 5 | 10 | 25 | 50 | 100 |
|---|---|---|---|---|---|---|
| 0.1 | 0.340 / 6.1 | 0.274 / 3.9 | 0.221 / 3.5 | 0.159 / 2.3 | 0.126 / 1.7 | 0.102 / 1.3 |
| 0.25 | 0.286 / 4.8 | 0.155 / 2.3 | 0.128 / 1.7 | 0.094 / 1.1 | 0.068 / 0.7 | 0.065 / 0.7 |
| 0.5 | 0.181 / 2.8 | 0.111 / 1.4 | 0.095 / 1.1 | 0.067 / 0.7 | 0.058 / 0.6 | 0.053 / 0.5 |
| 1 | 0.121 / 1.5 | 0.063 / 0.7 | 0.057 / 0.6 | 0.051 / 0.5 | 0.049 / 0.5 | 0.050 / 0.5 |
| 2 | 0.103 / 1.3 | 0.061 / 0.6 | 0.056 / 0.5 | 0.051 / 0.5 | 0.051 / 0.5 | 0.051 / 0.5 |
| ∞ (no noise) | 0.094 / 1.1 | 0.059 / 0.6 | 0.056 / 0.5 | 0.052 / 0.5 | 0.051 / 0.5 | 0.052 / 0.5 |

Reading it:

* **BPCER is the meaningful column.** Threshold distances look large (0.05
  even without noise) because the bona fide distribution of the proxy data is
  flat around its 5 % quantile (two of 39 clips scored 0.5, "no evidence"), so
  many thresholds give almost the same BPCER.
* **The noise floor is reached at ε = 1 with 5 to 10 machines per centre**
  (0.6 to 0.7 points of BPCER), at ε = 0.5 with about 25 machines, at
  ε = 0.25 with about 50. ε = 0.1 is not usable at centre level even with 100
  machines (1.3 points) and needs country-level aggregation.
* **The remaining 0.5 points are label bias, not noise**: 10 % of the bona
  fide residents who fail every attempt give up rather than take the assisted
  capture and land in `REJECTED` (9.3).
* **The evidence gate does its job**: it rejected 100 % of centre releases at
  ε = 0.1, and at ε = 0.5 those from up to 10 machines (78 % at 10); at
  ε = 1 only a single machine is refused (and 3 % at 5 machines). Signed
  bias stays within +1 point everywhere (`target/calibration-simulation/simulation.md`
  has the full table, including p90 errors).

### 9.3 Label selection bias (no noise, 100 machines per centre)

| Share of all-attempts-failed bona fide residents accepted by assisted capture | mean \|t̂ − t*\| | mean \|BPCER(t̂) − 5 %\| | mean signed BPCER error |
|---|---|---|---|
| 0.0 (only liveness passes count as bona fide) | 0.376 | 8.0 pp | +8.0 pp |
| 0.5 | 0.218 | 3.3 pp | +3.3 pp |
| 0.9 (default) | 0.051 | 0.5 pp | +0.5 pp |
| 1.0 | 0.009 | 0.1 pp | +0.0 pp |

This is the most important result of the simulation. Labelling only sessions
that passed liveness as bona fide biases every estimate above the threshold in
force, by 8 BPCER points here, and the job would then *raise* the threshold
for the dark centres that already reject too many residents. The bias is gone
only when sessions whose capture was accepted by other means are labelled bona
fide too. Any deployment must get these labels right before trusting the
loop.

### 9.4 What the job decides (ε = 1, 25 machines per centre, one draw)

| Centre | t* | estimate | true BPCER at estimate | default (tightening-only) | with `--allow-loosening` |
|---|---|---|---|---|---|
| C1 | 0.7268 | 0.7648 | 5.3 % | LOOSENING_NOT_AUTHORISED, stays 0.800 | LOOSEN → 0.765 |
| C2 | 0.4254 | 0.4886 | 5.5 % | LOOSENING_NOT_AUTHORISED, stays 0.800 | LOOSEN → 0.750 (step) |
| C3 | 0.2372 | 0.2533 | 5.2 % | LOOSENING_NOT_AUTHORISED, stays 0.800 | LOOSEN → 0.750 (step) |
| C4 | 0.5447 | 0.6188 | 5.5 % | LOOSENING_NOT_AUTHORISED, stays 0.800 | LOOSEN → 0.750 (step) |
| C5 | 0.8082 | 0.8553 | 5.5 % | TIGHTEN → 0.850 (step) | TIGHTEN → 0.850 (step) |
| C6 | 0.3121 | 0.3090 | 5.0 % | LOOSENING_NOT_AUTHORISED, stays 0.800 | LOOSEN → 0.750 (step) |

The estimates themselves land within half a BPCER point of target at every
centre. The rules then decide how much of that to act on: the bright centre
is tightened automatically, the dark ones only with review and one 0.05 step
per period, and never below the 0.60 floor. Whether thresholds as low as
0.24 (C3) are acceptable is a security decision the data cannot make: at that
point PAD rests on the challenges, which in the proxy evaluation is where it
rested anyway.

## 10. End-to-end loop, in process

`CalibrationLoopDemo` (also run as `EndToEndCalibrationLoopTest`): two
centres of 30 machines, 400 resident sessions each, ε = 1 per workflow,
lifetime budget 6. Output of the last run:

```
[1] policy in force builtin-1.0, signed with ops-2026
[2] 60 clients released 180 CALIBRATION_RELEASED records (epsilon 1 per workflow, 3 per release) into their offline audit queues
[3] audit sync export: 183 lines (3 re-delivered)
read 180 releases (3 duplicates dropped, 0 malformed, 0 budget-exhausted notices) into 6 groups
  10011-dim      RESIDENT   clients=30   bonaFide=11660    current=0.800 estimate=0.4789 -> LOOSENING_NOT_AUTHORISED           proposed=0.800
  10011-dim      OPERATOR   clients=30   bonaFide=-337     current=0.850 estimate=NaN -> UNCHANGED_INSUFFICIENT_EVIDENCE    proposed=0.850
  10011-dim      SUPERVISOR clients=30   bonaFide=244      current=0.900 estimate=0.2756 -> UNCHANGED_INSUFFICIENT_EVIDENCE    proposed=0.900
  10012-reference RESIDENT   clients=30   bonaFide=11684    current=0.800 estimate=0.8151 -> TIGHTEN                            proposed=0.815
  ...
  signed 10012-reference.policy.envelope.json (version builtin-1.0+cal.2026Q4.10012-reference)
  (second run, --allow-loosening)
  10011-dim      RESIDENT   ... estimate=0.4789 -> LOOSEN    proposed=0.750
  signed 10011-dim.policy.envelope.json (version builtin-1.0+cal.2026Q4.10011-dim)
[4] tightening-only run emitted nothing for 10011-dim: true; reviewed run emitted 10011-dim.policy.envelope.json
[5] client at 10011-dim: signature VALID, fallback false, version builtin-1.0+cal.2026Q4.10011-dim, RESIDENT passiveThreshold 0.750 (was 0.800)
[6] tampered copy: signature INVALID, fallback true, RESIDENT passiveThreshold 0.800
[7] machine 10011-dim-m0: 1 further release(s) accepted, then refused; epsilon spent 6.0 of 6.0
```

The OPERATOR and SUPERVISOR rows show what pure noise looks like: no sessions
were simulated for them, their noisy totals are around zero (−337, 244), and
the evidence gate refuses them. Step 5 delivers the update the way MOSIP
delivers config, as `mosip.registration.liveness.policy.json` and
`.policy.signature` properties through `PropertiesPolicyAdapter`, and
loads it with the unchanged `PolicyLoader.loadVerified`.

## 11. Limits and next steps

* **No MOSIP sandbox.** The audit sync is a JSONL file and the config
  delivery is a `Properties` object. Against a sandbox the releases would ride
  the Registration Client audit sync to the audit service, and the job would
  read an export of it.
* **Not wired into the clients yet.** The integrations must call
  `CalibrationAccumulator.record` when a session's outcome is final (after
  supervisor attestation or back-office adjudication, which for flags can be
  days later), persist the accumulator with `toJson()` next to the offline
  audit queue, and call `CalibrationReleaser.release` on a fixed schedule.
* **Labels.** The whole method depends on labelling assisted-capture
  acceptances as bona fide (9.3). Late flags from adjudication have to reach
  the machine before its period is released, or the period must be released
  late.
* **Base data.** The simulation's base distribution is 39 real bona fide
  clips and digitally simulated attacks, and the centre shifts are invented.
  It shows that the estimator, the noise and the rules behave; it does not
  predict field thresholds.
* **Poisoning.** The server sums histograms as they arrive; a compromised
  machine can move its centre's estimate by up to its sample cap. Per-machine
  L1 clipping and robust aggregation are the next step.
* **Policy replay.** Version monotonicity is not enforced by `PolicyLoader`
  (pre-existing).
* **Only the passive threshold.** `padHardThreshold`, per-signal weights and
  challenge timing windows are not calibrated; the same histograms of
  per-signal scores could support that at proportionally higher privacy cost.

## 12. Files and commands

| Path | What |
|---|---|
| `telemetry/src/main/java/io/mosip/vivid/telemetry/calibration/` | `ScoreBinning`, `CalibrationLabel`, `CalibrationAccumulator`, `ExactSampling`, `DpMechanism`, `PrivacyBudgetLedger`, `CalibrationReleaser` (Java 11, ships in the clients) |
| `telemetry/.../audit/LivenessAuditEvent.java` | new events `CALIBRATION_RELEASED`, `CALIBRATION_BUDGET_EXHAUSTED` |
| `calibration/` (new module, Java 17) | `ReleaseReader`, `ClientRelease`, `AggregatedHistogram`, `ThresholdEstimator`, `CalibrationRules`, `PolicyUpdater`, `CalibrationJobMain`, `CalibrationSimulation(Main)`, `CalibrationLoopDemo` |
| `telemetry/src/test/.../calibration/CalibrationTelemetryTest.java` | sampler exactness, e^ε ratio check, caps, ledger, release through `OfflineAuditQueue` |
| `calibration/src/test/...` | estimator, rules, reader, updater, simulation smoke test, end-to-end loop |

```sh
MAVEN_OPTS=-Xmx1g mvn -o -pl telemetry,policy,calibration -am install -DforkCount=1
cd calibration
CP=$(mvn -o -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)
java -cp target/classes:$CP io.mosip.vivid.calibration.CalibrationSimulationMain --reps 20   # writes target/calibration-simulation/
java -cp target/classes:$CP io.mosip.vivid.calibration.CalibrationLoopDemo /tmp/vivid-loop
```
