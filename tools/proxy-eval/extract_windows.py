#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Re-extracts the proxy-evaluation bona fide PAD windows listed in docs/testing/proxy-eval/sources.csv.

For every row with used_for containing "pad": decode the downloaded Commons transcode named in version_used
(<videos>/<Pnn>.webm; fetch_and_extract.sh downloads one clip at a time and deletes it afterwards), take the 6 s
window that starts at pad_window_start_s at the clip's native frame rate (presentation times from the decoder),
find the median YuNet face over the window (largest detection with score >= 0.6, every second frame), cut a fixed
4:3 crop around it sized so the inter-ocular distance is about 80 px at 640x480 (capped at the frame size), resize to
640x480 and save JPEG quality 90 plus timestamps.txt (ms from the first kept frame). A histogram scene-cut test
splits the window at every cut and keeps the longest shot.

Output: <out>/<Pnn>-BONA_FIDE-L0-D0-T1/00001.jpg ... timestamps.txt, and <out>/extract-log.jsonl.
Requires Python 3.9+, numpy and OpenCV with FFmpeg (VP9) and FaceDetectorYN. Used for
docs/testing/proxy-eval/improvement-2026-11.md; the original 24 Oct extraction script was not kept, so this is a
re-implementation from the description in docs/testing/proxy-eval/README.md section 2.
"""
import argparse, csv, hashlib, json, os, sys, urllib.parse
import cv2
import numpy as np

def transcode_url(page_url, version):
    name = urllib.parse.unquote(page_url.split('/wiki/File:')[1]).replace(' ', '_')
    h = hashlib.md5(name.encode()).hexdigest()
    q = urllib.parse.quote(name)
    suffix = version.replace('Commons transcode ', '').strip()
    return f"https://upload.wikimedia.org/wikipedia/commons/transcoded/{h[0]}/{h[:2]}/{q}/{q}.{suffix}"


def hist(img):
    hsv = cv2.cvtColor(cv2.resize(img, (160, 90)), cv2.COLOR_BGR2HSV)
    h = cv2.calcHist([hsv], [0, 1], None, [32, 32], [0, 180, 0, 256])
    return cv2.normalize(h, h).flatten()


def extract(row, out, yunet, video, window_s=6.0, iod_target=80.0):
    pid = row['participant_id']
    start = float(row['pad_window_start_s'])
    url = transcode_url(row['url'], row['version_used'])
    cap = cv2.VideoCapture(video, cv2.CAP_FFMPEG)
    if not cap.isOpened():
        raise RuntimeError("cannot open " + url)
    cap.set(cv2.CAP_PROP_POS_MSEC, max(0.0, start * 1000 - 1500))
    frames, pts = [], []
    fps = float(row['native_fps'])
    half = 500.0 / fps
    while True:
        ok, f = cap.read()
        if not ok:
            break
        t = cap.get(cv2.CAP_PROP_POS_MSEC)
        if t < start * 1000 - half:
            continue
        if t >= (start + window_s) * 1000 - half:
            break
        frames.append(f); pts.append(t)
    cap.release()
    if not frames:
        raise RuntimeError("no frames")
    # scene cuts: split the window at every cut and keep the longest shot
    hs = [hist(f) for f in frames]
    bounds = [0] + [i for i in range(1, len(frames)) if cv2.compareHist(hs[i - 1], hs[i], cv2.HISTCMP_CORREL) < 0.5] + [len(frames)]
    seg = max(zip(bounds[:-1], bounds[1:]), key=lambda ab: ab[1] - ab[0])
    cut = seg if len(bounds) > 2 else None
    frames, pts = frames[seg[0]:seg[1]], pts[seg[0]:seg[1]]
    H, W = frames[0].shape[:2]
    det = cv2.FaceDetectorYN.create(yunet, "", (W, H), 0.6, 0.3, 50)
    boxes, iods, nfaces = [], [], []
    for f in frames[::2]:
        det.setInputSize((W, H))
        _, faces = det.detect(f)
        # largest detection at the engine-like 0.6 threshold: very large faces score below 0.85 in YuNet, and small
        # spurious detections (photos on a desk, figurines) are never the largest
        faces = [] if faces is None else [r for r in faces if r[14] >= 0.6]
        n = len(faces)
        nfaces.append(n)
        if n:
            b = max(faces, key=lambda r: r[2] * r[3])
            boxes.append(b[:4]); iods.append(float(np.hypot(b[6] - b[4], b[7] - b[5])))
    if not boxes:
        raise RuntimeError("no face")
    bx = np.median(np.array(boxes), axis=0)
    iod = float(np.median(iods))
    cw = 640.0 * iod / iod_target
    ch = cw * 0.75
    if cw > W: cw, ch = W, W * 0.75
    if ch > H: ch, cw = H, H / 0.75
    cx, cy = bx[0] + bx[2] / 2, bx[1] + bx[3] / 2
    x0 = int(round(min(max(cx - cw / 2, 0), W - cw)))
    y0 = int(round(min(max(cy - ch / 2, 0), H - ch)))
    cw, ch = int(round(cw)), int(round(ch))
    item = f"{pid}-BONA_FIDE-L0-D0-T1"
    od = os.path.join(out, item)
    os.makedirs(od, exist_ok=True)
    scale = 640.0 / cw
    interp = cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC
    with open(os.path.join(od, "timestamps.txt"), "w") as ts:
        for i, (f, t) in enumerate(zip(frames, pts)):
            c = cv2.resize(f[y0:y0 + ch, x0:x0 + cw], (640, 480), interpolation=interp)
            name = f"{i + 1:05d}.jpg"
            cv2.imwrite(os.path.join(od, name), c, [cv2.IMWRITE_JPEG_QUALITY, 90])
            ts.write(f"{name} {int(round(t - pts[0]))}\n")
    return {"participant": pid, "item": item, "url": url, "src_size": [W, H], "frames": len(frames),
            "duration_s": round((pts[-1] - pts[0]) / 1000, 2), "first_pts_ms": round(pts[0], 1), "cut_at": cut,
            "crop": [x0, y0, cw, ch], "scale": round(scale, 3), "iod_src": round(iod, 1), "iod_out": round(iod * scale, 1),
            "frac_single_face": round(sum(1 for n in nfaces if n == 1) / len(nfaces), 3), "frac_face": round(sum(1 for n in nfaces if n >= 1) / len(nfaces), 3)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sources", required=True)
    ap.add_argument("--out")
    ap.add_argument("--yunet")
    ap.add_argument("--only", default="")
    ap.add_argument("--videos", help="directory holding <Pnn>.webm downloads")
    ap.add_argument("--print-urls", action="store_true", help="print '<Pnn> <start> <fps> <transcode url>' per PAD row and exit")
    a = ap.parse_args()
    if a.print_urls:
        for row in csv.DictReader(open(a.sources)):
            if 'pad' in row['used_for']:
                print(row['participant_id'], row['pad_window_start_s'], row['native_fps'], transcode_url(row['url'], row['version_used']))
        return
    if not (a.out and a.yunet and a.videos):
        ap.error("--out, --yunet and --videos are required")
    only = set(x for x in a.only.split(",") if x)
    os.makedirs(a.out, exist_ok=True)
    log = open(os.path.join(a.out, "extract-log.jsonl"), "a")
    for row in csv.DictReader(open(a.sources)):
        if 'pad' not in row['used_for'] or (only and row['participant_id'] not in only):
            continue
        try:
            r = extract(row, a.out, a.yunet, os.path.join(a.videos, row['participant_id'] + '.webm'))
        except Exception as e:
            r = {"participant": row['participant_id'], "error": repr(e)}
        print(json.dumps(r), flush=True)
        log.write(json.dumps(r) + "\n"); log.flush()


if __name__ == "__main__":
    main()
