#!/usr/bin/env python3
"""Generate the docs/ux SVG mockups from the 12-ux-spec.md states and copy."""
import math
import sys
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)

# ---- design tokens -------------------------------------------------------
C = dict(
    navy="#0F2A57", primary="#1D5FB4", primaryHover="#174E95", primaryTint="#E7F0FB",
    bg="#F3F6FA", surface="#FFFFFF", border="#D6DEE9", text="#16202E", muted="#5A6A80",
    faint="#8A98AB", preview="#1A2433", previewHi="#26344A",
    amber="#D98A00", amberTint="#FFF4DD", green="#1E8A4C", greenTint="#E5F5EC",
    red="#C0392B", redTint="#FCEBEA", grey="#9AA6B6", neutral="#4F6B8F", neutralTint="#EAF0F7",
    skin="#9FB0C6", skinDark="#7D90AA", hair="#4A5A72", shirt="#3A4D69",
)
FONT = "sans-serif"


def t(x, y, s, size=16, weight="normal", fill=None, anchor="start", extra=""):
    return (f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" font-weight="{weight}" '
            f'fill="{fill or C["text"]}" text-anchor="{anchor}" {extra}>{escape(s)}</text>')


def rect(x, y, w, h, fill, rx=0, stroke=None, sw=1, extra=""):
    st = f' stroke="{stroke}" stroke-width="{sw}"' if stroke else ""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}"{st} {extra}/>'


def button(x, y, w, label, kind="primary", h=44, size=16):
    if kind == "primary":
        bg, fg, st = C["primary"], "#FFFFFF", None
    elif kind == "secondary":
        bg, fg, st = C["surface"], C["primary"], C["primary"]
    else:  # link
        return t(x, y + h / 2 + 6, label, size, "bold", C["primary"], extra='text-decoration="underline"')
    return (rect(x, y, w, h, bg, 6, st, 1.5) +
            t(x + w / 2, y + h / 2 + 6, label, size, "bold", fg, "middle"))


def dot(cx, cy, r, fill, stroke=None):
    st = f' stroke="{stroke}" stroke-width="2"' if stroke else ""
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}"{st}/>'


# ---- icons (24x24 base, scaled) -----------------------------------------
def icon_check(cx, cy, r, color):
    return (dot(cx, cy, r, color) +
            f'<path d="M{cx - r * .45},{cy + r * .02} L{cx - r * .1},{cy + r * .38} L{cx + r * .5},{cy - r * .35}" '
            f'fill="none" stroke="#fff" stroke-width="{max(3, r * .18):.1f}" stroke-linecap="round" stroke-linejoin="round"/>')


def icon_lock(cx, cy, s, color):
    return (f'<g fill="none" stroke="{color}" stroke-width="{s * .09:.1f}" stroke-linecap="round">'
            f'<path d="M{cx - s * .28},{cy - s * .05} v{-s * .15} a{s * .28},{s * .28} 0 0 1 {s * .56},0 v{s * .15}"/></g>'
            + rect(cx - s * .4, cy - s * .05, s * .8, s * .55, color, s * .08)
            + dot(cx, cy + s * .17, s * .07, "#fff")
            + rect(cx - s * .025, cy + s * .2, s * .05, s * .14, "#fff"))


def icon_device_off(cx, cy, s, color):
    # webcam with slash
    return (f'<g fill="none" stroke="{color}" stroke-width="{s * .07:.1f}" stroke-linecap="round" stroke-linejoin="round">'
            f'<circle cx="{cx}" cy="{cy - s * .1}" r="{s * .3}"/><circle cx="{cx}" cy="{cy - s * .1}" r="{s * .11}"/>'
            f'<path d="M{cx - s * .22},{cy + s * .42} h{s * .44} M{cx},{cy + s * .2} v{s * .22}"/>'
            f'<path d="M{cx - s * .45},{cy - s * .5} L{cx + s * .45},{cy + s * .45}" stroke="{C["red"]}"/></g>')


def icon_neutral_face(cx, cy, s):
    # neutral, non-alarming: face outline in a soft circle
    return (dot(cx, cy, s * .5, C["neutralTint"]) +
            f'<g fill="none" stroke="{C["neutral"]}" stroke-width="{s * .06:.1f}" stroke-linecap="round">'
            f'<circle cx="{cx}" cy="{cy}" r="{s * .3}"/>'
            f'<path d="M{cx - s * .12},{cy + s * .1} h{s * .24}"/></g>'
            + dot(cx - s * .11, cy - s * .07, s * .035, C["neutral"]) + dot(cx + s * .11, cy - s * .07, s * .035, C["neutral"]))


def icon_info(cx, cy, s, color):
    return (dot(cx, cy, s * .5, color) +
            rect(cx - s * .05, cy - s * .05, s * .1, s * .3, "#fff", s * .03) + dot(cx, cy - s * .2, s * .06, "#fff"))


def icon_head_turn(cx, cy, s, direction="left", color=None):
    """Face outline with a curved arrow. The arrow follows the subject convention on the mirrored
    preview: TURN_LEFT points to the screen's left (spec §4)."""
    color = color or C["primary"]
    d = -1 if direction == "left" else 1
    sw = max(2.5, s * .055)
    fx = cx + d * s * .07
    return (f'<g fill="none" stroke="{color}" stroke-width="{sw:.1f}" stroke-linecap="round" stroke-linejoin="round">'
            f'<ellipse cx="{cx}" cy="{cy + s * .1}" rx="{s * .22}" ry="{s * .28}"/>'
            f'<path d="M{fx - s * .05},{cy + s * .1} l{d * s * .06},{s * .09} h{-d * s * .05}"/>'
            f'<path d="M{cx + s * .3},{cy - s * .3} Q{cx},{cy - s * .48} {cx - s * .3},{cy - s * .3}" '
            + ('' if d < 0 else '') + '/>'
            + (f'<path d="M{cx - s * .3},{cy - s * .3} l{s * .02},{-s * .12} M{cx - s * .3},{cy - s * .3} l{s * .12},{s * .02}"/>' if d < 0 else
               f'<path d="M{cx + s * .3},{cy - s * .3} l{-s * .02},{-s * .12} M{cx + s * .3},{cy - s * .3} l{-s * .12},{s * .02}"/>')
            + '</g>'
            + dot(fx - s * .09, cy + s * .02, s * .03, color) + dot(fx + s * .07, cy + s * .02, s * .03, color))


def icon_eye(cx, cy, s, closed=False, color=None):
    color = color or C["primary"]
    if closed:
        return (f'<g fill="none" stroke="{color}" stroke-width="{s * .07:.1f}" stroke-linecap="round">'
                f'<path d="M{cx - s * .4},{cy} q{s * .4},{s * .3} {s * .8},0"/>'
                f'<path d="M{cx - s * .25},{cy + s * .12} l{-s * .06},{s * .1} M{cx},{cy + s * .16} v{s * .12} M{cx + s * .25},{cy + s * .12} l{s * .06},{s * .1}"/></g>')
    return (f'<g fill="none" stroke="{color}" stroke-width="{s * .07:.1f}" stroke-linecap="round">'
            f'<path d="M{cx - s * .42},{cy} q{s * .42},{-s * .4} {s * .84},0 q{-s * .42},{s * .4} {-s * .84},0z"/></g>'
            + dot(cx, cy, s * .13, color))


