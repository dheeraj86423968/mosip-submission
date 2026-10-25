#!/usr/bin/env bash
# Applies the mosip-vivid Android integration (integration-android/patches + the vivid-android module + the
# generic Dart liveness files) to the real MOSIP android-registration-client (ARC) and checks that it builds.
# Results and caveats: docs/testing/android-arc-integration.md.
#
# usage: apply-to-arc.sh <work-dir> [step ...]
#   <work-dir>  ARC is cloned into <work-dir>/arc (shallow, ~20 MB) and reused if already there.
#   steps       any of: patch pub analyze test apk   (default: patch pub analyze test)
#               patch    clone/reset ARC at $ARC_COMMIT, `git am` the patches, copy vivid-android and lib/liveness
#               pub      local.properties, `sh pigeon.sh` (flutter pub get, build_runner, every Pigeon file incl. liveness)
#               analyze  `flutter analyze` on the whole app -> <work-dir>/analyze-patched.log (count printed)
#               test     ARC's own Flutter tests + our liveness tests -> <work-dir>/test-patched.log
#               apk      `flutter build apk --debug` (Gradle without daemon, -Xmx1536m) -> <work-dir>/apk.log
#               baseline same pub/analyze/test on the UNPATCHED commit -> *-baseline.log (run it before patch)
# env:
#   FLUTTER_ROOT   Flutter 3.10.4 SDK (default ~/.cache/vivid-android/flutter)
#   ANDROID_HOME   Android SDK with platforms;android-34 and build-tools;34.0.0 (needed for apk)
#   JAVA_HOME      JDK 21 (default /usr/lib/jvm/java-21-openjdk-amd64)
#   GRADLE_USER_HOME  default ~/.cache/vivid-android/gradle-home
#   PUB_CACHE      default ~/.cache/vivid-android/pub-cache
#   VIVID_MODELS_DIR  optional dir with face_landmarker.task, minifasnet_v2_80x80_int8.tflite,
#                  mobilefacenet_112_fp16.tflite; copied into the module assets with real SHA-256 digests
#   APK_ARGS       extra `flutter build apk` arguments, e.g. "--target-platform android-arm64"
#   ARC_BUILD_DIR  put ARC's build/ (Gradle buildDir of every module, ~2 GB) elsewhere via a symlink,
#                  e.g. a tmpfs when / is short of space
#   The io.mosip.vivid jars must be in ~/.m2 (`mvn install` at the mosip-vivid root) for the apk step.
set -euo pipefail

ARC_REPO=${ARC_REPO:-https://github.com/mosip/android-registration-client}
ARC_BRANCH=${ARC_BRANCH:-release-1.2.x}
ARC_COMMIT=${ARC_COMMIT:-240c50d988e53b8fcfc86a654a2aeb2f5e48f64c}
WORK=$(realpath -m "${1:?usage: apply-to-arc.sh <work-dir> [step ...]}"); shift || true
STEPS=${*:-patch pub analyze test}
HERE=$(cd "$(dirname "$0")" && pwd)            # integration-android
ARC=$WORK/arc

export FLUTTER_ROOT=${FLUTTER_ROOT:-$HOME/.cache/vivid-android/flutter}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.cache/vivid-android/gradle-home}
export PUB_CACHE=${PUB_CACHE:-$HOME/.cache/vivid-android/pub-cache}
export PATH=$FLUTTER_ROOT/bin:$JAVA_HOME/bin:$PATH
export FLUTTER_SUPPRESS_ANALYTICS=true
GIT_ID=(-c user.name=mosip-vivid -c user.email=vivid@localhost)

has_step() { case " $STEPS " in *" $1 "*) return 0;; *) return 1;; esac; }

checkout_clean() {
  mkdir -p "$WORK"
  if [ ! -d "$ARC/.git" ]; then
    git clone -q --depth 1 -b "$ARC_BRANCH" "$ARC_REPO" "$ARC"
  fi
  cd "$ARC"
  if ! git cat-file -e "$ARC_COMMIT^{commit}" 2>/dev/null; then
    git fetch -q --depth 1 origin "$ARC_COMMIT"
  fi
  git checkout -q -f -B vivid-liveness "$ARC_COMMIT"
  # keep pub/build caches, drop everything else that is not tracked (copied module, generated code)
  git clean -qfdx -e .dart_tool -e build -e android/.gradle -e android/local.properties
  echo "ARC commit: $(git rev-parse HEAD)"
}

