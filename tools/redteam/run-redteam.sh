#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
#
# Red team in CI (PLAN.md 17.5, reduced scope): render synthetic attack variants from device-mock drawn faces, score
# them through the real orchestrator + ONNX engine, compute APCER per species / BPCER / ACER, append a trend row and
# fail when a previously rejected attack variant is now accepted (writing a regression fixture for each).
#
# Used by .github/workflows/redteam.yml; runs the same way locally. Docs: docs/testing/redteam-trend.md.
#
# Usage: tools/redteam/run-redteam.sh [--out DIR] [--subjects N] [--takes K] [--seed S] [--threads T]
#            [--models DIR] [--no-build] [--no-challenge] [--update-baseline] [--source ci|local]
#            [--trend-seed FILE] [--baseline FILE]
# Environment: MVN (default mvn), MVN_ARGS (extra Maven flags, e.g. "-o -q"), JAVA_OPTS (default -Xmx1g).
# Exit codes: 0 ok or skipped (no models), 1 regression found, 2 pipeline error.
set -euo pipefail
# Any unexpected failure is a pipeline error (2), never confused with a regression (1).
trap 'echo "run-redteam: pipeline error at line $LINENO (exit $?)" >&2; exit 2' ERR

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/target/redteam"
SUBJECTS=12
TAKES=2
SEED=redteam-2026
THREADS=2
MODELS="$ROOT/liveness-engine-onnx/models"
BUILD=1
CHALLENGE=1
UPDATE_BASELINE=0
SOURCE=local
BASELINE="$ROOT/docs/testing/redteam/baseline.json"
TREND_SEED="$ROOT/docs/testing/redteam/trend.jsonl"
THRESHOLD=0.997         # RESIDENT passive threshold (LivenessPolicy.defaults; 0.80 before builtin-1.1)
DETECTOR=0.35           # YuNet score threshold for drawn faces (as in e2e-tests); production uses 0.7
MVN="${MVN:-mvn}"
MVN_ARGS="${MVN_ARGS:-}"
JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --subjects) SUBJECTS="$2"; shift 2 ;;
    --takes) TAKES="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --threads) THREADS="$2"; shift 2 ;;
    --models) MODELS="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    --no-challenge) CHALLENGE=0; shift ;;
    --update-baseline) UPDATE_BASELINE=1; shift ;;
    --source) SOURCE="$2"; shift 2 ;;
    --trend-seed) TREND_SEED="$2"; shift 2 ;;
    --baseline) BASELINE="$2"; shift 2 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "run-redteam: unknown argument $1" >&2; exit 2 ;;
  esac
done

