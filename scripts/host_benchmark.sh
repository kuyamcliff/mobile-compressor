#!/usr/bin/env bash
# Host (desktop) benchmark of the bundled software encoders through the real
# native engine (vcengine_cli). Useful to compare encoders and catch
# performance regressions; it is NOT a phone benchmark.
# Usage: scripts/host_benchmark.sh <vcengine_cli> [seconds]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CLI=${1:?path to vcengine_cli}
SECS=${2:-10}
P=$ROOT/native/prebuilt/host
export LD_LIBRARY_PATH=$P/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
"$P/bin/ffmpeg" -v error -y -filter_complex "testsrc2=size=1920x1080:rate=30,noise=alls=12:allf=t,format=yuv420p[v]" -map "[v]" \
  -t "$SECS" -c:v libopenh264 -b:v 60M "$W/src.mp4"
printf '%-6s %-12s %8s %8s %10s %8s\n' codec encoder avg_fps speed size_kB kbps
bench() { # codec encoder container rcmode quality preset
  python3 - "$@" > "$W/plan.json" <<'PY'
import json,sys
codec,enc,cont,rc,q,preset=sys.argv[1:7]
v={"sourceStreamIndex":0,"mode":"transcode","pipeline":"software","codec":codec,"encoder":enc,
   "width":1920,"height":1080,"rateControl":{"mode":rc,"quality":float(q)}}
if preset!="-": v["preset"]=preset
if enc=="libvpx-vp9": v["options"]={"cpu-used":"8" if preset=="realtime" else "4"}  # as the app planner does
print(json.dumps({"container":{"format":cont,"fastStart":True},"video":v}))
PY
  "$CLI" transcode "$W/plan.json" "$W/src.mp4" "$W/out.$3" 2>/dev/null | python3 -c "
import sys,json
for l in sys.stdin:
    if l.startswith('ENCODE_COMPLETE '):
        d=json.loads(l.split(' ',1)[1]); b=d.get('outputBytes',0)
        print('%-6s %-12s %8.1f %7.2fx %10d %8d'%('$1','$2',d.get('averageFps',0),d.get('speed',0),b/1000,b*8/$SECS/1000))
    elif l.startswith('FAILED'): print('$1 FAILED', l[:200])"
}
bench h264 libopenh264 mp4 cqp 26 -
bench hevc libkvazaar mp4 cqp 30 veryfast
bench av1 libsvtav1 mp4 crf 35 10
bench vp9 libvpx-vp9 webm crf 34 realtime
bench vp9 libvpx-vp9 webm crf 34 good
