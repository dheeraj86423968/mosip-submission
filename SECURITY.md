# Security policy

mosip-vivid is a presentation attack detection (PAD) subsystem for a national
identity registration client. Weaknesses in it have direct consequences for
the integrity of an identity register, so we treat the following as security
vulnerabilities, not as ordinary bugs:

* any way to obtain a `LivenessResult` with `decision = LIVE` or a
  `SessionState` with `phase = PASSED` while the policy predicates are not
  satisfied (see `formal/README.md` for the exact predicates);
* a presentation attack instrument (print, screen, replay, mask, deepfake)
  that passes the engine at a rate materially above the rates in the published
  evaluation report, or that passes consistently in a specific condition;
* a way to predict, influence or replay the active challenge sequence;
* a way to pass liveness on the preview stream and present a different face
  to the signed `rcapture` call (identity-continuity bypass);
* a way to load an unsigned or tampered model bundle or policy document, or
  to disable liveness through configuration without an audit event;
* leakage of frames, embeddings or other biometric data to disk, logs or the
  network from any module;
* audit records that can be altered without changing `evidenceHash`.

## Reporting

Please **do not** open a public GitHub issue for any of the above.

Use GitHub's private vulnerability reporting on this repository ("Security"
tab, "Report a vulnerability"). If that is unavailable, contact a maintainer
listed in the repository's GitHub organisation directly and ask for a secure
channel. Include:

* the module and version (commit hash) affected;
* a description of the attack instrument or input and the conditions
  (lighting, device, workflow, policy);
* a reproduction: a scenario file for `device-mock`, a corpus sample you are
  entitled to share, or step-by-step instructions;
* the impact as you understand it.

We will acknowledge within 5 working days, keep you informed, and credit you
in the advisory unless you prefer otherwise.

## Disclosure

We follow coordinated disclosure. We ask for 90 days from acknowledgement to
ship a fix and a regression test before public disclosure, shorter if a fix is
simple. Because this project targets MOSIP, confirmed issues that affect a
MOSIP release will also be reported to the MOSIP security process so that
adopting countries are informed through their normal channel.

## Scope notes

* Research-only datasets are never used for shipped weights; a finding that a
  shipped model was trained on such data is also in scope.
* Findings against the `device-mock` or `attack-lab` tools themselves (for
  example a path traversal in a scenario loader) are in scope but lower
  severity, since they are development tools.
* Vulnerabilities in upstream MOSIP components should be reported to MOSIP.
