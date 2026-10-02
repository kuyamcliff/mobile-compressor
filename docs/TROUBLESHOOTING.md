# Troubleshooting

## Building

| Symptom | Fix |
|---|---|
| `FFmpeg prebuilt not found at native/prebuilt/android-arm64-v8a` | Run `native/third_party/build_deps.sh android-arm64-v8a` (or `./gradlew buildNativeDeps`). |
| A dependency fails to build | Look at `build-deps/build/<target>/<name>.log`. Delete `build-deps/build/<target>/<name>` and its `.done-<name>` marker, then re-run. |
| FFmpeg patch fails to apply after a version bump | Upstream may already include the fix; see [FFMPEG.md](FFMPEG.md#local-patches). |
| Gradle "Could not resolve …" | Transient network or proxy failure. Re-run; the build has no other network needs once dependencies are cached. |
| Robolectric "Failed to fetch maven artifact android-all-instrumented" | Pre-fetch the jar into `~/.m2` (see [TESTING.md](TESTING.md)). |
| `lintVitalRelease` fails | Fix the reported issue; the release build intentionally fails on lint errors. |
| Release APK is unsigned | Create `keystore.properties` ([BUILD.md](BUILD.md#release-signing)). |
| Host test binary can't find `.so` files | Set `export LD_LIBRARY_PATH=$PWD/native/prebuilt/host/lib`. |

## Using the app

| Symptom | Cause / what to do |
|---|---|
| "Hardware … is not used" | The plan details list the exact reason: no HW encoder for the codec, size/fps unsupported, 10-bit/HDR, or a CPU-only filter (which gives Hybrid). Choose H.264/HEVC, lower the resolution, or accept software. |
| Very slow encoding | Software AV1/VP9 "good"/heavy denoise are CPU-bound. Use Automatic/Hardware, HEVC/H.264, VP9 realtime, or remove nlmeans/bm3d denoise. Check the thermal status in Diagnostics. |
| Job shows "Cooling down" | Thermal policy paused or throttled it. It resumes automatically; the "Performance" profile is less conservative (critical temperatures always pause). |
| Job waits with "Waiting for the charger" | "Only run when charging" is enabled in Settings. |
| Job "Interrupted" | The app was killed or the device restarted, or Android's background time limit was reached. Tap Retry. |
| Output larger than the original | The source was already efficient. Use a lower quality, a target size, or HEVC/AV1. The report warns about this. |
| Target size missed | Constant-bitrate control is approximate for short or complex clips. Enable Smart target (sample calibration) or two-pass (VP9). |
| "Storage error" / not enough space | Free space; the engine checks free space before starting and stops cleanly if the disk fills. |
| Shared video fails after closing the app | The share-sheet permission is temporary. Keep the app open until the job starts, or add the file with "Add videos". |
| Subtitles missing in MP4 | MP4 only holds text subtitles (mov_text); bitmap subtitles (PGS/DVD) are dropped with a warning. Use MKV or burn them in. |
| Burned-in subtitles show boxes | The device's fallback font lacks glyphs for that script. |
| Cannot play a format in preview | The preview player is Android's (Media3). Unsupported formats show an error there, but compression still works. |
| App crashed | Settings → Diagnostics shows stored crash reports (local only); share them to report a bug. |
