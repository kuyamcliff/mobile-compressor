#!/usr/bin/env bash
# Builds FFmpeg and every external codec/filter library it links against, from
# source, for one target:
#
#   build_deps.sh android-arm64-v8a   # primary Android ABI
#   build_deps.sh android-x86_64      # emulators / Chromebooks (optional)
#   build_deps.sh host                # Linux x86_64, used by native tests
#
# All external libraries are built as static, position-independent archives and
# folded into the FFmpeg *shared* libraries (libavcodec.so, ...). FFmpeg itself is
# built LGPL-2.1-or-later: no --enable-gpl, no --enable-nonfree. See LICENSING.md.
#
# Output: native/prebuilt/<target>/{include,lib}
# Work dir: build-deps/ (sources, build trees; safe to delete)
set -euo pipefail

TARGET="${1:?usage: build_deps.sh <android-arm64-v8a|android-x86_64|host>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=versions.env
source "$HERE/versions.env"

WORK="${DEPS_WORK_DIR:-$ROOT/build-deps}"
SRC="$WORK/src"
BLD="$WORK/build/$TARGET"
PREFIX="$ROOT/native/prebuilt/$TARGET"
JOBS="${JOBS:-$(nproc)}"
mkdir -p "$SRC" "$BLD" "$PREFIX/lib/pkgconfig" "$PREFIX/include"

log() { printf '\n==> [%s] %s\n' "$TARGET" "$*"; }

# ---------------------------------------------------------------------------
# Toolchain
# ---------------------------------------------------------------------------
COMMON_CFLAGS="-O3 -fPIC -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fno-strict-aliasing"
case "$TARGET" in
  android-*)
    ABI="${TARGET#android-}"
    ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
    NDK="${ANDROID_NDK_HOME:-$ANDROID_SDK/ndk/$NDK_VERSION}"
    [ -d "$NDK" ] || { echo "NDK not found at $NDK" >&2; exit 1; }
    TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
    SYSROOT="$TC/sysroot"
    case "$ABI" in
      arm64-v8a) TRIPLE=aarch64-linux-android; FF_ARCH=aarch64; FF_CPU=armv8-a; MESON_CPU_FAMILY=aarch64; MESON_CPU=armv8; CMAKE_PROC=aarch64; VPX_TARGET=arm64-android-gcc ;;
      x86_64)    TRIPLE=x86_64-linux-android;  FF_ARCH=x86_64;  FF_CPU=x86-64;  MESON_CPU_FAMILY=x86_64;  MESON_CPU=x86_64; CMAKE_PROC=x86_64; VPX_TARGET=x86_64-android-gcc ;;
      *) echo "unsupported ABI $ABI" >&2; exit 1 ;;
    esac
    export CC="$TC/bin/${TRIPLE}${ANDROID_API}-clang"
    export CXX="$TC/bin/${TRIPLE}${ANDROID_API}-clang++"
    export AR="$TC/bin/llvm-ar" NM="$TC/bin/llvm-nm" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip"
    export LD="$TC/bin/ld.lld"
    HOST_TRIPLE="$TRIPLE"
    # 16 KB page alignment is required for apps targeting Android 15+ devices.
    EXTRA_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
    CXX_RUNTIME="-lc++_shared"
    CROSS=1
    ;;
  host)
    export CC=gcc CXX=g++ AR=ar NM=nm RANLIB=ranlib STRIP=strip
    HOST_TRIPLE="$(gcc -dumpmachine)"
    EXTRA_LDFLAGS=""
    CXX_RUNTIME="-lstdc++"
    CROSS=0
    ;;
  *) echo "unknown target $TARGET" >&2; exit 1 ;;
esac

