#!/usr/bin/env bash
# Interoperability run against MOSIP's reference mock SBI (MockMDS from mosip/mosip-mock-services).
#
#   tools/interop-mockmds.sh [--record DIR] [--keep]
#
# 1. Shallow-clones mosip-mock-services at $MOCKMDS_REF (default v1.2.0.2, the 1.2.0.x line, SBI 0.9.5) into a
#    temporary directory and builds MockMDS with JDK 11 into an isolated Maven repository inside it.
# 2. Starts MockMDS as a Registration "Biometric Device" service; it binds the first free port in 127.0.0.1:4501-4600.
# 3. Optionally records raw MOSIPDISC / MOSIPDINFO / STREAM / RCAPTURE responses with curl (--record DIR).
# 4. Runs device-sbi-stream's opt-in tests MockMdsInteropTest and MockMdsConformanceTest with -Dvivid.interop.mockmds=PORT.
# 5. Stops MockMDS and deletes the clone and its Maven cache (unless --keep).
#
# Needs git, a JDK 11 (JAVA11_HOME, or /usr/lib/jvm/java-11-*), Maven, curl and network access for the first build.
# Findings: docs/testing/interop-mockmds.md.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT=$PWD
REF=${MOCKMDS_REF:-v1.2.0.2}
RECORD=""; KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --record) RECORD=$(realpath -m "$2"); shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) echo "usage: $0 [--record DIR] [--keep]" >&2; exit 2 ;;
  esac
done

JAVA11=${JAVA11_HOME:-$( (ls -d /usr/lib/jvm/java-11-openjdk* /usr/lib/jvm/temurin-11* 2>/dev/null || true) | head -1)}
[ -x "$JAVA11/bin/java" ] || { echo "JDK 11 not found; set JAVA11_HOME (MockMDS 1.2.0.x builds on Spring Boot 2.0 / Java 11)" >&2; exit 2; }

WORK=$(mktemp -d -t vivid-mockmds-XXXXXX)
MDS_PID=""
cleanup() {
  [ -n "$MDS_PID" ] && kill "$MDS_PID" 2>/dev/null && wait "$MDS_PID" 2>/dev/null || true
  if [ "$KEEP" = 1 ]; then echo "kept $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

echo "== clone mosip-mock-services@$REF into $WORK"
git clone -q --depth 1 --branch "$REF" https://github.com/mosip/mosip-mock-services.git "$WORK/src" 2>/dev/null
echo "== build MockMDS (JDK 11, isolated Maven repo $WORK/m2)"
JAVA_HOME=$JAVA11 MAVEN_OPTS=${MAVEN_OPTS:--Xmx768m} mvn -B -ntp -q -f "$WORK/src/MockMDS/pom.xml" -Dmaven.repo.local="$WORK/m2" \
    package -DskipTests -Dmaven.javadoc.skip=true -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dmaven.source.skip=true
JAR=$(ls "$WORK"/src/MockMDS/target/mock-mds-*.jar | grep -v sources | head -1)

echo "== start MockMDS"
( cd "$WORK/src/MockMDS/target" && exec "$JAVA11/bin/java" -Xmx256m -cp "$(basename "$JAR"):lib/*" io.mosip.mock.sbi.test.TestMockSBI \
    "mosip.mock.sbi.device.purpose=Registration" "mosip.mock.sbi.biometric.type=Biometric Device" ) > "$WORK/mockmds.log" 2>&1 &
MDS_PID=$!
PORT=""
for _ in $(seq 1 120); do
  PORT=$(grep -o 'SBI Proxy Service started on port [0-9]*' "$WORK/mockmds.log" 2>/dev/null | grep -o '[0-9]*$' | head -1 || true)
  [ -n "$PORT" ] && curl -s -o /dev/null -X MOSIPDISC "http://127.0.0.1:$PORT/device" -d '{"type":"Face"}' && break
  kill -0 "$MDS_PID" 2>/dev/null || { cat "$WORK/mockmds.log" >&2; echo "MockMDS exited" >&2; exit 1; }
  sleep 0.5
done
[ -n "$PORT" ] || { echo "MockMDS did not start" >&2; exit 1; }
echo "MockMDS listening on 127.0.0.1:$PORT"

if [ -n "$RECORD" ]; then
  echo "== record raw responses into $RECORD"
  mkdir -p "$RECORD"; U=http://127.0.0.1:$PORT
  curl -s -i -X MOSIPDISC "$U/device" -d '{"type":"Face"}' > "$RECORD/disc-face.http"
  curl -s -i -X MOSIPDISC "$U/device" -d '{"type":"Biometric Device"}' > "$RECORD/disc-all.http"
  curl -s -i -X MOSIPDINFO "$U/info" > "$RECORD/info.http"
  curl -s -X STREAM "$U/stream" -d '{"deviceId":"3","deviceSubId":0,"timeout":1500}' -D "$RECORD/stream-headers.txt" -o "$RECORD/stream-body.bin" --max-time 5 || true
  curl -s -i -X STREAM "$U/stream" -d '{"deviceId":"99","deviceSubId":0,"timeout":1000}' > "$RECORD/stream-baddevice.http"
  curl -s -X STREAM "$U/stream" -d '{"deviceId":"3","deviceSubId":0,"timeout":3000}' -o /dev/null --max-time 5 & sleep 0.7
  curl -s -i -X STREAM "$U/stream" -d '{"deviceId":"3","deviceSubId":0,"timeout":1000}' --max-time 3 > "$RECORD/stream-busy.http"; wait
  curl -s -i -X RCAPTURE "$U/capture" --max-time 15 -d '{"env":"Staging","purpose":"Registration","specVersion":"0.9.5","timeout":10000,"captureTime":"2026-10-24T12:00:00Z","transactionId":"vivid-1","bio":[{"type":"Face","count":1,"bioSubType":[],"exception":[],"requestedScore":40,"deviceId":"3","deviceSubId":0,"previousHash":""}],"customOpts":null}' > "$RECORD/rcapture-face.http"
  ls -l "$RECORD"
fi

echo "== device-sbi-stream interop + conformance tests against MockMDS"
cd "$REPO_ROOT"
export MAVEN_OPTS=${VIVID_MAVEN_OPTS:--Xmx1g}
mvn -B -q ${MVN_OFFLINE:+-o} -pl device-api,liveness-api,device-mock,testkit install -DskipTests
mvn -B ${MVN_OFFLINE:+-o} -pl device-sbi-stream test -DforkCount=1 -DargLine=-Xmx1g -Dvivid.interop.mockmds="$PORT" \
    -Dtest='MockMds*Test' -Dsurefire.failIfNoSpecifiedTests=false | grep -E '\[interop\]|Tests run:|FAIL|BUILD' || true
grep -h '<testsuite' device-sbi-stream/target/surefire-reports/TEST-*MockMds*.xml | grep -o 'name="[^"]*" time="[^"]*" tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"'
if grep -q 'failures="[1-9]\|errors="[1-9]' device-sbi-stream/target/surefire-reports/TEST-*MockMds*.xml; then exit 1; fi
