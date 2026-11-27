package jp.kaleidpixel.pageloupe

import java.io.File
import java.util.concurrent.ExecutorService

/**
 * The single writer for one small state file (the browsing
 * history, the saved tab session).
 *
 * Every write and delete for the file goes through one
 * single-thread executor, in submission order, so a later
 * state can never be overwritten by an earlier queued one —
 * the failure mode of mixing queued writes with a synchronous
 * "final" write on the caller thread (the sync write lands
 * first, the stale queued write lands after it and wins).
 *
 * Two trims keep the queue shallow under event bursts (an SPA
 * firing pushState in a loop submits a save per commit):
 *
 * - Generation skip: a task whose generation is no longer the
 *   newest submitted one does nothing — a newer snapshot is
 *   already queued behind it and will write the final state.
 * - Content dedupe: a task whose content equals what the
 *   previous task actually wrote skips the file operation.
 *
 * The content supplier runs on the executor thread, so the
 * (potentially large) serialization happens off the caller's
 * thread; the supplier must only read the immutable snapshot
 * it captured at submit time. A null content deletes the file.
 *
 * [flush] queues a barrier behind everything submitted so far
 * and waits for it: the caller can then rely on the newest
 * submitted state being on disk. Never call [flush] from the
 * executor's own thread (it would wait for itself).
 *
 * Pure java.io / java.util.concurrent, so the ordering rules
 * are covered by JVM tests.
 */
class QueuedFileWriter(
    private val file: File,
    private val executor: ExecutorService,
    private val onError: (Throwable) -> Unit = {},
) {

    @Volatile private var newestGeneration = 0L
    private val generationLock = Any()

    /** Executor-thread confined: the last content written. */
    private var lastWritten: String? = null

    /** Executor-thread confined: whether anything was written yet. */
    private var hasWritten = false

    /**
     * Queue a write of the state [supplier] produces, or a
     * delete when it produces null. Submissions are cheap and
     * safe to make on every state change.
     */
    fun submit(supplier: () -> String?) {
        val generation = synchronized(generationLock) { ++newestGeneration }
        executor.execute {
            if (generation != newestGeneration) {
                // Superseded before it ran: the newer task
                // behind this one carries the final state.
                return@execute
            }
            val content = try {
                supplier()
            } catch (t: Throwable) {
                onError(t)
                return@execute
            }
            if (hasWritten && content == lastWritten) return@execute
            try {
                if (content == null) {
                    // A failed delete must not be recorded as
                    // done (SEN-16): with the state recorded, a
                    // later identical (null) submission would
                    // dedupe-skip and the file would stay.
                    // Deleting an absent file is success.
                    if (!file.delete() && file.exists()) {
                        throw java.io.IOException("delete failed: ${file.name}")
                    }
                } else {
                    AtomicFileWrite.write(file) { w -> w.write(content) }
                }
                lastWritten = content
                hasWritten = true
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /**
     * Wait until every write submitted so far has run. Used
     * where the process may exit immediately after (closing
     * the last tab finishes the app). The wait is bounded: if
     * the queue does not drain in time the writes still land
     * afterwards — the guarantee degrades instead of freezing
     * the caller (an ANR would invite a force-stop, which
     * loses far more). An interrupt is reported and the
     * caller's interrupt status restored.
     */
    fun flush(timeoutMillis: Long = 2_000) {
        try {
            executor.submit {}.get(
                timeoutMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            onError(e)
        } catch (t: Throwable) {
            onError(t)
        }
    }
}
