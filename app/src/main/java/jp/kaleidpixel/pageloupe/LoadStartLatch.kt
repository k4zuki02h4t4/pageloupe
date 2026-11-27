package jp.kaleidpixel.pageloupe

/**
 * Where the in-flight load SEQUENCE started from, latched
 * (SEC-167, SEN-353, PER-162).
 *
 * CommitUrl needs to know whether an uncommitted finish
 * leaves the start page on screen, which is true when the
 * sequence began at home. "Sequence" is the operative word:
 * a load can be interrupted by another load before it
 * finishes, and a redirect hop can re-fire the start
 * callback; sampling the origin at every start would let a
 * later hop overwrite the first one's origin with its own
 * target (SEN-353).
 *
 * The latch therefore samples only at a sequence's FIRST
 * start and holds until the sequence ends — at its commit
 * (doUpdateVisitedHistory) or at its finish (onPageFinished),
 * whichever comes first. It deliberately does NOT borrow
 * another flag's lifetime: loadStartedSinceCommit, for
 * instance, is cleared only at commits, so a sequence that
 * ends uncommitted at a finish would leave it set and the
 * next sequence would never sample at all (PER-162).
 *
 * Pure and JVM-tested (LoadStartLatchTest); BrowserTab only
 * feeds it the callbacks. Main-thread use only.
 */
class LoadStartLatch {

    /** The latched origin of the in-flight sequence. */
    var startedFromHome: Boolean = false
        private set

    private var inSequence: Boolean = false

    /**
     * A main-frame load started. [fromHome] is whether the
     * page being left is the start page. Only a sequence's
     * first start samples; later starts in the same sequence
     * keep the first origin.
     */
    fun onLoadStarted(fromHome: Boolean) {
        if (!inSequence) {
            inSequence = true
            startedFromHome = fromHome
        }
    }

    /** The sequence committed: it ends here. */
    fun onCommit() {
        inSequence = false
        startedFromHome = false
    }

    /**
     * The sequence finished: returns the latched origin for
     * CommitUrl's rule and ends the sequence.
     */
    fun onFinish(): Boolean {
        val origin = startedFromHome
        inSequence = false
        startedFromHome = false
        return origin
    }

    /**
     * Forget any in-flight sequence (SEN-360): the tab is
     * being destroyed, possibly after a renderer death that
     * swallowed the sequence's finish. A surviving latch must
     * not suppress the next sequence's sampling.
     */
    fun reset() {
        inSequence = false
        startedFromHome = false
    }
}
