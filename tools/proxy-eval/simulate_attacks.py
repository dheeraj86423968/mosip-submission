#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Digitally simulated presentation attacks for the mosip-vivid proxy self-evaluation.

Input: a corpus directory of bona fide items in the CorpusScoreMain layout
(``<corpus>/<P07-BONA_FIDE-L0-D0-T1>/00001.jpg ... timestamps.txt``, see docs/testing/test-plan.md section 6).
Output: one attack item per bona fide item and species, with the same frame count and the same timestamps,
named ``P07-SIM_PRINT-L0-D0-T1`` and so on, plus a ``params.jsonl`` line per item recording every random draw.

This is a *digital proxy*. It is not an ISO/IEC 30107-3 presentation attack instrument. What it models and what it
leaves out is listed in docs/testing/proxy-eval/README.md. Every parameter and its range is defined in ``RANGES``
below; each item draws its own values from a generator seeded by (seed, item id, species), so the output is
reproducible.

Species (``--species``, comma separated):
  print          SIM_PRINT          one still frame, halftone or ordered dither, ink/paper gamut, ink spread, paper
                                    texture, perspective view, hand-held jitter, no facial motion (engine class PRINT)
  print_warped   SIM_PRINT_WARPED   the same print bent around a vertical axis, flexing slowly (PRINT)
  print_cutout   SIM_PRINT_CUTOUT   the same print with both eyes cut out; the live clip's own eyes show through the
                                    holes, aligned per frame (PRINT). Real attacks show the attacker's eyes instead.
  screen_replay  SIM_SCREEN_REPLAY  every frame of the clip shown on a simulated RGB-stripe display and re-captured:
                                    pixel-grid aliasing (moire), display gamma and white point, black level, specular
                                    glare patch, bezel on some items, rolling refresh banding (VIDEO_REPLAY)
  screen_photo   SIM_SCREEN_PHOTO   one still frame on the same simulated display (SCREEN_REPLAY)

Every species ends with the same capture-camera model: optical blur, 2x sampling with a pixel-aperture average,
RGGB Bayer mosaic and demosaic, auto exposure toward the
bona fide frame's mean, signal-dependent sensor noise and JPEG re-encoding.

Requires Python 3.9+, numpy and OpenCV (cv2 with FaceDetectorYN for the still-frame choice and the cut-out eyes).

Example:
  python3 tools/proxy-eval/simulate_attacks.py --corpus /scratch/bona_fide --out /scratch/attacks \
      --species print,screen_replay,screen_photo --yunet liveness-engine-onnx/models/face_detection_yunet_2023mar.onnx
