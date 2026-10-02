package com.kuyamcliff.compressor.preview

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kuyamcliff.compressor.data.repo.AppJson
import com.kuyamcliff.compressor.data.storage.AppFiles
import com.kuyamcliff.compressor.data.storage.SourceAccess
import com.kuyamcliff.compressor.domain.ResolvedPlan
import com.kuyamcliff.compressor.engine.EngineError
import com.kuyamcliff.compressor.engine.EngineEvents
import com.kuyamcliff.compressor.engine.EngineFailure
import com.kuyamcliff.compressor.engine.MediaEngine
import com.kuyamcliff.compressor.engine.ProgressStats
import com.kuyamcliff.compressor.engine.QualityMetrics
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.PlanSegment
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.model.SourceFile
import com.kuyamcliff.compressor.queue.JobFactory
import com.kuyamcliff.compressor.queue.forPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

enum class PreviewPosition { CURRENT, BEGINNING, MIDDLE, END, CUSTOM }

@Serializable
data class PreviewResult(
    val path: String,
    val key: String,
    val sampleBytes: Long,
    val sourceSegmentBytes: Long,
    val segmentStartUs: Long,
    val segmentDurationUs: Long,
    val videoBps: Long,
    val encodeFps: Double,
    val speed: Double,
    val pipeline: String,
    val encoder: String,
    val wallTimeUs: Long,
    val outputPixelsPerSecond: Double,
    val metrics: QualityMetrics? = null,
)

data class ComparisonPoint(val label: String, val config: CompressionConfig, val result: PreviewResult, val projectedBytes: Long)

/**
 * Short test encodes with the exact job settings (PRD §41), reused while the
 * relevant settings are unchanged (cache key = plan hash, §180), plus the
 * sample-based tools built on them: smart target-size calibration (§14) and
 * multi-setting comparisons (§105, §150, §151).
 */
