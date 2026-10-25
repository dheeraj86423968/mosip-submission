---
name: Device adapter conformance report
about: Results of running the testkit conformance suite against a FaceFrameSource implementation
title: "[conformance] <vendor> <model>"
labels: interop
assignees: ''
---

### Device

Vendor, model, SBI spec version, certification level (L0/L1), firmware.

### Adapter

`adapterId()`, module or external repository, commit.

### Capabilities reported

Paste `DeviceCapabilities` (resolution, fps, formats, depth, IR, signsFrames).

### Conformance results

Paste the `testkit` conformance summary (cadence, format honesty, fault
signalling, reconnect).

### Notes

Anything the adapter cannot honour from `StreamOptions`.
