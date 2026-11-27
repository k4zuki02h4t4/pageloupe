package jp.kaleidpixel.pageloupe

import android.provider.MediaStore

/**
 * The MediaStore selection that picks out this app's exported
 * log rows, split out of [ExportedLogs] in pure form: the
 * Bundle assembly stays there, but the selection string, its
 * arguments and the pending-match mode are decided here so JVM
 * tests can pin them (ExportSelectionTest) -- the MediaStore paths
 * have no other off-device coverage, and a drifted selection
 * would silently sweep or delete the wrong rows. The
 * MediaStore fields referenced are compile-time constants and
 * are inlined, so this object never loads an Android class at
 * runtime.
 */
object ExportSelection {

    /** Rows in the export folder whose name carries the export prefix. */
    fun selection(): String =
        MediaStore.MediaColumns.RELATIVE_PATH + " = ? AND " +
            MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?"

    /**
     * Arguments for [selection]. Read-back RELATIVE_PATH values
     * carry a trailing slash, so the folder argument takes one
     * too; the name argument is the prefix as a LIKE pattern.
     */
    fun args(relativePath: String, prefix: String): Array<String> =
        arrayOf("$relativePath/", "$prefix%")

    /**
     * The MIME type an export file is declared with.
     * MediaProvider appends the canonical extension of the
     * declared type when it does not match the file name (a
     * ".log" declared as text/plain lands as ".log.txt", verified
     * on device, Android 15), so the declared type must be the
     * one the MIME map pairs with the file's extension.
     */
    fun mimeTypeOf(name: String): String = when {
        name.endsWith(".json") -> "application/json"
        name.endsWith(".log") -> "text/x-log"
        else -> "text/plain"
    }

    /** Which pending rows a selection bundle matches. */
    enum class PendingMatch {
        /** Only the still-pending rows (the sweep). */
        ONLY,

        /** Pending rows alongside the published ones (clear-data). */
        INCLUDE,
    }

    /** The MediaStore pending-match constant for [mode]. */
    fun matchPending(mode: PendingMatch): Int = when (mode) {
        PendingMatch.ONLY -> MediaStore.MATCH_ONLY
        PendingMatch.INCLUDE -> MediaStore.MATCH_INCLUDE
    }
}
