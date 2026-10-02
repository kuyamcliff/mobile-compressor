package com.kuyamcliff.compressor.engine

import kotlinx.serialization.Serializable

/** Event names emitted by the native job (vc::ev, JobControl.h). */
object EngineEvents {
    const val JOB_CREATED = "JOB_CREATED"
    const val ANALYSIS_STARTED = "ANALYSIS_STARTED"
    const val ANALYSIS_COMPLETE = "ANALYSIS_COMPLETE"
    const val PIPELINE_SELECTED = "PIPELINE_SELECTED"
    const val ENCODE_STARTED = "ENCODE_STARTED"
    const val PROGRESS = "PROGRESS"
    const val PAUSED = "PAUSED"
    const val RESUMED = "RESUMED"
    const val THERMAL_WARNING = "THERMAL_WARNING"
    const val STORAGE_WARNING = "STORAGE_WARNING"
    const val ENCODE_COMPLETE = "ENCODE_COMPLETE"
    const val VALIDATION_STARTED = "VALIDATION_STARTED"
    const val VALIDATION_COMPLETE = "VALIDATION_COMPLETE"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

/** Progress snapshot computed natively from encoded timestamps (ProgressTracker). */
@Serializable
data class ProgressStats(
    val progress: Double = -1.0,
    val currentFps: Double = 0.0,
    val averageFps: Double = 0.0,
    val speed: Double = 0.0,
    val etaUs: Long = -1,
    val elapsedUs: Long = 0,
    val mediaUs: Long = 0,
    val frames: Long = 0,
    val outputBytes: Long = 0,
    val projectedBytes: Long = -1,
    val outputBitrate: Long = 0,
    val inputBitrate: Long = 0,
    val pass: Int = 0,
    val passes: Int = 1,
    // Present on ENCODE_COMPLETE:
    val sourceSegmentBytes: Long = 0,
    val mediaDurationUs: Long = 0,
    val wallTimeUs: Long = 0,
    val pipeline: String = "",
    val encoder: String = "",
    val decodeErrors: Long = 0,
    val warnings: List<String> = emptyList(),
) {
    val indeterminate: Boolean get() = progress < 0
}

@Serializable
data class ValidationCheck(val name: String = "", val ok: Boolean = false, val detail: String = "")

@Serializable
data class ValidationResult(val ok: Boolean = false, val checks: List<ValidationCheck> = emptyList())

@Serializable
data class QualityMetrics(val psnr: Double = 0.0, val ssim: Double = 0.0, val ssimDb: Double = 0.0, val frames: Int = 0)

@Serializable
data class ComplexityReport(
    val frames: Int = 0,
    val analysedSeconds: Double = 0.0,
    val motion: Double = 0.0,
    val detail: Double = 0.0,
    val brightness: Double = 0.0,
    val darkFraction: Double = 0.0,
    val noise: Double = 0.0,
    val sceneChangesPerMinute: Double = 0.0,
    val score: Double = 0.5,
    @kotlinx.serialization.SerialName("class") val complexityClass: String = "medium",
)

/** Native job lifecycle (vc::JobState). */
enum class NativeJobState(val code: Int) {
    CREATED(0), RUNNING(1), PAUSED(2), VALIDATING(3), COMPLETED(4), FAILED(5), CANCELLED(6), UNKNOWN(-1);

    companion object {
        fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: UNKNOWN
    }
}