export CFLAGS="$COMMON_CFLAGS -I$PREFIX/include"
export CXXFLAGS="$COMMON_CFLAGS -I$PREFIX/include"
export LDFLAGS="$EXTRA_LDFLAGS -L$PREFIX/lib"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
fetch_git() { # name url tag
  local dir="$SRC/$1-$3"
  if [ ! -d "$dir/.git" ]; then
    rm -rf "$dir"
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$3" "$2" "$dir"
  fi
  echo "$dir"
}
fetch_tar() { # name url version
  local dir="$SRC/$1-$3" tarball="$SRC/$(basename "$2")"
  if [ ! -d "$dir" ]; then
    [ -f "$tarball" ] || curl -fsSL --retry 4 -o "$tarball" "$2"
    mkdir -p "$dir" && tar -xf "$tarball" -C "$dir" --strip-components=1
  fi
  echo "$dir"
}
done_marker() { echo "$BLD/.done-$1"; }
is_done() { [ -f "$(done_marker "$1")" ]; }
mark_done() { touch "$(done_marker "$1")"; }

MESON_CROSS="$BLD/meson-cross.ini"
write_meson_cross() {
  [ "$CROSS" = 1 ] || return 0
  cat > "$MESON_CROSS" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
nm = '$NM'
pkg-config = 'pkg-config'

[built-in options]
c_args = [$(printf "'%s'," $CFLAGS)]
cpp_args = [$(printf "'%s'," $CXXFLAGS)]
c_link_args = [$(printf "'%s'," $LDFLAGS)]
cpp_link_args = [$(printf "'%s'," $LDFLAGS)]

[properties]
pkg_config_libdir = '$PKG_CONFIG_LIBDIR'

[host_machine]
system = 'android'
cpu_family = '$MESON_CPU_FAMILY'
cpu = '$MESON_CPU'
endian = 'little'
EOF
}
meson_build() { # name srcdir [meson options...]
  local name="$1" src="$2"; shift 2
  local b="$BLD/$name"
  rm -rf "$b"
  local cross=()
  [ "$CROSS" = 1 ] && cross=(--cross-file "$MESON_CROSS")
  meson setup "$b" "$src" "${cross[@]}" --prefix="$PREFIX" --libdir=lib \
    --buildtype=release --default-library=static -Db_staticpic=true "$@" >"$b.log" 2>&1 || { tail -50 "$b.log"; exit 1; }
  ninja -C "$b" -j "$JOBS" >>"$b.log" 2>&1 || { tail -50 "$b.log"; exit 1; }
  ninja -C "$b" install >>"$b.log" 2>&1
}
cmake_build() { # name srcdir [cmake options...]
  local name="$1" src="$2"; shift 2
  local b="$BLD/$name"
  rm -rf "$b"
  local tc=()
  if [ "$CROSS" = 1 ]; then
    tc=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$ANDROID_API" -DANDROID_STL=c++_shared)
  fi
  cmake -S "$src" -B "$b" -G Ninja "${tc[@]}" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_PREFIX_PATH="$PREFIX" -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
    -DCMAKE_C_FLAGS="$CFLAGS" -DCMAKE_CXX_FLAGS="$CXXFLAGS" \
    -DCMAKE_SHARED_LINKER_FLAGS="$LDFLAGS" -DCMAKE_EXE_LINKER_FLAGS="$LDFLAGS" \
    "$@" >"$b.log" 2>&1 || { tail -50 "$b.log"; exit 1; }
  cmake --build "$b" -j "$JOBS" >>"$b.log" 2>&1 || { tail -50 "$b.log"; exit 1; }
  cmake --install "$b" >>"$b.log" 2>&1
}
autotools_build() { # name srcdir [configure options...]
  local name="$1" src="$2"; shift 2
  local b="$BLD/$name"
  rm -rf "$b" && mkdir -p "$b"
  local host=()
  [ "$CROSS" = 1 ] && host=(--host="$HOST_TRIPLE")
  (cd "$b" && "$src/configure" "${host[@]}" --prefix="$PREFIX" --libdir="$PREFIX/lib" \
      --enable-static --disable-shared --with-pic "$@" >"$b.log" 2>&1) || { tail -50 "$b.log"; exit 1; }
  make -C "$b" -j "$JOBS" >>"$b.log" 2>&1 || { tail -50 "$b.log"; exit 1; }
  make -C "$b" install >>"$b.log" 2>&1
}
# C++ static libraries need the C++ runtime when linked from C (FFmpeg links with
# the C driver). Rewrite their pkg-config files to name the right runtime.
fix_cxx_pc() {
  local pc="$PREFIX/lib/pkgconfig/$1.pc"
  [ -f "$pc" ] || return 0
  sed -i -E 's/ -l(stdc\+\+|c\+\+|c\+\+_shared|c\+\+_static)\b//g' "$pc"
  if grep -q '^Libs.private:' "$pc"; then
    sed -i -E "s|^Libs.private:(.*)|Libs.private:\1 $CXX_RUNTIME|" "$pc"
  else
    echo "Libs.private: $CXX_RUNTIME" >> "$pc"
  fi
}

