package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.capability.CapabilityResolver
import com.kuyamcliff.compressor.capability.HwEncoderChoice
import com.kuyamcliff.compressor.capability.HwResolution
import com.kuyamcliff.compressor.engine.ComplexityReport
import com.kuyamcliff.compressor.model.AudioCodec
import com.kuyamcliff.compressor.model.AudioQuality
import com.kuyamcliff.compressor.model.AudioSettings
import com.kuyamcliff.compressor.model.BitDepth
import com.kuyamcliff.compressor.model.ChannelChoice
import com.kuyamcliff.compressor.model.ChapterMode
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.CropMode
import com.kuyamcliff.compressor.model.Deinterlace
import com.kuyamcliff.compressor.model.EngineChoice
import com.kuyamcliff.compressor.model.ExecutionPlan
import com.kuyamcliff.compressor.model.FilterStrength
import com.kuyamcliff.compressor.model.FpsChoice
import com.kuyamcliff.compressor.model.FpsMode
import com.kuyamcliff.compressor.model.HdrMode
import com.kuyamcliff.compressor.model.MetadataMode
import com.kuyamcliff.compressor.model.PlanAudio
import com.kuyamcliff.compressor.model.PlanBurn
import com.kuyamcliff.compressor.model.PlanChapter
import com.kuyamcliff.compressor.model.PlanColor
import com.kuyamcliff.compressor.model.PlanContainer
import com.kuyamcliff.compressor.model.PlanCrop
import com.kuyamcliff.compressor.model.PlanDenoise
import com.kuyamcliff.compressor.model.PlanFilters
import com.kuyamcliff.compressor.model.PlanRateControl
import com.kuyamcliff.compressor.model.PlanSegment
import com.kuyamcliff.compressor.model.PlanSharpen
import com.kuyamcliff.compressor.model.PlanSubtitle
import com.kuyamcliff.compressor.model.PlanValidation
import com.kuyamcliff.compressor.model.PlanVideo
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.model.Scaler
import com.kuyamcliff.compressor.model.SourceInfo
import com.kuyamcliff.compressor.model.StreamInfo
import com.kuyamcliff.compressor.model.SubtitleMode
import com.kuyamcliff.compressor.model.VideoCodec
import com.kuyamcliff.compressor.model.VideoMode
import com.kuyamcliff.compressor.model.VideoSettings
import com.kuyamcliff.compressor.model.VideoStreamInfo
import kotlin.math.roundToInt

enum class PipelineKind { REMUX, HARDWARE, HYBRID, SOFTWARE }

/** Everything the planner needs besides the config. */
data class PlanContext(
    val source: SourceInfo,
    val sourceSizeBytes: Long,
    val resolver: CapabilityResolver?,          // null = no hardware available (e.g. tests)
    val complexity: ComplexityReport? = null,
    /** Bitrate measured from a preview sample with the same settings (bits/s). */
    val measuredVideoBps: Long? = null,
    /** Correction factor from smart-target sampling (actual/requested bitrate). */
    val bitrateCalibration: Double = 1.0,
    val scratchDir: String = "",
    val fontsDir: String = "",
    val fallbackFont: String = "",
    /** external subtitle URI -> app-private copy path */
    val externalSubtitlePaths: Map<String, String> = emptyMap(),
    val segment: PlanSegment? = null,
    val threadBudget: Int = 0,
    val measuredPixelsPerSec: Map<String, Double> = emptyMap(),
)

data class PlanSummary(
    val pipeline: PipelineKind,
    val encoderName: String,
    val encoderDisplay: String,
    val hwEncoderComponent: String? = null,
    val hwDecoderComponent: String? = null,
    val inputMode: String? = null,               // "Surface" / "Buffer (NV12)"
    val outputSize: Size? = null,
    val outputFps: Double = 0.0,
    val fpsLabel: String = "",
    val rateControlLabel: String = "",
    val qualityLabel: String = "",
    val audioLabel: String = "",
    val container: Container = Container.MP4,
    val estimate: SizeRange? = null,
    val videoKbps: Int = 0,
    val audioKbps: Int = 0,
    val budget: TargetBudget? = null,
    val feasibility: Feasibility? = null,
    val encodeSeconds: Double? = null,
    val hardwareUnavailableReasons: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val subtitleCount: Int = 0,
    val audioTrackCount: Int = 0,
)

data class ResolvedPlan(val plan: ExecutionPlan, val summary: PlanSummary)

class PlanningException(val issues: List<ConfigIssue>) : Exception(issues.joinToString("; ") { it.message })

/**
 * CompressionConfig -> validate -> normalize -> resolve capabilities -> immutable
 * ExecutionPlan (PRD §141). Pure and deterministic given its inputs.
 */
