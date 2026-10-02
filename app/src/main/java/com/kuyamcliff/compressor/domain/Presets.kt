package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.model.AudioCodec
import com.kuyamcliff.compressor.model.AudioQuality
import com.kuyamcliff.compressor.model.AudioSettings
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.FilterSettings
import com.kuyamcliff.compressor.model.FpsChoice
import com.kuyamcliff.compressor.model.FpsMode
import com.kuyamcliff.compressor.model.OutputSettings
import com.kuyamcliff.compressor.model.QualityLevel
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.model.ResolutionChoice
import com.kuyamcliff.compressor.model.SubtitleMode
import com.kuyamcliff.compressor.model.SubtitleSettings
import com.kuyamcliff.compressor.model.VideoCodec
import com.kuyamcliff.compressor.model.VideoSettings
import kotlinx.serialization.Serializable

@Serializable
enum class PresetCategory { GENERAL, CONTENT, SHARING, PROFILE, CUSTOM }

/** What a preset needs from the device/build; checked when presets load (PRD §161). */
@Serializable
data class PresetRequirements(
    val codec: VideoCodec? = null,
    val tenBit: Boolean = false,
    val container: Container? = null,
)

/** A preset is a complete configuration object, never a bag of UI strings (PRD §57). */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val description: String,
    val category: PresetCategory,
    val config: CompressionConfig,
    val builtIn: Boolean = true,
    val requirements: PresetRequirements = PresetRequirements(config.video.codec, false, config.container),
    /** Presets that permit FPS reduction may lower the frame rate (PRD §65). */
    val allowsFpsReduction: Boolean = false,
)

object BuiltInPresets {
    private fun cfg(
        codec: VideoCodec,
        quality: QualityLevel,
        resolution: ResolutionChoice = ResolutionChoice.SOURCE,
        maxFps: FpsChoice = FpsChoice.SOURCE,
        container: Container = Container.MP4,
        audio: AudioSettings = AudioSettings(),
        subtitles: SubtitleSettings = SubtitleSettings(),
        filters: FilterSettings = FilterSettings(),
        targetMb: Double = 0.0,
    ) = CompressionConfig(
        container = container,
        video = VideoSettings(
            codec = codec,
            qualityLevel = quality,
            qualitySlider = quality.slider,
            rateControl = if (targetMb > 0) RateControlMode.TARGET_SIZE else if (quality == QualityLevel.LOSSLESS) RateControlMode.LOSSLESS else RateControlMode.CONSTANT_QUALITY,
            targetSizeMb = targetMb,
            resolution = resolution,
            fps = maxFps,
            fpsMode = if (maxFps == FpsChoice.SOURCE) FpsMode.VFR else FpsMode.PEAK,
            filters = filters,
        ),
        audio = audio,
        subtitles = subtitles,
        output = OutputSettings(fastStart = true),
    )

    private val aac96 = AudioSettings(codec = AudioCodec.AAC, quality = AudioQuality.SMALL)
    private val aacBalanced = AudioSettings(codec = AudioCodec.AAC, quality = AudioQuality.BALANCED)
    private val aacHigh = AudioSettings(codec = AudioCodec.AAC, quality = AudioQuality.HIGH)

