package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.engine.ComplexityReport
import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.VideoCodec
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** How an estimate was derived; shown to the user next to every number. */
enum class EstimateBasis { MODEL, ANALYSIS, SAMPLE, CONTROLLED }

data class SizeRange(val lowBytes: Long, val highBytes: Long, val basis: EstimateBasis) {
    val midBytes: Long get() = (lowBytes + highBytes) / 2
}

enum class QualityRisk { LOW, MODERATE, HIGH, SEVERE, IMPOSSIBLE }

/**
 * Deterministic, explainable estimation (PRD §104, §152, §229). Nothing here is
 * a hard-coded answer: every value is computed from the source, the settings
 * and, when available, measurements (complexity analysis, preview samples,
 * observed encode speed on this device).
 *
 * Core model: bits per pixel per frame (bpp) needed for a given quality,
 * expressed in H.264-equivalent units and scaled by codec efficiency and
 * content complexity.
 */
object Estimator {

    /** H.264-equivalent bpp for a quality slider position (0 = smallest .. 1 = best). */
    fun bppForSlider(slider: Float): Double = 0.012 * 2.0.pow(4.5 * slider.coerceIn(0f, 1f))

    /** Inverse of [bppForSlider]. */
    fun sliderForBpp(bpp: Double): Float = (ln(max(bpp, 1e-6) / 0.012) / ln(2.0) / 4.5).toFloat().coerceIn(0f, 1f)

    /**
     * Content complexity multiplier (≈1 for typical footage). Measured analysis
     * wins; otherwise the source's own bitrate gives a weak hint.
     */
    fun complexityFactor(report: ComplexityReport?, sourceVideoBps: Long, srcPixelsPerSec: Double, srcCodec: String): Double {
        if (report != null) return 0.55 + report.score * 1.35
        if (sourceVideoBps <= 0 || srcPixelsPerSec <= 0) return 1.0
        val srcBppEq = sourceVideoBps / srcPixelsPerSec / Codecs.sourceEfficiency(srcCodec)
        // Typical consumer captures sit around 0.1–0.2 bpp (H.264-eq); scale gently.
        return (srcBppEq / 0.15).pow(0.35).coerceIn(0.7, 1.5)
    }

    fun containerOverheadBytes(container: Container, payloadBytes: Long, durationSec: Double): Long = when (container) {
        Container.MPEG_TS -> (payloadBytes * 0.05).roundToLong() + 20_000
        Container.MKV, Container.WEBM -> (payloadBytes * 0.008).roundToLong() + 20_000 + (durationSec * 12).roundToLong()
        else -> (payloadBytes * 0.004).roundToLong() + 30_000 + (durationSec * 8).roundToLong()
    }

    data class VideoModelInput(
        val width: Int,
        val height: Int,
        val fps: Double,
        val codec: VideoCodec,
        val hardware: Boolean,
        val slider: Float,
        val complexity: Double,
        val sourceVideoBps: Long,
        val sourcePixelsPerSec: Double,
        val sourceCodec: String,
    )

    fun efficiency(codec: VideoCodec, hardware: Boolean) =
        if (hardware) Codecs.hardwareEfficiency(codec) else Codecs.info(codec).efficiency

    /** Expected video bitrate (bits/s) for constant-quality encoding. */
    fun constantQualityBitrate(i: VideoModelInput): Long {
        val pps = i.width.toDouble() * i.height * i.fps
        var bps = pps * bppForSlider(i.slider) * efficiency(i.codec, i.hardware) * i.complexity
        // Below "visually lossless", re-encoding cannot sensibly exceed what the
        // source spends on the same pixels (adjusted for codec efficiency).
        if (i.slider < 0.9f && i.sourceVideoBps > 0 && i.sourcePixelsPerSec > 0) {
            val cap = i.sourceVideoBps * (pps / i.sourcePixelsPerSec) *
                (efficiency(i.codec, i.hardware) / Codecs.sourceEfficiency(i.sourceCodec)) * 1.15
            bps = min(bps, cap)
        }
        return max(bps, 20_000.0).roundToLong()
    }

    fun rangeFor(midBytes: Long, basis: EstimateBasis): SizeRange {
        val (lo, hi) = when (basis) {
            EstimateBasis.MODEL -> 0.55 to 1.6
            EstimateBasis.ANALYSIS -> 0.72 to 1.35
            EstimateBasis.SAMPLE -> 0.85 to 1.18
            EstimateBasis.CONTROLLED -> 0.94 to 1.05
        }
        return SizeRange((midBytes * lo).roundToLong(), (midBytes * hi).roundToLong(), basis)
    }

    /** Fraction of size saved by reducing the frame rate (inter-frame redundancy makes it sub-linear). */
    fun fpsSavingRange(sourceFps: Double, targetFps: Double): Pair<Double, Double> {
        if (targetFps >= sourceFps || sourceFps <= 0) return 0.0 to 0.0
        val r = targetFps / sourceFps
        return (1 - r.pow(0.35)) to (1 - r.pow(0.55))
    }

    /** Encode-time estimate from measured throughput (pixels/s) when available. */
    fun encodeSeconds(outPixelsPerSec: Double, durationSec: Double, measuredPixelsPerSec: Double?): Double? {
        val rate = measuredPixelsPerSec ?: return null
        if (rate <= 0) return null
        return outPixelsPerSec * durationSec / rate
    }
}

