# Models for `liveness-av`

`liveness-av` recognises the spoken digits with [Vosk](https://alphacephei.com/vosk/) (Kaldi, offline). The models
are **not in the repository**. Download them to `~/.cache/vivid-av/models`, or point the system property
`vivid.av.models` or the environment variable `VIVID_AV_MODELS` at another directory (`VoskModels`). Tests that need a
model are skipped when it is missing.

The Java binding `com.alphacephei:vosk:0.3.45` (Apache-2.0, from Maven Central) bundles `libvosk` for Linux x86-64,
Windows x86-64 and macOS, and is about 25 MB. It loads the library through JNA 5.7.0 (Apache-2.0 / LGPL-2.1 dual).

| Language tag | Directory | Zip size | Unpacked | MD5 (zip, as published) | SHA-256 (zip, as downloaded 2026-10-24) | Licence |
|---|---|---|---|---|---|---|
| `en` (US English) | `vosk-model-small-en-us-0.15` | 41,205,931 B | 68 MB | `09ab50ccd62b674cbaa231b825f9c1cb` | `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498` | Apache-2.0 |
| `en-IN` (Indian English) | `vosk-model-small-en-in-0.4` | 37,573,330 B | 55 MB | `62fd085f33c8c5fc01a235030e2a1fe3` | `20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7` | Apache-2.0 |
| `hi` (Hindi) | `vosk-model-small-hi-0.22` | 44,458,845 B | 79 MB | `80f1265262c9a8a515f2707498e1b485` | `7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48` | Apache-2.0 |
| `te` (Telugu) | `vosk-model-small-te-0.42` | 60,544,249 B | 123 MB | `ae3c093fd7dd883d462983b15900ca61` | `3e77b05becc7599a596ccebd8b22cb95eb4eca0cfca524827cdfbfd66af09497` | Apache-2.0 |
| `gu` (Gujarati) | `vosk-model-small-gu-0.42` | 108,054,987 B | 332 MB | `4595a6f0cc0c88fee6eec7d80496fc10` | `e87b7055e52b38a493a8308cf815b6fc05b9a89ef8aee18b4cbc2867cfc5f42a` | Apache-2.0 |

The licences are those stated for each model on the Vosk model list (https://alphacephei.com/vosk/models). The MD5
values match that page's `model-list.json`. The published word error rates for these models on general speech are
roughly 10 % for US English, 21-25 % for Hindi, 20 % for Gujarati and 76-88 % for Telugu. This module decodes with a
closed grammar of the ten digit words, which is much easier than open vocabulary. The digit-level results we measured
are in [`docs/testing/av-liveness.md`](../../docs/testing/av-liveness.md).

## Download

```sh
mkdir -p ~/.cache/vivid-av/models && cd ~/.cache/vivid-av
for m in vosk-model-small-en-us-0.15 vosk-model-small-en-in-0.4 vosk-model-small-hi-0.22 \
         vosk-model-small-te-0.42 vosk-model-small-gu-0.42; do
  curl -fsSL -o $m.zip https://alphacephei.com/vosk/models/$m.zip
done
sha256sum -c <<'SUMS'
30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498  vosk-model-small-en-us-0.15.zip
20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7  vosk-model-small-en-in-0.4.zip
7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48  vosk-model-small-hi-0.22.zip
3e77b05becc7599a596ccebd8b22cb95eb4eca0cfca524827cdfbfd66af09497  vosk-model-small-te-0.42.zip
e87b7055e52b38a493a8308cf815b6fc05b9a89ef8aee18b4cbc2867cfc5f42a  vosk-model-small-gu-0.42.zip
SUMS
for z in *.zip; do unzip -q -o $z -d models; done && rm *.zip
```

All 10 digit words of every lexicon in `DigitLexicon` are in each model's vocabulary. Vosk logs
`Ignoring word missing in vocabulary` for a missing grammar word, and no such warning appears for these models.

## Not obtained

* **Tamil, Kannada, Malayalam, Marathi, Bengali**: Vosk publishes no small model for these languages. The big Hindi
  and Gujarati models (1.5 GB and 700 MB) and the 900 MB Indian English model were not downloaded, because the machine
  had about 6 GB of free disk.

## Test-only speech synthesis (not shipped, not redistributed)

The test corpus is synthetic speech made by [`../tools/synth_corpus.py`](../tools/synth_corpus.py) into
`~/.cache/vivid-av/corpus`. It is never written to the repository. The script uses:

* **eSpeak 1.48** (GPL-3.0), the system `espeak` binary, for the voices `en-us`, `hi`, `te` ("telugu-test") and
  `gu` ("gujarati-test").
* **Piper** `piper-tts` 1.8.0 (GPL-3.0-or-later, github.com/OHF-voice/piper1-gpl; a test tool only, never linked
  into or shipped with any module), installed with pip into
  `~/.cache/vivid-av/venv`. Voices come from `huggingface.co/rhasspy/piper-voices`, stored in `~/.cache/vivid-av/tts`.
  Each voice is used only to generate local test audio. Several have non-commercial dataset licences, so their audio
  must not be redistributed:

| Voice | SHA-256 of `.onnx` | Dataset licence (MODEL_CARD) |
|---|---|---|
| `en_US-lessac-medium` | `5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f` | Blizzard 2013 Lessac licence |
| `en_US-l2arctic-medium` (speakers ASI, RRBI, SVBI, TNI: Hindi-L1 English) | `d89f6f124bf1e7735b2179d2141b8001c3e19169d5e743ed6e35624f4c76f044` | CC BY-NC 4.0 |
| `hi_IN-pratham-medium` | `169964b0871667f6793416d4b35e97357a68ba1ad01df8580c28048989ee7693` | CC BY-NC-SA 4.0 |
| `hi_IN-priyamvada-medium` | `aa63bcf2cd493b55a450f280e23cf77f03afc9af7015e6e5acd43b652f166c88` | CC BY-NC-SA 4.0 |
| `te_IN-maya-medium` | `c3518ad4e3ca8ea6059c1e002f3772068f634960f58b237a96ff629db1c6200e` | IIT Madras IndicTTS licence |
| `te_IN-venkatesh-medium` | `dfaa5b7833cd48d946f3fe18c9c934aaa4e8590aac6922fddf34783a694c3c87` | CC BY 4.0 |

There is no Piper voice for Gujarati, so Gujarati was exercised with eSpeak only.

```sh
python3 -m venv ~/.cache/vivid-av/venv && ~/.cache/vivid-av/venv/bin/pip install piper-tts==1.8.0
# fetch the .onnx and .onnx.json of each voice above into ~/.cache/vivid-av/tts, then:
~/.cache/vivid-av/venv/bin/python -I liveness-av/tools/synth_corpus.py --per-voice 12
```
