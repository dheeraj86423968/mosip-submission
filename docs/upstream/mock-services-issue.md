# Issue draft: face streaming in MockMDS for liveness development

**Repository:** `mosip/mosip-mock-services` (MockMDS, tested at v1.2.0.2)

We ran a liveness client against MockMDS 1.2.0.2. Discovery, device info, `RCAPTURE` and face `/stream` all
interoperate. A few behaviours differ from what an SBI 0.9.5 client expects, and the face stream cannot exercise
liveness. Details are in `docs/testing/interop-mockmds.md`.

## Observations

1. **Non-standard stream framing.** The stream is HTTP/1.0 with no length framing, and the delimiter is written as
   `--BoundaryString` while the header declares `boundary=--BoundaryString`. RFC 2046 makes the delimiter
   `----BoundaryString`. Part headers have no space after the colon, and an extra blank line follows each JPEG.
2. **Refusals return HTTP 200.** Errors (111 busy, 605 unknown device) come back as HTTP 200 with a JSON body.
3. **Extra response after timeout.** When the stream request times out, a second HTTP response with error 609 is
   appended to the same socket.
4. **Static frames.** The face stream repeats one still image, so liveness and PAD cannot be exercised.
5. **Hash chain differs from the spec text.** The `RCAPTURE` hash chain is computed over raw digest bytes, which
   differs from the hex-string reading of the spec text. Clients should follow MockMDS. Documenting this in the spec
   would help.

## Offer

`mosip-vivid`'s `device-mock` is an MPL-2.0 SBI-compatible face device. We would contribute any of these to
MockMDS:

- synthetic face scenarios: live, print, screen, replay, multiple faces, no face;
- fault injection: disconnect, stall, corrupt frame, latency;
- the optional signed preview-stream extension.
