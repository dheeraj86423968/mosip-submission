## What

<!-- One paragraph: what behaviour changes and why. Link the issue. -->

Closes #

## Modules touched

- [ ] liveness-api / device-api (frozen SPI; needs an issue and maintainer sign-off)
- [ ] liveness-core (orchestrator / fusion / challenges)
- [ ] policy
- [ ] telemetry
- [ ] liveness-engine-* (which signal?)
- [ ] model-bundle
- [ ] device-* adapters
- [ ] testkit / attack-lab
- [ ] integration-desktop / integration-android
- [ ] docs / formal / CI

## Checklist

- [ ] `mvn -B -ntp verify` passes locally
- [ ] New or changed behaviour has a test (unit, scenario or conformance)
- [ ] If the orchestrator or policy schema changed: `formal/LivenessOrchestrator.tla` updated and TLC still passes
- [ ] If an engine signal changed: golden tests updated; ISO 30107-3 metrics table pasted below when models were available
- [ ] No frames, embeddings or personal data added to the repository
- [ ] No decision logic added to an engine or device adapter
- [ ] Design docs under `docs/design/` updated if this changes documented behaviour
- [ ] User-visible strings go through i18n keys and follow the UX spec (`docs/design/12-ux-spec.md`)

## Evaluation impact (engine changes only)

| Instrument | APCER before | APCER after |
|---|---|---|
| | | |

BPCER before / after:

## Screenshots (UI changes only)

