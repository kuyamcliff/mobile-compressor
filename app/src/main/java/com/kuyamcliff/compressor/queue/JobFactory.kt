package com.kuyamcliff.compressor.queue

import android.net.Uri
import com.kuyamcliff.compressor.capability.CapabilityResolver
import com.kuyamcliff.compressor.data.db.CompressionJobEntity
import com.kuyamcliff.compressor.data.prefs.UserPreferences
import com.kuyamcliff.compressor.data.repo.AppJson
import com.kuyamcliff.compressor.data.repo.HistoryRepository
import com.kuyamcliff.compressor.data.repo.JobRepository
import com.kuyamcliff.compressor.data.repo.SourceRepository
import com.kuyamcliff.compressor.data.storage.AppFiles
import com.kuyamcliff.compressor.data.storage.OutputStorage
import com.kuyamcliff.compressor.domain.FileNaming
import com.kuyamcliff.compressor.domain.Geometry
import com.kuyamcliff.compressor.domain.PipelineKind
import com.kuyamcliff.compressor.domain.PlanContext
import com.kuyamcliff.compressor.domain.Planner
import com.kuyamcliff.compressor.domain.ResolvedPlan
import com.kuyamcliff.compressor.engine.ComplexityReport
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.ConflictPolicy
import com.kuyamcliff.compressor.model.EngineChoice
import com.kuyamcliff.compressor.model.ExecutionPlan
import com.kuyamcliff.compressor.model.JobPriority
import com.kuyamcliff.compressor.model.JobStatus
import com.kuyamcliff.compressor.model.JobSummary
import com.kuyamcliff.compressor.model.PlanSegment
import com.kuyamcliff.compressor.model.SourceFile
import com.kuyamcliff.compressor.model.SubtitleMode
import com.kuyamcliff.compressor.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns (source, config) into a planned, queued job. Shared by the configure
 * screen, batch mode, quick actions and the hardware->software fallback.
 */
