# Contributing to mosip-vivid

Thank you for considering a contribution. This project aims to be merged into
the MOSIP Registration Clients, so we hold it to the standards a MOSIP
maintainer would apply to a pull request against `mosip/registration-client`.

## Ground rules

* **Licence.** All contributions are made under the Mozilla Public License 2.0
  (`LICENSE`). By opening a pull request you confirm you have the right to
  contribute the code under that licence. Model weights must carry an
  OSI-approved or equivalent permissive licence and a `LICENSE-*.txt` next to
  the file; research-only datasets and weights are never committed.
* **Code of conduct.** See `CODE_OF_CONDUCT.md`.
* **Security.** A PAD bypass, a way to skip a challenge, or a way to make a
  session report `PASSED` without satisfying policy is a security
  vulnerability. Do not open a public issue; follow `SECURITY.md`.
* **No biometric data in the repository.** No face images, videos or
  embeddings of real people, not even your own, outside the consented corpus
  process described in `docs/design/09-security-privacy.md`. Test fixtures use
  synthetic or clearly licensed material.

## Repository layout and ownership

The repository is a Maven multi-module build. `liveness-api` and `device-api`
are the two SPIs; they are **frozen**: changing a public signature there needs
an issue first, because every engine, adapter and client integration depends
on them. The other modules are described in the root `README.md`.

## Development workflow

1. Fork and branch from `main`. Branch names: `feat/<area>-<short>`,
   `fix/<area>-<short>`, `docs/<short>`.
2. Build and test locally: `mvn -B -ntp verify` (JDK 17 or newer).
3. If you touch `liveness-core`'s orchestrator or the policy schema, re-run
   the TLA+ model (`formal/README.md`) and update the spec if the behaviour
   changed on purpose. CI runs TLC and will fail otherwise.
4. If you touch an engine signal, run the engine golden tests and, when models
   are available, the `testkit` ISO 30107-3 report; paste the APCER/BPCER
   table in the pull request.
5. Open a pull request using the template. Keep it focused: one behaviour per
   PR.

## Coding conventions

* Main code of the modules that ship in a client (liveness-api, device-api, telemetry, policy,
  model-bundle, liveness-core, device-sbi-stream, liveness-engine-onnx, integration-desktop) is
  Java 11 (`--release 11`): no records, switch expressions, `instanceof` patterns or Java 12+ APIs.
  Android also uses liveness-api, device-api, liveness-core, policy and telemetry; in those, avoid APIs
  missing on Android API 28, such as `InputStream.readAllBytes()` and `String.isBlank/strip/repeat`.
  Tests and the tooling modules use Java 17. `liveness-api` and `device-api` must stay dependency-free.
* Immutable DTOs, builders where there are more than four fields,
  `Objects.requireNonNull` on constructor inputs.
* Engines score; the orchestrator decides. Do not add decision logic (policy,
  thresholds, retries) to an engine or adapter.
* Never log frames, embeddings or anything derived from pixels at a level
  above `TRACE`, and never in production builds. Scores, decisions, hashes and
  timings are fine.
* Public API has Javadoc. Internal code has comments where the *why* is not
  obvious.
* Tests: JUnit 5, AssertJ. Orchestrator changes need a state-machine test;
  adapter changes need to pass the `testkit` conformance suite.

## Commit messages

Conventional style, imperative mood, scoped by module:

```
core: fail active challenge when action onset precedes prompt
device-mock: add scenario step for stalled stream
docs: ISO 30107-3 alignment, attack potential table
```

Reference issues with `Closes #123` in the body.

## Documentation

Design documents live in `docs/design/` and are Markdown with Mermaid
diagrams so GitHub renders them. If a change alters behaviour that a document
describes, update the document in the same PR. Everything in the docs is
either implemented or explicitly marked *as designed*; keep that distinction
honest.
