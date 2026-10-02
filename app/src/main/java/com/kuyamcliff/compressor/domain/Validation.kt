package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.capability.HwResolution
import com.kuyamcliff.compressor.model.AudioCodec
import com.kuyamcliff.compressor.model.BitDepth
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.CropMode
import com.kuyamcliff.compressor.model.EngineChoice
import com.kuyamcliff.compressor.model.HdrMode
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.model.SubtitleMode
import com.kuyamcliff.compressor.model.VideoCodec
import com.kuyamcliff.compressor.model.VideoMode

enum class Severity { ERROR, WARNING, INFO }

/** A one-tap fix offered next to an issue. */
data class ConfigFix(val label: String, val apply: (CompressionConfig) -> CompressionConfig)

data class ConfigIssue(
    val code: String,
    val severity: Severity,
    val message: String,
    val fixes: List<ConfigFix> = emptyList(),
)

/**
 * Validates a configuration against the source, container rules and device
 * capabilities before anything is queued (PRD §123, §141). Every error comes
 * with an explanation and, where possible, concrete alternatives.
 */
object ConfigValidator {

    fun validate(config: CompressionConfig, ctx: PlanContext): List<ConfigIssue> {
        val issues = mutableListOf<ConfigIssue>()
        val src = ctx.source
        val c = config.container
        val v = config.video
        val planner = Planner()
        val vStream = planner.videoStream(src, v)
        val vInfo = vStream?.video

        if (vStream == null && src.audioStreams.isEmpty()) {
            issues += ConfigIssue("no_streams", Severity.ERROR, "The source has no audio or video that can be processed.")
            return issues
        }

        // ---------------- video
        if (vStream != null && vInfo != null) {
            if (!vStream.decoderAvailable && v.mode == VideoMode.TRANSCODE) {
                issues += ConfigIssue(
                    "video_undecodable", Severity.ERROR,
                    "The source video codec (${vStream.codec}) cannot be decoded, so it cannot be re-encoded.",
                    listOf(ConfigFix("Copy the video stream instead") { it.copy(video = it.video.copy(mode = VideoMode.COPY)) }),
                )
            }
            if (v.mode == VideoMode.COPY) {
                if (!Compatibility.canCopyVideo(c, vStream.codec)) {
                    issues += ConfigIssue(
                        "copy_incompatible", Severity.ERROR,
                        "${c.name} cannot contain the source ${vStream.codec.uppercase()} video without re-encoding.",
                        listOf(ConfigFix("Use MKV") { it.copy(container = Container.MKV) },
                            ConfigFix("Re-encode the video") { it.copy(video = it.video.copy(mode = VideoMode.TRANSCODE)) }),
                    )
                }
            } else {
                if (!Compatibility.supports(c, v.codec)) {
                    val alternatives = Compatibility.containersFor(v.codec).filter { !it.isAdvanced }
                    issues += ConfigIssue(
                        "container_video", Severity.ERROR,
                        "Configuration conflict: ${c.name} cannot contain ${v.codec.name} video.",
                        alternatives.map { alt -> ConfigFix("Use ${alt.name}") { it.copy(container = alt) } } +
                            Compatibility.videoCodecs(c).filter { !it.isOther }.take(2).map { vc ->
                                ConfigFix("Use ${vc.name}") { it.copy(video = it.video.copy(codec = vc)) }
                            },
                    )
                }
                val sw = Codecs.info(v.codec)
                if (v.rateControl == RateControlMode.LOSSLESS && !sw.scale.lossless) {
                    issues += ConfigIssue(
                        "lossless_unsupported", Severity.ERROR,
                        "${v.codec.name} has no lossless mode in this build.",
                        listOf(
                            ConfigFix("Use FFV1 (MKV)") { it.copy(container = Container.MKV, video = it.video.copy(codec = VideoCodec.FFV1)) },
                            ConfigFix("Use VP9 lossless (MKV)") { it.copy(container = Container.MKV, video = it.video.copy(codec = VideoCodec.VP9)) },
                        ),
                    )
                }
                if (v.codec == VideoCodec.FFV1 && v.rateControl != RateControlMode.LOSSLESS) {
                    issues += ConfigIssue("ffv1_lossless", Severity.INFO, "FFV1 is always lossless; quality settings are ignored.")
                }
                if (v.twoPass && !sw.supportsTwoPass) {
                    issues += ConfigIssue(
                        "two_pass_unavailable", Severity.ERROR,
                        "2-pass is unavailable for ${v.codec.name}: this build only provides genuine two-pass encoding with libvpx (VP9/VP8).",
                        listOf(ConfigFix("Turn off 2-pass") { it.copy(video = it.video.copy(twoPass = false)) }),
                    )
                }
                if (v.twoPass && v.engine == EngineChoice.HARDWARE) {
                    issues += ConfigIssue(
                        "two_pass_hw", Severity.ERROR,
                        "2-pass is unavailable because hardware encoders do not expose two-pass control.",
                        listOf(ConfigFix("Use software encoding") { it.copy(video = it.video.copy(engine = EngineChoice.SOFTWARE)) },
                            ConfigFix("Turn off 2-pass") { it.copy(video = it.video.copy(twoPass = false)) }),
                    )
                }
                if (v.rateControl == RateControlMode.TARGET_SIZE && v.targetSizeMb <= 0.0) {
                    issues += ConfigIssue("target_missing", Severity.ERROR, "Enter a target size.")
                }
                if ((v.rateControl == RateControlMode.AVERAGE_BITRATE || v.rateControl == RateControlMode.CONSTANT_BITRATE) && v.bitrateKbps < 50) {
                    issues += ConfigIssue("bitrate_low", Severity.ERROR, "The video bitrate must be at least 50 kbps.")
                }
                if (v.bitDepth == BitDepth.TEN && !sw.supports10Bit) {
                    issues += ConfigIssue(
                        "ten_bit_unsupported", Severity.ERROR,
                        "10-bit output is not available for ${v.codec.name} in this app (OpenH264 and Kvazaar are 8-bit; the hardware path is 8-bit SDR).",
                        listOf(
                            ConfigFix("Use 8-bit") { it.copy(video = it.video.copy(bitDepth = BitDepth.EIGHT)) },
                            ConfigFix("Use AV1 (10-bit)") { it.copy(video = it.video.copy(codec = VideoCodec.AV1, engine = EngineChoice.SOFTWARE)) },
                        ),
                    )
                }
                // HDR must never be destroyed silently (PRD §29, §213).
                if (vInfo.isHdr && v.hdr == HdrMode.PRESERVE) {
                    val tenBit = planner.wantsTenBit(v, vInfo)
                    if (!tenBit || v.engine == EngineChoice.HARDWARE) {
                        issues += ConfigIssue(
                            "hdr_loss", Severity.ERROR,
                            "This video is HDR (${vInfo.hdr.uppercase()}). ${v.codec.name} with the selected engine cannot keep HDR " +
                                "(it needs 10-bit output). Choose how to handle HDR.",
                            listOf(
                                ConfigFix("Convert HDR to SDR") { it.copy(video = it.video.copy(hdr = HdrMode.TONEMAP_SDR)) },
                                ConfigFix("Keep HDR with AV1 (software)") {
                                    it.copy(video = it.video.copy(codec = VideoCodec.AV1, engine = EngineChoice.SOFTWARE, bitDepth = BitDepth.AUTO),
                                        container = if (Compatibility.supports(it.container, VideoCodec.AV1)) it.container else Container.MKV)
                                },
                                ConfigFix("Keep HDR with VP9 (software)") {
                                    it.copy(video = it.video.copy(codec = VideoCodec.VP9, engine = EngineChoice.SOFTWARE, bitDepth = BitDepth.AUTO),
                                        container = if (Compatibility.supports(it.container, VideoCodec.VP9)) it.container else Container.MKV)
                                },
                            ),
                        )
                    }
                }
                if (vInfo.isHdr && v.hdr == HdrMode.TONEMAP_SDR) {
                    issues += ConfigIssue("hdr_to_sdr", Severity.WARNING, "HDR will be converted to SDR (tone-mapped). Highlights and colours will change.")
                }
                if (vInfo.bitDepth > 8 && !planner.wantsTenBit(v, vInfo) && !vInfo.isHdr) {
                    issues += ConfigIssue("ten_to_eight", Severity.INFO, "The ${vInfo.bitDepth}-bit source will be converted to 8-bit.")
                }
                // Hardware explicitly requested but impossible.
                if (v.engine == EngineChoice.HARDWARE) {
                    val reasons = hardwareProblems(config, ctx)
                    if (reasons.isNotEmpty()) {
                        issues += ConfigIssue(
                            "hardware_unavailable", Severity.ERROR,
                            "Hardware encoding for ${v.codec.name} is unavailable for this device/configuration: ${reasons.first()}",
                            listOf(ConfigFix("Switch to software") { it.copy(video = it.video.copy(engine = EngineChoice.SOFTWARE)) }),
                        )
                    }
                }
                if (v.crop.mode != CropMode.NONE) {
                    val o = Geometry.orientedSize(vInfo, v.filters.rotate)
                    if (v.crop.left + v.crop.right >= o.width - 16 || v.crop.top + v.crop.bottom >= o.height - 16) {
                        issues += ConfigIssue("crop_too_large", Severity.ERROR, "The crop removes the whole picture.")
                    }
                }
                val out = Geometry.outputSize(vInfo, v)
                val src2 = Geometry.croppedSize(vInfo, v)
                if (out.shortSide < src2.shortSide * 0.5) {
                    issues += ConfigIssue("big_downscale", Severity.WARNING, "Resolution will be reduced substantially (${src2} → ${out}).")
                }
                val outFps = Geometry.outputFps(vInfo, v)
                if (outFps < Geometry.sourceFps(vInfo) * 0.55) {
                    issues += ConfigIssue("big_fps_drop", Severity.WARNING, "Frame rate drops from ${"%.3g".format(Geometry.sourceFps(vInfo))} to ${"%.3g".format(outFps)} FPS.")
                }
                if (v.advancedOptions.keys.any { it.isBlank() || it.contains(' ') || it.contains('=') }) {
                    issues += ConfigIssue("bad_option_syntax", Severity.ERROR, "Advanced encoder options must be written as key=value, one per line.")
                }
                if (v.filters.denoise.ordinal >= 2 && v.filters.denoiser.ff != "atadenoise") {
                    issues += ConfigIssue("slow_filter", Severity.WARNING, "Strong ${v.filters.denoiser.name.lowercase()} denoise can make encoding many times slower and disables the hardware fast path.")
                }
            }
        }

        // ---------------- audio
        val audioIdx = planner.selectedAudioStreams(config.audio, src)
        if (config.audio.removeAudio && src.audioStreams.isNotEmpty()) {
            issues += ConfigIssue("audio_removed", Severity.WARNING, "The output will have no audio.")
        }
        if (audioIdx.size > 1 && !Compatibility.supportsMultipleAudio(c)) {
            issues += ConfigIssue("multi_audio", Severity.ERROR, "${c.name} supports only one audio track.",
                listOf(ConfigFix("Use MKV") { it.copy(container = Container.MKV) }))
        }
        val encodedCodecs = audioIdx.mapNotNull { idx ->
            val track = config.audio.tracks?.firstOrNull { it.sourceIndex == idx }
            val wantCopy = track?.passthrough ?: config.audio.passthroughWhenPossible
            val stream = src.stream(idx)
            if (wantCopy && stream != null && Compatibility.canCopyAudio(c, stream.codec)) null else (track?.codec ?: config.audio.codec)
        }.distinct()
        for (ac in encodedCodecs) {
            if (!Compatibility.supports(c, ac)) {
                val allowed = Compatibility.audioCodecs(c)
                issues += ConfigIssue(
                    "container_audio", Severity.ERROR,
                    "Configuration conflict: ${c.name} cannot contain ${ac.name} audio.",
                    allowed.take(3).map { a -> ConfigFix("Use ${a.name}") { cfg -> cfg.copy(audio = cfg.audio.copy(codec = a, tracks = cfg.audio.tracks?.map { t -> t.copy(codec = null) })) } },
                )
            }
        }
        if (config.audio.passthroughWhenPossible) {
            audioIdx.mapNotNull { src.stream(it) }.filter { !Compatibility.canCopyAudio(c, it.codec) }.forEach {
                issues += ConfigIssue("passthrough_fallback", Severity.INFO, "${it.codec.uppercase()} audio can't be copied into ${c.name}; it will be re-encoded as ${config.audio.codec.name}.")
            }
        }

        // ---------------- subtitles
        val subs = config.subtitles
        val support = Compatibility.subtitleSupport(c)
        if (subs.mode == SubtitleMode.BURN) {
            if (v.mode == VideoMode.COPY) {
                issues += ConfigIssue("burn_copy", Severity.ERROR, "Subtitles can only be burned in when the video is re-encoded.",
                    listOf(ConfigFix("Re-encode the video") { it.copy(video = it.video.copy(mode = VideoMode.TRANSCODE)) }))
            }
            val stream = planner.burnStream(subs.burnStreamIndex, src)
            if (!subs.burnExternal && stream == null) {
                issues += ConfigIssue("burn_none", Severity.ERROR, "There is no subtitle track to burn in.")
            }
            if (!subs.burnExternal && stream?.subtitle?.bitmap == true) {
                issues += ConfigIssue("burn_bitmap", Severity.ERROR,
                    "Burning image-based subtitles (PGS/VobSub/DVB) is not supported by this app's engine.",
                    listOf(ConfigFix("Copy subtitles into MKV instead") { it.copy(container = Container.MKV, subtitles = it.subtitles.copy(mode = SubtitleMode.COPY)) }))
            }
        } else if (subs.mode == SubtitleMode.COPY) {
            val selected = subs.tracks?.filter { it.include }?.mapNotNull { src.stream(it.sourceIndex) } ?: src.subtitleStreams
            val dropped = selected.filter { st ->
                val bitmap = st.subtitle?.bitmap == true
                !(st.codec in support.copyCodecs && (!bitmap || support.bitmapCopy)) && (bitmap || support.textCodec == null)
            }
            if (dropped.isNotEmpty()) {
                issues += ConfigIssue("subs_dropped", Severity.WARNING,
                    "${dropped.size} subtitle track(s) cannot be stored in ${c.name} and will be left out.",
                    listOf(ConfigFix("Use MKV") { it.copy(container = Container.MKV) }))
            }
            if (subs.external.isNotEmpty() && support.textCodec == null) {
                issues += ConfigIssue("ext_subs", Severity.WARNING, "${c.name} cannot hold subtitles; external subtitle files will be ignored.")
            }
        }

        // ---------------- chapters / output
        if (!Compatibility.supportsChapters(c) && src.format.chapters.isNotEmpty() && config.chapters.mode != com.kuyamcliff.compressor.model.ChapterMode.STRIP) {
            issues += ConfigIssue("chapters_dropped", Severity.INFO, "${c.name} does not store chapters; they will be left out.")
        }
        if (config.output.replaceOriginal) {
            issues += ConfigIssue("replace_original", Severity.WARNING, "The original file will be deleted after the new file is validated.")
        }
        return issues
    }

