package jp.kaleidpixel.pageloupe

/**
 * The per-session frontend state of the DevTools panel, grouped
 * so the lifecycle rules live in one place.
 *
 * All writes go through the note* methods below (the setters
 * are private), so the rules are structural rather than prose:
 *
 * - [frontendLoaded] and [frontendFailedUrl] are a pair. Every
 *   (re)load path starts from both cleared, or the stats ticker
 *   can overwrite a load error with "recording". The pair is
 *   cleared only by [resetLoadState], which open and close both
 *   call; neither field can be lowered on its own from outside.
 * - [lastTargetSecret] is a sticky security flag and
 *   deliberately sits OUTSIDE [resetLoadState]: opening or
 *   closing a session must never lower it, because late console
 *   messages from a closed secret frontend still need
 *   redaction. It is raised by [noteSessionOpened] and
 *   re-derived only by [notePageFinished] — those are the only
 *   two writers, and only the latter can lower it.
 *
 * Pure JVM holder (no Android references) so the lifecycle is
 * unit-testable. Every current call site runs on the UI thread
 * (WebView callbacks, the main-looper stats ticker); the
 * @Volatile modifiers are defensive visibility insurance,
 * not because a cross-thread writer exists today.
 */
class DevToolsSessionState {

    /**
     * Sticky redaction flag: true once any DevTools session has
     * inspected a secret tab, until a new frontend load for a
     * live (possibly non-secret) target finishes. Never lowered
     * by an open or by the about:blank unload on close, so late
     * frontend messages from a secret session stay redacted.
     */
    @Volatile var lastTargetSecret: Boolean = false
        private set

    /**
     * True once the CURRENT session's frontend has finished a
     * real load. The per-second stats ticker
     * must not overwrite the status line before this: connection
     * failures and ambiguity errors are shown on the same single
     * line, and a "recording" stats text would erase them within
     * a second even though nothing is being recorded.
     */
    @Volatile var frontendLoaded: Boolean = false
        private set

    /**
     * URL of the frontend load that failed with a main-frame
     * error in the current session — a network-layer error
     * or an HTTP error status. WebView calls onPageFinished
     * even for a failed main-frame load, with the failed URL:
     * without this record, the finish would mark the frontend
     * as loaded and the stats ticker would overwrite the error
     * with "recording" within a second. (Connect and ambiguity
     * failures never reach a load; this record is what covers
     * load failures.)
     */
    @Volatile var frontendFailedUrl: String? = null
        private set

    /**
     * Start a (re)load: the load pair returns to its initial
     * state together. [lastTargetSecret] is NOT touched — see
     * the class doc for why lowering it here would break
     * redaction of late messages.
     */
    fun resetLoadState() {
        frontendLoaded = false
        frontendFailedUrl = null
    }

    /**
     * Record that the current load failed for [url]. A null
     * [url] identifies no load, so it is ignored: the previous
     * record stands until [resetLoadState], which keeps the
     * class doc's promise that the pair is never lowered one
     * field at a time.
     */
    fun noteLoadFailed(url: String?) {
        if (url != null) frontendFailedUrl = url
    }

    /**
     * Record an HTTP response status for the current load.
     * HTTP errors never reach onReceivedError, so without
     * this entry point a 404 frontend page would finish as
     * "loaded" and no error would be shown. Only 4xx/5xx
     * count as failures:
     * 1xx–3xx responses are not load failures (a redirect
     * finishes normally). The threshold lives here so the
     * WebView callback stays a thin relay and the rule is
     * unit-testable.
     *
     * @return true when the status is an HTTP error and the
     * caller should surface it. The URL itself is recorded
     * under the same rule as [noteLoadFailed]: a null [url]
     * identifies no load and leaves the previous record
     * standing. That mirrors the network-error path, which
     * also surfaces the error without recording when the URL
     * is null — in that case a later finish can still mark
     * the load as loaded and the ticker may overwrite the
     * error. WebResourceRequest.url is non-null in practice,
     * so this is a contract-level residual, not a live path.
     */
    fun noteHttpError(url: String?, statusCode: Int): Boolean {
        if (statusCode < 400) return false
        noteLoadFailed(url)
        return true
    }

    /**
     * A session opened: raise the sticky flag when the target
     * is secret. Opening can only raise it, never lower it.
     */
    fun noteSessionOpened(targetSecret: Boolean) {
        if (targetSecret) lastTargetSecret = true
    }

    /**
     * A frontend page finished loading. Two rules:
     *
     * - With a live target and a real (non-blank) URL, the
     *   sticky flag is re-derived from that target. Finishes
     *   with no live target (the about:blank unload on close)
     *   must NOT touch the flag: late messages from the closed
     *   secret frontend still need redaction. The re-derivation
     *   does not distinguish a late finish arriving from an
     *   earlier frontend instance; that narrow window is an
     *   accepted residue recorded in the design records, so this flag
     *   alone must not be treated as a windowless guarantee.
     * - A real page marks the frontend loaded — unless THIS
     *   finish is the load that just failed ([frontendFailedUrl]
     *   matches), because WebView fires the finish for failed
     *   loads too.
     */
    fun notePageFinished(url: String?, targetPresent: Boolean, targetSecret: Boolean) {
        val realPage = url != null && url != "about:blank"
        if (targetPresent && realPage) {
            lastTargetSecret = targetSecret
        }
        if (realPage && url != frontendFailedUrl) {
            frontendLoaded = true
        }
    }
}
