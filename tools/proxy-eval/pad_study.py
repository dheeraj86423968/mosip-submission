#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Analysis for the November 2026 passive-PAD improvement study (docs/testing/proxy-eval/improvement-2026-11.md).

Works only on measurements: scores.jsonl files (CorpusScoreMain / PadReplayMain format) and the per-frame feature
CSV written by ``PadReplayMain dump --features``. No pixels.

Subcommands
  metrics   ISO/IEC 30107-3 figures for one or more score files, restricted to a split:
            per-species APCER with Wilson 95 % CIs, BPCER, ACER, BPCER@APCER 5 % / 10 %, non-responses.
  compare   paired comparison of two score files on the same items: per-metric difference with a subject-level
            bootstrap 95 % interval (resampling participants, so all of a person's items move together).
  s1        item-level separation (AUC per species) of S1 candidates on the passive window, from the feature CSVs.
  fit       fits fusion weights: L2-regularised logistic regression on per-item mean confidence-weighted attack
            log-odds of the fusable passive signals, optionally constrained non-negative; grouped (by participant)
            cross-validation gives the spread of the weights.

The split file is docs/testing/proxy-eval/split-2026-11.csv (participant_id,split). Never tune on 'heldout'.
"""
import argparse
import csv
import json
import math
import os
import random
import sys
from collections import defaultdict

import numpy as np

SPECIES_ORDER = ["SIM_PRINT", "SIM_PRINT_WARPED", "SIM_PRINT_CUTOUT", "SIM_SCREEN_REPLAY", "SIM_SCREEN_PHOTO"]
NON_RESPONSE = {"FACE_NOT_DETECTED", "FAILURE_TO_ACQUIRE", "QUALITY_TOO_LOW", "TIMEOUT", "SESSION_TIMEOUT",
                "DEVICE_ERROR", "DEVICE_DISCONNECTED", "ENGINE_ERROR", "INVALID_FRAME_DATA", "ACQUISITION_TIMEOUT"}


# ------------------------------------------------------------------------------------------------- helpers

def load_split(path):
    out = {}
    with open(path) as fh:
        for line in fh:
            if line.startswith("#") or line.startswith("participant_id") or not line.strip():
                continue
            pid, sp = line.strip().split(",")
            out[pid] = sp
    return out


def subject_of(rec):
    return rec.get("subject") or rec["id"].split("-")[0]


def load_scores(path, split=None, which="all"):
    recs = []
    with open(path) as fh:
        for line in fh:
            if not line.strip() or line.startswith("#"):
                continue
            r = json.loads(line)
            if which != "all" and split is not None and split.get(subject_of(r)) != which:
                continue
            if split is not None and subject_of(r) not in split:
                continue
            recs.append(r)
    return recs


def is_bona(r):
    return r["label"] in ("BONA_FIDE", "LIVE")


def is_response(r):
    if "response" in r:
        return bool(r["response"])
    return not (r.get("decision") == "ERROR" or (r.get("failure") or "") in NON_RESPONSE)


def effective_score(r):
    """IsoReportMain.effectiveScore: a rejection the passive threshold does not control scores 0."""
    d, f = r.get("decision"), r.get("failure")
    if not d or d == "LIVE" or f == "LIVENESS_BELOW_THRESHOLD":
        return r["score"]
    return 0.0


def wilson(k, n, z=1.959963984540054):
    if n == 0:
        return (float("nan"), float("nan"))
    p = k / n
    den = 1 + z * z / n
    c = (p + z * z / (2 * n)) / den
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / den
    return (max(0.0, c - h), min(1.0, c + h))


def metrics(recs, thr):
    bona = [effective_score(r) for r in recs if is_bona(r) and is_response(r)]
    bona_nr = sum(1 for r in recs if is_bona(r) and not is_response(r))
    sp = defaultdict(list)
    sp_nr = defaultdict(int)
    for r in recs:
        if is_bona(r):
            continue
        if is_response(r):
            sp[r["label"]].append(effective_score(r))
        else:
            sp_nr[r["label"]] += 1
    out = {"threshold": thr, "species": {}}
    for s in sorted(set(sp) | set(sp_nr), key=lambda x: SPECIES_ORDER.index(x) if x in SPECIES_ORDER else 99):
        acc = sum(1 for v in sp[s] if v >= thr)
        n = len(sp[s])
        out["species"][s] = {"accepted": acc, "n": n, "apcer": acc / n if n else float("nan"), "ci": wilson(acc, n),
                             "nonResponses": sp_nr[s]}
    rej = sum(1 for v in bona if v < thr)
    out["bpcer"] = {"rejected": rej, "n": len(bona), "value": rej / len(bona) if bona else float("nan"),
                    "ci": wilson(rej, len(bona)), "nonResponses": bona_nr}
    maxap = max((v["apcer"] for v in out["species"].values()), default=float("nan"))
    out["maxApcer"] = maxap
    out["acer"] = (maxap + out["bpcer"]["value"]) / 2
    # BPCER at APCER <= target: lowest BPCER over every threshold (accepted when score >= t)
    cands = sorted(set(bona) | set(v for l in sp.values() for v in l) | {0.0, 1.0 + 1e-9})
    for target, key in ((0.05, "bpcerAtApcer5"), (0.10, "bpcerAtApcer10")):
        best = (float("nan"), float("nan"))
        for t in cands:
            ma = max(sum(1 for v in l if v >= t) / len(l) for l in sp.values() if l)
            if ma <= target + 1e-12:
                b = sum(1 for v in bona if v < t) / len(bona)
                if math.isnan(best[0]) or b < best[0]:
                    best = (b, t)
        out[key] = best[0]
        out[key + "Threshold"] = best[1]
    return out


def fmt_pct(v):
    return "n/a" if v is None or (isinstance(v, float) and math.isnan(v)) else f"{100 * v:.1f} %"


def fmt_ci(ci):
    return f"{100 * ci[0]:.1f}–{100 * ci[1]:.1f} %"


def print_metrics(name, m):
    print(f"### {name} (threshold {m['threshold']})")
    print("| Species | Accepted / responses | APCER | Wilson 95 % CI | Non-responses |")
    print("|---|---|---|---|---|")
    for s, v in m["species"].items():
        print(f"| {s} | {v['accepted']} / {v['n']} | {fmt_pct(v['apcer'])} | {fmt_ci(v['ci'])} | {v['nonResponses']} |")
    b = m["bpcer"]
    print(f"| **Bona fide** | {b['rejected']} rejected / {b['n']} | **BPCER {fmt_pct(b['value'])}** | {fmt_ci(b['ci'])} | {b['nonResponses']} |")
    print(f"\nmax APCER {fmt_pct(m['maxApcer'])}, ACER {fmt_pct(m['acer'])}, BPCER@APCER≤5 % {fmt_pct(m['bpcerAtApcer5'])}, "
          f"BPCER@APCER≤10 % {fmt_pct(m['bpcerAtApcer10'])}\n")


def cmd_metrics(a):
    split = load_split(a.split) if a.split else None
    res = {}
    for f in a.scores:
        recs = load_scores(f, split, a.which)
        m = metrics(recs, a.threshold if a.threshold is not None else threshold_from_header(f))
        res[f] = m
        print_metrics(f"{os.path.basename(f)} [{a.which}, {len({subject_of(r) for r in recs})} subjects]", m)
    if a.json:
        with open(a.json, "w") as fh:
            json.dump(res, fh, indent=1, default=float)


def threshold_from_header(path):
    with open(path) as fh:
        first = fh.readline()
    if first.startswith("#") and "passiveThreshold=" in first:
        return float(first.split("passiveThreshold=")[1].split()[0])
    return 0.80


# ------------------------------------------------------------------------------------------------- compare

def cmd_compare(a):
    split = load_split(a.split) if a.split else None
    A = {r["id"]: r for r in load_scores(a.base, split, a.which)}
    B = {r["id"]: r for r in load_scores(a.new, split, a.which)}
    ids = sorted(set(A) & set(B))
    ta = a.base_threshold if a.base_threshold is not None else threshold_from_header(a.base)
    tb = a.new_threshold if a.new_threshold is not None else threshold_from_header(a.new)
    subjects = sorted({subject_of(A[i]) for i in ids})
    by_subj = defaultdict(list)
    for i in ids:
        by_subj[subject_of(A[i])].append(i)

    def stats(sel):
        ra = [A[i] for i in sel]
        rb = [B[i] for i in sel]
        ma, mb = metrics(ra, ta), metrics(rb, tb)
        d = {"bpcer": mb["bpcer"]["value"] - ma["bpcer"]["value"], "maxApcer": mb["maxApcer"] - ma["maxApcer"],
             "acer": mb["acer"] - ma["acer"]}
        for s in ma["species"]:
            if s in mb["species"]:
                d["apcer:" + s] = mb["species"][s]["apcer"] - ma["species"][s]["apcer"]
        prints = [s for s in ma["species"] if "PRINT" in s]
        if prints:
            pa = sum(ma["species"][s]["accepted"] for s in prints) / max(1, sum(ma["species"][s]["n"] for s in prints))
            pb = sum(mb["species"][s]["accepted"] for s in prints) / max(1, sum(mb["species"][s]["n"] for s in prints))
            d["apcer:ALL_PRINTS"] = pb - pa
        screens = [s for s in ma["species"] if "SCREEN" in s]
        if screens:
            pa = sum(ma["species"][s]["accepted"] for s in screens) / max(1, sum(ma["species"][s]["n"] for s in screens))
            pb = sum(mb["species"][s]["accepted"] for s in screens) / max(1, sum(mb["species"][s]["n"] for s in screens))
            d["apcer:ALL_SCREENS"] = pb - pa
        return d

    point = stats(ids)
    rng = random.Random(a.seed)
    boots = defaultdict(list)
    for _ in range(a.boot):
        sel = []
        for s in (rng.choice(subjects) for _ in subjects):
            sel.extend(by_subj[s])
        try:
            for k, v in stats(sel).items():
                if not math.isnan(v):
                    boots[k].append(v)
        except (ValueError, ZeroDivisionError):
            continue
    print(f"paired comparison on {len(ids)} items, {len(subjects)} subjects [{a.which}]; "
          f"{a.boot} subject-level bootstrap resamples; difference = new - base")
    print("| Metric | Difference | Bootstrap 95 % interval |")
    print("|---|---|---|")
    out = {}
    for k, v in point.items():
        b = sorted(boots[k])
        lo, hi = (b[int(0.025 * len(b))], b[int(0.975 * len(b)) - 1]) if b else (float("nan"), float("nan"))
        out[k] = {"diff": v, "ci": [lo, hi]}
        print(f"| {k} | {100 * v:+.1f} pts | {100 * lo:+.1f} to {100 * hi:+.1f} |")
    if a.json:
        with open(a.json, "w") as fh:
            json.dump(out, fh, indent=1)


# ------------------------------------------------------------------------------------------------- features

def logit(p, eps=1e-4):
    p = min(1 - eps, max(eps, p))
    return math.log(p / (1 - p))


def passive_window(rows, min_quality=0.4, window_ms=2500, min_frames=8):
    """Frames the orchestrator would fuse in passive mode: from the first single-face frame that passes the quality
    floor until >= min_frames gated frames and >= window_ms have elapsed (approximation of QualityGate)."""
    out = []
    t0 = None
    for r in rows:
        if r["faces"] != "1" or not r["quality"] or float(r["quality"]) < min_quality:
            continue
        t = int(r["t_ms"])
        if t0 is None:
            t0 = t
        out.append(r)
        if len(out) >= min_frames and t - t0 >= window_ms:
            break
    return out


def read_features(paths):
    items = defaultdict(list)
    for p in paths:
        with open(p) as fh:
            for r in csv.DictReader(fh):
                items[r["item"]].append(r)
    return items


def s1_logit_from_views(r, views):
    pr = np.mean([float(r["preal_" + v]) for v in views])
    return logit(1 - pr, 1e-6)


def item_features(items, split, which, signals, s1_views=None, window="passive"):
    X, y, groups, ids, labels = [], [], [], [], []
    for item, rows in items.items():
        subj = rows[0]["subject"]
        if split is not None and (subj not in split or (which != "all" and split[subj] != which)):
            continue
        rows = passive_window(rows) if window == "passive" else [r for r in rows if r["faces"] == "1"]
        if len(rows) < 3:
            continue
        feats = []
        for s in signals:
            vals = []
            for r in rows:
                if s == "cnn_texture" and s1_views:
                    if not r.get("preal_" + s1_views[0]):
                        continue
                    c = float(r["cnn_texture_c"]) if r["cnn_texture_c"] else 0.0
                    vals.append(c * s1_logit_from_views(r, s1_views))
                else:
                    if not r.get(s + "_p"):
                        continue
                    vals.append(float(r[s + "_c"]) * logit(float(r[s + "_p"])))
            feats.append(float(np.mean(vals)) if vals else 0.0)
        X.append(feats)
        y.append(0 if rows[0]["label"] == "BONA_FIDE" else 1)
        groups.append(subj)
        ids.append(item)
        labels.append(rows[0]["label"])
    return np.array(X), np.array(y), np.array(groups), ids, labels


def auc(pos, neg):
    if not pos or not neg:
        return float("nan")
    s = 0.0
    for p in pos:
        for n in neg:
            s += 1.0 if p > n else 0.5 if p == n else 0.0
    return s / (len(pos) * len(neg))


def cmd_s1(a):
    split = load_split(a.split)
    items = read_features(a.features)
    print(f"S1 candidates, item-level mean c*attack-logit over the {a.window} window, split={a.which}")
    print("| Candidate | " + " | ".join(SPECIES_ORDER) + " | all prints | all screens | BF median p |")
    print("|---|" + "---|" * (len(SPECIES_ORDER) + 3))
    res = {}
    for cand in a.candidates:
        views = cand.split("+")
        X, y, g, ids, labels = item_features(items, split, a.which, ["cnn_texture"], views, a.window)
        x = X[:, 0]
        neg = [v for v, l in zip(x, labels) if l == "BONA_FIDE"]
        row = []
        for s in SPECIES_ORDER:
            row.append(auc([v for v, l in zip(x, labels) if l == s], neg))
        ap = auc([v for v, l in zip(x, labels) if "PRINT" in l], neg)
        asc = auc([v for v, l in zip(x, labels) if "SCREEN" in l], neg)
        med = 1 / (1 + math.exp(-float(np.median(neg))))
        res[cand] = {"auc": dict(zip(SPECIES_ORDER, row)), "prints": ap, "screens": asc, "bfMedianP": med}
        print(f"| {cand} | " + " | ".join(f"{v:.3f}" for v in row) + f" | {ap:.3f} | {asc:.3f} | {med:.3f} |")
    if a.json:
        with open(a.json, "w") as fh:
            json.dump(res, fh, indent=1)


# ------------------------------------------------------------------------------------------------- fit

def fit_lr(X, y, l2, nonneg, sample_weight):
    from scipy.optimize import minimize
    n, d = X.shape

    def f(wb):
        w, b = wb[:d], wb[d]
        z = X @ w + b
        # log(1+exp(-y'z)) with y' in {-1,1}
        ys = 2 * y - 1
        m = -ys * z
        loss = np.where(m > 30, m, np.log1p(np.exp(np.minimum(m, 30))))
        L = np.sum(sample_weight * loss) / np.sum(sample_weight) + 0.5 * l2 * np.sum(w * w)
        s = 1 / (1 + np.exp(-m))
        gz = -ys * s * sample_weight / np.sum(sample_weight)
        gw = X.T @ gz + l2 * w
        gb = np.sum(gz)
        return L, np.concatenate([gw, [gb]])

    bounds = [(0, None) if nonneg else (None, None)] * d + [(None, None)]
    r = minimize(f, np.zeros(d + 1), jac=True, method="L-BFGS-B", bounds=bounds)
    return r.x[:d], r.x[d]


def balanced_weights(y):
    n1, n0 = max(1, y.sum()), max(1, (1 - y).sum())
    return np.where(y == 1, 0.5 / n1, 0.5 / n0) * len(y)


def cmd_fit(a):
    split = load_split(a.split)
    items = read_features(a.features)
    views = a.s1.split("+") if a.s1 else None
    X, y, g, ids, labels = item_features(items, split, "train", a.signals, views, a.window)
    if a.standardize:
        sd = X.std(axis=0)
        sd[sd == 0] = 1
    else:
        sd = np.ones(X.shape[1])
    Xs = X / sd
    sw = balanced_weights(y)
    subjects = sorted(set(g))
    # choose L2 strength by grouped CV log-loss
    rng = random.Random(a.seed)
    best = None
    folds_rep = []
    for rep in range(a.repeats):
        s = subjects[:]
        rng.shuffle(s)
        folds_rep.append([set(s[i::a.folds]) for i in range(a.folds)])
    cv_table = []
    for l2 in a.l2:
        losses, coefs = [], []
        for folds in folds_rep:
            for test in folds:
                tr = np.array([gg not in test for gg in g])
                te = ~tr
                w, b = fit_lr(Xs[tr], y[tr], l2, a.nonneg, balanced_weights(y[tr]))
                z = Xs[te] @ w + b
                p = 1 / (1 + np.exp(-z))
                p = np.clip(p, 1e-6, 1 - 1e-6)
                ll = -np.mean(balanced_weights(y[te]) * (y[te] * np.log(p) + (1 - y[te]) * np.log(1 - p)))
                losses.append(ll)
                coefs.append(w / sd)
        coefs = np.array(coefs)
        cv_table.append((l2, float(np.mean(losses)), float(np.std(losses)), coefs))
        if best is None or np.mean(losses) < best[1]:
            best = (l2, float(np.mean(losses)))
    w, b = fit_lr(Xs, y, best[0], a.nonneg, sw)
    w = w / sd
    print(f"fusion fit on train split: {len(y)} items ({int((1 - y).sum())} bona fide, {int(y.sum())} attacks), "
          f"{len(subjects)} subjects; features = per-item mean c*logit(p) over the {a.window} window; S1 = {a.s1 or 'stored'}")
    print(f"{'nonnegative' if a.nonneg else 'unconstrained'} L2 logistic regression, class-balanced; "
          f"{a.folds}-fold grouped CV x{a.repeats}")
    print("| L2 | CV log-loss (mean ± sd) | " + " | ".join(a.signals) + " |")
    print("|---|---|" + "---|" * len(a.signals))
    for l2, m, s, coefs in cv_table:
        cells = [f"{np.mean(coefs[:, i]):.3f} ± {np.std(coefs[:, i]):.3f}" for i in range(len(a.signals))]
        print(f"| {l2} | {m:.4f} ± {s:.4f} | " + " | ".join(cells) + (" |  ← chosen" if l2 == best[0] else " |"))
    print(f"\nfinal fit (L2={best[0]}): intercept {b:.3f}; weights " + ", ".join(f"{s}={v:.4f}" for s, v in zip(a.signals, w)))
    rel = {s: (v / w[0] if w[0] > 0 else float('nan')) for s, v in zip(a.signals, w)}
    print("relative to the first signal: " + ", ".join(f"{s}={v:.3f}" for s, v in rel.items()))
    if a.json:
        chosen = [c for c in cv_table if c[0] == best[0]][0][3]
        with open(a.json, "w") as fh:
            json.dump({"signals": a.signals, "l2": best[0], "intercept": b, "weights": dict(zip(a.signals, w.tolist())),
                       "relative": rel, "cvMean": dict(zip(a.signals, chosen.mean(axis=0).tolist())),
                       "cvSd": dict(zip(a.signals, chosen.std(axis=0).tolist())),
                       "cvMin": dict(zip(a.signals, chosen.min(axis=0).tolist())),
                       "cvMax": dict(zip(a.signals, chosen.max(axis=0).tolist())),
                       "items": len(y), "subjects": len(subjects), "s1": a.s1, "window": a.window,
                       "nonneg": a.nonneg, "cvTable": [(l2, m, s) for l2, m, s, _ in cv_table]}, fh, indent=1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("metrics")
    m.add_argument("scores", nargs="+")
    m.add_argument("--split")
    m.add_argument("--which", default="all", choices=["all", "train", "heldout"])
    m.add_argument("--threshold", type=float)
    m.add_argument("--json")
    c = sub.add_parser("compare")
    c.add_argument("base")
    c.add_argument("new")
    c.add_argument("--split")
    c.add_argument("--which", default="all", choices=["all", "train", "heldout"])
    c.add_argument("--base-threshold", type=float)
    c.add_argument("--new-threshold", type=float)
    c.add_argument("--boot", type=int, default=2000)
    c.add_argument("--seed", type=int, default=2026)
    c.add_argument("--json")
    s = sub.add_parser("s1")
    s.add_argument("--features", nargs="+", required=True)
    s.add_argument("--split", required=True)
    s.add_argument("--which", default="train", choices=["all", "train", "heldout"])
    s.add_argument("--window", default="passive", choices=["passive", "full"])
    s.add_argument("--candidates", nargs="+", required=True)
    s.add_argument("--json")
    f = sub.add_parser("fit")
    f.add_argument("--features", nargs="+", required=True)
    f.add_argument("--split", required=True)
    f.add_argument("--signals", nargs="+", default=["cnn_texture", "moire", "color_stats"])
    f.add_argument("--s1", default="")
    f.add_argument("--window", default="passive", choices=["passive", "full"])
    f.add_argument("--nonneg", action="store_true")
    f.add_argument("--standardize", action="store_true")
    f.add_argument("--l2", type=float, nargs="+", default=[0.001, 0.01, 0.1, 1.0])
    f.add_argument("--folds", type=int, default=5)
    f.add_argument("--repeats", type=int, default=20)
    f.add_argument("--seed", type=int, default=2026)
    f.add_argument("--json")
    a = ap.parse_args()
    {"metrics": cmd_metrics, "compare": cmd_compare, "s1": cmd_s1, "fit": cmd_fit}[a.cmd](a)


if __name__ == "__main__":
    main()
