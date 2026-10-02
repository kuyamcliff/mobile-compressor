package com.kuyamcliff.compressor.data.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.kuyamcliff.compressor.engine.EngineError
import com.kuyamcliff.compressor.engine.EngineFailure
import com.kuyamcliff.compressor.engine.ErrorCategory
import java.io.FileNotFoundException

data class SourceMeta(val uri: Uri, val displayName: String, val size: Long, val mimeType: String?, val lastModified: Long, val location: String)

/**
 * Reads user-selected content through the Storage Access Framework. Never
 * converts URIs to filesystem paths; the engine receives file descriptors.
 */
class SourceAccess(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun persistPermission(uri: Uri) {
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    fun hasPersistedPermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    fun meta(uri: Uri): SourceMeta {
        var name = uri.lastPathSegment ?: "video"
        var size = -1L
        var modified = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }?.let { name = it }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }?.let { modified = c.getLong(it) }
                }
            }
        }
        if (size < 0) size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
        val location = when (uri.authority) {
            "com.android.externalstorage.documents" -> DocumentsContract.getDocumentId(uri).substringAfter(':').substringBeforeLast('/', "").ifEmpty { "Internal storage" }
            "com.android.providers.downloads.documents" -> "Downloads"
            "com.android.providers.media.documents", "media" -> "Media library"
            else -> uri.authority ?: ""
        }
        return SourceMeta(uri, name, size, runCatching { resolver.getType(uri) }.getOrNull(), modified, location)
    }

    /** Opens the source read-only; failures are mapped to explained engine errors. */
    fun openRead(uri: Uri): ParcelFileDescriptor = try {
        resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
    } catch (e: SecurityException) {
        throw EngineFailure(EngineError.local(ErrorCategory.CONTENT_URI_EXPIRED,
            "Access to the source file has expired.", listOf("Select the file again")))
    } catch (e: FileNotFoundException) {
        throw EngineFailure(EngineError.local(ErrorCategory.SOURCE_DISAPPEARED,
            "The source file is no longer available.", listOf("Make sure it wasn't moved or deleted")))
    } catch (e: IllegalArgumentException) {
        throw EngineFailure(EngineError.local(ErrorCategory.INVALID_INPUT, "The selected item is not a readable file."))
    }

    fun canDelete(uri: Uri): Boolean = runCatching {
        if (!DocumentsContract.isDocumentUri(context, uri)) return false
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
        } ?: false
    }.getOrDefault(false)

    fun delete(uri: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    /** Lists video documents in a user-chosen tree (watch-folder workflow). */
    fun listVideos(treeUri: Uri): List<SourceMeta> {
        val out = mutableListOf<SourceMeta>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<String>().apply { add(rootId) }
        var visited = 0
        while (stack.isNotEmpty() && visited < 2000) {
            val docId = stack.removeFirst()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (stack.size < 200) stack.add(id)
                    } else if (mime.startsWith("video/")) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        out += SourceMeta(uri, c.getString(1) ?: id, c.getLong(3), mime, c.getLong(4), "")
                    }
                }
            }
        }
        return out
    }
}
