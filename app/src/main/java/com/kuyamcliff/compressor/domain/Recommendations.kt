package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.capability.CapabilityResolver
import com.kuyamcliff.compressor.model.AudioCodec
import com.kuyamcliff.compressor.model.AudioQuality
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.EngineChoice
import com.kuyamcliff.compressor.model.FpsChoice
import com.kuyamcliff.compressor.model.FpsMode
import com.kuyamcliff.compressor.model.HdrMode
import com.kuyamcliff.compressor.model.QualityLevel
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.model.ResolutionChoice
import com.kuyamcliff.compressor.model.SourceInfo
import com.kuyamcliff.compressor.model.VideoCodec

data class Recommendation(
    val config: CompressionConfig,
    val reasons: List<String>,
    val summary: PlanSummary?,
    val savingsLow: Double,
    val savingsHigh: Double,
)

data class FpsSuggestion(val fromFps: Double, val toFps: Double, val savingLow: Double, val savingHigh: Double)

/**
 * Deterministic, explainable suggestions (PRD §60–65, §118). Nothing is ever
 * applied silently: the UI shows these and the user taps "Apply".
 */
object Recommendations {

    /** Best codec this device encodes in hardware, by compression efficiency. */
    fun bestHardwareCodec(resolver: CapabilityResolver?, preferSmallest: Boolean): VideoCodec? {
        if (resolver == null) return null
        val order = if (preferSmallest) listOf(VideoCodec.AV1, VideoCodec.HEVC, VideoCodec.VP9, VideoCodec.H264)
        else listOf(VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.H264)
        return order.firstOrNull { c -> c.mime != null && resolver.hardwareEncoders(c.mime).isNotEmpty() }
    }

    fun recommend(base: CompressionConfig, ctx: PlanContext): Recommendation {
        val src = ctx.source
        val v = src.video
        val reasons = mutableListOf<String>()
        val hwCodec = bestHardwareCodec(ctx.resolver, preferSmallest = false)
        val codec = hwCodec ?: VideoCodec.HEVC
        reasons += if (hwCodec != null) "${codec.name}: efficient and hardware-accelerated on this device"
        else "${codec.name}: efficient compression (no hardware encoder found, software will be used)"
        var resolution = ResolutionChoice.SOURCE
        if (v != null) {
            val short = minOf(v.video!!.displayWidth, v.video.displayHeight)
            if (short > 1080) {
                resolution = ResolutionChoice.R1080
                reasons += "1080p: keeps detail for phone and TV viewing while saving a lot over ${short}p"
            } else reasons += "Keep the source resolution (${short}p)"
        }
        val srcAudio = src.audioStreams.firstOrNull { it.default } ?: src.audioStreams.firstOrNull()
        val passthrough = srcAudio != null && srcAudio.codec == "aac" && srcAudio.bitrate in 1..160_000
        if (passthrough) reasons += "Copy the AAC audio (already efficient)" else reasons += "AAC 128 kbps audio"
        val isHdr = v?.video?.isHdr == true
        var config = base.copy(
            container = if (Compatibility.supports(base.container, codec)) base.container else Container.MP4,
            video = base.video.copy(
                codec = codec,
                engine = EngineChoice.AUTOMATIC,
                rateControl = RateControlMode.CONSTANT_QUALITY,
                qualityLevel = QualityLevel.BALANCED,
                qualitySlider = QualityLevel.BALANCED.slider,
                resolution = resolution,
                fps = FpsChoice.SOURCE,
                fpsMode = FpsMode.VFR,
                hdr = if (isHdr && codec != VideoCodec.AV1) HdrMode.TONEMAP_SDR else base.video.hdr,
            ),
            audio = base.audio.copy(codec = AudioCodec.AAC, quality = AudioQuality.BALANCED, passthroughWhenPossible = passthrough, removeAudio = false),
        )
        if (isHdr && config.video.hdr == HdrMode.TONEMAP_SDR) reasons += "HDR source: converted to SDR for compatibility (you can keep HDR with AV1)"
        val summary = runCatching { Planner().plan(config, ctx).summary }.getOrElse {
            // If hardware choice fails validation for this source, fall back to software.
            config = config.copy(video = config.video.copy(engine = EngineChoice.SOFTWARE))
            runCatching { Planner().plan(config, ctx).summary }.getOrNull()
        }
        val srcBytes = ctx.sourceSizeBytes.coerceAtLeast(1)
        val est = summary?.estimate
        val low = est?.let { 1.0 - it.highBytes.toDouble() / srcBytes } ?: 0.0
        val high = est?.let { 1.0 - it.lowBytes.toDouble() / srcBytes } ?: 0.0
        return Recommendation(config, reasons, summary, low.coerceIn(-1.0, 0.99), high.coerceIn(-1.0, 0.99))
    }

    /** Smart FPS: only suggested when reducing is likely to save meaningful space (PRD §23). */
    fun smartFps(src: SourceInfo): FpsSuggestion? {
        val fps = src.video?.video?.fps ?: return null
        val target = when {
            fps > 100 -> 60.0
            fps > 32 -> 30.0
            else -> return null
        }
        val (lo, hi) = Estimator.fpsSavingRange(fps, target)
        return FpsSuggestion(fps, target, lo, hi)
    }
}
