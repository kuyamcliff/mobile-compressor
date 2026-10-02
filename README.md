# Compressor — offline Android video compressor

A native Android video compressor built on FFmpeg 9.0.2 (LGPL, built from source)
and the phone's MediaCodec hardware encoders. Everything happens on the device:
the app has **no INTERNET permission**, and videos, previews and reports never leave the phone.

- **UI:** Kotlin, Jetpack Compose, Material 3. Simple / Advanced / Expert settings tiers, with search.
- **Engine:** C++17 behind JNI. It runs three pipelines, chosen per job from real device capabilities:
  - **Hardware:** MediaCodec decode → GPU (GLES) transform → MediaCodec encode, zero-copy.
  - **Hybrid:** FFmpeg decode + CPU filters → MediaCodec encode.
  - **Software:** FFmpeg decode + filters → OpenH264 / Kvazaar (HEVC) / SVT-AV1 / libvpx (VP9/VP8) / FFV1.
  - Plus **remux** (stream copy).
- **Safety:** output goes to a temporary file. It is validated (reopen, codec, size, duration, decode, seek) and only then finalized atomically. Originals are never modified unless "Replace original" is explicitly enabled.
- **Honest numbers:** progress, ETA and sizes come from real measurements or a documented model. Previews give real size and PSNR/SSIM.

| | |
|---|---|
| Package | `com.kuyamcliff.compressor` |
| minSdk / targetSdk | 29 / 36 |
| ABI | arm64-v8a, with 16 KB page-aligned native libraries |
| APKs | `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/release/app-release.apk` |

## Features

- **Input:** pick or share one or many videos, add a folder, or watch folders. Watched folders are scanned manually; there is no background polling.
- **Size control:**
  - constant quality (8 named levels plus a slider)
  - target size, with a feasibility / quality guard and resolution options
  - "smart target" calibration from real samples
  - average or constant bitrate
  - lossless (Kvazaar, libvpx-vp9, FFV1)
  - two-pass (libvpx)
- **Video:** codec, engine (auto/hardware/software), resolution, crop, rotate/flip, scaler, FPS cap/CFR/VFR, HDR preserve or tone-map, bit depth, colour overrides.
- **Filters:** deinterlace, detelecine, deblock, denoise, sharpen, deband, grayscale.
- **Expert:** encoder preset/tune/profile/level, GOP, B-frames, refs, threads, VBV, and raw `key=value` encoder options. Raw options are validated by the native engine before a job starts.
- **Tracks and metadata:** per-track audio (codec/bitrate/channels/sample rate/passthrough), subtitles (keep/convert, burn-in, external SRT/ASS/VTT), chapters (keep/strip/edit), metadata (keep/strip/edit).
- **Preview:** test-encode a segment, compare side by side / stacked / swipe with synchronized playback and zoom, and run a quality-ladder experiment with a measured size-vs-SSIM graph.
- **Queue:** background foreground-service queue with pause/resume/cancel/retry, priorities, reorder, thermal / battery / charging policies, and recovery after a crash or restart.
- **Library:** report per job, history, 24 built-in presets plus custom presets with import/export, diagnostics, licenses, privacy and help.

## Documentation

| File | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | layers, pipelines, data flow, plan contract |
| [docs/BUILD.md](docs/BUILD.md) | building dependencies, engine and APKs |
| [docs/FFMPEG.md](docs/FFMPEG.md) | FFmpeg configuration, components, patches |
| [docs/LICENSING.md](docs/LICENSING.md) | licenses and LGPL compliance |
| [docs/TESTING.md](docs/TESTING.md) | test suites and how to run them |
| [docs/BENCHMARKS.md](docs/BENCHMARKS.md) | measured encoder throughput |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | common problems |

## Quick start

```bash
native/third_party/build_deps.sh android-arm64-v8a   # once, ~1 h: FFmpeg + codecs
./gradlew :app:assembleDebug :app:assembleRelease
scripts/verify_apk.sh                                 # release checks
```
