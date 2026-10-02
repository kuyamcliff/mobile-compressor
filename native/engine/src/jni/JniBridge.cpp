// JNI bridge for com.kuyamcliff.compressor.engine.NativeEngine.
//
// Design rules (see ARCHITECTURE.md):
//  * Kotlin never calls into native per frame. A job is created once with an
//    immutable JSON execution plan plus two file descriptors, then runs on its
//    own native worker thread.
//  * Native -> Kotlin traffic is coarse events (JSON payloads), at most every
//    `progressIntervalMs`, delivered through NativeJobListener.onEvent on the
//    worker thread (attached to the JVM for its lifetime).
//  * Ownership: the job duplicates the descriptors it receives; Kotlin may close
//    its ParcelFileDescriptors right after createJob returns. The listener is
//    held as a JNI global reference released when the job is destroyed.
//  * Errors crossing the boundary are JSON objects with a stable category code
//    (EngineError::toJson), never bare integers.
#include <jni.h>
#include <pthread.h>

#include <memory>
#include <mutex>
#include <string>

#include "analysis/BuildInfo.h"
#include "analysis/Complexity.h"
#include "analysis/QualityMetrics.h"
#include "analysis/Validator.h"
#include "core/Errors.h"
#include "core/Log.h"
#include "job/JobManager.h"
#include "pipeline/EncoderSetup.h"
#include "probe/Probe.h"
#include "probe/Thumbnail.h"

using namespace vc;

