package tests

import jp.kaleidpixel.pageloupe.DevToolsProxy
import jp.kaleidpixel.pageloupe.DevToolsProxyGate

// DevToolsProxyGate (FULL-SEC-2 / FULL-SEN-4): the proxy's
// connection gate as a pure function. The rules pinned
// here: the check ORDER (token before everything), the
// token segment extraction, the Host and path pins, every
// Origin line counting, and the two-header upgrade test
// (FULL-SEC-1). A regression in any of them is a hole in
// the only wall between a web page (or a leaked token) and
// the DevTools socket.
fun main() {
    var failures = 0
    var checks = 0
    fun check(name: String, cond: Boolean) {
        checks++
        if (!cond) {
            failures++
            println("FAIL: $name")
        }
    }

    val token = "abcdef0123456789abcdef0123456789"
    val port = 9333
    val target = "ABCDEF0123456789ABCDEF0123456789"

    fun request(
        path: String = "/t/$token/devtools/page/$target",
        host: String? = "127.0.0.1:$port",
        hostLines: List<String>? = null,
        origin: List<String> = listOf(DevToolsProxy.FRONTEND_ORIGIN),
        upgrade: String? = "websocket",
        connection: String? = "Upgrade",
    ): String {
        val lines = ArrayList<String>()
        lines.add("GET $path HTTP/1.1")
        if (hostLines != null) {
            for (h in hostLines) lines.add("Host: $h")
        } else if (host != null) {
            lines.add("Host: $host")
        }
        for (o in origin) lines.add("Origin: $o")
        if (upgrade != null) lines.add("Upgrade: $upgrade")
        if (connection != null) lines.add("Connection: $connection")
        lines.add("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==")
        lines.add("Sec-WebSocket-Version: 13")
        return lines.joinToString("\r\n") + "\r\n\r\n"
    }

    fun allow(name: String, headerText: String, tok: String = token) {
        val d = DevToolsProxyGate.evaluate(headerText, tok, port)
        check(name, d is DevToolsProxyGate.Decision.Allow)
    }

    fun reject(name: String, headerText: String, reason: String, tok: String = token) {
        val d = DevToolsProxyGate.evaluate(headerText, tok, port)
        check(
            name,
            d is DevToolsProxyGate.Decision.Reject && d.reason == reason,
        )
    }

    // -- the happy path ------------------------------------
    run {
        val d = DevToolsProxyGate.evaluate(request(), token, port)
        check(
            "a well-formed request is allowed with the real path",
            d is DevToolsProxyGate.Decision.Allow &&
                d.realPath == "/devtools/page/$target",
        )
    }
    allow("no Origin line is allowed (token-only client)", request(origin = emptyList()))
    allow(
        "upgrade tokens may be lists in any case",
        request(upgrade = "WebSocket", connection = "keep-alive, Upgrade"),
    )
    allow(
        "header names are case-insensitive",
        request().replace("Upgrade:", "upgrade:").replace("Connection:", "connection:"),
    )

    // -- token ----------------------------------------------
    reject("a missing token prefix is rejected", request(path = "/devtools/page/$target"), "token")
    reject(
        "a wrong token is rejected",
        request(path = "/t/00000000000000000000000000000000/devtools/page/$target"),
        "token",
    )
    reject(
        "a token that is only a prefix of the segment is rejected",
        request(path = "/t/${token}ff/devtools/page/$target"),
        "token",
    )
    reject(
        "an empty session token rejects everything",
        request(),
        "token",
        tok = "",
    )
    reject(
        "the token check comes before the host check",
        request(path = "/devtools/page/$target", host = "evil.example"),
        "token",
    )

    // -- host pin --------------------------------------------
    reject("a foreign host is rejected", request(host = "evil.example:$port"), "host")
    reject("the wrong port is rejected", request(host = "127.0.0.1:1"), "host")
    reject("a missing host is rejected", request(host = null), "host")
    reject(
        "two host lines are rejected",
        request(hostLines = listOf("127.0.0.1:$port", "127.0.0.1:$port")),
        "host",
    )

    // -- path pin ---------------------------------------------
    reject(
        "the target list is not relayed",
        request(path = "/t/$token/json/list"),
        "path",
    )
    reject(
        "a browser-level endpoint is not relayed",
        request(path = "/t/$token/devtools/browser/$target"),
        "path",
    )
    reject(
        "an empty target id is rejected",
        request(path = "/t/$token/devtools/page/"),
        "path",
    )
    reject(
        "an extra path segment is rejected",
        request(path = "/t/$token/devtools/page/$target/extra"),
        "path",
    )
    reject(
        "a query marker is rejected",
        request(path = "/t/$token/devtools/page/$target?x=1"),
        "path",
    )

    // -- origin ------------------------------------------------
    reject(
        "a foreign origin is rejected",
        request(origin = listOf("https://evil.example")),
        "origin",
    )
    reject(
        "a foreign origin behind a good one is rejected",
        request(origin = listOf(DevToolsProxy.FRONTEND_ORIGIN, "https://evil.example")),
        "origin",
    )
    run {
        val d = DevToolsProxyGate.evaluate(
            request(origin = listOf("https://evil.example")), token, port)
        check(
            "an origin rejection is a 403 carrying the value",
            d is DevToolsProxyGate.Decision.Reject &&
                d.status == "403 Forbidden" && d.detail == "https://evil.example",
        )
    }

    // -- upgrade -----------------------------------------------
    reject(
        "an Upgrade header without Connection is not a session (FULL-SEC-1)",
        request(connection = null),
        "upgrade",
    )
    reject(
        "a Connection header without Upgrade is not a session",
        request(upgrade = null),
        "upgrade",
    )
    reject(
        "a non-websocket upgrade is rejected",
        request(upgrade = "h2c"),
        "upgrade",
    )
    reject(
        "a plain GET is rejected",
        request(upgrade = null, connection = null),
        "upgrade",
    )

    // -- response head ------------------------------------------
    check(
        "a 101 head is an upgrade",
        DevToolsProxyGate.isUpgradeResponse("HTTP/1.1 101 Switching Protocols\r\n\r\n"),
    )
    check(
        "a 200 head is not an upgrade",
        !DevToolsProxyGate.isUpgradeResponse("HTTP/1.1 200 OK\r\n\r\n"),
    )
    check(
        "a 404 head is not an upgrade",
        !DevToolsProxyGate.isUpgradeResponse("HTTP/1.1 404 Not Found\r\n\r\n"),
    )
    // The status code is parsed positionally (SEN-7): a
    // " 101 " inside a header value does not upgrade a 200.
    check(
        "a 101 in a header value is not an upgrade",
        !DevToolsProxyGate.isUpgradeResponse(
            "HTTP/1.1 200 OK\r\nX-Note: saw 101 \r\n\r\n"
        ),
    )

    // -- stripOrigin ----------------------------------------------
    run {
        val stripped = DevToolsProxyGate.stripOrigin(
            "GET /x HTTP/1.1\r\nHost: 127.0.0.1:1\r\nOrigin: https://a\r\n" +
                "origin:https://b\r\nX-Keep: 1\r\n\r\n",
        )
        check(
            "stripOrigin removes every Origin line, spacing aside",
            !stripped.contains("Origin") && !stripped.contains("origin:") &&
                stripped.contains("X-Keep: 1") && stripped.contains("Host:"),
        )
    }

    println("DevToolsProxyGateTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
