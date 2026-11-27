package tests

import jp.kaleidpixel.pageloupe.ConsoleLogRecorder
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

// JVM tests for the sequential requests JSON export
// (ConsoleLogRecorder.writeRequestsJson). The production path
// writes through android.util.JsonWriter, which is a stub on
// the JVM, so the recorder takes a JsonSink and this suite
// supplies a plain-text one; the emitted text is then parsed
// back with the pinned JSON-java jar and asserted on.
//
// Scope follows the owner's SEN-19 conditions for the test
// jar: assertions cover the recorder's own logic — which
// fields are emitted, their values AND their JSON types, the
// header tree walk and the omission / invalid markers. The
// opt* accessors are not used for value assertions: they
// coerce (a string "201" would read back as the int 201), so
// a regression that emits the wrong type would slip through.
// Number parsing types themselves (Integer vs Long) are a
// JSON-java property, not the recorder's, so numbers are
// compared by value through Number. Key order and number
// formatting are likewise out of scope; byte-level output
// equality stays verified on device. Run via
// the runUnitTests Gradle task.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

private fun getOrNull(o: JSONObject, key: String): Any? =
    if (o.has(key)) o.get(key) else null

/** True when [v] is a JSON number (not a string) with this value. */
private fun numEq(v: Any?, expected: Long): Boolean =
    (v as? Number)?.toLong() == expected

/** True when [v] is a JSON string with this value. */
private fun strEq(v: Any?, expected: String): Boolean =
    v is String && v == expected

/** True when the key is present and holds an explicit JSON null. */
private fun isJsonNull(o: JSONObject, key: String): Boolean =
    o.has(key) && o.get(key) === JSONObject.NULL

/** A JsonSink that emits plain JSON text (no indent). */
private class StringJsonSink : ConsoleLogRecorder.JsonSink {
    val sb = StringBuilder()
    var flushed = false
        private set

    private class Ctx(val array: Boolean, var count: Int = 0)

    private val stack = ArrayDeque<Ctx>()
    private var pendingName = false

    private fun beforeValue() {
        if (pendingName) {
            pendingName = false
            return
        }
        val top = stack.lastOrNull() ?: return
        if (top.count > 0) sb.append(',')
        top.count++
    }

    override fun beginArray(): ConsoleLogRecorder.JsonSink = apply {
        beforeValue()
        stack.addLast(Ctx(array = true))
        sb.append('[')
    }

    override fun endArray(): ConsoleLogRecorder.JsonSink = apply {
        stack.removeLast()
        sb.append(']')
    }

    override fun beginObject(): ConsoleLogRecorder.JsonSink = apply {
        beforeValue()
        stack.addLast(Ctx(array = false))
        sb.append('{')
    }

    override fun endObject(): ConsoleLogRecorder.JsonSink = apply {
        stack.removeLast()
        sb.append('}')
    }

    override fun name(name: String): ConsoleLogRecorder.JsonSink = apply {
        val top = stack.last()
        if (top.count > 0) sb.append(',')
        top.count++
        sb.append('"').append(escape(name)).append("\":")
        pendingName = true
    }

    override fun value(value: String?): ConsoleLogRecorder.JsonSink = apply {
        beforeValue()
        if (value == null) sb.append("null")
        else sb.append('"').append(escape(value)).append('"')
    }

    override fun value(value: Long): ConsoleLogRecorder.JsonSink = apply {
        beforeValue()
        sb.append(value)
    }

    override fun value(value: Boolean): ConsoleLogRecorder.JsonSink = apply {
        beforeValue()
        sb.append(value)
    }

    override fun value(value: Number): ConsoleLogRecorder.JsonSink = apply {
        beforeValue()
        sb.append(value.toString())
    }

    override fun nullValue(): ConsoleLogRecorder.JsonSink = apply {
        beforeValue()
        sb.append("null")
    }

    override fun flush() {
        flushed = true
    }

    private fun escape(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code))
                else out.append(c)
            }
        }
        return out.toString()
    }
}

private fun export(list: List<ConsoleLogRecorder.NetReq>): Pair<String, StringJsonSink> {
    val sink = StringJsonSink()
    ConsoleLogRecorder().writeRequestsJson(list, sink)
    return sink.sb.toString() to sink
}

private fun timeRoundTrips(obj: JSONObject, startWallMs: Long): Boolean {
    val text = obj.optString("time")
    if (text.isEmpty()) return false
    val parsed = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(text)
    } catch (_: Exception) {
        null
    }
    return parsed != null && parsed.time == startWallMs
}