class Planner {

    fun plan(config: CompressionConfig, ctx: PlanContext, jobId: String = ""): ResolvedPlan {
        val issues = ConfigValidator.validate(config, ctx)
        val errors = issues.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) throw PlanningException(errors)
        return build(config, ctx, jobId, issues)
    }

    private fun build(config: CompressionConfig, ctx: PlanContext, jobId: String, issues: List<ConfigIssue>): ResolvedPlan {
        val src = ctx.source
        val container = config.container
        val notes = issues.filter { it.severity != Severity.ERROR }.map { it.message }.toMutableList()
        val vSettings = config.video
        val vStream = videoStream(src, vSettings)
        val durationSec = (ctx.segment?.durationUs?.let { minOf(it, src.durationUs) } ?: src.durationUs) / 1e6

        val audioPlans = planAudio(config, ctx)
        val audioKbps = audioPlans.sumOf { a ->
            if (a.mode == "copy") ((src.stream(a.sourceStreamIndex)?.bitrate ?: 128_000L) / 1000).toInt() else a.bitrateKbps
        }
        val (subPlans, burn) = planSubtitles(config, ctx)

        var summary = PlanSummary(
            pipeline = PipelineKind.REMUX, encoderName = "copy", encoderDisplay = "Stream copy",
            container = container, audioKbps = audioKbps, audioLabel = audioLabel(audioPlans, src),
            subtitleCount = subPlans.size, audioTrackCount = audioPlans.size,
        )

        var planVideo: PlanVideo? = null
        var expectW: Int? = null
        var expectH: Int? = null
        var expectCodec: String? = null
        if (vStream != null) {
            val v = vStream.video!!
            if (vSettings.mode == VideoMode.COPY) {
                planVideo = PlanVideo(sourceStreamIndex = vStream.index, mode = "copy")
                expectW = v.displayWidth
                expectH = v.displayHeight
                expectCodec = vStream.codec
                val vbps = if (vStream.bitrate > 0) vStream.bitrate else 0L
                val bytes = ((vbps + audioKbps * 1000L) / 8 * durationSec).toLong()
                val mid = if (ctx.segment == null && audioPlans.all { it.mode == "copy" }) ctx.sourceSizeBytes else bytes
                summary = summary.copy(
                    outputSize = Size(v.displayWidth, v.displayHeight), outputFps = Geometry.sourceFps(v),
                    fpsLabel = "Same as source", estimate = Estimator.rangeFor(mid, EstimateBasis.CONTROLLED),
                    rateControlLabel = "No re-encoding", qualityLabel = "Original quality",
                )
            } else {
                val result = planVideo(config, ctx, vStream, v, durationSec, audioKbps, burn)
                planVideo = result.first
                summary = summary.copy(
                    pipeline = result.second.pipeline,
                    encoderName = result.second.encoderName,
                    encoderDisplay = result.second.encoderDisplay,
                    hwEncoderComponent = result.second.hwEncoderComponent,
                    hwDecoderComponent = result.second.hwDecoderComponent,
                    inputMode = result.second.inputMode,
                    outputSize = result.second.outputSize,
                    outputFps = result.second.outputFps,
                    fpsLabel = result.second.fpsLabel,
                    rateControlLabel = result.second.rateControlLabel,
                    qualityLabel = result.second.qualityLabel,
                    estimate = result.second.estimate,
                    videoKbps = result.second.videoKbps,
                    budget = result.second.budget,
                    feasibility = result.second.feasibility,
                    encodeSeconds = result.second.encodeSeconds,
                    hardwareUnavailableReasons = result.second.hardwareUnavailableReasons,
                )
                notes += result.second.notes
                expectW = planVideo.width
                expectH = planVideo.height
                expectCodec = planVideo.codec
            }
        } else {
            val bytes = (audioKbps * 1000L / 8 * durationSec).toLong()
            summary = summary.copy(estimate = Estimator.rangeFor(bytes, EstimateBasis.CONTROLLED), encoderDisplay = "Audio only")
        }

        val chapters = when (config.chapters.mode) {
            ChapterMode.PRESERVE -> "preserve"
            ChapterMode.STRIP -> "strip"
            ChapterMode.CUSTOM -> "custom"
        }.let { if (!Compatibility.supportsChapters(container) && it != "strip") "strip" else it }

        val plan = ExecutionPlan(
            jobId = jobId,
            container = PlanContainer(
                format = container.muxer,
                fastStart = config.output.fastStart && !config.output.fragmented,
                fragmented = config.output.fragmented && container == Container.MP4,
            ),
            video = planVideo,
            audio = audioPlans,
            subtitles = subPlans,
            chapterMode = chapters,
            chapters = if (chapters == "custom") config.chapters.custom.map { PlanChapter(it.title, it.startUs, it.endUs) } else emptyList(),
            metadataMode = when (config.metadata.mode) {
                MetadataMode.PRESERVE -> "preserve"; MetadataMode.STRIP -> "strip"; MetadataMode.CUSTOM -> "custom"
            },
            metadata = if (config.metadata.mode == MetadataMode.CUSTOM) config.metadata.fields else emptyMap(),
            segment = ctx.segment,
            copyAttachments = Compatibility.supportsAttachments(container),
            validate = PlanValidation(
                container = container.muxer,
                videoCodec = expectCodec,
                width = expectW,
                height = expectH,
                durationUs = (durationSec * 1e6).toLong().takeIf { it > 0 },
                audioTracks = audioPlans.size,
                subtitleTracks = subPlans.size,
                expectVideo = planVideo != null,
            ),
        )
        return ResolvedPlan(plan, summary.copy(notes = notes.distinct()))
    }

    // ------------------------------------------------------------------ video

    private data class VideoResult(
        val pipeline: PipelineKind,
        val encoderName: String,
        val encoderDisplay: String,
        val hwEncoderComponent: String?,
        val hwDecoderComponent: String?,
        val inputMode: String?,
        val outputSize: Size,
        val outputFps: Double,
        val fpsLabel: String,
        val rateControlLabel: String,
        val qualityLabel: String,
        val estimate: SizeRange,
        val videoKbps: Int,
        val budget: TargetBudget?,
        val feasibility: Feasibility?,
        val encodeSeconds: Double?,
        val hardwareUnavailableReasons: List<String>,
        val notes: List<String>,
    )

    /** Why the GPU fast path cannot be used for these settings (empty = it can). */
    fun cpuOnlyReasons(s: VideoSettings, source: VideoStreamInfo, hasBurn: Boolean): List<String> = buildList {
        val f = s.filters
        if (f.deinterlace != Deinterlace.OFF || f.detelecine) add("deinterlacing")
        if (f.denoise != FilterStrength.OFF) add("denoise")
        if (f.deblock != FilterStrength.OFF) add("deblock")
        if (f.sharpen != FilterStrength.OFF) add("sharpen")
        if (f.deband) add("deband")
        if (source.isHdr && s.hdr == HdrMode.TONEMAP_SDR) add("HDR to SDR conversion")
        if (!s.color.isPreserve) add("colour conversion")
        if (hasBurn) add("subtitle burn-in")
        if (s.scaler != Scaler.AUTOMATIC) add("${s.scaler.name.lowercase()} scaling")
    }

    /** Reasons the requested settings need a software encoder (empty = hardware possible). */
    fun softwareOnlyReasons(s: VideoSettings, source: VideoStreamInfo): List<String> = buildList {
        if (s.codec == VideoCodec.FFV1 || s.codec == VideoCodec.MPEG4 || s.codec == VideoCodec.VP8) add("${s.codec.name} has no hardware encoder path")
        if (s.rateControl == RateControlMode.LOSSLESS) add("lossless encoding")
        if (s.twoPass) add("two-pass encoding")
        if (wantsTenBit(s, source)) add("10-bit / HDR output")
        if (s.encoderPreset != null || s.tune != null) add("software encoder preset/tune")
        if (s.advancedOptions.isNotEmpty()) add("advanced encoder options")
        if (s.nativeQuality != null) add("encoder-native quality value")
    }

    fun wantsTenBit(s: VideoSettings, v: VideoStreamInfo): Boolean = when (s.bitDepth) {
        BitDepth.TEN -> true
        BitDepth.EIGHT -> false
        BitDepth.AUTO -> v.bitDepth > 8 && !(v.isHdr && s.hdr == HdrMode.TONEMAP_SDR) &&
            Codecs.info(s.codec).supports10Bit && s.engine != EngineChoice.HARDWARE
    }

    private fun planVideo(
        config: CompressionConfig,
        ctx: PlanContext,
        stream: StreamInfo,
        v: VideoStreamInfo,
        durationSec: Double,
        audioKbps: Int,
        burn: PlanBurn?,
    ): Pair<PlanVideo, VideoResult> {
        val s = config.video
        val codec = s.codec
        val sw = Codecs.info(codec)
        val notes = mutableListOf<String>()
        val srcFps = Geometry.sourceFps(v)
        val outFps = Geometry.outputFps(v, s)
        val tenBit = wantsTenBit(s, v)

        // --- engine / pipeline selection
        val swReasons = softwareOnlyReasons(s, v)
        val cpuReasons = cpuOnlyReasons(s, v, burn != null)
        var hwChoice: HwEncoderChoice? = null
        var hwReasons: List<String> = emptyList()
        val provisionalSize = Geometry.outputSize(v, s, if (codec == VideoCodec.HEVC) 8 else 2)
        if (s.engine != EngineChoice.SOFTWARE && swReasons.isEmpty() && codec.mime != null && ctx.resolver != null) {
            val needSurface = cpuReasons.isEmpty()
            var res = ctx.resolver.resolveEncoder(codec.mime, provisionalSize.width, provisionalSize.height, outFps, false, needSurface, codec.name)
            if (res is HwResolution.Unavailable && needSurface) {
                // Surface input unavailable: try the buffer (hybrid) path.
                res = ctx.resolver.resolveEncoder(codec.mime, provisionalSize.width, provisionalSize.height, outFps, false, false, codec.name)
            }
            when (res) {
                is HwResolution.Available -> hwChoice = res.choice
                is HwResolution.Unavailable -> hwReasons = res.reasons
            }
        } else if (s.engine != EngineChoice.SOFTWARE) {
            hwReasons = if (ctx.resolver == null) listOf("Hardware codecs are unavailable.") else swReasons.map { "Requires software encoding: $it" }
        }
        val useHw = hwChoice != null && s.engine != EngineChoice.SOFTWARE
        val alignment = when {
            useHw -> maxOf(2, hwChoice!!.codec.widthAlignment, hwChoice.codec.heightAlignment)
            codec == VideoCodec.HEVC -> 8 // Kvazaar requires multiples of 8
            else -> 2
        }
        val size = Geometry.outputSize(v, s, alignment)
        var pipeline = PipelineKind.SOFTWARE
        var hwDecoder: String? = null
        if (useHw) {
            val decoder = stream.codecMime()?.let { ctx.resolver!!.resolveDecoder(it, v.width, v.height, srcFps) }
            pipeline = if (cpuReasons.isEmpty() && hwChoice!!.surfaceInput && decoder != null && v.bitDepth <= 8 && !v.isHdr) {
                hwDecoder = decoder.name
                PipelineKind.HARDWARE
            } else {
                if (cpuReasons.isNotEmpty()) notes += "Using hybrid pipeline: ${cpuReasons.joinToString()} runs on the CPU, encoding on hardware."
                PipelineKind.HYBRID
            }
        }

        // --- rate control
        val pixelsPerSec = size.pixels * outFps
        val srcPps = v.displayWidth.toLong() * v.displayHeight * srcFps
        val complexity = Estimator.complexityFactor(ctx.complexity, stream.bitrate, srcPps.toDouble(), stream.codec)
        val modelInput = Estimator.VideoModelInput(
            size.width, size.height, outFps, codec, useHw, s.effectiveSlider, complexity, stream.bitrate, srcPps.toDouble(), stream.codec,
        )
        var rc: PlanRateControl
        var hwBitrateMode = hwChoice?.bitrateMode ?: CapabilityResolver.BITRATE_MODE_VBR
        var hwQuality = -1
        var hwQpMin = -1
        var hwQpMax = -1
        var rcLabel: String
        var qualityLabel: String
        var videoBps: Long
        var basis: EstimateBasis
        var budget: TargetBudget? = null
        var feasibility: Feasibility? = null
        when (s.rateControl) {
            RateControlMode.CONSTANT_QUALITY -> {
                rcLabel = "Constant quality"
                videoBps = ctx.measuredVideoBps ?: Estimator.constantQualityBitrate(modelInput)
                basis = when {
                    ctx.measuredVideoBps != null -> EstimateBasis.SAMPLE
                    ctx.complexity != null -> EstimateBasis.ANALYSIS
                    else -> EstimateBasis.MODEL
                }
                if (useHw) {
                    val c = hwChoice!!.codec
                    if (hwChoice.supportsCq && c.qualityMax > c.qualityMin) {
                        hwBitrateMode = CapabilityResolver.BITRATE_MODE_CQ
                        hwQuality = (c.qualityMin + (c.qualityMax - c.qualityMin) * s.effectiveSlider).roundToInt()
                        rc = PlanRateControl(mode = "cq", quality = hwQuality.toDouble())
                        qualityLabel = "CQ $hwQuality (hardware scale ${c.qualityMin}–${c.qualityMax})"
                    } else {
                        // No constant-quality mode on this encoder: quality-targeted VBR,
                        // optionally with QP bounds. Labelled honestly in the UI.
                        val kbps = (videoBps / 1000).toInt().coerceIn(maxOf(100, c.bitrateMin / 1000), maxOf(200, c.bitrateMax / 1000))
                        rc = PlanRateControl(mode = "vbr", bitrateKbps = kbps, maxBitrateKbps = (kbps * 1.6).toInt())
                        if (c.qpBounds) {
                            hwQpMax = (42 - 20 * s.effectiveSlider).roundToInt()
                            hwQpMin = (hwQpMax - 18).coerceAtLeast(10)
                        }
                        qualityLabel = "Quality-targeted VBR ≈${kbps} kbps (encoder has no constant-quality mode)"
                        rcLabel = "Variable bitrate"
                        basis = if (basis == EstimateBasis.MODEL) EstimateBasis.ANALYSIS else basis
                        videoBps = kbps * 1000L
                    }
                } else {
                    val q = s.nativeQuality ?: sw.scale.fromSlider(s.effectiveSlider)
                    rc = PlanRateControl(mode = if (codec == VideoCodec.H264 || codec == VideoCodec.HEVC) "cqp" else "crf", quality = q)
                    qualityLabel = "${sw.scale.label} ${q.roundToInt()}"
                }
            }
            RateControlMode.AVERAGE_BITRATE, RateControlMode.CONSTANT_BITRATE -> {
                val cbr = s.rateControl == RateControlMode.CONSTANT_BITRATE
                rc = PlanRateControl(
                    mode = if (cbr) "cbr" else "abr", bitrateKbps = s.bitrateKbps,
                    maxBitrateKbps = if (cbr) s.bitrateKbps else s.maxBitrateKbps, twoPass = s.twoPass && sw.supportsTwoPass && !useHw,
                )
                if (useHw && cbr) hwBitrateMode = CapabilityResolver.BITRATE_MODE_CBR
                rcLabel = if (cbr) "Constant bitrate" else if (rc.twoPass) "Average bitrate (2-pass)" else "Average bitrate"
                qualityLabel = "${s.bitrateKbps} kbps"
                videoBps = s.bitrateKbps * 1000L
                basis = EstimateBasis.CONTROLLED
            }
            RateControlMode.TARGET_SIZE -> {
                val targetBytes = (s.targetSizeMb * 1024 * 1024).toLong()
                val subs = config.subtitles.let { if (it.mode == SubtitleMode.COPY) (it.tracks?.count { t -> t.include } ?: ctx.source.subtitleStreams.size) else 0 }
                val b = TargetSize.budget(targetBytes, durationSec, audioKbps, subs, config.container)
                budget = b
                feasibility = TargetSize.feasibility(
                    b, durationSec, size, outFps, codec, useHw, complexity, audioKbps, config.container,
                    Geometry.croppedSize(v, s),
                )
                val kbps = (b.videoKbps / ctx.bitrateCalibration).roundToInt().coerceAtLeast(TargetSize.MIN_VIDEO_KBPS)
                val twoPass = s.twoPass && sw.supportsTwoPass && !useHw
                rc = PlanRateControl(
                    mode = "abr", bitrateKbps = kbps, maxBitrateKbps = (kbps * 1.5).roundToInt(),
                    bufferKbits = kbps * 2, twoPass = twoPass,
                )
                rcLabel = if (twoPass) "Target size (2-pass)" else if (s.smartTarget) "Smart target size" else "Target size"
                qualityLabel = "${b.videoKbps} kbps video budget"
                videoBps = b.videoKbps * 1000L
                basis = EstimateBasis.CONTROLLED
            }
            RateControlMode.LOSSLESS -> {
                rc = PlanRateControl(mode = "lossless")
                rcLabel = "Lossless"
                qualityLabel = "Mathematically lossless"
                videoBps = (pixelsPerSec * 12 * sw.efficiency / 20.0 * 0.5).toLong().coerceAtLeast(1_000_000)
                basis = EstimateBasis.MODEL
            }
        }

        // --- size estimate
        val payload = (videoBps + audioKbps * 1000L) / 8.0 * durationSec
        val total = payload + Estimator.containerOverheadBytes(config.container, payload.toLong(), durationSec)
        val estimate = if (budget != null) {
            SizeRange((budget.targetBytes * 0.93).toLong(), (budget.targetBytes * 1.04).toLong(), EstimateBasis.CONTROLLED)
        } else Estimator.rangeFor(total.toLong(), basis)

        // --- encoder-specific details
        val encoderName = if (useHw) hwChoice!!.codec.name else sw.ffEncoder
        val encoderDisplay = if (useHw) "${codec.name} hardware (${hwChoice!!.codec.name})" else "${codec.name} software (${sw.displayName})"
        val swPreset = if (useHw) "" else s.encoderPreset ?: sw.defaultPreset ?: ""
        val options = LinkedHashMap(s.advancedOptions)
        if (!useHw && codec == VideoCodec.VP9 && "cpu-used" !in options) options["cpu-used"] = if (swPreset == "realtime") "8" else "4"
        if (!useHw && codec == VideoCodec.VP8 && "cpu-used" !in options) options["cpu-used"] = "4"
        val (fpsMode, fpsNum, fpsDen) = fpsPlan(s, srcFps)
        val fpsLabel = when {
            s.fps == FpsChoice.SOURCE && s.fpsMode != FpsMode.CFR -> "Same as source (${"%.3g".format(srcFps)})"
            fpsMode == "peak" -> "Peak ${"%.3g".format(fpsNum.toDouble() / fpsDen)} (variable)"
            else -> "${"%.3g".format(fpsNum.toDouble() / fpsDen)} constant"
        }
        val f = s.filters
        val cropForEngine = Geometry.cropForEngine(v, s)
        val pixFmt = when {
            codec == VideoCodec.FFV1 && v.bitDepth > 8 -> "yuv420p10le"
            tenBit -> "yuv420p10le"
            else -> "yuv420p"
        }
        val profileStr = when {
            useHw -> ""
            codec == VideoCodec.H264 -> s.profile ?: "high"
            else -> s.profile ?: ""
        }
        val threads = if (s.threads > 0) s.threads else ctx.threadBudget
        val planVideo = PlanVideo(
            sourceStreamIndex = stream.index,
            mode = "transcode",
            pipeline = when (pipeline) {
                PipelineKind.HARDWARE -> "hardware"; PipelineKind.HYBRID -> "hybrid"; else -> "software"
            },
            codec = codec.ffName,
            encoder = encoderName,
            mime = if (useHw) codec.mime!! else "",
            hwDecoder = hwDecoder ?: "",
            width = size.width,
            height = size.height,
            crop = if (s.crop.mode != CropMode.NONE) PlanCrop(cropForEngine.top, cropForEngine.bottom, cropForEngine.left, cropForEngine.right) else null,
            scaler = s.scaler.ff,
            fpsMode = fpsMode,
            fpsNum = fpsNum,
            fpsDen = fpsDen,
            pixFmt = pixFmt,
            bitDepth = if (pixFmt.contains("10")) 10 else 8,
            rateControl = rc,
            keyIntSeconds = s.keyframeIntervalSec,
            bFrames = s.bFrames,
            refFrames = s.refFrames,
            profile = profileStr,
            level = s.level ?: "",
            preset = swPreset,
            tune = if (useHw) "" else s.tune ?: "",
            options = if (useHw) emptyMap() else options,
            threads = threads,
            filters = PlanFilters(
                deinterlace = when (f.deinterlace) {
                    Deinterlace.OFF -> ""; Deinterlace.AUTO, Deinterlace.BWDIF -> "bwdif"; Deinterlace.YADIF -> "yadif"
                },
                deinterlaceAuto = f.deinterlace == Deinterlace.AUTO,
                detelecine = f.detelecine,
                deblock = when (f.deblock) { FilterStrength.OFF -> ""; FilterStrength.LIGHT, FilterStrength.MEDIUM -> "weak"; FilterStrength.STRONG -> "strong" },
                denoise = if (f.denoise != FilterStrength.OFF) PlanDenoise(f.denoiser.ff, f.denoise.name.lowercase()) else null,
                sharpen = if (f.sharpen != FilterStrength.OFF) PlanSharpen(f.sharpener.ff, f.sharpen.name.lowercase()) else null,
                deband = f.deband,
                grayscale = f.grayscale,
                rotate = f.rotate,
                hflip = f.hflip,
                vflip = f.vflip,
            ),
            hdrMode = if (s.hdr == HdrMode.TONEMAP_SDR) "tonemap" else "preserve",
            tonemap = s.tonemap,
            color = if (s.color.isPreserve) null else PlanColor(
                s.color.primaries.orEmpty(), s.color.transfer.orEmpty(), s.color.matrix.orEmpty(), s.color.range.orEmpty(),
            ),
            burn = burn,
            hwColorFormat = if (hwChoice != null && hwChoice.colorFormat > 0) hwChoice.colorFormat else 21,
            hwBitrateMode = hwBitrateMode,
            hwQpMin = hwQpMin,
            hwQpMax = hwQpMax,
            hwProfile = if (useHw) ctx.resolver!!.profileFor(hwChoice!!, s.profile) else -1,
            hwQuality = hwQuality,
        )
        if (!useHw && codec == VideoCodec.HEVC) notes += "Software HEVC (Kvazaar) is slow on phones; hardware HEVC is much faster when available."
        val measured = ctx.measuredPixelsPerSec["${pipeline.name}:${codec.name}"]
        val encodeSeconds = Estimator.encodeSeconds(pixelsPerSec, durationSec * (if (rc.twoPass) 1.6 else 1.0), measured)
        return planVideo to VideoResult(
            pipeline = pipeline,
            encoderName = encoderName,
            encoderDisplay = encoderDisplay,
            hwEncoderComponent = hwChoice?.codec?.name?.takeIf { useHw },
            hwDecoderComponent = hwDecoder,
            inputMode = when (pipeline) {
                PipelineKind.HARDWARE -> "Surface"
                PipelineKind.HYBRID -> if (planVideo.hwColorFormat == 19) "Buffer (I420)" else "Buffer (NV12)"
                else -> null
            },
            outputSize = size,
            outputFps = outFps,
            fpsLabel = fpsLabel,
            rateControlLabel = rcLabel,
            qualityLabel = qualityLabel,
            estimate = estimate,
            videoKbps = (videoBps / 1000).toInt(),
            budget = budget,
            feasibility = feasibility,
            encodeSeconds = encodeSeconds,
            hardwareUnavailableReasons = if (useHw) emptyList() else hwReasons,
            notes = notes,
        )
    }

    private fun fpsPlan(s: VideoSettings, srcFps: Double): Triple<String, Int, Int> {
        if (s.fps == FpsChoice.SOURCE) {
            return if (s.fpsMode == FpsMode.CFR) {
                val (n, d) = Geometry.fpsRational(srcFps)
                Triple("cfr", n, d)
            } else Triple("source", 0, 1)
        }
        val value = if (s.fps == FpsChoice.CUSTOM) s.customFps else s.fps.value
        val (n, d) = Geometry.fpsRational(value)
        return when (s.fpsMode) {
            FpsMode.CFR -> Triple("cfr", n, d)
            FpsMode.PEAK, FpsMode.VFR -> Triple("peak", n, d)
        }
    }

    // ------------------------------------------------------------------ audio

    fun audioBitrateFor(codec: AudioCodec, quality: AudioQuality, channels: Int, custom: Int): Int {
        if (codec.lossless) return 0
        if (quality == AudioQuality.CUSTOM) return custom
        val perChannel = when (codec) {
            AudioCodec.AAC -> when (quality) { AudioQuality.HIGH -> 96; AudioQuality.BALANCED -> 64; else -> 40 }
            AudioCodec.OPUS -> when (quality) { AudioQuality.HIGH -> 64; AudioQuality.BALANCED -> 48; else -> 24 }
            AudioCodec.MP3 -> when (quality) { AudioQuality.HIGH -> 112; AudioQuality.BALANCED -> 80; else -> 56 }
            AudioCodec.AC3, AudioCodec.EAC3 -> when (quality) { AudioQuality.HIGH -> 96; AudioQuality.BALANCED -> 72; else -> 48 }
            else -> 64
        }
        val ch = channels.coerceIn(1, 8)
        // Surround channels compress better jointly; LFE needs little.
        val effective = if (ch > 2) 2 + (ch - 2) * 0.6 else ch.toDouble()
        val kbps = (perChannel * effective).roundToInt()
        val max = when (codec) { AudioCodec.MP3 -> 320; AudioCodec.OPUS -> 510; AudioCodec.AAC -> 640; else -> 640 }
        return kbps.coerceIn(16, max)
    }

    fun selectedAudioStreams(a: AudioSettings, src: SourceInfo): List<Int> {
        if (a.removeAudio) return emptyList()
        a.tracks?.let { tracks -> return tracks.filter { it.include }.map { it.sourceIndex } }
        val streams = src.audioStreams
        val def = streams.firstOrNull { it.default } ?: streams.firstOrNull()
        return listOfNotNull(def?.index)
    }

    private fun planAudio(config: CompressionConfig, ctx: PlanContext): List<PlanAudio> {
        val a = config.audio
        val src = ctx.source
        return selectedAudioStreams(a, src).mapNotNull { idx ->
            val stream = src.stream(idx) ?: return@mapNotNull null
            val info = stream.audio ?: return@mapNotNull null
            val track = a.tracks?.firstOrNull { it.sourceIndex == idx }
            val wantCopy = track?.passthrough ?: a.passthroughWhenPossible
            val language = track?.language ?: stream.language.takeIf { it.isNotBlank() && it != "und" }.orEmpty()
            val title = track?.title ?: stream.title
            val isDefault = track?.isDefault ?: (idx == selectedAudioStreams(a, src).firstOrNull())
            if (wantCopy && Compatibility.canCopyAudio(config.container, stream.codec)) {
                PlanAudio(sourceStreamIndex = idx, mode = "copy", language = language, title = title, default = isDefault)
            } else {
                val codec = track?.codec ?: a.codec
                val channelChoice = track?.channels ?: a.channels
                val maxCh = when (codec) { AudioCodec.MP3 -> 2; AudioCodec.AC3, AudioCodec.EAC3 -> 6; else -> 8 }
                val channels = (if (channelChoice == ChannelChoice.ORIGINAL) info.channels else channelChoice.channels).coerceIn(1, maxCh)
                val bitrate = track?.bitrateKbps ?: audioBitrateFor(codec, a.quality, channels, a.bitrateKbps)
                PlanAudio(
                    sourceStreamIndex = idx, mode = "encode", codec = codec.ffName, encoder = codec.encoder,
                    bitrateKbps = bitrate,
                    channels = if (channelChoice == ChannelChoice.ORIGINAL && info.channels <= maxCh) 0 else channels,
                    sampleRate = track?.sampleRate ?: a.sampleRate,
                    language = language, title = title, default = isDefault, volumeDb = a.volumeDb,
                )
            }
        }
    }

    private fun audioLabel(plans: List<PlanAudio>, src: SourceInfo): String = when {
        plans.isEmpty() -> "No audio"
        plans.size == 1 -> plans[0].let { if (it.mode == "copy") "${src.stream(it.sourceStreamIndex)?.codec?.uppercase()} (copied)" else "${it.codec.uppercase()} ${it.bitrateKbps} kbps" }
        else -> "${plans.size} audio tracks"
    }

    // -------------------------------------------------------------- subtitles

    private fun planSubtitles(config: CompressionConfig, ctx: PlanContext): Pair<List<PlanSubtitle>, PlanBurn?> {
        val s = config.subtitles
        val src = ctx.source
        val support = Compatibility.subtitleSupport(config.container)
        if (s.mode == SubtitleMode.NONE) return emptyList<PlanSubtitle>() to null
        if (s.mode == SubtitleMode.BURN) {
            val ext = s.external.firstOrNull()
            if (s.burnExternal && ext != null) {
                val path = ctx.externalSubtitlePaths[ext.uri] ?: return emptyList<PlanSubtitle>() to null
                return emptyList<PlanSubtitle>() to PlanBurn(-1, path, false, ctx.fontsDir, ctx.fallbackFont)
            }
            val stream = burnStream(s.burnStreamIndex, src) ?: return emptyList<PlanSubtitle>() to null
            return emptyList<PlanSubtitle>() to PlanBurn(
                stream.index, "${ctx.scratchDir}/burn_${stream.index}.mkv", stream.subtitle?.bitmap == true, ctx.fontsDir, ctx.fallbackFont,
            )
        }
        val selected = s.tracks?.filter { it.include } ?: src.subtitleStreams.map { com.kuyamcliff.compressor.model.SubtitleTrackSettings(it.index, isDefault = it.default, forced = it.forced) }
        val out = mutableListOf<PlanSubtitle>()
        for (t in selected) {
            val stream = src.stream(t.sourceIndex) ?: continue
            val bitmap = stream.subtitle?.bitmap == true
            val plan = when {
                stream.codec in support.copyCodecs && (!bitmap || support.bitmapCopy) -> PlanSubtitle(sourceStreamIndex = stream.index, mode = "copy")
                !bitmap && support.textCodec != null -> PlanSubtitle(sourceStreamIndex = stream.index, mode = "convert", codec = support.textCodec)
                else -> null
            } ?: continue
            out += plan.copy(language = stream.language.takeIf { it != "und" }.orEmpty(), title = stream.title, default = t.isDefault, forced = t.forced)
        }
        for (e in s.external) {
            val path = ctx.externalSubtitlePaths[e.uri] ?: continue
            val codec = support.textCodec ?: continue
            out += PlanSubtitle(externalPath = path, mode = "convert", codec = codec, language = e.language, title = e.displayName, default = e.isDefault)
        }
        return out to null
    }

    fun burnStream(index: Int?, src: SourceInfo): StreamInfo? =
        index?.let { src.stream(it) } ?: src.subtitleStreams.firstOrNull { it.forced } ?: src.subtitleStreams.firstOrNull { it.default }
            ?: src.subtitleStreams.firstOrNull()

    fun videoStream(src: SourceInfo, s: VideoSettings): StreamInfo? =
        (s.sourceStreamIndex?.let { src.stream(it) } ?: src.video)?.takeIf { it.video != null }
}

/** MIME used to pick a platform decoder for a source stream. */
fun StreamInfo.codecMime(): String? = when (codec) {
    "h264" -> "video/avc"
    "hevc" -> "video/hevc"
    "av1" -> "video/av01"
    "vp9" -> "video/x-vnd.on2.vp9"
    "vp8" -> "video/x-vnd.on2.vp8"
    "mpeg4" -> "video/mp4v-es"
    else -> null
}
