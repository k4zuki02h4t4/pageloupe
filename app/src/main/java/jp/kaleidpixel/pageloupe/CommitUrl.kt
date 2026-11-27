package jp.kaleidpixel.pageloupe

/**
 * What the tab's current URL becomes when a load FINISHES
 * (SEN-299). onPageFinished fires for loads that never
 * committed — a 204 response, a load the page itself stopped
 * with window.stop(), an aborted redirect chain — with the
 * TARGET url, while the screen still shows the previous page.
 * Adopting that URL unconditionally leaves the address bar
 * (and the lock) naming a site the user is not looking at,
 * over the previous site's content.
 *
 * The rule: an http(s) finish whose sequence did not commit
 * leaves the previous screen in place, and what stays on
 * screen decides:
 * - a load sequence that started FROM the start page leaves
 *   the start page on screen (SEC-167): lastCommittedUrl
 *   deliberately still names the page before home (the home
 *   load's own bookkeeping does not move it), so reverting
 *   to it — or adopting a target that happens to EQUAL it,
 *   the revisit of the very page visited before home
 *   (SEN-365) — would put a departed site's name over the
 *   start page. The caller passes that case as
 *   [startedFromHome] and the home URL — [HOME_URL_MARKER] —
 *   stands. [startedFromHome] is only ever true for a
 *   sequence that has not committed: a commit clears it
 *   (LoadStartLatch), so this branch never masks a committed
 *   finish.
 * - any other finish that does not match the last COMMITTED
 *   url ([BrowserTab.lastCommittedUrl], set only by
 *   doUpdateVisitedHistory) did not commit either: revert to
 *   the committed URL, the page actually on screen.
 * - nothing committed yet (a fresh tab's first load): adopt
 *   the finished URL — there is no previous page to protect.
 * Anything else keeps the historical behavior:
 * - a non-http(s) finish (the start page's data: load,
 *   about:): adopt it — the commit bookkeeping for those is
 *   deliberately different (see the home handling in
 *   BrowserTab) and they are not a page a site can spoof;
 * - a finish that matches the committed URL: adopt it (the
 *   normal case, including reloads and history moves).
 *
 * Pure string work, pinned by JVM tests; BrowserTab only
 * wires the callback arguments in.
 */
object CommitUrl {

    fun onPageFinished(
        finishedUrl: String,
        lastCommittedUrl: String,
        startedFromHome: Boolean
    ): String {
        val web = finishedUrl.startsWith("http://", ignoreCase = true) ||
            finishedUrl.startsWith("https://", ignoreCase = true)
        if (!web) return finishedUrl
        // Before the equality shortcut (SEN-365): a home
        // sequence that ends uncommitted at the very URL
        // visited before home must still land on the home
        // marker — equality here does not mean "committed",
        // because a commit would have cleared startedFromHome
        // before this finish was reported.
        if (startedFromHome) return HOME_URL_MARKER
        if (finishedUrl == lastCommittedUrl) return finishedUrl
        return if (lastCommittedUrl.isNotEmpty()) {
            lastCommittedUrl
        } else {
            finishedUrl
        }
    }
}
