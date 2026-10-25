#!/usr/bin/env python3
"""Synthesise the spoken-code test corpus for liveness-av (synthetic TTS speech only, no real people).

Writes 16 kHz mono 16-bit WAV files and manifest.tsv to ~/.cache/vivid-av/corpus (override with --out).
Nothing is written into the repository. Codes come from the `secrets` module (CSPRNG).

Voices:
  * Piper neural TTS (pip install piper-tts, voices from huggingface.co/rhasspy/piper-voices) when --piper-dir has them:
    en_US-lessac-medium, en_US-l2arctic-medium (speakers ASI, RRBI, SVBI, TNI: Hindi-L1 English),
    hi_IN-pratham-medium, hi_IN-priyamvada-medium, te_IN-maya-medium, te_IN-venkatesh-medium.
  * eSpeak (system `espeak` or `espeak-ng`): en-us, hi, te, gu.  Gujarati has no Piper voice, so it is eSpeak only.

Usage: python3 synth_corpus.py [--out DIR] [--per-voice N] [--piper-dir DIR] [--seed-free]
Run Piper voices with the Python that has piper-tts installed (for example ~/.cache/vivid-av/venv/bin/python).
"""
import argparse
import os
import random
import secrets
import shutil
import subprocess
import sys
import tempfile
import wave

WORDS = {
    "en": ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"],
    "hi": ["शून्य", "एक", "दो", "तीन", "चार", "पांच", "छह", "सात", "आठ", "नौ"],
    "te": ["సున్నా", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది"],
    "gu": ["શૂન્ય", "એક", "બે", "ત્રણ", "ચાર", "પાંચ", "છ", "સાત", "આઠ", "નવ"],
}

PIPER = [  # (voice id, lang, onnx file, speaker id)
    ("piper-en-lessac", "en", "en_US-lessac-medium.onnx", None),
    ("piper-en-l2arctic-ASI", "en", "en_US-l2arctic-medium.onnx", 10),
    ("piper-en-l2arctic-RRBI", "en", "en_US-l2arctic-medium.onnx", 19),
    ("piper-en-l2arctic-SVBI", "en", "en_US-l2arctic-medium.onnx", 2),
    ("piper-en-l2arctic-TNI", "en", "en_US-l2arctic-medium.onnx", 9),
    ("piper-hi-pratham", "hi", "hi_IN-pratham-medium.onnx", None),
    ("piper-hi-priyamvada", "hi", "hi_IN-priyamvada-medium.onnx", None),
    ("piper-te-maya", "te", "te_IN-maya-medium.onnx", None),
    ("piper-te-venkatesh", "te", "te_IN-venkatesh-medium.onnx", None),
]
ESPEAK = [("espeak-en-us", "en", "en-us"), ("espeak-hi", "hi", "hi"), ("espeak-te", "te", "te"), ("espeak-gu", "gu", "gu")]


def code():
    return "".join(str(secrets.randbelow(10)) for _ in range(4))


def text(lang, digits):
    return ", ".join(WORDS[lang][int(d)] for d in digits)


def to16k(src, dst):
    with wave.open(src, "rb") as w:
        rate, ch, width, n = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        raw = w.readframes(n)
    if width != 2:
        raise SystemExit("expected 16-bit audio from " + src)
    import array
    a = array.array("h"); a.frombytes(raw)
    if ch > 1:
        a = array.array("h", [sum(a[i:i + ch]) // ch for i in range(0, len(a), ch)])
    out = array.array("h")
    m = int(len(a) * 16000 / rate)
    for i in range(m):
        s = i * rate / 16000; j = int(s); f = s - j
        x = a[min(j, len(a) - 1)]; y = a[min(j + 1, len(a) - 1)]
        out.append(int(round(x + (y - x) * f)))
    with wave.open(dst, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(out.tobytes())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.expanduser("~/.cache/vivid-av/corpus"))
    ap.add_argument("--per-voice", type=int, default=12)
    ap.add_argument("--piper-dir", default=os.path.expanduser("~/.cache/vivid-av/tts"))
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    rnd = random.Random(secrets.randbits(64))
    rows = []
    tmp = tempfile.mkdtemp(prefix="vivid-av-tts-")
    try:
        try:
            from piper import PiperVoice, SynthesisConfig
        except ImportError:
            PiperVoice = None
            print("piper-tts not importable: Piper voices skipped", file=sys.stderr)
        loaded = {}
        for vid, lang, onnx, spk in PIPER:
            path = os.path.join(args.piper_dir, onnx)
            if PiperVoice is None or not os.path.exists(path):
                print("skip " + vid, file=sys.stderr); continue
            voice = loaded.get(onnx) or PiperVoice.load(path)
            loaded[onnx] = voice
            os.makedirs(os.path.join(args.out, vid), exist_ok=True)
            for i in range(args.per_voice):
                c = code(); raw = os.path.join(tmp, "p.wav"); rel = f"{vid}/{i:03d}.wav"
                cfg = SynthesisConfig(speaker_id=spk, length_scale=rnd.uniform(0.95, 1.25))
                with wave.open(raw, "wb") as w:
                    voice.synthesize_wav(text(lang, c), w, syn_config=cfg)
                to16k(raw, os.path.join(args.out, rel))
                rows.append((rel, lang, vid, c))
        espeak = shutil.which("espeak-ng") or shutil.which("espeak")
        for vid, lang, v in ESPEAK:
            if not espeak:
                print("espeak not found: eSpeak voices skipped", file=sys.stderr); break
            os.makedirs(os.path.join(args.out, vid), exist_ok=True)
            for i in range(args.per_voice):
                c = code(); raw = os.path.join(tmp, "e.wav"); rel = f"{vid}/{i:03d}.wav"
                subprocess.run([espeak, "-v", v, "-s", str(rnd.randint(115, 150)), "-w", raw, text(lang, c)], check=True)
                to16k(raw, os.path.join(args.out, rel))
                rows.append((rel, lang, vid, c))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    with open(os.path.join(args.out, "manifest.tsv"), "w", encoding="utf-8") as f:
        f.write("file\tlang\tvoice\tcode\n")
        for r in rows:
            f.write("\t".join(r) + "\n")
    print(f"{len(rows)} clips in {args.out}")


if __name__ == "__main__":
    main()
