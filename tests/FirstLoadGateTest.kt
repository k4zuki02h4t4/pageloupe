package tests

import jp.kaleidpixel.pageloupe.FirstLoadGate

/**
 * JVM unit tests for the first-load gate (FirstLoadGate.kt,
 * SEN-1101). The contracts pinned here:
 *
 * - Until a success proves the native initialization, at
 *   most one holder holds the claim: the claim holder
 *   reads HELD, everyone else reads WAIT, and a holder
 *   with no claim outstanding reads CLAIM.
 * - A settle releases the claim in any outcome; only a
 *   success proves the initialization, after which every
 *   decision is FREE.
 * - Waiting holders are resumed oldest first, exactly
 *   once, by the settle that releases the claim --
 *   including a claim that settles without success
 *   (succeeded = false), which is how a run whose load
 *   failed lets the next run proceed.
 * - A settle by a holder that holds no claim, and a
 *   repeat settle, change nothing.
 * - A holder that stops waiting (its run ended) drops
 *   its registration through cancelWait and is not
 *   resumed; it may register again afterwards.
 *
 * The wiring (the pump consulting the gate, the claim
 * settling from a Task completion listener) stays in
 * PageTranslator and is covered by on-device checks; what
 * is pinned here is every decision those calls delegate
 * to the gate.
 */

private var failures = 0
private var checks = 0

private fun expect(name: String, cond: Boolean, detail: String = "") {
    checks++
    if (cond) {
        println("ok   $name")
    } else {
        failures++
        println("FAIL $name $detail")
    }
}

fun main() {
    // A fresh gate: unproven, first holder claims, the
    // claim holder is held, another holder waits.
    run {
        val gate = FirstLoadGate()
        val a = Any()
        val b = Any()
        expect("fresh gate is unproven", !gate.isProven)
        expect(
            "first holder reads CLAIM",
            gate.decision(a) == FirstLoadGate.Decision.CLAIM
        )
        gate.claim(a)
        expect(
            "claim holder reads HELD",
            gate.decision(a) == FirstLoadGate.Decision.HELD
        )
        expect(
            "other holder reads WAIT",
            gate.decision(b) == FirstLoadGate.Decision.WAIT
        )
    }

    // A successful settle proves the initialization and
    // resumes the waiters oldest first, exactly once.
    run {
        val gate = FirstLoadGate()
        val a = Any()
        val b = Any()
        val c = Any()
        val resumed = mutableListOf<String>()
        gate.claim(a)
        gate.waitForRelease(b) { resumed.add("b") }
        gate.waitForRelease(c) { resumed.add("c") }
        val resumes = gate.settle(a, succeeded = true)
        expect("success proves the gate", gate.isProven)
        expect("settle returns both waiters", resumes.size == 2)
        for (resume in resumes) resume()
        expect(
            "waiters resume oldest first",
            resumed == listOf("b", "c"),
            "resumed=$resumed"
        )
        expect(
            "proven gate reads FREE for anyone",
            gate.decision(b) == FirstLoadGate.Decision.FREE &&
                gate.decision(Any()) == FirstLoadGate.Decision.FREE
        )
        expect(
            "repeat settle changes nothing",
            gate.settle(a, succeeded = true).isEmpty() && gate.isProven
        )
    }

    // A failed settle releases the claim without proving
    // anything: the next holder claims again.
    run {
        val gate = FirstLoadGate()
        val a = Any()
        val b = Any()
        var bResumed = false
        gate.claim(a)
        gate.waitForRelease(b) { bResumed = true }
        val resumes = gate.settle(a, succeeded = false)
        expect("failure does not prove", !gate.isProven)
        expect("failure still resumes waiters", resumes.size == 1)
        for (resume in resumes) resume()
        expect("waiter was resumed", bResumed)
        expect(
            "next holder reads CLAIM after a failure",
            gate.decision(b) == FirstLoadGate.Decision.CLAIM
        )
    }

    // A claim that settles without success (its load
    // failed) releases the waiting run, which can then
    // claim for itself.
    run {
        val gate = FirstLoadGate()
        val a = Any()
        val b = Any()
        gate.claim(a)
        gate.waitForRelease(b) { }
        gate.settle(a, succeeded = false)
        expect(
            "after a cancelled claim the waiter can claim",
            gate.decision(b) == FirstLoadGate.Decision.CLAIM
        )
        gate.claim(b)
        gate.settle(b, succeeded = true)
        expect("the waiter's own success proves", gate.isProven)
    }

    // A duplicate wait registration keeps the first
    // callback and resumes the holder once.
    run {
        val gate = FirstLoadGate()
        val a = Any()
        val b = Any()
        var count = 0
        gate.claim(a)
        gate.waitForRelease(b) { count += 1 }
        gate.waitForRelease(b) { count += 100 }
        val resumes = gate.settle(a, succeeded = false)
        for (resume in resumes) resume()
        expect("duplicate wait registers once", count == 1, "count=$count")
    }

    // A settle by a holder that holds no claim is a
    // no-op, and claiming over an outstanding claim is
    // refused.
    run {
        val gate = FirstLoadGate()
        val a = Any()
        val b = Any()
        gate.claim(a)
        expect(
            "settle by a non-holder is a no-op",
            gate.settle(b, succeeded = true).isEmpty() &&
                !gate.isProven &&
                gate.decision(a) == FirstLoadGate.Decision.HELD
        )
        var refused = false
        try {
            gate.claim(b)
        } catch (e: IllegalStateException) {
            refused = true
        }
        expect("claim over an outstanding claim is refused", refused)
    }

    // A run that ends while waiting drops its
    // registration: the claim's settle then resumes
    // nobody for it, and the holder may wait afresh.
    run {
        val gate = FirstLoadGate()
        val a = Any()
        val b = Any()
        gate.claim(a)
        gate.waitForRelease(b) { }
        gate.cancelWait(b)
        expect(
            "cancelled wait is not resumed",
            gate.settle(a, succeeded = false).isEmpty()
        )
        gate.claim(a)
        var resumed = false
        gate.waitForRelease(b) { resumed = true }
        for (resume in gate.settle(a, succeeded = false)) resume()
        expect("holder can wait again after cancelling", resumed)
        gate.cancelWait(b)
        expect(
            "cancelling a non-waiter is a no-op",
            !gate.isProven &&
                gate.decision(b) == FirstLoadGate.Decision.CLAIM &&
                gate.decision(a) == FirstLoadGate.Decision.CLAIM
        )
    }

    // cancelWait touches nothing but the caller's own
    // registration: with a claim out and another holder
    // waiting, a non-waiter's cancelWait leaves the claim
    // held, the waiter registered and the non-waiter
    // still reading WAIT.
    run {
        val gate = FirstLoadGate()
        val a = Any()
        val b = Any()
        val c = Any()
        var bResumed = false
        gate.claim(a)
        gate.waitForRelease(b) { bResumed = true }
        gate.cancelWait(c)
        expect(
            "non-waiter cancelWait leaves the claim held",
            gate.decision(a) == FirstLoadGate.Decision.HELD
        )
        expect(
            "non-waiter still reads WAIT after its cancelWait",
            gate.decision(c) == FirstLoadGate.Decision.WAIT
        )
        val resumes = gate.settle(a, succeeded = false)
        for (resume in resumes) resume()
        expect(
            "the real waiter is still resumed",
            resumes.size == 1 && bResumed
        )
    }

    if (failures > 0) {
        println("FirstLoadGateTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("FirstLoadGateTest: all $checks checks passed")
}
