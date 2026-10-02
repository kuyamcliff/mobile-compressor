# FFmpeg in this app

## Version and build

- **FFmpeg 9.0.2** (current stable at build time), built from the release tarball by `native/third_party/build_deps.sh`.
- **License:** LGPL version 2.1 or later. The build uses `--disable-gpl --disable-nonfree --disable-autodetect`; the license string embedded in `libavutil.so` is checked by `scripts/verify_apk.sh`.
- **Libraries:** shared `libavcodec`, `libavformat`, `libavfilter`, `libavutil`, `libswscale`, `libswresample`, built against `libc++_shared` with 16 KB page alignment. Not built: `libavdevice` (Android), programs, network protocols, docs.
- **External libraries:** compiled statically *into* those shared libraries, so the LGPL libraries remain replaceable:

| Library | Version | Used for |
|---|---|---|
| dav1d | 1.5.4 | AV1 decoding |
| OpenH264 | 2.6.0 | H.264 software encoding |
| Kvazaar | 2.3.2 | HEVC software encoding |
| libvpx | 1.17.0 | VP8/VP9 software encoding (two-pass) |
| SVT-AV1 | 4.2.0 | AV1 software encoding |
| Opus | 1.6.1 | Opus audio |
| LAME | 3.100 | MP3 audio |
| zimg | 3.0.6 | `zscale` (HDR tone-mapping colour conversion) |
| FreeType / FriBidi / HarfBuzz / libass | 2.14.3 / 1.0.17 / 14.5.1 / 0.17.5 | subtitle burn-in |

## Enabled components

Only what the app uses is enabled (`--disable-everything`, then an explicit allow-list):

- **Decoders:**
  - video: H.264, HEVC, VP8, VP9, AV1 (dav1d), MPEG-4/2/1, H.263, MJPEG, ProRes, DNxHD, Theora, VC-1/WMV, FLV, GIF, PNG, FFV1, raw, HuffYUV, UtVideo
  - audio: AAC, MP1/2/3, AC-3/E-AC-3, Opus, Vorbis, FLAC, ALAC, DTS, TrueHD/MLP, WMA, AMR, GSM, ADPCM, PCM
  - subtitles: SubRip, ASS/SSA, WebVTT, mov_text, DVD/DVB/PGS bitmaps, MicroDVD
- **Encoders:** libopenh264, libkvazaar, libvpx (VP8/VP9), libsvtav1, mpeg4, ffv1, mjpeg/png (thumbnails), AAC, libopus, libmp3lame, FLAC, ALAC, AC-3/E-AC-3, PCM, mov_text/SubRip/ASS/WebVTT/dvdsub
- **(De)muxers:**
  - demuxers: MOV/MP4, Matroska/WebM, AVI, MPEG-TS/PS, FLV, Ogg, ASF, MXF, raw elementary streams, subtitle files
  - muxers: MP4, MOV, Matroska, WebM, 3GP, MPEG-TS
- **Filters:**
  - geometry and timing: scale, crop, pad, fps, transpose/flip/rotate, trim
  - deinterlace and telecine: yadif, bwdif, idet, fieldmatch/decimate
  - cleanup: deblock, nlmeans, atadenoise, bm3d, unsharp, cas, deband
  - colour and HDR: colorspace, zscale, tonemap
  - subtitles: subtitles, ass
  - analysis: psnr, ssim, signalstats, entropy, scdet, cropdetect
- **GPL-only filters are not available:** hqdn3d, eq, pp, colormatrix, pullup and the x264/x265 encoders, among others. The app uses LGPL equivalents: nlmeans/atadenoise instead of hqdn3d, and fieldmatch+decimate instead of pullup.

The full configure line is printed in the app (Settings → Licenses → `00-THIRD_PARTY_NOTICES.txt`, and Diagnostics → Native engine).

## Local patches

Both patches are in `native/third_party/patches/` and are applied idempotently by the build script.

1. **`ffmpeg-0001-libopenh264enc-constant-qp.patch`**
   - Problem: with `rc_mode=off`, FFmpeg's OpenH264 wrapper never sets the layer QP, so constant-QP encoding ignored the requested quality.
   - Fix: the patch derives `iDLayerQp` from `qmin`/`qmax`.
   - Covered by `Media.H264QualityScaleIsMonotonic`.
2. **`ffmpeg-0002-vf_subtitles-fallback-font.patch`**
   - Adds a `fallback_font` option to the `subtitles` filter, passed to `ass_set_fonts`.
   - Without it, there is no fontconfig on Android, so burned-in text subtitles render with no glyphs.
   - Covered by `Media.BurnInTextSubtitles`.

## How the app uses FFmpeg

- Not through the `ffmpeg` command-line tool: the C++ engine uses the libav* APIs directly.
- File access: Android `content://` URIs are opened as file descriptors in Kotlin. A custom `AVIOContext` does `pread`/`pwrite` on them, with `io_open` hooks for the MP4 faststart second pass. The engine never sees file paths.
- The C++ code uses RAII wrappers (`core/FfRaii.h`). FFmpeg errors are translated into user-facing categories (`core/Errors.cpp`).
- `av_log` is routed to logcat and to a per-job capture used for error details.

## Upgrading

Edit `native/third_party/versions.env`, delete `build-deps/` (or the relevant `.done-*` marker), re-run the script, then run the native tests (see [TESTING.md](TESTING.md)).
If a patch no longer applies, check whether upstream fixed the issue before rebasing it.