fun main() {
    // Same pinned-jar guard as RecorderJsonTest: fail fast if
    // org.json did not resolve to the test-only jar.
    run {
        val expected = System.getProperty("pageloupe.test.jsonJar")
        if (expected.isNullOrEmpty()) {
            println("RecorderRequestsJson: run via the runUnitTests Gradle task (it passes the pinned jar name) — aborting")
            kotlin.system.exitProcess(1)
        }
        val loc = JSONObject::class.java.protectionDomain
            ?.codeSource?.location?.path ?: ""
        if (!loc.endsWith("/$expected")) {
            println("RecorderRequestsJson: org.json resolved from '$loc', not the pinned tests/lib jar — aborting")
            kotlin.system.exitProcess(1)
        }
    }

    // Empty list: a bare JSON array, and the sink is flushed.
    run {
        val (text, sink) = export(emptyList())
        val arr = JSONArray(text)
        check("empty: array of 0", arr.length() == 0)
        check("empty: flushed", sink.flushed)
    }

    // Full record: every field is emitted with its value and
    // type, and the header strings are walked into nested
    // objects.
    run {
        val r = ConsoleLogRecorder.NetReq(
            requestId = "req-1",
            hop = 2,
            startWallMs = 1700000000123L,
            method = "POST",
            url = "https://example.com/api?q=1",
            type = "XHR",
            status = 201,
            statusText = "Created",
            mimeType = "application/json",
            errorText = null,
            initiator = "https://example.com/page",
            durationMs = 345L,
            encodedBytes = 6789L,
            requestHeaders = """{"user-agent":"TestUA","x-num":5,"x-flag":true,""" +
                """"x-null":null,"x-arr":[1,"a"],"x-obj":{"k":"v"}}""",
            responseHeaders = """{"content-type":"application/json","x-deep":{"list":[true,null]}}""",
            postData = "a=1&b=2"
        )
        val (text, _) = export(listOf(r))
        val arr = JSONArray(text)
        check("full: one record", arr.length() == 1)
        val o = arr.getJSONObject(0)
        check("full: time round-trips", timeRoundTrips(o, 1700000000123L))
        check("full: requestId", strEq(getOrNull(o, "requestId"), "req-1"))
        check("full: redirectHop is a number", numEq(getOrNull(o, "redirectHop"), 2))
        check("full: type", strEq(getOrNull(o, "type"), "XHR"))
        check("full: method", strEq(getOrNull(o, "method"), "POST"))
        check("full: url", strEq(getOrNull(o, "url"), "https://example.com/api?q=1"))
        check("full: status is a number", numEq(getOrNull(o, "status"), 201))
        check("full: statusText", strEq(getOrNull(o, "statusText"), "Created"))
        check("full: mimeType", strEq(getOrNull(o, "mimeType"), "application/json"))
        check("full: no errorText key", !o.has("errorText"))
        check("full: durationMs is a number", numEq(getOrNull(o, "durationMs"), 345))
        check("full: encodedBytes is a number", numEq(getOrNull(o, "encodedBytes"), 6789))
        check("full: initiator", strEq(getOrNull(o, "initiator"), "https://example.com/page"))
        check("full: postData", strEq(getOrNull(o, "postData"), "a=1&b=2"))
        val req = o.getJSONObject("requestHeaders")
        check("full: header string", strEq(getOrNull(req, "user-agent"), "TestUA"))
        check("full: header number stays a number", numEq(getOrNull(req, "x-num"), 5))
        check("full: header boolean stays a boolean", getOrNull(req, "x-flag") == true)
        check("full: header null is present and null", isJsonNull(req, "x-null"))
        val xa = req.getJSONArray("x-arr")
        check(
            "full: header array",
            xa.length() == 2 && numEq(xa.get(0), 1) && strEq(xa.get(1), "a")
        )
        check(
            "full: header nested object",
            strEq(getOrNull(req.getJSONObject("x-obj"), "k"), "v")
        )
        val deep = o.getJSONObject("responseHeaders").getJSONObject("x-deep").getJSONArray("list")
        check(
            "full: header deep walk",
            deep.length() == 2 && deep.get(0) == true && deep.get(1) === JSONObject.NULL
        )
    }

    // Error record: errorText is emitted when present, and
    // hop 1 already counts as a redirect hop.
    run {
        val r = ConsoleLogRecorder.NetReq(
            requestId = "req-err",
            hop = 1,
            errorText = "net::ERR_ABORTED"
        )
        val (text, _) = export(listOf(r))
        val o = JSONArray(text).getJSONObject(0)
        check("error: errorText", strEq(getOrNull(o, "errorText"), "net::ERR_ABORTED"))
        check("error: redirectHop at hop 1", numEq(getOrNull(o, "redirectHop"), 1))
        check("error: no status key", !o.has("status"))
    }

    // Minimal record: only the unconditional fields appear —
    // no redirectHop at hop 0, no optional keys at all.
    run {
        val r = ConsoleLogRecorder.NetReq(requestId = "req-min", startWallMs = 1700000000000L)
        val (text, _) = export(listOf(r))
        val o = JSONArray(text).getJSONObject(0)
        check("minimal: exactly time+requestId", o.length() == 2)
        check("minimal: requestId", strEq(getOrNull(o, "requestId"), "req-min"))
        check("minimal: time round-trips", timeRoundTrips(o, 1700000000000L))
    }

    // Header markers: the omission marker passes through as a
    // plain string, and an unparseable header string becomes
    // the invalid marker instead of being dropped. The marker
    // strings are pinned as literals here on purpose: they are
    // part of the export format, so a change on the production
    // side must fail this suite and be reviewed.
    run {
        val r = ConsoleLogRecorder.NetReq(
            requestId = "req-mark",
            requestHeaders = "<headers omitted: over size cap>",
            responseHeaders = "{not json"
        )
        val (text, _) = export(listOf(r))
        val o = JSONArray(text).getJSONObject(0)
        check(
            "markers: omitted stays a string",
            strEq(getOrNull(o, "requestHeaders"), "<headers omitted: over size cap>")
        )
        check(
            "markers: invalid becomes the marker",
            strEq(getOrNull(o, "responseHeaders"), "<headers omitted: invalid JSON>")
        )
    }

    // Two records keep their order and their own values.
    run {
        val a = ConsoleLogRecorder.NetReq(requestId = "first", method = "GET")
        val b = ConsoleLogRecorder.NetReq(requestId = "second", method = "POST", status = 404)
        val (text, _) = export(listOf(a, b))
        val arr = JSONArray(text)
        check("order: two records", arr.length() == 2)
        check("order: first", strEq(getOrNull(arr.getJSONObject(0), "requestId"), "first"))
        check("order: second", strEq(getOrNull(arr.getJSONObject(1), "requestId"), "second"))
        check("order: second status", numEq(getOrNull(arr.getJSONObject(1), "status"), 404))
        check("order: first has no status", !arr.getJSONObject(0).has("status"))
    }

    println("RecorderRequestsJsonTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
