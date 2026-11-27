package tests

import jp.kaleidpixel.pageloupe.ConsoleLogRecorder
import org.json.JSONObject

// JVM tests for the recorder's internal-Document exclusion
// (isInternalDocumentUrl behind ConsoleLogRecorder, SEN-152):
// a Document request for the app's own start page is not
// recorded; everything else is, including messages that arrive
// while the tab shows the start page (a leaving page's tail, an
// entering page's head). Runs against the pinned test-only
// JSON-java jar like RecorderJsonTest; run via
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

private const val START_DATA_URL = "data:text/html;charset=utf-8;base64,PGI+"
private const val BARE_DATA_URL = "data:text/html;charset=utf-8;base64,"

private fun consoleMsg(text: String): String =
    """{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[{"type":"string","value":"$text"}],"timestamp":1700000000.0}}"""

private fun docRequest(id: String, url: String): String =
    """{"method":"Network.requestWillBeSent","params":{"requestId":"$id","timestamp":100.0,"request":{"method":"GET","url":"$url"},"type":"Document"}}"""

private fun redirectDocRequest(id: String, url: String): String =
    """{"method":"Network.requestWillBeSent","params":{"requestId":"$id","timestamp":100.5,"redirectResponse":{"status":302,"statusText":"Found"},"request":{"method":"GET","url":"$url"},"type":"Document"}}"""

private fun xhrRequest(id: String, url: String): String =
    """{"method":"Network.requestWillBeSent","params":{"requestId":"$id","timestamp":100.0,"request":{"method":"GET","url":"$url"},"type":"XHR"}}"""

private fun responseReceived(id: String, status: Int): String =
    """{"method":"Network.responseReceived","params":{"requestId":"$id","timestamp":100.5,"type":"Document","response":{"url":"https://example.com/","status":$status,"statusText":"","mimeType":"text/html"}}}"""

private fun loadingFinished(id: String): String =
    """{"method":"Network.loadingFinished","params":{"requestId":"$id","timestamp":101.0,"encodedDataLength":512}}"""

private fun loadingFailed(id: String): String =
    """{"method":"Network.loadingFailed","params":{"requestId":"$id","timestamp":101.0,"errorText":"net::ERR_FAILED"}}"""

private fun wrapped(inner: String): String =
    JSONObject()
        .put("method", "Target.receivedMessageFromTarget")
        .put(
            "params",
            JSONObject().put("sessionId", "s1").put("message", inner)
        )
        .toString()

private fun suppliedRecorder(): ConsoleLogRecorder {
    val rec = ConsoleLogRecorder()
    rec.startPageDataUrl = { START_DATA_URL }
    return rec
}