def icon_pause(cx, cy, s, color="#fff"):
    return rect(cx - s * .3, cy - s * .35, s * .2, s * .7, color, 2) + rect(cx + s * .1, cy - s * .35, s * .2, s * .7, color, 2)


def note(x, y, w, lines, size=13):
    """Designer annotation, not product UI: purple dashed box."""
    h = 40 + len(lines) * (size + 7)
    o = rect(x, y, w, h, "#F7F0FB", 8, "#8E44AD", 1.5, 'stroke-dasharray="5 4"')
    o += t(x + 14, y + 22, "Design note", size, "bold", "#8E44AD")
    for i, ln in enumerate(lines):
        o += t(x + 14, y + 22 + (i + 1) * (size + 7), ln, size, "normal", "#5B2C6F")
    return o


def icon_assist_chip(x, y):
    return rect(x, y, 160, 30, "rgba(255,255,255,0.92)", 15) + t(x + 80, y + 20, "Assisted capture", 14, "bold", C["navy"], "middle")


# ---- stylised face (vector illustration, not a photo) ----------------------
def face(cx, cy, scale=1.0, yaw=0.0, eyes_closed=False, smile=False):
    s = scale
    off = yaw * 55 * s  # horizontal shift of features for a head turn
    g = [f'<g>']
    # shoulders
    g.append(f'<path d="M{cx - 190 * s},{cy + 260 * s} q{20 * s},{-120 * s} {190 * s},{-125 * s} '
             f'q{170 * s},{5 * s} {190 * s},{125 * s} z" fill="{C["shirt"]}"/>')
    g.append(rect(cx - 38 * s, cy + 90 * s, 76 * s, 60 * s, C["skinDark"]))
    # head
    g.append(f'<ellipse cx="{cx + off * .3}" cy="{cy}" rx="{108 * s * (1 - abs(yaw) * .08)}" ry="{140 * s}" fill="{C["skin"]}"/>')
    g.append(f'<path d="M{cx - 110 * s + off * .2},{cy - 30 * s} q{-4 * s},{-120 * s} {110 * s},{-112 * s} '
             f'q{114 * s},{-6 * s} {112 * s},{110 * s} q{-30 * s},{-70 * s} {-110 * s},{-66 * s} '
             f'q{-80 * s},{-2 * s} {-112 * s},{68 * s} z" fill="{C["hair"]}"/>')
    # eyes
    for ex in (-38, 38):
        x = cx + ex * s + off
        if eyes_closed:
            g.append(f'<path d="M{x - 14 * s},{cy - 8 * s} q{14 * s},{8 * s} {28 * s},0" fill="none" stroke="{C["hair"]}" stroke-width="{3 * s}" stroke-linecap="round"/>')
        else:
            g.append(f'<ellipse cx="{x}" cy="{cy - 10 * s}" rx="{13 * s}" ry="{7 * s}" fill="#E9EEF5"/>')
            g.append(dot(x + yaw * 4 * s, cy - 10 * s, 5 * s, C["hair"]))
        g.append(f'<path d="M{x - 16 * s},{cy - 30 * s} q{16 * s},{-8 * s} {32 * s},0" fill="none" stroke="{C["hair"]}" stroke-width="{4 * s}" stroke-linecap="round"/>')
    # nose and mouth
    g.append(f'<path d="M{cx + off * 1.2},{cy - 4 * s} l{-6 * s + yaw * 6 * s},{34 * s} h{12 * s}" fill="none" stroke="{C["skinDark"]}" stroke-width="{3 * s}" stroke-linecap="round" stroke-linejoin="round"/>')
    if smile:
        g.append(f'<path d="M{cx - 30 * s + off},{cy + 58 * s} q{30 * s},{24 * s} {60 * s},0" fill="none" stroke="{C["hair"]}" stroke-width="{4 * s}" stroke-linecap="round"/>')
    else:
        g.append(f'<path d="M{cx - 24 * s + off},{cy + 64 * s} q{24 * s},{6 * s} {48 * s},0" fill="none" stroke="{C["hair"]}" stroke-width="{4 * s}" stroke-linecap="round"/>')
    g.append('</g>')
    return "".join(g)


def oval(cx, cy, rx, ry, state):
    """Oval guide. Colour is never the only cue: pattern and thickness change too (spec §5)."""
    if state == "idle":
        return f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="none" stroke="{C["grey"]}" stroke-width="3" stroke-dasharray="10 10"/>'
    if state == "positioning":
        return f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="none" stroke="{C["amber"]}" stroke-width="5" stroke-dasharray="14 10"/>'
    if state == "checking":
        # dashed amber with a sweep arc (animated in the product; static here)
        a0, a1 = math.radians(-100), math.radians(-10)
        x0, y0 = cx + rx * math.cos(a0), cy + ry * math.sin(a0)
        x1, y1 = cx + rx * math.cos(a1), cy + ry * math.sin(a1)
        return (f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="none" stroke="{C["amber"]}" stroke-width="5" stroke-dasharray="14 10"/>'
                f'<path d="M{x0:.1f},{y0:.1f} A{rx},{ry} 0 0 1 {x1:.1f},{y1:.1f}" fill="none" stroke="#FFC24D" stroke-width="9" stroke-linecap="round"/>')
    if state == "challenge":
        return f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="none" stroke="{C["green"]}" stroke-width="6"/>'
    if state == "passed":
        return (f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="none" stroke="{C["green"]}" stroke-width="9"/>'
                + icon_check(cx + rx * .72, cy - ry * .78, 26, C["green"]))
    if state == "failed":
        return f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="none" stroke="{C["red"]}" stroke-width="6" stroke-dasharray="4 8" stroke-linecap="round"/>'
    if state == "neutral":
        return f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="none" stroke="{C["grey"]}" stroke-width="4"/>'
    return ""


