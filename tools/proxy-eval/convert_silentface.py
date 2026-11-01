#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Converts the official Silent-Face-Anti-Spoofing weights (minivision-ai, Apache-2.0) to ONNX for S1.

  git clone https://github.com/minivision-ai/Silent-Face-Anti-Spoofing   # or fetch the files listed below
  python -m venv venv && venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cpu && venv/bin/pip install onnx onnxruntime
  venv/bin/python tools/proxy-eval/convert_silentface.py <Silent-Face checkout> liveness-engine-onnx/models

Needs from the checkout: src/model_lib/MiniFASNet.py, src/utility.py and
resources/anti_spoof_models/{2.7_80x80_MiniFASNetV2.pth,4_0_0_80x80_MiniFASNetV1SE.pth}.
Writes 2.7_80x80_MiniFASNetV2.onnx and 4_0_0_80x80_MiniFASNetV1SE.onnx (input 1x3x80x80 BGR, raw 0..255 values as
upstream's to_tensor does not divide by 255; output 3 logits, "real" at index 1) and checks that ONNX Runtime matches
PyTorch on random inputs. With torch 2.15.0+cpu, onnx 1.23.2 and opset 13 the export is byte-for-byte reproducible;
the SHA-256 values are in liveness-engine-onnx/models/README.md.
"""
import os
import sys
from collections import OrderedDict

import numpy as np
import torch

sf, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, sf)
from src.model_lib.MiniFASNet import MiniFASNetV1SE, MiniFASNetV2  # noqa: E402
from src.utility import get_kernel  # noqa: E402
import onnxruntime as ort  # noqa: E402

for name, cls in [("2.7_80x80_MiniFASNetV2", MiniFASNetV2), ("4_0_0_80x80_MiniFASNetV1SE", MiniFASNetV1SE)]:
    m = cls(conv6_kernel=get_kernel(80, 80))
    sd = torch.load(os.path.join(sf, "resources/anti_spoof_models", name + ".pth"), map_location="cpu", weights_only=False)
    sd = OrderedDict((k[7:] if k.startswith("module.") else k, v) for k, v in sd.items())
    m.load_state_dict(sd)
    m.eval()
    x = torch.randn(1, 3, 80, 80) * 60 + 120
    dst = os.path.join(out, name + ".onnx")
    torch.onnx.export(m, x, dst, input_names=["input"], output_names=["logits"], opset_version=13, dynamo=False,
                      do_constant_folding=True)
    s = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
    worst = 0.0
    for _ in range(20):
        x = torch.rand(1, 3, 80, 80) * 255
        with torch.no_grad():
            a = torch.softmax(m(x), 1).numpy()
        b = s.run(None, {"input": x.numpy()})[0]
        b = np.exp(b - b.max())
        b /= b.sum()
        worst = max(worst, float(np.abs(a - b).max()))
    print(f"{dst}: max |softmax(PyTorch) - softmax(ONNX)| over 20 random inputs = {worst:.2e}")
    if worst > 1e-4:
        sys.exit(f"{name}: ONNX output does not match PyTorch")
