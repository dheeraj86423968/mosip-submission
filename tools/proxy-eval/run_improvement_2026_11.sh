#!/bin/sh
# SPDX-License-Identifier: MPL-2.0
# Reproduces docs/testing/proxy-eval/improvement-2026-11.md end to end.
#   tools/proxy-eval/run_improvement_2026_11.sh <work dir outside the repository, ~3 GB free> [step...]
# Steps (default: all): extract simulate dump replay analyse clean
# Needs: Python 3 with numpy, scipy and OpenCV (FFmpeg + FaceDetectorYN); curl; a JDK 17+; the repository built
# (mvn -o install -DskipTests); liveness-engine-onnx/models with the three default models; and, for the Silent-Face
# views, <work>/onnx/{2.7_80x80_MiniFASNetV2,4_0_0_80x80_MiniFASNetV1SE}.onnx from tools/proxy-eval/convert_silentface.py.
# Media stays in <work>; "clean" deletes frames and attacks and keeps only scores, dumps and the extraction log.
set -eu
M="$(cd "$(dirname "$0")/../.." && pwd)"
W="$1"; shift
STEPS="${*:-extract simulate dump replay analyse}"
mkdir -p "$W/out"
cd "$W"
Y="$M/liveness-engine-onnx/models/face_detection_yunet_2023mar.onnx"
O="$W/onnx"
SPLIT="$M/docs/testing/proxy-eval/split-2026-11.csv"
CFG="$M/tools/proxy-eval/configs-2026-11"
[ -f testkit.cp ] || (cd "$M" && mvn -o -q -pl testkit dependency:build-classpath -Dmdep.outputFile="$W/testkit.cp")
CP="$M/testkit/target/classes:$(cat "$W/testkit.cp")"
JAVA="java -Xmx1500m -cp $CP"
A=antispoof_minifasnetv2se_128.onnx
for step in $STEPS; do case "$step" in
extract)   # 38 bona fide windows (P18 no longer decodes at 30 fps; see the write-up)
  sh "$M/tools/proxy-eval/fetch_and_extract.sh" "$M" "$W" ;;
simulate)  # seeded, same simulator and seed (2026) as the 24 Oct run
  for b in A:print,screen_replay,screen_photo B:print_warped,print_cutout; do
    n=${b%%:*}; sp=${b#*:}; mkdir -p att$n
    printf 'item_id,participant_id,label,attack_class,level,lighting,device,take,duration_s\n' > att$n/log.csv
    python3 -P "$M/tools/proxy-eval/simulate_attacks.py" --corpus bf --out att$n --species $sp --yunet "$Y" --log att$n/log.csv
  done ;;
dump)      # every frame through the production analyzer once, plus the S1 candidate views
  for n in A B; do
    mkdir -p run$n; for d in att$n/P*; do ln -sfn "$W/$d" run$n/; done
    [ $n = A ] && for d in bf/P*; do ln -sfn "$W/$d" run$n/; done
    $JAVA io.mosip.vivid.testkit.corpus.PadReplayMain dump --corpus run$n --out out/dump$n.jsonl.gz \
      --models "$M/liveness-engine-onnx/models" --log att$n/log.csv --features out/frames$n.csv \
      --view A15=$A --view A15f=$A+flip --view A12=$A@1.2 --view A20=$A@2.0 --view A27=$A@2.7 \
      --view B27=$O/2.7_80x80_MiniFASNetV2.onnx --view C40=$O/4_0_0_80x80_MiniFASNetV1SE.onnx \
      --view B27sq=$O/2.7_80x80_MiniFASNetV2.onnx+square --view C40sq=$O/4_0_0_80x80_MiniFASNetV1SE.onnx+square \
      --view B27f=$O/2.7_80x80_MiniFASNetV2.onnx+flip --view C40f=$O/4_0_0_80x80_MiniFASNetV1SE.onnx+flip
  done ;;
replay)    # real orchestrator, passive mode, one scores.jsonl per configuration
  for c in s1 grid grid2 final; do
    $JAVA io.mosip.vivid.testkit.corpus.PadReplayMain replay --dump out/dumpA.jsonl.gz --dump out/dumpB.jsonl.gz \
      --configs "$CFG/cfg-$c.jsonl" --out-dir out/$c
  done ;;
analyse)
  P="python3 -P $M/tools/proxy-eval/pad_study.py"
  $P s1 --features out/framesA.csv out/framesB.csv --split "$SPLIT" --which train --candidates A15 A15f A12+A15 A12 A20 A15+A27 \
     B27 C40 B27sq C40sq B27+C40 B27f+C40f A15+B27 A15+C40 A15f+C40f A15+B27+C40
  for s1 in "" A12; do
    $P fit --features out/framesA.csv out/framesB.csv --split "$SPLIT" --signals cnn_texture moire color_stats --nonneg --s1 "$s1"
  done
  $P fit --features out/framesA.csv out/framesB.csv --split "$SPLIT" --signals cnn_texture moire color_stats rppg --nonneg --window full
  for w in train heldout all; do $P metrics out/final/baseline.jsonl out/final/new.jsonl --split "$SPLIT" --which $w; done
  $P compare out/final/baseline.jsonl out/final/new.jsonl --split "$SPLIT" --which heldout
  $P compare out/final/baseline.jsonl out/final/new.jsonl --split "$SPLIT" --which all ;;
clean)
  rm -rf bf attA attB runA runB video ;;
*) echo "unknown step $step" >&2; exit 2 ;;
esac; done
