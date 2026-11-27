package jp.kaleidpixel.pageloupe

/**
 * Lifetime bookkeeping for popup probes (SEN-286): at most
 * one undecided probe per tab. Pure state with no Android
 * dependencies, so the rules that the popup reviews kept
 * circling — supersede, expire, and "a late callback may act
 * only while its probe is still registered" — are pinned by
 * JVM tests (PopupProbeRegistryTest).
 *
 * The registry never destroys anything itself: each operation
 * hands the affected probe back and the caller destroys it
 * (immediately or posted, as the calling context requires).
 */
class PopupProbeRegistry<T> {

    private var pending: T? = null

    /**
     * Register [probe] as the tab's pending popup. Returns the
     * previously pending probe when one was superseded (the
     * caller destroys it); null when the slot was empty.
     */
    fun begin(probe: T): T? {
        val old = pending
        pending = probe
        return old
    }

    /**
     * Unregister [probe]. True when it was the pending one —
     * the only state in which a callback arriving for it may
     * still act (open a tab). A probe that was superseded,
     * expired or already retired reports false.
     */
    fun retire(probe: T): Boolean {
        if (pending !== probe) return false
        pending = null
        return true
    }

    /**
     * Unregister the pending probe and return it (the caller
     * destroys it); null when nothing is pending.
     */
    fun expire(): T? {
        val old = pending
        pending = null
        return old
    }

    /** Whether [probe] is the currently pending one. */
    fun isPending(probe: T): Boolean = pending === probe
}
