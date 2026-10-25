#!/usr/bin/env bash
# Model-based trace conformance between formal/LivenessOrchestrator.tla and liveness-core (PLAN.md 17.7).
#
#   1. TLC model-checks both configurations and dumps each state graph (-dump dot,actionlabels).
#   2. tools/gen_spec_tables.py regenerates the checked artifact
#      liveness-core/src/test/java/io/mosip/vivid/core/session/GeneratedSpecTables.java
#      (phase enum, failure reasons, actions, phase-level transition table).
#   3. SpecConformanceTest checks that its Java transliteration of Next reaches exactly TLC's states and
#      edges, then replays random traces against the real LivenessOrchestrator (fake clock, scripted
#      engine and detectors) and asserts the orchestrator's state after every step.
#
# Usage: formal/conformance.sh [--check-generated] [traces]
#   --check-generated  fail if the regenerated GeneratedSpecTables.java differs from the committed copy (CI)
#   traces             random traces per configuration (default 10000)
# Env: TLA2TOOLS (path to tla2tools.jar; default ~/.cache/vivid-tla/tla2tools.jar, downloaded if absent),
#      MVN_FLAGS (extra Maven flags, e.g. -o), TLC_WORKERS (default 2).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
CHECK=0
if [ "${1:-}" = "--check-generated" ]; then CHECK=1; shift; fi
TRACES=${1:-10000}
JAR=${TLA2TOOLS:-$HOME/.cache/vivid-tla/tla2tools.jar}
if [ ! -f "$JAR" ]; then
  mkdir -p "$(dirname "$JAR")"
  curl -fsSL --retry 3 -o "$JAR" https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar
fi
OUT="$ROOT/formal/target"
rm -rf "$OUT" && mkdir -p "$OUT"
for cfg in LivenessOrchestrator LivenessOrchestrator-supervisor; do
  (cd formal && java -Xmx1g -XX:+UseParallelGC -cp "$JAR" tlc2.TLC -workers "${TLC_WORKERS:-2}" -nowarning -deadlock \
      -metadir "$OUT/meta-$cfg" -dump dot,actionlabels "$OUT/$cfg.dot" \
      -config "$cfg.cfg" LivenessOrchestrator.tla) > "$OUT/$cfg.tlc.log" 2>&1
  grep -q "Model checking completed. No error has been found." "$OUT/$cfg.tlc.log" \
    || { cat "$OUT/$cfg.tlc.log"; echo "TLC failed for $cfg" >&2; exit 1; }
  grep -E "distinct states found" "$OUT/$cfg.tlc.log" | tail -1 | sed "s/^/[tlc] $cfg: /"
done
GEN=liveness-core/src/test/java/io/mosip/vivid/core/session/GeneratedSpecTables.java
cp "$GEN" "$OUT/GeneratedSpecTables.committed.java" 2>/dev/null || true
python3 formal/tools/gen_spec_tables.py formal/LivenessOrchestrator.tla "$GEN" "$OUT/LivenessOrchestrator.dot" "$OUT/LivenessOrchestrator-supervisor.dot"
if [ "$CHECK" = 1 ] && ! diff -u "$OUT/GeneratedSpecTables.committed.java" "$GEN"; then
  echo "GeneratedSpecTables.java is stale: run formal/conformance.sh and commit the result" >&2
  exit 1
fi
export VIVID_TLC_DUMP_DIR="$OUT"
# shellcheck disable=SC2086
mvn -B -ntp ${MVN_FLAGS:-} -pl liveness-core -am -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest=SpecConformanceTest -Dvivid.conformance.traces="$TRACES" -DforkCount=1 test
cat liveness-core/target/conformance/*.txt | tee "$OUT/conformance-report.txt"
