package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.model.CropSettings
import com.kuyamcliff.compressor.model.FpsChoice
import com.kuyamcliff.compressor.model.FpsMode
import com.kuyamcliff.compressor.model.ResolutionChoice
import com.kuyamcliff.compressor.model.VideoSettings
import com.kuyamcliff.compressor.model.VideoStreamInfo
import kotlin.math.max
import kotlin.math.roundToInt

data class Size(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    val shortSide: Int get() = minOf(width, height)
    override fun toString() = "${width}×$height"
}

/**
 * Output geometry rules (PRD §24–26):
 *  - never upscale unless requested,
 *  - preserve display aspect ratio by default (SAR and rotation included),
 *  - "1080p" means the short side is 1080, so portrait video stays portrait,
 *  - align to what the encoder requires.
 */
object Geometry {

    /** Display-oriented size after rotation metadata, SAR and the user's extra rotation. */
    fun orientedSize(v: VideoStreamInfo, userRotation: Int): Size {
        var w = if (v.displayWidth > 0) v.displayWidth else v.width
        var h = if (v.displayHeight > 0) v.displayHeight else v.height
        if (userRotation % 180 != 0) { val t = w; w = h; h = t }
        return Size(w, h)
    }

    /** Size after cropping (crop values are in display-oriented pixels). */
    fun croppedSize(v: VideoStreamInfo, s: VideoSettings): Size {
        val o = orientedSize(v, s.filters.rotate)
        val c = s.crop
        if (c.mode == com.kuyamcliff.compressor.model.CropMode.NONE) return o
        return Size(max(16, o.width - c.left - c.right), max(16, o.height - c.top - c.bottom))
    }

    fun align(value: Int, alignment: Int): Int {
        val a = max(2, alignment)
        return max(a, ((value.toDouble() / a).roundToInt()) * a).coerceAtLeast(16)
    }

    /**
     * Final encoded size. [alignment] comes from the chosen encoder (2 for most,
     * 8 for Kvazaar, the hardware codec's widthAlignment otherwise).
     */
    fun outputSize(v: VideoStreamInfo, s: VideoSettings, alignment: Int = 2): Size {
        val src = croppedSize(v, s)
        val aspect = src.width.toDouble() / src.height
        val target: Size = when (s.resolution) {
            ResolutionChoice.SOURCE -> src
            ResolutionChoice.CUSTOM -> {
                val cw = s.customWidth
                val ch = s.customHeight
                when {
                    cw > 0 && ch > 0 && !s.keepAspect -> Size(cw, ch)
                    cw > 0 && (ch <= 0 || s.keepAspect) -> Size(cw, (cw / aspect).roundToInt())
                    ch > 0 -> Size((ch * aspect).roundToInt(), ch)
                    else -> src
                }
            }
            else -> {
                val short = s.resolution.shortSide
                if (src.width >= src.height) Size((short * aspect).roundToInt(), short)
                else Size(short, (short / aspect).roundToInt())
            }
        }
        val capped = if (!s.allowUpscale && target.pixels > src.pixels) src else target
        return Size(align(capped.width, alignment), align(capped.height, alignment))
    }

    fun sourceFps(v: VideoStreamInfo): Double = v.fps.takeIf { it > 0 } ?: 30.0

    /** Effective output frame rate (for estimates); VFR sources keep their average. */
    fun outputFps(v: VideoStreamInfo, s: VideoSettings): Double {
        val src = sourceFps(v)
        val chosen = when (s.fps) {
            FpsChoice.SOURCE -> return src
            FpsChoice.CUSTOM -> s.customFps
            else -> s.fps.value
        }
        return if (s.fpsMode == FpsMode.PEAK) minOf(src, chosen) else chosen
    }

    /** Rational form of a frame rate for the native fps filter. */
    fun fpsRational(fps: Double): Pair<Int, Int> {
        val known = listOf(24000 to 1001, 30000 to 1001, 60000 to 1001)
        known.firstOrNull { kotlin.math.abs(it.first.toDouble() / it.second - fps) < 0.005 }?.let { return it }
        val rounded = (fps * 1000).roundToInt()
        return if (rounded % 1000 == 0) (rounded / 1000) to 1 else rounded to 1000
    }

    fun clampCrop(v: VideoStreamInfo, s: VideoSettings, crop: CropSettings): CropSettings {
        val o = orientedSize(v, s.filters.rotate)
        fun even(x: Int) = (x / 2) * 2
        val l = even(crop.left.coerceIn(0, o.width - 16))
        val r = even(crop.right.coerceIn(0, o.width - 16 - l))
        val t = even(crop.top.coerceIn(0, o.height - 16))
        val b = even(crop.bottom.coerceIn(0, o.height - 16 - t))
        return crop.copy(left = l, right = r, top = t, bottom = b)
    }

    /**
     * Converts display-space crop (what the user sees) to the engine's coded
     * orientation-space crop, compensating non-square pixels.
     */
    fun cropForEngine(v: VideoStreamInfo, s: VideoSettings): CropSettings {
        val c = s.crop
        if (c.mode == com.kuyamcliff.compressor.model.CropMode.NONE) return CropSettings()
        val sar = if (v.sarNum > 0 && v.sarDen > 0) v.sarNum.toDouble() / v.sarDen else 1.0
        if (kotlin.math.abs(sar - 1.0) < 1e-3) return c
        val rotated = (v.rotation + s.filters.rotate) % 180 != 0
        fun even(x: Double) = ((x / 2).roundToInt()) * 2
        return if (!rotated) c.copy(left = even(c.left / sar), right = even(c.right / sar))
        else c.copy(top = even(c.top / sar), bottom = even(c.bottom / sar))
    }
}