"""
import argparse
import hashlib
import json
import math
import os
import re
import sys

import cv2
import numpy as np

SPECIES = {
    "print": ("SIM_PRINT", "PRINT"),
    "print_warped": ("SIM_PRINT_WARPED", "PRINT"),
    "print_cutout": ("SIM_PRINT_CUTOUT", "PRINT"),
    "screen_replay": ("SIM_SCREEN_REPLAY", "VIDEO_REPLAY"),
    "screen_photo": ("SIM_SCREEN_PHOTO", "SCREEN_REPLAY"),
}

# Every random parameter, as (low, high) for a uniform draw unless noted. Units in the comments.
RANGES = {
    # --- print instrument (print space is the frame upsampled 4x, i.e. 2560x1920 for a 640x480 clip) ---
    "print_upsample": 4,                       # print-space pixels per source pixel (fixed; 2560 px for 640 px)
    "print_method": ["halftone", "dither"],    # alternates by item: clustered-dot AM screen (laser) or 8x8 Bayer (inkjet)
    "halftone_period": (4.0, 7.0),             # print px per cell = 1.0-1.75 camera px (coarse end of real prints)
    "halftone_angles": (15, 75, 0, 45),        # C, M, Y, K screen angles in degrees (fixed, the classic set)
    "ink_spread_sigma": (0.8, 1.4),            # print px, Gaussian dot gain / ink bleed
    "paper_white": ((0.93, 0.97), (0.92, 0.96), (0.86, 0.93)),  # R, G, B reflectance of the paper
    "paper_texture": (0.015, 0.04),            # relative amplitude of low-frequency paper mottle
    "paper_fibre": (0.005, 0.015),             # relative amplitude of per-pixel fibre noise
    # --- print in the scene ---
    "print_scale": (0.85, 1.10),               # print width / camera width (<1: print edge and background visible)
    "print_tilt": (0.00, 0.06),                # max corner displacement, fraction of width (perspective tilt)
    "print_rotation_deg": (-4.0, 4.0),
    "light_gradient": (0.0, 0.25),             # illumination falloff across the print (fraction)
    "light_tint": ((0.95, 1.08), (1.0, 1.0), (0.85, 1.02)),  # R, G, B multipliers of the room light
    "hand_jitter_px": (1.5, 5.0),              # RMS translation of the hand-held print or phone, camera px
    "hand_jitter_rot_deg": (0.2, 0.8),
    "hand_jitter_scale": (0.003, 0.012),       # relative size breathing (moving toward/away from the camera)
    "hand_jitter_hz": (0.4, 2.5),              # band of the sinusoids summed for the jitter
    # --- warped print ---
    "bend_deg": (35.0, 80.0),                  # total arc of the cylindrical bend across the print width
    "bend_flex_deg": (0.0, 10.0),              # amplitude of slow flexing
    "bend_flex_hz": (0.2, 0.7),
    # --- cut-out print ---
    "eye_hole_w": (0.42, 0.52),                # hole width, inter-ocular distances
    "eye_hole_h": (0.20, 0.28),                # hole height, inter-ocular distances
    "hole_rim_shadow": (0.25, 0.5),            # darkening of a 2 px rim around the hole (paper edge shadow)
    # --- display ---
    "display_freq": (0.6, 1.4),                # display pixels per camera pixel across the screen (sets moire)
    "display_fill": (0.80, 1.12),              # screen width / camera width (<1 and the bezel shows)
    "bezel_frac": (0.03, 0.08),                # bezel width, fraction of screen width
    "subpixel_dim_row": (0.45, 0.70),          # brightness of the black-matrix half-row (vertical fill factor)
    "display_gamma": (0.85, 1.15),             # exponent applied to the content by the display pipeline
    "display_white": ((0.90, 0.98), (1.0, 1.0), (1.02, 1.10)),  # R, G, B white point (screens run blue)
    "display_black": (0.02, 0.05),             # black level (emissive screens never reach 0)
    "display_saturation": (0.90, 1.20),
    "glare_amp": (0.08, 0.30),                 # peak additive specular reflection
    "glare_sigma_px": (35.0, 90.0),            # glare patch size, camera px
    "glare_elong": (1.0, 2.5),                 # aspect ratio of the glare ellipse
    "band_amp": (0.015, 0.06),                 # rolling refresh banding depth
    "band_period_px": (120.0, 480.0),
    "band_speed_hz": (0.2, 1.5),
    "screen_held": 0.5,                        # probability the screen is hand-held (else on a stand: jitter x0.15)
    # --- capture camera (all species) ---
    "camera_blur_sigma": (0.30, 0.55),         # optical PSF, camera px (webcams rarely have an anti-alias filter)
    "exposure_match": (0.90, 1.10),            # auto exposure lands within +-10% of the bona fide frame mean
    "noise_read": (1.0, 2.5),                  # read noise, 8-bit DN
    "noise_shot": (0.15, 0.35),                # shot-noise gain: sigma = gain * sqrt(DN), i.e. 1.7-4 DN at mid grey
    "jpeg_quality": (85, 95),
}


def rng_for(seed, item, species):
    h = hashlib.sha256(f"{seed}|{item}|{species}".encode()).digest()
    return np.random.default_rng(int.from_bytes(h[:8], "little"))


def u(rng, key, params, name=None):
    lo, hi = RANGES[key]
    v = float(rng.uniform(lo, hi))
    params[name or key] = round(v, 4)
    return v


def u3(rng, key, params):
    v = [float(rng.uniform(lo, hi)) for lo, hi in RANGES[key]]
    params[key] = [round(x, 4) for x in v]
    return np.array(v, np.float32)


# ----------------------------------------------------------------------------------------------- items and frames

def read_item(d):
    frames = sorted(f for f in os.listdir(d) if re.match(r".*\.(jpg|jpeg|png)$", f, re.I))
    ts = {}
    p = os.path.join(d, "timestamps.txt")
    if os.path.exists(p):
        for i, line in enumerate(open(p)):
            parts = line.split()
            if len(parts) == 2:
                ts[parts[0]] = int(parts[1])
            elif len(parts) == 1 and i < len(frames):
                ts[frames[i]] = int(parts[0])
    times = [ts.get(f, round(i * 1000 / 15)) for i, f in enumerate(frames)]
    return frames, times


def load(d, f):
    img = cv2.imread(os.path.join(d, f), cv2.IMREAD_COLOR)
    return cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0


class Faces:
    def __init__(self, model):
        self.model = model
        self.det = None

    def detect(self, rgb01):
        if not self.model:
            return None
        h, w = rgb01.shape[:2]
        if self.det is None:
            self.det = cv2.FaceDetectorYN.create(self.model, "", (w, h), 0.6, 0.3, 50)
        self.det.setInputSize((w, h))
        bgr = cv2.cvtColor((rgb01 * 255).astype(np.uint8), cv2.COLOR_RGB2BGR)
        _, faces = self.det.detect(bgr)
        if faces is None or len(faces) == 0:
            return None
        return max(faces, key=lambda r: r[2] * r[3])


def pick_still(d, frames, faces):
    """The frame an attacker would print: the most confidently detected face in the middle 60% of the clip."""
    n = len(frames)
    lo, hi = int(n * 0.2), max(int(n * 0.8), int(n * 0.2) + 1)
    best, best_s = frames[n // 2], -1.0
    for f in frames[lo:hi:3]:
        r = faces.detect(load(d, f))
        if r is not None and r[14] > best_s:
            best, best_s = f, float(r[14])
    return best


# ------------------------------------------------------------------------------------------------- shared pieces

def jitter_track(rng, n_times, times_s, params, scale=1.0):
    """Smooth hand-held motion: three sinusoids per axis in the jitter band, normalised to the drawn RMS."""
    amp = u(rng, "hand_jitter_px", params) * scale
    rot = u(rng, "hand_jitter_rot_deg", params) * scale
    sc = u(rng, "hand_jitter_scale", params) * scale
    lo, hi = RANGES["hand_jitter_hz"]
    t = np.asarray(times_s, np.float64)

    def comp(rms):
        s = np.zeros_like(t)
        for _ in range(3):
            s += np.sin(2 * math.pi * rng.uniform(lo, hi) * t + rng.uniform(0, 2 * math.pi)) * rng.uniform(0.5, 1.0)
        s += np.cumsum(rng.normal(0, 0.05, len(t)))       # slow drift
        s -= s.mean()
        std = s.std() or 1.0
        return s / std * rms

    return comp(amp), comp(amp), comp(rot), comp(sc)


def scene_homography(w_src, h_src, W, H, scale, tilt, rot_deg, rng, cx=0.5, cy=0.5):
    """Maps an instrument of size (w_src, h_src) into a camera frame (W, H): fit to width*scale, rotate, perspective."""
    s = W * scale / w_src
    corners = np.array([[0, 0], [w_src, 0], [w_src, h_src], [0, h_src]], np.float32)
    c = (corners - [w_src / 2, h_src / 2]) * s
    a = math.radians(rot_deg)
    R = np.array([[math.cos(a), -math.sin(a)], [math.sin(a), math.cos(a)]], np.float32)
    c = c @ R.T + [W * cx, H * cy]
    c = c + rng.uniform(-tilt, tilt, size=(4, 2)).astype(np.float32) * W
    return c.astype(np.float32), corners


def apply_jitter(dst_corners, W, H, dx, dy, drot, dsc):
    c = dst_corners - [W / 2, H / 2]
    a = math.radians(drot)
    R = np.array([[math.cos(a), -math.sin(a)], [math.sin(a), math.cos(a)]], np.float32)
    return (c @ R.T * (1 + dsc) + [W / 2 + dx, H / 2 + dy]).astype(np.float32)


def background(rng, W, H, params):
    """A plain room wall behind the instrument: a soft colour gradient with mild noise (no people)."""
    base = rng.uniform(0.25, 0.75, 3).astype(np.float32) * rng.uniform(0.7, 1.0)
    gx = np.linspace(-1, 1, W, dtype=np.float32)[None, :, None]
    gy = np.linspace(-1, 1, H, dtype=np.float32)[:, None, None]
    g = base * (1 + 0.15 * rng.uniform(-1, 1) * gx + 0.15 * rng.uniform(-1, 1) * gy)
    params["background_rgb"] = [round(float(x), 3) for x in base]
    return np.clip(g + rng.normal(0, 0.01, (H, W, 3)).astype(np.float32), 0, 1)


class Camera:
    """Capture camera shared by all species. Renders at 2x and area-averages (pixel aperture)."""

    def __init__(self, rng, params, ref_mean):
        self.sigma = u(rng, "camera_blur_sigma", params)
        self.expo = u(rng, "exposure_match", params)
        self.read = u(rng, "noise_read", params)
        self.shot = u(rng, "noise_shot", params)
        self.q = int(round(u(rng, "jpeg_quality", params)))
        self.ref_mean = ref_mean
        self.rng = rng
        self.gain = None

    def finish(self, img2x, optics=True):
        """img2x: float RGB at twice the output size. Returns the uint8 RGB output frame. optics=False when the
        caller already applied the optical blur (the display path does it in panel units)."""
        img = cv2.GaussianBlur(img2x, (0, 0), self.sigma * 2) if optics else img2x
        img = cv2.resize(img, (img2x.shape[1] // 2, img2x.shape[0] // 2), interpolation=cv2.INTER_AREA)
        if self.gain is None:   # auto exposure settles on the first frame and then holds (no hunting)
            m = float(img.mean()) or 1e-3
            self.gain = float(np.clip(self.ref_mean * self.expo / m, 0.5, 2.5))
        img = np.clip(img * self.gain, 0, 1) * 255.0
        # RGGB Bayer mosaic: one colour per photosite, noise on the raw values, bilinear demosaic. Colour channels
        # are sampled at half the pixel rate, which is where most real recapture moire comes from.
        H, W = img.shape[:2]
        raw = np.empty((H, W), np.float32)
        raw[0::2, 0::2] = img[0::2, 0::2, 0]
        raw[0::2, 1::2] = img[0::2, 1::2, 1]
        raw[1::2, 0::2] = img[1::2, 0::2, 1]
        raw[1::2, 1::2] = img[1::2, 1::2, 2]
        raw = raw + self.rng.normal(0, 1, raw.shape).astype(np.float32) * np.sqrt(self.read ** 2 + self.shot ** 2 * raw)
        raw = np.clip(raw, 0, 255).astype(np.uint8)
        return cv2.cvtColor(raw, cv2.COLOR_BayerBG2RGB)   # OpenCV names the pattern differently: BG2RGB = RGGB

    def write(self, path, rgb8):
        cv2.imwrite(path, cv2.cvtColor(rgb8, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, self.q])


# ------------------------------------------------------------------------------------------------------- print

def render_print(rgb, rng, params, idx):
    """The physical print as a reflectance image in print space (print_upsample x the source resolution)."""
    k = RANGES["print_upsample"]
    h, w = rgb.shape[:2]
    up = cv2.resize(rgb, (w * k, h * k), interpolation=cv2.INTER_CUBIC).clip(0, 1)
    K = 1 - up.max(axis=2)
    denom = np.maximum(1 - K, 1e-4)
    cmy = [(1 - up[..., c] - K) / denom for c in range(3)]
    inks = cmy + [K]
    method = RANGES["print_method"][idx % 2]
    params["print_method"] = method
    H, W = K.shape
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    cover = []
    if method == "halftone":
        P = u(rng, "halftone_period", params)
        for ink, ang in zip(inks, RANGES["halftone_angles"]):
            a = math.radians(ang)
            uu = xx * math.cos(a) + yy * math.sin(a)
            vv = -xx * math.sin(a) + yy * math.cos(a)
            thr = 0.5 - 0.25 * (np.cos(2 * math.pi * uu / P) + np.cos(2 * math.pi * vv / P))  # clustered dot
            cover.append((ink > thr).astype(np.float32))
    else:
        b = np.array([[0, 32, 8, 40, 2, 34, 10, 42], [48, 16, 56, 24, 50, 18, 58, 26], [12, 44, 4, 36, 14, 46, 6, 38],
                      [60, 28, 52, 20, 62, 30, 54, 22], [3, 35, 11, 43, 1, 33, 9, 41], [51, 19, 59, 27, 49, 17, 57, 25],
                      [15, 47, 7, 39, 13, 45, 5, 37], [63, 31, 55, 23, 61, 29, 53, 21]], np.float32)
        bayer = (b + 0.5) / 64.0
        for c, ink in enumerate(inks):
            sh = np.roll(np.roll(bayer, 2 * c, 0), 3 * c, 1)       # offset matrices per ink
            thr = np.tile(sh, (H // 8 + 1, W // 8 + 1))[:H, :W]
            cover.append((ink > thr).astype(np.float32))
    spread = u(rng, "ink_spread_sigma", params)
    cover = [cv2.GaussianBlur(c, (0, 0), spread) for c in cover]
    # Subtractive ink model: each ink transmits its own RGB fraction where it covers the paper.
    ink_rgb = np.array([[0.10, 0.62, 0.90], [0.92, 0.22, 0.55], [0.96, 0.90, 0.12], [0.10, 0.10, 0.11]], np.float32)
    paper = u3(rng, "paper_white", params)
    refl = np.ones((H, W, 3), np.float32) * paper
    for c in range(4):
        refl *= 1 - cover[c][..., None] * (1 - ink_rgb[c])
    mottle = rng.normal(0, 1, (H // 16 + 1, W // 16 + 1)).astype(np.float32)
    mottle = cv2.resize(cv2.GaussianBlur(mottle, (0, 0), 1.5), (W, H), interpolation=cv2.INTER_CUBIC)
    mottle = mottle / (mottle.std() or 1) * u(rng, "paper_texture", params)
    fibre = rng.normal(0, u(rng, "paper_fibre", params), (H, W)).astype(np.float32)
    refl *= (1 + mottle + fibre)[..., None]
    return refl.clip(0, 1)


def lighting(rng, params, H, W):
    g = u(rng, "light_gradient", params)
    a = rng.uniform(0, 2 * math.pi)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    ramp = ((xx / W - 0.5) * math.cos(a) + (yy / H - 0.5) * math.sin(a))
    tint = u3(rng, "light_tint", params)
    return ((1 - g * ramp)[..., None] * tint).astype(np.float32)


def cylinder_maps(Wp, Hp, bend_deg, view_dist=3.0):
    """Remap a flat print of width Wp onto a cylinder bent around the vertical axis (seen from the front).
    Returns (map_x, map_y, shade) over an output canvas of the same size, centred."""
    phi = math.radians(max(bend_deg, 1e-3))
    R = Wp / phi
    xo = np.arange(Wp, dtype=np.float32) - Wp / 2
    # visible half-width of the bent print
    half = R * math.sin(phi / 2)
    theta = np.arcsin(np.clip(xo / R, -1, 1)) * (np.abs(xo) <= half)   # x = R sin(theta) on the arc
    z = R * (1 - np.cos(theta))                       # depth behind the front-most line
    persp = 1.0 / (1.0 + z / (view_dist * Wp))        # farther parts shrink toward the centre line
    src_x = theta * R + Wp / 2
    valid = (np.abs(xo) <= half).astype(np.float32)
    yo = np.arange(Hp, dtype=np.float32) - Hp / 2
    map_x = np.tile(src_x, (Hp, 1))
    map_y = (yo[:, None] / persp[None, :]) + Hp / 2
    shade = (0.78 + 0.22 * np.cos(theta - 0.35))[None, :] * valid[None, :]
    map_x = np.where(valid[None, :] > 0, map_x, -10).astype(np.float32)
    return map_x, map_y.astype(np.float32), np.repeat(shade, Hp, 0).astype(np.float32)


def eye_holes(face_still, k, shape, rng, params):
    """Elliptical eye-hole masks in print space, from the still frame's YuNet landmarks."""
    Hp, Wp = shape
    re_, le = face_still[4:6] * k, face_still[6:8] * k
    iod = float(np.hypot(*(le - re_)))
    hw, hh = u(rng, "eye_hole_w", params) * iod / 2, u(rng, "eye_hole_h", params) * iod / 2
    ang = math.degrees(math.atan2(le[1] - re_[1], le[0] - re_[0]))
    mask = np.zeros((Hp, Wp), np.float32)
    for c in (re_, le):
        cv2.ellipse(mask, (int(round(c[0])), int(round(c[1]))), (int(round(hw)), int(round(hh))), ang, 0, 360, 1.0, -1, cv2.LINE_AA)
    rim = cv2.dilate(mask, np.ones((5, 5), np.uint8)) - mask
    return mask, rim, (re_, le)


