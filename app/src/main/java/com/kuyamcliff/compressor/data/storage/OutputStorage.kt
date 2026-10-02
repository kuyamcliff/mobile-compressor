package com.kuyamcliff.compressor.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import com.kuyamcliff.compressor.domain.FileNaming
import com.kuyamcliff.compressor.engine.EngineError
import com.kuyamcliff.compressor.engine.EngineFailure
import com.kuyamcliff.compressor.engine.ErrorCategory
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Where an output is written while encoding, and how it becomes visible.
 *
 *  - Default (MediaStore, Movies/Compressed): inserted with IS_PENDING=1 so no
 *    other app sees it; flipped to visible only after validation.
 *  - User folder (SAF): written as "<name>.partial" and renamed to the final
 *    name after validation.
 *
 * Either way, a failed or cancelled job deletes the temporary item and a
 * corrupt file is never presented as successful output (PRD §51).
 */
data class OutputTarget(val kind: String, val uri: Uri, val finalName: String, val folderUri: String?, val mime: String)

class OutputStorage(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun locationLabel(folderUri: String?, folderLabel: String?): String =
        if (folderUri == null) "${Environment.DIRECTORY_MOVIES}/$DEFAULT_SUBDIR" else folderLabel ?: "Selected folder"

    /** True if a file with this name already exists at the destination (PRD §102). */
    fun exists(fileName: String, folderUri: String?): Boolean = runCatching {
        if (folderUri == null) {
            resolver.query(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(fileName, RELATIVE_PATH), null,
            )?.use { it.count > 0 } ?: false
        } else {
            DocumentFile.fromTreeUri(context, Uri.parse(folderUri))?.findFile(fileName) != null
        }
    }.getOrDefault(false)

    /** First free "name (n).ext" at the destination. */
    fun uniqueName(fileName: String, folderUri: String?): String {
        if (!exists(fileName, folderUri)) return fileName
        for (n in 1..999) {
            val candidate = FileNaming.numbered(fileName, n)
            if (!exists(candidate, folderUri)) return candidate
        }
        return FileNaming.numbered(fileName, System.currentTimeMillis().toInt() and 0xffff)
    }

    fun createPending(fileName: String, mime: String, folderUri: String?): OutputTarget = try {
        if (folderUri == null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            OutputTarget("mediastore", uri, fileName, null, mime)
        } else {
            val tree = Uri.parse(folderUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "$fileName.partial")
                ?: throw IllegalStateException("createDocument returned null")
            OutputTarget("saf", doc, fileName, folderUri, mime)
        }
    } catch (e: SecurityException) {
        throw EngineFailure(EngineError.local(ErrorCategory.PERMISSION_DENIED, "The output folder can no longer be written.",
            listOf("Choose the output folder again in Settings")))
    } catch (e: Exception) {
        throw EngineFailure(EngineError.local(ErrorCategory.OUTPUT_PATH_FAILURE, "Could not create the output file: ${e.message}",
            listOf("Check that the output folder still exists", "Choose another output folder")))
    }

    fun openForWrite(target: OutputTarget): ParcelFileDescriptor = try {
        resolver.openFileDescriptor(target.uri, "rw") ?: throw IllegalStateException("no descriptor")
    } catch (e: Exception) {
        throw EngineFailure(EngineError.local(ErrorCategory.OUTPUT_PATH_FAILURE, "Could not open the output file for writing."))
    }

    fun openForRead(uri: Uri): ParcelFileDescriptor? = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()

    /** Free bytes on the filesystem that holds [pfd] (works for MediaStore and SAF alike). */
    fun freeBytes(pfd: ParcelFileDescriptor): Long = runCatching {
        val st = Os.fstatvfs(pfd.fileDescriptor)
        st.f_bavail * st.f_bsize
    }.getOrDefault(-1L)

    fun freeBytesDefault(): Long = runCatching {
        val st = Os.statvfs(Environment.getExternalStorageDirectory().path)
        st.f_bavail * st.f_bsize
    }.getOrDefault(-1L)

    /** Makes a validated output visible under its final name. Returns the final URI. */
    fun finalizeOutput(target: OutputTarget, replaceExisting: Boolean): Uri {
        if (target.kind == "mediastore") {
            if (replaceExisting) deleteExistingMediaStore(target.finalName, target.uri)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DISPLAY_NAME, target.finalName)
            }
            resolver.update(target.uri, values, null, null)
            return target.uri
        }
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(target.folderUri!!))
        if (replaceExisting) folder?.findFile(target.finalName)?.delete()
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, target.uri, target.finalName) }.getOrNull()
        if (renamed != null) return renamed
        // Provider cannot rename: copy into a correctly named document, then drop the partial.
        val parent = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(target.folderUri), DocumentsContract.getTreeDocumentId(Uri.parse(target.folderUri)))
        val dest = DocumentsContract.createDocument(resolver, parent, target.mime, target.finalName)
            ?: throw EngineFailure(EngineError.local(ErrorCategory.OUTPUT_PATH_FAILURE, "Could not finalise the output file."))
        resolver.openFileDescriptor(target.uri, "r")!!.use { inPfd ->
            resolver.openFileDescriptor(dest, "w")!!.use { outPfd ->
                FileInputStream(inPfd.fileDescriptor).channel.use { src ->
                    FileOutputStream(outPfd.fileDescriptor).channel.use { dst -> src.transferTo(0, src.size(), dst) }
                }
            }
        }
        discard(target)
        return dest
    }

    private fun deleteExistingMediaStore(name: String, except: Uri) {
        runCatching {
            resolver.query(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, RELATIVE_PATH), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), c.getLong(0))
                    if (uri != except) runCatching { resolver.delete(uri, null, null) }
                }
            }
        }
    }

    fun discard(target: OutputTarget) {
        runCatching {
            if (target.kind == "mediastore") resolver.delete(target.uri, null, null)
            else DocumentsContract.deleteDocument(resolver, target.uri)
        }
    }

    /** Deletes a finished output (user confirmed). */
    fun deleteOutput(uri: Uri): Boolean = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(resolver, uri)
        else resolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    fun size(uri: Uri): Long = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L

    /** Removes pending MediaStore items left behind by a killed process. */
    fun cleanupAbandonedPending(keep: Set<String>): Int {
        var removed = 0
        runCatching {
            val q = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(RELATIVE_PATH))
            }
            val base = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            resolver.query(base, arrayOf(MediaStore.MediaColumns._ID), q, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(base, c.getLong(0))
                    if (uri.toString() !in keep && resolver.delete(uri, null, null) > 0) removed++
                }
            }
        }
        return removed
    }

    companion object {
        const val DEFAULT_SUBDIR = "Compressed"
        val RELATIVE_PATH = "${Environment.DIRECTORY_MOVIES}/$DEFAULT_SUBDIR/"
    }
}
