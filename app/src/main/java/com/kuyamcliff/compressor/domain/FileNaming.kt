package com.kuyamcliff.compressor.domain

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Output file naming (PRD §40, §83). Source metadata and names are untrusted:
 * every generated name is sanitised before it reaches MediaStore/SAF, and no
 * name is ever interpreted as a path.
 */
object FileNaming {
    val tokens = listOf("{name}", "{codec}", "{resolution}", "{quality}", "{date}", "{size}", "{fps}")
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    private const val MAX_BASE = 120

    data class Values(
        val name: String,
        val codec: String = "",
        val resolution: String = "",
        val quality: String = "",
        val sizeLabel: String = "",
        val fps: String = "",
        val now: LocalDateTime = LocalDateTime.now(),
    )

    fun baseName(sourceDisplayName: String): String {
        val dot = sourceDisplayName.lastIndexOf('.')
        return if (dot > 0) sourceDisplayName.substring(0, dot) else sourceDisplayName
    }

    fun render(template: String, v: Values, extension: String): String {
        val t = template.ifBlank { "{name}_compressed" }
        val base = t.replace("{name}", baseName(v.name))
            .replace("{codec}", v.codec)
            .replace("{resolution}", v.resolution)
            .replace("{quality}", v.quality)
            .replace("{date}", v.now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")))
            .replace("{size}", v.sizeLabel)
            .replace("{fps}", v.fps)
        return sanitize(base) + "." + sanitizeExtension(extension)
    }

    fun sanitize(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            sb.append(
                when {
                    ch.code < 0x20 || ch.code == 0x7F -> '_'
                    ch in "/\\:*?\"<>|" -> '_'
                    else -> ch
                },
            )
        }
        var s = sb.toString().replace("..", "_").trim().trim('.', ' ')
        s = s.replace(Regex("_{2,}"), "_")
        if (s.isEmpty()) s = "video"
        if (s.uppercase() in reserved) s += "_"
        if (s.length > MAX_BASE) s = s.take(MAX_BASE).trimEnd('.', ' ')
        return s
    }

    fun sanitizeExtension(ext: String): String = ext.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "mp4" }.take(8)

    /** "Movie_HEVC.mp4" -> "Movie_HEVC (1).mp4" */
    fun numbered(fileName: String, n: Int): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) "${fileName.substring(0, dot)} ($n)${fileName.substring(dot)}" else "$fileName ($n)"
    }
}
