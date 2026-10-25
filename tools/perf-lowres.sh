#!/usr/bin/env bash
# Low-resource performance proxy: runs testkit's PerfBenchMain (engine alone and full orchestrator, embedding on
# every frame and every 5th frame) pinned to 1 and 2 CPU cores with a 256 MiB or 512 MiB Java heap.
# This is NOT an Android device; see docs/testing/performance.md for the method and caveats.
#
#   tools/perf-lowres.sh [out-dir]          # default out-dir: target/perf
# Env: FRAMES (300), WARMUP (30), CORE1 (3), CORE2 ("2,3"), MODELS (liveness-engine-onnx/models)
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:-target/perf}
FRAMES=${FRAMES:-300}; WARMUP=${WARMUP:-30}
CORE1=${CORE1:-3}; CORE2=${CORE2:-2,3}
MODELS=${MODELS:-liveness-engine-onnx/models}
command -v taskset >/dev/null || { echo "taskset (util-linux) is required" >&2; exit 2; }
[ -f "$MODELS/face_detection_yunet_2023mar.onnx" ] || { echo "models missing in $MODELS (see liveness-engine-onnx/models/README.md)" >&2; exit 3; }
mkdir -p "$OUT"

export MAVEN_OPTS=${MAVEN_OPTS:--Xmx1g}
mvn -o -q -B -pl device-api,liveness-api,device-mock,policy,telemetry,model-bundle,liveness-core,liveness-engine-onnx,testkit install -DskipTests
mvn -o -q -B -pl testkit dependency:build-classpath -Dmdep.outputFile="$OUT/cp.txt" -Dmdep.includeScope=test
CP="testkit/target/classes:$(cat "$OUT/cp.txt")"

run() {   # label cpus xmx ortThreads [format]
  local label=$1 cpus=$2 xmx=$3 threads=$4 fmt=${5:-RGB888}
  echo "== $label (taskset -c $cpus, -Xmx$xmx, ORT threads $threads, $fmt) load: $(cut -d' ' -f1-3 /proc/loadavg)" | tee -a "$OUT/summary.txt"
  taskset -c "$cpus" java -Xmx"$xmx" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn --enable-native-access=ALL-UNNAMED -cp "$CP" \
      io.mosip.vivid.testkit.bench.PerfBenchMain --models "$MODELS" --frames "$FRAMES" --warmup "$WARMUP" --threads "$threads" \
      --format "$fmt" --label "$label" --json "$OUT/$label.json" 2>/dev/null | tee -a "$OUT/summary.txt"
}

: > "$OUT/summary.txt"
echo "# $(date -Is) $(uname -srm) $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs) java $(java -version 2>&1 | head -1)" | tee -a "$OUT/summary.txt"
run 1core-256m  "$CORE1" 256m 1
run 1core-512m  "$CORE1" 512m 1
run 2core-256m  "$CORE2" 256m 2
run 2core-512m  "$CORE2" 512m 2
run 2core-512m-mjpeg "$CORE2" 512m 2 MJPEG
run 4core-512m  "0-3"    512m 2
echo "results in $OUT/summary.txt and $OUT/*.json"
