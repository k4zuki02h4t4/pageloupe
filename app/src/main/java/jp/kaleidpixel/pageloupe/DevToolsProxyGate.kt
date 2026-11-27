package jp.kaleidpixel.pageloupe

import java.security.MessageDigest

/**
 * The DevTools proxy's connection gate, as a pure function
 * (FULL-SEC-2 / FULL-SEN-4): given the request's header
 * block, the session token and the proxy's port, decide
 * whether the connection may be relayed, and to which real
 * path. Before this extraction the checks lived inline in
 * the socket handler, where no test could reach them — the
 * individual pins (DevToolsProxyPolicy) were tested, but
 * their order, the token extraction and the upgrade test
 * were not.
 *
 * The checks, in order (the order is part of the design:
 * nothing about the request is trusted or revealed before
 * the token has been presented):
 * 1. Token: the path must be /t/<token>/<real path>, and
 *    the presented segment must equal the session token,
 *    compared in constant time. An empty session token
 *    rejects everything (two empty values would otherwise
 *    "match").
 * 2. Host pin: exactly one Host line, naming the loopback
 *    address and the proxy's own port (a browser driven
 *    here by a hostile page — DNS rebinding — would carry
 *    that page's own host name).
 * 3. Path pin: the real path must be a page-target shape
 *    (DevToolsProxyPolicy), so even a leaked token reaches
 *    nothing else the DevTools socket serves.
 * 4. Origin: every Origin line must be the hosted
 *    frontend's own; Origin-less clients rely on the
 *    token alone.
 * 5. Upgrade: the request must be a WebSocket upgrade by
 *    the protocol's own test — an Upgrade header naming
 *    websocket AND a Connection header naming upgrade
 *    (FULL-SEC-1: the Upgrade header alone let non-session
 *    requests through to the raw relay).
 *
 * Pure and JVM-tested (DevToolsProxyGateTest); the proxy
 * owns the sockets, the logging and the rejection bytes.
 */
object DevToolsProxyGate {

    sealed interface Decision {
        /** Relay, with the token prefix stripped. */
        data class Allow(val realPath: String) : Decision

        /**
         * Refuse with this HTTP status. [reason] names the
         * check that failed, for the proxy's log; [detail]
         * carries the offending value where the log shows
         * one (the rejected origin, sanitized by the caller).
         */
        data class Reject(
            val status: String,
            val reason: String,
            val detail: String? = null,
        ) : Decision
    }

    fun evaluate(headerText: String, sessionToken: String, port: Int): Decision {
        val firstLine = headerText.split("\r\n").firstOrNull() ?: ""
        val parts = firstLine.split(" ")
        val path = parts.getOrElse(1) { "/" }

        val segEnd = path.indexOf('/', 3)
        val presented = if (path.startsWith("/t/") && segEnd > 3) {
            path.substring(3, segEnd)
        } else {
            null
        }
        if (sessionToken.isEmpty() || presented == null ||
            !MessageDigest.isEqual(
                presented.toByteArray(Charsets.US_ASCII),
                sessionToken.toByteArray(Charsets.US_ASCII),
            )
        ) {
            return Decision.Reject("404 Not Found", "token")
        }
        val realPath = path.substring(segEnd)

        val hosts = headerValues(headerText, "Host")
        if (hosts.size != 1 ||
            !DevToolsProxyPolicy.isExpectedHost(hosts.first(), port)
        ) {
            return Decision.Reject("404 Not Found", "host")
        }

        if (!DevToolsProxyPolicy.isAllowedRelayPath(realPath)) {
            return Decision.Reject("404 Not Found", "path")
        }

        val origins = headerValues(headerText, "Origin")
        val badOrigin = origins.firstOrNull { it != DevToolsProxy.FRONTEND_ORIGIN }
        if (badOrigin != null) {
            return Decision.Reject("403 Forbidden", "origin", badOrigin)
        }

        if (!isWebSocketUpgrade(headerText)) {
            return Decision.Reject("404 Not Found", "upgrade")
        }

        return Decision.Allow(realPath)
    }

    /** Whether a response head is a 101 upgrade. */
    fun isUpgradeResponse(respHeadText: String): Boolean {
        // Parse the status code out of the status line
        // positionally (review round 2, SEN-7): a loose
        // contains(" 101 ") could match a header value or a
        // reason phrase on another status.
        val line = respHeadText.split("\r\n").firstOrNull() ?: return false
        val parts = line.split(' ', limit = 3)
        return parts.size >= 2 && parts[1] == "101"
    }

    /**
     * Whether the headers ask for a WebSocket upgrade, by
     * the test the upstream applies: an Upgrade header
     * naming websocket and a Connection header naming
     * upgrade (either may be a comma-separated token list,
     * in any case).
     */
    fun isWebSocketUpgrade(headers: String): Boolean {
        val upgrade = headerValue(headers, "Upgrade") ?: return false
        if (!upgrade.split(',').any { it.trim().equals("websocket", ignoreCase = true) }) {
            return false
        }
        val connection = headerValue(headers, "Connection") ?: return false
        return connection.split(',').any { it.trim().equals("upgrade", ignoreCase = true) }
    }

    /**
     * The request with every Origin line removed (the proxy
     * strips it before forwarding; see DevToolsProxy's
     * class doc). A line is an Origin line when its parsed
     * name says so, whatever the spacing.
     */
    fun stripOrigin(headers: String): String {
        val lines = headers.split("\r\n")
        return lines.filterNot {
            headerName(it)?.equals("Origin", ignoreCase = true) == true
        }.joinToString("\r\n")
    }

    /** All values of header [name], in order (name match is
     * case-insensitive, spacing around the colon is free). */
    fun headerValues(headers: String, name: String): List<String> =
        headers.split("\r\n").mapNotNull { line ->
            if (headerName(line)?.equals(name, ignoreCase = true) == true) {
                line.substring(line.indexOf(':') + 1).trim()
            } else {
                null
            }
        }

    fun headerValue(headers: String, name: String): String? =
        headerValues(headers, name).firstOrNull()

    /** The parsed name of a header line (trimmed), or null if it has none. */
    private fun headerName(line: String): String? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        return line.substring(0, colon).trim()
    }
}
