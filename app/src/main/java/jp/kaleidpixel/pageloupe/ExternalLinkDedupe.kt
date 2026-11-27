package jp.kaleidpixel.pageloupe

/**
 * Whether a URL may take part in the external-link dedupe
 * comparison at all: bounded length, and an authority free of
 * userinfo ('@') and backslashes. A WHATWG parser reads those
 * differently than [normalizeUrlForCompare] does (userinfo is
 * dropped there, '\' is not a separator), so a URL carrying them
 * could compare equal to a different real URL. Applied to BOTH
 * sides of the comparison -- the incoming link and each tab's
 * URLs.
 */
fun isDedupeSafeUrl(url: String, maxLength: Int): Boolean {
    if (url.length > maxLength) return false
    val authority = url.substringAfter("://", "")
        .takeWhile { it != '/' && it != '?' && it != '#' }
    return !authority.contains('@') && !authority.contains('\\')
}

/** One tab's dedupe inputs: what it shows, what it will show, its mode. */
data class DedupeCandidate(
    val currentUrl: String,
    val deferredUrl: String?,
    val secret: Boolean
)

/** What opening an external link should do (see [externalLinkAction]). */
enum class ExternalLinkAction { SWITCH, CREATE, REFUSE }

/**
 * The full open-external-link decision as one pure function:
 * reuse wins over the tab cap (switching creates nothing), the
 * cap refuses only new-tab creation, anything else creates.
 * Returns the action and, for SWITCH, the tab index. Pure so
 * the cap boundary (cap-1 / cap) and the restore branch's
 * fallback are pinned by unit tests.
 */
fun externalLinkAction(
    incoming: String,
    candidates: List<DedupeCandidate>,
    maxLength: Int,
    tabCount: Int,
    tabCap: Int
): Pair<ExternalLinkAction, Int> {
    val idx = findDedupeTabIndex(incoming, candidates, maxLength)
    return when {
        idx >= 0 -> ExternalLinkAction.SWITCH to idx
        tabCount >= tabCap -> ExternalLinkAction.REFUSE to -1
        else -> ExternalLinkAction.CREATE to -1
    }
}

/**
 * Index of the candidate already showing [url] (the external
 * link), or -1. Secret tabs never take external links, so they
 * are skipped. Pure: unit-tested on the JVM.
 */
fun findDedupeTabIndex(url: String, candidates: List<DedupeCandidate>, maxLength: Int): Int {
    if (!isDedupeSafeUrl(url, maxLength)) return -1
    val wanted = normalizeUrlForCompare(url)
    for ((i, c) in candidates.withIndex()) {
        if (c.secret) continue
        if (isDedupeSafeUrl(c.currentUrl, maxLength) &&
            normalizeUrlForCompare(c.currentUrl) == wanted
        ) {
            return i
        }
        val deferred = c.deferredUrl
        if (deferred != null && isDedupeSafeUrl(deferred, maxLength) &&
            normalizeUrlForCompare(deferred) == wanted
        ) {
            return i
        }
    }
    return -1
}

/**
 * Canonical form for comparing "is this the same URL?" between an
 * external intent's raw string and a tab's WebView-reported URL:
 * scheme and host lowercased, userinfo and fragment dropped,
 * default port dropped, empty path made "/". Paths and queries
 * stay case-sensitive. A redirect still changes a URL beyond what
 * normalization can reconcile; this only removes the spelling
 * differences.
 */
fun normalizeUrlForCompare(url: String): String {
    // Strip only the WHATWG range (C0 controls and space, up to
    // U+0020). String.trim() would also remove Unicode spaces
    // (U+3000 and friends), which a URL parser instead encodes
    // into the path -- a different URL that must not compare
    // equal.
    val s = url.trim { it <= ' ' }.substringBefore('#')
    val colon = s.indexOf(':')
    if (colon <= 0) return s
    val scheme = s.substring(0, colon).lowercase()
    if (!s.startsWith("://", colon)) return scheme + s.substring(colon)
    val rest = s.substring(colon + 3)
    val authEnd = rest.indexOfFirst { it == '/' || it == '?' }
        .let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authEnd)
    var tail = rest.substring(authEnd)
    val hostPort = authority.substringAfterLast('@')
    val portSep = hostPort.lastIndexOf(':')
    val host: String
    val port: String?
    if (portSep >= 0 && hostPort.substring(portSep + 1).isAsciiDigits()) {
        host = hostPort.substring(0, portSep).lowercase()
        port = hostPort.substring(portSep + 1)
    } else if (portSep >= 0 && portSep == hostPort.length - 1) {
        // A trailing colon ("example.com:") is an empty port,
        // which counts as no port at all.
        host = hostPort.substring(0, portSep).lowercase()
        port = null
    } else {
        host = hostPort.lowercase()
        port = null
    }
    val isDefaultPort = (scheme == "http" && port == "80") || (scheme == "https" && port == "443")
    val portPart = if (port != null && !isDefaultPort) ":$port" else ""
    if (tail.isEmpty()) {
        tail = "/"
    } else if (tail.startsWith('?')) {
        tail = "/$tail"
    }
    return "$scheme://$host$portPart$tail"
}
