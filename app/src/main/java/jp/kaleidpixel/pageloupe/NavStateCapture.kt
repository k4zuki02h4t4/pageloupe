package jp.kaleidpixel.pageloupe

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
