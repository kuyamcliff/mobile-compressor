package com.kuyamcliff.compressor.engine

/**
 * JNI surface of the native media engine (libvcengine.so, see native/engine).
 *
 * Every call here is coarse-grained: a job is created once with an immutable
 * JSON execution plan and runs entirely in native code; progress and results
 * come back as [NativeJobListener] events. Blocking calls (probe, metrics, …)
 * must be invoked off the main thread — [MediaEngine] wraps them in coroutines.
 *
 * File descriptors passed in are duplicated by the engine; callers keep (and
 * close) their own ParcelFileDescriptors.
 */
internal object NativeEngine {
    @Volatile private var loaded = false

    /** Loads FFmpeg + the engine once per process. Safe to call repeatedly. */
    @Synchronized
    fun ensureLoaded(logLevel: Int) {
        if (!loaded) {
            System.loadLibrary("vcengine")
            loaded = true
        }
        nativeInit(logLevel)
    }

    @JvmStatic external fun nativeInit(logLevel: Int)
    @JvmStatic external fun nativeBuildInfo(): String
    @JvmStatic external fun nativeProbe(fd: Int, displayName: String): String
    @JvmStatic external fun nativeThumbnail(fd: Int, timeUs: Long, maxDim: Int): IntArray?
    @JvmStatic external fun nativeAnalyzeComplexity(fd: Int, windows: Int, windowSeconds: Double): String
    @JvmStatic external fun nativeCompareQuality(sourceFd: Int, sampleFd: Int, planJson: String): String
    @JvmStatic external fun nativeValidateOutput(fd: Int, expectJson: String): String
    @JvmStatic external fun nativeValidateEncoderOptions(encoder: String, optionsJson: String): String
    @JvmStatic external fun nativeDescribeEncoderOptions(encoder: String): String
    @JvmStatic external fun nativeContainerSupports(muxer: String, codec: String): Int

    @JvmStatic external fun nativeCreateJob(planJson: String, inputFd: Int, outputFd: Int, listener: NativeJobListener): Long
    @JvmStatic external fun nativeStartJob(handle: Long): Boolean
    @JvmStatic external fun nativePauseJob(handle: Long): Boolean
    @JvmStatic external fun nativeResumeJob(handle: Long): Boolean
    @JvmStatic external fun nativeCancelJob(handle: Long): Boolean
    @JvmStatic external fun nativeSetThrottle(handle: Long, percent: Int)
    @JvmStatic external fun nativeJobState(handle: Long): Int
    @JvmStatic external fun nativeJobStats(handle: Long): String
    @JvmStatic external fun nativeDestroyJob(handle: Long)
}

/** Receives coarse job events from the native worker thread. */
fun interface NativeJobListener {
    fun onEvent(type: String, payloadJson: String)
}

/** Thrown by [NativeEngine.nativeCreateJob]; message is an EngineError JSON object. */
class NativeEngineException(message: String) : Exception(message)