def preview(x, y, w, h, state="checking", face_kw=None, show_face=True, paused=False, off=False, mask_id="pv", chip=None, overlay=None):
    cx, cy = x + w / 2, y + h / 2
    rx, ry = min(w, h) * .3, min(w, h) * .4
    p = [f'<defs><clipPath id="{mask_id}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10"/></clipPath>'
         f'<linearGradient id="{mask_id}g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{C["previewHi"]}"/>'
         f'<stop offset="1" stop-color="{C["preview"]}"/></linearGradient></defs>']
    p.append(rect(x, y, w, h, f'url(#{mask_id}g)', 10))
    if off:
        p.append(rect(x, y, w, h, C["preview"], 10))
    p.append(f'<g clip-path="url(#{mask_id})">')
    if show_face and not off:
        p.append(face(cx, cy + 10, min(w, h) / 470, **(face_kw or {})))
    if paused:
        p.append(rect(x, y, w, h, "rgba(15,22,34,0.55)"))
    p.append('</g>')
    p.append(oval(cx, cy, rx, ry, state))
    if paused:
        p.append(rect(x + 16, y + 16, 104, 30, "rgba(255,255,255,0.9)", 15) + icon_pause(x + 36, y + 31, 16, C["navy"])
                 + t(x + 52, y + 36, "Paused", 14, "bold", C["navy"]))
    if chip:
        p.append(icon_assist_chip(x + w - 176, y + 16))
    if overlay:
        p.append(overlay)
    return "".join(p)


def progress(x, y, w, frac, label=True, size=14):
    p = rect(x, y, w, 8, C["border"], 4) + (rect(x, y, max(8, w * frac), 8, C["primary"], 4) if frac > 0 else "")
    if label:
        p += t(x + w, y + 30, f"{int(round(frac * 100))} %", size, "bold", C["muted"], "end")
    return p


def dots(x, y, n, done, current, r=9, gap=30):
    out = []
    for i in range(n):
        cx = x + i * gap
        if i < done:
            out.append(icon_check(cx, y, r + 1, C["green"]))
        elif i == current:
            out.append(dot(cx, y, r, C["primary"]))
        else:
            out.append(dot(cx, y, r - 1, C["surface"], C["grey"]))
    return "".join(out)


def countdown(cx, cy, r, secs, total, size=18):
    frac = secs / total
    a = -math.pi / 2 + 2 * math.pi * frac
    large = 1 if frac > .5 else 0
    x1, y1 = cx + r * math.cos(a), cy + r * math.sin(a)
    return (f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="{C["border"]}" stroke-width="5"/>'
            f'<path d="M{cx},{cy - r} A{r},{r} 0 {large} 1 {x1:.1f},{y1:.1f}" fill="none" stroke="{C["primary"]}" stroke-width="5" stroke-linecap="round"/>'
            + t(cx, cy + size * .36, f"{secs} s", size, "bold", C["text"], "middle"))


def svg_doc(w, h, body, title, desc):
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" '
            f'font-family="{FONT}" role="img" aria-labelledby="title desc">\n'
            f'<title id="title">{escape(title)}</title>\n<desc id="desc">{escape(desc)}</desc>\n'
            f'{body}\n</svg>\n')


# ---- desktop shell ---------------------------------------------------------
DW, DH = 1280, 800


def desktop_shell(right, *, crumb="Resident registration › Biometrics › Face", online=True, attempt="Attempt 1 of 3",
                  liveness_on=True, preview_svg="", progress_frac=None, footer_extra="", state_tag="", cancel=True):
    b = [rect(0, 0, DW, DH, C["bg"])]
    # app header
    b.append(rect(0, 0, DW, 56, C["navy"]))
    b.append(dot(32, 28, 13, C["primary"]) + t(32, 33, "M", 14, "bold", "#fff", "middle"))
    b.append(t(56, 34, "MOSIP Registration Client", 18, "bold", "#FFFFFF"))
    b.append(t(DW - 24, 34, "Operator: officer01  ·  Centre 10002", 14, "normal", "#C9D6EA", "end"))
    # breadcrumb + status strip
    b.append(rect(0, 56, DW, 52, C["surface"]) + f'<line x1="0" y1="108" x2="{DW}" y2="108" stroke="{C["border"]}"/>')
    b.append(t(24, 88, crumb, 17, "bold", C["text"]))
    sx = DW - 24
    lv = "Liveness check: on" if liveness_on else "Liveness check: disabled by policy"
    b.append(t(sx, 88, lv, 14, "normal", C["muted"], "end"))
    sx -= 14 * len(lv) * .52 + 24
    if attempt:
        b.append(t(sx, 88, attempt, 14, "bold", C["text"], "end"))
        sx -= 14 * len(attempt) * .55 + 24
    on_label = "Online" if online else "Offline"
    b.append(t(sx, 88, on_label, 14, "bold", C["green"] if online else C["muted"], "end"))
    lx = sx - 14 * len(on_label) * .6 - 12
    b.append(dot(lx, 83, 6, C["green"]) if online else dot(lx, 83, 6, C["surface"], C["muted"]))
    # panes
    b.append(rect(24, 128, 616, 572, C["surface"], 12, C["border"]))
    b.append(rect(660, 128, 596, 572, C["surface"], 12, C["border"]))
    b.append(preview_svg)
    if progress_frac is not None:
        b.append(progress(52, 640, 560, progress_frac))
        b.append(t(52, 670, "Progress", 14, "normal", C["muted"]))
    b.append(right)
    # footer
    b.append(rect(0, 720, DW, 80, C["surface"]) + f'<line x1="0" y1="720" x2="{DW}" y2="720" stroke="{C["border"]}"/>')
    if cancel:
        b.append(button(24, 738, 120, "Cancel", "secondary"))
    b.append(t(DW - 24, 766, "Diagnostics", 15, "bold", C["primary"], "end", 'text-decoration="underline"'))
    b.append(footer_extra)
    if state_tag:
        b.append(t(DW / 2, 766, f"Mockup · {state_tag}", 13, "normal", C["faint"], "middle"))
    return "".join(b)


def dpreview(**kw):
    return preview(52, 156, 560, 460, **kw)


def write(name, w, h, body, title, desc):
    (OUT / name).write_text(svg_doc(w, h, body, title, desc), encoding="utf-8")


RX = 700  # right pane text x

# 1. Resident capture, passive check (S3)
right = (t(RX, 220, "Checking face liveness…", 30, "bold") +
         t(RX, 258, "Keep looking at the camera", 20, "normal", C["muted"]) +
         t(RX, 320, "Liveness check: on", 16, "normal", C["muted"]) +
         note(RX, 560, 516, ["No button and no instruction beyond positioning during the", "passive window (spec §1.1). Oval: amber, dashed, with sweep."]))
write("desktop-01-resident-passive-check.svg", DW, DH,
      desktop_shell(right, preview_svg=dpreview(state="checking", mask_id="p1"), progress_frac=.6, state_tag="S3 Checking"),
      "Desktop resident capture: passive liveness check",
      "State S3. Live preview with an amber dashed oval guide and a sweep. Copy: Checking face liveness…, Keep looking at the camera. Progress 60 percent.")


