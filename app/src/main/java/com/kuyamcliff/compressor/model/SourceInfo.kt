package com.kuyamcliff.compressor.model

import kotlinx.serialization.Serializable

/** Source analysis returned by the native probe (Probe.cpp describeInput/probeSource). */
@Serializable
data class SourceInfo(
    val format: FormatInfo = FormatInfo(),
    val streams: List<StreamInfo> = emptyList(),
    val primaryVideoIndex: Int = -1,
    val encoder: String = "",
) {
    val video: StreamInfo? get() = streams.firstOrNull { it.index == primaryVideoIndex }
    val videoStreams: List<StreamInfo> get() = streams.filter { it.type == "video" && !it.attachedPic }
    val audioStreams: List<StreamInfo> get() = streams.filter { it.type == "audio" }
    val subtitleStreams: List<StreamInfo> get() = streams.filter { it.type == "subtitle" }
    val durationUs: Long get() = format.durationUs
    val durationSec: Double get() = format.durationUs / 1e6
    val hasAttachments: Boolean get() = streams.any { it.type == "attachment" }

    fun stream(index: Int): StreamInfo? = streams.firstOrNull { it.index == index }
}

@Serializable
data class FormatInfo(
    val name: String = "",
    val longName: String = "",
    val durationUs: Long = 0,
    val startTimeUs: Long = 0,
    val bitrate: Long = 0,
    val size: Long = 0,
    val tags: Map<String, String> = emptyMap(),
    val chapters: List<ChapterInfo> = emptyList(),
)

@Serializable
data class ChapterInfo(val id: Long = 0, val startUs: Long = 0, val endUs: Long = 0, val title: String = "")

@Serializable
data class StreamInfo(
    val index: Int = 0,
    val type: String = "",
    val codec: String = "",
    val codecLongName: String = "",
    val profile: String = "",
    val level: Int = 0,
    val bitrate: Long = 0,
    val bitrateEstimated: Boolean = false,
    val language: String = "",
    val title: String = "",
    val handler: String = "",
    val encoder: String = "",
    val default: Boolean = false,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
    val comment: Boolean = false,
    val attachedPic: Boolean = false,
    val durationUs: Long = 0,
    val frameCount: Long = 0,
    val decoderAvailable: Boolean = true,
    val tags: Map<String, String> = emptyMap(),
    val video: VideoStreamInfo? = null,
    val audio: AudioStreamInfo? = null,
    val subtitle: SubtitleStreamInfo? = null,
    val timing: TimingInfo? = null,
    val filename: String = "",
    val mimetype: String = "",
) {
    val displayLanguage: String
        get() = if (language.isBlank() || language == "und") "Unknown" else
            java.util.Locale.forLanguageTag(language).getDisplayLanguage(java.util.Locale.getDefault()).ifBlank { language }
}

@Serializable
data class VideoStreamInfo(
    val width: Int = 0,
    val height: Int = 0,
    val sarNum: Int = 1,
    val sarDen: Int = 1,
    val rotation: Int = 0,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val fpsNum: Int = 0,
    val fpsDen: Int = 1,
    val rFpsNum: Int = 0,
    val rFpsDen: Int = 1,
    val pixFmt: String = "",
    val bitDepth: Int = 8,
    val chroma: String = "",
    val colorSpace: String = "",
    val colorPrimaries: String = "",
    val colorTransfer: String = "",
    val colorRange: String = "",
    val chromaLocation: String = "",
    val hdr: String = "sdr",
    val fieldOrder: String = "progressive",
) {
    val fps: Double get() = if (fpsNum > 0 && fpsDen > 0) fpsNum.toDouble() / fpsDen else if (rFpsNum > 0 && rFpsDen > 0) rFpsNum.toDouble() / rFpsDen else 0.0
    val isHdr: Boolean get() = hdr != "sdr"
    val isInterlaced: Boolean get() = fieldOrder == "interlaced"
    val aspectRatio: Double get() = if (displayHeight > 0) displayWidth.toDouble() / displayHeight else 0.0
    val isPortrait: Boolean get() = displayHeight > displayWidth
}

@Serializable
data class AudioStreamInfo(
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val channelLayout: String = "",
    val sampleFmt: String = "",
    val bitsPerSample: Int = 0,
    val lossless: Boolean = false,
)

@Serializable
data class SubtitleStreamInfo(val bitmap: Boolean = false, val text: Boolean = true)

@Serializable
data class TimingInfo(
    val vfr: Boolean? = null,
    val medianFrameDurationUs: Long = 0,
    val minFrameDurationUs: Long = 0,
    val maxFrameDurationUs: Long = 0,
    val sampledFps: Double = 0.0,
    val sampledVideoBitrate: Long = 0,
)

/** A selected source file with its analysis. */
@Serializable
data class SourceFile(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val location: String = "",
    val mimeType: String = "",
    val info: SourceInfo,
) {
    val extension: String get() = displayName.substringAfterLast('.', "").lowercase()
}
