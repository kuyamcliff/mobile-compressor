#!/usr/bin/env bash
# Release validation for the Compressor APK (PRD release checklist).
# Usage: scripts/verify_apk.sh [path/to/app-release.apk]
# Exits non-zero if any check fails.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK=${1:-$ROOT/app/build/outputs/apk/release/app-release.apk}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null)}}
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
APKANALYZER="$SDK/cmdline-tools/latest/bin/apkanalyzer"
fail=0
pass() { printf '  PASS  %s\n' "$1"; }
bad()  { printf '  FAIL  %s\n' "$1"; fail=1; }
check() { if eval "$2" >/dev/null 2>&1; then pass "$1"; else bad "$1"; fi; }

[ -f "$APK" ] || { echo "APK not found: $APK"; exit 2; }
echo "Verifying $APK ($(du -h "$APK" | cut -f1))"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
unzip -q "$APK" -d "$TMP/x"

echo "[signature]"
check "APK signature verifies (v2+)" "'$BT/apksigner' verify --min-sdk-version 29 '$APK'"
if "$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -q "CN=Android Debug"; then bad "not signed with the debug key"; else pass "not signed with the debug key"; fi

echo "[manifest]"
"$BT/aapt2" dump badging "$APK" > "$TMP/badging.txt" 2>/dev/null
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" > "$TMP/manifest.txt" 2>/dev/null
if grep -q "android.permission.INTERNET" "$TMP/badging.txt"; then bad "no INTERNET permission"; else pass "no INTERNET permission"; fi
check "debuggable=false" "! grep -q 'application-debuggable' '$TMP/badging.txt'"
check "minSdk 29" "grep -q \"minSdkVersion:'29'\" '$TMP/badging.txt'"
check "targetSdk 36" "grep -q \"targetSdkVersion:'36'\" '$TMP/badging.txt'"
check "foreground service type declared" "grep -q 'foregroundServiceType' '$TMP/manifest.txt'"
check "allowBackup rules present" "grep -q 'dataExtractionRules' '$TMP/manifest.txt'"

echo "[native libraries]"
for so in libvcengine.so libavcodec.so libavformat.so libavfilter.so libavutil.so libswscale.so libswresample.so libc++_shared.so; do
  check "lib/arm64-v8a/$so present" "[ -f '$TMP/x/lib/arm64-v8a/$so' ]"
done
check "no GPL/nonfree FFmpeg (license string)" "grep -qa 'LGPL version 2.1 or later' '$TMP/x/lib/arm64-v8a/libavutil.so'"
check "FFmpeg built with --disable-gpl" "grep -qa -- '--disable-gpl' '$TMP/x/lib/arm64-v8a/libavutil.so'"
check "native libs stored uncompressed + 16 KB aligned" "'$BT/zipalign' -c -P 16 4 '$APK'"
for so in "$TMP"/x/lib/arm64-v8a/*.so; do
  al=$(readelf -lW "$so" 2>/dev/null | awk '/LOAD/{print $NF; exit}')
  [ "$al" = "0x4000" ] || [ "$al" = "0x10000" ] || { bad "$(basename "$so") LOAD alignment $al (need >=16 KB)"; continue; }
done && pass "ELF LOAD segments aligned to 16 KB"
check "JNI_OnLoad exported by libvcengine.so" "readelf -sW '$TMP/x/lib/arm64-v8a/libvcengine.so' | grep -q ' JNI_OnLoad'"

echo "[code shrinking keeps JNI entry points]"
if [ -x "$APKANALYZER" ]; then
  "$APKANALYZER" dex packages --defined-only "$APK" > "$TMP/dex.txt" 2>/dev/null
  check "NativeEngine class kept" "grep -q 'com.kuyamcliff.compressor.engine.NativeEngine\$' <(awk '{print \$NF}' '$TMP/dex.txt'; echo) || grep -qE '[[:space:]]com\.kuyamcliff\.compressor\.engine\.NativeEngine$' '$TMP/dex.txt'"
  check "NativeJobListener.onEvent kept" "grep -q 'NativeJobListener' '$TMP/dex.txt' && grep -q 'onEvent' '$TMP/dex.txt'"
  check "NativeEngineException kept" "grep -q 'com.kuyamcliff.compressor.engine.NativeEngineException' '$TMP/dex.txt'"
else
  echo "  SKIP  apkanalyzer not found"
fi

echo "[licenses]"
check "third-party notices bundled" "[ -s '$TMP/x/assets/licenses/00-THIRD_PARTY_NOTICES.txt' ]"
check "FFmpeg LGPL text bundled" "[ -s '$TMP/x/assets/licenses/FFmpeg-LGPL-2.1.txt' ]"

echo
if [ $fail = 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; fi
exit $fail