class PreviewManager(
    private val engine: MediaEngine,
    private val access: SourceAccess,
    private val files: AppFiles,
    private val factory: JobFactory,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun segmentFor(position: PreviewPosition, durationUs: Long, seconds: Int, currentUs: Long, customStartUs: Long): PlanSegment {
        val len = minOf(seconds * 1_000_000L, durationUs.coerceAtLeast(1_000_000L))
        val start = when (position) {
            PreviewPosition.BEGINNING -> 0L
            PreviewPosition.MIDDLE -> (durationUs - len) / 2
            PreviewPosition.END -> durationUs - len
            PreviewPosition.CURRENT -> currentUs
            PreviewPosition.CUSTOM -> customStartUs
        }.coerceIn(0L, (durationUs - len).coerceAtLeast(0L))
        return PlanSegment(start, len)
    }

    suspend fun encode(
        source: SourceFile,
        config: CompressionConfig,
        segment: PlanSegment,
        withMetrics: Boolean,
        calibration: Double = 1.0,
        onProgress: (Double) -> Unit = {},
    ): PreviewResult = withContext(Dispatchers.IO) {
        val ctx = factory.context(source, config, segment = segment, calibration = calibration)
        val resolved: ResolvedPlan = factory.plan(config, ctx)
        val plan = resolved.plan.forPreview(segment)
        val key = plan.cacheKey() + "_" + source.uri.hashCode().toUInt().toString(16)
        val ext = config.container.extension
        val outFile = File(files.previews, "$key.$ext")
        val meta = File(files.previews, "$key.json")
        if (outFile.exists() && meta.exists()) {
            runCatching { json.decodeFromString(PreviewResult.serializer(), meta.readText()) }.getOrNull()?.let { cached ->
                if (!withMetrics || cached.metrics != null) return@withContext cached
                val withM = cached.copy(metrics = runCatching { metrics(source, outFile, plan.toJson()) }.getOrNull())
                meta.writeText(json.encodeToString(PreviewResult.serializer(), withM))
                return@withContext withM
            }
        }
        var stats: ProgressStats? = null
        var failure: EngineError? = null
        access.openRead(Uri.parse(source.uri)).use { inPfd ->
            ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { outPfd ->
                engine.run(plan.toJson(), inPfd, outPfd).collect { ev ->
                    when (ev.type) {
                        EngineEvents.PROGRESS -> json.decodeFromString(ProgressStats.serializer(), ev.payload).progress.let { if (it >= 0) onProgress(it) }
                        EngineEvents.ENCODE_COMPLETE -> stats = json.decodeFromString(ProgressStats.serializer(), ev.payload)
                        EngineEvents.FAILED -> failure = EngineError.parse(ev.payload)
                        EngineEvents.CANCELLED -> failure = EngineError(message = "Cancelled")
                    }
                }
            }
        }
        failure?.let { outFile.delete(); throw EngineFailure(it) }
        val s = stats ?: run { outFile.delete(); throw EngineFailure(EngineError(message = "Preview produced no output.")) }
        val bytes = outFile.length()
        val durUs = s.mediaDurationUs.takeIf { it > 0 } ?: segment.durationUs
        val audioBps = plan.audio.sumOf { if (it.mode == "copy") 128_000L else it.bitrateKbps * 1000L }
        val videoBps = ((bytes * 8.0 / (durUs / 1e6)) - audioBps).toLong().coerceAtLeast(1)
        val outSize = resolved.summary.outputSize
        val pps = (outSize?.pixels ?: 0L) * resolved.summary.outputFps.coerceAtLeast(1.0)
        var result = PreviewResult(
            path = outFile.absolutePath, key = key, sampleBytes = bytes, sourceSegmentBytes = s.sourceSegmentBytes,
            segmentStartUs = segment.startUs, segmentDurationUs = durUs, videoBps = videoBps, encodeFps = s.averageFps,
            speed = s.speed, pipeline = s.pipeline, encoder = s.encoder, wallTimeUs = s.elapsedUs, outputPixelsPerSecond = pps.toDouble(),
        )
        if (withMetrics && plan.video != null) {
            result = result.copy(metrics = runCatching { metrics(source, outFile, plan.toJson()) }.getOrNull())
        }
        meta.writeText(json.encodeToString(PreviewResult.serializer(), result))
        result
    }

    private suspend fun metrics(source: SourceFile, sample: File, planJson: String): QualityMetrics =
        access.openRead(Uri.parse(source.uri)).use { src ->
            ParcelFileDescriptor.open(sample, ParcelFileDescriptor.MODE_READ_ONLY).use { s -> engine.compareQuality(src, s, planJson) }
        }

    /**
     * Smart target size (PRD §14): encode short samples spread over the file at
     * the computed bitrate, measure what the encoder actually produced, and
     * correct the bitrate. Bounded to [maxIterations]; returns the calibration
     * factor (actual / requested) for the planner.
     */
    suspend fun calibrateTarget(
        source: SourceFile,
        config: CompressionConfig,
        maxIterations: Int,
        onStep: (iteration: Int, ratio: Double) -> Unit = { _, _ -> },
    ): Double {
        require(config.video.rateControl == RateControlMode.TARGET_SIZE)
        val dur = source.info.durationUs
        val seconds = when {
            dur > 600_000_000L -> 8
            dur > 60_000_000L -> 5
            else -> 3
        }
        val positions = if (dur > 30_000_000L) listOf(0.15, 0.5, 0.85) else listOf(0.5)
        var calibration = 1.0
        repeat(maxIterations.coerceIn(1, 5)) { iteration ->
            var requested = 0.0
            var actual = 0.0
            for (pos in positions) {
                val len = minOf(seconds * 1_000_000L, dur)
                val seg = PlanSegment(((dur - len) * pos).toLong().coerceAtLeast(0), len)
                val ctx = factory.context(source, config, segment = seg, calibration = calibration)
                val resolved = factory.plan(config, ctx)
                val requestedKbps = resolved.plan.video?.rateControl?.bitrateKbps ?: return calibration
                val r = encode(source, config, seg, withMetrics = false, calibration = calibration)
                requested += requestedKbps * 1000.0 * calibration
                actual += r.videoBps.toDouble()
            }
            // ratio = produced / budget at the current calibration c. The encoder's own
            // overshoot factor is ratio * c, which becomes the next calibration.
            val ratio = if (requested > 0) actual / requested else 1.0
            onStep(iteration + 1, ratio)
            if (abs(ratio - 1.0) < 0.05) return calibration
            calibration = (ratio * calibration).coerceIn(0.5, 2.0)
        }
        return calibration
    }

    /** Encodes the same segment with several configurations (quality ladder / A–B compare). */
    suspend fun compare(
        source: SourceFile,
        configs: List<Pair<String, CompressionConfig>>,
        segment: PlanSegment,
        onProgress: (index: Int, progress: Double) -> Unit = { _, _ -> },
    ): List<ComparisonPoint> = configs.mapIndexed { i, (label, cfg) ->
        val r = encode(source, cfg, segment, withMetrics = true) { onProgress(i, it) }
        val projected = (r.sampleBytes.toDouble() * source.info.durationUs / r.segmentDurationUs.coerceAtLeast(1)).toLong()
        ComparisonPoint(label, cfg, r, projected)
    }

    fun clearAll() = files.clearPreviews(0)

    companion object {
        fun encodeResult(r: PreviewResult): String = AppJson.json.encodeToString(PreviewResult.serializer(), r)
    }
}