# 2. Challenge (S4 + S4f)
def challenge_right(prompt, icon_svg, done, cur, n, secs, feedback, y0=196):
    return (t(RX, y0, f"Action {cur + 1} of {n}", 15, "bold", C["muted"]) +
            rect(RX, y0 + 18, 516, 124, C["surface"], 12, C["primary"], 2.5) +
            rect(RX + 16, y0 + 34, 92, 92, C["primaryTint"], 10) + icon_svg +
            t(RX + 128, y0 + 72, prompt[0], 28, "bold") +
            (t(RX + 128, y0 + 108, prompt[1], 28, "bold") if len(prompt) > 1 else "") +
            dots(RX + 14, y0 + 192, n, done, cur) +
            t(RX + 14 + n * 30 + 2, y0 + 198, f"{cur + 1}/{n}", 15, "bold", C["muted"]) +
            countdown(RX + 470, y0 + 192, 34, secs, 8) +
            t(RX + 470, y0 + 248, "time left", 13, "normal", C["muted"], "middle") +
            rect(RX, y0 + 278, 516, 56, C["greenTint"] if feedback == "Action detected" else C["bg"], 8) +
            (icon_check(RX + 30, y0 + 306, 12, C["green"]) if feedback == "Action detected" else
             f'<path d="M{RX + 22},{y0 + 306} h16 m-6,-6 l6,6 l-6,6" fill="none" stroke="{C["primary"]}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>') +
            t(RX + 54, y0 + 313, feedback, 20, "bold", C["text"]))


right = challenge_right(["Please turn your head", "to the left"], icon_head_turn(RX + 62, 276, 70, "left"),
                        1, 1, 3, 6, "Please continue")
write("desktop-02-challenge-prompt.svg", DW, DH,
      desktop_shell(right, preview_svg=dpreview(state="challenge", face_kw=dict(yaw=-.6), mask_id="p2"),
                    progress_frac=.73, state_tag="S4 Challenge + S4f feedback"),
      "Desktop challenge prompt with progress",
      "State S4. Green solid oval. Challenge card: Please turn your head to the left, with head-and-arrow icon, progress dots 2 of 3, 6 second countdown ring, feedback line Please continue.")

# 3. Success (S5)
right = (icon_check(RX + 36, 236, 30, C["green"]) +
         t(RX + 84, 246, "Good, face captured", 30, "bold") +
         t(RX + 84, 284, "successfully", 30, "bold") +
         t(RX, 350, "Liveness check: on", 16, "normal", C["muted"]) +
         note(RX, 560, 516, ["Shown for 1 s, then the calling registration screen", "continues. Oval: green, thick, solid, with check mark."]))
write("desktop-03-result-success.svg", DW, DH,
      desktop_shell(right, preview_svg=dpreview(state="passed", face_kw=dict(smile=True), mask_id="p3"),
                    progress_frac=1.0, state_tag="S5 Passed"),
      "Desktop result: face captured successfully",
      "State S5. Green thick oval with check mark, progress 100 percent. Copy: Good, face captured successfully. Shown for one second before the registration screen continues.")

# 4. Liveness failed with guidance (S6)
guid = ["Look directly at the camera.", "Improve lighting.", "Keep your face within the capture area."]
right = (t(RX, 210, "We could not verify face", 28, "bold") +
         t(RX, 246, "liveness. Please try again.", 28, "bold") +
         "".join(dot(RX + 10, 300 + i * 40, 5, C["text"]) + t(RX + 28, 306 + i * 40, g, 19) for i, g in enumerate(guid)) +
         button(RX, 440, 320, "Try again (attempt 2 of 3)") +
         note(RX, 560, 516, ["Up to three guidance lines, chosen from the failing quality", "component. Oval: red, dotted. Retry consumes an attempt."]))
write("desktop-04-liveness-failed-guidance.svg", DW, DH,
      desktop_shell(right, attempt="Attempt 1 of 3", preview_svg=dpreview(state="failed", paused=True, mask_id="p4"),
                    progress_frac=.6, state_tag="S6 Liveness failed (actionable)"),
      "Desktop liveness failure with guidance and retry",
      "State S6. Preview paused, red dotted oval. Copy: We could not verify face liveness. Please try again. Guidance: Look directly at the camera. Improve lighting. Keep your face within the capture area. Button: Try again (attempt 2 of 3).")

# 5. PAD failure (S7) - generic
right = (icon_neutral_face(RX + 40, 232, 80) +
         t(RX + 100, 222, "Face verification could not", 28, "bold") +
         t(RX + 100, 258, "be completed. Please try again.", 28, "bold") +
         button(RX + 100, 310, 320, "Try again (attempt 2 of 3)") +
         note(RX, 540, 516, ["Same message for PAD_DETECTED and IDENTITY_DRIFT. Neutral", "icon, no warning triangle, no guidance list, no attack class.", "Detail only in audit and supervisor-gated Diagnostics."]))
write("desktop-05-pad-failure-retry.svg", DW, DH,
      desktop_shell(right, preview_svg=dpreview(state="neutral", paused=True, mask_id="p5"), progress_frac=.6,
                    state_tag="S7 PAD / generic failure"),
      "Desktop PAD failure with retry (generic message)",
      "State S7. Preview paused, neutral grey oval, neutral face icon, no warning triangle. Copy: Face verification could not be completed. Please try again. Button: Try again (attempt 2 of 3). No guidance list.")

# 6. Max retries / supervisor escalation (S8)
right = (icon_lock(RX + 36, 226, 60, C["navy"]) +
         t(RX + 88, 222, "Face capture could not be", 28, "bold") +
         t(RX + 88, 258, "completed.", 28, "bold") +
         t(RX + 88, 300, "A supervisor is required to continue.", 20, "normal", C["text"]) +
         button(RX + 88, 344, 230, "Request supervisor") + button(RX + 334, 344, 120, "Close", "secondary") +
         note(RX, 520, 516, ["Buttons follow onMaxRetries. Request supervisor: supervisor", "passes their own liveness check, then a new resident session", "starts as Assisted capture (accessibility pool). All audited."]))
lock_overlay = icon_lock(52 + 280, 156 + 230, 90, "#C9D6EA")
write("desktop-06-max-retries-supervisor.svg", DW, DH,
      desktop_shell(right, attempt="Attempt 3 of 3",
                    preview_svg=preview(52, 156, 560, 460, state="", off=True, show_face=False, mask_id="p6", overlay=lock_overlay),
                    progress_frac=None, state_tag="S8 Max retries (resident)", cancel=False),
      "Desktop maximum retries reached: supervisor escalation",
      "State S8 for a resident. Preview off with lock icon. Copy: Face capture could not be completed. A supervisor is required to continue. Buttons: Request supervisor, Close.")

# 7. Device error (S1e)
right = (icon_device_off(RX + 40, 236, 76, C["navy"]) +
         t(RX + 100, 222, "Face capture device not found.", 26, "bold") +
         t(RX + 100, 262, "Check that the device is connected and", 19) +
         t(RX + 100, 290, "switched on, then try again.", 19) +
         button(RX + 100, 330, 140, "Retry") +
         note(RX, 520, 516, ["Same pane for the other device errors in 08-error-handling.md,", "e.g. “The camera is not available. Close other applications", "that may be using it and try again.” Diagnostics if enabled."]))
