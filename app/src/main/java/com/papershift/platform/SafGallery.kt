package com.papershift.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.papershift.core.AccessLost
import com.papershift.core.FolderMissing
import com.papershift.core.GalleryImage
import com.papershift.core.GalleryPort
import java.io.FileNotFoundException

/**
 * Top-level images of a SAF tree (PRD §5.3). Uses one DocumentsContract query with a
 * projection rather than DocumentFile, which makes an IPC call per property.
 */
class SafGallery(context: Context) : GalleryPort {
    private val resolver = context.applicationContext.contentResolver

    override fun list(treeUri: String): List<GalleryImage> {
        val tree = Uri.parse(treeUri)
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission }) {
            throw AccessLost("No persisted read permission for $tree")
        }
        val treeId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: IllegalArgumentException) {
            throw FolderMissing("Not a tree URI: $tree", e)
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        try {
            val cursor = resolver.query(children, PROJECTION, null, null, null)
                ?: throw FolderMissing("Provider returned no cursor for $tree")
            return cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val mimeCol = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                val modifiedCol = c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
                buildList {
                    while (c.moveToNext()) {
                        val mime = c.getString(mimeCol) ?: continue
                        if (mime !in IMAGE_MIME_TYPES) continue
                        val id = c.getString(idCol) ?: continue
                        add(
                            GalleryImage(
                                id = id,
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                                lastModified = if (c.isNull(modifiedCol)) 0L else c.getLong(modifiedCol),
                            ),
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            throw AccessLost("Permission denied for $tree", e)
        } catch (e: FileNotFoundException) {
            throw FolderMissing("Folder not found: $tree", e)
        } catch (e: IllegalArgumentException) {
            // ExternalStorageProvider reports a deleted folder this way.
            throw FolderMissing("Folder not found: $tree", e)
        } catch (e: IllegalStateException) {
            // Removed SD card.
            throw FolderMissing("Folder unavailable: $tree", e)
        }
    }

    /** Display name of the tree's root folder, for the folder row. */
    fun folderName(treeUri: Uri): String? = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Persists read access to [newTree] and releases [oldTree] (TC-SAF-01: exactly one grant). */
    fun takeFolder(newTree: Uri, oldTree: String?) {
        resolver.takePersistableUriPermission(newTree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (oldTree != null && oldTree != newTree.toString()) {
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(oldTree), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private companion object {
        val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED)
        val IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif")
    }
}