    val all: List<Preset> = listOf(
        Preset("fast_1080p", "Fast 1080p", "Compatibility first: H.264 + AAC in MP4, plays everywhere.", PresetCategory.GENERAL,
            cfg(VideoCodec.H264, QualityLevel.HIGH, ResolutionChoice.R1080, audio = aacBalanced)),
        Preset("balanced_1080p", "Balanced 1080p", "HEVC at balanced quality: much smaller than H.264 at similar quality.", PresetCategory.GENERAL,
            cfg(VideoCodec.HEVC, QualityLevel.BALANCED, ResolutionChoice.R1080, audio = aacBalanced)),
        Preset("small_1080p", "Small 1080p", "Aggressive HEVC compression, frame rate capped at 30.", PresetCategory.GENERAL,
            cfg(VideoCodec.HEVC, QualityLevel.SMALL, ResolutionChoice.R1080, FpsChoice.F30, audio = aac96), allowsFpsReduction = true),
        Preset("small_720p", "720p Small", "File-size focused 720p HEVC, up to 30 FPS.", PresetCategory.GENERAL,
            cfg(VideoCodec.HEVC, QualityLevel.SMALL, ResolutionChoice.R720, FpsChoice.F30, audio = aac96), allowsFpsReduction = true),
        Preset("max_compression", "Maximum Compression", "AV1, 720p, up to 30 FPS, Opus audio. Smallest files; expect visible loss.", PresetCategory.GENERAL,
            cfg(VideoCodec.AV1, QualityLevel.VERY_SMALL, ResolutionChoice.R720, FpsChoice.F30,
                audio = AudioSettings(codec = AudioCodec.OPUS, quality = AudioQuality.SMALL)), allowsFpsReduction = true),
        Preset("archive", "High Quality Archive", "Quality first: source resolution and frame rate, audio copied, MKV.", PresetCategory.GENERAL,
            cfg(VideoCodec.HEVC, QualityLevel.VERY_HIGH, container = Container.MKV,
                audio = AudioSettings(passthroughWhenPossible = true, quality = AudioQuality.HIGH))),
        Preset("lossless", "Lossless (FFV1)", "Mathematically lossless video in MKV. Usually larger than the source.", PresetCategory.GENERAL,
            cfg(VideoCodec.FFV1, QualityLevel.LOSSLESS, container = Container.MKV,
                audio = AudioSettings(codec = AudioCodec.FLAC))),
        Preset("anime", "Anime", "Flat colours and line art: HEVC with debanding to keep gradients clean.", PresetCategory.CONTENT,
            cfg(VideoCodec.HEVC, QualityLevel.HIGH, ResolutionChoice.R1080, container = Container.MKV,
                filters = FilterSettings(deband = true), audio = aacBalanced)),
        Preset("screen_recording", "Screen Recording", "Text and UI clarity: keeps resolution, caps at 30 FPS.", PresetCategory.CONTENT,
            cfg(VideoCodec.H264, QualityLevel.HIGH, ResolutionChoice.SOURCE, FpsChoice.F30, audio = aac96), allowsFpsReduction = true),
        Preset("gameplay", "Gameplay", "Motion priority: keeps high frame rates, higher quality for fast scenes.", PresetCategory.CONTENT,
            cfg(VideoCodec.HEVC, QualityLevel.HIGH, ResolutionChoice.R1080, audio = aacHigh)),
        Preset("film", "Film", "Keeps texture and grain; source frame rate; HDR preserved when possible.", PresetCategory.CONTENT,
            cfg(VideoCodec.AV1, QualityLevel.VERY_HIGH, container = Container.MKV,
                audio = AudioSettings(passthroughWhenPossible = true))),
        Preset("audio_focused", "Audio-focused", "Balanced video; audio copied when possible, otherwise high-quality AAC.", PresetCategory.CONTENT,
            cfg(VideoCodec.HEVC, QualityLevel.BALANCED, ResolutionChoice.R1080,
                audio = AudioSettings(passthroughWhenPossible = true, quality = AudioQuality.HIGH))),
        Preset("social", "Social Media", "Small and compatible: H.264 1080p, up to 30 FPS.", PresetCategory.SHARING,
            cfg(VideoCodec.H264, QualityLevel.BALANCED, ResolutionChoice.R1080, FpsChoice.F30, audio = aacBalanced), allowsFpsReduction = true),
        Preset("messaging", "Messaging", "Very small H.264 720p for chat apps.", PresetCategory.SHARING,
            cfg(VideoCodec.H264, QualityLevel.SMALL, ResolutionChoice.R720, FpsChoice.F30, audio = aac96), allowsFpsReduction = true),
        Preset("web", "Web", "MP4 with fast start (moov first) for progressive web playback.", PresetCategory.SHARING,
            cfg(VideoCodec.H264, QualityLevel.BALANCED, ResolutionChoice.R1080, audio = aacBalanced)),
        Preset("whatsapp_small", "WhatsApp Small", "Target-size preset. Adjust the limit to what your app currently accepts.", PresetCategory.SHARING,
            cfg(VideoCodec.H264, QualityLevel.BALANCED, ResolutionChoice.R720, FpsChoice.F30, audio = aac96, targetMb = 16.0), allowsFpsReduction = true),
        Preset("telegram_small", "Telegram Small", "Target-size preset (editable limit), HEVC for efficiency.", PresetCategory.SHARING,
            cfg(VideoCodec.HEVC, QualityLevel.BALANCED, ResolutionChoice.R1080, FpsChoice.F30, audio = aacBalanced, targetMb = 50.0), allowsFpsReduction = true),
        Preset("discord_small", "Discord Small", "Target-size preset (editable limit) with H.264 for wide playback.", PresetCategory.SHARING,
            cfg(VideoCodec.H264, QualityLevel.BALANCED, ResolutionChoice.R720, FpsChoice.F30, audio = aac96, targetMb = 10.0), allowsFpsReduction = true),
        Preset("email", "Email", "Target-size preset (editable) for attachments.", PresetCategory.SHARING,
            cfg(VideoCodec.H264, QualityLevel.SMALL, ResolutionChoice.R720, FpsChoice.F30, audio = aac96, targetMb = 20.0), allowsFpsReduction = true),
        Preset("mobile_storage", "Mobile Storage", "Save space on the phone: HEVC 1080p, balanced quality.", PresetCategory.SHARING,
            cfg(VideoCodec.HEVC, QualityLevel.BALANCED, ResolutionChoice.R1080, audio = aacBalanced)),
        Preset("profile_compatibility", "Compatibility", "Works everywhere: MP4, H.264, AAC.", PresetCategory.PROFILE,
            cfg(VideoCodec.H264, QualityLevel.HIGH, audio = aacBalanced)),
        Preset("profile_quality", "Quality", "Quality first; size second.", PresetCategory.PROFILE,
            cfg(VideoCodec.HEVC, QualityLevel.VERY_HIGH, audio = aacHigh)),
        Preset("profile_compression", "Compression", "Size first with reasonable quality.", PresetCategory.PROFILE,
            cfg(VideoCodec.HEVC, QualityLevel.SMALL, ResolutionChoice.R1080, FpsChoice.F30, audio = aac96), allowsFpsReduction = true),
        Preset("profile_extreme", "Extreme", "Smallest output; quality loss is expected.", PresetCategory.PROFILE,
            cfg(VideoCodec.AV1, QualityLevel.MAXIMUM_COMPRESSION, ResolutionChoice.R540, FpsChoice.F24,
                audio = AudioSettings(codec = AudioCodec.OPUS, quality = AudioQuality.SMALL)), allowsFpsReduction = true),
    )

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }
    val default: Preset get() = byId("balanced_1080p")!!
}