    /** Non-empty when hardware cannot do this configuration (used for forced-hardware errors and UI hints). */
    fun hardwareProblems(config: CompressionConfig, ctx: PlanContext): List<String> {
        val v = config.video
        val planner = Planner()
        val vInfo = planner.videoStream(ctx.source, v)?.video ?: return listOf("No video stream.")
        val sw = planner.softwareOnlyReasons(v, vInfo)
        if (sw.isNotEmpty()) return sw.map { "requires software: $it" }
        val mime = v.codec.mime ?: return listOf("${v.codec.name} has no hardware encoder.")
        val resolver = ctx.resolver ?: return listOf("Hardware codecs are unavailable.")
        val size = Geometry.outputSize(vInfo, v)
        val fps = Geometry.outputFps(vInfo, v)
        return when (val r = resolver.resolveEncoder(mime, size.width, size.height, fps, false, false, v.codec.name)) {
            is HwResolution.Available -> emptyList()
            is HwResolution.Unavailable -> r.reasons
        }
    }

    /** Settings that deserve a confirmation before starting (PRD §144). */
    fun dangerousSettings(config: CompressionConfig, summary: PlanSummary, ctx: PlanContext): List<String> = buildList {
        summary.feasibility?.let { if (it.risk >= QualityRisk.HIGH) add("Target size is very low for this video (expected quality loss: ${it.risk.name.lowercase()}).") }
        val vInfo = Planner().videoStream(ctx.source, config.video)?.video
        if (vInfo != null && config.video.mode == VideoMode.TRANSCODE) {
            val out = summary.outputSize
            if (out != null && out.shortSide < Geometry.croppedSize(vInfo, config.video).shortSide * 0.5) add("Major resolution reduction (${vInfo.displayWidth}×${vInfo.displayHeight} → $out).")
            if (summary.outputFps > 0 && summary.outputFps < Geometry.sourceFps(vInfo) * 0.55) add("Large frame-rate reduction.")
            if (vInfo.isHdr && config.video.hdr == HdrMode.TONEMAP_SDR) add("HDR will be converted to SDR.")
        }
        if (config.audio.removeAudio && ctx.source.audioStreams.isNotEmpty()) add("Audio will be removed.")
        if (config.output.replaceOriginal) add("The original file will be deleted after validation.")
    }
}

fun AudioCodec.displayName(): String = when (this) {
    AudioCodec.AAC -> "AAC"; AudioCodec.OPUS -> "Opus"; AudioCodec.MP3 -> "MP3"; AudioCodec.FLAC -> "FLAC"
    AudioCodec.ALAC -> "ALAC"; AudioCodec.AC3 -> "AC-3"; AudioCodec.EAC3 -> "E-AC-3"
}