dev_overlay = icon_device_off(52 + 280, 156 + 230, 110, "#C9D6EA")
write("desktop-07-device-error.svg", DW, DH,
      desktop_shell(right, preview_svg=preview(52, 156, 560, 460, state="", off=True, show_face=False, mask_id="p7", overlay=dev_overlay),
                    state_tag="S1e Device error"),
      "Desktop device error",
      "State S1e. Preview off with a struck-through camera icon. Copy: Face capture device not found. Check that the device is connected and switched on, then try again. Buttons: Retry; Diagnostics link if enabled.")

# 8. Operator login liveness (modal over login screen)
b = [rect(0, 0, DW, DH, C["bg"]), rect(0, 0, DW, 56, C["navy"]),
     dot(32, 28, 13, C["primary"]) + t(32, 33, "M", 14, "bold", "#fff", "middle"),
     t(56, 34, "MOSIP Registration Client", 18, "bold", "#fff"),
     rect(440, 170, 400, 420, C["surface"], 12, C["border"]),
     t(640, 225, "Login", 26, "bold", C["text"], "middle"),
     t(480, 275, "User ID", 15, "bold", C["muted"]), rect(480, 285, 320, 44, C["surface"], 6, C["border"]),
     t(494, 313, "officer01", 16),
     t(480, 365, "Authentication", 15, "bold", C["muted"]), rect(480, 375, 320, 44, C["surface"], 6, C["border"]),
     t(494, 403, "Face", 16), button(480, 450, 320, "Next"),
     rect(0, 0, DW, DH, "rgba(10,20,40,0.55)")]
mx, my, mw, mh = 120, 70, 1040, 660
b.append(rect(mx, my, mw, mh, C["surface"], 14))
b.append(rect(mx, my, mw, 60, C["navy"], 14) + rect(mx, my + 40, mw, 20, C["navy"]))
b.append(t(mx + 28, my + 39, "Operator authentication", 20, "bold", "#fff"))
b.append(t(mx + mw - 28, my + 39, "×", 26, "bold", "#C9D6EA", "end"))
# status strip in modal
b.append(rect(mx, my + 60, mw, 44, C["bg"]))
b.append(dot(mx + 34, my + 82, 6, C["green"]) + t(mx + 48, my + 88, "Online", 14, "bold", C["green"]))
b.append(t(mx + 120, my + 88, "Attempt 1 of 3", 14, "bold", C["text"]))
b.append(t(mx + 250, my + 88, "Liveness check: on", 14, "normal", C["muted"]))
b.append(preview(mx + 28, my + 124, 440, 400, state="challenge", face_kw=dict(eyes_closed=True), mask_id="p8"))
b.append(progress(mx + 28, my + 540, 440, .8))
ox = mx + 500
b.append(t(ox, my + 150, "Action 1 of 2", 15, "bold", C["muted"]))
b.append(rect(ox, my + 168, 500, 110, C["surface"], 12, C["primary"], 2.5))
b.append(rect(ox + 16, my + 184, 78, 78, C["primaryTint"], 10) + icon_eye(ox + 55, my + 223, 60))
b.append(t(ox + 116, my + 233, "Please blink", 28, "bold"))
b.append(dots(ox + 14, my + 320, 2, 0, 0) + t(ox + 76, my + 326, "1/2", 15, "bold", C["muted"]))
b.append(countdown(ox + 450, my + 320, 32, 3, 8))
b.append(rect(ox, my + 372, 500, 56, C["greenTint"], 8) + icon_check(ox + 30, my + 400, 12, C["green"]) +
         t(ox + 54, my + 407, "Action detected", 20, "bold"))
b.append(f'<line x1="{mx}" y1="{my + mh - 84}" x2="{mx + mw}" y2="{my + mh - 84}" stroke="{C["border"]}"/>')
b.append(button(mx + 28, my + mh - 64, 120, "Cancel", "secondary"))
b.append(button(mx + mw - 220, my + mh - 64, 192, "Use password", "secondary"))
b.append(t(DW / 2, DH - 22, "Mockup · Operator login: S4 Challenge (active mode ALWAYS), S4f Action detected, Use password visible from the start", 13, "normal", "#C9D6EA", "middle"))
write("desktop-08-operator-login-liveness.svg", DW, DH, "".join(b),
      "Desktop operator login with face liveness",
      "Operator authentication modal over the login screen. No packet breadcrumb. Challenge Please blink, dots 1 of 2, 3 second countdown, feedback Action detected. Use password is visible from the start.")

# ---- Android ---------------------------------------------------------------
AW, AH = 412, 892


def phone(body, *, online=True, attempt="2/3", title="Face capture", tag=""):
    b = [rect(0, 0, AW, AH, "#DDE3EC"),
         rect(6, 6, AW - 12, AH - 12, "#111827", 44),
         rect(16, 16, AW - 32, AH - 32, C["surface"], 36),
         f'<defs><clipPath id="screen"><rect x="16" y="16" width="{AW - 32}" height="{AH - 32}" rx="36"/></clipPath></defs>',
         '<g clip-path="url(#screen)">']
    # status bar
    b.append(rect(16, 16, AW - 32, 40, C["navy"]))
    b.append(t(46, 42, "10:24", 14, "bold", "#fff"))
    b.append(dot(AW / 2, 30, 7, "#111827"))
    b.append(rect(AW - 82, 31, 30, 13, "none", 3, "#fff", 1.5) + rect(AW - 80, 33, 20, 9, "#fff", 2))
    # app bar
    b.append(rect(16, 56, AW - 32, 64, C["navy"]))
    b.append(f'<path d="M44,78 l-10,10 l10,10" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>')
    b.append(t(64, 95, title, 20, "bold", "#fff"))
    lab = "Online" if online else "Offline"
    b.append(t(AW - 36, 86, lab, 14, "bold", "#9BE3B5" if online else "#D5DCE6", "end"))
    lx = AW - 36 - len(lab) * 8.4 - 10
    b.append(dot(lx, 81, 5, "#4ADE80") if online else dot(lx, 81, 5, C["navy"], "#D5DCE6"))
    b.append(t(AW - 36, 108, attempt, 13, "normal", "#C9D6EA", "end"))
    b.append(body)
    # nav handle
    b.append(rect(AW / 2 - 60, AH - 34, 120, 5, "#9AA6B6", 3))
    b.append('</g>')
    return "".join(b)


def a_preview(state, mask, face_kw=None, h=430):
    return preview(16, 120, AW - 32, h, state=state, face_kw=face_kw, mask_id=mask)


def tag_line(s):
    return t(AW / 2, AH - 48, f"Mockup · {s}", 11, "normal", C["faint"], "middle")


# A1 resident capture S3
body = (a_preview("checking", "a1") + progress(36, 572, AW - 72, .6) +
        t(36, 646, "Checking face liveness…", 24, "bold") +
        t(36, 678, "Keep looking at the camera", 20, "normal", C["muted"]) +
        t(36, 718, "Liveness check: on", 15, "normal", C["muted"]) +
        button(AW / 2 - 70, 760, 140, "Cancel", "secondary", 48) + tag_line("S3 Checking"))
