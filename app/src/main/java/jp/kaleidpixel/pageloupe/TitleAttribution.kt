package jp.kaleidpixel.pageloupe

/**
 * Which URL a tab's current title belongs to.
 *
 * A tab's title and its URL do not move together: the URL
 * follows onPageStarted, a commit (doUpdateVisitedHistory)
 * can land before the title of the page it commits, and the
 * outgoing document can still change its title while the next
 * load is in flight. Pairing whatever title happens to be
 * current with whatever URL happens to be current therefore
 * mixes two different pages -- the history feature already
 * works around this with its arrived-since-commit flag, and
 * consumers that present the title AS the current page's
 * (the share sheet is the first) need the stricter answer
 * this holder computes.
 *
 * Pure transitions, held as BrowserTab state and driven from
 * its callbacks; JVM-tested in tests/TitleAttributionTest.kt.
 *
 * The rules:
 * - A title that arrives is attributed to the last COMMITTED
 *   URL: the committed document is the one on screen and the
 *   one whose title changes are reported. A title arriving
 *   while a load is still in flight is therefore attributed
 *   to the outgoing page -- it can never be paired with the
 *   incoming URL, which is the safety property that matters.
 *   The commit then either adopts it (same-document commits
 *   only) or drops the attribution; see the next rule.
 * - A commit re-attributes a title that arrived since the
 *   previous commit ONLY when no load started in between
 *   (a same-document commit: an SPA sets document.title just
 *   before pushState, so the title and the new URL are the
 *   same document's). For a full navigation the window
 *   between onPageStarted and the commit belongs to the
 *   OUTGOING document -- it is the only one that can report
 *   a title then -- so a title that arrived in that window
 *   is never adopted for the incoming URL; the new page's
 *   own title arrives after its commit and is attributed by
 *   the first rule. A commit with no adoptable title keeps
 *   an attribution that already names the committed URL
 *   (same-URL reloads, history moves within a page) and
 *   clears any other: the carried-over title is a previous
 *   page's and belongs to nothing current until the new
 *   page reports its own. (Accepted residual: if a WebView
 *   ever delivered a commit with no preceding onPageStarted
 *   for a full navigation, that commit would look
 *   same-document here; the callback order this rule relies
 *   on is the framework's documented one. A load that ends
 *   without committing (a 204, a download, an abort) leaves
 *   the load-started flag set, so a later same-document
 *   commit on the surviving page is treated as a full
 *   navigation and its title attribution is cleared -- a
 *   safe-side degradation (URL-only sharing), never a wrong
 *   pairing.)
 * - The placeholder titles a tab starts with are attributed
 *   to nothing, so they can never be presented as a page's.
 */
object TitleAttribution {

    /**
     * Attribution for a title arriving now. While the start
     * page is showing, its title belongs to the start page:
     * the home load never updates the committed URL (its
     * internal about:blank commit is skipped upstream), so
     * attributing by the committed URL there would hand the
     * start page's title to the previous page -- and a later
     * visit to that page could then share "PageLoupe" as its
     * title.
     */
    fun onTitleReceived(lastCommittedUrl: String, currentUrl: String): String? =
        if (isHomeUrl(currentUrl)) HOME_URL_MARKER
        else lastCommittedUrl.ifEmpty { null }

    /**
     * Attribution after [committedUrl] commits.
     * [loadStartedSinceCommit] separates a full navigation
     * (a load started, so any title that arrived came from
     * the outgoing document) from a same-document commit
     * (no load started, so a fresh title is this document's
     * own, set just before its pushState).
     */
    fun onCommit(
        current: String?,
        arrivedSinceCommit: Boolean,
        loadStartedSinceCommit: Boolean,
        committedUrl: String
    ): String? =
        when {
            arrivedSinceCommit && !loadStartedSinceCommit -> committedUrl
            // The keep branch is only for commits where NO
            // title arrived at all: a title that arrived
            // mid-load belongs to the outgoing document even
            // when the navigation targets the same URL, so
            // it must not ride the keep branch into the new
            // document.
            !arrivedSinceCommit && current == committedUrl -> current
            else -> null
        }

    /** True when [titleUrl] names the page at [currentUrl]. */
    fun belongs(titleUrl: String?, currentUrl: String): Boolean =
        titleUrl != null && titleUrl == currentUrl
}