write_meson_cross

# ---------------------------------------------------------------------------
# Libraries
# ---------------------------------------------------------------------------
if ! is_done dav1d; then
  log "dav1d $DAV1D_TAG (AV1 decoder, BSD-2-Clause)"
  s=$(fetch_git dav1d "$DAV1D_GIT" "$DAV1D_TAG")
  meson_build dav1d "$s" -Denable_tools=false -Denable_tests=false -Denable_examples=false
  mark_done dav1d
fi

if ! is_done openh264; then
  log "OpenH264 $OPENH264_TAG (H.264 encoder, BSD-2-Clause)"
  s=$(fetch_git openh264 "$OPENH264_GIT" "$OPENH264_TAG")
  meson_build openh264 "$s" -Dtests=disabled
  fix_cxx_pc openh264
  mark_done openh264
fi

if ! is_done kvazaar; then
  log "Kvazaar $KVAZAAR_TAG (HEVC encoder, BSD-3-Clause)"
  s=$(fetch_git kvazaar "$KVAZAAR_GIT" "$KVAZAAR_TAG")
  [ -f "$s/configure" ] || (cd "$s" && ./autogen.sh >/dev/null 2>&1)
  # Kvazaar's x86 intrinsics are guarded by its configure checks; on ARM it uses
  # the portable C kernels. Bionic provides the librt symbols in libc, so give the
  # linker an empty librt.a for kvazaar's (unused) CLI target.
  if [ "$CROSS" = 1 ]; then
    mkdir -p "$BLD/stublibs" && "$AR" rc "$BLD/stublibs/librt.a"
    LDFLAGS="$LDFLAGS -L$BLD/stublibs" autotools_build kvazaar "$s"
    sed -i -E 's/ -lrt\b//g' "$PREFIX/lib/pkgconfig/kvazaar.pc"
  else
    autotools_build kvazaar "$s"
  fi
  mark_done kvazaar
fi

if ! is_done libvpx; then
  log "libvpx $LIBVPX_TAG (VP8/VP9 encoder, BSD-3-Clause)"
  s=$(fetch_git libvpx "$LIBVPX_GIT" "$LIBVPX_TAG")
  b="$BLD/libvpx"; rm -rf "$b"; mkdir -p "$b"
  vpx_target=()
  [ "$CROSS" = 1 ] && vpx_target=(--target="$VPX_TARGET")
  # libvpx invokes $LD directly with driver-style flags; link through the compiler.
  (cd "$b" && LD="$CC" LDFLAGS="$LDFLAGS" "$s/configure" "${vpx_target[@]}" --prefix="$PREFIX" --libdir="$PREFIX/lib" \
      --enable-pic --enable-static --disable-shared --disable-examples --disable-tools --disable-docs \
      --disable-unit-tests --disable-decode-perf-tests --disable-encode-perf-tests \
      --enable-vp8 --enable-vp9 --enable-vp9-highbitdepth --enable-runtime-cpu-detect \
      --enable-multithread --disable-webm-io --disable-libyuv >"$b.log" 2>&1) || { tail -50 "$b.log"; exit 1; }
  make -C "$b" -j "$JOBS" >>"$b.log" 2>&1 || { tail -50 "$b.log"; exit 1; }
  make -C "$b" install >>"$b.log" 2>&1
  mark_done libvpx
fi

if ! is_done svtav1; then
  log "SVT-AV1 $SVTAV1_TAG (AV1 encoder, BSD-3-Clause-Clear + AOM patent license)"
  s=$(fetch_git svtav1 "$SVTAV1_GIT" "$SVTAV1_TAG")
  cmake_build svtav1 "$s" -DBUILD_APPS=OFF -DBUILD_DEC=OFF -DBUILD_TESTING=OFF -DENABLE_AVX512=OFF -DSVT_AV1_LTO=OFF
  mark_done svtav1
