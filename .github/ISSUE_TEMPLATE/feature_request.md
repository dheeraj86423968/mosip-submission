---
name: Feature request
about: A new signal, challenge, adapter, policy option or integration
title: "[module] short description"
labels: enhancement
assignees: ''
---

### Problem

What cannot be done today, and for which workflow (resident capture,
operator auth, supervisor auth).

### Proposal

How it would work. For a new signal: attack classes targeted, inputs needed
from `FrameAnalysis`, how it is calibrated. For a new challenge: detector
criteria, anti-replay requirement, whether it `requiresMotorAbility`. For an
adapter: the device, its SBI spec version and certification level.

### SPI impact

Does this require a change to `liveness-api` or `device-api`? Those are
frozen; explain why the change cannot be made behind the existing interfaces.

### Evaluation

How would we know it works? (ISO 30107-3 metric, conformance test, scenario.)
