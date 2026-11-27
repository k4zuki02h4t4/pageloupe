package jp.kaleidpixel.pageloupe

import java.io.File
import java.io.IOException
import java.io.Writer

/**
 * Write a file atomically: stream into "<name>.tmp" next to
 * the target, then rename over the target. Any failure —
 * including a rename that does not go through — deletes the
 * temp and rethrows, so a failed write never leaves a
 * truncated file under the real name. A temp left behind by a
 * process killed mid-write (the catch never ran) is harmless:
 * its name differs from the target. What clears it depends on
 * the caller: fixed-name targets reuse the same temp on the
 * next write, while the console recordings (timestamped names,
 * never rewritten) have theirs swept by RecordedLogs.prune.
 * Pure java.io, so the failure paths are covered by JVM tests.
 */
object AtomicFileWrite {

    fun write(target: File, block: (Writer) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.bufferedWriter().use { block(it) }
            if (!tmp.renameTo(target)) {
                throw IOException("rename failed: ${target.name}")
            }
        } catch (e: Throwable) {
            if (!tmp.delete() && tmp.exists()) {
                // Keep the failure traceable without masking the
                // original error; a later write reuses the temp
                // name, so the leftover does not accumulate.
                e.addSuppressed(IOException("temp not deleted: ${tmp.name}"))
            }
            throw e
        }
    }
}
