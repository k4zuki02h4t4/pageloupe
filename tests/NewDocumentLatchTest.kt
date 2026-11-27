package tests

import jp.kaleidpixel.pageloupe.NewDocumentLatch

private var failures = 0
private var total = 0

private fun check(name: String, cond: Boolean) {
    total++
    if (cond) {
        println("ok   $name")
    } else {
        failures++
        println("FAIL $name")
    }
}

fun main() {
    val plain = NewDocumentLatch()
    check("commit without a start is same-document", !plain.onCommit())

    val loaded = NewDocumentLatch()
    loaded.onLoadStarted()
    check("started then committed is a new document", loaded.onCommit())
    check("the commit consumed the sequence", !loaded.onCommit())

    val aborted = NewDocumentLatch()
    aborted.onLoadStarted()
    aborted.onFinish()
    check(
        "a sequence that ended uncommitted leaves the next commit same-document",
        !aborted.onCommit()
    )

    val chained = NewDocumentLatch()
    chained.onLoadStarted()
    chained.onLoadStarted()
    check("an interrupted sequence still commits as new", chained.onCommit())

    val reset = NewDocumentLatch()
    reset.onLoadStarted()
    reset.reset()
    check("reset forgets the in-flight sequence", !reset.onCommit())

    val finishedAfterCommit = NewDocumentLatch()
    finishedAfterCommit.onLoadStarted()
    finishedAfterCommit.onCommit()
    finishedAfterCommit.onFinish()
    check("a finish after the commit changes nothing", !finishedAfterCommit.onCommit())

    println("NewDocumentLatchTest: $total checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
