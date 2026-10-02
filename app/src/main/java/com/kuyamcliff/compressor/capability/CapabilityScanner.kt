package com.kuyamcliff.compressor.capability

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Discovers real codec capabilities via MediaCodecList and caches them per
 * Build.FINGERPRINT (an OS update can change codecs, so the cache is keyed by it).
 */
class CapabilityScanner(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheFile: File get() = File(context.filesDir, "device_capabilities.json")

    fun loadOrScan(forceRescan: Boolean = false): DeviceCapabilities {
        if (!forceRescan) {
            runCatching {
                val cached = json.decodeFromString(DeviceCapabilities.serializer(), cacheFile.readText())
                if (cached.fingerprint == Build.FINGERPRINT && cached.codecs.isNotEmpty()) return cached
            }
        }
        val caps = scan()
        runCatching { cacheFile.writeText(json.encodeToString(DeviceCapabilities.serializer(), caps)) }
        return caps
    }

    fun scan(): DeviceCapabilities {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val codecs = mutableListOf<CodecCapability>()
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (info.isAlias) continue
            for (type in info.supportedTypes) {
                val mime = type.lowercase()
                if (mime !in INTERESTING_MIMES) continue
                runCatching { codecs += describe(info, mime) }
                    .onFailure { Log.w(TAG, "skipping ${info.name}/$mime: ${it.message}") }
            }
        }
        return DeviceCapabilities(
            fingerprint = Build.FINGERPRINT,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "",
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "",
            abis = Build.SUPPORTED_ABIS.toList(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuMaxFreqKhz = readCpuFrequencies(),
            totalRamBytes = mem.totalMem,
            lowRamDevice = am.isLowRamDevice,
            glRenderer = queryGlRenderer(),
            codecs = codecs,
            scannedAtMs = System.currentTimeMillis(),
        )
    }

    private fun describe(info: MediaCodecInfo, mime: String): CodecCapability {
        val caps = info.getCapabilitiesForType(mime)
        val vc = caps.videoCapabilities
        val ec: EncoderCapabilities? = if (info.isEncoder) caps.encoderCapabilities else null
        val profiles = caps.profileLevels.map { ProfileLevel(it.profile, it.level) }
        val profileSet = profiles.map { it.profile }.toSet()
        val rateModes = buildList {
            if (ec != null) {
                if (ec.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CQ)) add("cq")
                if (ec.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_VBR)) add("vbr")
                if (ec.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR)) add("cbr")
                if (Build.VERSION.SDK_INT >= 31 && ec.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR_FD)) add("cbr_fd")
            }
        }
        val probed = if (vc != null) PROBE_MODES.map { (w, h, fps) ->
            // Probe both orientations: some codecs only list landscape sizes.
            val ok = runCatching { vc.areSizeAndRateSupported(w, h, fps) || vc.areSizeAndRateSupported(h, w, fps) }.getOrDefault(false)
            ProbedMode(w, h, fps, ok)
        } else emptyList()
        val hdrFeature = Build.VERSION.SDK_INT >= 33 && info.isEncoder &&
            caps.isFeatureSupported(CodecCapabilities.FEATURE_HdrEditing)
        return CodecCapability(
            name = info.name,
            mime = mime,
            encoder = info.isEncoder,
            hardwareAccelerated = info.isHardwareAccelerated,
            softwareOnly = info.isSoftwareOnly,
            vendor = info.isVendor,
            alias = info.isAlias,
            surfaceInput = info.isEncoder && CodecCapabilities.COLOR_FormatSurface in caps.colorFormats,
            supports10Bit = profileSet.any { it in CodecProfiles.tenBitProfiles(mime) },
            supportsHdr = hdrFeature || profileSet.any { it in CodecProfiles.hdrProfiles(mime) },
            supportsBFrames = null, // not exposed by the platform; never assumed
            maxWidth = vc?.supportedWidths?.upper ?: 0,
            maxHeight = vc?.supportedHeights?.upper ?: 0,
            widthAlignment = vc?.widthAlignment ?: 2,
            heightAlignment = vc?.heightAlignment ?: 2,
            maxFps = vc?.supportedFrameRates?.upper?.toDouble() ?: 0.0,
            bitrateMin = vc?.bitrateRange?.lower ?: 0,
            bitrateMax = vc?.bitrateRange?.upper ?: 0,
            profiles = profiles,
            colorFormats = caps.colorFormats.toList(),
            rateControls = rateModes,
            qualityMin = ec?.qualityRange?.lower ?: 0,
            qualityMax = ec?.qualityRange?.upper ?: 0,
            complexityMin = ec?.complexityRange?.lower ?: 0,
            complexityMax = ec?.complexityRange?.upper ?: 0,
            maxInstances = caps.maxSupportedInstances,
            qpBounds = Build.VERSION.SDK_INT >= 31 && info.isEncoder && caps.isFeatureSupported(CodecCapabilities.FEATURE_QpBounds),
            probedModes = probed,
        )
    }

    private fun readCpuFrequencies(): List<Long> = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
        runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
    }

    /** Creates a throw-away 1x1 pbuffer context only to read GL_RENDERER for diagnostics. */
    private fun queryGlRenderer(): String = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, ctx)
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: ""
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, ctx)
        EGL14.eglTerminate(display)
        "$vendor $renderer".trim()
    }.getOrDefault("")

    companion object {
        private const val TAG = "CapabilityScanner"
        val INTERESTING_MIMES = setOf(
            "video/avc", "video/hevc", "video/av01", "video/x-vnd.on2.vp9", "video/x-vnd.on2.vp8",
            "video/mp4v-es", "video/mpeg2", "video/3gpp",
        )
        private val PROBE_MODES = listOf(
            Triple(7680, 4320, 30.0), Triple(3840, 2160, 60.0), Triple(3840, 2160, 30.0), Triple(2560, 1440, 60.0),
            Triple(2560, 1440, 30.0), Triple(1920, 1080, 120.0), Triple(1920, 1080, 60.0), Triple(1920, 1080, 30.0),
            Triple(1280, 720, 120.0), Triple(1280, 720, 60.0), Triple(1280, 720, 30.0), Triple(854, 480, 30.0),
        )
    }
}