fi

if ! is_done opus; then
  log "Opus $OPUS_TAG (Opus encoder, BSD-3-Clause)"
  s=$(fetch_git opus "$OPUS_GIT" "$OPUS_TAG")
  cmake_build opus "$s" -DOPUS_BUILD_TESTING=OFF -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_INSTALL_PKG_CONFIG_MODULE=ON \
    -DOPUS_DISABLE_INTRINSICS=OFF -DOPUS_DRED=OFF -DOPUS_OSCE=OFF
  mark_done opus
fi

if ! is_done lame; then
  log "LAME $LAME_VERSION (MP3 encoder, LGPL-2.0-or-later)"
  s=$(fetch_tar lame "$LAME_URL" "$LAME_VERSION")
  # The 2017 config.sub predates some triplets; refresh from automake.
  cp -f /usr/share/misc/config.sub /usr/share/misc/config.guess "$s/" 2>/dev/null || \
    cp -f "$(automake --print-libdir)/config.sub" "$(automake --print-libdir)/config.guess" "$s/"
  # lame 3.100 references a symbol removed from its own export list.
  sed -i '/lame_init_old/d' "$s/include/libmp3lame.sym"
  autotools_build lame "$s" --disable-frontend --disable-decoder --disable-gtktest --disable-analyzer-hooks
  mark_done lame
fi

if ! is_done zimg; then
  log "zimg $ZIMG_TAG (colorspace/HDR conversion, WTFPL)"
  s=$(fetch_git zimg "$ZIMG_GIT" "$ZIMG_TAG")
  (cd "$s" && git submodule update --init --depth 1 --quiet 2>/dev/null || true)
  [ -f "$s/configure" ] || (cd "$s" && ./autogen.sh >/dev/null 2>&1)
  autotools_build zimg "$s" --disable-testapp --disable-example --disable-unit-test
  fix_cxx_pc zimg
  mark_done zimg
fi

if ! is_done freetype; then
  log "FreeType $FREETYPE_TAG (subtitle rendering, FTL)"
  s=$(fetch_git freetype "$FREETYPE_GIT" "$FREETYPE_TAG")
  meson_build freetype "$s" -Dzlib=internal -Dbzip2=disabled -Dpng=disabled -Dbrotli=disabled \
    -Dharfbuzz=disabled -Dtests=disabled -Dmmap=enabled
  mark_done freetype
fi

if ! is_done fribidi; then
  log "FriBidi $FRIBIDI_TAG (bidi text, LGPL-2.1-or-later)"
  s=$(fetch_git fribidi "$FRIBIDI_GIT" "$FRIBIDI_TAG")
  meson_build fribidi "$s" -Ddocs=false -Dtests=false -Dbin=false
  mark_done fribidi
fi

if ! is_done harfbuzz; then
  log "HarfBuzz $HARFBUZZ_TAG (text shaping, MIT)"
  s=$(fetch_git harfbuzz "$HARFBUZZ_GIT" "$HARFBUZZ_TAG")
  # HarfBuzz 14 ships its CMake build in the release tag; Meson files are absent.
  cmake_build harfbuzz "$s" -DHB_HAVE_FREETYPE=ON -DHB_HAVE_GLIB=OFF -DHB_HAVE_ICU=OFF -DHB_HAVE_CAIRO=OFF \
    -DHB_HAVE_GRAPHITE2=OFF -DHB_HAVE_GOBJECT=OFF -DHB_HAVE_INTROSPECTION=OFF -DHB_BUILD_UTILS=OFF \
    -DHB_BUILD_SUBSET=OFF -DHB_BUILD_RASTER=OFF -DHB_BUILD_VECTOR=OFF -DHB_BUILD_GPU=OFF -DHB_BUILD_TESTS=OFF
  fix_cxx_pc harfbuzz
  mark_done harfbuzz
fi

