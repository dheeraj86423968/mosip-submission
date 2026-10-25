# Change request: optional signed preview stream for SBI

**Spec:** MOSIP Secure Biometric Interface, 0.9.5 and the 1.x line
**Type:** additive, optional extension; no change to existing verbs, fields or behaviour
**Reference implementation:** `mosip-vivid`, `device-mock` (device side) and `device-sbi-stream` (client side)

## Problem

`RCAPTURE`/`CAPTURE` responses are signed by the device key, but the `/stream` preview is plain MJPEG. Liveness and
presentation attack detection must run on that preview, because a single still is not enough. So the frames that
decide whether a live person is present have no integrity or origin guarantee. A process on the client machine can
replace the stream, for example with a virtual camera, a proxy on 127.0.0.1, or a replayed recording. The signed
capture afterwards proves only that the device saw *something*.

## Proposal

1. **Advertise.** In the signed `deviceInfo` payload: `"streamSigning": {"supported": true, "alg": "vivid-sp-v1",
   "deviceKey": "<base64 SPKI P-256>"}`. On L1 devices `deviceKey` is the certified device key.
2. **Request.** The client adds `"signedPreview": {"alg", "clientKey", "clientNonce"}` to the STREAM body. It carries
   an ephemeral P-256 key and a 32-byte nonce.
3. **Authenticate the session.** The first multipart part (`X-SBI-Part: stream-init`) carries `sessionId`, the
   echoed `clientNonce`, a `deviceNonce`, and an ECDSA signature by the device key over
   `alg|sessionId|clientNonce|deviceNonce`.
4. **Key.** `HKDF-SHA256(clientNonce || deviceNonce, ECDH(client, device), "vivid-sp-v1 " + sessionId)`.
5. **Per frame.** Each frame carries `X-SBI-Frame-Seq`, `X-SBI-Frame-Time` and
   `X-SBI-Frame-Signature = HMAC-SHA256(key, seq|time|hex(SHA-256(jpeg)))`. The sequence must strictly increase.
6. **Bind to capture** (optional field). `RCAPTURE` may carry `streamSessionId`, echoed inside the signed capture
   response. This proves the captured image came from the same device session that passed liveness.

Devices that do not implement it keep working as today. Clients that do not request it see no difference.

## Security properties

| Attack | Result |
|---|---|
| Frame replaced or modified after the device signed it | HMAC mismatch |
| Frame replayed within a session | Sequence not increasing |
| Whole session replayed | Echoed `clientNonce` mismatch |
| Impostor service on the SBI port | stream-init signature fails against the device key |
| Unsigned frames injected into a signed stream | No signature; the client treats it as injection |

**Not covered:** a compromised device, or an attacker who holds the device private key (L1 secure elements address
this), and physical presentation attacks in front of a genuine sensor (PAD's job).

## Cost

One ECDH and one ECDSA operation per stream, one HMAC and SHA-256 per frame (microseconds), and about 150 header
bytes per frame.

## Evidence

- Two independent implementations interoperate over HTTP.
- `SignedPreviewTest` covers signing, pinning, and modified, replayed, stripped and impostor cases.
- `FrameSourceBridgeTest` shows an unsigned frame from a signing device ending the session as `ATTACK`/`INJECTION`.
- Full text: `docs/design/02-frame-streaming.md` §6.

## Open questions for the spec owners

- Should `deviceKey` be the existing device key or a dedicated stream key certified by it?
- Should the Android SBI transport (intent / content provider) carry the same headers in a sidecar?
- Algorithm agility: register `vivid-sp-v1` under a MOSIP name, e.g. `sbi-sp-1`.
