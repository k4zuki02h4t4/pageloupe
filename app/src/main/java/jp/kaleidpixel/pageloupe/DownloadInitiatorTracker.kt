package jp.kaleidpixel.pageloupe

/**
 * Remembers a tab's last http(s) navigation attempt — its URL
 * and which frame the navigation was FOR — so the download
 * gate can tell what started a download (SEN-285).
 *
 * The DownloadListener reports only a URL: neither the frame
 * the navigation was for nor the frame whose script may have
 * initiated it. What shouldOverrideUrlLoading sees is the
 * navigation's TARGET frame (request.isForMainFrame), and that
 * is what this tracker records; a subframe script that
 * navigates the top frame therefore classifies as MAIN_FRAME,
 * the same residual the popup rules accept for target=_top
 * (recorded in the design records).
 *
 * Lifetime rules, all event-driven:
 * - Every attempt overwrites the record, redirect hops
 *   included, so the hop that produced a response is the one
 *   on record when its download fires — with one exception:
 *   a subframe record for a URL is sticky. A later main-frame
 *   attempt for the SAME URL does not upgrade it, because two
 *   in-flight navigations for one URL cannot be told apart
 *   when their download responses arrive, and the error must
 *   fall on the safe side (no cookie). A main-frame record is
 *   NOT sticky: a later subframe attempt replaces it.
 * - [classify] consumes the record when the URL matches: one
 *   record classifies at most one download. BrowserTab
 *   classifies at the very top of its DownloadListener, so
 *   even a download that is refused or unsupported spends
 *   its record.
 * - A new main-frame document clears the record (BrowserTab
 *   calls [clear] from onPageStarted): attempts from the
 *   page being left must not classify the new page's
 *   downloads. A navigation that becomes a download never
 *   reaches onPageStarted, so its record survives until the
 *   listener fires — unless a different navigation starts a
 *   new document first, which also clears it (the tab has
 *   moved on; the late download classifies UNKNOWN, the
 *   safe side). SPA history updates (pushState) do NOT
 *   clear: the document has not changed (SEN-335).
 *
 * The record is a single slot, which bounds the guarantee:
 * if another attempt overwrites the record between a
 * subframe attempt and its download response — and a third
 * attempt for the same URL then lands as a main-frame record
 * — the response can still classify as MAIN_FRAME (SEN-336).
 * The window needs two same-URL navigations racing on one
 * page and is accepted rather than tracking per-URL state.
 *
 * POST navigations never reach shouldOverrideUrlLoading and
 * leave no record; app-initiated loads (a typed URL, a
 * restore) do not either. Both classify as UNKNOWN, which the
 * cookie gate treats as closed.
 *
 * Android-free on purpose: the record / stickiness /
 * consumption rules are pinned by JVM tests
 * (DownloadInitiatorTrackerTest).
 */
class DownloadInitiatorTracker {

    private var url: String? = null
    private var forMainFrame: Boolean = false

    /** Record one navigation attempt (see the class KDoc). */
    fun record(url: String, forMainFrame: Boolean) {
        if (this.url == url && !this.forMainFrame && forMainFrame) {
            // Sticky subframe: an in-flight subframe navigation
            // for this URL may still produce the download, and
            // its response cannot be told from the main frame's.
            return
        }
        this.url = url
        this.forMainFrame = forMainFrame
    }

    /**
     * Classify a download URL against the record. An exact URL
     * match consumes the record; a mismatch leaves it in place
     * (it may belong to a navigation still in flight) and
     * reports UNKNOWN.
     */
    fun classify(downloadUrl: String): PageFeaturePolicy.DownloadFrame {
        val recorded = url ?: return PageFeaturePolicy.DownloadFrame.UNKNOWN
        if (recorded != downloadUrl) {
            return PageFeaturePolicy.DownloadFrame.UNKNOWN
        }
        val main = forMainFrame
        clear()
        return if (main) {
            PageFeaturePolicy.DownloadFrame.MAIN_FRAME
        } else {
            PageFeaturePolicy.DownloadFrame.SUBFRAME
        }
    }

    /**
     * Drop the record (a new main-frame document started
     * loading; see the class KDoc).
     */
    fun clear() {
        url = null
        forMainFrame = false
    }
}
