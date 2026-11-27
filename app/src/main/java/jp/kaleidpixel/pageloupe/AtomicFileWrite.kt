package jp.kaleidpixel.pageloupe

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.Writer

/**
 * Write a file atomically: stream into "<name>.tmp" next to
 * the target, fsync, then rename over the target. Any
 * failure — including a rename that does not go through —
 * deletes the temp and rethrows, so a failed write never
 * leaves a truncated file under the real name. A temp left
 * behind by a process killed mid-write (the catch never
 * ran) is harmless: its name differs from the target. What
 * clears it depends on the caller: fixed-name targets reuse
 * the same temp on the next write, while the console
 * recordings (timestamped names, never rewritten) have
 * theirs swept by RecordedLogs.prune.
 *
 * The fsync before the rename (SEN-16) is what makes the
 * rename meaningful under power loss: without it the name
 * can land while the content is still only in the page
 * cache, and the "atomic" file reads back truncated or
 * empty after a crash. The directory is not fsynced (it
 * cannot be opened as a stream portably on Android), so
 * the guarantee is: the content is durable once written;
 * the rename is atomic within the running system.
 *
 * Pure java.io, so the failure paths are covered by JVM
 * tests. Every file write in the app goes through here —
 * text through [write], binary through [writeStream] —
 * so the tmp + fsync + rename + cleanup pattern exists
 * exactly once.
 */
object AtomicFileWrite {

    fun write(target: File, block: (Writer) -> Unit) {
        writeStream(target) { out ->
            val writer = out.bufferedWriter()
            block(writer)
            writer.flush()
        }
    }

    fun writeStream(target: File, block: (OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                block(out)
                out.flush()
                out.fd.sync()
            }
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
