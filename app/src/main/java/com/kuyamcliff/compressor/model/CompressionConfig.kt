package com.kuyamcliff.compressor.model

import kotlinx.serialization.Serializable

/**
 * The one canonical compression configuration. The UI, presets, queue, preview,
 * history and the native execution plan are all derived from this object; no
 * other component keeps its own copy of settings.
 *
 * It is immutable: every edit produces a new instance (which is what makes
 * undo/redo and preview-cache invalidation trivial).
 *
 * Source-specific selections (which tracks, crop pixels) are nullable and mean
 * "decide from the source" when null, so a preset can be applied to any file.
 */
@Serializable
data class CompressionConfig(
    val schemaVersion: Int = 1,
    val container: Container = Container.MP4,
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val subtitles: SubtitleSettings = SubtitleSettings(),
    val chapters: ChapterSettings = ChapterSettings(),
    val metadata: MetadataSettings = MetadataSettings(),
    val output: OutputSettings = OutputSettings(),
)

@Serializable
enum class Container(val muxer: String, val extension: String, val mime: String) {
    MP4("mp4", "mp4", "video/mp4"),
    MKV("matroska", "mkv", "video/x-matroska"),
    WEBM("webm", "webm", "video/webm"),
    MOV("mov", "mov", "video/quicktime"),
    THREE_GP("3gp", "3gp", "video/3gpp"),
    MPEG_TS("mpegts", "ts", "video/mp2t");

    val isAdvanced: Boolean get() = this == MOV || this == THREE_GP || this == MPEG_TS
}

@Serializable
enum class VideoCodec(val ffName: String, val mime: String?, val softwareEncoder: String?) {
    H264("h264", "video/avc", "libopenh264"),
    HEVC("hevc", "video/hevc", "libkvazaar"),
    AV1("av1", "video/av01", "libsvtav1"),
    VP9("vp9", "video/x-vnd.on2.vp9", "libvpx-vp9"),
    VP8("vp8", "video/x-vnd.on2.vp8", "libvpx"),
    MPEG4("mpeg4", "video/mp4v-es", "mpeg4"),
    FFV1("ffv1", null, "ffv1");

    /** Shown under Advanced -> Other codecs. */
    val isOther: Boolean get() = this == VP8 || this == MPEG4 || this == FFV1
}

@Serializable
enum class EngineChoice { AUTOMATIC, HARDWARE, SOFTWARE }

@Serializable
enum class VideoMode { TRANSCODE, COPY }

/** How file size is determined (PRD §148). */
@Serializable
enum class RateControlMode {
    CONSTANT_QUALITY, // quality drives bitrate
    AVERAGE_BITRATE,  // bitrate drives expected size
    TARGET_SIZE,      // bitrate computed from a size budget
    CONSTANT_BITRATE,
    LOSSLESS,
}

/** Named quality positions; each maps to encoder-specific parameters in QualityMapping. */
@Serializable
enum class QualityLevel(val slider: Float) {
    LOSSLESS(1.0f),
    VISUALLY_LOSSLESS(0.95f),
    VERY_HIGH(0.85f),
    HIGH(0.72f),
    BALANCED(0.58f),
    MEDIUM(0.46f),
    SMALL(0.34f),
    VERY_SMALL(0.22f),
    MAXIMUM_COMPRESSION(0.08f),
    CUSTOM(-1f);

    companion object {
        val selectable = listOf(VISUALLY_LOSSLESS, VERY_HIGH, HIGH, BALANCED, MEDIUM, SMALL, VERY_SMALL, MAXIMUM_COMPRESSION)
        fun nearest(slider: Float): QualityLevel = selectable.minBy { kotlin.math.abs(it.slider - slider) }
    }
}

@Serializable
enum class ResolutionChoice(val shortSide: Int) {
    SOURCE(0), R4320(4320), R2160(2160), R1440(1440), R1080(1080), R720(720), R540(540), R480(480), R360(360), R240(240), CUSTOM(-1)
}

@Serializable
enum class FpsChoice(val value: Double) {
    SOURCE(0.0), F23_976(24000.0 / 1001), F24(24.0), F25(25.0), F29_97(30000.0 / 1001), F30(30.0),
    F50(50.0), F59_94(60000.0 / 1001), F60(60.0), F120(120.0), CUSTOM(-1.0)
}