write("android-01-resident-capture.svg", AW, AH, phone(body, attempt="Attempt 1 of 3"),
      "Android resident capture: passive check",
      "Phone portrait. Full-bleed preview with amber dashed oval and sweep, progress 60 percent, Checking face liveness…, Keep looking at the camera, Cancel button.")

# A2 challenge S4 (bottom sheet)
sheet_y = 484
body = (a_preview("challenge", "a2", dict(yaw=-.6), h=330) +
        progress(36, 462, AW - 72, .73, label=False) +
        rect(16, sheet_y, AW - 32, AH - sheet_y, C["surface"], 24, C["border"]) +
        rect(AW / 2 - 24, sheet_y + 10, 48, 5, C["border"], 3) +
        t(36, sheet_y + 40, "Action 2 of 3", 14, "bold", C["muted"]) +
        rect(32, sheet_y + 52, AW - 64, 110, C["surface"], 12, C["primary"], 2.5) +
        rect(46, sheet_y + 64, 80, 86, C["primaryTint"], 10) + icon_head_turn(86, sheet_y + 107, 64, "left") +
        t(142, sheet_y + 98, "Please turn your", 26, "bold") + t(142, sheet_y + 132, "head to the left", 26, "bold") +
        dots(50, sheet_y + 196, 3, 1, 1) + t(146, sheet_y + 202, "2/3", 15, "bold", C["muted"]) +
        countdown(AW - 78, sheet_y + 196, 28, 6, 8, 16) +
        rect(32, sheet_y + 240, AW - 64, 48, C["bg"], 8) +
        f'<path d="M50,{sheet_y + 264} h16 m-6,-6 l6,6 l-6,6" fill="none" stroke="{C["primary"]}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>' +
        t(80, sheet_y + 271, "Please continue", 20, "bold") +
        button(AW / 2 - 70, sheet_y + 304, 140, "Cancel", "secondary", 44))
write("android-02-challenge.svg", AW, AH, phone(body, attempt="Attempt 1 of 3"),
      "Android challenge prompt in a bottom sheet",
      "Phone portrait. Preview with green oval, bottom sheet with Please turn your head to the left, progress dots 2 of 3, 6 second countdown ring and Please continue feedback.")

# A3 offline - passed, liveness enforced exactly as online
body = (a_preview("passed", "a3", dict(smile=True)) + progress(36, 572, AW - 72, 1.0) +
        icon_check(52, 636, 18, C["green"]) +
        t(82, 632, "Good, face captured", 22, "bold") + t(82, 660, "successfully", 22, "bold") +
        t(36, 700, "Liveness check: on", 15, "normal", C["muted"]) +
        note(32, 722, AW - 64, ["Offline changes only the app-bar indicator.", "Same passive check and challenges; audit", "record queued until the next sync."], 12) +
        tag_line("Offline · S5 Passed (same checks as online)"))
write("android-03-offline.svg", AW, AH, phone(body, online=False, attempt="Attempt 1 of 3"),
      "Android offline: liveness still enforced",
      "Phone portrait with an Offline indicator in the app bar. The passive check and challenges run on the device exactly as online; the result Good, face captured successfully is shown and the audit record is queued for sync.")


# ---- authentication variants (operator / supervisor) ----------------------

def auth_modal(title, backdrop, preview_svg, right, footer_buttons, tag, attempt="Attempt 1 of 2", online=True):
    mx, my, mw, mh = 120, 70, 1040, 660
    b = [backdrop, rect(0, 0, DW, DH, "rgba(10,20,40,0.55)"),
         rect(mx, my, mw, mh, C["surface"], 14),
         rect(mx, my, mw, 60, C["navy"], 14) + rect(mx, my + 40, mw, 20, C["navy"]),
         t(mx + 28, my + 39, title, 20, "bold", "#fff"),
         t(mx + mw - 28, my + 39, "×", 26, "bold", "#C9D6EA", "end"),
         rect(mx, my + 60, mw, 44, C["bg"])]
    if online:
        b.append(dot(mx + 34, my + 82, 6, C["green"]) + t(mx + 48, my + 88, "Online", 14, "bold", C["green"]))
    else:
        b.append(dot(mx + 34, my + 82, 6, C["surface"], C["muted"]) + t(mx + 48, my + 88, "Offline", 14, "bold", C["muted"]))
    b.append(t(mx + 120, my + 88, attempt, 14, "bold", C["text"]))
    b.append(t(mx + 250, my + 88, "Liveness check: on", 14, "normal", C["muted"]))
    b.append(preview_svg)
    b.append(right(mx + 500, my))
    b.append(f'<line x1="{mx}" y1="{my + mh - 84}" x2="{mx + mw}" y2="{my + mh - 84}" stroke="{C["border"]}"/>')
    b.append(footer_buttons(mx, my, mw, mh))
    b.append(t(DW / 2, DH - 22, f"Mockup · {tag}", 13, "normal", "#C9D6EA", "middle"))
    return "".join(b)


def approval_backdrop():
    b = [rect(0, 0, DW, DH, C["bg"]), rect(0, 0, DW, 56, C["navy"]),
         dot(32, 28, 13, C["primary"]) + t(32, 33, "M", 14, "bold", "#fff", "middle"),
         t(56, 34, "MOSIP Registration Client", 18, "bold", "#fff"),
         t(DW - 24, 34, "Supervisor: super02  ·  Centre 10002", 14, "normal", "#C9D6EA", "end"),
         t(24, 92, "Pending approval", 20, "bold", C["text"])]
    for i in range(6):
        y = 120 + i * 56
        b.append(rect(24, y, DW - 48, 48, C["surface"], 6, C["border"]))
        b.append(t(44, y + 30, f"10002100070001520261024{i:02d}", 15, "normal", C["text"]))
        b.append(t(DW - 44, y + 30, "Approve", 15, "bold", C["green"], "end"))
    return "".join(b)


# 9. Supervisor authentication during packet approval (S4 + S4f)
def sup_right(ox, my):
    return (t(ox, my + 150, "Action 2 of 3", 15, "bold", C["muted"]) +
            rect(ox, my + 168, 500, 110, C["surface"], 12, C["primary"], 2.5) +
            rect(ox + 16, my + 184, 78, 78, C["primaryTint"], 10) + icon_head_turn(ox + 55, my + 223, 60, "right") +
            t(ox + 116, my + 215, "Please turn your head", 28, "bold") + t(ox + 116, my + 251, "to the right", 28, "bold") +
            dots(ox + 14, my + 320, 3, 1, 1) + t(ox + 106, my + 326, "2/3", 15, "bold", C["muted"]) +
            countdown(ox + 450, my + 320, 32, 2, 3) +
            rect(ox, my + 372, 500, 56, C["bg"], 8) +
            f'<path d="M{ox + 22},{my + 400} h16 m-6,-6 l6,6 l-6,6" fill="none" stroke="{C["primary"]}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>' +
            t(ox + 54, my + 407, "Please continue", 20, "bold") +
            note(ox, my + 452, 500, ["Supervisor policy: active mode ALWAYS, exactly 3 challenges,", "3 s to start each action, identity matched to the supervisor."]))


