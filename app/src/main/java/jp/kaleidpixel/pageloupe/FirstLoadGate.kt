package jp.kaleidpixel.pageloupe

/**
 * Process-wide gate for the first ML Kit translate loads
 * (SEN-1101).
 *
 * A client's first translate() makes ML Kit load that
 * language pair's model resource, and the load runs the
 * translate library's one-time native initialization
 * (TranslateJni's static zze() is an unsynchronized lazy
 * init, and the per-pair nativeInit carries an init_google
 * state check -- both verified against the 17.0.3
 * bytecode). When a fresh process's first translation
 * covered several languages at once, those first loads
 * overlapped on ML Kit's thread pool and the second
 * initialization aborted the whole process inside
 * libtranslate_jni, seen on device 2026-11-27. A
 * successful translate() proves a load completed -- the
 * call passes through the load unconditionally -- so one
 * success proves the initialization for the whole
 * process, and from then on loads are safe at any
 * concurrency. A failure proves nothing: whether that
 * load reached the initialization is unknowable from
 * here, so the gate stays shut until some item succeeds.
 *
 * The discipline: until proven, at most one load may be
 * in flight in the whole process. The state lives at
 * process scope, not in one run's locals, because a run
 * whose generation has moved on cannot stop the load it
 * started; the next run has to wait for that load to
 * settle instead of overlapping it. The wiring keeps
 * this honest about what "settled" means: a Task bound
 * to a translator's cancellation token settles the
 * moment the token is cancelled, while the load itself
 * keeps running on ML Kit's thread pool (both verified
 * against the bytecode), so PageTranslator does not
 * close the claiming client at the run's end -- the
 * claimed Task then settles only when its load has
 * really finished, and the gate settles with it.
 * Downloads never load the
 * library (they settle through the model manager), so
 * translateAll's pump -- the only place translate() is
 * called -- is the one place this gate has to cover.
 *
 * Holders are opaque tokens, one per translateAll
 * invocation, compared by identity. All access is on the
 * main thread, like the rest of PageTranslator's state,
 * so the gate needs no synchronization.
 */
class FirstLoadGate {

    /** What issuing one more item means for a holder now. */
    enum class Decision {
        /** The initialization is proven; issue freely. */
        FREE,

        /** No load is in flight; issue as the claim holder. */
        CLAIM,

        /** Another holder's load is in flight; wait. */
        WAIT,

        /** This holder's own claimed load is still out. */
        HELD,
    }

    private var proven = false
    private var claimHolder: Any? = null
    private val waiters = LinkedHashMap<Any, () -> Unit>()

    /** Whether some translation has proved the one-time
     * native initialization complete in this process. */
    val isProven: Boolean get() = proven

    fun decision(holder: Any): Decision = when {
        proven -> Decision.FREE
        claimHolder === holder -> Decision.HELD
        claimHolder != null -> Decision.WAIT
        else -> Decision.CLAIM
    }

    /** Marks [holder] as the one load in flight. The
     * caller must have just read a CLAIM decision. */
    fun claim(holder: Any) {
        check(!proven && claimHolder == null) {
            "claim without a CLAIM decision"
        }
        claimHolder = holder
    }

    /** Registers [resume] to run when the outstanding
     * claim settles. One registration per holder; a repeat
     * while already waiting keeps the first callback. */
    fun waitForRelease(holder: Any, resume: () -> Unit) {
        if (holder !in waiters) waiters[holder] = resume
    }

    /** Drops [holder]'s wait registration, if any (its run
     * ended while waiting). The claim itself is untouched:
     * a holder that holds the claim is not in the waiters,
     * and its settlement remains the waiters' release. */
    fun cancelWait(holder: Any) {
        waiters.remove(holder)
    }

    /**
     * Settles [holder]'s claim in any outcome and returns
     * the waiting runs' resume callbacks, oldest first; a
     * success also proves the initialization. A settle
     * from a holder that holds no claim (or a repeat
     * settle) changes nothing and returns an empty list.
     */
    fun settle(holder: Any, succeeded: Boolean): List<() -> Unit> {
        if (claimHolder !== holder) return emptyList()
        claimHolder = null
        if (succeeded) proven = true
        val resumes = waiters.values.toList()
        waiters.clear()
        return resumes
    }
}
