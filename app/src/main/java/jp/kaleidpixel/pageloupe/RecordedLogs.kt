package jp.kaleidpixel.pageloupe

import android.content.Context
import java.io.File
import java.io.Writer

/**
 * The recorded DevTools console/request logs (the menu's
 * 「コンソールログの記録」): one session's files live in the
 * app-specific folder, which the system removes together with
 * the app -- nothing is written to shared storage until the
 * user explicitly exports files from the console-export page
 * (owner design, 2026-11-23, replacing the v0.21.0 direct
 * MediaStore export to Documents/PageLoupe: files the app
 * wrote on its own could outlive an uninstall there with no
 * way for a reinstalled app to delete them).
 *
 * The folder is separate from the debug log's folder: the
 * debug log is a rolling diagnostic, these are user-facing
 * records with their own list / export / delete lifecycle.
 *
 * Only completed recordings are listed, counted or pruned:
 * writes go through a same-folder temp file (AtomicFileWrite),
 * and a temp left behind by a killed write is not a recording
 * -- it must never reach the export page or the user's folder.
 */
object RecordedLogs {

    /** Folder (under the app-specific base) holding the recordings. */
    private const val DIR_NAME = "console-logs"

    /** One completed recording, snapshotted for listing. */
    data class Recording(val name: String, val sizeBytes: Long, val modifiedMillis: Long)

    /** The folder path, without creating anything: read paths
     * (list / count / delete) must not create the folder just
     * by looking. */
    private fun folder(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(base, DIR_NAME)
    }

    /** The recordings folder, created: the write paths' entry. */
    fun dir(ctx: Context): File {
        if (ctx.getExternalFilesDir(null) == null) {
            DebugLog.log(
                "export",
                "external files dir unavailable; recordings use internal storage"
            )
        }
        val dir = folder(ctx)
        dir.mkdirs()
        return dir
    }

    /** The completed recordings, newest first (name breaks ties). */
    fun list(ctx: Context): List<Recording> {
        val dir = folder(ctx)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f ->
            f.isFile && RecordedLogsPolicy.isRecordingFile(f.name)
        } ?: return emptyList()
        return files
            .map { Recording(it.name, it.length(), it.lastModified()) }
            .sortedWith(
                compareByDescending<Recording> { it.modifiedMillis }
                    .thenByDescending { it.name }
            )
    }

    /** How many recordings exist (the menu greys its export item at zero). */
    fun count(ctx: Context): Int {
        val dir = folder(ctx)
        if (!dir.isDirectory) return 0
        return dir.listFiles { f ->
            f.isFile && RecordedLogsPolicy.isRecordingFile(f.name)
        }?.size ?: 0
    }

    /** Writes one recording from a rendered string (UTF-8). */
    fun writeText(ctx: Context, name: String, text: String) {
        AtomicFileWrite.write(File(dir(ctx), name)) { it.write(text) }
    }

    /** Writes one recording by streaming into the given writer. */
    fun write(ctx: Context, name: String, block: (Writer) -> Unit) {
        AtomicFileWrite.write(File(dir(ctx), name), block)
    }

    /** Deletes everything in the folder, temp files included
     * (the clear-data logs category). */
    fun deleteAll(ctx: Context) {
        try {
            folder(ctx).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            DebugLog.log("export", "recorded-log delete failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Drops the oldest recordings beyond the policy cap, so a
     * user who records and never exports does not fill the
     * folder without bound. Also sweeps temp files left by
     * killed writes: every write and this prune are serialized
     * on the log-file executor, so a temp file seen here can
     * only be a leftover, never a write in flight. Runs after
     * each session's write.
     */
    fun prune(ctx: Context) {
        try {
            val files = folder(ctx).listFiles { f -> f.isFile } ?: return
            for (f in files) {
                if (f.name.endsWith(".tmp") && !f.delete()) {
                    DebugLog.log("export", "stale temp not deleted: ${f.name}")
                }
            }
            val victims = RecordedLogsPolicy.pruneVictims(
                files.filter { RecordedLogsPolicy.isRecordingFile(it.name) }
                    .map { it.name to it.lastModified() }
            )
            for (name in victims) {
                if (!File(folder(ctx), name).delete()) {
                    DebugLog.log("export", "recording prune could not delete: $name")
                }
            }
            if (victims.isNotEmpty()) {
                DebugLog.log("export", "pruned ${victims.size} old recording(s)")
            }
        } catch (e: Exception) {
            DebugLog.log("export", "recording prune failed: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * The recordings folder's retention policy, in pure form so
 * JVM tests can pin it (RecordedLogsPolicyTest).
 */
object RecordedLogsPolicy {

    /**
     * Most recordings kept. One recording session writes up to
     * four files (console/requests × JSON/text), so the cap is
     * roughly ten sessions of headroom.
     */
    const val MAX_FILES = 40

    /**
     * Whether a folder entry is a completed recording: the
     * recording prefix, and a final extension. Temp files of
     * in-flight or killed writes end in ".tmp" and never
     * qualify, whatever their base name.
     */
    fun isRecordingFile(name: String): Boolean =
        name.startsWith("devtools-") &&
            (name.endsWith(".json") || name.endsWith(".log"))

    /**
     * Given (name, lastModified) pairs, the names to delete so
     * at most [maxFiles] remain: the oldest go first, and the
     * name breaks modification-time ties so the choice never
     * depends on listing order.
     */
    fun pruneVictims(entries: List<Pair<String, Long>>, maxFiles: Int = MAX_FILES): List<String> {
        if (entries.size <= maxFiles) return emptyList()
        val oldestFirst = entries.sortedWith(compareBy({ it.second }, { it.first }))
        return oldestFirst.take(entries.size - maxFiles).map { it.first }
    }
}