def sup_footer(mx, my, mw, mh):
    return button(mx + 28, my + mh - 64, 120, "Cancel", "secondary") + button(mx + mw - 220, my + mh - 64, 192, "Use password", "secondary")


write("desktop-09-supervisor-auth-liveness.svg", DW, DH,
      auth_modal("Supervisor authentication", approval_backdrop(),
                 preview(120 + 28, 70 + 124, 440, 400, state="challenge", face_kw=dict(yaw=.6), mask_id="p9") + progress(148, 610, 440, .55),
                 sup_right, sup_footer, "Supervisor packet approval: S4 Challenge 2 of 3, S4f Please continue", attempt="Attempt 1 of 2"),
      "Desktop supervisor authentication with face liveness",
      "Supervisor authentication modal over the packet approval list. Challenge Please turn your head to the right, dots 2 of 3, countdown, feedback Please continue, Use password visible.")


# 10. Operator/supervisor authentication locked (S8, auth variant)
def lock_right(ox, my):
    return (icon_lock(ox + 28, my + 180, 44, C["amber"]) +
            t(ox + 64, my + 192, "Face authentication is locked.", 26, "bold") +
            t(ox, my + 250, "Use another authentication method or", 20, "normal", C["text"]) +
            t(ox, my + 280, "contact your supervisor.", 20, "normal", C["text"]) +
            note(ox, my + 330, 500, ["No reason or attack detail is shown. LIVENESS_LOCKED is", "written to the audit trail with the evidence hash; the", "lock follows policy onMaxRetries = LOCK_AND_ALERT."]))


def lock_footer(mx, my, mw, mh):
    return button(mx + 28, my + mh - 64, 120, "Close", "secondary") + button(mx + mw - 220, my + mh - 64, 192, "Use password", "primary")


write("desktop-10-auth-locked.svg", DW, DH,
      auth_modal("Supervisor authentication", approval_backdrop(),
                 preview(120 + 28, 70 + 124, 440, 400, state="", off=True, show_face=False, mask_id="p10",
                         overlay=icon_lock(120 + 28 + 220, 70 + 124 + 200, 90, "#C9D6EA")),
                 lock_right, lock_footer, "S8 Max retries, authentication variant (operator and supervisor)", attempt="Attempt 2 of 2"),
      "Desktop authentication locked after maximum attempts",
      "Operator or supervisor authentication after the last failed attempt: preview off with lock, Face authentication is locked. Use another authentication method or contact your supervisor. Buttons Close and Use password.")


# A4 operator login challenge
sheet_y = 484
body = (a_preview("challenge", "a4", dict(smile=True), h=330) +
        progress(36, 462, AW - 72, .4, label=False) +
        rect(16, sheet_y, AW - 32, AH - sheet_y, C["surface"], 24, C["border"]) +
        rect(AW / 2 - 24, sheet_y + 10, 48, 5, C["border"], 3) +
        t(36, sheet_y + 40, "Action 1 of 2", 14, "bold", C["muted"]) +
        rect(32, sheet_y + 52, AW - 64, 110, C["surface"], 12, C["primary"], 2.5) +
        rect(46, sheet_y + 64, 80, 86, C["primaryTint"], 10) + icon_neutral_face(86, sheet_y + 107, 56) +
        t(142, sheet_y + 115, "Please smile", 26, "bold") +
        dots(50, sheet_y + 196, 2, 0, 0) + t(116, sheet_y + 202, "1/2", 15, "bold", C["muted"]) +
        countdown(AW - 78, sheet_y + 196, 28, 2, 3, 16) +
        rect(32, sheet_y + 240, AW - 64, 48, C["greenTint"], 8) + icon_check(56, sheet_y + 264, 11, C["green"]) +
        t(80, sheet_y + 271, "Action detected", 20, "bold") +
        button(AW / 2 - 90, sheet_y + 296, 180, "Use password", "secondary", 40) +
        tag_line("Operator login · S4 + S4f, after password"))
write("android-04-operator-login.svg", AW, AH, phone(body, attempt="Attempt 1 of 3", title="Operator login"),
      "Android operator login with face liveness",
      "Phone portrait. After the password step, the operator sees a bottom sheet Please smile, dots 1 of 2, countdown, feedback Action detected, Use password.")

# A5 supervisor approval locked
body = (a_preview("", "a5", h=330) +
        rect(16, 120, AW - 32, 330, "#1F2937") + icon_lock(AW / 2, 285, 80, "#C9D6EA") +
        icon_lock(52, 512, 30, C["amber"]) +
        t(78, 520, "Face authentication", 22, "bold") + t(36, 550, "is locked.", 22, "bold") +
        t(36, 592, "Use another authentication", 18, "normal") +
        t(36, 618, "method or contact your", 18, "normal") + t(36, 644, "supervisor.", 18, "normal") +
        button(36, 690, AW - 72, "Use password", "primary", 48) +
        button(36, 750, AW - 72, "Close", "secondary", 48) +
        tag_line("Supervisor approval · S8 locked (auth variant)"))
write("android-05-supervisor-locked.svg", AW, AH, phone(body, attempt="Attempt 2 of 2", title="Supervisor auth"),
      "Android supervisor authentication locked",
      "Phone portrait. Supervisor authentication for packet approval after the last failed attempt: preview off with lock, Face authentication is locked, Use password and Close.")

# ---- user flow -------------------------------------------------------------
FW, FH = 1280, 900


def node(x, y, w, h, title, sub="", kind="step", sid=""):
    fill, stroke, fg = {
        "step": (C["surface"], C["primary"], C["text"]),
        "start": (C["navy"], C["navy"], "#fff"),
        "pass": (C["greenTint"], C["green"], C["text"]),
        "fail": (C["redTint"], C["red"], C["text"]),
        "neutral": (C["neutralTint"], C["neutral"], C["text"]),
        "lock": (C["amberTint"], C["amber"], C["text"]),
    }[kind]
    o = rect(x, y, w, h, fill, 10, stroke, 2)
    if sid:
        o += rect(x + 10, y - 12, len(sid) * 8 + 14, 22, stroke, 11) + t(x + 17 + len(sid) * 4, y + 4, sid, 12, "bold", "#fff", "middle")
    lines = sub.split("\n") if sub else []
    ty = y + h / 2 + 6 - (len(lines) * 9)
    o += t(x + w / 2, ty, title, 16, "bold", fg, "middle")
    for i, ln in enumerate(lines):
        o += t(x + w / 2, ty + 22 + i * 18, ln, 13, "normal", fg if kind == "start" else C["muted"], "middle")
    return o


