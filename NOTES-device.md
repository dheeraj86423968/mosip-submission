
## device-mock / device-sbi-stream / testkit (agent notes, 2026-10-24)

Nothing here blocks the work. device-api and liveness-api were not modified. These are points for the owners of the contract:

1. **Info verb name.** SBI 0.9.5 and MOSIP's MockMDS use `MOSIPDINFO /info`, not `MOSIPINFO`, which is how PLAN §3.1 and the brief spell it. `device-sbi-stream` sends `MOSIPDINFO`; `device-mock` accepts both. PLAN.md should be corrected.
2. **Unknown resolution.** `DeviceCapabilities` cannot say "resolution not yet known". The builder default is 640x480, which would be a lie for SBI. `SbiStreamFrameSource.capabilities()` reports `resolution(0, 0)` and `nominalFps 0` until the first frame, then the observed values. The conformance test only compares dimensions when they are > 0. Consider documenting 0 as "unknown" in `DeviceCapabilities`.
3. **capabilities() on an absent device.** It throws `DeviceException(UNAVAILABLE)` because the SPI has no other way to signal "no device". The SPI Javadoc should say so.
4. **SBI 1.0 (`POST /rcapture` etc.)** is not implemented, only the 0.9.5 custom verbs. See device-sbi-stream/README.md.
