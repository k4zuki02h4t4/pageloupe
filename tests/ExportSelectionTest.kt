package tests

import jp.kaleidpixel.pageloupe.ExportSelection
import jp.kaleidpixel.pageloupe.ExportedLogs

// JVM tests for the MediaStore selection behind the exported
// log files (Documents/PageLoupe): the exact selection string,
// its arguments and the pending-match modes, plus the folder /
// path / prefix constants the arguments are built from. The
// MediaStore operations themselves only run on device; this
// suite pins WHAT they ask for, so an edit cannot silently
// retarget the pending sweep or the clear-data delete.
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
    check(
        "selection targets relative_path + display_name",
        ExportSelection.selection() == "relative_path = ? AND _display_name LIKE ?",
    )
    check("folder is PageLoupe", ExportedLogs.FOLDER == "PageLoupe")
    check(
        "relative path is Documents/PageLoupe",
        ExportedLogs.RELATIVE_PATH == "Documents/PageLoupe",
    )
    check("prefix is devtools-", ExportedLogs.PREFIX == "devtools-")
    check(
        "args are the slashed folder and the prefix pattern",
        ExportSelection.args(ExportedLogs.RELATIVE_PATH, ExportedLogs.PREFIX)
            .contentEquals(arrayOf("Documents/PageLoupe/", "devtools-%")),
    )
    // MediaStore.MATCH_ONLY = 3, MATCH_INCLUDE = 1 (API constants).
    check(
        "sweep matches pending only",
        ExportSelection.matchPending(ExportSelection.PendingMatch.ONLY) == 3,
    )
    check(
        "clear-data includes pending",
        ExportSelection.matchPending(ExportSelection.PendingMatch.INCLUDE) == 1,
    )

    // MIME types: the declared type must be the one the MIME
    // map pairs with the extension, or MediaProvider renames
    // the file (".log" as text/plain lands as ".log.txt").
    check("json mime", ExportSelection.mimeTypeOf("devtools-console-x.json") == "application/json")
    check("log mime", ExportSelection.mimeTypeOf("devtools-console-x.log") == "text/x-log")
    check("other mime", ExportSelection.mimeTypeOf("devtools-x.txt") == "text/plain")

    if (failures > 0) {
        println("ExportSelectionTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("ExportSelectionTest: all $checks checks passed")
}
