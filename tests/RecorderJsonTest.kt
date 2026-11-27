package tests

import jp.kaleidpixel.pageloupe.ConsoleLogRecorder
import org.json.JSONArray
import org.json.JSONObject

// JVM tests for the org.json side of ConsoleLogRecorder: the
// CDP ingestion (onCdpMessage) and the console JSON export
// (writeConsoleJson, driven through a tree-building JsonSink
// so the assertions stay on parsed structure). These need a
// WORKING org.json, so the suite runs with the test-only
// JSON-java jar (pinned by SHA-256) ahead of android.jar.
//
// Scope (owner decision 2026-11-21, ledger SEN-19): JSON-java
// and Android's org.json diverge in edge behavior — key order
// in toString(), optString for a JSON null, duplicate keys,
// nesting depth, number formatting. The checks below assert
// the recorder's own logic (structure and values) only:
// stored header strings are parsed back and compared as maps,
// never as strings, and no expectation depends on the edge
// behaviors above. Byte-level output equality stays verified
// on device. Run via the runUnitTests Gradle task.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

/** A JsonSink that rebuilds the org.json tree, so the
 * streaming console export is asserted as parsed JSON. */
private class TreeSink : ConsoleLogRecorder.JsonSink {
    private val stack = ArrayDeque<Any>()
    private var pendingName: String? = null
    var root: JSONArray? = null

    private fun add(v: Any?) {
        val value = v ?: JSONObject.NULL
        when (val top = stack.lastOrNull()) {
            is JSONArray -> top.put(value)
            is JSONObject -> top.put(pendingName!!, value)
            null -> root = value as JSONArray
            else -> error("unexpected container: $top")
        }
        pendingName = null
    }

    override fun beginArray(): ConsoleLogRecorder.JsonSink =
        apply { val a = JSONArray(); add(a); stack.addLast(a) }
    override fun endArray(): ConsoleLogRecorder.JsonSink =
        apply { stack.removeLast() }
    override fun beginObject(): ConsoleLogRecorder.JsonSink =
        apply { val o = JSONObject(); add(o); stack.addLast(o) }
    override fun endObject(): ConsoleLogRecorder.JsonSink =
        apply { stack.removeLast() }
    override fun name(name: String): ConsoleLogRecorder.JsonSink =
        apply { pendingName = name }
    override fun value(value: String?): ConsoleLogRecorder.JsonSink =
        apply { add(value) }
    override fun value(value: Long): ConsoleLogRecorder.JsonSink =
        apply { add(value) }
    override fun value(value: Boolean): ConsoleLogRecorder.JsonSink =
        apply { add(value) }
    override fun value(value: Number): ConsoleLogRecorder.JsonSink =
        apply { add(value) }
    override fun nullValue(): ConsoleLogRecorder.JsonSink =
        apply { add(null) }
    override fun flush() {}
}

private fun consoleJson(
    r: ConsoleLogRecorder,
    list: List<ConsoleLogRecorder.Entry>,
    net: Map<String, ConsoleLogRecorder.NetReq>
): String {
    val sink = TreeSink()
    r.writeConsoleJson(list, net, sink)
    return sink.root!!.toString()
}

