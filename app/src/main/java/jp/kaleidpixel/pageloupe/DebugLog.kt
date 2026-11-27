package jp.kaleidpixel.pageloupe

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer diagnostics, gated by the "デバッグログを出力" option.
 * When the option is off, every call is a no-op: instrumentation stays in
 * the code, output does not. When on, lines go to logcat and to
 * files/logs/pageloupe-debug.log (rotated once past roughly 1MB --
 * the counter tracks chars as an approximation of bytes, so a
 * non-ASCII-heavy log can grow to a few MB before rotating).
 *
 * The file writer stays open between lines: re-opening it for
 * every line would put the open on whatever thread happened to
 * log, UI included. Writes are serialized and flushed per
 * line so an attached log is never behind the app.
 *
 * Logging is best-effort by contract: [log] never throws, not even
 * an Error — building a line allocates, and some callers are
 * failure handlers running while the process is already in
 * trouble, heap exhaustion included. The contract covers what
 * happens inside [log]; arguments are evaluated by the caller
 * first, so failure handlers should pass literals or prebuilt
 * strings rather than building a string inside their own catch
 * block. A failure inside [log] is counted; logcat is told on
 * the 1st, 2nd, 4th, 8th… failure, and the count is repeated
 * in the log file on the next line that gets through, so a
 * broken logger is not indistinguishable from silence. The
 * file report is at-least-once: if a notice's flush fails
 * midway, the count stays pending and the notice repeats on a
 * later line, so read it as an upper bound in that rare case.
 * The bounded logcat rate has a trade-off: the gaps between
 * powers of two grow, so after many lifetime failures a newly
 * persistent breakage can take a while to reach the next
 * logcat line. Once lines get through again, the file notice
 * reports the count still pending; while no line gets
 * through, the sparse logcat lines are the only clue.
 */
object DebugLog {

    /** Log calls that failed inside [log] and were dropped (see the class doc). */
    private val droppedLines = DroppedLineCounter()

    @Volatile private var enabled = false
    @Volatile private var file: File? = null
    private var writer: BufferedWriter? = null
    private var bytesInCurrentFile: Long = 0

    /**
     * A live secret that must never reach the log (currently the
     * DevTools proxy's session token). Callers register it when the
     * secret is minted and clear it when it dies; every log line is
     * scrubbed centrally, so a message that happens to quote a URL
     * containing the secret (a frontend console line, a WebSocket
     * error) cannot leak it.
     */
    @Volatile private var maskedSecret: String? = null

    fun setMaskedSecret(secret: String?) {
        maskedSecret = secret?.takeIf { it.isNotEmpty() }
    }

    fun init(ctx: Context) {
        enabled = Settings.debugLog(ctx)
        if (enabled) openFile(ctx)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        if (on) {
            openFile(ctx)
            log("debug", "debug log enabled")
        } else {
            // The close runs on the writer queue, not under the
            // lock on this (UI) thread (review round 3, PER-4):
            // a writer stuck in a clogged disk write holds the
            // lock, and waiting for it here would ANR the
            // settings page. writeLine re-checks `enabled`
            // under the lock, so lines queued before the close
            // write nothing once off.
            dispatcher.enqueueBarrier {
                synchronized(this) { closeWriterLocked() }
            }
        }
    }

    fun isEnabled(): Boolean = enabled