if ! is_done libass; then
  log "libass $LIBASS_TAG (ASS/SSA subtitle burn-in, ISC)"
  s=$(fetch_git libass "$LIBASS_GIT" "$LIBASS_TAG")
  if [ -f "$s/meson.build" ]; then
    meson_build libass "$s" -Dfontconfig=disabled -Ddirectwrite=disabled -Dcoretext=disabled \
      -Drequire-system-font-provider=false -Dtest=disabled -Dcompare=disabled -Dprofile=disabled -Dfuzz=disabled -Dcheckasm=disabled -Dlibunibreak=disabled
  else
    [ -f "$s/configure" ] || (cd "$s" && ./autogen.sh >/dev/null 2>&1)
    autotools_build libass "$s" --disable-fontconfig --disable-require-system-font-provider --disable-test
  fi
  mark_done libass
fi

# ---------------------------------------------------------------------------
# FFmpeg
# ---------------------------------------------------------------------------
FF_DECODERS="h264 hevc vp8 vp9 libdav1d mpeg4 msmpeg4v3 mpeg2video mpeg1video h263 mjpeg prores dnxhd theora vc1 wmv1 wmv2 wmv3 flv gif png ffv1 rawvideo huffyuv utvideo \
aac aac_latm mp1 mp2 mp3 mp3float ac3 eac3 opus vorbis flac alac dca truehd mlp wmav1 wmav2 wmapro amrnb amrwb gsm_ms adpcm_ima_wav adpcm_ms \
pcm_s16le pcm_s16be pcm_s24le pcm_s32le pcm_f32le pcm_u8 pcm_mulaw pcm_alaw \
subrip srt ass ssa webvtt movtext text dvdsub dvbsub pgssub microdvd"
FF_ENCODERS="libopenh264 libkvazaar libvpx_vp8 libvpx_vp9 libsvtav1 mpeg4 ffv1 mjpeg png rawvideo \
aac libopus libmp3lame flac alac ac3 eac3 pcm_s16le pcm_s24le \
movtext subrip srt ass ssa webvtt dvdsub"
FF_DEMUXERS="mov matroska avi mpegts mpegps flv ogg asf mp3 aac ac3 eac3 wav flac srt ass webvtt h264 hevc m4v mxf gif \
mjpeg rawvideo dv truehd amr ivf obu mpegvideo concat ffmetadata"
FF_MUXERS="mp4 mov matroska webm tgp mpegts ipod ismv srt ass webvtt ffmetadata null"
FF_PARSERS="h264 hevc vp8 vp9 av1 aac aac_latm mpegaudio ac3 opus vorbis flac mpeg4video mpegvideo dca mjpeg png vc1 h263 dvdsub dvbsub"
FF_FILTERS="format aformat null anull copy split asplit \
scale crop pad fps setsar setdar setpts asetpts trim atrim setparams transpose hflip vflip rotate \
yadif bwdif idet fieldmatch decimate deblock nlmeans atadenoise bm3d unsharp cas deband gradfun \
hue colorspace zscale tonemap lutyuv \
overlay subtitles ass \
aresample pan volume asetnsamples apad loudnorm dynaudnorm \
psnr ssim scdet signalstats cropdetect blackdetect select thumbnail entropy"

