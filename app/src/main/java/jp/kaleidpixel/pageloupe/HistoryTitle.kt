package jp.kaleidpixel.pageloupe

/**
 * Which title a history entry gets at the moment its page
 * commits -- the history side of the attribution question
 * TitleAttribution answers for presentation (SEN-222).
 *
 * The rule, aligned with TitleAttribution's premise:
 * - A title that arrived since the last commit is adopted
 *   ONLY when no load started in between: a same-document
 *   commit (an SPA sets document.title just before
 *   pushState, so the title and the new URL are the same
 *   document's). When a load DID start, a title arriving in
 *   the start-to-commit window can only be the OUTGOING
 *   document's -- adopting it would pin the previous page's
 *   title on the new entry, and the fill-in can never
 *   correct it (it only fills empty titles).
 * - Failing that, when this URL already is the newest entry
 *   (a reload or revisit) AND the tab's title is attributed
 *   to this URL, the tab's title is that page's own.
 * - Otherwise the title has not landed yet: record an empty
 *   title and let the title-arrival path fill it in.
 *
 * Device evidence (2026-11-24, WebView 158.0.8127.36, the
 * gated [nav] lines in the debug log): in every observed
 * full load the commit arrived with NO title yet -- the
 * incoming page's title lands just after its commit, and
 * an outgoing page's title changes all land before
 * onPageStarted (which resets the arrived flag). So for
 * full loads the first branch simply never fires and the
 * fill-in does the work; the load-started guard is what
 * keeps the rule TRUE on a WebView whose callback timing
 * differs, not a behaviour change on this one.
 *
 * Accepted residual (the same one TitleAttribution
 * documents): a load that starts but never commits (a 204,
 * an aborted chain) leaves the load-started flag set, so a
 * same-document commit on the surviving page afterwards is
 * treated as a full navigation here too and its title is
 * recorded empty (the fill-in cannot run — no title arrives
 * after that commit). Safe-side: an entry with no title,
 * never a wrong one.
 *
 * Pure; JVM-tested in tests/HistoryTitleTest.kt.
 */
object HistoryTitle {

    fun atCommit(
        arrivedSinceCommit: Boolean,
        loadStartedSinceCommit: Boolean,
        currentTitle: String,
        titleUrl: String?,
        committedUrl: String,
        newestHistoryUrl: String?,
    ): String = when {
        arrivedSinceCommit && !loadStartedSinceCommit -> currentTitle
        newestHistoryUrl == committedUrl &&
            TitleAttribution.belongs(titleUrl, committedUrl) -> currentTitle
        else -> ""
    }
}
