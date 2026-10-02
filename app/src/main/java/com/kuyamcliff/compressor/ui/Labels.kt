package com.kuyamcliff.compressor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.domain.PipelineKind
import com.kuyamcliff.compressor.domain.QualityRisk
import com.kuyamcliff.compressor.model.AudioCodec
import com.kuyamcliff.compressor.model.AudioQuality
import com.kuyamcliff.compressor.model.ChannelChoice
import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.EngineChoice
import com.kuyamcliff.compressor.model.FilterStrength
import com.kuyamcliff.compressor.model.FpsChoice
import com.kuyamcliff.compressor.model.FpsMode
import com.kuyamcliff.compressor.model.JobStatus
import com.kuyamcliff.compressor.model.QualityLevel
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.model.ResolutionChoice
import com.kuyamcliff.compressor.model.VideoCodec

/** Enum -> localized label. All user-visible text comes from string resources (PRD §133). */
object Labels {
    @Composable fun quality(q: QualityLevel): String = stringResource(
        when (q) {
            QualityLevel.LOSSLESS -> R.string.q_lossless
            QualityLevel.VISUALLY_LOSSLESS -> R.string.q_visually_lossless
            QualityLevel.VERY_HIGH -> R.string.q_very_high
            QualityLevel.HIGH -> R.string.q_high
            QualityLevel.BALANCED -> R.string.q_balanced
            QualityLevel.MEDIUM -> R.string.q_medium
            QualityLevel.SMALL -> R.string.q_small
            QualityLevel.VERY_SMALL -> R.string.q_very_small
            QualityLevel.MAXIMUM_COMPRESSION -> R.string.q_max_compression
            QualityLevel.CUSTOM -> R.string.q_custom
        },
    )

    @Composable fun qualityHelp(q: QualityLevel): String = stringResource(
        when (q) {
            QualityLevel.LOSSLESS -> R.string.qh_lossless
            QualityLevel.VISUALLY_LOSSLESS -> R.string.qh_visually_lossless
            QualityLevel.VERY_HIGH, QualityLevel.HIGH -> R.string.qh_high
            QualityLevel.BALANCED, QualityLevel.MEDIUM -> R.string.qh_balanced
            QualityLevel.SMALL, QualityLevel.VERY_SMALL -> R.string.qh_small
            QualityLevel.MAXIMUM_COMPRESSION -> R.string.qh_extreme
            QualityLevel.CUSTOM -> R.string.qh_custom
        },
    )

    fun codec(c: VideoCodec): String = when (c) {
        VideoCodec.H264 -> "H.264 / AVC"
        VideoCodec.HEVC -> "H.265 / HEVC"
        VideoCodec.AV1 -> "AV1"
        VideoCodec.VP9 -> "VP9"
        VideoCodec.VP8 -> "VP8"
        VideoCodec.MPEG4 -> "MPEG-4 Part 2"
        VideoCodec.FFV1 -> "FFV1 (lossless)"
    }

    fun codecShort(c: VideoCodec): String = when (c) {
        VideoCodec.H264 -> "H.264"; VideoCodec.HEVC -> "HEVC"; VideoCodec.AV1 -> "AV1"; VideoCodec.VP9 -> "VP9"
        VideoCodec.VP8 -> "VP8"; VideoCodec.MPEG4 -> "MPEG-4"; VideoCodec.FFV1 -> "FFV1"
    }

    @Composable fun codecHelp(c: VideoCodec): String = stringResource(
        when (c) {
            VideoCodec.H264 -> R.string.codec_h264_help
            VideoCodec.HEVC -> R.string.codec_hevc_help
            VideoCodec.AV1 -> R.string.codec_av1_help
            VideoCodec.VP9 -> R.string.codec_vp9_help
            VideoCodec.VP8 -> R.string.codec_vp8_help
            VideoCodec.MPEG4 -> R.string.codec_mpeg4_help
            VideoCodec.FFV1 -> R.string.codec_ffv1_help
        },
    )

    fun container(c: Container): String = when (c) {
        Container.MP4 -> "MP4"; Container.MKV -> "MKV"; Container.WEBM -> "WebM"; Container.MOV -> "MOV"
        Container.THREE_GP -> "3GP"; Container.MPEG_TS -> "MPEG-TS"
    }

    fun audioCodec(a: AudioCodec): String = when (a) {
        AudioCodec.AAC -> "AAC"; AudioCodec.OPUS -> "Opus"; AudioCodec.MP3 -> "MP3"; AudioCodec.FLAC -> "FLAC"
        AudioCodec.ALAC -> "ALAC"; AudioCodec.AC3 -> "AC-3"; AudioCodec.EAC3 -> "E-AC-3"
    }