gh_output() { if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$1" >> "$GITHUB_OUTPUT"; fi; }

missing=""
for m in face_detection_yunet_2023mar.onnx antispoof_minifasnetv2se_128.onnx face_recognition_sface_2021dec.onnx; do
  [ -s "$MODELS/$m" ] || missing="$missing $m"
done
if [ -n "$missing" ]; then
  msg="ONNX models missing in $MODELS:$missing (see liveness-engine-onnx/models/README.md); red team not run."
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::notice title=Red team skipped::$msg"; else echo "run-redteam: SKIPPED: $msg"; fi
  gh_output "status=skipped"
  exit 0
fi

mkdir -p "$OUT"
# Absolute: Maven resolves a relative -Dmdep.outputFile against the module directory, not the caller's.
OUT="$(cd "$OUT" && pwd)"
cd "$ROOT"
if [ "$BUILD" = 1 ]; then
  # shellcheck disable=SC2086
  "$MVN" $MVN_ARGS -q -pl testkit -am -DskipTests install
fi
if [ "$BUILD" = 1 ] || [ ! -s "$OUT/testkit.cp" ]; then
  # shellcheck disable=SC2086
  "$MVN" $MVN_ARGS -q -pl testkit dependency:build-classpath -Dmdep.outputFile="$OUT/testkit.cp"
fi
CP="$ROOT/testkit/target/classes:$(cat "$OUT/testkit.cp")"
# shellcheck disable=SC2206
JAVA=(java $JAVA_OPTS -Djava.awt.headless=true -cp "$CP")

COMMIT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
if [ "$COMMIT" != unknown ] && [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null | head -1)" ]; then COMMIT="$COMMIT-dirty"; fi
START=$(date +%s)

echo "== render corpus ($SUBJECTS subjects, $TAKES bona fide takes each, seed $SEED)"
rm -rf "$OUT/corpus"
"${JAVA[@]}" io.mosip.vivid.testkit.redteam.RedTeamCorpusMain --out "$OUT/corpus" --subjects "$SUBJECTS" --takes "$TAKES" \
  --seed "$SEED" --threads "$THREADS" > "$OUT/render.log"
tail -1 "$OUT/render.log"

score() {   # mode
  echo "== score ($1)"
  "${JAVA[@]}" io.mosip.vivid.testkit.corpus.CorpusScoreMain --corpus "$OUT/corpus" --out "$OUT/scores_$1.jsonl" \
    --models "$MODELS" --mode "$1" --workflow RESIDENT --detector-threshold "$DETECTOR" 2> "$OUT/score_$1.err" | tail -1
  "${JAVA[@]}" io.mosip.vivid.testkit.metrics.IsoReportMain --scores "$OUT/scores_$1.jsonl" --out "$OUT/iso-$1" \
    --threshold "$THRESHOLD" --no-slices --title "mosip-vivid red team ($1 replay, RESIDENT, synthetic drawn faces)" \
    --context engine=onnx --context "mode=$1-replay" --context "commit=$COMMIT" --context "detectorThreshold=$DETECTOR" \
    --context "generator=see variants.jsonl" || echo "run-redteam: IsoReportMain could not report $1 mode (see above); the gate still runs"
}
score passive
GATE_SCORES=(--scores "passive=$OUT/scores_passive.jsonl")
if [ "$CHALLENGE" = 1 ]; then
  score challenge
  GATE_SCORES+=(--scores "challenge=$OUT/scores_challenge.jsonl")
fi
RUNTIME=$(( $(date +%s) - START ))

if [ ! -f "$OUT/trend.jsonl" ] && [ -f "$TREND_SEED" ]; then cp "$TREND_SEED" "$OUT/trend.jsonl"; fi
EXTRA=()
if [ "$UPDATE_BASELINE" = 1 ]; then EXTRA+=(--write-baseline "$BASELINE" --export-accepted "$OUT/known-bypasses"); fi
echo "== gate"
trap - ERR      # the gate's own exit code (0 ok, 1 regression) is handled below
set +e
"${JAVA[@]}" io.mosip.vivid.testkit.redteam.RedTeamGateMain --variants "$OUT/corpus/variants.jsonl" "${GATE_SCORES[@]}" \
  --threshold "$THRESHOLD" --detector-threshold "$DETECTOR" --baseline "$BASELINE" --fixtures-out "$OUT/fixtures" \
  --trend "$OUT/trend.jsonl" --trend-md "$OUT/trend.md" --summary-md "$OUT/summary.md" --summary-json "$OUT/summary.json" \
  --commit "$COMMIT" --source "$SOURCE" --runtime-seconds "$RUNTIME" "${EXTRA[@]}"
GATE=$?
set -e
gh_output "status=ran"
gh_output "gate=$GATE"
if [ "$GATE" = 1 ]; then
  gh_output "regressions=true"
  echo "run-redteam: REGRESSION: previously rejected attack variant(s) accepted; fixtures in $OUT/fixtures"
elif [ "$GATE" != 0 ]; then
  echo "run-redteam: gate error ($GATE)" >&2
  exit 2
fi
echo "run-redteam: done in ${RUNTIME}s; summary $OUT/summary.md, trend $OUT/trend.md"
exit "$GATE"
