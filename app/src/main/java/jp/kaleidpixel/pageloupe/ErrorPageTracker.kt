package jp.kaleidpixel.pageloupe

/**
 * Which recent loads failed, so their commits can be
 * marked as error pages (v0.43.0; the lifetime rules were
 * pinned down over review rounds r1-r11 and one round
 * of device evidence — see the finish rule below).
 *
 * An error page still commits its failed URL (SEN-347),
 * and what is on screen then is the WebView's own error
 * document, not the site's page: the commit must be
 * marked so the address bar does not vouch for it
 * (BrowserTab.lastCommitWasErrorPage, consumed by
 * PageFeaturePolicy.showsLock / showsCleartextWarning).
 *
 * Failures arrive from two callbacks of different
 * certainty, and are kept apart for that reason:
 * - onReceivedError fires for subresources too, so only
 *   the calls flagged as main-frame are recorded, into a
 *   single slot holding the page's own failure. Only a
 *   later main-frame failure replaces it (same signal,
 *   last wins — a redirect chain fails more than once);
 *   nothing else can displace or evict it. The filter
 *   lives here, not at the call site, so no caller can
 *   record a subresource failure into the slot.
 * - onReceivedSslError does not say which frame failed,
 *   so its records form a set keyed by the URL that
 *   failed validation: a record can only ever mark a
 *   commit of that same URL, which for a main-frame
 *   failure is exactly its commit's URL. A set, not a
 *   single slot: with one slot, a subresource record
 *   arriving after the main frame's failure displaced it
 *   (r3), and one standing before it blocked it under
 *   first-wins (r4) — apart from the bounded clear
 *   below, recording never displaces or blocks the
 *   OTHER provenance's record.
 *
 * A record lives until the next load start or commit:
 * - a load start drops every record but the start URL's
 *   own. The start callback can be delivered AFTER the
 *   same load's failure callback (both are posted around
 *   the commit), so the start URL's record must survive
 *   its start (r2);
 * - a commit consumes every record, marked or not, so
 *   a failure recorded before a commit can never mark
 *   a later one (records that arrive after a commit are
 *   residual (1)'s concern);
 * - a load's FINISH retires nothing. On WebView 158 an
 *   error page's finish is delivered BEFORE its history
 *   commit (device evidence, v0.43.0 E2E 2026-11-27:
 *   started -> failed -> finished -> commit, all within
 *   a millisecond), so retiring at the finish would
 *   erase the record just before its own commit reads
 *   it. An earlier design retired records there on the
 *   assumption that a commit precedes its finish; that
 *   holds for normal loads, not for error pages.
 *
 * Bounds: the SSL set holds at most [MAX_SSL_RECORDS]
 * records; when full it is cleared before adding, so the
 * newest record is never the one dropped. The clear can
 * only evict SSL records — never the main-frame slot.
 * An SSL URL longer than [MAX_SSL_URL_LENGTH] is not
 * recorded at all: TLS fails before any length check on
 * the wire, and a page making many distinct overlong
 * URLs fail could otherwise pin the set's worth of
 * multi-megabyte strings in the heap (r5).
 *
 * Accepted residuals, stated separately because they do
 * not fail the same way:
 * (1) A leftover record can mark a later successful
 * NEW-document commit of the same URL. Records live
 * until the next start or commit, so one left behind
 * by a load that failed for real but never commits
 * (superseded after its failure was recorded, before
 * its error page could commit), or recorded by a
 * subresource failure after its page's commit, is
 * still there when that URL is later opened directly —
 * and under the failure-before-start delivery order it
 * is indistinguishable from the new load's own failure.
 * The direction depends on the scheme, because the
 * mark suppresses both indicators: on an https page it
 * hides the lock (safe side — the bar vouches for less
 * than it could); on an http page it hides the
 * cleartext warning, which is that warning's
 * fail-open. The mark never shows a lock that is not
 * there. Arranging it for another origin's URL would
 * require that origin's load to actually fail in this
 * tab first, which the page does not control; the
 * realistic case is the page's own origin. A
 * same-document commit does not newly adopt the
 * tracker's verdict — it only inherits the standing
 * mark — so pushState cannot newly mark a document
 * this way (r9). The same-document test is
 * loadStartedSinceCommit, which survives an
 * uncommitted finish by design, so a same-document
 * commit that follows an uncommitted load start takes
 * the new-document branch and can adopt a verdict
 * after all; that narrow case is part of this
 * residual (r10).
 * The stopped/superseded case that used to dominate
 * this residual is closed since SEN-385 (v0.44.2):
 * an aborted load reports ERROR_UNKNOWN and is not
 * recorded at all (see recordError), so the record a
 * replacing navigation's commit could adopt can now
 * only come from a failure that really happened. The
 * gate rests on Chromium mapping ERR_ABORTED to
 * ERROR_UNKNOWN; the device procedure that would
 * confirm the mapping is NEEDS-VALIDATION #1 of
 * security-audit run-4.
 * (2) Fail-open — the error page shows the lock, as it
 * did before v0.43.0: a redirect chain whose failure is
 * recorded before its start callback is delivered loses
 * its record at that start, which only keeps the start
 * URL's own.
 * (3) Fail-open, likewise: if no onReceivedError follows
 * the main frame's SSL failure and a page then floods
 * more than [MAX_SSL_RECORDS] distinct failing
 * subresource URLs before the commit, the overflow clear
 * drops the main frame's SSL record too. The main-frame
 * slot is never dropped this way. The same applies when
 * the main frame's own URL exceeds [MAX_SSL_URL_LENGTH]:
 * its SSL record is not kept, and only a following
 * onReceivedError can still mark the commit.
 *
 * Pure and JVM-tested (ErrorPageTrackerTest); BrowserTab
 * only feeds it the callbacks. Main-thread use only.
 */
