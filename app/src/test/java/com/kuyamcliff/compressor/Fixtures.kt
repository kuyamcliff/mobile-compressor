package com.kuyamcliff.compressor

import com.kuyamcliff.compressor.capability.CapabilityResolver
import com.kuyamcliff.compressor.capability.CodecCapability
import com.kuyamcliff.compressor.capability.DeviceCapabilities
import com.kuyamcliff.compressor.domain.PlanContext
import com.kuyamcliff.compressor.model.AudioStreamInfo
import com.kuyamcliff.compressor.model.ChapterInfo
import com.kuyamcliff.compressor.model.FormatInfo
import com.kuyamcliff.compressor.model.SourceInfo
import com.kuyamcliff.compressor.model.StreamInfo
import com.kuyamcliff.compressor.model.SubtitleStreamInfo
import com.kuyamcliff.compressor.model.TimingInfo
import com.kuyamcliff.compressor.model.VideoStreamInfo

object Fixtures {
    fun phoneVideo(
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 30,
        durationSec: Int = 60,
        codec: String = "hevc",
        bitrate: Long = 16_000_000,
        hdr: String = "sdr",
        bitDepth: Int = 8,
        vfr: Boolean = false,
        subtitles: Boolean = false,
        rotation: Int = 0,
    ): SourceInfo {
        val dur = durationSec * 1_000_000L
        val streams = mutableListOf(
            StreamInfo(
                index = 0, type = "video", codec = codec, bitrate = bitrate, durationUs = dur,
                video = VideoStreamInfo(
                    width = width, height = height, rotation = rotation,
                    displayWidth = if (rotation % 180 != 0) height else width, displayHeight = if (rotation % 180 != 0) width else height,
                    fpsNum = fps, fpsDen = 1, rFpsNum = fps, rFpsDen = 1, pixFmt = if (bitDepth > 8) "yuv420p10le" else "yuv420p",
                    bitDepth = bitDepth, chroma = "4:2:0", hdr = hdr,
                    colorTransfer = if (hdr == "hlg") "arib-std-b67" else if (hdr == "pq") "smpte2084" else "bt709",
                ),
                timing = TimingInfo(vfr = vfr, sampledFps = fps.toDouble()),
            ),
            StreamInfo(index = 1, type = "audio", codec = "aac", bitrate = 192_000, language = "eng", default = true, durationUs = dur,
                audio = AudioStreamInfo(sampleRate = 48000, channels = 2, channelLayout = "stereo")),
        )
        if (subtitles) {
            streams += StreamInfo(index = 2, type = "subtitle", codec = "subrip", language = "eng", subtitle = SubtitleStreamInfo(bitmap = false, text = true))
            streams += StreamInfo(index = 3, type = "subtitle", codec = "hdmv_pgs_subtitle", language = "fra", subtitle = SubtitleStreamInfo(bitmap = true, text = false))
        }
        return SourceInfo(
            format = FormatInfo(name = "mov,mp4,m4a,3gp,3g2,mj2", durationUs = dur, bitrate = bitrate + 192_000, size = (bitrate + 192_000) / 8 * durationSec,
                chapters = listOf(ChapterInfo(0, 0, dur / 2, "One"), ChapterInfo(1, dur / 2, dur, "Two"))),
            streams = streams,
            primaryVideoIndex = 0,
        )
    }

    fun ctx(src: SourceInfo = phoneVideo(), resolver: CapabilityResolver? = null) =
        PlanContext(source = src, sourceSizeBytes = src.format.size, resolver = resolver, scratchDir = "/tmp", fontsDir = "/tmp/fonts", fallbackFont = "Roboto")

    private const val NV12 = 21

    fun hwEncoder(name: String, mime: String, maxW: Int = 3840, maxH: Int = 2160, surface: Boolean = true) = CodecCapability(
        name = name, mime = mime, encoder = true, hardwareAccelerated = true, softwareOnly = false, vendor = true,
        surfaceInput = surface, maxWidth = maxW, maxHeight = maxH, maxFps = 60.0, bitrateMin = 1, bitrateMax = 100_000_000,
        colorFormats = listOf(NV12, 0x7F000789), rateControls = listOf("vbr", "cbr", "cq"), maxInstances = 4,
    )

    fun hwDecoder(name: String, mime: String) = CodecCapability(
        name = name, mime = mime, encoder = false, hardwareAccelerated = true, softwareOnly = false, vendor = true,
        maxWidth = 3840, maxHeight = 2160, maxFps = 60.0,
    )

    /** A typical mid-range device: HW AVC + HEVC encoders, no HW AV1/VP9 encoder. */
    fun resolver(sizeOk: Boolean? = true): CapabilityResolver = CapabilityResolver(
        DeviceCapabilities(
            fingerprint = "test", sdkInt = 34, cpuCores = 8, totalRamBytes = 8L shl 30,
            codecs = listOf(
                hwEncoder("c2.vendor.avc.encoder", "video/avc"),
                hwEncoder("c2.vendor.hevc.encoder", "video/hevc"),
                hwDecoder("c2.vendor.avc.decoder", "video/avc"),
                hwDecoder("c2.vendor.hevc.decoder", "video/hevc"),
            ),
        ),
    ) { _, _, _, _, _ -> sizeOk }
}
