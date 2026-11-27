package jp.kaleidpixel.pageloupe

import java.net.URLEncoder

/**
 * Classification of what the user typed into the address bar.
 *
 * Pure functions only (no Android types), so the rules are covered
 * by JVM unit tests (tests/UrlInputTest.kt).
 */
sealed class UrlInput {
    /** Blank input: do nothing. */
    object Empty : UrlInput()

    /** The internal start page. */
    object Home : UrlInput()

    /** Load this URL as-is. */
    data class Load(val url: String) : UrlInput()

    /** Not a URL: search for this query text. */
    data class Search(val query: String) : UrlInput()
}

fun googleSearchUrl(query: String): String =
    "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")

fun classifyUrlInput(raw: String): UrlInput {
    val q = raw.trim()
    if (q.isEmpty()) return UrlInput.Empty
    val lower = q.lowercase()
    when {
        lower.startsWith("pageloupe:") -> return UrlInput.Home
        lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") -> return UrlInput.Load(q)
    }
    if (q.any { it.isWhitespace() }) return UrlInput.Search(q)
    // Scheme-less input counts as a URL only when its authority (the
    // part before the first '/', '?' or '#') looks like a host.
    // Two separate decisions follow. isHostLike decides whether
    // the authority is a host at all -- without its localhost
    // case, "localhost:3000" would classify as a search.
    // isLocalHost then decides the scheme: local authorities
    // (localhost, *.local, and loopback / private / link-local /
    // 0.0.0.0/8 addresses) get http:// -- development servers do
    // not serve TLS -- and everything else gets the https://
    // default. Downgrading local names to plaintext is a
    // deliberate trade-off, recorded in the design records.
    val authority = q.takeWhile { it != '/' && it != '?' && it != '#' }
    val host = hostPart(authority) ?: return UrlInput.Search(q)
    if (!isHostLike(host)) return UrlInput.Search(q)
    val scheme = if (isLocalHost(host)) "http" else "https"
    return UrlInput.Load("$scheme://$q")
}

/**
 * True for a non-empty run of ASCII digits only. Char.isDigit()
 * also accepts full-width and other Unicode digits, which URL
 * parsers normalize differently (a full-width "０１０" becomes the
 * octal-looking "010"); numeric checks here stay ASCII so the
 * classification cannot disagree with the parser downstream.
 */
internal fun String.isAsciiDigits(): Boolean =
    isNotEmpty() && all { it in '0'..'9' }

/** The host of an authority, with a numeric ":port" stripped; null when malformed. */
private fun hostPart(authority: String): String? {
    if (authority.isEmpty()) return null
    val colon = authority.lastIndexOf(':')
    if (colon < 0) return authority
    val port = authority.substring(colon + 1)
    if (!port.isAsciiDigits()) return null
    val host = authority.substring(0, colon)
    return host.ifEmpty { null }
}

private fun isHostLike(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true)) return true
    if (parseIpv4(host) != null) return true
    if (!host.contains('.')) return false
    // Domain-like: dot-separated labels of letters / digits /
    // hyphens, and the last label carries a letter (a real TLD --
    // this also keeps "1.2.3.999" out of the URL branch: it is not
    // a valid IPv4 address, and an all-numeric tail is no TLD).
    val labels = host.split('.')
    if (labels.any { label ->
            label.isEmpty() ||
                !label.all { it.isLetterOrDigit() || it == '-' } ||
                label.startsWith('-') || label.endsWith('-')
        }
    ) {
        return false
    }
    return labels.last().any { it.isLetter() }
}

private fun isLocalHost(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true)) return true
    if (host.endsWith(".local", ignoreCase = true)) return true
    val ip = parseIpv4(host) ?: return false
    return ip[0] == 0 || ip[0] == 127 || ip[0] == 10 ||
        (ip[0] == 172 && ip[1] in 16..31) ||
        (ip[0] == 192 && ip[1] == 168) ||
        (ip[0] == 169 && ip[1] == 254)
}

/** [a, b, c, d] for a dotted-quad string, null when not a valid IPv4 address. */
private fun parseIpv4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val out = IntArray(4)
    for (i in parts.indices) {
        val p = parts[i]
        if (p.length > 3 || !p.isAsciiDigits()) return null
        // A leading zero reads as OCTAL in URL parsers (WHATWG),
        // so "010.0.0.1" can mean 8.0.0.1, not 10.0.0.1. Refuse to
        // classify such parts at all rather than risk calling a
        // public address local and downgrading it to http.
        if (p.length > 1 && p.startsWith('0')) return null
        val v = p.toInt()
        if (v > 255) return null
        out[i] = v
    }
    return out
}
