package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.model.VideoCodec
import kotlin.math.roundToInt

/**
 * Encoder-specific knowledge: quality scales, presets, tunes, profiles. Each
 * encoder has its own quality model; values are never assumed equivalent across
 * codecs (PRD §16, §21).
 */
data class QualityScale(
    val label: String,          // e.g. "QP", "CRF"
    val best: Double,           // value giving the highest quality
    val worst: Double,          // value giving the smallest files
    val lossless: Boolean,      // encoder offers mathematically lossless mode
) {
    /** slider 0 (smallest) .. 1 (best) -> native value. */
    fun fromSlider(s: Float): Double = (worst + (best - worst) * s.coerceIn(0f, 1f)).roundToInt().toDouble()
    fun toSlider(v: Double): Float = ((v - worst) / (best - worst)).toFloat().coerceIn(0f, 1f)
    val min: Double get() = minOf(best, worst)
    val max: Double get() = maxOf(best, worst)
}

data class SoftwareEncoderInfo(
    val codec: VideoCodec,
    val ffEncoder: String,
    val displayName: String,
    val license: String,
    val scale: QualityScale,
    val presets: List<String>,
    val defaultPreset: String?,
    val tunes: List<String>,
    val profiles: List<String>,
    val supportsTwoPass: Boolean,
    val supports10Bit: Boolean,
    val supportsBFrames: Boolean,
    /** H.264-equivalent bitrate factor (lower = more efficient). Used by estimates only. */
    val efficiency: Double,
    /** Relative software speed class on ARM for the default preset (1 = fastest). */
    val speedClass: Int,
)

object Codecs {
    val software: Map<VideoCodec, SoftwareEncoderInfo> = listOf(
        SoftwareEncoderInfo(
            VideoCodec.H264, "libopenh264", "OpenH264", "BSD-2-Clause",
            // OpenH264 has no CRF: constant quality is a fixed QP (0-51, lower = better).
            QualityScale("QP", best = 16.0, worst = 42.0, lossless = false),
            presets = emptyList(), defaultPreset = null, tunes = emptyList(),
            profiles = listOf("constrained_baseline", "main", "high"),
            supportsTwoPass = false, supports10Bit = false, supportsBFrames = false, efficiency = 1.15, speedClass = 1,
        ),
        SoftwareEncoderInfo(
            VideoCodec.HEVC, "libkvazaar", "Kvazaar", "BSD-3-Clause",
            QualityScale("QP", best = 18.0, worst = 42.0, lossless = true),
            presets = listOf("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow", "placebo"),
            defaultPreset = "veryfast", tunes = emptyList(), profiles = listOf("main"),
            supportsTwoPass = false, supports10Bit = false, supportsBFrames = true, efficiency = 0.72, speedClass = 3,
        ),
        SoftwareEncoderInfo(
            VideoCodec.AV1, "libsvtav1", "SVT-AV1", "BSD-3-Clause-Clear",
            QualityScale("CRF", best = 18.0, worst = 56.0, lossless = false),
            presets = (0..13).map { it.toString() }, defaultPreset = "10", tunes = listOf("vq", "psnr", "ssim"),
            profiles = listOf("main"),
            supportsTwoPass = false, supports10Bit = true, supportsBFrames = true, efficiency = 0.55, speedClass = 2,
        ),
        SoftwareEncoderInfo(
            VideoCodec.VP9, "libvpx-vp9", "libvpx VP9", "BSD-3-Clause",
            QualityScale("CRF", best = 15.0, worst = 52.0, lossless = true),
            presets = listOf("realtime", "good", "best"), defaultPreset = "good",
            tunes = listOf("default", "screen", "film", "psnr", "ssim"), profiles = listOf("0", "2"),
            supportsTwoPass = true, supports10Bit = true, supportsBFrames = false, efficiency = 0.68, speedClass = 3,
        ),
        SoftwareEncoderInfo(
            VideoCodec.VP8, "libvpx", "libvpx VP8", "BSD-3-Clause",
            QualityScale("CRF", best = 10.0, worst = 50.0, lossless = false),
            presets = listOf("realtime", "good", "best"), defaultPreset = "good", tunes = listOf("psnr", "ssim"),
            profiles = emptyList(), supportsTwoPass = true, supports10Bit = false, supportsBFrames = false,
            efficiency = 1.05, speedClass = 2,
        ),
        SoftwareEncoderInfo(
            VideoCodec.MPEG4, "mpeg4", "FFmpeg MPEG-4 Part 2", "LGPL-2.1-or-later",
            QualityScale("q", best = 2.0, worst = 20.0, lossless = false),
            presets = emptyList(), defaultPreset = null, tunes = emptyList(), profiles = emptyList(),
            supportsTwoPass = false, supports10Bit = false, supportsBFrames = true, efficiency = 1.6, speedClass = 1,
        ),
        SoftwareEncoderInfo(
            VideoCodec.FFV1, "ffv1", "FFmpeg FFV1 (lossless)", "LGPL-2.1-or-later",
            QualityScale("—", best = 0.0, worst = 0.0, lossless = true),
            presets = emptyList(), defaultPreset = null, tunes = emptyList(), profiles = emptyList(),
            supportsTwoPass = false, supports10Bit = true, supportsBFrames = false, efficiency = 20.0, speedClass = 1,
        ),
    ).associateBy { it.codec }

    fun info(codec: VideoCodec): SoftwareEncoderInfo = software.getValue(codec)

    /** Hardware encoders are assumed comparable to a medium software preset of the same codec. */
    fun hardwareEfficiency(codec: VideoCodec): Double = when (codec) {
        VideoCodec.H264 -> 1.25
        VideoCodec.HEVC -> 0.85
        VideoCodec.AV1 -> 0.7
        VideoCodec.VP9 -> 0.85
        VideoCodec.VP8 -> 1.2
        else -> 1.6
    }

    /** Efficiency of a source codec, for judging how much a re-encode can save. */
    fun sourceEfficiency(ffCodec: String): Double = when (ffCodec) {
        "h264" -> 1.0
        "hevc" -> 0.72
        "av1" -> 0.58
        "vp9" -> 0.7
        "vp8" -> 1.05
        "mpeg4", "msmpeg4v3", "h263" -> 1.6
        "mpeg2video", "mpeg1video" -> 2.2
        "mjpeg" -> 6.0
        "prores", "dnxhd" -> 8.0
        "ffv1", "rawvideo", "huffyuv", "utvideo" -> 20.0
        else -> 1.3
    }
}
