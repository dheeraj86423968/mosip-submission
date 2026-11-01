#!/bin/sh
# SPDX-License-Identifier: MPL-2.0
# usage: fetch_and_extract.sh <repo> <workdir> [Pnn ...]   downloads one clip at a time, extracts, deletes the clip
M=$1; W=$2; shift 2
cd "$W" || exit 1
python3 -P "$M/tools/proxy-eval/extract_windows.py" --print-urls --sources "$M/docs/testing/proxy-eval/sources.csv" > urls.txt
mkdir -p video
while read p s f u; do
  if [ $# -gt 0 ] && ! echo " $* " | grep -q " $p "; then continue; fi
  curl -sfL --retry 5 --retry-delay 3 -A 'mosip-vivid-proxy-eval/1.0 (research)' -o video/$p.webm "$u" || { echo "$p download failed"; continue; }
  sha256sum video/$p.webm >> video-sha256.txt
  python3 -P "$M/tools/proxy-eval/extract_windows.py" --sources "$M/docs/testing/proxy-eval/sources.csv" --out bf --videos video \
      --yunet "$M/liveness-engine-onnx/models/face_detection_yunet_2023mar.onnx" --only $p 2>/dev/null
  rm -f video/$p.webm
done < urls.txt