    @Composable fun engine(e: EngineChoice): String = stringResource(
        when (e) { EngineChoice.AUTOMATIC -> R.string.engine_auto; EngineChoice.HARDWARE -> R.string.engine_hw; EngineChoice.SOFTWARE -> R.string.engine_sw },
    )

    @Composable fun rateControl(r: RateControlMode): String = stringResource(
        when (r) {
            RateControlMode.CONSTANT_QUALITY -> R.string.rc_cq
            RateControlMode.AVERAGE_BITRATE -> R.string.rc_abr
            RateControlMode.TARGET_SIZE -> R.string.rc_target
            RateControlMode.CONSTANT_BITRATE -> R.string.rc_cbr
            RateControlMode.LOSSLESS -> R.string.rc_lossless
        },
    )

    @Composable fun resolution(r: ResolutionChoice): String = when (r) {
        ResolutionChoice.SOURCE -> stringResource(R.string.same_as_source)
        ResolutionChoice.CUSTOM -> stringResource(R.string.custom)
        ResolutionChoice.R4320 -> "8K"
        ResolutionChoice.R2160 -> "4K"
        else -> "${r.shortSide}p"
    }

    @Composable fun fps(f: FpsChoice): String = when (f) {
        FpsChoice.SOURCE -> stringResource(R.string.same_as_source)
        FpsChoice.CUSTOM -> stringResource(R.string.custom)
        else -> com.kuyamcliff.compressor.util.Format.fpsLabel(f.value)
    }

    @Composable fun fpsMode(m: FpsMode): String = stringResource(
        when (m) { FpsMode.VFR -> R.string.fps_vfr; FpsMode.CFR -> R.string.fps_cfr; FpsMode.PEAK -> R.string.fps_peak },
    )

    @Composable fun strength(s: FilterStrength): String = stringResource(
        when (s) { FilterStrength.OFF -> R.string.off; FilterStrength.LIGHT -> R.string.light; FilterStrength.MEDIUM -> R.string.medium; FilterStrength.STRONG -> R.string.strong },
    )

    @Composable fun audioQuality(q: AudioQuality): String = stringResource(
        when (q) { AudioQuality.HIGH -> R.string.aq_high; AudioQuality.BALANCED -> R.string.aq_balanced; AudioQuality.SMALL -> R.string.aq_small; AudioQuality.CUSTOM -> R.string.custom },
    )

    @Composable fun channels(c: ChannelChoice): String = stringResource(
        when (c) {
            ChannelChoice.ORIGINAL -> R.string.ch_original; ChannelChoice.MONO -> R.string.ch_mono; ChannelChoice.STEREO -> R.string.ch_stereo
            ChannelChoice.SURROUND_2_1 -> R.string.ch_21; ChannelChoice.SURROUND_5_1 -> R.string.ch_51; ChannelChoice.SURROUND_7_1 -> R.string.ch_71
        },
    )

    @Composable fun status(s: JobStatus): String = stringResource(
        when (s) {
            JobStatus.WAITING -> R.string.st_waiting
            JobStatus.PREPARING -> R.string.st_preparing
            JobStatus.ANALYZING -> R.string.st_analyzing
            JobStatus.ENCODING -> R.string.st_encoding
            JobStatus.PAUSED -> R.string.st_paused
            JobStatus.THERMAL_PAUSED -> R.string.st_thermal_paused
            JobStatus.FINALIZING -> R.string.st_finalizing
            JobStatus.COMPLETE -> R.string.st_complete
            JobStatus.FAILED -> R.string.st_failed
            JobStatus.CANCELLED -> R.string.st_cancelled
            JobStatus.INTERRUPTED -> R.string.st_interrupted
            JobStatus.STORAGE_ERROR -> R.string.st_storage_error
            JobStatus.UNSUPPORTED -> R.string.st_unsupported
        },
    )

    @Composable fun pipeline(p: PipelineKind): String = stringResource(
        when (p) {
            PipelineKind.REMUX -> R.string.pl_remux
            PipelineKind.HARDWARE -> R.string.pl_hardware
            PipelineKind.HYBRID -> R.string.pl_hybrid
            PipelineKind.SOFTWARE -> R.string.pl_software
        },
    )

    @Composable fun risk(r: QualityRisk): String = stringResource(
        when (r) {
            QualityRisk.LOW -> R.string.risk_low
            QualityRisk.MODERATE -> R.string.risk_moderate
            QualityRisk.HIGH -> R.string.risk_high
            QualityRisk.SEVERE -> R.string.risk_severe
            QualityRisk.IMPOSSIBLE -> R.string.risk_impossible
        },
    )
}