def simulate_print(d, frames, times, out_dir, species, rng, params, faces, idx):
    still = pick_still(d, frames, faces)
    params["still_frame"] = still
    src = load(d, still)
    h, w = src.shape[:2]
    ref_mean = float(src.mean())
    refl = render_print(src, rng, params, idx)
    Hp, Wp = refl.shape[:2]
    k = RANGES["print_upsample"]
    W2, H2 = w * 2, h * 2
    hole = rim = None
    eye_still = None
    if species == "print_cutout":
        fs = faces.detect(src)
        if fs is None:
            raise RuntimeError("no face in the still frame for the cut-out")
        hole, rim, eye_still = eye_holes(fs, k, (Hp, Wp), rng, params)
        refl = refl * (1 - rim * u(rng, "hole_rim_shadow", params))[..., None]
    if species == "print_warped":
        bend = u(rng, "bend_deg", params)
        flex = u(rng, "bend_flex_deg", params)
        flex_hz = u(rng, "bend_flex_hz", params)
    scale = u(rng, "print_scale", params)
    tilt = u(rng, "print_tilt", params)
    rot = u(rng, "print_rotation_deg", params)
    dst0, corners = scene_homography(Wp, Hp, W2, H2, scale, tilt, rot, rng)
    light = lighting(rng, params, Hp, Wp)
    bg = cv2.resize(background(rng, w, h, params), (W2, H2))
    t_s = [t / 1000.0 for t in times]
    jx, jy, jr, js = jitter_track(rng, len(times), t_s, params)
    cam = Camera(rng, params, ref_mean)
    params["print_light_mean"] = round(float(light.mean()), 3)
    # The camera's optical blur is applied in print space (k / scale print px per camera px) before sampling, so the
    # halftone aliases exactly as far as the optics let it; Camera.finish then only applies the pixel aperture.
    sig = cam.sigma * k / scale
    base = cv2.GaussianBlur(refl * light, (0, 0), sig)
    if species == "print_cutout":
        lit = refl * light
        hole_b = cv2.GaussianBlur(hole, (0, 0), sig)[..., None]
        base = cv2.GaussianBlur(lit * (1 - hole[..., None]), (0, 0), sig)
    for i, f in enumerate(frames):
        inst = base
        alpha = np.ones((Hp, Wp), np.float32)
        if species == "print_cutout":
            live = load(d, f)
            fl = faces.detect(live)
            behind = np.zeros_like(base)
            if fl is not None:
                live_up = cv2.resize(live, (Wp, Hp), interpolation=cv2.INTER_LINEAR)
                for j, c_still in enumerate(eye_still):
                    c_live = fl[4 + 2 * j:6 + 2 * j] * k
                    M = np.float32([[1, 0, c_still[0] - c_live[0]], [0, 1, c_still[1] - c_live[1]]])
                    shifted = cv2.warpAffine(live_up, M, (Wp, Hp), borderMode=cv2.BORDER_REPLICATE)
                    near = np.zeros((Hp, Wp), np.float32)
                    cv2.circle(near, (int(c_still[0]), int(c_still[1])), int(Wp * 0.08), 1.0, -1)
                    behind += shifted * near[..., None]
            else:
                behind[:] = 0.05   # the face behind moved away: the holes look dark
            inst = base + cv2.GaussianBlur(behind * hole[..., None], (0, 0), sig)
        if species == "print_warped":
            bnd = bend + flex * math.sin(2 * math.pi * flex_hz * t_s[i])
            mx, my, shade = cylinder_maps(Wp, Hp, bnd)
            inst = cv2.remap(inst, mx, my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0) * shade[..., None]
            alpha = (shade > 0).astype(np.float32)
        dst = apply_jitter(dst0, W2, H2, jx[i] * 2, jy[i] * 2, jr[i], js[i])
        Hm = cv2.getPerspectiveTransform(corners, dst)
        warped = cv2.warpPerspective(inst, Hm, (W2, H2), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        a_ = cv2.warpPerspective(alpha, Hm, (W2, H2), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)[..., None]
        scene = warped * a_ + bg * (1 - a_)
        cam.write(os.path.join(out_dir, f), cam.finish(scene, optics=False))


# ------------------------------------------------------------------------------------------------------ screen

def display_content(rgb, gamma, white, black, sat):
    g = rgb.mean(axis=2, keepdims=True)
    x = np.clip(g + (rgb - g) * sat, 0, 1) ** gamma
    return (black + (1 - black) * x * white).astype(np.float32)


def simulate_screen(d, frames, times, out_dir, species, rng, params, faces):
    still = pick_still(d, frames, faces) if species == "screen_photo" else None
    if still:
        params["still_frame"] = still
    first = load(d, still or frames[0])
    h, w = first.shape[:2]
    ref_mean = float(first.mean())
    W2, H2 = w * 2, h * 2
    freq = u(rng, "display_freq", params)
    fill = u(rng, "display_fill", params)
    bez = u(rng, "bezel_frac", params)
    dim = u(rng, "subpixel_dim_row", params)
    gamma = u(rng, "display_gamma", params)
    white = u3(rng, "display_white", params)
    black = u(rng, "display_black", params)
    sat = u(rng, "display_saturation", params)
    held = bool(rng.uniform() < RANGES["screen_held"])
    params["screen_held"] = held
    # Display raster: Dw x Dh pixels covering `fill` of the camera width; each pixel is 3 RGB stripes x 2 rows
    # (the second row is the dimmer black-matrix gap). Panel arrays are therefore 3 columns and 2 rows per pixel.
    Dw = int(round(w * fill * freq))
    Dh = int(round(Dw * h / w))
    bzd = int(round(bez * Dw))
    params["display_px"] = [Dw, Dh]
    params["bezel_visible"] = bool(fill * (Dw + 2 * bzd) / Dw < 1.0)
    stripe = np.zeros((1, 3 * Dw, 3), np.float32)
    for c in range(3):
        stripe[0, c::3, c] = 1.0
    rowmask = np.tile(np.array([1.0, dim], np.float32).reshape(2, 1, 1), (Dh, 1, 1))
    Sw, Sh = 3 * (Dw + 2 * bzd), 2 * (Dh + 2 * bzd)
    tilt = u(rng, "print_tilt", params, "screen_tilt") * 0.6
    rot = u(rng, "print_rotation_deg", params, "screen_rotation_deg")
    # Geometry in display-pixel units (square pixels), then expressed in panel-array coordinates.
    dst0, _ = scene_homography(Dw + 2 * bzd, Dh + 2 * bzd, W2, H2, fill * (Dw + 2 * bzd) / Dw, tilt, rot, rng)
    corners = np.array([[0, 0], [Sw, 0], [Sw, Sh], [0, Sh]], np.float32)
    bg = cv2.resize(background(rng, w, h, params), (W2, H2))
    gl_amp = u(rng, "glare_amp", params)
    gl_sig = u(rng, "glare_sigma_px", params) * 2
    gl_el = u(rng, "glare_elong", params)
    gl_ang = float(rng.uniform(0, math.pi))
    gl_c = rng.uniform([0.2 * W2, 0.15 * H2], [0.8 * W2, 0.85 * H2])
    params["glare_centre"] = [round(float(gl_c[0] / W2), 3), round(float(gl_c[1] / H2), 3)]
    b_amp = u(rng, "band_amp", params)
    b_per = u(rng, "band_period_px", params) * 2
    b_spd = u(rng, "band_speed_hz", params)
    t_s = [t / 1000.0 for t in times]
    jx, jy, jr, js = jitter_track(rng, len(times), t_s, params, 1.0 if held else 0.15)
    cam = Camera(rng, params, ref_mean)
    yy, xx = np.mgrid[0:H2, 0:W2].astype(np.float32)
    ca, sa = math.cos(gl_ang), math.sin(gl_ang)
    cached = None
    for i, f in enumerate(frames):
        if cached is None or still is None:
            src = load(d, still or f)
            disp = cv2.resize(src, (Dw, Dh), interpolation=cv2.INTER_AREA)
            disp = display_content(disp, gamma, white, black, sat)
            panel = np.repeat(np.repeat(disp, 3, axis=1), 2, axis=0) * stripe * rowmask * 3.0  # x3: one stripe lit
            screen = np.full((Sh, Sw, 3), 0.015, np.float32)                                   # bezel: near black
            screen[2 * bzd:2 * bzd + 2 * Dh, 3 * bzd:3 * bzd + 3 * Dw] = panel
            cached = screen
        dst = apply_jitter(dst0, W2, H2, jx[i] * 2, jy[i] * 2, jr[i], js[i])
        Hm = cv2.getPerspectiveTransform(corners, dst)
        # The camera's optical blur is applied here, in panel units (3*freq columns and 2*freq rows per camera
        # pixel), before sampling; Camera.finish then only box-averages (pixel aperture). Whatever grid contrast
        # survives the optics aliases into moire at the sampling stage.
        sx = max(cam.sigma * 3 * freq, 0.3)
        sy = max(cam.sigma * 2 * freq, 0.3)
        blurred = cv2.GaussianBlur(cached, (0, 0), sigmaX=sx, sigmaY=sy)
        warped = cv2.warpPerspective(blurred, Hm, (W2, H2), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(-1, -1, -1))
        inside = (warped[..., :1] >= 0).astype(np.float32)
        scene = np.where(inside > 0, warped, bg)
        dxg = (xx - gl_c[0] - jx[i] * 2)
        dyg = (yy - gl_c[1] - jy[i] * 2)
        uu = (dxg * ca + dyg * sa) / (gl_sig * gl_el)
        vv = (-dxg * sa + dyg * ca) / gl_sig
        glare = gl_amp * np.exp(-0.5 * (uu * uu + vv * vv))
        band = 1 - b_amp * 0.5 * (1 + np.cos(2 * math.pi * (yy / b_per - b_spd * t_s[i])))
        scene = scene * np.where(inside > 0, band[..., None], 1) + (glare * inside[..., 0])[..., None]
        cam.write(os.path.join(out_dir, f), cam.finish(np.clip(scene, 0, 1), optics=False))
    # Pixel-grid luminance contrast left after the Gaussian optics and a 1 px box aperture (before aliasing).
    mtf = math.exp(-2 * math.pi ** 2 * cam.sigma ** 2 * freq ** 2) * abs(math.sin(math.pi * freq) / (math.pi * freq))
    params["expected_grid_contrast"] = round(mtf, 4)


# -------------------------------------------------------------------------------------------------------- main

NAME = re.compile(r"^(P\d+)-([A-Z0-9_]+)-(L\d+)-(D\d+)-T(\d+)$")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", required=True, help="directory of bona fide items (CorpusScoreMain layout)")
    ap.add_argument("--out", required=True, help="output directory for attack items")
    ap.add_argument("--species", default="print,screen_replay,screen_photo", help="comma separated: " + ",".join(SPECIES))
    ap.add_argument("--yunet", default="", help="YuNet ONNX model (picks the still frame; required for print_cutout)")
    ap.add_argument("--seed", default="2026", help="global seed")
    ap.add_argument("--only", default="", help="comma separated participant ids to process (default all)")
    ap.add_argument("--log", default="", help="append capture-log.csv rows for the generated items to this file")
    a = ap.parse_args(argv)
    species = [s.strip() for s in a.species.split(",") if s.strip()]
    for s in species:
        if s not in SPECIES:
            ap.error("unknown species " + s)
    only = set(x for x in a.only.split(",") if x)
    faces = Faces(a.yunet)
    os.makedirs(a.out, exist_ok=True)
    items = sorted(x for x in os.listdir(a.corpus) if NAME.match(x) and NAME.match(x).group(2) == "BONA_FIDE")
    plog = open(os.path.join(a.out, "params.jsonl"), "a")
    log = open(a.log, "a") if a.log else None
    for idx, item in enumerate(items):
        m = NAME.match(item)
        if only and m.group(1) not in only:
            continue
        d = os.path.join(a.corpus, item)
        frames, times = read_item(d)
        if not frames:
            continue
        for s in species:
            label, cls = SPECIES[s]
            out_id = f"{m.group(1)}-{label}-{m.group(3)}-{m.group(4)}-T{m.group(5)}"
            od = os.path.join(a.out, out_id)
            os.makedirs(od, exist_ok=True)
            rng = rng_for(a.seed, item, s)
            params = {"item": out_id, "source": item, "species": s, "seed": a.seed}
            try:
                if s.startswith("print"):
                    simulate_print(d, frames, times, od, s, rng, params, faces, idx)
                else:
                    simulate_screen(d, frames, times, od, s, rng, params, faces)
            except Exception as e:  # keep going; the item is dropped and the failure recorded
                params["error"] = repr(e)
                for f in os.listdir(od):
                    os.remove(os.path.join(od, f))
                os.rmdir(od)
                print(f"{out_id}: FAILED {e!r}", file=sys.stderr)
            else:
                with open(os.path.join(od, "timestamps.txt"), "w") as fh:
                    for f, t in zip(frames, times):
                        fh.write(f"{f} {t}\n")
                if log:
                    log.write(f"{out_id},{m.group(1)},{label},{cls},LEVEL1,{m.group(3)},{m.group(4)},{m.group(5)},"
                              f"{(times[-1] - times[0]) / 1000:.1f},NA,NA,NA,NA,N,,,,,,,,NA,NA,NA,NA,,,simulated\n")
                print(f"{out_id}: {len(frames)} frames", flush=True)
            plog.write(json.dumps(params) + "\n")
            plog.flush()
    plog.close()
    if log:
        log.close()


if __name__ == "__main__":
    main()
