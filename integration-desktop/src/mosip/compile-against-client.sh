#!/usr/bin/env bash
# Compiles the MOSIP-side liveness integration (src/mosip/patches + src/mosip/java) against the real
# MOSIP Desktop Registration Client. See docs/testing/desktop-mosip-compile.md for results and caveats.
#
# usage: compile-against-client.sh <work-dir> [maven-local-repo]
#   <work-dir>          where registration-client is cloned (shallow, ~240 MB) — reused if already there
#   [maven-local-repo]  default <work-dir>/m2repo (~550 MB after the build). The mosip-vivid jars must already be
#                       installed in it (or in ~/.m2, from which io/mosip/vivid is copied): `mvn install` at the repo root.
# env: JDK11 (default /usr/lib/jvm/java-11-openjdk-amd64), JDK21 (default /usr/lib/jvm/java-21-openjdk-amd64),
#      CLIENT_REF (default release-1.2.0.x), RUN_TESTS=1 to also run the client's tests for the touched classes.
set -euo pipefail
WORK=$(realpath -m "${1:?work dir}")
REPO=$(realpath -m "${2:-$WORK/m2repo}")
JDK11=${JDK11:-/usr/lib/jvm/java-11-openjdk-amd64}
JDK21=${JDK21:-/usr/lib/jvm/java-21-openjdk-amd64}
CLIENT_REF=${CLIENT_REF:-release-1.2.0.x}
HERE=$(cd "$(dirname "$0")" && pwd)          # integration-desktop/src/mosip
CLIENT=$WORK/registration-client
MVN_COMMON=(-B -Dmaven.repo.local="$REPO" -Djacoco.skip -Dmaven.javadoc.skip -Dgpg.skip)

mkdir -p "$WORK" "$REPO/io/mosip"
[ -d "$REPO/io/mosip/vivid" ] || cp -r "$HOME/.m2/repository/io/mosip/vivid" "$REPO/io/mosip/"

if [ ! -d "$CLIENT/.git" ]; then
  git clone --depth 1 -b "$CLIENT_REF" https://github.com/mosip/registration-client "$CLIENT"
fi
cd "$CLIENT"
git reset -q --hard && git clean -qfd registration
echo "client commit: $(git rev-parse HEAD)"

for p in "$HERE"/patches/*.patch; do git apply --check "$p" && git apply "$p" && echo "applied $(basename "$p")"; done
mkdir -p registration/registration-client/src/main/java/io/mosip/vivid/desktop
cp -r "$HERE/java/io/mosip/vivid/desktop/mosip" registration/registration-client/src/main/java/io/mosip/vivid/desktop/
rm -f registration/registration-client/src/main/java/io/mosip/vivid/desktop/mosip/*.txt

cd registration
# 1) parent, registration-api, stub, registration-services: the client's own toolchain (JDK 11, source/target 11)
JAVA_HOME=$JDK11 mvn "${MVN_COMMON[@]}" -DskipTests -pl registration-api,registration-api-stub-impl,registration-services -am install

# 2) registration-client with our classes. JDK 11 javac cannot read the mosip-vivid jars (class file 61 = Java 17),
#    so this step uses JDK 21 javac with --release 11 (client bytecode stays Java 11) and a Lombok that runs on JDK 21.
JAVA_HOME=$JDK21 mvn "${MVN_COMMON[@]}" -Dmaven.test.skip=true -Dlombok.version=1.18.30 -Dmaven.compiler.release=11 \
  -pl registration-client compile

if [ "${RUN_TESTS:-0}" = 1 ]; then
  JAVA_HOME=$JDK11 mvn "${MVN_COMMON[@]}" -pl registration-services surefire:test \
    -Dtest='PacketHandlerServiceTest,AppConfigTest,AuditFactoryTest,LoginServiceTest,BioServiceTest' -DfailIfNoTests=false
fi
echo "OK: patched registration-services and registration-client (incl. io.mosip.vivid.desktop.mosip) compiled"
