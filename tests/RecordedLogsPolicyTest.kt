package tests

import jp.kaleidpixel.pageloupe.RecordedLogsPolicy

// JVM tests for the recordings folder's retention policy:
// which recordings the prune drops once the folder grows past
// its cap. Pure list arithmetic; the folder wiring is covered
// on device. Run via tests/run_tests.sh.

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
    // Nothing to prune at or under the cap.
    check(
        "empty folder prunes nothing",
        RecordedLogsPolicy.pruneVictims(emptyList(), maxFiles = 4).isEmpty()
    )
    check(
        "exactly at the cap prunes nothing",
        RecordedLogsPolicy.pruneVictims(
            listOf("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L), maxFiles = 4
        ).isEmpty()
    )

    // Over the cap: exactly the overflow goes, oldest first,
    // regardless of the order the entries were listed in.
    check(
        "overflow drops the oldest",
        RecordedLogsPolicy.pruneVictims(
            listOf("c" to 3L, "a" to 1L, "b" to 2L, "d" to 4L, "e" to 5L),
            maxFiles = 4
        ) == listOf("a")
    )
    check(
        "larger overflow drops oldest in order",
        RecordedLogsPolicy.pruneVictims(
            listOf("f" to 6L, "a" to 1L, "e" to 5L, "b" to 2L, "d" to 4L, "c" to 3L),
            maxFiles = 3
        ) == listOf("a", "b", "c")
    )

    // Equal timestamps: the name decides, so the victims never
    // depend on the directory listing's order.
    check(
        "timestamp ties break by name",
        RecordedLogsPolicy.pruneVictims(
            listOf("b" to 7L, "a" to 7L, "c" to 7L), maxFiles = 2
        ) == listOf("a")
    )

    // Only completed recordings qualify: the recording
    // prefix and a final .json / .log. A temp file of an
    // in-flight or killed write ends in .tmp and never does.
    check(
        "console json is a recording",
        RecordedLogsPolicy.isRecordingFile("devtools-console-20261123-012345-678.json")
    )
    check(
        "requests log is a recording",
        RecordedLogsPolicy.isRecordingFile("devtools-requests-20261123-012345-678.log")
    )
    check(
        "temp of a json is not a recording",
        !RecordedLogsPolicy.isRecordingFile("devtools-console-20261123-012345-678.json.tmp")
    )
    check(
        "other extension is not a recording",
        !RecordedLogsPolicy.isRecordingFile("devtools-notes.txt")
    )
    check(
        "no prefix is not a recording",
        !RecordedLogsPolicy.isRecordingFile("pageloupe-debug.log")
    )

    // The production cap is the default argument.
    check(
        "default cap keeps forty",
        RecordedLogsPolicy.pruneVictims(
            (1..41).map { "f$it" to it.toLong() }
        ) == listOf("f1")
    )

    if (failures > 0) {
        println("RecordedLogsPolicyTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("RecordedLogsPolicyTest: $checks checks passed")
}
