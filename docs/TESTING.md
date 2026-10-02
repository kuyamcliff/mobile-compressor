# Testing

## 1. Native engine (C++, host)

- `native/engine/tests` builds `vcengine_tests`. It links the real engine and the FFmpeg **host** build, and synthesises its own fixture videos with the host `ffmpeg`.
- **53 tests:** 24 unit tests and 29 end-to-end media tests.
- Most media tests verify their output with an independent `ffprobe`, not with the engine's own probe.

```bash
native/third_party/build_deps.sh host
cmake -G Ninja -S native/engine -B /tmp/vcbuild -DCMAKE_BUILD_TYPE=RelWithDebInfo && ninja -C /tmp/vcbuild
LD_LIBRARY_PATH=native/prebuilt/host/lib /tmp/vcbuild/vcengine_tests          # all
LD_LIBRARY_PATH=native/prebuilt/host/lib /tmp/vcbuild/vcengine_tests Media    # filter by prefix
```

**Unit tests:**
- Plan: parsing and validation
- FilterBuilder: graph construction, escaping, HDR chain, rotation, fps
- Progress: real progress, pause excluded, two-pass, indeterminate
- DTS generation for B-frames
- state machine
- error translation
- timestamp and UTF-8 utilities

**Media tests:**

| Area | What is tested |
|---|---|
| Probe | source description; rotation/VFR/HDR detection; bad input is rejected |
| HEVC acceptance | 720p30 with AAC; ffprobe-verified hvc1 tag, fps, audio bitrate, A/V sync, event order |
| Rate control | H.264 QP monotonicity; VP9 two-pass hitting its target bitrate; target size and extreme targets |
| Codecs and containers | AV1 + Opus in MKV |
| Remux | remux; audio passthrough |
| Tracks | multi-track selection; chapters and metadata; metadata strip/custom |
| Subtitles | mov_text conversion; burn-in |
| Geometry and timing | portrait rotation; VFR preserve and CFR conversion |
| Colour | HDR preserve and tone-map |
| Previews | preview segments with PSNR/SSIM; lossless; copy-preview keyframe start |
| Job control | cancel leaves no completed state; pause is real |
| Error handling | corrupt and truncated sources; invalid encoder options; container conflicts |
| Edge cases | large start offsets; validator catching mismatches |
| Analysis | complexity ranking; thumbnail rotation |

The Android-only code (`android/` MediaCodec/GL pipeline, JNI) compiles warning-free with the NDK as part of the Gradle build. It can only be *run* on a device (see section 4).

## 2. Kotlin JVM unit tests (`app/src/test`)

```bash
./gradlew :app:testDebugUnitTest
```

**69 tests:**

| Suite | Tests | Covers |
|---|---|---|
| `PlannerTest` | 20 | software/hardware/hybrid selection with a fake device; HW rejection reasons; AV1 without HW; remux; scaling (landscape/portrait/no-upscale); fps cap; target-size budget; HDR choice / 10-bit; subtitle mapping per container; invalid configs; deterministic plans and JSON round-trip; cache keys; **every built-in preset plans** |
| `EstimationTest` | 8 | bpp model round-trip; size budget; impossible targets; resolution options; risk thresholds; make-smaller; ranges |
| `ValidationTest` | 9 | container/codec conflicts with auto-fix; target size; burn-in rules; option syntax; replace-original warning; compatibility matrix |
| `FileNamingTest` | 5 | tokens; sanitisation (path traversal, reserved names, control characters, length); extensions; numbering |
| `PresetsTest` | 5 | unique ids; compatibility; serialisation round-trip; private paths stripped on export |
| `JobStateMachineTest` | 7 | lifecycle; final states; late events; retry; restart recovery |
| `DevicePolicyTest` | 5 | thermal / battery / charging policy per profile |
| `RepositoriesTest` (Robolectric, in-memory Room) | 5 | enqueue ordering; validated transitions; reorder; restart recovery; preset seeding/CRUD/import/export; built-ins protected |
| `ComponentsUiTest` (Robolectric, Compose) | 5 | disabled options explain why; chip selection; switches; onboarding flow; quality-ladder recommendation |

Robolectric downloads its Android runtime jar on first use. If the test JVM cannot reach Maven Central (restricted proxies), pre-fetch it into `~/.m2`:

```bash
V=14-robolectric-10818077-i7; D=~/.m2/repository/org/robolectric/android-all-instrumented/$V; mkdir -p $D
curl -fLo $D/android-all-instrumented-$V.jar https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/$V/android-all-instrumented-$V.jar
```

## 3. Release validation

```bash
./gradlew :app:assembleRelease && scripts/verify_apk.sh
```

- 27 checks; see [BUILD.md](BUILD.md).
- `lintVitalRelease` runs as part of `assembleRelease`.

## 4. On-device testing (manual)

No device or arm64 emulator was available while this was built, so on-device behaviour is **not yet verified**. Suggested checklist on an arm64 phone:

1. **Pipelines:**
   - Phone recording (1080p/4K, HEVC, rotated) → Balanced 1080p. Expect the Hardware pipeline (shown in the plan details), a playable output in `Movies/Compressed`, and the original untouched.
   - Same file with Denoise → Hybrid. Engine = Software → Kvazaar.
2. **Size and quality:**
   - Target size 25 MB on a 5-minute video: the quality guard appears, the output lands within about 10% of the target, and Smart target is closer.
   - HDR (HLG/PQ) clip: the HDR choice dialog appears; tone-map gives SDR output; AV1/VP9 preserve 10-bit in software.
3. **Queue and lifecycle:**
   - Queue three files; pause/resume from the notification; lock the screen.
   - Kill the app mid-job: on restart the job is "Interrupted" and Retry works.
   - Heat the device or use the "Cool" profile: throttling or pause messages appear.
4. **Previews:** encode a preview, compare (all three layouts, pinch-zoom), and run the quality ladder.
5. **Diagnostics:** the codec list matches the device; share diagnostics.
6. **Share sheet:** share a video from Photos into the app.
