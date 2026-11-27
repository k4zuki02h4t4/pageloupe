package tests

import jp.kaleidpixel.pageloupe.QueuedFileWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// JVM tests for QueuedFileWriter (SEN-154 review round 1): the
// single-writer ordering behind the history and tab-session
// files — a later submission must never be overwritten by an
// earlier queued one, superseded submissions are skipped, and
// flush() guarantees the newest state is on disk.
// Run via tests/run_tests.sh.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

fun main() {
    val dir = File(System.getProperty("java.io.tmpdir"), "qfw-test-${System.nanoTime()}")
    dir.mkdirs()
    val file = File(dir, "state.txt")
    val executor = Executors.newSingleThreadExecutor()
    try {
        val writer = QueuedFileWriter(file, executor)

        // Submissions land in order; the last one wins.
        writer.submit { "one" }
        writer.submit { "two" }
        writer.submit { "three" }
        writer.flush()
        check("last submission wins", file.readText() == "three")

        // A burst of submissions: only the newest needs to be
        // written, and the file still ends with it (the older
        // ones may be generation-skipped).
        for (i in 0 until 50) writer.submit { "burst-$i" }
        writer.flush()
        check("burst ends at the newest", file.readText() == "burst-49")

        // Null content deletes the file.
        writer.submit { null }
        writer.flush()
        check("null deletes the file", !file.exists())

        // A write after a delete recreates the file.
        writer.submit { "back" }
        writer.flush()
        check("write after delete recreates", file.readText() == "back")

        // The regression this class exists for: a "final" state
        // submitted after a pile of stale ones is what survives
        // a flush — never a stale one.
        writer.submit { "stale-1" }
        writer.submit { "stale-2" }
        writer.submit { "final" }
        writer.flush()
        check("final state survives the flush", file.readText() == "final")

        // A throwing supplier skips its write but neither kills
        // the queue nor loses later submissions.
        var errors = 0
        val writer2 = QueuedFileWriter(File(dir, "state2.txt"), executor) { errors++ }
        writer2.submit { throw RuntimeException("boom") }
        writer2.submit { "after-error" }
        writer2.flush()
        check("supplier failure is reported", errors == 1)
        check(
            "queue survives a supplier failure",
            File(dir, "state2.txt").readText() == "after-error",
        )

        // flush() is bounded: with the executor's thread held
        // by a blocked task, flush returns after its timeout
        // instead of waiting forever, and the queued write
        // still lands once the thread frees up.
        val latch = java.util.concurrent.CountDownLatch(1)
        executor.execute { latch.await(10, TimeUnit.SECONDS) }
        val writer3 = QueuedFileWriter(File(dir, "state3.txt"), executor)
        writer3.submit { "eventual" }
        val startedAt = System.nanoTime()
        writer3.flush(200)
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000
        check("flush is bounded by its timeout", waitedMs < 5_000)
        latch.countDown()
        writer3.flush()
        check(
            "queued write lands after the thread frees",
            File(dir, "state3.txt").readText() == "eventual",
        )
    } finally {
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)
        dir.deleteRecursively()
    }

    if (failures > 0) {
        println("QueuedFileWriterTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("QueuedFileWriterTest: all $checks checks passed")
}
