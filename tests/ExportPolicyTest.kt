package tests

import jp.kaleidpixel.pageloupe.ExportPolicy
import java.io.FileNotFoundException
import java.io.IOException

// JVM tests for the console-export flow's pure decisions:
// failure classification (which failures clear the saved
// destination), the document-id display-name fallback, size
// formatting and the result toast's wording. The SAF wiring
// itself is covered on device. Run via tests/run_tests.sh.

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
    // Access loss: the grant or the folder is gone.
    check(
        "security exception is access loss",
        ExportPolicy.isAccessLoss(SecurityException("revoked"))
    )
    check(
        "file not found is access loss",
        ExportPolicy.isAccessLoss(FileNotFoundException("gone"))
    )
    check(
        "plain io failure is transient",
        !ExportPolicy.isAccessLoss(IOException("provider busy"))
    )
    check(
        "illegal argument is transient",
        !ExportPolicy.isAccessLoss(IllegalArgumentException("bad doc id"))
    )

    // Display-name fallback from tree document ids.
    check(
        "volume and folder",
        ExportPolicy.fallbackDisplayName("primary:Documents") == "Documents"
    )
    check(
        "nested folder reads as its last segment",
        ExportPolicy.fallbackDisplayName("primary:Documents/Downloads") == "Downloads"
    )
    check(
        "empty id has no name",
        ExportPolicy.fallbackDisplayName("") == null
    )
    check(
        "id ending at the volume separator has no name",
        ExportPolicy.fallbackDisplayName("primary:") == null
    )

    // Size formatting boundaries.
    check("zero bytes", ExportPolicy.formatSize(0) == "0 B")
    check("under a KB stays bytes", ExportPolicy.formatSize(1023) == "1023 B")
    check("one KB", ExportPolicy.formatSize(1024) == "1.0 KB")
    check("fractional KB", ExportPolicy.formatSize(1536) == "1.5 KB")
    check("one MB", ExportPolicy.formatSize(1024 * 1024) == "1.0 MB")

    // Result wording: moved / failed / skipped combinations.
    check(
        "all moved",
        ExportPolicy.resultMessage(3, 0, 0) == "3件を書き出しました"
    )
    check(
        "all failed",
        ExportPolicy.resultMessage(0, 2, 0) == "書き出しに失敗しました"
    )
    check(
        "mixed",
        ExportPolicy.resultMessage(2, 1, 0) == "2件を書き出し、1件に失敗しました"
    )
    check(
        "everything already gone",
        ExportPolicy.resultMessage(0, 0, 2) == "対象のファイルは既に削除されていました"
    )
    check(
        "skipped noted alongside moved",
        ExportPolicy.resultMessage(2, 0, 1) ==
            "2件を書き出しました（1件は既に削除されていたため対象外）"
    )

    // Completion wording: the partial-leftover note appears on
    // BOTH endings; the invalid ending itemizes its counts.
    check(
        "completion plain",
        ExportPolicy.completionMessage(3, 0, 0, false, 0, aborted = false) == "3件を書き出しました"
    )
    check(
        "completion normal with leftover",
        ExportPolicy.completionMessage(2, 1, 0, false, 1, aborted = false) ==
            "2件を書き出し、1件に失敗しました（未完成のファイルが保存先に残っている場合があります）"
    )
    check(
        "completion invalid bare",
        ExportPolicy.completionMessage(0, 0, 0, true, 0, aborted = false) ==
            "保存先にアクセスできませんでした。フォルダを選び直してください"
    )
    check(
        "completion invalid itemized",
        ExportPolicy.completionMessage(2, 1, 0, true, 0, aborted = false) ==
            "保存先にアクセスできませんでした。フォルダを選び直してください（2件は書き出し済みです、1件は失敗しました）"
    )
    check(
        "completion invalid with leftover",
        ExportPolicy.completionMessage(0, 1, 0, true, 2, aborted = false) ==
            "保存先にアクセスできませんでした。フォルダを選び直してください（1件は失敗しました、未完成のファイルが保存先に残っている場合があります）"
    )
    check(
        "completion aborted bare",
        ExportPolicy.completionMessage(0, 0, 0, false, 0, aborted = true) ==
            "書き出しが中断しました"
    )
    check(
        "completion aborted itemized",
        ExportPolicy.completionMessage(1, 1, 0, false, 1, aborted = true) ==
            "書き出しが中断しました（1件は書き出し済みです、1件は失敗しました、未完成のファイルが保存先に残っている場合があります）"
    )
    check(
        "invalid wording wins over aborted",
        ExportPolicy.completionMessage(0, 0, 0, true, 0, aborted = true) ==
            "保存先にアクセスできませんでした。フォルダを選び直してください"
    )

    if (failures > 0) {
        println("ExportPolicyTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("ExportPolicyTest: $checks checks passed")
}