namespace {
constexpr const char* kClass = "com/kuyamcliff/compressor/engine/NativeEngine";
constexpr const char* TAG = "JniBridge";

JavaVM* gVm = nullptr;
pthread_key_t gDetachKey;
JobManager* gJobs = nullptr;
std::once_flag gInitOnce;

void detachThread(void*) {
  if (gVm) gVm->DetachCurrentThread();
}

// Returns a JNIEnv for the current thread, attaching it (and arranging the
// detach at thread exit) when it is a native worker thread.
JNIEnv* envForThread() {
  JNIEnv* env = nullptr;
  if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
  JavaVMAttachArgs args{JNI_VERSION_1_6, "vc-engine-job", nullptr};
  if (gVm->AttachCurrentThread(&env, &args) != JNI_OK) return nullptr;
  pthread_setspecific(gDetachKey, env);
  return env;
}

std::string toString(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out = c ? c : "";
  if (c) env->ReleaseStringUTFChars(s, c);
  return out;
}

jstring toJString(JNIEnv* env, const std::string& s) {
  // NewStringUTF needs modified UTF-8; dumpJson already replaced invalid bytes,
  // and JSON escapes control characters, so plain JSON text is safe here.
  return env->NewStringUTF(s.c_str());
}

std::string errorJson(const EngineError& e) { return dumpJson(Json{{"error", e.toJson()}}); }

template <typename F>
jstring guarded(JNIEnv* env, F&& f) {
  try {
    return toJString(env, f());
  } catch (const EngineException& e) {
    return toJString(env, errorJson(e.error()));
  } catch (const std::bad_alloc&) {
    EngineError e;
    e.category = ErrorCategory::MemoryFailure;
    e.message = "Out of memory.";
    return toJString(env, errorJson(e));
  } catch (const std::exception& ex) {
    EngineError e;
    e.category = ErrorCategory::Internal;
    e.message = "Unexpected engine error.";
    e.detail = ex.what();
    return toJString(env, errorJson(e));
  }
}

void throwEngineException(JNIEnv* env, const std::string& json) {
  jclass cls = env->FindClass("com/kuyamcliff/compressor/engine/NativeEngineException");
  if (cls) env->ThrowNew(cls, json.c_str());
}

// Shared holder for the Kotlin listener (global ref) used by a job's sink.
struct ListenerRef {
  jobject listener = nullptr;
  jmethodID onEvent = nullptr;
  ~ListenerRef() {
    if (listener) {
      if (JNIEnv* env = envForThread()) env->DeleteGlobalRef(listener);
    }
  }
};

// ---------------------------------------------------------------------------
void nativeInit(JNIEnv*, jclass, jint logLevel) {
  std::call_once(gInitOnce, [] {
    Log::installFfmpegBridge();
    gJobs = new JobManager();  // lives for the process; jobs are destroyed individually
  });
  Log::setLevel(static_cast<LogLevel>(std::clamp(static_cast<int>(logLevel), 0, 4)));
}

jstring nativeBuildInfo(JNIEnv* env, jclass) {
  return guarded(env, [] { return dumpJson(buildInfo()); });
}

jstring nativeProbe(JNIEnv* env, jclass, jint fd, jstring name) {
  std::string n = toString(env, name);
  return guarded(env, [&] { return dumpJson(probeSource(fd, n, nullptr)); });
}

jintArray nativeThumbnail(JNIEnv* env, jclass, jint fd, jlong timeUs, jint maxDim) {
  try {
    Thumbnail t = extractThumbnail(fd, timeUs, maxDim, nullptr);
    jsize n = static_cast<jsize>(t.width) * t.height + 2;
    jintArray arr = env->NewIntArray(n);
    if (!arr) return nullptr;
    std::vector<jint> px(n);
    px[0] = t.width;
    px[1] = t.height;
    for (size_t i = 0, p = 2; i + 3 < t.rgba.size(); i += 4, ++p) {
      // RGBA bytes -> ARGB_8888 ints for Bitmap.createBitmap(int[]).
      uint32_t r = t.rgba[i], g = t.rgba[i + 1], b = t.rgba[i + 2];
      px[p] = static_cast<jint>(0xFF000000u | (r << 16) | (g << 8) | b);
    }
    env->SetIntArrayRegion(arr, 0, n, px.data());
    return arr;
  } catch (const std::exception& e) {
    VC_LOGW(TAG, "thumbnail failed: %s", e.what());
    return nullptr;
  }
}

jstring nativeAnalyzeComplexity(JNIEnv* env, jclass, jint fd, jint windows, jdouble windowSeconds) {
  return guarded(env, [&] { return dumpJson(analyzeComplexity(fd, windows, windowSeconds, nullptr)); });
}

jstring nativeCompareQuality(JNIEnv* env, jclass, jint sourceFd, jint sampleFd, jstring planJson) {
  std::string p = toString(env, planJson);
  return guarded(env, [&] { return dumpJson(compareQuality(sourceFd, sampleFd, parsePlan(Json::parse(p)), nullptr)); });
}

jstring nativeValidateOutput(JNIEnv* env, jclass, jint fd, jstring expectJson) {
  std::string e = toString(env, expectJson);
  return guarded(env, [&] { return dumpJson(validateOutput(fd, Json::parse(e), nullptr)); });
}

jstring nativeValidateEncoderOptions(JNIEnv* env, jclass, jstring encoder, jstring optionsJson) {
  std::string enc = toString(env, encoder);
  std::string o = toString(env, optionsJson);
  return guarded(env, [&] {
    std::vector<std::pair<std::string, std::string>> opts;
    Json j = Json::parse(o);
    for (auto it = j.begin(); it != j.end(); ++it) opts.emplace_back(it.key(), it.value().get<std::string>());
    return dumpJson(Json{{"error", validateEncoderOptions(enc, opts)}});
  });
}

jstring nativeDescribeEncoderOptions(JNIEnv* env, jclass, jstring encoder) {
  std::string enc = toString(env, encoder);
  return guarded(env, [&] { return describeEncoderOptions(enc); });
}

jint nativeContainerSupports(JNIEnv* env, jclass, jstring muxer, jstring codec) {
  return containerSupportsCodec(toString(env, muxer), toString(env, codec));
}

jlong nativeCreateJob(JNIEnv* env, jclass, jstring planJson, jint inputFd, jint outputFd, jobject listener) {
  if (!gJobs) {
    throwEngineException(env, R"({"category":19,"message":"Engine not initialised"})");
    return 0;
  }
  auto ref = std::make_shared<ListenerRef>();
  if (listener) {
    ref->listener = env->NewGlobalRef(listener);
    jclass lc = env->GetObjectClass(listener);
    ref->onEvent = env->GetMethodID(lc, "onEvent", "(Ljava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(lc);
  }
  EventSink sink = [ref](const char* type, const Json& payload) {
    if (!ref->listener || !ref->onEvent) return;
    JNIEnv* e = envForThread();
    if (!e) return;
    jstring t = e->NewStringUTF(type);
    jstring p = e->NewStringUTF(dumpJson(payload).c_str());
    e->CallVoidMethod(ref->listener, ref->onEvent, t, p);
    if (e->ExceptionCheck()) {
      e->ExceptionDescribe();
      e->ExceptionClear();  // a listener bug must not kill the encode
    }
    e->DeleteLocalRef(t);
    e->DeleteLocalRef(p);
  };
  try {
    return static_cast<jlong>(gJobs->create(toString(env, planJson), inputFd, outputFd, sink));
  } catch (const EngineException& e) {
    throwEngineException(env, dumpJson(e.error().toJson()));
  } catch (const std::exception& ex) {
    EngineError e;
    e.category = ErrorCategory::Internal;
    e.message = "Could not create the job.";
    e.detail = ex.what();
    throwEngineException(env, dumpJson(e.toJson()));
  }
  return 0;
}

template <typename F>
jboolean withJob(jlong h, F&& f) {
  if (!gJobs) return JNI_FALSE;
  auto job = gJobs->get(h);
  return job && f(*job) ? JNI_TRUE : JNI_FALSE;
}

jboolean nativeStartJob(JNIEnv*, jclass, jlong h) { return withJob(h, [](Job& j) { return j.start(); }); }
jboolean nativePauseJob(JNIEnv*, jclass, jlong h) { return withJob(h, [](Job& j) { return j.pause(); }); }
jboolean nativeResumeJob(JNIEnv*, jclass, jlong h) { return withJob(h, [](Job& j) { return j.resume(); }); }
jboolean nativeCancelJob(JNIEnv*, jclass, jlong h) { return withJob(h, [](Job& j) { return j.cancel(); }); }
void nativeSetThrottle(JNIEnv*, jclass, jlong h, jint pct) {
  withJob(h, [pct](Job& j) {
    j.setThrottle(pct);
    return true;
  });
}
jint nativeJobState(JNIEnv*, jclass, jlong h) {
  if (!gJobs) return -1;
  auto job = gJobs->get(h);
  return job ? static_cast<jint>(job->state()) : -1;
}
jstring nativeJobStats(JNIEnv* env, jclass, jlong h) {
  auto job = gJobs ? gJobs->get(h) : nullptr;
  return toJString(env, job ? dumpJson(job->lastStats()) : "{}");
}
void nativeDestroyJob(JNIEnv*, jclass, jlong h) {
  // Joins the worker; Kotlin calls this off the main thread.
  if (gJobs) gJobs->destroy(h);
}

const JNINativeMethod kMethods[] = {
    {"nativeInit", "(I)V", reinterpret_cast<void*>(nativeInit)},
    {"nativeBuildInfo", "()Ljava/lang/String;", reinterpret_cast<void*>(nativeBuildInfo)},
    {"nativeProbe", "(ILjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(nativeProbe)},
    {"nativeThumbnail", "(IJI)[I", reinterpret_cast<void*>(nativeThumbnail)},
    {"nativeAnalyzeComplexity", "(IID)Ljava/lang/String;", reinterpret_cast<void*>(nativeAnalyzeComplexity)},
    {"nativeCompareQuality", "(IILjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(nativeCompareQuality)},
    {"nativeValidateOutput", "(ILjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(nativeValidateOutput)},
    {"nativeValidateEncoderOptions", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeValidateEncoderOptions)},
    {"nativeDescribeEncoderOptions", "(Ljava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeDescribeEncoderOptions)},
    {"nativeContainerSupports", "(Ljava/lang/String;Ljava/lang/String;)I", reinterpret_cast<void*>(nativeContainerSupports)},
    {"nativeCreateJob",
     "(Ljava/lang/String;IILcom/kuyamcliff/compressor/engine/NativeJobListener;)J",
     reinterpret_cast<void*>(nativeCreateJob)},
    {"nativeStartJob", "(J)Z", reinterpret_cast<void*>(nativeStartJob)},
    {"nativePauseJob", "(J)Z", reinterpret_cast<void*>(nativePauseJob)},
    {"nativeResumeJob", "(J)Z", reinterpret_cast<void*>(nativeResumeJob)},
    {"nativeCancelJob", "(J)Z", reinterpret_cast<void*>(nativeCancelJob)},
    {"nativeSetThrottle", "(JI)V", reinterpret_cast<void*>(nativeSetThrottle)},
    {"nativeJobState", "(J)I", reinterpret_cast<void*>(nativeJobState)},
    {"nativeJobStats", "(J)Ljava/lang/String;", reinterpret_cast<void*>(nativeJobStats)},
    {"nativeDestroyJob", "(J)V", reinterpret_cast<void*>(nativeDestroyJob)},
};
}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  gVm = vm;
  pthread_key_create(&gDetachKey, detachThread);
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass cls = env->FindClass(kClass);
  if (!cls) return JNI_ERR;
  if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
  env->DeleteLocalRef(cls);
  return JNI_VERSION_1_6;
}
