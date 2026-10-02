package com.kuyamcliff.compressor.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The resolved, immutable plan the native engine executes (mirror of
 * native/engine/src/plan/Plan.h). Produced by the Planner from
 * CompressionConfig + SourceInfo + DeviceCapabilities; frozen once a job is
 * queued.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExecutionPlan(
    val jobId: String = "",
    val container: PlanContainer,
    val video: PlanVideo? = null,
    val audio: List<PlanAudio> = emptyList(),
    val subtitles: List<PlanSubtitle> = emptyList(),
    val chapterMode: String = "preserve",
    val chapters: List<PlanChapter> = emptyList(),
    val metadataMode: String = "preserve",
    val metadata: Map<String, String> = emptyMap(),
    val segment: PlanSegment? = null,
    @EncodeDefault val progressIntervalMs: Int = 250,
    val copyAttachments: Boolean = true,
    val validate: PlanValidation? = null,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    /** Stable key for caches (everything but the job id). */
    fun cacheKey(): String = copy(jobId = "").toJson().hashCode().toUInt().toString(16)

    companion object {
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true; explicitNulls = false }
        fun fromJson(s: String): ExecutionPlan = json.decodeFromString(serializer(), s)
    }
}

@Serializable
data class PlanContainer(val format: String, val fastStart: Boolean = true, val fragmented: Boolean = false)

@Serializable
data class PlanCrop(val top: Int = 0, val bottom: Int = 0, val left: Int = 0, val right: Int = 0)

@Serializable
data class PlanRateControl(
    val mode: String,
    val quality: Double = 0.0,
    val bitrateKbps: Int = 0,
    val maxBitrateKbps: Int = 0,
    val minBitrateKbps: Int = 0,
    val bufferKbits: Int = 0,
    val twoPass: Boolean = false,
)

@Serializable
data class PlanDenoise(val algo: String, val strength: String)

@Serializable
data class PlanSharpen(val algo: String, val strength: String)

@Serializable
data class PlanFilters(
    val deinterlace: String = "",
    val deinterlaceAuto: Boolean = false,
    val detelecine: Boolean = false,
    val deblock: String = "",
    val denoise: PlanDenoise? = null,
    val sharpen: PlanSharpen? = null,
    val deband: Boolean = false,
    val grayscale: Boolean = false,
    val rotate: Int = 0,
    val hflip: Boolean = false,
    val vflip: Boolean = false,
)

@Serializable
data class PlanColor(val primaries: String = "", val transfer: String = "", val matrix: String = "", val range: String = "")

@Serializable
data class PlanBurn(
    val sourceStreamIndex: Int = -1,
    val externalPath: String = "",
    val bitmap: Boolean = false,
    val fontsDir: String = "",
    val fallbackFont: String = "",
)

@Serializable
data class PlanVideo(
    val sourceStreamIndex: Int,
    val mode: String,               // transcode | copy
    val pipeline: String = "software",
    val codec: String = "",
    val encoder: String = "",
    val mime: String = "",
    val hwDecoder: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val crop: PlanCrop? = null,
    val scaler: String = "bicubic",
    val fpsMode: String = "source",
    val fpsNum: Int = 0,
    val fpsDen: Int = 1,
    val pixFmt: String = "yuv420p",
    val bitDepth: Int = 8,
    val rateControl: PlanRateControl? = null,
    val keyIntSeconds: Double = 0.0,
    val bFrames: Int = -1,
    val refFrames: Int = -1,
    val profile: String = "",
    val level: String = "",
    val preset: String = "",
    val tune: String = "",
    val options: Map<String, String> = emptyMap(),
    val threads: Int = 0,
    val filters: PlanFilters = PlanFilters(),
    val autorotate: Boolean = true,
    val hdrMode: String = "preserve",
    val tonemap: String = "hable",
    val color: PlanColor? = null,
    val burn: PlanBurn? = null,
    val hwColorFormat: Int = 21,
    val hwBitrateMode: Int = 1,
    val hwQpMin: Int = -1,
    val hwQpMax: Int = -1,
    val hwProfile: Int = -1,
    val hwLevel: Int = -1,
    val hwQuality: Int = -1,
)

@Serializable
data class PlanAudio(
    val sourceStreamIndex: Int,
    val mode: String,               // encode | copy
    val codec: String = "",
    val encoder: String = "",
    val bitrateKbps: Int = 0,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val language: String = "",
    val title: String = "",
    val default: Boolean = false,
    val volumeDb: Double = 0.0,
)

@Serializable
data class PlanSubtitle(
    val sourceStreamIndex: Int = -1,
    val externalPath: String = "",
    val mode: String = "copy",      // copy | convert
    val codec: String = "",
    val language: String = "",
    val title: String = "",
    val default: Boolean = false,
    val forced: Boolean = false,
)

@Serializable
data class PlanChapter(val title: String, val startUs: Long, val endUs: Long)

@Serializable
data class PlanSegment(val startUs: Long, val durationUs: Long)

@Serializable
data class PlanValidation(
    val container: String? = null,
    val videoCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationUs: Long? = null,
    val audioTracks: Int? = null,
    val subtitleTracks: Int? = null,
    val expectVideo: Boolean = true,
)
