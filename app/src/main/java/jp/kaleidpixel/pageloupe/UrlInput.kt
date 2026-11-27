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
    // deliberate trade-off, recorded in REVIEW.md.
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
private fun String.isAsciiDigits(): Boolean =
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

/**
 * The start page is internal: a tab is "home" when nothing has been
 * loaded into it yet, or when its URL carries the pageloupe: scheme.
 * Decided by scheme only: also matching the look-alike host
 * "https://pageloupe.home" would let a real page pose as the
 * start page. Pure and top-level so the
 * JVM tests can pin it (BrowserTab.isHomeUrl delegates here).
 */
fun isHomeUrl(url: String): Boolean =
    url.isBlank() || url.startsWith("pageloupe:")

/**
 * Whether the document on screen is the start page, composed
 * from the tab's three home signals so the phase table is
 * pinned by tests (BrowserTab.homeDisplayed delegates here):
 *
 * - committed and displayed: [homeCommitted] (and then the
 *   current URL is the home URL too);
 * - being left by a load that has not committed yet:
 *   [loadSequenceFromHome] — homeCommitted itself already
 *   drops when the load starts, and the current URL already
 *   names the target;
 * - a load that ended without committing: the current URL
 *   was restored to the home marker ([currentIsHome]);
 * - a load TOWARDS home that just started: [currentIsHome]
 *   again — the previous page is still on screen, and
 *   refusing is the safe side.
 *
 * A normal page on screen makes all three false.
 */
fun isHomeDisplayed(
    homeCommitted: Boolean,
    loadSequenceFromHome: Boolean,
    currentIsHome: Boolean
): Boolean = homeCommitted || loadSequenceFromHome || currentIsHome

/** The scheme/mime/charset header of the start page's data URL. */
const val START_PAGE_DATA_URL_PREFIX = "data:text/html;charset=utf-8;base64,"

/**
 * The canonical home URL. This top-level constant is the
 * single source: BrowserTab.HOME_URL aliases it, so pure-JVM
 * code (the tab session codec) and the tab itself can never
 * drift apart.
 */
const val HOME_URL_MARKER = "pageloupe://newtab/"

/**
 * The URL of the start page's load, as constructed from its
 * HTML: the data: form carrying the HTML as base64 (standard
 * alphabet, padded, no line breaks — java.util.Base64's basic
 * encoder, whose output android.util.Base64's NO_WRAP matches).
 * Pure and top-level so the JVM tests can pin the exact form.
 *
 * Note: the WebView on the development device does not report
 * this full string on the CDP stream — for a
 * loadDataWithBaseURL navigation it reports just
 * [START_PAGE_DATA_URL_PREFIX] with the payload stripped
 * (verified on the real device, 2026-11-21). The recorder's
 * internal-load classification therefore accepts both forms
 * as the start page's own load (see isInternalDocumentUrl);
 * the full form is kept for WebView builds that do report it
 * whole.
 */
fun startPageDataUrl(html: String): String =
    START_PAGE_DATA_URL_PREFIX +
        java.util.Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))

/**
 * Whether a Document request is one of the app's own internal
 * loads, whose traffic the DevTools recorder does not record:
 * the start page (the home URL or its data: load) and
 * about:blank, the internal blank of that load.
 *
 * The data: load is recognised in either reported form: the
 * bare [START_PAGE_DATA_URL_PREFIX] (what this WebView reports
 * for a loadDataWithBaseURL navigation, payload stripped —
 * verified on the real device, 2026-11-21) or the exact full
 * URL of the tab's current start page load,
 * [startPageDataUrl], nonce included (for a WebView build that
 * reports the URL whole). Every other data: Document carries
 * its payload — a data: URL the user typed, a data: iframe —
 * and is a real page's, not an internal load.
 *
 * Accepted residuals: a Document whose URL is exactly the
 * bare prefix (an empty-payload data: load) is classified
 * internal too — it carries no content, so nothing is lost.
 * And on a WebView that reports the full form, a start page
 * load that is quickly superseded by a reload may be reported
 * with the previous load's URL and miss the exact match; the
 * cost is that one Document being recorded.
 *
 * Everything that is not an internal Document — including
 * every non-Document message — is recorded, even while the
 * tab shows the start page: the start page emits nothing but
 * its own load (see startPageHtml's invariant), so anything
 * else on the stream belongs to a real page (the tail of the
 * page being left, the head of the page being entered, a
 * Service Worker's traffic). This is what keeps a leaving
 * page's unload beacon and an entering page's first Document
 * in the recording (SEN-152).
 */
fun isInternalDocumentUrl(url: String, startPageDataUrl: String?): Boolean =
    isHomeUrl(url) || url == "about:blank" || url == START_PAGE_DATA_URL_PREFIX ||
        (startPageDataUrl != null && url == startPageDataUrl)

/** True for an http(s) link string (external VIEW intents, launch data, saved URLs). */
fun isWebLink(url: String?): Boolean =
    !url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))

/**
 * The launch intent's link that a recreated activity should
 * still open, or null when there is nothing to open: the link
 * was already consumed (its digest matches the saved one), or
 * the intent carries no web link at all. Pure, so the restore
 * branch's decision is unit-tested.
 */
fun launchLinkToOpen(launchUrl: String?, storedDigest: String?): String? {
    if (!isWebLink(launchUrl)) return null
    return if (urlDigest(launchUrl!!) != storedDigest) launchUrl else null
}

