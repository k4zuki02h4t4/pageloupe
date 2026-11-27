package tests

import jp.kaleidpixel.pageloupe.DevToolsProxyPolicy

// JVM tests for the DevTools proxy's relay pins (audit
// hardening): which path shape and which Host header the
// proxy accepts. Run via the runUnitTests Gradle task.

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
        "page target path is allowed",
        DevToolsProxyPolicy.isAllowedRelayPath("/devtools/page/ABC123")
    )
    check(
        "target list path is refused",
        !DevToolsProxyPolicy.isAllowedRelayPath("/json/list")
    )
    check(
        "browser endpoint is refused",
        !DevToolsProxyPolicy.isAllowedRelayPath("/devtools/browser/ABC123")
    )
    check(
        "empty target id is refused",
        !DevToolsProxyPolicy.isAllowedRelayPath("/devtools/page/")
    )
    check(
        "nested path is refused",
        !DevToolsProxyPolicy.isAllowedRelayPath("/devtools/page/ABC/extra")
    )
    check(
        "root path is refused",
        !DevToolsProxyPolicy.isAllowedRelayPath("/")
    )
    check(
        "hyphenated target id is allowed",
        DevToolsProxyPolicy.isAllowedRelayPath("/devtools/page/AB-CD12")
    )
    check(
        "query marker in the id is refused",
        !DevToolsProxyPolicy.isAllowedRelayPath("/devtools/page/ABC?x=1")
    )
    check(
        "escaped slash in the id is refused",
        !DevToolsProxyPolicy.isAllowedRelayPath("/devtools/page/ABC%2FDEF")
    )
    check(
        "non-ASCII letter in the id is refused",
        !DevToolsProxyPolicy.isAllowedRelayPath("/devtools/page/ABC\u00e9DEF")
    )
    check(
        "own host and port are accepted",
        DevToolsProxyPolicy.isExpectedHost("127.0.0.1:9222", 9222)
    )
    check(
        "another port is refused",
        !DevToolsProxyPolicy.isExpectedHost("127.0.0.1:9223", 9222)
    )
    check(
        "a foreign host is refused",
        !DevToolsProxyPolicy.isExpectedHost("evil.example:9222", 9222)
    )
    check(
        "localhost name is refused",
        !DevToolsProxyPolicy.isExpectedHost("localhost:9222", 9222)
    )
    check(
        "missing host is refused",
        !DevToolsProxyPolicy.isExpectedHost(null, 9222)
    )
    check(
        "unbound proxy refuses every host",
        !DevToolsProxyPolicy.isExpectedHost("127.0.0.1:-1", -1)
    )
    println("DevToolsProxyPolicyTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
