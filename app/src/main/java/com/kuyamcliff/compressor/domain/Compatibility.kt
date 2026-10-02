package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.model.AudioCodec
import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.VideoCodec

/**
 * Internal compatibility matrix (PRD §185): what each container can carry.
 * Mirrors what FFmpeg's muxers accept (avformat_query_codec) for the encoders
 * this build ships; the native engine re-checks before writing anything.
 */
object Compatibility {
    private val videoByContainer: Map<Container, Set<VideoCodec>> = mapOf(
        Container.MP4 to setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.VP9, VideoCodec.MPEG4),
        Container.MKV to VideoCodec.entries.toSet(),
        Container.WEBM to setOf(VideoCodec.VP9, VideoCodec.VP8, VideoCodec.AV1),
        Container.MOV to setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.MPEG4),
        Container.THREE_GP to setOf(VideoCodec.H264, VideoCodec.MPEG4),
        Container.MPEG_TS to setOf(VideoCodec.H264, VideoCodec.HEVC),
    )

    private val audioByContainer: Map<Container, Set<AudioCodec>> = mapOf(
        Container.MP4 to setOf(AudioCodec.AAC, AudioCodec.OPUS, AudioCodec.MP3, AudioCodec.ALAC, AudioCodec.AC3, AudioCodec.EAC3),
        Container.MKV to AudioCodec.entries.toSet(),
        Container.WEBM to setOf(AudioCodec.OPUS),
        Container.MOV to setOf(AudioCodec.AAC, AudioCodec.ALAC, AudioCodec.MP3, AudioCodec.AC3, AudioCodec.EAC3),
        Container.THREE_GP to setOf(AudioCodec.AAC),
        Container.MPEG_TS to setOf(AudioCodec.AAC, AudioCodec.MP3, AudioCodec.AC3, AudioCodec.EAC3, AudioCodec.OPUS),
    )

    /** Source audio codecs (FFmpeg names) that may be stream-copied into each container. */
    private val audioCopyByContainer: Map<Container, Set<String>> = mapOf(
        Container.MP4 to setOf("aac", "mp3", "ac3", "eac3", "alac", "opus", "flac"),
        Container.MKV to setOf("aac", "mp3", "mp2", "ac3", "eac3", "opus", "vorbis", "flac", "alac", "dts", "truehd", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le"),
        Container.WEBM to setOf("opus", "vorbis"),
        Container.MOV to setOf("aac", "mp3", "alac", "ac3", "eac3", "pcm_s16le", "pcm_s24le", "pcm_s16be"),
        Container.THREE_GP to setOf("aac", "amr_nb", "amr_wb"),
        Container.MPEG_TS to setOf("aac", "mp3", "mp2", "ac3", "eac3", "opus", "dts"),
    )

    /** Source video codecs (FFmpeg names) that may be stream-copied (remuxed). */
    private val videoCopyByContainer: Map<Container, Set<String>> = mapOf(
        Container.MP4 to setOf("h264", "hevc", "av1", "vp9", "mpeg4", "mpeg2video"),
        Container.MKV to setOf("h264", "hevc", "av1", "vp9", "vp8", "mpeg4", "mpeg2video", "mpeg1video", "vc1", "theora", "ffv1", "prores", "mjpeg", "msmpeg4v3", "wmv3", "h263"),
        Container.WEBM to setOf("vp9", "vp8", "av1"),
        Container.MOV to setOf("h264", "hevc", "mpeg4", "prores", "mjpeg", "dnxhd"),
        Container.THREE_GP to setOf("h264", "mpeg4", "h263"),
        Container.MPEG_TS to setOf("h264", "hevc", "mpeg2video"),
    )

    /** Subtitle output per container: target codec for text subtitles, and whether bitmap subs can be copied. */
    data class SubtitleSupport(val textCodec: String?, val copyCodecs: Set<String>, val bitmapCopy: Boolean)

    private val subtitles: Map<Container, SubtitleSupport> = mapOf(
        Container.MP4 to SubtitleSupport("mov_text", setOf("mov_text"), false),
        Container.MOV to SubtitleSupport("mov_text", setOf("mov_text"), false),
        Container.MKV to SubtitleSupport("subrip", setOf("subrip", "srt", "ass", "ssa", "webvtt", "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "text"), true),
        Container.WEBM to SubtitleSupport("webvtt", setOf("webvtt"), false),
        Container.THREE_GP to SubtitleSupport("mov_text", setOf("mov_text"), false),
        Container.MPEG_TS to SubtitleSupport(null, emptySet(), false),
    )

    fun videoCodecs(c: Container): Set<VideoCodec> = videoByContainer[c].orEmpty()
    fun audioCodecs(c: Container): Set<AudioCodec> = audioByContainer[c].orEmpty()
    fun supports(c: Container, v: VideoCodec) = v in videoCodecs(c)
    fun supports(c: Container, a: AudioCodec) = a in audioCodecs(c)
    fun canCopyAudio(c: Container, sourceCodec: String) = sourceCodec in audioCopyByContainer[c].orEmpty()
    fun canCopyVideo(c: Container, sourceCodec: String) = sourceCodec in videoCopyByContainer[c].orEmpty()
    fun subtitleSupport(c: Container): SubtitleSupport = subtitles.getValue(c)
    fun supportsChapters(c: Container) = c == Container.MP4 || c == Container.MKV || c == Container.MOV || c == Container.WEBM
    fun supportsMultipleAudio(c: Container) = c != Container.THREE_GP
    fun supportsAttachments(c: Container) = c == Container.MKV

    /** Which containers can hold a codec (for "Choose: …" suggestions). */
    fun containersFor(v: VideoCodec): List<Container> = Container.entries.filter { supports(it, v) }
    fun containersFor(a: AudioCodec): List<Container> = Container.entries.filter { supports(it, a) }
}