    /** The one place that resolves the logs directory (MainActivity uses it too). */
    fun logsDir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(base, "logs")
        dir.mkdirs()
        return dir
    }

    private fun openFile(ctx: Context) {
        // Resolve the target file only. The writer itself opens
        // lazily on the writer thread (writeLine), so no file
        // I/O beyond the directory lookup happens on the
        // caller's thread (review round 3, PER-4).
        try {
            val dir = logsDir(ctx)
            // No lock (review round 4, SEN-8): `file` is
            // @Volatile, and the writer holds this object's
            // lock across disk writes — taking it here, on
            // the UI thread, waited out a clogged disk for a
            // plain assignment.
            file = File(dir, "pageloupe-debug.log")
        } catch (_: Exception) {
            file = null
        }
    }

    /** Caller must hold the lock. Reopens in append mode. */
    private fun openWriterLocked() {
        val f = file ?: return
        // Close the previous writer first: init() runs on every
        // onCreate, and reassigning without closing would leak one
        // file descriptor per activity recreation until GC.
        closeWriterLocked()
        try {
            writer = java.io.FileOutputStream(f, true).bufferedWriter(Charsets.UTF_8)
            bytesInCurrentFile = f.length()
        } catch (_: Exception) {
            writer = null
        }
    }

    private fun closeWriterLocked() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }

    /**
     * Delete the debug log files (current and rotated), e.g. as part
     * of "clear history and data". The names are resolved from
     * the logs directory unconditionally: a process
     * that started with logging OFF never opened the file, but a
     * previous process's log must still be erasable.
     * Logging continues afterwards: the next line reopens
     * a fresh file.
     */
    fun deleteLogFiles(ctx: Context): Boolean {
        // The delete runs as a barrier on the writer queue
        // (SEN-24): lines already queued are written first
        // and then deleted with everything else, so a clear
        // cannot be overtaken by a line that was logged
        // before it but had not reached the file yet. The
        // caller waits for the barrier — the clear is a
        // user-visible privacy action, not a fire-and-forget
        // — and falls back to doing it inline if the barrier
        // itself cannot run. The barrier captures the
        // application context, never the caller's Activity
        // (review round 3, SEN-10).
        val appCtx = ctx.applicationContext
        val done = java.util.concurrent.CountDownLatch(1)
        try {
            dispatcher.enqueueBarrier {
                try {
                    deleteLogFilesNow(appCtx)
                } finally {
                    done.countDown()
                }
            }
            // Bounded (review round 2, SEN-10): the caller
            // is the UI thread, and an unbounded wait turns
            // a clogged disk into an ANR. On timeout the
            // barrier stays queued and still deletes when
            // it runs — lines queued before it are written
            // first and deleted with the rest, lines after
            // it reopen a fresh file. The result is reported
            // to the caller (review round 3, SEN-10/SEC-5):
            // false means the delete has NOT happened yet,
            // and the clear-data flow surfaces that instead
            // of claiming success.
            return done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (_: Exception) {
            deleteLogFilesNow(appCtx)
            return true
        }
    }

    private fun deleteLogFilesNow(ctx: Context) {
        synchronized(this) {
            closeWriterLocked()
            try {
                val dir = logsDir(ctx)
                File(dir, "pageloupe-debug.log").delete()
                File(dir, "pageloupe-debug.old.log").delete()
            } catch (_: Exception) {
            }
            bytesInCurrentFile = 0
        }
    }

    fun log(tag: String, msg: String) {
        // Best-effort, always: diagnostics must never break the
        // caller — not even under heap exhaustion, where building
        // the line itself can fail with an Error. Some callers
        // are failure handlers that run precisely when the
        // process is already in trouble. A failure here is
        // counted, not silently swallowed: logcat is told on
        // the 1st, 2nd, 4th, 8th… failure (so a logger that
        // keeps failing stays visible, at a bounded rate, even
        // when no line reaches the file), and logLine repeats
        // the count in the file on the next line that gets
        // through.
        try {
            logLine(tag, msg)
        } catch (_: Throwable) {
            // The whole bookkeeping block is guarded again: it
            // runs precisely when the process is in trouble, and
            // even a failure inside it (class loading under heap
            // exhaustion included) must not let log() throw.
            try {
                val report = droppedLines.recordFailureForReport()
                if (report > 0) {
                    Log.w("PageLoupe/debuglog", "log call dropped (total $report)")
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * A string made safe to sit on ONE log line: the
     * backslash is doubled first, so the encoding is
     * lossless (a page's literal "\n" text can never be
     * mistaken for an escaped line feed), then every Unicode
     * line-break character -- LF, VT, FF, CR, NEL (U+0085)
     * and the line / paragraph separators -- becomes its
     * visible escape. Shared by this logger and the
     * recorder's text exports, so the rule exists exactly
     * once (audit hardening).
     */
    fun escapeLine(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\u000B", "\\u000B")
            .replace("\u000C", "\\u000C")
            .replace("\u0085", "\\u0085")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

    /** Cap on queued, unwritten log lines (SEN-11). */
    private val MAX_QUEUED_LINES = 1000

    /**
     * The one writer queue (SEN-24): file writes — with
     * their rotation and dropped-line notices — run here,
     * not on the caller's thread, so a log call on the UI or
     * a relay thread never waits on the disk. Lines keep
     * their call order (single thread, FIFO) and their
     * call-time stamps (the line is built before the hand-off).
     * Daemon and never shut down, like the other process
     * executors.
     *
     * The queue is a real ThreadPoolExecutor held directly
     * (review round 3, SEN-1): the round-2 cap check cast
     * the Executors.newSingleThreadExecutor result — a
     * delegating wrapper, never a ThreadPoolExecutor — so
     * the cast always failed and the cap never engaged.
     * [LogLineDispatcher] owns the executor so the cap is
     * exercisable from JVM tests.
     */
    private val dispatcher = LogLineDispatcher(MAX_QUEUED_LINES)

    private fun logLine(tag: String, msg: String) {
        if (!enabled) return
        val secret = maskedSecret
        val safe = if (secret != null) msg.replace(secret, "***") else msg
        // One physical line per call (audit hardening): a
        // message carrying CR/LF or the Unicode separators
        // (an exception message, a page-derived value) must
        // not forge extra lines in the log file.
        val oneLine = escapeLine(safe)
        val line = "${stamp()} [$tag] $oneLine\n"
        // Bounded queue (review round 2, SEN-11): the
        // executor's queue is unbounded, and a clogged disk
        // would otherwise accumulate a closure and two
        // strings per log call without limit. Past the cap
        // the line is dropped and counted like any other
        // failed log call, so the loss stays visible.
        if (!dispatcher.offerLine { writeLine(tag, oneLine, line) }) {
            val report = droppedLines.recordFailureForReport()
            if (report > 0) {
                Log.w("PageLoupe/debuglog", "log call dropped (total $report)")
            }
            return
        }
    }

    private fun writeLine(tag: String, oneLine: String, line: String) {
        synchronized(this) {
            // Re-checked under the lock: a thread that passed the
            // outer check just before setEnabled(false) closed the
            // writer must not reopen it and write while off.
            if (!enabled) return
            Log.d("PageLoupe/$tag", oneLine)
            try {
                val f = file ?: return
                if (writer == null) openWriterLocked()
                if (bytesInCurrentFile > 1_000_000) {
                    closeWriterLocked()
                    val old = File(f.parentFile, "pageloupe-debug.old.log")
                    old.delete()
                    f.renameTo(old)
                    openWriterLocked()
                }
                val out = writer ?: return
                out.write(line)
                out.flush()
                // Char count approximates the byte count (the log
                // is mostly ASCII); encoding the line just to
                // measure it would be waste per line.
                bytesInCurrentFile += line.length
                // Report earlier dropped log calls in the log
                // itself, now that a line got through. (Built
                // only when the count is non-zero.) The count is
                // acknowledged only after the notice is written:
                // if this write fails, the count stays pending
                // for the next line instead of vanishing.
                val dropped = droppedLines.pending()
                if (dropped > 0) {
                    // Guarded separately: the main line above is
                    // already written, so a failure here (even an
                    // Error) must not make log() count that line
                    // as dropped. The count simply stays pending
                    // and the notice is retried on a later line.
                    try {
                        val notice = "${stamp()} [debuglog] ${droppedLinesNotice(dropped)}\n"
                        out.write(notice)
                        out.flush()
                        bytesInCurrentFile += notice.length
                        droppedLines.acknowledge(dropped)
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Exception) {
                // File failures (disk full, rotation races) are
                // routine degradation and stay local to the file
                // path; failures elsewhere in logLine are what
                // the dropped-lines count in log() tracks.
            }
        }
    }

    private val stampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private fun stamp(): String = stampFormat.get()!!.format(Date())
}

/**
 * Bookkeeping for log calls dropped inside [DebugLog.log]:
 * pure (no Android types), so the counting rules are covered
 * by JVM tests. Two counts are kept apart: the pending count
 * backs the file notice — a recorded drop stays pending until
 * a report of it has actually been written ([acknowledge]),
 * and drops recorded after the reported count stay pending —
 * while the lifetime total backs the logcat throttle and is
 * never reset, so a successful file report does not restart
 * the throttle's 1, 2, 4, 8… sequence.
 */
class DroppedLineCounter {
    private val pendingCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** Record one dropped call; returns the lifetime total. */
    fun recordFailure(): Int {
        pendingCount.incrementAndGet()
        return totalCount.incrementAndGet()
    }

    /**
     * Record one dropped call and decide the logcat report in
     * the same place: returns the lifetime total when logcat
     * should be told (the total is a power of two), or 0 when
     * this failure stays silent. Keeping the decision here —
     * rather than letting the caller choose which count to
     * judge by — is what the JVM tests pin down.
     */
    fun recordFailureForReport(): Int {
        val total = recordFailure()
        return if (shouldReportDrop(total)) total else 0
    }

    /**
     * Whether a dropped-call total is worth a logcat line: the
     * powers of two, so a persistently broken logger reports
     * 1, 2, 4, 8… instead of once per failed call. A member of
     * this class — not a top-level function — so that the
     * failure path in [DebugLog.log] loads no app class at
     * failure time: this class is already loaded when the
     * counter is created, during DebugLog's initialization,
     * and loading a new class mid-failure (the file's facade
     * class, say) could itself fail under heap exhaustion,
     * taking the report down with the failure it belonged to.
     */
    fun shouldReportDrop(total: Int): Boolean =
        total > 0 && (total and (total - 1)) == 0

    /** Drops not yet successfully reported. */
    fun pending(): Int = pendingCount.get()

    /** Mark [reported] drops as reported. */
    fun acknowledge(reported: Int) {
        pendingCount.addAndGet(-reported)
    }
}

/** The dropped-calls notice text (without stamp or newline). */
fun droppedLinesNotice(dropped: Int): String =
    "$dropped earlier log call(s) failed inside the logger and were dropped"

/**
 * The debug log's single-thread dispatch queue (pure JVM, so
 * the cap is unit-testable). A real ThreadPoolExecutor is held
 * directly — never the delegating wrapper that
 * Executors.newSingleThreadExecutor returns, whose queue the
 * round-2 cap check could not see (review round 3, SEN-1).
 * Log lines are subject to the queue cap; barriers (writer
 * close, log-file deletion) always enqueue, because a clear
 * must not become impossible exactly when the disk is
 * clogged. The check-then-enqueue race can overshoot the cap
 * by at most the number of threads logging concurrently,
 * which is the soft cap's accepted margin.
 */
class LogLineDispatcher(private val maxQueuedLines: Int) {

    private val executor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(),
    ) { r -> Thread(r, "pl-debug-log").apply { isDaemon = true } }

    /** Enqueue a log line; false when the queue is at the cap. */
    fun offerLine(task: Runnable): Boolean {
        if (executor.queue.size >= maxQueuedLines) return false
        executor.execute(task)
        return true
    }

    /** Enqueue a barrier task; barriers are never capped. */
    fun enqueueBarrier(task: Runnable) {
        executor.execute(task)
    }

    /** Queued, not-yet-running tasks (test visibility). */
    fun queuedCount(): Int = executor.queue.size
}
