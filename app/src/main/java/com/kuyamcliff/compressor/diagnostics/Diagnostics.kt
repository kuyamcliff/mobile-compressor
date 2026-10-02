package com.kuyamcliff.compressor.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.kuyamcliff.compressor.BuildConfig
import com.kuyamcliff.compressor.capability.CodecProfiles
import com.kuyamcliff.compressor.capability.DeviceCapabilities
import com.kuyamcliff.compressor.data.storage.AppFiles
import com.kuyamcliff.compressor.queue.DeviceState
import com.kuyamcliff.compressor.util.Format
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the engine was doing; written at job start/finish so a native crash can be explained later. */
@Serializable
data class Breadcrumb(
    val jobId: Long,
    val stage: String,
    val codec: String,
    val pipeline: String,
    val encoder: String,
    val startedAt: Long,
    val lastError: String? = null,
)

/**
 * Local-only diagnostics and crash reports (PRD §87–89). Reports never include
 * video data or file contents; source names and paths are excluded unless the
 * user explicitly exports a debug report.
 */
class Diagnostics(private val context: Context, private val files: AppFiles) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val breadcrumbFile get() = File(files.crashes, "breadcrumb.json")

    fun installCrashHandler(enabled: () -> Boolean) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (enabled()) runCatching { writeCrash("java", stackTrace(e), t.name) }
            previous?.uncaughtException(t, e)
        }
    }

    fun breadcrumb(b: Breadcrumb?) {
        runCatching { if (b == null) breadcrumbFile.delete() else breadcrumbFile.writeText(json.encodeToString(Breadcrumb.serializer(), b)) }
    }

    /**
     * On start-up: if the previous process died natively (or was killed) while a
     * job was active, write a crash report from ApplicationExitInfo + breadcrumb.
     */
    fun collectPreviousExit(enabled: Boolean): String? {
        val crumb = runCatching { json.decodeFromString(Breadcrumb.serializer(), breadcrumbFile.readText()) }.getOrNull()
        breadcrumbFile.delete()
        if (Build.VERSION.SDK_INT < 30 || !enabled) return null
        val am = context.getSystemService(ActivityManager::class.java)
        val info = runCatching { am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull() ?: return null
        val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        if (prefs.getLong("last_exit_ts", 0) == info.timestamp) return null
        prefs.edit().putLong("last_exit_ts", info.timestamp).apply()
        if (info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE && info.reason != ApplicationExitInfo.REASON_CRASH &&
            info.reason != ApplicationExitInfo.REASON_ANR && crumb == null
        ) return null
        if (info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE && info.reason != ApplicationExitInfo.REASON_ANR) return null
        val trace = runCatching {
            info.traceInputStream?.bufferedReader()?.useLines { it.take(300).joinToString("\n") }
        }.getOrNull().orEmpty()
        val body = buildString {
            appendLine("Exit reason: ${reasonName(info.reason)} (${info.description ?: ""})")
            appendLine("Time: ${Date(info.timestamp)}")
            crumb?.let {
                appendLine("Job: #${it.jobId} stage=${it.stage} codec=${it.codec} pipeline=${it.pipeline} encoder=${it.encoder}")
                it.lastError?.let { e -> appendLine("Last native error: $e") }
            }
            appendLine()
            append(trace)
        }
        return writeCrash(if (info.reason == ApplicationExitInfo.REASON_ANR) "anr" else "native", body, null)
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        else -> "other ($r)"
    }

    private fun stackTrace(e: Throwable) = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    private fun writeCrash(kind: String, body: String, thread: String?): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(files.crashes, "crash_${kind}_$ts.txt")
        val crumb = runCatching { breadcrumbFile.readText() }.getOrNull()
        f.writeText(buildString {
            appendLine("Compressor crash report ($kind)")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("FFmpeg: ${BuildConfig.FFMPEG_VERSION}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
            thread?.let { appendLine("Thread: $it") }
            crumb?.let { appendLine("Active job: $it") }
            appendLine()
            append(body)
        })
        // Keep at most 20 reports.
        files.crashes.listFiles()?.filter { it.name.startsWith("crash_") }?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        return f.absolutePath
    }

    fun crashReports(): List<File> = files.crashes.listFiles()?.filter { it.name.startsWith("crash_") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun clearCrashReports() = crashReports().forEach { it.delete() }

    /** Full diagnostics text (no user video data, no file names). */
    fun report(caps: DeviceCapabilities?, buildInfo: JsonObject?, device: DeviceState, tempBytes: Long): String = buildString {
        appendLine("== Compressor diagnostics ==")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        caps?.let { c ->
            if (c.socModel.isNotEmpty()) appendLine("SoC: ${c.socManufacturer} ${c.socModel}")
            appendLine("ABIs: ${c.abis.joinToString()}")
            appendLine("CPU cores: ${c.cpuCores}; max freq (MHz): ${c.cpuMaxFreqKhz.joinToString { (it / 1000).toString() }}")
            appendLine("GPU: ${c.glRenderer.ifEmpty { "unknown" }}")
            appendLine("RAM: ${Format.bytes(c.totalRamBytes)}${if (c.lowRamDevice) " (low-RAM device)" else ""}")
        }
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        appendLine("Memory available: ${Format.bytes(mi.availMem)}${if (mi.lowMemory) " (LOW)" else ""}; app memory class ${am.memoryClass} MB")
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        appendLine("Storage: ${Format.bytes(stat.availableBytes)} free of ${Format.bytes(stat.totalBytes)}; app temp files ${Format.bytes(tempBytes)}")
        appendLine("Thermal status: ${device.thermalStatus}${if (!device.thermalHeadroom.isNaN()) ", headroom ${"%.2f".format(device.thermalHeadroom)}" else ""}")
        appendLine("Battery: ${device.batteryPercent}%${if (device.charging) " charging" else ""}${if (device.powerSave) ", power saver" else ""}")
        appendLine()
        buildInfo?.let { b ->
            appendLine("== FFmpeg ==")
            appendLine("Version: ${b["ffmpegVersion"]?.jsonPrimitive?.content}")
            appendLine("License: ${b["license"]?.jsonPrimitive?.content}")
            appendLine("Libraries: ${b["libraries"]?.jsonObject?.entries?.joinToString { "${it.key} ${it.value.jsonPrimitive.content}" }}")
            appendLine("Encoders: ${b["encoders"]?.jsonArray?.joinToString { it.jsonObject["name"]!!.jsonPrimitive.content }}")
            appendLine("Decoders: ${b["decoders"]?.jsonArray?.size} enabled")
            appendLine("Muxers: ${b["muxers"]?.jsonArray?.joinToString { it.jsonPrimitive.content }}")
            appendLine("Filters: ${b["filters"]?.jsonArray?.size} enabled")
            appendLine("Configuration: ${b["configuration"]?.jsonPrimitive?.content}")
            appendLine()
        }
        caps?.let { c ->
            appendLine("== Video codecs (MediaCodecList) ==")
            for (codec in c.codecs.sortedWith(compareBy({ !it.encoder }, { it.mime }, { !it.hardwareAccelerated }))) {
                appendLine(
                    "${if (codec.encoder) "ENC" else "DEC"} ${codec.mime} ${codec.name} " +
                        "${if (codec.hardwareAccelerated) "hardware" else "software"} max ${codec.maxWidth}x${codec.maxHeight}@${codec.maxFps.toInt()} " +
                        "profiles=[${codec.profiles.map { CodecProfiles.name(codec.mime, it.profile) }.distinct().joinToString()}]" +
                        (if (codec.encoder) " rc=${codec.rateControls.joinToString("/")} surface=${codec.surfaceInput}" else "") +
                        (if (codec.supports10Bit) " 10-bit" else "") + (if (codec.supportsHdr) " HDR" else ""),
                )
            }
        }
    }
}