fun main() {
    // Guard: the implementation under test really is the
    // pinned JSON-java jar, not the android.jar stub. Fail
    // fast with a clear message instead of a stub crash
    // halfway through the suite. The expected jar name comes
    // from the Gradle build (the single source for which jar is
    // pinned); this guard only detects a stub mix-up — the
    // jar's integrity is the script's hash check, not this.
    run {
        // No fallback copy of the name here: without the
        // property the suite was not started by the Gradle task,
        // which is the only supported way to run it.
        val expected = System.getProperty("pageloupe.test.jsonJar")
        if (expected.isNullOrEmpty()) {
            println("RecorderJson: run via the runUnitTests Gradle task (it passes the pinned jar name) — aborting")
            kotlin.system.exitProcess(1)
        }
        val loc = JSONObject::class.java.protectionDomain
            ?.codeSource?.location?.path ?: ""
        if (!loc.endsWith("/$expected")) {
            println("RecorderJson: org.json resolved from '$loc', not the pinned tests/lib jar — aborting")
            kotlin.system.exitProcess(1)
        }
    }

    // Log.entryAdded: fields, 1-based line, timestamp kept as ms.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"network","level":"error","text":"Failed to load resource","timestamp":1700000000000,"url":"https://example.com/x.js","lineNumber":0,"networkRequestId":"req-9"}}}"""
        )
        val e = r.snapshot().single()
        check("log: level", e.level == "error")
        check("log: text", e.text == "Failed to load resource")
        check("log: source", e.source == "network")
        check("log: url", e.url == "https://example.com/x.js")
        check("log: line is 1-based", e.line == 1)
        check("log: timestamp ms kept", e.timeMs == 1700000000000L)
        check("log: networkRequestId", e.networkRequestId == "req-9")
        check("log: raw kept", e.raw != null &&
            JSONObject(e.raw!!).optString("method") == "Log.entryAdded")
        check("log: counters", r.size() == 1 && r.messagesSeen == 1 && r.parseFailures == 0)
    }

    // Runtime.consoleAPICalled: args stringified and joined,
    // seconds timestamp scaled to ms, top frame applied.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[{"type":"string","value":"hello"},{"type":"number","value":42},{"type":"object","description":"Array(2)"},{"type":"undefined"}],"timestamp":1700000000.25,"stackTrace":{"callFrames":[{"functionName":"main","scriptId":"7","url":"https://example.com/app.js","lineNumber":10,"columnNumber":4}]}}}"""
        )
        val e = r.snapshot().single()
        check("console: text joined", e.text == "hello 42 Array(2) undefined")
        check("console: level from type", e.level == "log")
        check("console: source", e.source == "console")
        check("console: timestamp seconds scaled", e.timeMs == 1700000000250L)
        check("console: frame url", e.url == "https://example.com/app.js")
        check("console: frame line/column 1-based", e.line == 11 && e.column == 5)
        check("console: frame kept", e.frames.size == 1 &&
            e.frames[0].functionName == "main" && e.frames[0].scriptId == "7")
    }

    // Runtime.exceptionThrown: exception description wins,
    // level error, source javascript.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Runtime.exceptionThrown","params":{"exceptionDetails":{"text":"Uncaught ReferenceError: x is not defined","url":"https://example.com/a.js","lineNumber":4,"exception":{"description":"ReferenceError: x is not defined"}}}}"""
        )
        val e = r.snapshot().single()
        check("exception: text from description", e.text == "ReferenceError: x is not defined")
        check("exception: level/source", e.level == "error" && e.source == "javascript")
        check("exception: line 1-based", e.line == 5 && e.url == "https://example.com/a.js")
    }

    // Network flow: request -> response -> finished assembles
    // one record; headers are compared parsed, not as strings.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"r1","timestamp":100.0,"request":{"method":"GET","url":"https://example.com/","headers":{"Accept":"text/html","X-Test":"1"}},"type":"Document","initiator":{"type":"script","stack":{"callFrames":[{"functionName":"load","scriptId":"3","url":"https://example.com/app.js","lineNumber":2,"columnNumber":0}]}}}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.responseReceived","params":{"requestId":"r1","type":"Document","response":{"url":"https://example.com/","status":200,"statusText":"OK","mimeType":"text/html","headers":{"Content-Type":"text/html"}}}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.loadingFinished","params":{"requestId":"r1","timestamp":100.25,"encodedDataLength":1234}}"""
        )
        val nr = r.requestsSnapshot().single()
        check("net: method/url/type", nr.method == "GET" &&
            nr.url == "https://example.com/" && nr.type == "Document")
        check("net: hop 0", nr.hop == 0)
        check("net: status", nr.status == 200 && nr.statusText == "OK")
        check("net: mime", nr.mimeType == "text/html")
        check("net: duration", nr.durationMs == 250L)
        check("net: encoded bytes", nr.encodedBytes == 1234L)
        val reqH = JSONObject(nr.requestHeaders!!)
        check("net: request headers as map", reqH.length() == 2 &&
            reqH.optString("Accept") == "text/html" && reqH.optString("X-Test") == "1")
        val respH = JSONObject(nr.responseHeaders!!)
        check("net: response headers as map", respH.length() == 1 &&
            respH.optString("Content-Type") == "text/html")
        check("net: initiator frame ref", nr.initiator == "https://example.com/app.js:3")
    }

    // Redirect: the previous hop is finalized (status from the
    // redirect response, duration up to the redirect) and a
    // new hop record takes over the id.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"r2","timestamp":200.0,"request":{"method":"GET","url":"https://example.com/a"},"type":"Document"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"r2","timestamp":200.5,"redirectResponse":{"status":301,"statusText":"Moved Permanently"},"request":{"method":"GET","url":"https://example.com/b"},"type":"Document"}}"""
        )
        val list = r.requestsSnapshot()
        check("redirect: two hops", list.size == 2 && list[0].hop == 0 && list[1].hop == 1)
        check("redirect: first hop finalized", list[0].status == 301 &&
            list[0].statusText == "Moved Permanently" && list[0].durationMs == 500L)
        check("redirect: second hop url", list[1].url == "https://example.com/b")
        check("redirect: id now maps to hop 1", r.requestsAndNetwork().second["r2"]?.hop == 1)
    }

    // loadingFailed: error text, canceled suffix; events for
    // unknown ids are ignored without side effects.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.responseReceived","params":{"requestId":"ghost","response":{"status":200}}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"r3","timestamp":300.0,"request":{"method":"GET","url":"https://example.com/c"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.loadingFailed","params":{"requestId":"r3","timestamp":300.5,"errorText":"net::ERR_FAILED","canceled":true}}"""
        )
        val list = r.requestsSnapshot()
        check("failed: unknown id ignored", list.size == 1)
        check("failed: error with canceled suffix",
            list[0].errorText == "net::ERR_FAILED (canceled)")
        check("failed: duration", list[0].durationMs == 500L)
    }

    // Pre-filter and failure counting: unhandled methods are
    // noted without a parse, a message without a method is
    // ignored, and a truncated handled message counts as a
    // parse failure (broad behavior only — how a parser treats
    // broken input differs between implementations).
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage("""{"method":"Page.frameNavigated","params":{}}""")
        r.onCdpMessage("""{"foo":1}""")
        r.onCdpMessage("""{"method":"Log.entryAdded","params":{"entry":""")
        check("filter: nothing recorded", r.size() == 0 && r.requestsSnapshot().isEmpty())
        check("filter: all seen", r.messagesSeen == 3)
        check("filter: unhandled method noted", r.recentMethods() == listOf("Page.frameNavigated"))
        check("filter: parse failure counted", r.parseFailures == 1 &&
            r.lastParseSample.isNotEmpty())
    }

    // Target wrapping: the inner message goes through the same
    // entry point (and the same counters). The outer message
    // is a fixed literal in real CDP shape (method first), so
    // the test does not depend on a serializer's key order.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Target.receivedMessageFromTarget","params":{"message":"{\"method\":\"Log.entryAdded\",\"params\":{\"entry\":{\"source\":\"javascript\",\"level\":\"warning\",\"text\":\"inner\",\"timestamp\":1700000000000}}}"}}"""
        )
        check("target: inner recorded", r.size() == 1 && r.snapshot()[0].text == "inner")
        check("target: both messages seen", r.messagesSeen == 2)
        val r2 = ConsoleLogRecorder()
        r2.onCdpMessage("""{"method":"Target.receivedMessageFromTarget","params":{}}""")
        // A missing message records nothing and is not a parse
        // failure. (It currently reads as an empty message and
        // passes through the entry point once more — an
        // artifact of optString never returning null, not a
        // designed contract, so the seen count is not pinned.)
        check("target: missing message records nothing",
            r2.size() == 0 && r2.parseFailures == 0)
    }

    // Field cap: an oversize entry text is truncated with the
    // visible marker, never cut silently.
    run {
        val r = ConsoleLogRecorder()
        val big = "x".repeat(40_000)
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"javascript","level":"log","text":"$big","timestamp":1700000000000}}}"""
        )
        val text = r.snapshot().single().text
        check("cap: truncated with marker", text.length == 32_768 + "…[truncated]".length &&
            text.endsWith("[truncated]") && text.startsWith("xxxx"))
    }

    // Count caps: entries and requests keep the newest 5000
    // and count the evicted oldest.
    run {
        val r = ConsoleLogRecorder()
        for (i in 0..5000) {
            r.onCdpMessage(
                """{"method":"Log.entryAdded","params":{"entry":{"source":"javascript","level":"log","text":"m$i","timestamp":1700000000000}}}"""
            )
        }
        check("cap: entries keep newest 5000", r.size() == 5000 && r.entriesDropped == 1 &&
            r.snapshot().first().text == "m1")
        val r2 = ConsoleLogRecorder()
        for (i in 0..5000) {
            r2.onCdpMessage(
                """{"method":"Network.requestWillBeSent","params":{"requestId":"q$i","timestamp":1.0,"request":{"method":"GET","url":"https://example.com/$i"}}}"""
            )
        }
        val snap = r2.requestsSnapshot()
        check("cap: requests keep newest 5000", snap.size == 5000 &&
            r2.requestsDropped == 1 && snap.last().requestId == "q5000")
    }

    // writeConsoleJson: structure and values of the console export,
    // parsed back (never compared as a raw string).
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"r1","timestamp":100.0,"request":{"method":"GET","url":"https://example.com/"},"type":"Document"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.responseReceived","params":{"requestId":"r1","response":{"status":200,"mimeType":"text/html"}}}"""
        )
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"network","level":"error","text":"Failed to load resource","timestamp":1700000000000,"url":"https://example.com/x.js","lineNumber":0,"networkRequestId":"r1"}}}"""
        )
        r.onCdpMessage(
            """{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[{"type":"string","value":"hi"}],"timestamp":1700000000.0,"stackTrace":{"callFrames":[{"functionName":"main","scriptId":"7","url":"https://example.com/app.js","lineNumber":10,"columnNumber":4}]}}}"""
        )
        val (reqs, byId) = r.requestsAndNetwork()
        check("consoleJson: requestsAndNetwork consistent", reqs.size == 1 && byId.size == 1)
        val arr = JSONArray(consoleJson(r, r.snapshot(), byId))
        check("consoleJson: two entries", arr.length() == 2)
        val o0 = arr.getJSONObject(0)
        check("consoleJson: time is ISO-shaped", o0.optString("time")
            .matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}.*""")))
        check("consoleJson: level/text", o0.optString("level") == "error" &&
            o0.optString("text") == "Failed to load resource")
        val net = o0.optJSONObject("network")
        check("consoleJson: network block", net != null &&
            net.optString("requestId") == "r1" && net.optInt("status") == 200 &&
            net.optString("method") == "GET")
        check("consoleJson: raw is a string carrying the event",
            JSONObject(o0.optString("raw")).optString("method") == "Log.entryAdded")
        val o1 = arr.getJSONObject(1)
        val frames = o1.optJSONArray("stackTrace")
        check("consoleJson: stackTrace block", frames != null && frames.length() == 1 &&
            frames.getJSONObject(0).optString("functionName") == "main" &&
            frames.getJSONObject(0).optInt("line") == 11)
        check("consoleJson: no network block without a request", !o1.has("network"))
    }

    // Raw cap: when the original message exceeds the raw cap,
    // the entry keeps no raw copy (PER-106).
    run {
        val r = ConsoleLogRecorder()
        val big = "x".repeat(70_000)
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"javascript","level":"log","text":"$big","timestamp":1700000000000}}}"""
        )
        check("raw cap: oversize message keeps no raw", r.snapshot().single().raw == null)
    }

    // Headers cap: an oversize headers object is replaced by
    // the visible marker, never truncated mid-JSON (SEN-8).
    run {
        val r = ConsoleLogRecorder()
        val big = "v".repeat(40_000)
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"big","timestamp":1.0,"request":{"method":"GET","url":"https://example.com/","headers":{"X-Big":"$big"}}}}"""
        )
        check("headers cap: omitted marker",
            r.requestsSnapshot().single().requestHeaders == "<headers omitted: over size cap>")
    }

    // responseReceived replaces the response headers only
    // when a headers object was actually sent (SEN-8).
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"h","timestamp":1.0,"request":{"method":"GET","url":"https://example.com/"}}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.responseReceived","params":{"requestId":"h","response":{"status":200,"headers":{"A":"1"}}}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.responseReceived","params":{"requestId":"h","response":{"status":200}}}"""
        )
        val afterSecond = r.requestsSnapshot().single().responseHeaders
        check("resp headers: kept when none sent", afterSecond != null &&
            JSONObject(afterSecond).optString("A") == "1")
        r.onCdpMessage(
            """{"method":"Network.responseReceived","params":{"requestId":"h","response":{"status":200,"headers":{"B":"2"}}}}"""
        )
        val afterThird = r.requestsSnapshot().single().responseHeaders
        check("resp headers: replaced when sent", afterThird != null &&
            JSONObject(afterThird).length() == 1 &&
            JSONObject(afterThird).optString("B") == "2")
    }

    // A redirect response with no previous record is just a
    // fresh hop 0; the redirect status goes nowhere.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"orphan","timestamp":1.0,"redirectResponse":{"status":301},"request":{"method":"GET","url":"https://example.com/"}}}"""
        )
        val nr = r.requestsSnapshot().single()
        check("redirect orphan: fresh hop 0", nr.hop == 0 && nr.status == null)
    }

    // exceptionThrown fallbacks: no description -> the
    // details text; no text either -> the fixed default.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Runtime.exceptionThrown","params":{"exceptionDetails":{"text":"Uncaught TypeError: boom","exception":{}}}}"""
        )
        r.onCdpMessage(
            """{"method":"Runtime.exceptionThrown","params":{"exceptionDetails":{"exception":{}}}}"""
        )
        val list = r.snapshot()
        check("exception fallback: details text", list[0].text == "Uncaught TypeError: boom")
        check("exception fallback: default", list[1].text == "Uncaught exception")
    }

    // Initiator shapes: url-only initiator, a scriptId-only
    // stack frame, and postData on the request.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"i1","timestamp":1.0,"request":{"method":"POST","url":"https://example.com/submit","postData":"a=1&b=2"},"initiator":{"type":"other","url":"https://example.com/","lineNumber":0}}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"i2","timestamp":1.0,"request":{"method":"GET","url":"https://example.com/x"},"initiator":{"type":"script","stack":{"callFrames":[{"scriptId":"5","lineNumber":1}]}}}}"""
        )
        val list = r.requestsSnapshot()
        check("initiator: url-only with line", list[0].initiator == "https://example.com/:1")
        check("initiator: script frame ref", list[1].initiator == "script 5:2")
        check("request: postData kept", list[0].postData == "a=1&b=2" &&
            list[0].method == "POST")
    }

    // Log entry without its own line/url takes them from the
    // top stack frame.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"javascript","level":"log","text":"t","timestamp":1700000000000,"stackTrace":{"callFrames":[{"url":"https://example.com/f.js","scriptId":"9","lineNumber":3,"columnNumber":1}]}}}}"""
        )
        val e = r.snapshot().single()
        check("log frame: url/line/column/scriptId from top frame",
            e.url == "https://example.com/f.js" && e.line == 4 &&
                e.column == 2 && e.scriptId == "9")
    }

    // Frame cap: a pathological stack is cut at MAX_FRAMES.
    run {
        val r = ConsoleLogRecorder()
        val frames = (0 until 40).joinToString(",") {
            """{"functionName":"f$it","scriptId":"1","url":"https://example.com/a.js","lineNumber":0,"columnNumber":0}"""
        }
        r.onCdpMessage(
            """{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[],"timestamp":1700000000.0,"stackTrace":{"callFrames":[$frames]}}}"""
        )
        check("frames: capped at 32", r.snapshot().single().frames.size == 32)
    }

    // stringifyArg edges. The null array element is the one
    // deliberate JSON-null exception (see tests/lib/README.md):
    // optJSONObject returns null for a non-object element in
    // both implementations (a type check, not the divergent
    // optString behavior), and stringifyArg maps that to
    // "undefined" in our own code. The single-key object value
    // cannot vary by key order.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[null,{"type":"object","value":{"a":1}}],"timestamp":1700000000.0}}"""
        )
        check("args: null element and object value",
            r.snapshot().single().text == """undefined {"a":1}""")
    }

    // Total-size budgets: past 8M chars the oldest records
    // are evicted (the count cap alone would allow 5000).
    // Assertions stay loose — the exact survivor count
    // depends on the serialized raw length.
    run {
        val r = ConsoleLogRecorder()
        val fed = 400
        for (i in 0 until fed) {
            val text = "e$i-" + "x".repeat(30_000)
            r.onCdpMessage(
                """{"method":"Log.entryAdded","params":{"entry":{"source":"javascript","level":"log","text":"$text","timestamp":1700000000000}}}"""
            )
        }
        val first = r.snapshot().first().text
        val firstIdx = first.substring(1, first.indexOf('-')).toInt()
        // Each entry costs at least its 30,005-char text, so
        // at most 8Mi/30,005 = 279 can survive the budget; the
        // newest entry must be among the survivors.
        check("budget: entries evicted by total size", r.size() < fed &&
            r.size() <= 279 && r.entriesDropped == fed - r.size() && firstIdx > 0 &&
            r.snapshot().last().text.startsWith("e399-"))
        val r2 = ConsoleLogRecorder()
        for (i in 0 until fed) {
            r2.onCdpMessage(
                """{"method":"Network.requestWillBeSent","params":{"requestId":"b$i","timestamp":1.0,"request":{"method":"POST","url":"https://example.com/$i","postData":"${"p".repeat(30_000)}"}}}"""
            )
        }
        val snap2 = r2.requestsSnapshot()
        check("budget: requests evicted by total size", snap2.size < fed &&
            snap2.size <= 279 && r2.requestsDropped == fed - snap2.size &&
            snap2.last().requestId == "b399")
    }

    // Deliberately NOT pinned: evictNetLocked's identity-based
    // removal (SEN-16) — that eviction never removes a
    // value-equal twin of the record being updated. NetReq is
    // a data class whose equality includes startWallMs (the
    // construction wall clock), so two value-equal but
    // distinct records cannot be constructed deterministically
    // through the public API, and a non-deterministic test
    // would be worse than none. The eviction loops themselves
    // are covered by the count-cap and budget tests above.

    // writeConsoleJson, continued: a network block for an unknown id
    // carries only the requestId; errorText and initiator are
    // emitted; absent optionals stay absent; empty in, empty
    // out.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"e1","timestamp":1.0,"request":{"method":"GET","url":"https://example.com/"},"initiator":{"type":"other","url":"https://example.com/","lineNumber":0}}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.loadingFailed","params":{"requestId":"e1","timestamp":1.5,"errorText":"net::ERR_FAILED"}}"""
        )
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"level":"error","text":"with net","timestamp":1700000000000,"networkRequestId":"e1"}}}"""
        )
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"level":"log","text":"missing net","timestamp":1700000000000,"networkRequestId":"nope"}}}"""
        )
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"level":"log","text":"plain","timestamp":1700000000000}}}"""
        )
        val arr = JSONArray(consoleJson(r, r.snapshot(), r.requestsAndNetwork().second))
        val net0 = arr.getJSONObject(0).optJSONObject("network")
        check("consoleJson: network errorText/initiator", net0 != null &&
            net0.optString("errorText") == "net::ERR_FAILED" &&
            net0.optString("initiator") == "https://example.com/:1")
        val net1 = arr.getJSONObject(1).optJSONObject("network")
        check("consoleJson: unknown id block has only requestId", net1 != null &&
            net1.optString("requestId") == "nope" && !net1.has("status"))
        val o2 = arr.getJSONObject(2)
        check("consoleJson: absent optionals stay absent", !o2.has("source") &&
            !o2.has("url") && !o2.has("stackTrace") && !o2.has("network"))
        check("consoleJson: empty in, empty out",
            JSONArray(consoleJson(r, emptyList(), emptyMap())).length() == 0)
    }

    // The parse-failure sample is the first 100 chars with
    // newlines flattened — our own transformation of the
    // input, independent of the parser implementation.
    run {
        val r = ConsoleLogRecorder()
        val msg = "{\"method\":\"Log.entryAdded\",\"params\":{\"entry\":{\n\"text\":\"" +
            "y".repeat(200)
        r.onCdpMessage(msg)
        check("sample: 100 chars, flattened", r.lastParseSample.length == 100 &&
            !r.lastParseSample.contains('\n') &&
            r.lastParseSample == msg.take(100).replace('\n', ' '))
    }

    // FULL-SEC-Q1: credential values are redacted at
    // ingestion — headers by name, bodies by content type —
    // while names, structure and other values survive. (The
    // password-shaped strings are built through pwKey: the
    // literal shape must not appear in the source, the same
    // fixture discipline as the mail addresses.)
    val pwKey = "password"
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"s1","timestamp":100.0,"request":{"method":"POST","url":"https://example.com/login","headers":{"Authorization":"Bearer sekret","Cookie":"sid=abc","Content-Type":"application/x-www-form-urlencoded","X-Test":"1"},"postData":"user=kim&$pwKey=hunter2&next=%2Fhome"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.responseReceived","params":{"requestId":"s1","type":"XHR","response":{"url":"https://example.com/login","status":200,"statusText":"OK","mimeType":"text/html","headers":{"Set-Cookie":"sid=xyz; HttpOnly","Content-Type":"text/html"}}}}"""
        )
        val nr = r.requestsSnapshot().single()
        val reqH = JSONObject(nr.requestHeaders!!)
        check("redact: authorization header", reqH.optString("Authorization") == "<redacted>")
        check("redact: cookie header", reqH.optString("Cookie") == "<redacted>")
        check("redact: other headers survive", reqH.optString("X-Test") == "1")
        val respH = JSONObject(nr.responseHeaders!!)
        check("redact: set-cookie header", respH.optString("Set-Cookie") == "<redacted>")
        check("redact: response content-type survives",
            respH.optString("Content-Type") == "text/html")
        check("redact: form password, rest of body intact",
            nr.postData == "user=kim&$pwKey=<redacted>&next=%2Fhome")
    }

    // FULL-SEC-Q1: a JSON body's credential keys are redacted
    // recursively; a non-credential body passes through.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"s2","timestamp":100.0,"request":{"method":"POST","url":"https://example.com/api","headers":{"Content-Type":"application/json"},"postData":"{\"user\":\"kim\",\"auth\":{\"access_token\":\"tok123\"},\"note\":\"hi\"}"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"s3","timestamp":101.0,"request":{"method":"POST","url":"https://example.com/plain","headers":{"Content-Type":"text/plain"},"postData":"a free-form diagnostic note"},"type":"XHR"}}"""
        )
        val byId = r.requestsSnapshot().associateBy { it.url }
        val body = JSONObject(byId.getValue("https://example.com/api").postData!!)
        check("redact: json nested token",
            body.getJSONObject("auth").optString("access_token") == "<redacted>")
        check("redact: json other keys survive",
            body.optString("user") == "kim" && body.optString("note") == "hi")
        check("redact: free-form body untouched",
            byId.getValue("https://example.com/plain").postData ==
                "a free-form diagnostic note")
    }

    // Review round 2 (SEN-8 / SEN-9 / PER-7): derived key
    // names, credential headers beyond the exact set, an
    // unparseable JSON body, a JSON body sent as text/plain,
    // a token in the URL query, and verbatim preservation
    // of an unchanged JSON body.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"t1","timestamp":100.0,"request":{"method":"POST","url":"https://example.com/cb?access_token=tok123&next=%2Fhome","headers":{"X-Api-Key":"key-1","Content-Type":"application/x-www-form-urlencoded"},"postData":"new_$pwKey=hunter2&user[$pwKey]=hunter3&keep=1"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"t2","timestamp":101.0,"request":{"method":"POST","url":"https://example.com/trunc","headers":{"Content-Type":"application/json"},"postData":"{\"$pwKey\":\"hunter2"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"t3","timestamp":102.0,"request":{"method":"POST","url":"https://example.com/beacon","headers":{"Content-Type":"text/plain"},"postData":"{\"$pwKey\":\"hunter2\",\"keep\":1}"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"t4","timestamp":103.0,"request":{"method":"POST","url":"https://example.com/verbatim","headers":{"Content-Type":"application/json"},"postData":"{ \"amount\" : 1.50, \"note\" : \"hi\" }"},"type":"XHR"}}"""
        )
        val all = r.requestsSnapshot()
        val t1 = all.single { it.url?.contains("/cb") == true }
        check("redact2: derived form keys",
            t1.postData == "new_$pwKey=<redacted>&user[$pwKey]=<redacted>&keep=1")
        check("redact2: x-api-key header",
            JSONObject(t1.requestHeaders!!).optString("X-Api-Key") == "<redacted>")
        check("redact2: url query token",
            t1.url == "https://example.com/cb?access_token=<redacted>&next=%2Fhome")
        val t2 = all.single { it.url?.contains("/trunc") == true }
        check("redact2: unparseable json body withheld",
            t2.postData == "<redacted: unparseable body>")
        val t3 = all.single { it.url?.contains("/beacon") == true }
        check("redact2: text/plain json body",
            JSONObject(t3.postData!!).optString(pwKey) == "<redacted>")
        val t4 = all.single { it.url?.contains("/verbatim") == true }
        check("redact2: unchanged json body verbatim",
            t4.postData == "{ \"amount\" : 1.50, \"note\" : \"hi\" }")
    }

    // Review round 3: NDJSON and trailing garbage fail
    // closed (SEN-4), a form-shaped body is redacted under
    // any content type (SEC-6), token matching no longer
    // over-matches diagnostic names (SEN-5/PER-6), URLs are
    // redacted on every ingestion path (SEC-1) including
    // userinfo and fragment tokens (SEN-6), and a deeply
    // nested body fails closed instead of overflowing
    // (PER-8).
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"u1","timestamp":100.0,"request":{"method":"POST","url":"https://example.com/ndjson","headers":{"Content-Type":"application/json"},"postData":"{\"keep\":1}\n{\"$pwKey\":\"hunter2\"}"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"u2","timestamp":101.0,"request":{"method":"POST","url":"https://user:pw12345@example.com/x?ok=1","headers":{},"postData":""},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"u3","timestamp":102.0,"request":{"method":"POST","url":"https://example.com/cb#access_token=tok777&x=1","headers":{},"postData":""},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"u4","timestamp":103.0,"request":{"method":"POST","url":"https://example.com/formtext","headers":{"Content-Type":"text/plain"},"postData":"$pwKey=hunter2&next=home"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"u5","timestamp":104.0,"request":{"method":"POST","url":"https://example.com/llm","headers":{"Content-Type":"application/json",":authority":"example.com","www-authenticate":"Bearer","X-Author":"kim"},"postData":"{\"max_tokens\":5,\"footprint\":\"x\",\"keep\":1}"},"type":"XHR"}}"""
        )
        val byId = r.requestsSnapshot().associateBy { it.url }
        check("redact3: ndjson body withheld",
            byId.getValue("https://example.com/ndjson").postData ==
                "<redacted: unparseable body>")
        check("redact3: userinfo password",
            byId.getValue("https://user:<redacted>@example.com/x?ok=1").url ==
                "https://user:<redacted>@example.com/x?ok=1")
        check("redact3: fragment token",
            byId.getValue("https://example.com/cb#access_token=<redacted>&x=1").url ==
                "https://example.com/cb#access_token=<redacted>&x=1")
        check("redact3: form-shaped text/plain body",
            byId.getValue("https://example.com/formtext").postData ==
                "$pwKey=<redacted>&next=home")
        val u5 = byId.getValue("https://example.com/llm")
        val u5h = JSONObject(u5.requestHeaders!!)
        check("redact3: pseudo-header and challenge survive",
            u5h.optString(":authority") == "example.com" &&
                u5h.optString("www-authenticate") == "Bearer" &&
                u5h.optString("X-Author") == "kim")
        check("redact3: lookalike keys survive verbatim",
            u5.postData == "{\"max_tokens\":5,\"footprint\":\"x\",\"keep\":1}")
    }

    // Review round 3 (SEC-1): console entries — url, text
    // and raw — get the same query redaction as the network
    // fields.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"network","level":"error","text":"Failed to load resource: https://example.com/a?access_token=tok999&ok=1","url":"https://example.com/s.js?api_token=zz777","timestamp":1700000000000}}}"""
        )
        val e = r.snapshot().single()
        check("redact3: log entry url",
            e.url == "https://example.com/s.js?api_token=<redacted>")
        check("redact3: log entry text",
            e.text.contains("access_token=<redacted>") &&
                !e.text.contains("tok999"))
        check("redact3: log entry raw",
            e.raw != null && !e.raw!!.contains("tok999") &&
                !e.raw!!.contains("zz777"))
    }

    // Review round 3 (PER-8): a pathologically nested body
    // fails closed rather than overflowing the stack.
    run {
        val r = ConsoleLogRecorder()
        val deep = "[".repeat(50_000) + "]".repeat(50_000)
        val msg = JSONObject()
            .put("method", "Network.requestWillBeSent")
            .put(
                "params", JSONObject()
                    .put("requestId", "u9")
                    .put("timestamp", 100.0)
                    .put(
                        "request", JSONObject()
                            .put("method", "POST")
                            .put("url", "https://example.com/deep")
                            .put("headers", JSONObject().put("Content-Type", "application/json"))
                            .put("postData", deep)
                    )
                    .put("type", "XHR")
            )
        r.onCdpMessage(msg.toString())
        check("redact3: deep nesting withheld",
            r.requestsSnapshot().single().postData == "<redacted: unparseable body>")
    }

    // Review round 4: the token matcher regains the
    // concatenated real-world names round 3 lost (SEN-4),
    // trailing whitespace no longer condemns a JSON body
    // (SEN-7), text-level redaction is escape-aware and
    // covers fragments and userinfo (SEN-5/SEC-1), URL-
    // valued headers get query redaction (SEC-6), and a
    // pathless URL is not mistaken for userinfo (SEN-6).
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"v1","timestamp":100.0,"request":{"method":"POST","url":"https://example.com/names","headers":{"Content-Type":"application/json","X-CSRFToken":"csrfval","X-Authorization":"Bearer abc"},"postData":"{\"password1\":\"a\",\"accesstoken\":\"b\",\"CSRFToken\":\"c\",\"csrfmiddlewaretoken\":\"d\",\"keep\":1}"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"v2","timestamp":101.0,"request":{"method":"POST","url":"https://example.com/trail","headers":{"Content-Type":"application/json"},"postData":"{\"keep\":1}\n"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"v3","timestamp":102.0,"request":{"method":"GET","url":"https://example.com/sub","headers":{"Referer":"https://example.com/p?access_token=tok888&ok=1"}},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"v4","timestamp":103.0,"request":{"method":"GET","url":"https://example.com?time=12:30&mail=a@b.com","headers":{}},"type":"XHR"}}"""
        )
        val byId = r.requestsSnapshot().associateBy { it.url }
        val v1 = byId.getValue("https://example.com/names")
        val v1body = JSONObject(v1.postData!!)
        check("redact4: concatenated names redacted",
            v1body.optString("password1") == "<redacted>" &&
                v1body.optString("accesstoken") == "<redacted>" &&
                v1body.optString("CSRFToken") == "<redacted>" &&
                v1body.optString("csrfmiddlewaretoken") == "<redacted>" &&
                v1body.optInt("keep") == 1)
        val v1h = JSONObject(v1.requestHeaders!!)
        check("redact4: framework headers redacted",
            v1h.optString("X-CSRFToken") == "<redacted>" &&
                v1h.optString("X-Authorization") == "<redacted>")
        check("redact4: trailing newline body preserved",
            byId.getValue("https://example.com/trail").postData == "{\"keep\":1}\n")
        val v3h = JSONObject(byId.getValue("https://example.com/sub").requestHeaders!!)
        check("redact4: referer query redacted",
            v3h.optString("Referer") ==
                "https://example.com/p?access_token=<redacted>&ok=1")
        check("redact4: pathless url not userinfo",
            byId.getValue("https://example.com?time=12:30&mail=a@b.com").url ==
                "https://example.com?time=12:30&mail=a@b.com")
    }

    // Review round 4 (SEN-5/SEC-1): text-level redaction
    // survives JSON-escaped slashes and covers URLs with
    // no '?' at all (fragment tokens, userinfo).
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"javascript","level":"log","text":"escaped https:\\/\\/example.com\\/a?next=\\/home&access_token=tok555 and frag https://example.com/app#access_token=tok666 and ui https://user:pw999@example.com/x end","timestamp":1700000000000}}}"""
        )
        val e = r.snapshot().single()
        check("redact4: escaped-slash run fully redacted",
            e.text.contains("access_token=<redacted>") &&
                !e.text.contains("tok555"))
        check("redact4: fragment-only url in text",
            !e.text.contains("tok666"))
        check("redact4: userinfo in text",
            e.text.contains("user:<redacted>@example.com") &&
                !e.text.contains("pw999"))
    }

    // Review round 5: the escaped scheme form is
    // recognized for userinfo (SEN-1/SEC-1), scalar-only
    // names redact in pair and scalar-JSON contexts but
    // not as containers (SEC-2), more bearer names are
    // covered (SEC-3), compound plurals are credential
    // lists (PER-2), and CORS policy headers survive
    // (SEC-4).
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"javascript","level":"log","text":"esc https:\\/\\/user:pw777@example.com\\/x end","timestamp":1700000000000}}}"""
        )
        val e = r.snapshot().single()
        check("redact5: escaped userinfo in text and raw",
            !e.text.contains("pw777") && !(e.raw?.contains("pw777") ?: false))
    }
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"w1","timestamp":100.0,"request":{"method":"POST","url":"https://example.com/pairs?key=AIza1&sig=zzz&ok=1","headers":{"Content-Type":"application/x-www-form-urlencoded"},"postData":"auth=tok123&ok=1"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"w2","timestamp":101.0,"request":{"method":"POST","url":"https://example.com/j","headers":{"Content-Type":"application/json","Access-Control-Allow-Credentials":"true"},"postData":"{\"auth\":\"Bearer abc\",\"private_key\":\"k\",\"refresh_tokens\":[\"a\"],\"client_secrets\":\"x\",\"keep\":1}"},"type":"XHR"}}"""
        )
        val byId = r.requestsSnapshot().associateBy { it.url }
        val w1 = byId.getValue("https://example.com/pairs?key=<redacted>&sig=<redacted>&ok=1")
        check("redact5: pair scalar names",
            w1.postData == "auth=<redacted>&ok=1")
        val w2 = byId.getValue("https://example.com/j")
        val w2b = JSONObject(w2.postData!!)
        check("redact5: json scalar and compound names",
            w2b.optString("auth") == "<redacted>" &&
                w2b.optString("private_key") == "<redacted>" &&
                org.json.JSONArray(w2b.optString("refresh_tokens"))
                    .optString(0) == "<redacted>" &&
                w2b.optString("client_secrets") == "<redacted>" &&
                w2b.optInt("keep") == 1)
        val w2h = JSONObject(w2.requestHeaders!!)
        check("redact5: cors header survives",
            w2h.optString("Access-Control-Allow-Credentials") == "true")
    }

    // Review round 6: compound plurals and ambiguous
    // names are gated on the value's shape (SEN-1/PER-2/
    // PER-3) — string-shaped values redact, numbers and
    // booleans keep both value and type; plural key
    // compounds and string-array credentials redact
    // (SEC-2/SEC-3); numeric rate-limit headers survive.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"x1","timestamp":100.0,"request":{"method":"POST","url":"https://example.com/usage","headers":{"Content-Type":"application/json","X-Ratelimit-Remaining-Tokens":"42","X-Api-Keys":"k1,k2"},"postData":"{\"cache_read_input_tokens\":123,\"refresh_tokens\":5,\"api_keys\":[\"sk-1\"],\"accesstokens\":\"zzz\",\"auth\":[\"Bearer q\"],\"sid\":42,\"pass\":true,\"keep\":1}"},"type":"XHR"}}"""
        )
        val x1 = r.requestsSnapshot().single()
        val b = JSONObject(x1.postData!!)
        check("redact6: usage numbers keep value and type",
            b.optInt("cache_read_input_tokens") == 123 &&
                b.optInt("refresh_tokens") == 5 &&
                b.optInt("sid") == 42 && b.optBoolean("pass"))
        check("redact6: string-shaped credentials redact",
            org.json.JSONArray(b.optString("api_keys"))
                    .optString(0) == "<redacted>" &&
                b.optString("accesstokens") == "<redacted>" &&
                org.json.JSONArray(b.optString("auth"))
                    .optString(0) == "<redacted>" &&
                b.optInt("keep") == 1)
        val h = JSONObject(x1.requestHeaders!!)
        check("redact6: numeric rate-limit header survives",
            h.optString("X-Ratelimit-Remaining-Tokens") == "42")
        check("redact6: plural key header redacts",
            h.optString("X-Api-Keys") == "<redacted>")
    }

    // Review round 7: empty-username userinfo redacts in
    // URLs and in text (SEN-1); plural-named containers
    // redact their strings wholesale (SEC-1); mixed
    // arrays redact per element (SEN-3); empty arrays
    // keep their type; duration-form rate-limit headers
    // survive (SEN-5).
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"y1","timestamp":100.0,"request":{"method":"GET","url":"https://:pw999@example.com/pkg","headers":{"X-Ratelimit-Reset-Tokens":"6m0s"}},"type":"Other"}}"""
        )
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"javascript","level":"log","text":"conn https://:pw888@example.com/ end","timestamp":1700000000000}}}"""
        )
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"y2","timestamp":101.0,"request":{"method":"POST","url":"https://example.com/maps","headers":{"Content-Type":"application/json"},"postData":"{\"api_keys\":{\"prod\":\"sk-prod\"},\"refresh_tokens\":[\"rt-1\",null],\"key\":[],\"fcm_tokens\":{\"device1\":\"AAAA\"}}"},"type":"XHR"}}"""
        )
        val y1 = r.requestsSnapshot().first { it.url?.contains(":pw999") == true || it.url?.contains(":<redacted>@") == true }
        check("redact7: empty-username userinfo in url",
            y1.url == "https://:<redacted>@example.com/pkg")
        val y1h = JSONObject(y1.requestHeaders!!)
        check("redact7: duration rate-limit header survives",
            y1h.optString("X-Ratelimit-Reset-Tokens") == "6m0s")
        val e = r.snapshot().single()
        check("redact7: empty-username userinfo in text",
            !e.text.contains("pw888"))
        val y2 = r.requestsSnapshot().first { it.url?.contains("maps") == true }
        val b = JSONObject(y2.postData!!)
        check("redact7: plural containers redact deeply",
            JSONObject(b.optString("api_keys")).optString("prod") == "<redacted>" &&
                JSONObject(b.optString("fcm_tokens")).optString("device1") == "<redacted>" &&
                !y2.postData!!.contains("rt-1"))
        check("redact7: empty array keeps its type",
            b.opt("key") is org.json.JSONArray)
    }

    // Review round 8: explicit names keep their any-type
    // rule inside plural containers (PER-1), nested
    // arrays under ambiguous names redact their strings
    // (SEN-1), non-counter plural headers redact (SEN-3),
    // and the raw copy covers the empty-username form
    // (SEC-4).
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"z1","timestamp":100.0,"request":{"method":"POST","url":"https://example.com/deep","headers":{"Content-Type":"application/json","X-Api-Tokens":"abc-def"},"postData":"{\"refresh_tokens\":[{\"pin\":1234,\"note\":\"n\"},\"rt-9\",null],\"key\":[[\"nested-secret\"]],\"mix\":[\"s\",1]}"},"type":"XHR"}}"""
        )
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"javascript","level":"log","text":"go https://:pw777@example.com/x now","timestamp":1700000000000}}}"""
        )
        val z1 = r.requestsSnapshot().single()
        val b = JSONObject(z1.postData!!)
        val rt = org.json.JSONArray(b.optString("refresh_tokens"))
        check("redact8: numeric pin inside plural container",
            JSONObject(rt.optString(0)).optString("pin") == "<redacted>" &&
                !z1.postData!!.contains("1234") &&
                rt.optString(1) == "<redacted>" &&
                rt.isNull(2))
        check("redact8: nested array under ambiguous name",
            !z1.postData!!.contains("nested-secret"))
        val h = JSONObject(z1.requestHeaders!!)
        check("redact8: non-counter plural header redacts",
            h.optString("X-Api-Tokens") == "<redacted>")
        val e = r.snapshot().single()
        check("redact8: raw carries no empty-username password",
            !(e.raw?.contains("pw777") ?: false) &&
                e.text == "go https://:<redacted>@example.com/x now")
    }

    // clear() resets the collections and the counters.
    run {
        val r = ConsoleLogRecorder()
        r.onCdpMessage(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"javascript","level":"log","text":"t","timestamp":1700000000000}}}"""
        )
        r.clear()
        check("clear: empty and counters reset", r.size() == 0 &&
            r.messagesSeen == 0 && r.parseFailures == 0 && r.requestsSnapshot().isEmpty())
    }

    if (failures > 0) {
        println("RecorderJson: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("RecorderJson: $checks checks, 0 failures")
}
