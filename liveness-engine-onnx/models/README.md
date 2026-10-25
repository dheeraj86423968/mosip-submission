# Models for `liveness-engine-onnx`

The `.onnx` files in this directory are **not committed** (see `../.gitignore`). Run the script below or
install a signed model bundle (`model-bundle`). Every model has an OSI-approved licence, and the licence
text is kept next to it.

| File | Role | Size | SHA-256 | Licence | Source |
|---|---|---|---|---|---|
| `face_detection_yunet_2023mar.onnx` | Face detection + 5 landmarks (YuNet, fixed 1x3x640x640 BGR input) | 232,589 B | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` | MIT (`LICENSE-yunet.txt`, Copyright 2020 Shiqi Yu) | https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet |
| `antispoof_minifasnetv2se_128.onnx` | S1 CNN anti-spoofing, MiniFASNetV2-SE, 1x3x128x128 RGB/255, logits `[real, spoof]` | 1,912,594 B | `af2381b88f38769222ed93379e12444e2a50814575de1c46170de570c55a42b6` | Apache-2.0 (`LICENSE-antispoof.txt`) | https://github.com/facenox/face-antispoof-onnx, file `models/best/98.20/best_model.onnx` (architecture from minivision-ai Silent-Face-Anti-Spoofing, Apache-2.0) |
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

## Not obtained

* **Original Silent-Face models** (`2.7_80x80_MiniFASNetV2.onnx`, `4_0_0_80x80_MiniFASNetV1SE.onnx`): I found no
  ONNX export whose provenance could be checked. The engine supports them unchanged: drop them here, and the
  file-name preset selects 80x80, BGR, 3 classes with real at index 1, and the crop scale from the name prefix.
  Both are then ensembled with any other anti-spoof model, up to two in total.
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