class JobFactory(
    private val resolver: suspend () -> CapabilityResolver?,
    private val files: AppFiles,
    private val output: OutputStorage,
    private val prefs: UserPreferences,
    private val jobs: JobRepository,
    private val history: HistoryRepository,
    private val sources: SourceRepository,
) {
    private val planner = Planner()

    suspend fun context(
        source: SourceFile,
        config: CompressionConfig,
        segment: PlanSegment? = null,
        complexity: ComplexityReport? = null,
        measuredVideoBps: Long? = null,
        calibration: Double = 1.0,
    ): PlanContext = withContext(Dispatchers.IO) {
        val p = prefs.current()
        val needsFont = config.subtitles.mode == SubtitleMode.BURN
        val (fontsDir, font) = if (needsFont) files.subtitleFont() else "" to ""
        val ext = config.subtitles.external.associate { e ->
            e.uri to runCatching { files.importSubtitle(Uri.parse(e.uri), e.displayName).absolutePath }.getOrDefault("")
        }.filterValues { it.isNotEmpty() }
        val cores = Runtime.getRuntime().availableProcessors()
        PlanContext(
            source = source.info,
            sourceSizeBytes = source.sizeBytes,
            resolver = resolver(),
            complexity = complexity ?: sources.cachedComplexity(source.uri),
            measuredVideoBps = measuredVideoBps,
            bitrateCalibration = calibration,
            scratchDir = files.scratch.absolutePath,
            fontsDir = fontsDir,
            fallbackFont = font,
            externalSubtitlePaths = ext,
            segment = segment,
            threadBudget = if (p.maxParallelSoftware > 1) (cores / p.maxParallelSoftware).coerceAtLeast(1) else 0,
            measuredPixelsPerSec = history.measuredThroughput(),
        )
    }

    fun plan(config: CompressionConfig, ctx: PlanContext, jobId: String = ""): ResolvedPlan = planner.plan(config, ctx, jobId)

    fun summaryOf(source: SourceFile, resolved: ResolvedPlan): JobSummary {
        val s = resolved.summary
        val v = source.info.video
        val vi = v?.video
        val audio = source.info.audioStreams.firstOrNull { it.default } ?: source.info.audioStreams.firstOrNull()
        return JobSummary(
            sourceCodec = v?.codec?.uppercase().orEmpty(),
            sourceResolution = vi?.let { "${it.displayWidth}×${it.displayHeight}" }.orEmpty(),
            sourceFps = vi?.fps ?: 0.0,
            sourceAudio = audio?.let { "${it.codec.uppercase()} ${if (it.bitrate > 0) "${it.bitrate / 1000} kbps" else ""}".trim() }.orEmpty(),
            sourceHdr = vi?.hdr ?: "sdr",
            outputCodec = resolved.plan.video?.let { if (it.mode == "copy") v?.codec?.uppercase() else it.codec.uppercase() }.orEmpty(),
            outputResolution = s.outputSize?.toString().orEmpty(),
            outputFps = s.outputFps,
            outputAudio = s.audioLabel,
            pipeline = s.pipeline.name,
            encoderDisplay = s.encoderDisplay,
            hwEncoder = s.hwEncoderComponent,
            rateControl = s.rateControlLabel,
            quality = s.qualityLabel,
            container = s.container.name,
            estimateLow = s.estimate?.lowBytes ?: 0,
            estimateHigh = s.estimate?.highBytes ?: 0,
            targetBytes = s.budget?.targetBytes ?: 0,
            durationUs = source.info.durationUs,
            notes = s.notes,
        )
    }

    suspend fun outputName(source: SourceFile, config: CompressionConfig, resolved: ResolvedPlan): String {
        val p = prefs.current()
        val template = config.output.fileNameTemplate.ifBlank { p.fileNameTemplate }
        val s = resolved.summary
        return FileNaming.render(
            template,
            FileNaming.Values(
                name = source.displayName,
                codec = resolved.plan.video?.codec?.uppercase() ?: "AUDIO",
                resolution = s.outputSize?.let { "${it.shortSide}p" } ?: "",
                quality = s.qualityLabel.replace(' ', '-').take(24),
                sizeLabel = s.estimate?.let { Format.bytes(it.midBytes).replace(" ", "") } ?: "",
                fps = Format.fpsLabel(s.outputFps) + "fps",
            ),
            config.container.extension,
        )
    }

    enum class NameDecision { USE, RENAME, REPLACE }

    suspend fun nameExists(name: String, config: CompressionConfig): Boolean = withContext(Dispatchers.IO) {
        output.exists(name, config.output.folderUri ?: prefs.current().outputFolderUri)
    }

    suspend fun enqueue(
        source: SourceFile,
        config: CompressionConfig,
        resolved: ResolvedPlan,
        decision: NameDecision = NameDecision.USE,
        priority: JobPriority = JobPriority.NORMAL,
        presetName: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val p = prefs.current()
        val folder = config.output.folderUri ?: p.outputFolderUri
        var name = outputName(source, config, resolved)
        val effective = if (decision == NameDecision.USE) {
            when (config.output.conflict) {
                ConflictPolicy.REPLACE -> NameDecision.REPLACE
                else -> NameDecision.RENAME
            }
        } else decision
        if (effective == NameDecision.RENAME) name = output.uniqueName(name, folder)
        val estimateHigh = resolved.summary.estimate?.highBytes ?: source.sizeBytes
        val summary = summaryOf(source, resolved)
        jobs.enqueue(
            CompressionJobEntity(
                sourceUri = source.uri,
                sourceName = source.displayName,
                sourceSize = source.sizeBytes,
                sourceDurationUs = source.info.durationUs,
                outputName = name,
                outputFolderUri = folder,
                outputKind = if (folder == null) "mediastore" else "saf",
                status = JobStatus.WAITING.name,
                priority = priority.weight,
                createdAt = System.currentTimeMillis(),
                configJson = AppJson.encodeConfig(config),
                planJson = resolved.plan.toJson(),
                summaryJson = AppJson.json.encodeToString(JobSummary.serializer(), summary),
                usesHardware = resolved.summary.pipeline == PipelineKind.HARDWARE || resolved.summary.pipeline == PipelineKind.HYBRID,
                pipeline = resolved.summary.pipeline.name,
                // Worst case for the storage pre-check: the estimate's upper bound, with headroom.
                estimatedMaxBytes = (maxOf(estimateHigh, 1L) * 1.25).toLong(),
                replaceOriginal = config.output.replaceOriginal,
                replaceExisting = effective == NameDecision.REPLACE,
                presetName = presetName,
            ),
        )
    }

    /** Re-plans a queued job with another engine (used by the hardware->software fallback). */
    val replanner = Replanner { job, engineChoice ->
        runCatching {
            val source = sources.analyze(Uri.parse(job.sourceUri))
            val config = AppJson.decodeConfig(job.configJson).let { it.copy(video = it.video.copy(engine = engineChoice)) }
            val ctx = context(source, config)
            val resolved = planner.plan(config, ctx)
            resolved.plan to summaryOf(source, resolved)
        }.getOrNull()
    }

    fun sizeOfOutput(source: SourceFile, config: CompressionConfig) = source.info.video?.video?.let { Geometry.outputSize(it, config.video) }

    companion object {
        fun defaultEngineLabel(e: EngineChoice) = e.name
    }
}

/** Convenience used by previews: plan JSON for a segment of the source. */
fun ExecutionPlan.forPreview(segment: PlanSegment): ExecutionPlan = copy(segment = segment, validate = null, jobId = "preview")
