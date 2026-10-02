cmake_minimum_required(VERSION 3.22)
project(vcengine LANGUAGES C CXX)

# Native media engine. Built two ways:
#  * Android (via Gradle externalNativeBuild): libvcengine.so + JNI bridge +
#    MediaCodec/EGL hardware pipeline.
#  * Host (Linux): static engine library, the engine CLI and the native test
#    suite, linked against the host FFmpeg build from build_deps.sh.

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
  set(VC_TARGET "android-${ANDROID_ABI}")
else()
  set(VC_TARGET "host")
endif()
set(VC_PREBUILT "${CMAKE_CURRENT_LIST_DIR}/../prebuilt/${VC_TARGET}" CACHE PATH "FFmpeg prebuilt root")
if(NOT EXISTS "${VC_PREBUILT}/include/libavcodec/avcodec.h")
  message(FATAL_ERROR "FFmpeg prebuilt not found at ${VC_PREBUILT}. Run native/third_party/build_deps.sh ${VC_TARGET}")
endif()

set(FF_LIBS avformat avcodec avfilter swscale swresample avutil)
foreach(lib ${FF_LIBS})
  add_library(ff_${lib} SHARED IMPORTED)
  set_target_properties(ff_${lib} PROPERTIES IMPORTED_LOCATION "${VC_PREBUILT}/lib/lib${lib}.so"
                                              IMPORTED_NO_SONAME OFF)
  list(APPEND FF_TARGETS ff_${lib})
endforeach()

set(ENGINE_SOURCES
  src/core/Log.cpp
  src/core/Errors.cpp
  src/core/FdIo.cpp
  src/core/Util.cpp
  src/probe/Probe.cpp
  src/probe/Thumbnail.cpp
  src/plan/Plan.cpp
  src/pipeline/FilterBuilder.cpp
  src/pipeline/FilterGraph.cpp
  src/pipeline/EncoderSetup.cpp
  src/pipeline/VideoEncoder.cpp
  src/pipeline/Muxer.cpp
  src/pipeline/Progress.cpp
  src/pipeline/Transcoder.cpp
  src/pipeline/DtsGenerator.cpp
  src/analysis/Validator.cpp
  src/analysis/QualityMetrics.cpp
  src/analysis/Complexity.cpp
  src/analysis/BuildInfo.cpp
  src/job/JobManager.cpp
)

if(ANDROID)
  list(APPEND ENGINE_SOURCES
    src/android/MediaCodecUtil.cpp
    src/android/MediaCodecBufferEncoder.cpp
    src/android/GlRenderer.cpp
    src/android/HwVideoHandler.cpp
    src/jni/JniBridge.cpp)
else()
  list(APPEND ENGINE_SOURCES src/pipeline/HwStubs.cpp)
endif()

set(VC_WARNINGS -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers)

if(ANDROID)
  add_library(vcengine SHARED ${ENGINE_SOURCES})
  target_link_libraries(vcengine PRIVATE ${FF_TARGETS} mediandk android log EGL GLESv2 nativewindow)
  target_link_options(vcengine PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL")
  target_compile_options(vcengine PRIVATE ${VC_WARNINGS} -fvisibility=hidden -ffunction-sections -fdata-sections
                         $<$<CONFIG:Release>:-O2> $<$<CONFIG:RelWithDebInfo>:-O2>)
  if(VC_VERBOSE_LOGS)
    target_compile_definitions(vcengine PRIVATE VC_VERBOSE_LOGS=1)
  endif()
else()
  add_library(vcengine STATIC ${ENGINE_SOURCES})
  target_link_libraries(vcengine PUBLIC ${FF_TARGETS} pthread)
  target_compile_options(vcengine PRIVATE ${VC_WARNINGS})
endif()
target_include_directories(vcengine PUBLIC src "${VC_PREBUILT}/include")

if(NOT ANDROID)
  add_executable(vcengine_cli tools/engine_cli.cpp)
  target_link_libraries(vcengine_cli PRIVATE vcengine)
  target_link_options(vcengine_cli PRIVATE "-Wl,--disable-new-dtags,-rpath,${VC_PREBUILT}/lib")

  enable_testing()
  add_executable(vcengine_tests
    tests/TestMain.cpp
    tests/UnitTests.cpp
    tests/MediaTests.cpp)
  target_link_libraries(vcengine_tests PRIVATE vcengine)
  target_compile_definitions(vcengine_tests PRIVATE
    VC_FFMPEG_BIN="${VC_PREBUILT}/bin/ffmpeg" VC_FFPROBE_BIN="${VC_PREBUILT}/bin/ffprobe")
  target_link_options(vcengine_tests PRIVATE "-Wl,--disable-new-dtags,-rpath,${VC_PREBUILT}/lib")
  add_test(NAME vcengine_tests COMMAND vcengine_tests)
endif()
