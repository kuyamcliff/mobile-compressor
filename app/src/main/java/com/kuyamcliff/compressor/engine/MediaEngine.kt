package com.kuyamcliff.compressor.engine

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.kuyamcliff.compressor.model.SourceInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One event from a native job. */
data class JobEvent(val type: String, val payload: String)

/**
 * Coroutine façade over [NativeEngine]. The native library (and FFmpeg) are
 * loaded lazily on first use, off the main thread, so app start-up never waits
 * for the media engine (PRD §136, §171).
 */
class MediaEngine(private val logLevel: () -> Int, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun ensureReady() = withContext(io) { NativeEngine.ensureLoaded(logLevel()) }

    private fun parseObject(text: String): JsonObject {
        val obj = json.parseToJsonElement(text).jsonObject
        EngineError.fromResult(obj)?.let { throw EngineFailure(it) }
        return obj
    }

    suspend fun buildInfo(): JsonObject = withContext(io) {
        ensureReady()
        parseObject(NativeEngine.nativeBuildInfo())
    }

    suspend fun probe(pfd: ParcelFileDescriptor, displayName: String): SourceInfo = withContext(io) {
        ensureReady()
        val text = NativeEngine.nativeProbe(pfd.fd, displayName)
        val obj = parseObject(text)
        json.decodeFromJsonElement(SourceInfo.serializer(), obj)
    }

    fun parseSourceInfo(text: String): SourceInfo = json.decodeFromString(SourceInfo.serializer(), text)
    fun encodeSourceInfo(info: SourceInfo): String = json.encodeToString(SourceInfo.serializer(), info)

    suspend fun thumbnail(pfd: ParcelFileDescriptor, timeUs: Long, maxDim: Int): Bitmap? = withContext(io) {
        ensureReady()
        val px = NativeEngine.nativeThumbnail(pfd.fd, timeUs, maxDim) ?: return@withContext null
        if (px.size < 3) return@withContext null
        val w = px[0]
        val h = px[1]
        if (w <= 0 || h <= 0 || px.size < w * h + 2) return@withContext null
        Bitmap.createBitmap(px, 2, w, w, h, Bitmap.Config.ARGB_8888)
    }

    suspend fun analyzeComplexity(pfd: ParcelFileDescriptor, windows: Int = 3, windowSeconds: Double = 4.0): ComplexityReport =
        withContext(io) {
            ensureReady()
            json.decodeFromJsonElement(ComplexityReport.serializer(), parseObject(NativeEngine.nativeAnalyzeComplexity(pfd.fd, windows, windowSeconds)))
        }

    suspend fun compareQuality(source: ParcelFileDescriptor, sample: ParcelFileDescriptor, planJson: String): QualityMetrics =
        withContext(io) {
            ensureReady()
            json.decodeFromJsonElement(QualityMetrics.serializer(), parseObject(NativeEngine.nativeCompareQuality(source.fd, sample.fd, planJson)))
        }

    suspend fun validateOutput(pfd: ParcelFileDescriptor, expectJson: String): ValidationResult = withContext(io) {
        ensureReady()
        json.decodeFromJsonElement(ValidationResult.serializer(), parseObject(NativeEngine.nativeValidateOutput(pfd.fd, expectJson)))
    }

    /** Returns null when the options are valid, otherwise a human-readable reason. */
    suspend fun validateEncoderOptions(encoder: String, options: Map<String, String>): String? = withContext(io) {
        ensureReady()
        val payload = JsonObject(options.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString()
        val obj = parseObject(NativeEngine.nativeValidateEncoderOptions(encoder, payload))
        obj["error"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
    }

    suspend fun describeEncoderOptions(encoder: String): String = withContext(io) {
        ensureReady()
        NativeEngine.nativeDescribeEncoderOptions(encoder)
    }

    /**
     * Runs a native job and emits its events. Cancelling the collector cancels
     * the native job; the job's resources are released before the flow ends.
     */
    fun run(planJson: String, input: ParcelFileDescriptor, output: ParcelFileDescriptor, onHandle: (NativeJobHandle) -> Unit = {}): Flow<JobEvent> =
        callbackFlow {
            ensureReady()
            val handle = try {
                NativeEngine.nativeCreateJob(planJson, input.fd, output.fd) { type, payload ->
                    trySend(JobEvent(type, payload))
                    if (type == EngineEvents.FAILED || type == EngineEvents.CANCELLED ||
                        type == EngineEvents.VALIDATION_COMPLETE || (type == EngineEvents.ENCODE_COMPLETE && !planJson.contains("\"validate\""))
                    ) {
                        channel.close()
                    }
                }
            } catch (e: NativeEngineException) {
                throw EngineFailure(EngineError.parse(e.message ?: ""))
            }
            val h = NativeJobHandle(handle)
            onHandle(h)
            NativeEngine.nativeStartJob(handle)
            awaitClose {
                // Joins the worker thread off the main thread.
                NativeEngine.nativeCancelJob(handle)
                NativeEngine.nativeDestroyJob(handle)
                h.destroyed = true
            }
        }.flowOn(io)
}

/** Control surface for a running native job. */
class NativeJobHandle(val handle: Long) {
    @Volatile var destroyed = false
    fun pause(): Boolean = !destroyed && NativeEngine.nativePauseJob(handle)
    fun resume(): Boolean = !destroyed && NativeEngine.nativeResumeJob(handle)
    fun cancel(): Boolean = !destroyed && NativeEngine.nativeCancelJob(handle)
    fun setThrottle(percent: Int) { if (!destroyed) NativeEngine.nativeSetThrottle(handle, percent) }
    fun state(): NativeJobState = if (destroyed) NativeJobState.UNKNOWN else NativeJobState.fromCode(NativeEngine.nativeJobState(handle))
}
