package com.kuyamcliff.compressor.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Locale-aware formatting for sizes, durations, rates. */
object Format {
    fun bytes(b: Long): String {
        val v = abs(b).toDouble()
        val sign = if (b < 0) "-" else ""
        return when {
            v >= 1024.0 * 1024 * 1024 -> sign + String.format(Locale.getDefault(), "%.2f GB", v / (1024.0 * 1024 * 1024))
            v >= 1024.0 * 1024 * 100 -> sign + String.format(Locale.getDefault(), "%.0f MB", v / (1024.0 * 1024))
            v >= 1024.0 * 1024 -> sign + String.format(Locale.getDefault(), "%.1f MB", v / (1024.0 * 1024))
            v >= 1024 -> sign + String.format(Locale.getDefault(), "%.0f KB", v / 1024)
            else -> "$sign${v.toLong()} B"
        }
    }

    fun bytesRange(lo: Long, hi: Long): String {
        val mb = 1024.0 * 1024
        return if (hi >= 1024 * mb) "${bytes(lo)}–${bytes(hi)}"
        else String.format(Locale.getDefault(), if (hi >= 100 * mb) "%.0f–%.0f MB" else "%.1f–%.1f MB", lo / mb, hi / mb)
    }

    /** mm:ss or h:mm:ss */
    fun duration(ms: Long): String {
        val total = (ms / 1000.0).roundToLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s) else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    fun fps(v: Double): String = if (v <= 0) "—" else String.format(Locale.getDefault(), "%.1f", v)

    fun fpsLabel(v: Double): String = when {
        v <= 0 -> "—"
        abs(v - Math.rint(v)) < 0.01 -> String.format(Locale.getDefault(), "%.0f", v)
        else -> String.format(Locale.getDefault(), "%.3f", v).trimEnd('0').trimEnd('.')
    }

    fun bitrate(bps: Long): String = when {
        bps >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f Mbps", bps / 1e6)
        bps > 0 -> String.format(Locale.getDefault(), "%.0f kbps", bps / 1e3)
        else -> "—"
    }

    fun percent(fraction: Double): String = String.format(Locale.getDefault(), "%.1f%%", fraction * 100)
    fun percentRange(lo: Double, hi: Double): String = String.format(Locale.getDefault(), "%.0f–%.0f%%", lo * 100, hi * 100)
    fun speed(x: Double): String = if (x <= 0) "—" else String.format(Locale.getDefault(), "%.2f× realtime", x)
}
