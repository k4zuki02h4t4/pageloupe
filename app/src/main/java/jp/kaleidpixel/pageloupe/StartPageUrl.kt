package jp.kaleidpixel.pageloupe

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
