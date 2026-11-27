package jp.kaleidpixel.pageloupe

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream

/**
 * The user-chosen export destination (SAF): the folder the
 * user picked once in the system tree picker, with a
 * PageLoupe subfolder inside it that receives the exported
 * recordings (owner design, 2026-11-23). Access rides on the
 * persisted URI grant from the picker, so the app needs no
 * storage permission.
 *
 * Written directly on DocumentsContract: the operations
 * needed are few (read the root's name, find or create one
 * subfolder, create files), and the framework API keeps the
 * app free of another vendored library.
 *
 * Scope note: the persisted grant itself covers the whole
 * picked tree (that is how SAF tree grants work). What the
 * app actually touches is only the root's child listing (to
 * find the subfolder), the PageLoupe subfolder and the files
 * it creates there -- and the grant is released when the
 * destination changes or is cleared (MainActivity's
 * updateExportFolder).
 */
object ExportTree {

    /** Subfolder created directly under the picked folder. */
    const val SUBFOLDER = "PageLoupe"

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )

    /**
     * Verifies the picked folder itself still exists. A
     * provider answers a deleted root with an empty (or null)
     * cursor rather than an exception, so without this check a
     * deleted destination looked like a transient failure
     * forever; FileNotFoundException is what the caller
     * classifies as access loss. Grant problems surface from
     * the query itself as SecurityException, also access loss.
     *
     * Both cursor shapes are treated as "gone" -- deliberately:
     * the framework delivers a deleted root as a null cursor,
     * and a provider that cannot answer for its root at all
     * (dead process, unmounted volume) is indistinguishable
     * from a deleted one from here. The cost of the wrong call
     * is a re-pick and a fresh grant; no data is lost either
     * way. (Line drawn on the v0.22.0 review; the deleted-
     * folder case is exercised on device.)
     */
    private fun requireRoot(ctx: Context, treeUri: Uri) {
        val cursor = ctx.contentResolver.query(
            rootDocumentUri(treeUri),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null
        )
        val exists = cursor?.use { it.moveToFirst() } ?: false
        if (!exists) {
            throw java.io.FileNotFoundException("export folder is gone")
        }
    }

    /**
     * The picked folder's display name for the export page's
     * destination row; falls back to the tree document id's
     * last segment when the provider gives no name.
     */
    fun displayName(ctx: Context, treeUri: Uri): String? {
        try {
            ctx.contentResolver.query(
                rootDocumentUri(treeUri),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (_: Exception) {
        }
        // getTreeDocumentId throws for a non-tree URI; a broken
        // saved value must degrade to "no name", never crash
        // the page that asked.
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return null
        }
        return ExportPolicy.fallbackDisplayName(docId)
    }

    /** Buffer for the export copy: the destination stream is
     * unbuffered, so the default 8KB would mean a write call
     * per 8KB of an up-to-8MB recording. */
    private const val COPY_BUFFER_BYTES = 64 * 1024

    /** The [ExportMove.Target] over the PageLoupe subfolder
     * at [dirUri]: create goes through [createFile], copy
     * streams into the document, discard is [deleteDocument]. */
    fun targetFor(context: Context, dirUri: Uri): ExportMove.Target =
        object : ExportMove.Target {
            override fun create(name: String, mimeType: String): Any =
                createFile(context, dirUri, mimeType, name)

            override fun copy(handle: Any, input: InputStream) {
                val out = context.contentResolver.openOutputStream(
                    handle as Uri, "w"
                ) ?: throw IOException("open failed")
                out.use { o -> input.copyTo(o, COPY_BUFFER_BYTES) }
            }

            override fun writtenLength(handle: Any): Long {
                // The document's own size column, queried the
                // same way the tree walks below query theirs;
                // any failure to say reads as "cannot say".
                return try {
                    context.contentResolver.query(
                        handle as Uri,
                        arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) {
                            cursor.getLong(0)
                        } else {
                            -1L
                        }
                    } ?: -1L
                } catch (_: Exception) {
                    -1L
                }
            }

            override fun discard(handle: Any): Boolean =
                deleteDocument(context, handle as Uri)
        }

    /** Best-effort delete of a document this flow created (a
     * partial copy left by a failed export); failure is logged,
     * never thrown. Returns whether the document is gone. */
    fun deleteDocument(ctx: Context, docUri: Uri): Boolean {
        return try {
            val gone = DocumentsContract.deleteDocument(ctx.contentResolver, docUri)
            if (!gone) {
                DebugLog.log("export", "partial export not deleted")
            }
            gone
        } catch (e: Exception) {
            DebugLog.log(
                "export",
                "partial export delete failed: ${e.javaClass.simpleName}"
            )
            false
        }
    }

    /**
     * The (directory, file) document ids of the children of
     * [parentDocId] named [name], from one listing; either is
     * null when no such child exists. Names compare
     * case-insensitively: the shared-storage providers sit on
     * case-insensitive volumes, where a user-made "pageloupe"
     * IS this folder as far as the filesystem is concerned --
     * missing it would mint a provider-renamed duplicate
     * ("PageLoupe (1)") on every export. An exact-case match
     * still wins when both exist (a case-sensitive provider
     * can hold both), so the pick is deterministic.
     */
    private fun findChildren(
        ctx: Context, treeUri: Uri, parentDocId: String, name: String
    ): Pair<String?, String?> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        // A null cursor is "unknown", not "absent": treating it
        // as absent would create a second PageLoupe folder next
        // to the existing one whenever a provider hiccups.
        val cursor = ctx.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null
        ) ?: throw IOException("child listing unavailable")
        var dirId: String? = null
        var fileId: String? = null
        var ciDirId: String? = null
        var ciFileId: String? = null
        cursor.use { c ->
            while (c.moveToNext()) {
                val childName = c.getString(1) ?: continue
                val exact = childName == name
                if (!exact && !childName.equals(name, ignoreCase = true)) continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                // An exact-case match wins over a case-insensitive
                // one: on a case-SENSITIVE provider both can
                // exist side by side, and the choice must be
                // deterministic.
                if (isDir) {
                    if (exact) {
                        if (dirId == null) dirId = c.getString(0)
                    } else if (ciDirId == null) {
                        ciDirId = c.getString(0)
                    }
                } else {
                    if (exact) {
                        if (fileId == null) fileId = c.getString(0)
                    } else if (ciFileId == null) {
                        ciFileId = c.getString(0)
                    }
                }
            }
        }
        return (dirId ?: ciDirId) to (fileId ?: ciFileId)
    }

    /**
     * The PageLoupe subfolder's document URI, created when
     * absent. Throws when the picked folder cannot be read or
     * written (grant revoked, folder deleted): the caller
     * treats that as an invalid destination and asks the user
     * to pick again.
     */
    fun pageLoupeDir(ctx: Context, treeUri: Uri): Uri {
        requireRoot(ctx, treeUri)
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val (dirId, fileId) = findChildren(ctx, treeUri, rootDocId, SUBFOLDER)
        if (dirId != null) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId)
        }
        if (fileId != null) {
            // A regular file with the subfolder's name blocks
            // it: creating anyway could land in a provider-
            // renamed folder ("PageLoupe (1)") that the next
            // lookup cannot find, multiplying folders per export.
            throw IOException("a file named $SUBFOLDER blocks the export folder")
        }
        return DocumentsContract.createDocument(
            ctx.contentResolver, rootDocumentUri(treeUri),
            DocumentsContract.Document.MIME_TYPE_DIR, SUBFOLDER
        ) ?: throw IOException("could not create $SUBFOLDER folder")
    }

    /**
     * Creates one file inside [dirUri] (a document URI in the
     * same tree). The provider may store it under an adjusted
     * name; the returned URI is the file either way. Throws on
     * failure.
     */
    fun createFile(ctx: Context, dirUri: Uri, mimeType: String, name: String): Uri =
        DocumentsContract.createDocument(ctx.contentResolver, dirUri, mimeType, name)
            ?: throw IOException("could not create $name")
}
