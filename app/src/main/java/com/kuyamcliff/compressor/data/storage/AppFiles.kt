package com.kuyamcliff.compressor.data.storage

import android.content.Context
import android.net.Uri
import com.kuyamcliff.compressor.domain.FileNaming
import java.io.File

/**
 * App-private scratch space: preview samples, per-job scratch (subtitle
 * extraction), copies of external subtitle files and the libass font. Only
 * this app's own temporary files are ever deleted here — never user videos.
 */
class AppFiles(private val context: Context) {
    val previews: File get() = dir(context.cacheDir, "previews")
    val scratch: File get() = dir(context.cacheDir, "scratch")
    val subtitles: File get() = dir(context.cacheDir, "subtitles")
    val reports: File get() = dir(context.cacheDir, "reports")
    val fonts: File get() = dir(context.filesDir, "fonts")
    val crashes: File get() = dir(context.filesDir, "crashes")

    private fun dir(parent: File, name: String) = File(parent, name).apply { mkdirs() }

    fun jobScratch(jobId: Long): File = dir(scratch, "job_$jobId")

    /** Copies an external subtitle document into app storage so libavformat can open it by path. */
    fun importSubtitle(uri: Uri, displayName: String): File {
        val ext = displayName.substringAfterLast('.', "srt").lowercase().filter { it.isLetterOrDigit() }.take(5)
        val out = File(subtitles, "${uri.toString().hashCode().toUInt().toString(16)}_${FileNaming.sanitize(displayName).take(40)}.$ext")
        if (!out.exists()) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // Subtitle files are small; refuse anything absurd (decompression-bomb style input).
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_SUBTITLE_BYTES) { "Subtitle file is too large" }
                        o.write(buf, 0, n)
                    }
                }
            }
        }
        return out
    }

    /**
     * libass is built without fontconfig. Provide one Unicode-capable system font
     * (copied once) as the fallback for burned-in subtitles.
     */
    fun subtitleFont(): Pair<String, String> {
        val dir = fonts
        val existing = dir.listFiles()?.firstOrNull { it.extension in setOf("ttf", "otf") }
        if (existing != null) return dir.absolutePath to existing.absolutePath
        val candidates = listOf("Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf", "Roboto-Medium.ttf")
        val sys = File("/system/fonts")
        val src = candidates.map { File(sys, it) }.firstOrNull { it.canRead() }
            ?: sys.listFiles()?.filter { it.extension == "ttf" && it.length() < 8_000_000 }?.minByOrNull { it.length() }
        if (src != null) {
            val dst = File(dir, src.name)
            runCatching { src.copyTo(dst, overwrite = true) }
            return dir.absolutePath to dst.absolutePath
        }
        return dir.absolutePath to ""
    }

    fun clearPreviews(olderThanMs: Long = 0) = sweep(previews, olderThanMs)

    /** Removes abandoned temporary files; returns bytes freed. */
    fun cleanupAbandoned(activeJobIds: Set<Long>, olderThanMs: Long = 6 * 3600_000L): Long {
        var freed = sweep(previews, olderThanMs)
        scratch.listFiles()?.forEach { d ->
            val id = d.name.removePrefix("job_").toLongOrNull()
            if (id == null || id !in activeJobIds) freed += d.walkBottomUp().sumOf { f -> val l = f.length(); if (f.delete()) l else 0L }
        }
        freed += sweep(subtitles, 7 * 24 * 3600_000L)
        freed += sweep(reports, 24 * 3600_000L)
        return freed
    }

    fun tempBytes(): Long = listOf(previews, scratch, subtitles, reports).sumOf { d -> d.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    private fun sweep(dir: File, olderThanMs: Long): Long {
        val cutoff = System.currentTimeMillis() - olderThanMs
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() <= cutoff) {
                val size = f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                if (f.deleteRecursively()) freed += size
            }
        }
        return freed
    }

    companion object {
        const val MAX_SUBTITLE_BYTES = 20L * 1024 * 1024
    }
}