def diamond(cx, cy, w, h, label):
    pts = f"{cx},{cy - h / 2} {cx + w / 2},{cy} {cx},{cy + h / 2} {cx - w / 2},{cy}"
    lines = label.split("\n")
    out = f'<polygon points="{pts}" fill="{C["primaryTint"]}" stroke="{C["primary"]}" stroke-width="2"/>'
    for i, ln in enumerate(lines):
        out += t(cx, cy + 5 - (len(lines) - 1) * 9 + i * 18, ln, 14, "bold", C["navy"], "middle")
    return out


def arrow(points, label="", lx=None, ly=None, color=None, dashed=False):
    color = color or C["muted"]
    d = "M" + " L".join(f"{x},{y}" for x, y in points)
    da = ' stroke-dasharray="6 5"' if dashed else ""
    o = f'<path d="{d}" fill="none" stroke="{color}" stroke-width="2"{da} marker-end="url(#arr)"/>'
    if label:
        if lx is None:
            (x0, y0), (x1, y1) = points[0], points[1]
            lx, ly = (x0 + x1) / 2, (y0 + y1) / 2 - 8
        o += rect(lx - len(label) * 3.6 - 6, ly - 14, len(label) * 7.2 + 12, 20, C["bg"], 4) + t(lx, ly + 1, label, 12, "bold", C["muted"], "middle")
    return o


f = [rect(0, 0, FW, FH, C["bg"]),
     f'<defs><marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">'
     f'<path d="M0,0 L10,5 L0,10 z" fill="{C["muted"]}"/></marker></defs>',
     t(40, 50, "Face liveness user flow: passive → active → decision → retry / escalate", 22, "bold", C["navy"]),
     t(40, 76, "Same SessionState on Desktop (JavaFX) and Android (Flutter). State ids as in docs/design/12-ux-spec.md §2.", 14, "normal", C["muted"])]
# row 1
f.append(node(40, 120, 170, 70, "Start capture", "resident / operator /\nsupervisor", "start", "S0"))
f.append(node(250, 120, 190, 70, "Connecting", "“Connecting to the face\ncapture device…”", "step", "S1"))
f.append(node(480, 120, 200, 70, "Positioning", "hints: Move closer,\nCentre your face …", "step", "S2"))
f.append(node(720, 120, 220, 70, "Passive check", "“Checking face liveness…”\nresident does nothing", "step", "S3"))
f.append(arrow([(210, 155), (248, 155)]))
f.append(arrow([(440, 155), (478, 155)]))
f.append(arrow([(680, 155), (718, 155)]))
# device error branch
f.append(node(250, 250, 190, 70, "Device error", "Retry · Diagnostics", "fail", "S1e"))
f.append(arrow([(345, 190), (345, 248)], "device fault", 345, 226))
f.append(arrow([(250, 285), (125, 285), (125, 192)], "Retry", 175, 278))
# veto from passive
f.append(diamond(830, 300, 260, 110, "score ≥ threshold and\nmode ON_LOW_CONF.?"))
f.append(arrow([(830, 190), (830, 243)]))
f.append(node(1010, 120, 220, 70, "PAD veto / drift", "hard veto at any frame", "neutral"))
f.append(arrow([(940, 155), (1008, 155)], "veto", 974, 147))
# passed
f.append(node(1010, 265, 220, 70, "Passed", "“Good, face captured\nsuccessfully”", "pass", "S5"))
f.append(arrow([(960, 300), (1008, 300)], "yes", 984, 290))
# active
f.append(node(700, 420, 260, 80, "Active challenges", "1–3 random prompts, e.g.\n“Please blink” · ●●○ · 6 s ring", "step", "S4"))
f.append(arrow([(830, 355), (830, 418)], "no (low score, or ALWAYS)", 830, 392))
f.append(diamond(830, 590, 240, 100, "all actions done\nand score ok?"))
f.append(arrow([(830, 500), (830, 538)]))
f.append(arrow([(950, 590), (1120, 590), (1120, 337)], "yes", 1040, 582))
# failures
f.append(node(420, 545, 220, 90, "Liveness failed", "“We could not verify face\nliveness. Please try again.”\n+ up to 3 guidance lines", "fail", "S6"))
f.append(arrow([(710, 590), (642, 590)], "no", 676, 582))
f.append(node(1010, 690, 220, 80, "PAD / generic failure", "“Face verification could\nnot be completed.”", "neutral", "S7"))
f.append(arrow([(1150, 190), (1150, 230), (1250, 230), (1250, 730), (1232, 730)], "", None, None))
f.append(arrow([(830, 640), (830, 730), (1008, 730)], "PAD in active", 920, 722))
# retry decision
f.append(diamond(530, 740, 220, 90, "attempt < max?"))
f.append(arrow([(530, 637), (530, 693)]))
f.append(arrow([(1010, 750), (980, 750), (980, 800), (680, 800), (680, 740), (642, 740)], "retry rules apply", 830, 792))
f.append(arrow([(420, 740), (300, 740), (300, 420), (580, 420), (580, 192)], "yes: Try again (attempt n of max)", 440, 412, C["primary"]))
# lock / escalation
f.append(node(40, 690, 230, 90, "Max retries / locked", "resident: “A supervisor is\nrequired to continue.”", "lock", "S8"))
f.append(arrow([(530, 785), (530, 830), (155, 830), (155, 782)], "no", 340, 822))
f.append(node(40, 530, 230, 120, "onMaxRetries", "Request supervisor →\nassisted capture (S10)\nUse password · Biometric\nexception · Close", "step"))
f.append(arrow([(155, 690), (155, 652)]))
f.append(arrow([(40, 590), (20, 590), (20, 155), (38, 155)], "", None, None, C["primary"], True))
f.append(t(44, 400, "supervisor-attested new session", 12, "bold", C["primary"], "middle", 'transform="rotate(-90 44 400)"'))
# legend
f.append(t(40, 878, "Legend:", 14, "bold", C["navy"]))
for i, (k, lab) in enumerate([("pass", "success"), ("fail", "actionable failure (guidance)"), ("neutral", "security failure (generic message)"), ("lock", "locked / escalation"), ("step", "screen state")]):
    fill, stroke = {"pass": (C["greenTint"], C["green"]), "fail": (C["redTint"], C["red"]),
                    "neutral": (C["neutralTint"], C["neutral"]), "lock": (C["amberTint"], C["amber"]),
                    "step": (C["surface"], C["primary"])}[k]
    lx = [110, 230, 450, 710, 900][i]
    f.append(rect(lx, 865, 22, 16, fill, 4, stroke, 2) + t(lx + 30, 878, lab, 13, "normal", C["text"]))
write("flow-liveness-user-flow.svg", FW, FH, "".join(f),
      "Face liveness user flow",
      "Start, device connecting (device error branch with Retry), positioning, passive check, decision on score and active mode, active challenges, success, liveness failure with guidance, generic PAD failure, retry while attempts remain, then max retries with supervisor escalation, password, biometric exception or close.")
print("ok")