fun main() {
    // Same jar guard as RecorderJsonTest: fail fast if org.json
    // resolved to the android.jar stub instead of the pinned jar.
    run {
        val expected = System.getProperty("pageloupe.test.jsonJar")
        if (expected.isNullOrEmpty()) {
            println("ConsoleLogRecorderGate: run via the runUnitTests Gradle task (it passes the pinned jar name) — aborting")
            kotlin.system.exitProcess(1)
        }
        val loc = JSONObject::class.java.protectionDomain
            ?.codeSource?.location?.path ?: ""
        if (!loc.endsWith("/$expected")) {
            println("ConsoleLogRecorderGate: org.json resolved from '$loc', not the pinned tests/lib jar — aborting")
            kotlin.system.exitProcess(1)
        }
    }

    // The SEN-152 regression, with no tab state involved at
    // all: a real page's Document, console output and XHR are
    // recorded.
    run {
        val rec = suppliedRecorder()
        rec.onCdpMessage(docRequest("r1", "https://example.com/"))
        check(
            "real document recorded",
            rec.requestsSnapshot().singleOrNull()?.url == "https://example.com/"
        )
        rec.onCdpMessage(consoleMsg("page log"))
        check("console recorded", rec.size() == 1)
        rec.onCdpMessage(xhrRequest("x1", "https://example.com/api"))
        check("xhr recorded", rec.requestsSnapshot().size == 2)
    }

    // Internal Documents are excluded in every recognised
    // form, and their own follow-up events create no request
    // record either.
    run {
        val rec = suppliedRecorder()
        rec.onCdpMessage(docRequest("h1", "pageloupe://newtab/"))
        rec.onCdpMessage(docRequest("h2", "about:blank"))
        rec.onCdpMessage(docRequest("h3", BARE_DATA_URL))
        rec.onCdpMessage(docRequest("h4", START_DATA_URL))
        check("internal documents excluded", rec.requestsSnapshot().isEmpty())
        rec.onCdpMessage(responseReceived("h3", 200))
        rec.onCdpMessage(loadingFinished("h3"))
        rec.onCdpMessage(loadingFailed("h4"))
        check("internal follow-ups create nothing", rec.requestsSnapshot().isEmpty())
    }

    // A data: Document carrying a payload is a real page's and
    // is recorded, even with the supplier set.
    run {
        val rec = suppliedRecorder()
        rec.onCdpMessage(docRequest("d1", "data:text/html,<p>real</p>"))
        check(
            "payload data document recorded",
            rec.requestsSnapshot().singleOrNull()?.url == "data:text/html,<p>real</p>"
        )
    }

    // The tail of the page being left is recorded even though
    // it arrives after the internal Document: the exclusion is
    // per-Document, not a state.
    run {
        val rec = suppliedRecorder()
        rec.onCdpMessage(docRequest("h1", BARE_DATA_URL))
        rec.onCdpMessage(xhrRequest("b1", "https://example.com/beacon"))
        check(
            "tail request after internal document recorded",
            rec.requestsSnapshot().singleOrNull()?.url == "https://example.com/beacon"
        )
        rec.onCdpMessage(consoleMsg("tail log"))
        check("tail console recorded", rec.size() == 1)
    }

    // A redirect chain records every hop under the same
    // requestId.
    run {
        val rec = suppliedRecorder()
        rec.onCdpMessage(docRequest("r1", "https://example.com/a"))
        rec.onCdpMessage(redirectDocRequest("r1", "https://example.com/b"))
        val reqs = rec.requestsSnapshot()
        check("redirect: both hops recorded", reqs.size == 2)
        check(
            "redirect: hop urls in order",
            reqs.map { it.url } == listOf("https://example.com/a", "https://example.com/b")
        )
        check("redirect: first hop kept the 302", reqs.firstOrNull()?.status == 302)
    }

    // A redirect hop is not classified: even when the hop's URL
    // is internal, the message goes through so the previous hop
    // keeps its redirect response.
    run {
        val rec = suppliedRecorder()
        rec.onCdpMessage(docRequest("r1", "https://example.com/a"))
        rec.onCdpMessage(redirectDocRequest("r1", "pageloupe://newtab/"))
        val reqs = rec.requestsSnapshot()
        check(
            "redirect to internal: previous hop kept the 302",
            reqs.firstOrNull()?.status == 302
        )
    }

    // Wrapped (Target) messages are classified on their inner
    // fields.
    run {
        val rec = suppliedRecorder()
        rec.onCdpMessage(wrapped(docRequest("h1", BARE_DATA_URL)))
        check("wrapped internal document excluded", rec.requestsSnapshot().isEmpty())
        rec.onCdpMessage(wrapped(docRequest("r1", "https://example.com/")))
        check(
            "wrapped real document recorded",
            rec.requestsSnapshot().singleOrNull()?.url == "https://example.com/"
        )
    }

    // A Document request whose URL is empty cannot be
    // classified as internal (the classifier needs a URL), so
    // it is recorded like any other request (its recorded URL
    // is null, as for any empty URL).
    run {
        val rec = suppliedRecorder()
        rec.onCdpMessage(docRequest("e1", ""))
        check(
            "empty url request recorded as-is",
            rec.requestsSnapshot().singleOrNull()?.url == null &&
                rec.requestsSnapshot().size == 1
        )
    }

    // Without an owner (default supplier), the constant forms
    // still classify: the bare prefix is excluded, a payload
    // data: Document is recorded.
    run {
        val rec = ConsoleLogRecorder()
        rec.onCdpMessage(docRequest("h1", BARE_DATA_URL))
        check("default: bare load excluded", rec.requestsSnapshot().isEmpty())
        rec.onCdpMessage(docRequest("d1", START_DATA_URL))
        check(
            "default: unsupplied data document recorded",
            rec.requestsSnapshot().singleOrNull()?.url == START_DATA_URL
        )
    }

    // clear() empties the recordings.
    run {
        val rec = suppliedRecorder()
        rec.onCdpMessage(docRequest("r1", "https://example.com/"))
        rec.onCdpMessage(consoleMsg("page log"))
        rec.clear()
        check("after clear: no requests", rec.requestsSnapshot().isEmpty())
        check("after clear: no console", rec.size() == 0)
    }

    println("ConsoleLogRecorderGate: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
