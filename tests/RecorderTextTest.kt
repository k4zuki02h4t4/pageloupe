package tests

import jp.kaleidpixel.pageloupe.ConsoleLogRecorder

/**
 * JVM unit tests for ConsoleLogRecorder's TEXT exports
 * (toText / toRequestsText and the shared tap-loss notes).
 * These methods never call org.json at runtime, so they run
 * against the android.jar stubs; the JSON exports are not
 * covered here. This is what pins the tap-loss notes in BOTH
 * exports — the requests text once missed them.
 */

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    checks++
    if (cond) {
        println("ok   $name")
    } else {
        failures++
        println("FAIL $name $detail")
    }
}

fun main() {
    val recorder = ConsoleLogRecorder()
    val entry = ConsoleLogRecorder.Entry(
        timeMs = 0L, level = "log", text = "hello", source = "console-api",
        url = null, line = null
    )
    val req = ConsoleLogRecorder.NetReq(
        requestId = "r1", startWallMs = 0L, method = "GET",
        url = "https://example.com/", status = 200
    )

    // Console text: entry-drop note, both tap notes, the entry.
    run {
        val text = recorder.toText(
            listOf(entry), emptyMap(),
            droppedEntries = 2, tapDropped = 3, tapFailed = true
        )
        check("console text has entry drop note",
            text.contains("古いコンソール記録 2 件を破棄しました"), text)
        check("console text has tap drop note",
            text.contains("タップで 3 件のメッセージを記録できませんでした"), text)
        check("console text has tap failure note",
            text.contains("タップが途中で停止したため"), text)
        check("console text has the entry", text.contains("hello"), text)
    }

    // Requests text: request-drop note and the SAME tap notes.
    run {
        val text = recorder.toRequestsText(
            listOf(req), droppedRequests = 1, tapDropped = 3, tapFailed = true
        )
        check("requests text has request drop note",
            text.contains("古いリクエスト記録 1 件を破棄しました"), text)
        check("requests text has tap drop note",
            text.contains("タップで 3 件のメッセージを記録できませんでした"), text)
        check("requests text has tap failure note",
            text.contains("タップが途中で停止したため"), text)
        check("requests text has the request",
            text.contains("GET https://example.com/ -> 200"), text)
    }

    // No losses: no notes at all.
    run {
        val console = recorder.toText(listOf(entry), emptyMap(), droppedEntries = 0)
        val requests = recorder.toRequestsText(listOf(req), droppedRequests = 0)
        check("no losses no notes in console text",
            !console.contains("破棄しました") && !console.contains("タップ"), console)
        check("no losses no notes in requests text",
            !requests.contains("破棄しました") && !requests.contains("タップ"), requests)
    }

    // A drop without a failure: only the drop note.
    run {
        val text = recorder.toRequestsText(
            listOf(req), droppedRequests = 0, tapDropped = 1, tapFailed = false
        )
        check("drop without failure has only the drop note",
            text.contains("タップで 1 件") && !text.contains("途中で停止"), text)
    }

    // Audit hardening: page-controlled fields cannot forge
    // lines in the text exports.
    run {
        val hostile = ConsoleLogRecorder.Entry(
            timeMs = 0L, level = "log",
            text = "one\ntwo\rthree\u2028four",
            source = "console-api",
            url = "https://example.com/\ninjected",
            line = 1
        )
        val text = recorder.toText(listOf(hostile), emptyMap(), droppedEntries = 0)
        check("console text escapes CR in entry text",
            text.contains("two\\rthree"), text)
        check("console text escapes U+2028 in entry text",
            text.contains("three\\u2028four"), text)
        check("console text escapes LF in entry url",
            text.contains("example.com/\\ninjected"), text)
        check("console text has no forged line",
            !text.contains("\ninjected"), text)
        val hostileReq = ConsoleLogRecorder.NetReq(
            requestId = "r2", startWallMs = 0L, method = "POST",
            url = "https://example.com/up", status = null,
            postData = "a\u2029b",
            initiator = "https://example.com/\revil"
        )
        val reqText = recorder.toRequestsText(listOf(hostileReq), droppedRequests = 0)
        check("requests text escapes U+2029 in postData",
            reqText.contains("a\\u2029b"), reqText)
        check("requests text escapes CR in initiator",
            reqText.contains("\\revil"), reqText)
    }

    // The shared line escape is lossless: a literal
    // backslash doubles, and NEL is escaped too.
    run {
        val bsReq = ConsoleLogRecorder.NetReq(
            requestId = "r3", startWallMs = 0L, method = "POST",
            url = "https://example.com/up", status = null,
            postData = "a\\b\u0085c"
        )
        val bsText = recorder.toRequestsText(listOf(bsReq), droppedRequests = 0)
        check("requests text doubles a literal backslash",
            bsText.contains("a\\\\b"), bsText)
        check("requests text escapes NEL",
            bsText.contains("b\\u0085c"), bsText)
    }

    println("RecorderTextTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