/** CFR forces a constant rate; PEAK keeps variable timing but caps the rate (HandBrake "peak framerate"). */
@Serializable
enum class FpsMode { VFR, CFR, PEAK }

@Serializable
enum class CropMode { NONE, AUTO, MANUAL }

@Serializable
enum class Scaler(val ff: String) { AUTOMATIC("bicubic"), BILINEAR("bilinear"), BICUBIC("bicubic"), LANCZOS("lanczos"), SPLINE("spline"), AREA("area") }

@Serializable
enum class BitDepth { AUTO, EIGHT, TEN }

@Serializable
enum class HdrMode { PRESERVE, TONEMAP_SDR }

@Serializable
enum class FilterStrength { OFF, LIGHT, MEDIUM, STRONG }

@Serializable
enum class Denoiser(val ff: String) { NLMEANS("nlmeans"), ATADENOISE("atadenoise"), BM3D("bm3d") }

@Serializable
enum class Sharpener(val ff: String) { UNSHARP("unsharp"), CAS("cas") }

@Serializable
enum class Deinterlace { OFF, AUTO, YADIF, BWDIF }

@Serializable
data class CropSettings(
    val mode: CropMode = CropMode.NONE,
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

@Serializable
data class FilterSettings(
    val deinterlace: Deinterlace = Deinterlace.OFF,
    val detelecine: Boolean = false,
    val deblock: FilterStrength = FilterStrength.OFF,
    val denoise: FilterStrength = FilterStrength.OFF,
    val denoiser: Denoiser = Denoiser.ATADENOISE,
    val sharpen: FilterStrength = FilterStrength.OFF,
    val sharpener: Sharpener = Sharpener.CAS,
    val deband: Boolean = false,
    val grayscale: Boolean = false,
    val rotate: Int = 0,
    val hflip: Boolean = false,
    val vflip: Boolean = false,
) {
    /** Filters that need CPU processing (never applied on the GPU fast path). */
    val needsCpu: Boolean
        get() = deinterlace != Deinterlace.OFF || detelecine || deblock != FilterStrength.OFF ||
            denoise != FilterStrength.OFF || sharpen != FilterStrength.OFF || deband
}

@Serializable
data class ColorSettings(
    val primaries: String? = null,
    val transfer: String? = null,
    val matrix: String? = null,
    val range: String? = null,
) {
    val isPreserve: Boolean get() = primaries == null && transfer == null && matrix == null && range == null
}

@Serializable
data class VideoSettings(
    val mode: VideoMode = VideoMode.TRANSCODE,
    val codec: VideoCodec = VideoCodec.HEVC,
    val engine: EngineChoice = EngineChoice.AUTOMATIC,
    val rateControl: RateControlMode = RateControlMode.CONSTANT_QUALITY,
    val qualityLevel: QualityLevel = QualityLevel.BALANCED,
    /** 0 = smallest size, 1 = maximum quality (used when qualityLevel == CUSTOM). */
    val qualitySlider: Float = QualityLevel.BALANCED.slider,
    /** Expert override in the encoder's own scale (QP/CRF/CQ); null = derived from the slider. */
    val nativeQuality: Double? = null,
    val bitrateKbps: Int = 4000,
    val maxBitrateKbps: Int = 0,
    val targetSizeMb: Double = 0.0,
    val twoPass: Boolean = false,
    val smartTarget: Boolean = false,
    val resolution: ResolutionChoice = ResolutionChoice.SOURCE,
    val customWidth: Int = 0,
    val customHeight: Int = 0,
    val keepAspect: Boolean = true,
    val allowUpscale: Boolean = false,
    val fps: FpsChoice = FpsChoice.SOURCE,
    val customFps: Double = 30.0,
    val fpsMode: FpsMode = FpsMode.VFR,
    val smartFps: Boolean = false,
    val crop: CropSettings = CropSettings(),
    val scaler: Scaler = Scaler.AUTOMATIC,
    val bitDepth: BitDepth = BitDepth.AUTO,
    val hdr: HdrMode = HdrMode.PRESERVE,
    val tonemap: String = "hable",
    val color: ColorSettings = ColorSettings(),
    val filters: FilterSettings = FilterSettings(),
    val profile: String? = null,
    val level: String? = null,
    val encoderPreset: String? = null,
    val tune: String? = null,
    val keyframeIntervalSec: Double = 0.0,
    val bFrames: Int = -1,
    val refFrames: Int = -1,
    val threads: Int = 0,
    val advancedOptions: Map<String, String> = emptyMap(),
    /** Which source video stream becomes the output (null = primary). */
    val sourceStreamIndex: Int? = null,
) {
    val effectiveSlider: Float get() = if (qualityLevel == QualityLevel.CUSTOM) qualitySlider else qualityLevel.slider
}

@Serializable
enum class AudioCodec(val ffName: String, val encoder: String) {
    AAC("aac", "aac"),
    OPUS("opus", "libopus"),
    MP3("mp3", "libmp3lame"),
    FLAC("flac", "flac"),
    ALAC("alac", "alac"),
    AC3("ac3", "ac3"),
    EAC3("eac3", "eac3");

    val lossless: Boolean get() = this == FLAC || this == ALAC
}

@Serializable
enum class AudioQuality { HIGH, BALANCED, SMALL, CUSTOM }

@Serializable
enum class ChannelChoice(val channels: Int) { ORIGINAL(0), MONO(1), STEREO(2), SURROUND_2_1(3), SURROUND_5_1(6), SURROUND_7_1(8) }

@Serializable
data class AudioTrackSettings(
    val sourceIndex: Int,
    val include: Boolean = true,
    val passthrough: Boolean = false,
    val codec: AudioCodec? = null,      // null = use AudioSettings.codec
    val bitrateKbps: Int? = null,
    val channels: ChannelChoice? = null,
    val sampleRate: Int? = null,
    val language: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class AudioSettings(
    val removeAudio: Boolean = false,
    val codec: AudioCodec = AudioCodec.AAC,
    val quality: AudioQuality = AudioQuality.BALANCED,
    val bitrateKbps: Int = 128,
    val channels: ChannelChoice = ChannelChoice.ORIGINAL,
    val sampleRate: Int = 0,
    /** Copy the source audio when the container accepts it. */
    val passthroughWhenPossible: Boolean = false,
    /** null = the source's default (or first) audio track only. */
    val tracks: List<AudioTrackSettings>? = null,
    val volumeDb: Double = 0.0,
)

@Serializable
enum class SubtitleMode { NONE, COPY, BURN }

@Serializable
data class SubtitleTrackSettings(
    val sourceIndex: Int,
    val include: Boolean = true,
    val isDefault: Boolean = false,
    val forced: Boolean = false,
)

@Serializable
data class ExternalSubtitle(val uri: String, val displayName: String, val language: String = "", val isDefault: Boolean = false)

@Serializable
data class SubtitleSettings(
    val mode: SubtitleMode = SubtitleMode.COPY,
    /** null = all text subtitle tracks the container can hold. */
    val tracks: List<SubtitleTrackSettings>? = null,
    /** Stream to burn in when mode == BURN (null = default/forced track). */
    val burnStreamIndex: Int? = null,
    val external: List<ExternalSubtitle> = emptyList(),
    val burnExternal: Boolean = false,
)

@Serializable
enum class ChapterMode { PRESERVE, STRIP, CUSTOM }

@Serializable
data class ChapterEdit(val title: String, val startUs: Long, val endUs: Long)

@Serializable
data class ChapterSettings(val mode: ChapterMode = ChapterMode.PRESERVE, val custom: List<ChapterEdit> = emptyList())

@Serializable
enum class MetadataMode { PRESERVE, STRIP, CUSTOM }

@Serializable
data class MetadataSettings(val mode: MetadataMode = MetadataMode.PRESERVE, val fields: Map<String, String> = emptyMap())

@Serializable
enum class ConflictPolicy { ASK, RENAME, REPLACE }

@Serializable
data class OutputSettings(
    val fastStart: Boolean = true,
    val fragmented: Boolean = false,
    val fileNameTemplate: String = "{name}_compressed",
    /** SAF tree URI; null = Movies/Compressed via MediaStore. */
    val folderUri: String? = null,
    val conflict: ConflictPolicy = ConflictPolicy.RENAME,
    /** Advanced, default OFF: delete the source after the output validated. */
    val replaceOriginal: Boolean = false,
)