/**
 * The launch intent's data string a start may act on, or null
 * when this start is a relaunch from Recents (audit
 * hardening): the system redelivers the ORIGINAL launch
 * intent there, link included, and that link was the user's
 * business in an earlier session, not a new instruction —
 * Chrome skips it the same way. Every onCreate branch takes
 * its launch link through here, because the digest guard in
 * [launchLinkToOpen] only covers recreations that carry saved
 * state; a fresh process has nothing to compare against.
 * Pure, so the decision is unit-tested.
 */
fun launchDataForStart(dataString: String?, launchedFromHistory: Boolean): String? =
    if (launchedFromHistory) null else dataString

/**
 * Whether renderer-crash recovery may restore a tab's saved
 * navigation state (its WebView back/forward list) for
 * [recoveryUrl], instead of loading that URL on its own. Two
 * conditions, both about never restoring a history the tab
 * does not actually have:
 *
 * - The recovery target is a web page. The start page is
 *   rebuilt by loadHome (its entry is a loadData load, not a
 *   fetchable URL), and anything else falls back to a plain
 *   load, exactly as recovery behaved before saved state.
 * - A snapshot exists and was captured at a commit of exactly
 *   this URL ([snapshotUrl]). A snapshot whose current entry
 *   is another page is stale — its capture failed at the last
 *   commit, or an uncommitted load had already moved the tab's
 *   current URL ahead — and restoring it would resurrect a
 *   history the user has already navigated away from.
 *
 * Pure, so the decision is unit-tested.
 */
fun shouldRestoreNavState(recoveryUrl: String, snapshotUrl: String?): Boolean =
    isWebLink(recoveryUrl) && snapshotUrl != null && snapshotUrl == recoveryUrl

/**
 * The most navigation-state snapshots one document may cause.
 * A document's own commits are the one input a page fully
 * controls (the History API), so the budget is what keeps a
 * page that fires URL-changing replaceState calls in a tight
 * loop from making the app re-serialize its whole history on
 * the main thread without bound. Sized on the scale of the
 * history list itself (Chromium caps it at about 50 entries):
 * ordinary browsing, even a pushState-happy SPA, stays far
 * below it.
 */
const val NAV_STATE_CAPTURE_BUDGET: Int = 50

/** What a commit means for the tab's navigation-state snapshot. */
enum class NavStateCapture {
    /** Take a fresh snapshot at this commit. */
    TakeSnapshot,

    /** The snapshot still describes the list; keep it as is. */
    SkipUnchanged,

    /** Drop the snapshot (the start page, or a spent capture budget). */
    DiscardSnapshot
}

/**
 * The shape of a back/forward list, for comparing the live
 * list against the one a snapshot was taken at: its size, its
 * current index, and its first entry's URL. The first URL is
 * part of the shape because at the history cap a new entry
 * pushes the oldest out — size, index and current URL all
 * stay the same while the list's head moves.
 */
data class NavStateShape(val size: Int, val index: Int, val firstUrl: String?)

/**
 * Decide what a commit does to the navigation-state snapshot,
 * from the live list's shape and the shape the snapshot was
 * taken at ([snapshotShape] is null when there is none).
 *
 * - The start page discards: it is never a restore target
 *   (recovery rebuilds it with loadHome), and keeping a
 *   pre-home snapshot would let a crash during the NEXT
 *   page's uncommitted load resurrect the history as it was
 *   before home was ever entered.
 * - An unchanged shape (with the committed URL equal to the
 *   snapshot's) means the snapshot still describes the list:
 *   a replaceState or a plain reload changed at most the
 *   current entry's own state object.
 * - Past the per-document budget, the snapshot is DISCARDED,
 *   not kept: reaching the budget means the shape check above
 *   already failed, so the snapshot no longer describes the
 *   list, and keeping it would let a later URL coincidence
 *   (the page steering back to the snapshot's URL) restore a
 *   history that is not the tab's anymore. The count is of
 *   attempts within the current document and resets when a
 *   commit begins a new document ([newDocument]); it is NOT
 *   reset by a failed capture, so a document whose captures
 *   keep failing cannot loop them either.
 *
 * Pure, so the decision is unit-tested.
 */
fun decideNavStateCapture(
    committedUrl: String,
    newDocument: Boolean,
    capturesThisDocument: Int,
    listShape: NavStateShape,
    snapshotUrl: String?,
    snapshotShape: NavStateShape?
): NavStateCapture {
    if (isHomeUrl(committedUrl)) return NavStateCapture.DiscardSnapshot
    if (snapshotUrl != null && snapshotShape != null &&
        snapshotUrl == committedUrl && listShape == snapshotShape
    ) {
        return NavStateCapture.SkipUnchanged
    }
    val spent = if (newDocument) 0 else capturesThisDocument
    if (spent >= NAV_STATE_CAPTURE_BUDGET) return NavStateCapture.DiscardSnapshot
    return NavStateCapture.TakeSnapshot
}

/**
 * Fixed-length digest of a URL (SHA-256, hex). Used to remember
 * "this launch intent was already consumed" in the saved state
 * without storing the URL itself: the state Bundle has a size
 * budget, and an external app controls the URL's length.
 */
fun urlDigest(url: String): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

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
