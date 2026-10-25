---
name: Bug report
about: Something behaves differently from the design docs or the API contract
title: "[module] short description"
labels: bug
assignees: ''
---

**Do not file PAD bypasses or other security issues here. See SECURITY.md.**

### Module and version

Module (e.g. `liveness-core`, `device-sbi-stream`), commit hash or release.

### Workflow and policy

RESIDENT / OPERATOR / SUPERVISOR; policy values that matter (threshold,
active mode, challenge pool, retries). Attach the effective policy JSON if
possible (no secrets).

### Expected behaviour

Cite the document or API Javadoc that defines it.

### Actual behaviour

Session states observed (`SessionState.toString()` output is ideal), failure
reason, audit events.

### Reproduction

A `device-mock` scenario file is the best reproduction. Otherwise: device,
OS, lighting, steps.

### Logs

Scores and decisions only. Never attach frames or images of people.
