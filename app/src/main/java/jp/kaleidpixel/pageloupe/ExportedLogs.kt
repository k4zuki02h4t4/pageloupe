package jp.kaleidpixel.pageloupe

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/**
 * Cleanup for the v0.21.0 export destination: the PageLoupe
 * folder inside the public Documents folder, written through
 * MediaStore (owner request, 2026-11-22; superseded the next
 * day by the record-then-export design -- recordings now live
 * in the app-specific folder, see [RecordedLogs], and reach
 * shared storage only through the user's explicit export).
 *
 * Files written back then are still this app's own MediaStore
 * rows while the same install lives, so the clear-data logs
 * category can still remove them; that is all that remains
 * here. After an uninstall the ownership is gone with the
 * install, and those files are the user's to delete from a
 * file app (owner-accepted, 2026-11-23).
 */
object ExportedLogs {

    /** Folder under Documents holding the v0.21.0 exports. */
    const val FOLDER = "PageLoupe"

    /** RELATIVE_PATH as inserts took it (no trailing slash). */
    const val RELATIVE_PATH = "Documents/$FOLDER"

    /** File-name prefix of every export file. */
    const val PREFIX = "devtools-"

    private fun collection(): Uri =
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * Deletes every v0.21.0 export file in the folder, pending
     * rows included (clear-data). One bulk delete; never
     * throws -- a failure is logged, and the caller's other
     * categories must not be skipped because this one failed.
     */
    fun deleteAll(ctx: Context) {
        try {
            val deleted = ctx.contentResolver.delete(
                collection(), selectionBundle(ExportSelection.PendingMatch.INCLUDE)
            )
            DebugLog.log("export", "clear-data deleted $deleted exported log file(s)")
        } catch (e: Exception) {
            DebugLog.log("export", "exported-log delete failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The folder-and-prefix selection, shared by query and
     * delete. The selection itself is [ExportSelection]'s
     * (JVM-pinned); this only packs it into a Bundle.
     */
    private fun selectionBundle(mode: ExportSelection.PendingMatch): Bundle {
        return Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                ExportSelection.selection()
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                ExportSelection.args(RELATIVE_PATH, PREFIX)
            )
            putInt(
                MediaStore.QUERY_ARG_MATCH_PENDING,
                ExportSelection.matchPending(mode)
            )
        }
    }
}