flutter_prep() {
  cd "$ARC"
  {
    echo "flutter.sdk=$FLUTTER_ROOT"
    [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=$ANDROID_HOME"
  } > android/local.properties
  sh pigeon.sh > "$WORK/pigeon-$1.log" 2>&1 || { tail -30 "$WORK/pigeon-$1.log"; exit 1; }
  echo "pigeon.sh ($1): ok, $(ls lib/pigeon | wc -l) Dart files in lib/pigeon"
}

run_analyze() {
  cd "$ARC"
  flutter analyze > "$WORK/analyze-$1.log" 2>&1 || true
  echo "flutter analyze ($1): $(grep -E '^[0-9]+ issues? found|No issues found' "$WORK/analyze-$1.log" || echo '?')" \
       "[errors $(grep -c '^ *error •' "$WORK/analyze-$1.log" || true)," \
       "warnings $(grep -c '^ *warning •' "$WORK/analyze-$1.log" || true)," \
       "infos $(grep -c '^ *info •' "$WORK/analyze-$1.log" || true)]"
}

run_tests() {
  cd "$ARC"
  local tests=(test/approve_packets_provider_test.dart test/login_test.dart test/machine_details_test.dart
               test/reject_dialog_test.dart test/sync_provider_test.dart test/widget_test.dart)
  [ "$1" = patched ] && tests+=(test/liveness)
  : > "$WORK/test-$1.log"
  for t in "${tests[@]}"; do
    if flutter test -j 1 "$t" >> "$WORK/test-$1.log" 2>&1; then r=pass; else r=FAIL; fi
    echo "flutter test ($1) $t: $r $(grep -oE '\+[0-9]+( ~[0-9]+)?( -[0-9]+)?: (All tests passed|Some tests failed)' "$WORK/test-$1.log" | tail -1)"
  done
}

if has_step baseline; then
  checkout_clean
  flutter_prep baseline
  run_analyze baseline
  run_tests baseline
fi

if has_step patch; then
  checkout_clean
  for p in "$HERE"/patches/*.patch; do
    git "${GIT_ID[@]}" am -q --committer-date-is-author-date "$p" && echo "applied $(basename "$p")"
  done
  # vivid-android module (sources, Pigeon output, manifest, model manifest), without build outputs
  mkdir -p android/vivid-android
  (cd "$HERE/vivid-android" && tar --exclude=build --exclude=.gradle -cf - .) | (cd android/vivid-android && tar -xf -)
  # patch 0002 carries a copy of the Pigeon definition; it must not drift from the module's
  cmp -s pigeon/liveness.dart android/vivid-android/pigeons/liveness.dart \
    || { echo "pigeon/liveness.dart (patch 0002) differs from vivid-android/pigeons/liveness.dart: regenerate the patches"; exit 1; }
  # generic Dart liveness code + its tests (lib/liveness/liveness_flow.dart comes from the patches)
  cp "$HERE"/flutter/lib/liveness/liveness_{controller,overlay,messages}.dart lib/liveness/
  mkdir -p test/liveness && cp "$HERE"/flutter/test/liveness/*.dart test/liveness/
  if [ -n "${VIVID_MODELS_DIR:-}" ]; then
    M=android/vivid-android/src/main/assets/vivid/models
    for f in face_landmarker.task minifasnet_v2_80x80_int8.tflite mobilefacenet_112_fp16.tflite; do
      if [ -f "$VIVID_MODELS_DIR/$f" ]; then
        cp "$VIVID_MODELS_DIR/$f" "$M/"
        sum=$(sha256sum "$M/$f" | cut -d' ' -f1)
        python3 - "$M/manifest.json" "$f" "$sum" <<'PY'
import json, sys
p, f, s = sys.argv[1:]
d = json.load(open(p))
for v in d["models"].values():
    if v["file"] == f:
        v["sha256"] = s
json.dump(d, open(p, "w"), indent=2)
PY
        echo "model $f: $sum"
      else
        echo "model $f: not in VIVID_MODELS_DIR (engine will report it missing at runtime)"
      fi
    done
  fi
  flutter_prep patched
fi

has_step analyze && run_analyze patched
has_step test && run_tests patched

if has_step apk; then
  cd "$ARC"
  : "${ANDROID_HOME:?ANDROID_HOME must point at an Android SDK for the apk step}"
  mkdir -p "$GRADLE_USER_HOME"
  # Low-memory machine settings; GRADLE_USER_HOME/gradle.properties overrides the project's.
  cat > "$GRADLE_USER_HOME/gradle.properties" <<'EOF'
org.gradle.daemon=false
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
org.gradle.workers.max=2
org.gradle.parallel=false
kotlin.compiler.execution.strategy=in-process
EOF
  if [ -n "${ARC_BUILD_DIR:-}" ] && [ ! -L build ]; then
    rm -rf build && mkdir -p "$ARC_BUILD_DIR" && ln -s "$ARC_BUILD_DIR" build
  fi
  # shellcheck disable=SC2086
  if flutter build apk --debug ${APK_ARGS:-} > "$WORK/apk.log" 2>&1; then
    ls -l build/app/outputs/flutter-apk/app-debug.apk
    echo "apk: BUILD OK"
  else
    tail -40 "$WORK/apk.log"
    echo "apk: BUILD FAILED (see $WORK/apk.log)"
    exit 1
  fi
fi
