package tests

import jp.kaleidpixel.pageloupe.DroppedLineCounter
import jp.kaleidpixel.pageloupe.droppedLinesNotice

/**
 * JVM unit tests for DebugLog's dropped-call bookkeeping
 * (DroppedLineCounter and the notice text in DebugLog.kt).
 * DebugLog itself talks to android.util.Log and the file
 * system, so it stays device-exercised; the counting rules —
 * a drop stays pending until its report has been written —
 * are pure and live here.
 */
fun main() {
    var checks = 0
    var failures = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        checks++
        if (!cond) {
            failures++
            println("FAIL: $name $detail")
        }
    }

    // Failures accumulate and report their running total.
    run {
        val counter = DroppedLineCounter()
        check("counter starts empty", counter.pending() == 0)
        check("first failure totals 1", counter.recordFailure() == 1)
        check("second failure totals 2", counter.recordFailure() == 2)
        check("pending counts both", counter.pending() == 2)
    }

    // Acknowledging clears exactly the reported count: drops
    // recorded after the snapshot stay pending.
    run {
        val counter = DroppedLineCounter()
        counter.recordFailure()
        counter.recordFailure()
        val snapshot = counter.pending()
        counter.recordFailure()
        counter.acknowledge(snapshot)
        check(
            "acknowledge keeps later drops",
            counter.pending() == 1,
            "pending=${counter.pending()}"
        )
        counter.acknowledge(1)
        check("acknowledge all clears", counter.pending() == 0)
    }

    // Fail, report successfully, fail again: the pending count
    // restarts, but the lifetime total (what the logcat
    // throttle reads) keeps counting, so the throttle sequence
    // is not reset by an intervening success.
    run {
        val counter = DroppedLineCounter()
        val first = counter.recordFailure()
        counter.acknowledge(counter.pending())
        val second = counter.recordFailure()
        val third = counter.recordFailure()
        check(
            "totals are cumulative across reports",
            first == 1 && second == 2 && third == 3,
            "first=$first second=$second third=$third"
        )
        check(
            "pending restarts after a report",
            counter.pending() == 2,
            "pending=${counter.pending()}"
        )
        check(
            "throttle follows the lifetime total",
            counter.shouldReportDrop(second) && !counter.shouldReportDrop(third),
            "second=$second third=$third"
        )
    }

    // The notice text carries the count and nothing else.
    check(
        "notice text",
        droppedLinesNotice(3) ==
            "3 earlier log call(s) failed inside the logger and were dropped",
        droppedLinesNotice(3)
    )

    // The report decision itself: recordFailureForReport
    // returns the total exactly when logcat should be told,
    // and an intervening successful report (acknowledge) does
    // not restart the sequence.
    run {
        val counter = DroppedLineCounter()
        val first = counter.recordFailureForReport()
        counter.acknowledge(counter.pending())
        val second = counter.recordFailureForReport()
        val third = counter.recordFailureForReport()
        val fourth = counter.recordFailureForReport()
        check(
            "report decision follows lifetime totals",
            first == 1 && second == 2 && third == 0 && fourth == 4,
            "first=$first second=$second third=$third fourth=$fourth"
        )
    }

    // Logcat reporting is throttled to powers of two.
    run {
        val counter = DroppedLineCounter()
        check(
            "report drop at powers of two",
            listOf(1, 2, 4, 8, 16).all { counter.shouldReportDrop(it) },
            ""
        )
        check(
            "report drop skips non powers",
            listOf(0, 3, 5, 6, 7, 9, -1).none { counter.shouldReportDrop(it) },
            ""
        )
    }

    println("DebugLogCounterTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
