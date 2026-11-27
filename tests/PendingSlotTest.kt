package tests

import jp.kaleidpixel.pageloupe.PendingSlot

// JVM tests for PendingSlot: the exactly-once holding semantics
// behind the page-feature interactions (SEN-25). Run via
// tests/run_tests.sh.

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
    // Empty slot: nothing pending, take hands out nothing.
    val empty = PendingSlot<String>()
    check("empty slot is not pending", !empty.isPending())
    check("empty peek is null", empty.peek() == null)
    check("empty take is null", empty.take() == null)

    // offer installs once; a second offer is refused and the
    // original survives.
    val slot = PendingSlot<String>()
    check("first offer accepted", slot.offer("a"))
    check("slot is pending after offer", slot.isPending())
    check("second offer refused", !slot.offer("b"))
    check("peek shows the original", slot.peek() == "a")

    // take hands the value out exactly once.
    check("take returns the value", slot.take() == "a")
    check("second take is null", slot.take() == null)
    check("not pending after take", !slot.isPending())

    // replace returns the superseded value for the caller to
    // retire, and installs the new one.
    val r = PendingSlot<String>()
    r.offer("old")
    check("replace returns the superseded value", r.replace("new") == "old")
    check("replace installed the new value", r.take() == "new")
    check(
        "replace on an empty slot returns null",
        PendingSlot<String>().replace("x") == null
    )

    // takeIf takes only a matching value.
    val t = PendingSlot<String>()
    t.offer("keep")
    check("takeIf with a false predicate takes nothing", t.takeIf { false } == null)
    check("value survives a failed takeIf", t.peek() == "keep")
    check("takeIf with a true predicate takes it", t.takeIf { it == "keep" } == "keep")
    check("slot empty after takeIf", !t.isPending())

    // clear drops without handing out.
    val c = PendingSlot<String>()
    c.offer("gone")
    c.clear()
    check("clear empties the slot", !c.isPending() && c.take() == null)

    // peek never consumes.
    val p = PendingSlot<String>()
    p.offer("stay")
    p.peek()
    p.peek()
    check("peek does not consume", p.take() == "stay")

    println("PendingSlotTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
