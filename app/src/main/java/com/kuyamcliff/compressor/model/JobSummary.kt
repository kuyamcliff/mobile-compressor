package com.kuyamcliff.compressor.model

import kotlinx.serialization.Serializable

/** Human-facing before/after description stored with a job (for the queue, report and history). */
@Serializable
data class JobSummary(
    val sourceCodec: String = "",
    val sourceResolution: String = "",
    val sourceFps: Double = 0.0,
    val sourceAudio: String = "",
    val sourceHdr: String = "sdr",
    val outputCodec: String = "",
    val outputResolution: String = "",
    val outputFps: Double = 0.0,
    val outputAudio: String = "",
    val pipeline: String = "",
    val encoderDisplay: String = "",
    val hwEncoder: String? = null,
    val rateControl: String = "",
    val quality: String = "",
    val container: String = "",
    val estimateLow: Long = 0,
    val estimateHigh: Long = 0,
    val targetBytes: Long = 0,
    val durationUs: Long = 0,
    val notes: List<String> = emptyList(),
)
