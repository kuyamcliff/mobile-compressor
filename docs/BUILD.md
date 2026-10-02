# Building

## Requirements

| Tool | Version used |
|---|---|
| JDK | 17+ |
| Android SDK | platform 36, build-tools 36.0.0 |
| Android NDK | 28.2.13676358 (pinned in `app/build.gradle.kts` and `versions.env`) |
| CMake | 3.31.6 (SDK CMake) |
| Gradle / AGP / Kotlin | wrapper / 8.13.2 / 2.3.21 |
| Host packages for the deps build | `git curl make nasm meson ninja pkg-config python3 autoconf automake libtool` |

Set `sdk.dir` in `local.properties`, or set `ANDROID_HOME`.

## 1. Native dependencies (FFmpeg and codecs)

```bash
native/third_party/build_deps.sh android-arm64-v8a   # what the APK needs
native/third_party/build_deps.sh host                 # optional: native tests and benchmarks on Linux
```

- Versions are pinned in `native/third_party/versions.env`, and everything is built from source.
- Output goes to `native/prebuilt/<target>/` (gitignored); work files go to `build-deps/`.
- Each component is skipped once it is built. Delete its `.done-*` marker to rebuild it.
- FFmpeg patches in `native/third_party/patches/` are applied automatically.
- All libraries are linked with `-Wl,-z,max-page-size=16384`, so they work on 16 KB-page devices.

Gradle runs `verifyFfmpegPrebuilt` before building. If the prebuilt is missing, the build stops with the command to run.
`./gradlew buildNativeDeps` invokes the script for you.

## 2. APKs

```bash
./gradlew :app:assembleDebug     # app-debug.apk   (applicationId suffix .debug, verbose native logs)
./gradlew :app:assembleRelease   # app-release.apk (R8 minify + resource shrinking)
```

- The native engine (`native/engine/CMakeLists.txt`) is built by the Android Gradle plugin's CMake integration.
- FFmpeg's `.so` files are staged into the APK by the `stageFfmpegLibs` task.

### Release signing

Create `keystore.properties` in the repository root. It is gitignored, as are `*.jks` files:

```
storeFile=release.jks
storePassword=…
keyAlias=compressor
keyPassword=…
```

Generate a key with:

```bash
keytool -genkeypair -keystore release.jks -alias compressor -keyalg RSA -keysize 4096 -validity 10000
```

Without `keystore.properties` the release APK is unsigned, and `verify_apk.sh` reports that.
Keep the keystore safe: Android only installs updates that are signed with the same key.

## 3. Native engine on the host (tests, CLI)

```bash
cmake -G Ninja -S native/engine -B /tmp/vcbuild -DCMAKE_BUILD_TYPE=RelWithDebInfo
ninja -C /tmp/vcbuild
export LD_LIBRARY_PATH=$PWD/native/prebuilt/host/lib
/tmp/vcbuild/vcengine_tests
/tmp/vcbuild/vcengine_cli info
```

## 4. Release validation

```bash
scripts/verify_apk.sh [path/to/app-release.apk]
```

The script checks:
- the signature, and that the release key is not the debug key
- that there is no INTERNET permission, the app is not debuggable, and minSdk/targetSdk are right
- that every native library is present
- that FFmpeg is the LGPL build
- 16 KB zip and ELF alignment
- the `JNI_OnLoad` export
- that R8 kept the JNI classes
- that the license texts are bundled
