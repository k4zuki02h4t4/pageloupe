package tests

import jp.kaleidpixel.pageloupe.PanelHeight

// JVM tests for the DevTools panel's height arithmetic: the
// chosen-height clamp shared by the drag handler and the
// rotation clamp, and the applied cap that keeps the bottom
// toolbar inside the column when the IME shrinks it. Pure
// arithmetic; the layout wiring is covered on device.
// Run via the runUnitTests Gradle task.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

// dp values as raw numbers; the functions take px, the ratios
// are what matter.
private fun maxChosen(space: Int) = PanelHeight.maxChosen(space, 110, 260)
private fun clampChosen(new: Int, space: Int) = PanelHeight.clampChosen(new, space, 200, 110, 260)
private fun cap(column: Int, toolbar: Int) = PanelHeight.appliedCap(column, toolbar, 110, 200)

fun main() {
    // Chosen clamp: roomy space -> space minus reserve.
    check("maxChosen roomy", maxChosen(1000) == 890)
    // Short space -> the 260 floor takes over.
    check("maxChosen floored", maxChosen(300) == 260)
    check("clampChosen inside", clampChosen(500, 1000) == 500)
    check("clampChosen above max", clampChosen(950, 1000) == 890)
    check("clampChosen below min", clampChosen(120, 1000) == 200)

    // Applied cap, tall column (no IME): the preferred bound
    // (column - reserve) rules while it exceeds the minimum.
    check("cap tall column", cap(941, 61) == 831)
    // Portrait with IME: preferred still rules.
    check("cap portrait IME", cap(511, 61) == 401)
    // Landscape with IME: column - reserve drops below the
    // minimum, so the minimum is the preferred bound -- and it
    // still fits over the hard bound.
    check("cap landscape IME", cap(301, 61) == 200)
    // Very short column: the hard bound (toolbar must fit)
    // undercuts the minimum.
    check("cap tiny column", cap(240, 61) == 179)
    // Degenerate: column no taller than the toolbar -> 0.
    check("cap degenerate", cap(61, 61) == 0)
    check("cap negative-safe", cap(30, 61) == 0)

    if (failures > 0) {
        println("PanelHeightTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("PanelHeightTest: all $checks checks passed")
}
