# Models for `liveness-engine-onnx`

The `.onnx` files in this directory are **not committed** (see `../.gitignore`). Run the script below or
install a signed model bundle (`model-bundle`). Every model has an OSI-approved licence, and the licence
text is kept next to it.

| File | Role | Size | SHA-256 | Licence | Source |
|---|---|---|---|---|---|
| `face_detection_yunet_2023mar.onnx` | Face detection + 5 landmarks (YuNet, fixed 1x3x640x640 BGR input) | 232,589 B | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` | MIT (`LICENSE-yunet.txt`, Copyright 2020 Shiqi Yu) | https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet |
| `antispoof_minifasnetv2se_128.onnx` | S1 CNN anti-spoofing, MiniFASNetV2-SE, 1x3x128x128 RGB/255, logits `[real, spoof]`, square crop 1.2x the box (1.5x before November 2026) | 1,912,594 B | `af2381b88f38769222ed93379e12444e2a50814575de1c46170de570c55a42b6` | Apache-2.0 (`LICENSE-antispoof.txt`) | https://github.com/facenox/face-antispoof-onnx, file `models/best/98.20/best_model.onnx` (architecture from minivision-ai Silent-Face-Anti-Spoofing, Apache-2.0) |
| `face_recognition_sface_2021dec.onnx` | S7 identity embedding (SFace, MobileFaceNet-style, 1x3x112x112 RGB 0..255, 128-d) | 38,696,353 B | `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` | Apache-2.0 (`LICENSE-sface.txt`) | https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface |

Total: about 40.8 MB. That is slightly above the 40 MB target. The int8 SFace variant (9.9 MB) was tried first,
but it is 3x slower on x86 CPUs: 50-60 ms against 16-20 ms. The export lists its weights as graph inputs, so ONNX
Runtime cannot constant-fold them. The int8 file still works with the engine if disk matters more than latency.

## Download

```sh
cd liveness-engine-onnx/models
curl -L -o face_detection_yunet_2023mar.onnx  https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx
curl -L -o antispoof_minifasnetv2se_128.onnx  https://github.com/facenox/face-antispoof-onnx/raw/main/models/best/98.20/best_model.onnx
curl -L -o face_recognition_sface_2021dec.onnx https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx
sha256sum -c <<'SUMS'
8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4  face_detection_yunet_2023mar.onnx
af2381b88f38769222ed93379e12444e2a50814575de1c46170de570c55a42b6  antispoof_minifasnetv2se_128.onnx
0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79  face_recognition_sface_2021dec.onnx
SUMS
```

## Optional: original Silent-Face models (converted, not enabled by default)

| File | Role | Size | SHA-256 | Licence | Source |
|---|---|---|---|---|---|
| `2.7_80x80_MiniFASNetV2.onnx` | S1, MiniFASNetV2, 1x3x80x80 **BGR, raw 0..255**, 3 logits, real at index 1, Silent-Face crop at 2.7x | 1,743,495 B | `1cb86b65b7e204950a10a868049e1012c0892f6f908dc93587f29cf3b3794419` | Apache-2.0 (`LICENSE-silentface.txt`, Copyright 2020 Minivision) | converted from `resources/anti_spoof_models/2.7_80x80_MiniFASNetV2.pth` (SHA-256 `a5eb02e1843f19b5386b953cc4c9f011c3f985d0ee2bb9819eea9a142099bec0`) |
| `4_0_0_80x80_MiniFASNetV1SE.onnx` | S1, MiniFASNetV1SE, same recipe, crop 4.0x | 1,742,663 B | `0a4539e34fd8428967614df33657edbc8ffc0404049104a22dc7eb1589f378a9` | Apache-2.0 (`LICENSE-silentface.txt`) | converted from `resources/anti_spoof_models/4_0_0_80x80_MiniFASNetV1SE.pth` (SHA-256 `84ee1d37d96894d5e82de5a57df044ef80a58be2b218b5ed7cdfd875ec2f5990`) |

Upstream: https://github.com/minivision-ai/Silent-Face-Anti-Spoofing, commit `b6d5f04ad78778917853b25c778acef6d5626d15`
(GitHub reports the licence as Apache-2.0; the `LICENSE` file is copied here as `LICENSE-silentface.txt`). No ONNX
export with checkable provenance was found, so the official PyTorch weights were converted with
[`tools/proxy-eval/convert_silentface.py`](../../tools/proxy-eval/convert_silentface.py) (torch 2.15.0+cpu, onnx
1.23.2, opset 13; ONNX Runtime matches PyTorch to 1e-7 on random inputs; the export is byte-for-byte reproducible).

Two preprocessing details matter and are now in the `MINIFASNET` preset (`AntiSpoofModelConfig`):
* upstream `to_tensor` does **not** divide by 255 (`inputScale = 1`); the earlier preset divided by 255;
* upstream crops the box scaled in each dimension, kept inside the frame (`CropImage._get_new_box`, `cropMode=fit`);
  a reflect-padded square crop at 4.0x made V1SE much worse (print AUC 0.56 against 0.82 on the training people).

**Why they are not in the default set.** On the November 2026 proxy evaluation they separated attacks less well than
the bundled model, alone or averaged with it, on the training people
([`improvement-2026-11.md`](../../docs/testing/proxy-eval/improvement-2026-11.md)). Discovery therefore uses them only
when no other anti-spoof model is in the directory; ensemble them explicitly through `engine.properties`
(`vivid.engine.antispoof2.model=...`) if a corpus of your own says they help.

## Not obtained

* **Dense landmarks** (68/106 points): no small model with a clearly OSI-licensed weight file was found. The InsightFace
  `2d106det` and `buffalo_*` weights are licensed for non-commercial research only, so they cannot ship with MOSIP. The
  engine derives every `FaceObservation` field from the 5 YuNet landmarks (see the module README).
* **InsightFace `w600k_mbf.onnx`**: excluded for the same reason (non-commercial weights). SFace is the
  Apache-2.0 replacement.

## Training-data caveat

The weights are redistributable, but they were trained on public face and PAD datasets that may themselves carry
research-only terms (for example, CelebA-Spoof for the anti-spoof model). PLAN.md section 4.5 calls for fine-tuning on
the project's own consented corpus before production use. Treat the bundled weights as a working baseline,
not a certified PAD model.
