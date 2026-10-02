# Benchmarks

## What exists and what does not

- **Measured here:** throughput of the bundled **software** encoders, through the real engine (`vcengine_cli`), on a Linux host.
- **Not measured yet:** no Android phone or arm64 emulator was available while this was built, so there are **no on-device numbers** in this file. Phone results will differ substantially:
  - ARM cores are slower than this host's x86 cores, which encode with AVX2.
  - Thermal throttling applies on phones.
  - Hardware encoders are usually several times faster than any of these software encoders.

## In-app measurement

- The app measures real speed on the device itself; nothing is hard-coded:
  - every preview and job reports average FPS, speed (× realtime) and wall time
  - the report screen shows them
  - history keeps encode time per job
- `HistoryRepository.measuredThroughput()` turns completed jobs into pixels-per-second figures, which the time estimator uses (estimates are labelled as estimates).
- The `benchmark` build type (`./gradlew assembleBenchmark`) is an optimized release build signed for local profiling.

## Host results (software encoders)

Setup:
- Command: `scripts/host_benchmark.sh /path/to/vcengine_cli 10`
- Host: Intel Xeon @ 2.10 GHz, 4 cores (AVX2), Linux
- Source: synthetic 1920×1080, 30 fps, 10 s, `testsrc2` with heavy per-frame noise. This is a deliberately hard, almost incompressible input, so sizes are much larger than for real footage.
- Output: 1080p, no audio, all cores (automatic threading)

| Codec | Encoder | Settings | Avg FPS | Speed | Bitrate on noise |
|---|---|---|---|---|---|
| H.264 | OpenH264 2.6.0 | QP 26, 4 slices | 32.0 | 0.80× | 42.7 Mbps |
| HEVC | Kvazaar 2.3.2 | QP 30, `veryfast` | 29.9 | 0.78× | 3.5 Mbps |
| AV1 | SVT-AV1 4.2.0 | CRF 35, preset 10 | 57.7 | 1.62× | 7.9 Mbps |
| VP9 | libvpx 1.17.0 | CRF 34, realtime, cpu-used 8 | 40.6 | 1.06× | 21.6 Mbps |
| VP9 | libvpx 1.17.0 | CRF 34, good, cpu-used 4 | 6.0 | 0.16× | 13.8 Mbps |

How to read the table:
- **Bitrates are not comparable across rows.** The quality settings are not equivalent, and noise punishes some encoders' rate control differently (Kvazaar's QP 30 smooths noise away).
- **Compare speed and quality with the in-app quality ladder** on real footage. It measures size and SSIM on the same segment.

## Regression found by benchmarking

The first run showed libvpx using one core, because `AVCodecContext` defaults to a single thread. The engine now requests automatic threading, and slices OpenH264 by core count:

| Case | Before | After |
|---|---|---|
| VP9 realtime | 16.9 fps | 40.6 fps |
| VP9 good | 1.8 fps | 6.0 fps |
| H.264 | 15.1 fps | 32.0 fps |

The H.264 slicing costs about 0.1 % in size.

## Recommended device benchmark (to do on hardware)

On a target phone, run the same 1080p30 and 4K30 clips with each preset in hardware and software modes. From the report screens, record:
- average FPS
- wall time
- output size
- PSNR/SSIM from the preview
- whether thermal throttling engaged (Diagnostics shows the thermal status)
