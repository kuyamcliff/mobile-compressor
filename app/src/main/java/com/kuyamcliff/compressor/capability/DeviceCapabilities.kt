package com.kuyamcliff.compressor.capability

import kotlinx.serialization.Serializable

/**
 * Snapshot of what this device can actually do, collected from MediaCodecList
 * and system services (never assumed from the Android version or model name).
 * Cached per build fingerprint by [CapabilityScanner].
 */
@Serializable
data class DeviceCapabilities(
    val fingerprint: String = "",
    val androidVersion: String = "",
    val sdkInt: Int = 0,
    val manufacturer: String = "",
    val model: String = "",
    val socManufacturer: String = "",
    val socModel: String = "",
    val abis: List<String> = emptyList(),
    val cpuCores: Int = 0,
    val cpuMaxFreqKhz: List<Long> = emptyList(),
    val totalRamBytes: Long = 0,
    val lowRamDevice: Boolean = false,
    val glRenderer: String = "",
    val codecs: List<CodecCapability> = emptyList(),
    val scannedAtMs: Long = 0,
) {
    val encoders: List<CodecCapability> get() = codecs.filter { it.encoder }
    val decoders: List<CodecCapability> get() = codecs.filter { !it.encoder }
    val isLowEnd: Boolean get() = lowRamDevice || totalRamBytes in 1 until 3L * 1024 * 1024 * 1024 || cpuCores in 1..4
}

@Serializable
data class CodecCapability(
    val name: String,
    val mime: String,
    val encoder: Boolean,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val vendor: Boolean,
    val alias: Boolean = false,
    val surfaceInput: Boolean = false,
    val supports10Bit: Boolean = false,
    val supportsHdr: Boolean = false,
    val supportsBFrames: Boolean? = null,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val widthAlignment: Int = 2,
    val heightAlignment: Int = 2,
    val maxFps: Double = 0.0,
    val bitrateMin: Int = 0,
    val bitrateMax: Int = 0,
    val profiles: List<ProfileLevel> = emptyList(),
    val colorFormats: List<Int> = emptyList(),
    val rateControls: List<String> = emptyList(), // "cq", "vbr", "cbr", "cbr_fd"
    val qualityMin: Int = 0,
    val qualityMax: Int = 0,
    val complexityMin: Int = 0,
    val complexityMax: Int = 0,
    val maxInstances: Int = 0,
    val qpBounds: Boolean = false,
    /** Achievable size/fps points measured by the vendor (API 29+ performance points). */
    val performancePoints: List<PerformancePoint> = emptyList(),
    /** Sizes probed with VideoCapabilities.areSizeAndRateSupported. */
    val probedModes: List<ProbedMode> = emptyList(),
) {
    val supportsCq: Boolean get() = "cq" in rateControls
}

@Serializable
data class ProfileLevel(val profile: Int, val level: Int)

@Serializable
data class PerformancePoint(val width: Int, val height: Int, val fps: Int)

@Serializable
data class ProbedMode(val width: Int, val height: Int, val fps: Double, val supported: Boolean)

/** MediaCodecInfo.CodecProfileLevel constants needed by the planner. */
object CodecProfiles {
    const val AVC_BASELINE = 0x01
    const val AVC_MAIN = 0x02
    const val AVC_HIGH = 0x08
    const val AVC_CONSTRAINED_BASELINE = 0x10000
    const val HEVC_MAIN = 0x01
    const val HEVC_MAIN10 = 0x02
    const val HEVC_MAIN10_HDR10 = 0x1000
    const val HEVC_MAIN10_HDR10_PLUS = 0x2000
    const val VP9_0 = 0x01
    const val VP9_2 = 0x04
    const val VP9_2_HDR = 0x1000
    const val AV1_MAIN8 = 0x1
    const val AV1_MAIN10 = 0x2
    const val AV1_MAIN10_HDR10 = 0x1000

    fun tenBitProfiles(mime: String): Set<Int> = when (mime) {
        "video/hevc" -> setOf(HEVC_MAIN10, HEVC_MAIN10_HDR10, HEVC_MAIN10_HDR10_PLUS)
        "video/x-vnd.on2.vp9" -> setOf(VP9_2, VP9_2_HDR, 0x08, 0x2000, 0x4000, 0x8000)
        "video/av01" -> setOf(AV1_MAIN10, AV1_MAIN10_HDR10, 0x2000)
        else -> emptySet()
    }

    fun hdrProfiles(mime: String): Set<Int> = when (mime) {
        "video/hevc" -> setOf(HEVC_MAIN10_HDR10, HEVC_MAIN10_HDR10_PLUS)
        "video/x-vnd.on2.vp9" -> setOf(VP9_2_HDR, 0x2000, 0x4000, 0x8000)
        "video/av01" -> setOf(AV1_MAIN10_HDR10, 0x2000)
        else -> emptySet()
    }

    fun name(mime: String, profile: Int): String = when (mime) {
        "video/avc" -> when (profile) {
            AVC_BASELINE -> "Baseline"; AVC_MAIN -> "Main"; AVC_HIGH -> "High"; AVC_CONSTRAINED_BASELINE -> "Constrained Baseline"
            0x04 -> "Extended"; 0x10 -> "High 10"; 0x80000 -> "Constrained High"; else -> "0x" + profile.toString(16)
        }
        "video/hevc" -> when (profile) {
            HEVC_MAIN -> "Main"; HEVC_MAIN10 -> "Main 10"; 0x04 -> "Main Still"; HEVC_MAIN10_HDR10 -> "Main 10 HDR10"
            HEVC_MAIN10_HDR10_PLUS -> "Main 10 HDR10+"; else -> "0x" + profile.toString(16)
        }
        "video/av01" -> when (profile) {
            AV1_MAIN8 -> "Main 8"; AV1_MAIN10 -> "Main 10"; AV1_MAIN10_HDR10 -> "Main 10 HDR10"; else -> "0x" + profile.toString(16)
        }
        "video/x-vnd.on2.vp9" -> when (profile) {
            VP9_0 -> "Profile 0"; 0x02 -> "Profile 1"; VP9_2 -> "Profile 2"; 0x08 -> "Profile 3"; VP9_2_HDR -> "Profile 2 HDR"
            else -> "0x" + profile.toString(16)
        }
        else -> "0x" + profile.toString(16)
    }
}