data class TargetBudget(
    val targetBytes: Long,
    val overheadBytes: Long,
    val audioBytes: Long,
    val subtitleBytes: Long,
    val videoBytes: Long,
    val videoKbps: Int,
) {
    val audioShare: Double get() = if (targetBytes > 0) audioBytes.toDouble() / targetBytes else 0.0
}

data class ResolutionOption(val label: String, val size: Size, val risk: QualityRisk, val bppEq: Double)

data class Feasibility(
    val risk: QualityRisk,
    val bppEq: Double,
    val recommendedTargetBytes: Long,
    val reasons: List<String>,
    val resolutionOptions: List<ResolutionOption>,
)

/** Target-size engine (PRD §13, §63, §64). */
object TargetSize {
    const val MIN_VIDEO_KBPS = 40

    fun budget(
        targetBytes: Long,
        durationSec: Double,
        audioKbpsTotal: Int,
        subtitleTracks: Int,
        container: Container,
    ): TargetBudget {
        val audioBytes = (audioKbpsTotal * 1000.0 / 8 * durationSec).roundToLong()
        val subBytes = subtitleTracks * 25_000L
        // Overhead depends on the payload, which is the target itself.
        val overhead = Estimator.containerOverheadBytes(container, targetBytes, durationSec)
        val video = targetBytes - overhead - audioBytes - subBytes
        val kbps = if (durationSec > 0) (video * 8 / durationSec / 1000).toInt() else 0
        return TargetBudget(targetBytes, overhead, audioBytes, subBytes, max(0, video), max(0, kbps))
    }

    fun riskFor(bppEq: Double, videoKbps: Int): QualityRisk = when {
        videoKbps < MIN_VIDEO_KBPS -> QualityRisk.IMPOSSIBLE
        bppEq >= 0.05 -> QualityRisk.LOW
        bppEq >= 0.028 -> QualityRisk.MODERATE
        bppEq >= 0.014 -> QualityRisk.HIGH
        bppEq >= 0.006 -> QualityRisk.SEVERE
        else -> QualityRisk.IMPOSSIBLE
    }

    /** bpp normalised to H.264-equivalent, typical complexity. */
    fun bppEq(videoKbps: Int, size: Size, fps: Double, codec: VideoCodec, hardware: Boolean, complexity: Double): Double {
        val pps = size.pixels * fps
        if (pps <= 0) return 0.0
        return videoKbps * 1000.0 / pps / Estimator.efficiency(codec, hardware) / complexity
    }

    fun feasibility(
        budget: TargetBudget,
        durationSec: Double,
        size: Size,
        fps: Double,
        codec: VideoCodec,
        hardware: Boolean,
        complexity: Double,
        audioKbpsTotal: Int,
        container: Container,
        sourceSize: Size,
    ): Feasibility {
        val bpp = bppEq(budget.videoKbps, size, fps, codec, hardware, complexity)
        val risk = if (budget.videoBytes <= 0) QualityRisk.IMPOSSIBLE else riskFor(bpp, budget.videoKbps)
        val reasons = buildList {
            add("${size.width}×${size.height}")
            add("${"%.0f".format(fps)} FPS")
            val mins = durationSec / 60
            add(if (mins >= 1) "${"%.0f".format(mins)} min" else "${"%.0f".format(durationSec)} s")
            if (complexity > 1.15) add("high-motion / detailed content")
            if (budget.audioShare > 0.25) add("audio uses ${(budget.audioShare * 100).toInt()}% of the budget")
        }
        // Recommended: what keeps this resolution/fps at "low risk" (bppEq 0.05).
        val recVideoBps = 0.05 * size.pixels * fps * Estimator.efficiency(codec, hardware) * complexity
        val recPayload = recVideoBps / 8 * durationSec + audioKbpsTotal * 1000.0 / 8 * durationSec
        val recommended = (recPayload + Estimator.containerOverheadBytes(container, recPayload.roundToLong(), durationSec)).roundToLong()

        val options = listOf(sourceSize.shortSide, 1080, 720, 540, 480, 360)
            .distinct()
            .filter { it <= sourceSize.shortSide }
            .map { short ->
                val s = if (sourceSize.width >= sourceSize.height)
                    Size(Geometry.align((short * sourceSize.width.toDouble() / sourceSize.height).toInt(), 2), short)
                else Size(short, Geometry.align((short * sourceSize.height.toDouble() / sourceSize.width).toInt(), 2))
                val b = bppEq(budget.videoKbps, s, fps, codec, hardware, complexity)
                ResolutionOption(if (short == sourceSize.shortSide) "Source (${short}p)" else "${short}p", s, riskFor(b, budget.videoKbps), b)
            }
        return Feasibility(risk, bpp, recommended, reasons, options)
    }

    /** Make Smaller: keep [percentOfOriginal] of the original size. */
    fun makeSmallerTarget(sourceBytes: Long, percentOfOriginal: Int): Long =
        (sourceBytes * percentOfOriginal.coerceIn(1, 99) / 100.0).roundToLong()
}
