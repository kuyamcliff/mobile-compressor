# Architecture

```
┌──────────────────────────── Kotlin (app/) ────────────────────────────┐
│ UI (Compose)  ui/home, configure, compare, queue, report, history,     │
│               presets, settings, info; ui/navigation (NavHost)         │
│        │ ViewModels (StateFlow)                                        │
│ Domain        model/CompressionConfig  ──ConfigValidator──▶ Planner ──▶ │
│               ExecutionPlan (JSON) + PlanSummary; Estimator, TargetSize,│
│               Recommendations, Presets, FileNaming, Compatibility       │
│ Capability    CapabilityScanner (MediaCodecList, cached) → Resolver     │
│ Data          Room (jobs, presets, history, settings, source cache),    │
│               DataStore prefs, SAF/MediaStore storage, AppFiles         │
│ Queue         QueueManager + EncodingService (FGS) + DeviceMonitor      │
│ Preview       PreviewManager (segment encodes, metrics, ladder, smart   │
│               target calibration)                                       │
│ engine/       MediaEngine (coroutines/Flow) → NativeEngine (JNI)        │
└──────────────────────────────────┬────────────────────────────────────┘
                                   │ plan JSON + fds  /  events JSON
┌──────────────────────── C++ (native/engine/src) ──────────────────────┐
│ jni/JniBridge     RegisterNatives, listener callbacks, exceptions      │
│ job/JobManager    job threads, state machine, pause/cancel/throttle    │
│ plan/Plan         strict JSON parsing + validation of the contract      │
│ pipeline/         Transcoder (handlers per stream), FilterBuilder,      │
│                   FilterGraph, EncoderSetup, VideoEncoder, Muxer,       │
│                   Progress (EMA ETA), DtsGenerator, JobControl          │
│ android/          MediaCodec decoder → AImageReader → AHardwareBuffer → │
│                   EGLImage → GlRenderer → encoder Surface (HW);         │
│                   MediaCodecBufferEncoder (hybrid)                      │
│ analysis/         Validator, QualityMetrics (PSNR/SSIM), Complexity,    │
│                   BuildInfo                                             │
│ probe/            Probe (source analysis), Thumbnail                    │
│ core/             FdIo (AVIOContext on fds), FfRaii, Errors, Log        │
└──────────────────────────── FFmpeg 9.0.2 (LGPL .so) ──────────────────┘
```

## Single source of truth

`CompressionConfig` is the only representation of the user's settings. It is immutable, which gives:
- undo/redo as a stack of configs
- presets as stored configs
- history entries that keep the config JSON

Each edit runs this chain, debounced, off the main thread:

```
config ─▶ ConfigValidator.validate(config, PlanContext) ─▶ issues (error/warning/info + one-tap fixes)
       └▶ Planner.plan(config, ctx) ─▶ ResolvedPlan(ExecutionPlan, PlanSummary)
```

**`PlanContext`** carries:
- the probed `SourceInfo`
- the `CapabilityResolver` (device codecs)
- complexity analysis
- measured preview bitrate and throughput
- smart-target calibration
- scratch and font directories

**`ExecutionPlan`** is the contract with native code:
- It is fully resolved: encoder name, pipeline, dimensions, fps rational, rate control in the encoder's own units, filters, tracks, chapters, metadata, segment and validation expectations.
- The native side re-validates it (`plan/Plan.cpp`) and never makes policy decisions.
- `ExecutionPlan.cacheKey()` identifies previews, so a cached preview is reused only while the plan is identical.

## Pipeline selection (Planner)

| Pipeline | When | Path |
|---|---|---|
| Remux | video "copy" | FFmpeg demux → mux, no decode |
| Hardware | HW encoder supports size/fps via Surface, HW decoder exists, 8-bit SDR, no CPU-only filters | MediaCodec decode → GPU transform (crop/rotate/flip/scale/grayscale) → MediaCodec encode |
| Hybrid | HW encoder usable but CPU filters (denoise, deinterlace, burn-in…) or no surface path | FFmpeg decode + libavfilter → NV12/I420 buffers → MediaCodec encode |
| Software | everything else, or engine = Software | FFmpeg decode + libavfilter → libopenh264/kvazaar/svtav1/libvpx/ffv1/mpeg4 |

- **Why hardware is rejected:** the reasons are collected per encoder (size, alignment, 10-bit, rate control, size/rate support from `areSizeAndRateSupported`) and shown in the UI.
- **Runtime fallback:** if a hardware job fails at runtime, `Replanner` re-plans it with software for the same codec, but only if the user allowed that in settings. Otherwise the job fails with an explanation.

## Job lifecycle

```
WAITING → PREPARING → ENCODING ⇄ PAUSED/THERMAL_PAUSED → FINALIZING → COMPLETE
                 ↘ FAILED / CANCELLED / STORAGE_ERROR / UNSUPPORTED      (retry → WAITING)
process death: running → INTERRUPTED (recoverAfterRestart)
```

- **Kotlin side:** `JobStateMachine` (persisted, transactional via `JobRepository.transition`) rejects invalid or out-of-order transitions.
- **Native side:** `JobManager` has its own state machine (Created/Running/Paused/Validating/Completed/Failed/Cancelled).
- **Engine events** (JSON):
  - lifecycle: JOB_CREATED, ANALYSIS_STARTED/COMPLETE, PIPELINE_SELECTED, ENCODE_STARTED, ENCODE_COMPLETE, PAUSED/RESUMED
  - progress: PROGRESS, carrying real frames/time/bytes, current and average fps, speed, and an EMA-smoothed ETA. Two-pass progress spans both passes. Unknown durations are reported as indeterminate.
  - warnings: THERMAL_WARNING, STORAGE_WARNING
  - outcomes: VALIDATION_STARTED/COMPLETE, FAILED (categorised `EngineError` with suggestions), CANCELLED
- **Pause** is real: worker threads block, and paused time is excluded from speed and ETA.
- **Throttling** applies a duty cycle under the thermal policy.

## Output safety

1. `OutputStorage.createPending` creates the output: a MediaStore item with `IS_PENDING=1` under `Movies/Compressed/`, or a SAF document `name.partial` in a chosen folder.
2. The engine writes through the fd. MP4 faststart reopens the output via `io_open`.
3. `Validator` reopens the output and checks container, codec, resolution, duration, stream counts, decodability and seeking.
4. Only on success does `finalizeOutput` run: `IS_PENDING=0` or rename. On any failure or cancel, the pending output is deleted.
5. The original is deleted only when "Replace original" is enabled, only after step 4, and only if the provider allows deletion.

## Background execution

- **`EncodingService`:** a `dataSync|mediaProcessing` foreground service with a progress notification (pause/resume/cancel). It holds a wake lock only while jobs run.
- **Android time limit:** when Android's foreground-service time limit is reached (API 35+ `onTimeout`), running jobs are interrupted cleanly and can be retried.
- **`DeviceMonitor.policy()`:** a pure function mapping thermal status, battery, charging and the performance profile to throttle/pause decisions. It is unit tested.
- **Concurrency:** one hardware job, and up to N software jobs (setting). Parallel software jobs split threads via `PlanContext.threadBudget`.

## Threading

- **UI:** Compose on the main thread. Planning on `Dispatchers.Default`. I/O, probing and DB on `Dispatchers.IO`.
- **Native:** each job runs on its own native thread(s). JNI callbacks attach the thread and post events into a `callbackFlow`.
- **Encoders:** software encoders use all cores by default.
