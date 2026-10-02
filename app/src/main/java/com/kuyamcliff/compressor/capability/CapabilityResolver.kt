package com.kuyamcliff.compressor.capability

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList

/** Answers "can codec X do W x H @ fps?" precisely (live query on device, fake in tests). */
fun interface SizeChecker {
    fun supports(codecName: String, mime: String, width: Int, height: Int, fps: Double): Boolean?
}

/** Live checker backed by MediaCodecInfo.VideoCapabilities.areSizeAndRateSupported. */
class MediaCodecSizeChecker : SizeChecker {
    private val infos by lazy { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.associateBy { it.name } }
    override fun supports(codecName: String, mime: String, width: Int, height: Int, fps: Double): Boolean? = runCatching {
        val vc = infos[codecName]?.getCapabilitiesForType(mime)?.videoCapabilities ?: return null
        vc.areSizeAndRateSupported(width, height, fps) || vc.areSizeAndRateSupported(height, width, fps)
    }.getOrNull()
}

data class HwEncoderChoice(
    val codec: CodecCapability,
    val surfaceInput: Boolean,
    val colorFormat: Int,
    val bitrateMode: Int,      // MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_*
    val supportsCq: Boolean,
)

sealed interface HwResolution {
    data class Available(val choice: HwEncoderChoice) : HwResolution
    data class Unavailable(val reasons: List<String>) : HwResolution
}

/**
 * Picks hardware codecs for a requested configuration from real capabilities.
 * Reasons for rejection are kept so the UI can explain exactly why hardware
 * is unavailable (PRD §19, §93, §183).
 */
class CapabilityResolver(private val caps: DeviceCapabilities, private val sizeChecker: SizeChecker) {

    fun hardwareEncoders(mime: String): List<CodecCapability> =
        caps.encoders.filter { it.mime == mime && it.hardwareAccelerated && !it.softwareOnly }

    fun hardwareDecoders(mime: String): List<CodecCapability> =
        caps.decoders.filter { it.mime == mime && it.hardwareAccelerated && !it.softwareOnly }

    /** Any platform decoder (hardware first, then Android's software decoders, which still output to a Surface). */
    fun surfaceDecoders(mime: String): List<CodecCapability> =
        caps.decoders.filter { it.mime == mime }.sortedByDescending { it.hardwareAccelerated }

    fun resolveEncoder(
        mime: String,
        width: Int,
        height: Int,
        fps: Double,
        tenBit: Boolean,
        needSurface: Boolean,
        codecLabel: String,
    ): HwResolution {
        val candidates = hardwareEncoders(mime)
        if (candidates.isEmpty()) return HwResolution.Unavailable(listOf("This device has no hardware $codecLabel encoder."))
        val reasons = mutableListOf<String>()
        for (c in candidates) {
            if (tenBit) {
                reasons += "The hardware $codecLabel encoder (${c.name}) path only produces 8-bit SDR output in this app."
                continue
            }
            if (width > c.maxWidth.coerceAtLeast(c.maxHeight) || height > c.maxWidth.coerceAtLeast(c.maxHeight)) {
                reasons += "${c.name} supports at most ${c.maxWidth}x${c.maxHeight}."
                continue
            }
            if (width % c.widthAlignment != 0 || height % c.heightAlignment != 0) {
                reasons += "${c.name} requires dimensions aligned to ${c.widthAlignment}x${c.heightAlignment}."
                continue
            }
            val ok = sizeChecker.supports(c.name, mime, width, height, fps)
            if (ok == false) {
                reasons += "${c.name} does not support ${width}x$height at ${"%.2f".format(fps)} fps."
                continue
            }
            val surface = c.surfaceInput
            if (needSurface && !surface) {
                reasons += "${c.name} does not accept Surface input."
                continue
            }
            val colorFormat = when {
                CodecCapabilities.COLOR_FormatYUV420SemiPlanar in c.colorFormats -> CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                CodecCapabilities.COLOR_FormatYUV420Planar in c.colorFormats -> CodecCapabilities.COLOR_FormatYUV420Planar
                else -> -1
            }
            if (!needSurface && colorFormat < 0) {
                reasons += "${c.name} accepts no buffer format the app can produce (NV12/I420)."
                continue
            }
            val mode = when {
                "vbr" in c.rateControls -> BITRATE_MODE_VBR
                "cbr" in c.rateControls -> BITRATE_MODE_CBR
                else -> BITRATE_MODE_VBR
            }
            return HwResolution.Available(HwEncoderChoice(c, surface, colorFormat, mode, c.supportsCq))
        }
        return HwResolution.Unavailable(reasons.distinct())
    }

    fun resolveDecoder(mime: String, width: Int, height: Int, fps: Double): CodecCapability? =
        hardwareDecoders(mime).firstOrNull { d ->
            width <= maxOf(d.maxWidth, d.maxHeight) && height <= maxOf(d.maxWidth, d.maxHeight) &&
                sizeChecker.supports(d.name, mime, width, height, fps.coerceAtLeast(1.0)) != false
        }

    /** Picks profile/level constants for a hardware encoder (8-bit SDR). */
    fun profileFor(choice: HwEncoderChoice, requested: String?): Int {
        val profiles = choice.codec.profiles.map { it.profile }.toSet()
        val mime = choice.codec.mime
        val wanted = when (mime) {
            "video/avc" -> when (requested?.lowercase()) {
                "baseline", "constrained_baseline" -> listOf(CodecProfiles.AVC_CONSTRAINED_BASELINE, CodecProfiles.AVC_BASELINE)
                "main" -> listOf(CodecProfiles.AVC_MAIN)
                else -> listOf(CodecProfiles.AVC_HIGH, CodecProfiles.AVC_MAIN, CodecProfiles.AVC_BASELINE)
            }
            "video/hevc" -> listOf(CodecProfiles.HEVC_MAIN)
            "video/av01" -> listOf(CodecProfiles.AV1_MAIN8)
            "video/x-vnd.on2.vp9" -> listOf(CodecProfiles.VP9_0)
            else -> emptyList()
        }
        return wanted.firstOrNull { it in profiles } ?: -1
    }

    companion object {
        const val BITRATE_MODE_CQ = 0
        const val BITRATE_MODE_VBR = 1
        const val BITRATE_MODE_CBR = 2
    }
}