if ! is_done ffmpeg; then
  log "FFmpeg $FFMPEG_VERSION (LGPL-2.1-or-later build)"
  s=$(fetch_tar ffmpeg "$FFMPEG_URL" "$FFMPEG_VERSION")
  b="$BLD/ffmpeg"; rm -rf "$b"; mkdir -p "$b"
  args=(
    --prefix="$PREFIX" --libdir="$PREFIX/lib"
    --enable-shared --disable-static --enable-pic
    --disable-gpl --disable-nonfree --disable-autodetect
    --disable-doc --disable-network --disable-avdevice
    --disable-everything
    --enable-avcodec --enable-avformat --enable-avfilter --enable-avutil --enable-swscale --enable-swresample
    --enable-protocol=file --enable-protocol=pipe
    --enable-bsfs
    --enable-zlib
    --enable-libdav1d --enable-libopenh264 --enable-libkvazaar --enable-libvpx --enable-libsvtav1
    --enable-libopus --enable-libmp3lame --enable-libzimg
    --enable-libass --enable-libfreetype --enable-libfribidi --enable-libharfbuzz
    --pkg-config-flags=--static
    --extra-cflags="$CFLAGS"
    --extra-ldflags="$LDFLAGS"
    --extra-libs="$CXX_RUNTIME -lm"
  )
  for d in $FF_DECODERS; do args+=(--enable-decoder="$d"); done
  for e in $FF_ENCODERS; do args+=(--enable-encoder="$e"); done
  for d in $FF_DEMUXERS; do args+=(--enable-demuxer="$d"); done
  for m in $FF_MUXERS;   do args+=(--enable-muxer="$m"); done
  for p in $FF_PARSERS;  do args+=(--enable-parser="$p"); done
  for f in $FF_FILTERS;  do args+=(--enable-filter="$f"); done
  if [ "$CROSS" = 1 ]; then
    args+=(--enable-cross-compile --target-os=android --arch="$FF_ARCH" --cpu="$FF_CPU"
           --cc="$CC" --cxx="$CXX" --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP"
           --sysroot="$SYSROOT" --pkg-config=pkg-config --disable-programs
           --disable-debug)
    [ "$FF_ARCH" = x86_64 ] && args+=(--x86asmexe=nasm)
  else
    # The host build also produces ffmpeg/ffprobe, used only by the test suite to
    # synthesise fixture clips and to independently verify engine output.
    args+=(--enable-avdevice --enable-indev=lavfi
           --enable-filter=testsrc2 --enable-filter=testsrc --enable-filter=sine --enable-filter=anullsrc
           --enable-filter=mandelbrot --enable-filter=noise --enable-filter=drawbox --enable-filter=geq
           --enable-filter=amerge --enable-filter=concat --enable-filter=aevalsrc --enable-filter=color
           --enable-filter=nullsrc --enable-filter=interlace --enable-filter=telecine --enable-filter=tinterlace
           --enable-encoder=libopenh264 --enable-encoder=pcm_s16le
           --enable-muxer=wav --enable-muxer=rawvideo --enable-muxer=framemd5 --enable-muxer=md5)
  fi
  (cd "$b" && "$s/configure" "${args[@]}" >"$b.log" 2>&1) || { tail -60 "$b.log"; tail -60 "$b/ffbuild/config.log"; exit 1; }
  make -C "$b" -j "$JOBS" >>"$b.log" 2>&1 || { tail -60 "$b.log"; exit 1; }
  make -C "$b" install >>"$b.log" 2>&1
  cp "$b/ffbuild/config.log" "$PREFIX/ffmpeg-config.log" 2>/dev/null || true
  grep -E '^(License|configuration):' -A0 "$b/config.h" >/dev/null 2>&1 || true
  sed -n 's/^#define FFMPEG_CONFIGURATION "\(.*\)"/\1/p' "$b/config.h" > "$PREFIX/ffmpeg-configuration.txt"
  sed -n 's/^#define FFMPEG_LICENSE "\(.*\)"/\1/p' "$b/config.h" > "$PREFIX/ffmpeg-license.txt"
  mark_done ffmpeg
fi

# nlohmann/json is header-only; vendor it next to the prebuilt headers.
if [ ! -f "$PREFIX/include/nlohmann/json.hpp" ]; then
  s=$(fetch_git nlohmann-json "$NLOHMANN_JSON_GIT" "$NLOHMANN_JSON_TAG")
  mkdir -p "$PREFIX/include/nlohmann"
  cp "$s/single_include/nlohmann/json.hpp" "$PREFIX/include/nlohmann/"
fi

# Copy the C++ runtime next to the FFmpeg libs so packaging is self-contained.
if [ "$CROSS" = 1 ]; then
  cp -f "$SYSROOT/usr/lib/$TRIPLE/libc++_shared.so" "$PREFIX/lib/"
fi

log "FFmpeg license: $(cat "$PREFIX/ffmpeg-license.txt")"
ls -la "$PREFIX/lib"/*.so* | awk '{print $5, $9}'
log "done -> $PREFIX"
