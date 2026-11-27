package tests

import jp.kaleidpixel.pageloupe.ConsoleLogRecorder
import org.json.JSONArray
import org.json.JSONObject

// JVM tests for the org.json side of ConsoleLogRecorder: the
// CDP ingestion (onCdpMessage) and the console JSON export
// (toJson). These need a WORKING org.json, so run_tests.sh
// runs this suite with the test-only JSON-java jar
// (tests/lib, pinned by SHA-256) ahead of android.jar.
//
// Scope (owner decision 2026-11-21, ledger SEN-19): JSON-java
// and Android's org.json diverge in edge behavior — key order
// in toString(), optString for a JSON null, duplicate keys,
// nesting depth, number formatting. The checks below assert
// the recorder's own logic (structure and values) only:
// stored header strings are parsed back and compared as maps,
// never as strings, and no expectation depends on the edge
// behaviors above. Byte-level output equality stays verified
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
    // Guard: the implementation under test really is the
    // pinned JSON-java jar, not the android.jar stub. Fail
    // fast with a clear message instead of a stub crash
    // halfway through the suite. The expected jar name comes
    // from run_tests.sh (the single source for which jar is
    // pinned); this guard only detects a stub mix-up — the
    // jar's integrity is the script's hash check, not this.
    run {
        // No fallback copy of the name here: without the
        // property the suite was not started by run_tests.sh,
        // which is the only supported way to run it.
        val expected = System.getProperty("pageloupe.test.jsonJar")
        if (expected.isNullOrEmpty()) {
            println("RecorderJson: run via tests/run_tests.sh (it passes the pinned jar name) — aborting")
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

    // toJson: structure and values of the console export,
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
        check("toJson: requestsAndNetwork consistent", reqs.size == 1 && byId.size == 1)
        val arr = JSONArray(r.toJson(r.snapshot(), byId))
        check("toJson: two entries", arr.length() == 2)
        val o0 = arr.getJSONObject(0)
        check("toJson: time is ISO-shaped", o0.optString("time")
            .matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}.*""")))
        check("toJson: level/text", o0.optString("level") == "error" &&
            o0.optString("text") == "Failed to load resource")
        val net = o0.optJSONObject("network")
        check("toJson: network block", net != null &&
            net.optString("requestId") == "r1" && net.optInt("status") == 200 &&
            net.optString("method") == "GET")
        check("toJson: raw is a string carrying the event",
            JSONObject(o0.optString("raw")).optString("method") == "Log.entryAdded")
        val o1 = arr.getJSONObject(1)
        val frames = o1.optJSONArray("stackTrace")
        check("toJson: stackTrace block", frames != null && frames.length() == 1 &&
            frames.getJSONObject(0).optString("functionName") == "main" &&
            frames.getJSONObject(0).optInt("line") == 11)
        check("toJson: no network block without a request", !o1.has("network"))
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

    // toJson, continued: a network block for an unknown id
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
        val arr = JSONArray(r.toJson(r.snapshot(), r.requestsAndNetwork().second))
        val net0 = arr.getJSONObject(0).optJSONObject("network")
        check("toJson: network errorText/initiator", net0 != null &&
            net0.optString("errorText") == "net::ERR_FAILED" &&
            net0.optString("initiator") == "https://example.com/:1")
        val net1 = arr.getJSONObject(1).optJSONObject("network")
        check("toJson: unknown id block has only requestId", net1 != null &&
            net1.optString("requestId") == "nope" && !net1.has("status"))
        val o2 = arr.getJSONObject(2)
        check("toJson: absent optionals stay absent", !o2.has("source") &&
            !o2.has("url") && !o2.has("stackTrace") && !o2.has("network"))
        check("toJson: empty in, empty out",
            JSONArray(r.toJson(emptyList(), emptyMap())).length() == 0)
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
