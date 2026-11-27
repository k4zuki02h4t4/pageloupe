package tests

import jp.kaleidpixel.pageloupe.ExportController

/**
 * JVM unit tests for the ExportController state machine
 * (SEN-6): the legal transition shapes, the double-open /
 * double-move refusals, the mid-move picker drop, and the
 * fresh-instance abandon. Run via the runUnitTests Gradle
 * task.
 */

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name $detail")
    }
}

fun main() {
    val c = ExportController

    // IDLE -> PICKING -> answered with pending names.
    run {
        c.resetForTest()
        check("open picker from idle", c.tryOpenPicker(listOf("a.json")))
        check("picker marked open", c.pickerOpen)
        check("second open refused", !c.tryOpenPicker(listOf("b.json")))
        check("pending names are the first pick's",
            c.pendingNames == listOf("a.json"))
        val out = c.onPickerResult()
        check("result hands the names over", out.names == listOf("a.json"))
        check("result not dropped outside a move", !out.droppedForMove)
        check("picker closed after result", !c.pickerOpen)
        check("names consumed exactly once", c.pendingNames == null)
        val again = c.onPickerResult()
        check("second result has no names", again.names == null)
    }

    // Destination-only pick (null names) round-trips as null.
    run {
        c.resetForTest()
        check("open destination-only picker", c.tryOpenPicker(null))
        val out = c.onPickerResult()
        check("destination-only result has null names", out.names == null)
        check("destination-only result not dropped", !out.droppedForMove)
    }

    // Launch failure and fresh-instance abandon both clear.
    run {
        c.resetForTest()
        c.tryOpenPicker(listOf("x"))
        c.pickerLaunchFailed()
        check("launch failure closes the picker", !c.pickerOpen)
        check("launch failure clears names", c.pendingNames == null)
        c.tryOpenPicker(listOf("y"))
        c.abandonPicker()
        check("abandon closes the picker", !c.pickerOpen)
        check("abandon clears names", c.pendingNames == null)
        check("picker usable after abandon", c.tryOpenPicker(null))
    }

    // MOVING: picker refused, second move refused, a picker
    // result landing mid-move is dropped.
    run {
        c.resetForTest()
        check("begin move from idle", c.tryBeginMove())
        check("move marked in flight", c.moveInFlight)
        check("second move refused", !c.tryBeginMove())
        check("picker refused during move", !c.tryOpenPicker(listOf("z")))
        // Forced state (a picker opened before the move in a
        // race the UI normally prevents): its result drops.
        c.resetForTest()
        c.tryOpenPicker(listOf("z"))
        // Simulate the move starting while the picker was up
        // by beginning the move through the same gate the
        // result path consults.
        check("begin move while picker open", c.tryBeginMove())
        val out = c.onPickerResult()
        check("mid-move result is dropped", out.droppedForMove)
        check("dropped result still hands names for reporting",
            out.names == listOf("z"))
        check("picker closed after dropped result", !c.pickerOpen)
        c.finishMove()
        check("finish clears the move", !c.moveInFlight)
        check("move can begin again", c.tryBeginMove())
        c.finishMove()
    }

    c.resetForTest()
    println("ExportControllerTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