class ErrorPageTracker {

    /** The main frame's failed URL, from onReceivedError. */
    private var mainFrameErrorUrl: String? = null

    /** Failed URLs from onReceivedSslError, frame unknown. */
    private val sslErrorUrls = LinkedHashSet<String>()

    /**
     * A load failed (onReceivedError). Only a main-frame
     * failure is the page's own: subresource calls are
     * ignored here, whatever the caller passed along.
     *
     * An aborted load is not recorded (SEN-385): a load
     * that was stopped or superseded by a newer navigation
     * reports ERROR_UNKNOWN (Chromium's ERR_ABORTED maps
     * to it) and commits no error page of its own — the
     * navigation that replaced it commits instead. Recording
     * it left a record that the replacing load's commit of
     * the same URL (possibly another origin's) then adopted,
     * which on an http page suppressed the cleartext
     * warning for a page that never failed.
     */
    fun recordError(url: String?, isForMainFrame: Boolean, errorCode: Int) {
        if (!isForMainFrame) return
        if (errorCode == ERROR_CODE_UNKNOWN) return
        if (url != null) mainFrameErrorUrl = url
    }

    /** A URL failed certificate validation (onReceivedSslError). */
    fun recordSslError(url: String?) {
        if (url == null || url.length > MAX_SSL_URL_LENGTH) return
        if (url in sslErrorUrls) return
        if (sslErrorUrls.size >= MAX_SSL_RECORDS) sslErrorUrls.clear()
        sslErrorUrls.add(url)
    }

    /**
     * A main-frame load started: drop every record but the
     * start URL's own (see the class doc). Returns whether
     * anything was dropped, for the caller's debug log.
     */
    fun onLoadStarted(url: String): Boolean {
        var dropped = false
        if (mainFrameErrorUrl != null && mainFrameErrorUrl != url) {
            mainFrameErrorUrl = null
            dropped = true
        }
        if (sslErrorUrls.any { it != url }) {
            sslErrorUrls.retainAll(setOf(url))
            dropped = true
        }
        return dropped
    }

    /**
     * A URL committed: whether that commit is an error
     * page. Every record is consumed either way.
     */
    fun consumeAtCommit(url: String): Boolean {
        val marked = url == mainFrameErrorUrl || url in sslErrorUrls
        mainFrameErrorUrl = null
        sslErrorUrls.clear()
        return marked
    }

    // There is deliberately no finish hook: an error
    // page's finish precedes its commit (see the class
    // doc), so a finish must never retire a record.

    companion object {
        /** Mirrors WebViewClient.ERROR_UNKNOWN: the code an
         * aborted (stopped or superseded) load reports. */
        const val ERROR_CODE_UNKNOWN = -1

        /** Cap on the SSL record set (see the class doc). */
        const val MAX_SSL_RECORDS = 32

        /** SSL URLs longer than this are not recorded. */
        const val MAX_SSL_URL_LENGTH = 8192
    }
}
