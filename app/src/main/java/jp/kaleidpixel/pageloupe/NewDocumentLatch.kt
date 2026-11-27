package jp.kaleidpixel.pageloupe

/**
 * Whether the next commit replaces the document: a load
 * sequence began (onPageStarted) and has not ended yet.
 *
 * This exists because the older flag for the same question,
 * loadStartedSinceCommit, has the wrong lifetime for it: that
 * flag clears only at commits, by design (its consumers —
 * title attribution, the per-document nav-state budget —
 * were built and verified on exactly that lifetime, SEN-222).
 * A sequence that ends uncommitted (a download, a 204, an
 * aborted chain) therefore leaves it set, and a later
 * same-document commit (pushState) would read it as "new
 * document" — for document-replacement consumers
 * (BrowserTab.Listener.onDocumentReplaced, v0.41.0) that is
 * false: the live document was never replaced.
 *
 * The latch follows LoadStartLatch's shape (SEC-167): sample
 * at a sequence's first start, end the sequence at its
 * commit OR at its finish, whichever comes first. A sequence
 * interrupted by another load keeps the latch up (the second
 * load is the one that may commit a new document). Pure and
 * JVM-tested (NewDocumentLatchTest); BrowserTab only feeds
 * it the callbacks. Main-thread use only.
 */
class NewDocumentLatch {

    private var inSequence: Boolean = false

    /** A main-frame load started. Only a sequence's first
     * start raises the latch; redirect hops and interruptions
     * within the sequence keep it. */
    fun onLoadStarted() {
        inSequence = true
    }

    /**
     * A commit arrived: returns whether it began a new
     * document (a sequence was in flight) and ends the
     * sequence either way.
     */
    fun onCommit(): Boolean {
        val began = inSequence
        inSequence = false
        return began
    }

    /** The sequence finished without (or after) committing:
     * it is over, so a later same-document commit must not
     * inherit its start. */
    fun onFinish() {
        inSequence = false
    }

    /** Forget any in-flight sequence (tab teardown, renderer
     * death): a surviving latch must not leak into the next
     * document's first commit. */
    fun reset() {
        inSequence = false
    }
}
