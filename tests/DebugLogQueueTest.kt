package tests

import jp.kaleidpixel.pageloupe.LogLineDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM unit tests for the debug log's dispatch queue cap
 * (review round 3, SEN-1): the round-2 cap check cast the
 * executor to ThreadPoolExecutor, but the executor was the
 * delegating wrapper from Executors.newSingleThreadExecutor,
 * so the cast always failed and the cap never engaged. The
 * dispatcher owns a real ThreadPoolExecutor; these checks
 * pin that the cap engages, that barriers bypass it, and
 * that order is FIFO once the queue drains.
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

    val cap = 5
    val d = LogLineDispatcher(cap)
    val gate = CountDownLatch(1)
    val ran = mutableListOf<Int>()
    val drained = CountDownLatch(1)

    // Occupy the single writer thread so the queue fills.
    check("blocker accepted", d.offerLine {
        gate.await(5, TimeUnit.SECONDS)
        synchronized(ran) { ran.add(0) }
    })
    // Fill the queue to the cap.
    var filled = 0
    for (i in 1..cap) {
        if (d.offerLine { synchronized(ran) { ran.add(i) } }) filled++
    }
    check("queue fills to the cap", filled == cap, "filled=$filled")
    check("cap engages past the limit", !d.offerLine { })
    // A barrier (writer close / log deletion) is never capped.
    d.enqueueBarrier {
        synchronized(ran) { ran.add(99) }
        drained.countDown()
    }
    gate.countDown()
    check("queue drains", drained.await(5, TimeUnit.SECONDS))
    check("FIFO order, barrier last",
        synchronized(ran) { ran.toList() } == listOf(0, 1, 2, 3, 4, 5, 99),
        "ran=$ran")
    // After the drain, lines are accepted again.
    val again = CountDownLatch(1)
    check("accepts after drain", d.offerLine { again.countDown() })
    check("post-drain line runs", again.await(5, TimeUnit.SECONDS))

    if (failures > 0) {
        println("DebugLogQueue: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("DebugLogQueue: $checks checks, 0 failures")
}
