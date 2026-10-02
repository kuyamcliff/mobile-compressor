# Licensing

*This is an engineering summary, not legal advice.*

## The app

- The app's own code (Kotlin and C++) is MIT-licensed; see `LICENSE`.

## Bundled components

| Component | License | How it ships |
|---|---|---|
| FFmpeg 9.0.2 | LGPL-2.1-or-later | separate shared libraries `lib{avcodec,avformat,avfilter,avutil,swscale,swresample}.so` |
| LAME 3.100 | LGPL-2.0-or-later | static inside `libavcodec.so` |
| FriBidi 1.0.17 | LGPL-2.1-or-later | static inside `libavfilter.so` |
| dav1d, OpenH264 | BSD-2-Clause | static inside `libavcodec.so` |
| Kvazaar, libvpx, Opus | BSD-3-Clause | static inside `libavcodec.so` |
| SVT-AV1 | BSD-3-Clause-Clear + AOM patent license | static inside `libavcodec.so` |
| zimg | WTFPL | static inside `libavfilter.so` |
| FreeType | FreeType License (FTL) | static inside `libavfilter.so` |
| HarfBuzz | MIT | static inside `libavfilter.so` |
| libass | ISC | static inside `libavfilter.so` |
| nlohmann/json | MIT | header-only, in `libvcengine.so` |
| AndroidX, Compose, Kotlin, Media3 | Apache-2.0 | Java/Kotlin dependencies |

**No GPL or nonfree code is included.**
- FFmpeg is configured with `--disable-gpl --disable-nonfree`.
- GPL-only filters and encoders (x264, x265, hqdn3d, …) are absent.
- `scripts/verify_apk.sh` checks the license string in the shipped `libavutil.so`.

## License texts in the APK

- All license texts are bundled in `app/src/main/assets/licenses/` and shown in the app under Settings → About → Licenses.
- `00-THIRD_PARTY_NOTICES.txt` summarises the components, the exact FFmpeg configure line, and the LGPL information.

## LGPL obligations when distributing the APK

1. **Replaceable library:** the LGPL code lives in the separate, dynamically loaded FFmpeg shared libraries, and the app uses only their public API. A user can rebuild them (same ABI) with `build_deps.sh` and replace them in the APK.
2. **Notice and license text:** both are shown in the app and included in the APK.
3. **Corresponding source:** whoever distributes the APK must offer the complete corresponding source of the LGPL components:
   - the FFmpeg 9.0.2 tarball plus the two patches in `native/third_party/patches/`
   - LAME 3.100
   - FriBidi 1.0.17
   - the build script `native/third_party/build_deps.sh` with `versions.env`

   Publishing this repository (with the patches and script) together with the upstream source archives satisfies this in the usual way.
4. **Do not enable GPL options.** `--enable-gpl` or GPL-only libraries such as x264/x265 would turn the whole app into GPL-covered work. `--enable-nonfree` (e.g. fdk-aac) would make it undistributable.

## Patents

- H.264, HEVC and, depending on jurisdiction, other formats may be patent-encumbered.
- OpenH264 is compiled from source here. Cisco's royalty coverage applies only to Cisco-distributed OpenH264 binaries, so it does **not** cover this build.
- Hardware codecs are provided by the device through MediaCodec.
- Anyone distributing the app commercially should get legal advice on codec patent licensing.

## Fonts

- Burned-in subtitles use a system font (e.g. Roboto) found on the device at runtime. No font is bundled.
