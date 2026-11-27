package tests

import jp.kaleidpixel.pageloupe.LoadStartLatch

// LoadStartLatch (SEC-167 / SEN-353 / PER-162): the origin of
// a load sequence is sampled at its first start only, and the
// sequence ends at its commit or its finish — never left
// dangling by an uncommitted finish.
fun main() {
    var failures = 0
    var checks = 0
    fun check(name: String, cond: Boolean) {
        checks++
        if (!cond) {
            failures++
            println("FAIL: $name")
        }
    }

    // Home -> uncommitted finish: the finish reports home,
    // and the NEXT sequence samples afresh (PER-162: an
    // uncommitted finish must not wedge the latch).
    run {
        val latch = LoadStartLatch()
        latch.onLoadStarted(fromHome = true)
        check("home sequence latches home", latch.onFinish())
        latch.onLoadStarted(fromHome = true)
        check("second home sequence samples again", latch.onFinish())
    }

    // Home -> interrupted by a second start -> finish: the
    // second start must not overwrite the origin with its own
    // target's not-home-ness (SEN-353).
    run {
        val latch = LoadStartLatch()
        latch.onLoadStarted(fromHome = true)
        latch.onLoadStarted(fromHome = false)
        check("interruption keeps the first origin", latch.onFinish())
    }

    // Site -> start -> commit -> finish: after a commit the
    // finish consults nothing (the committed URL matches),
    // and the reported origin is not-home.
    run {
        val latch = LoadStartLatch()
        latch.onLoadStarted(fromHome = false)
        latch.onCommit()
        check("committed sequence reports not-home", !latch.onFinish())
    }

    // Site -> uncommitted finish -> next sequence from home:
    // origins are per-sequence.
    run {
        val latch = LoadStartLatch()
        latch.onLoadStarted(fromHome = false)
        check("site sequence reports not-home", !latch.onFinish())
        latch.onLoadStarted(fromHome = true)
        check("next sequence samples home", latch.onFinish())
    }

    // A commit ends the sequence: a start after it samples
    // again even before any finish arrives (history moves can
    // commit without a load of their own in between).
    run {
        val latch = LoadStartLatch()
        latch.onLoadStarted(fromHome = true)
        latch.onCommit()
        latch.onLoadStarted(fromHome = false)
        check("post-commit start samples anew", !latch.onFinish())
    }

    // A finish with no sequence in flight reports not-home.
    run {
        val latch = LoadStartLatch()
        check("idle finish reports not-home", !latch.onFinish())
    }

    // A reset mid-sequence (the tab was destroyed, SEN-360)
    // forgets the sequence: the next start samples anew.
    run {
        val latch = LoadStartLatch()
        latch.onLoadStarted(fromHome = false)
        latch.reset()
        latch.onLoadStarted(fromHome = true)
        check("post-reset start samples anew", latch.onFinish())
    }

    println("LoadStartLatchTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
