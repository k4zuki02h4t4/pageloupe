package jp.kaleidpixel.pageloupe

/**
 * Holds at most one pending page-feature interaction (a file
 * pick, a permission prompt, a download confirmation, a
 * fullscreen). The point of the type is the exactly-once
 * contract: a value is handed out by [take] / [takeIf] at most
 * once — a second take returns null — so the framework callback
 * attached to the value can be answered exactly once, and
 * "superseded", "cancelled" and "owner went away" all funnel
 * through the same take-then-answer step instead of parallel
 * nullable fields that must be kept in sync by hand.
 *
 * Android-free and generic so the semantics are unit testable;
 * the values themselves (tab + framework callback) live in the
 * activity.
 */
class PendingSlot<T> {

    private var value: T? = null

    fun peek(): T? = value

    fun isPending(): Boolean = value != null

    /**
     * Install [v], returning the superseded value if any. The
     * caller must retire the returned value (answer it as
     * cancelled) — it is no longer held here.
     */
    fun replace(v: T): T? {
        val old = value
        value = v
        return old
    }

    /** Install [v] only when empty; false (and no change) when occupied. */
    fun offer(v: T): Boolean {
        if (value != null) return false
        value = v
        return true
    }

    /** Take the value out, handing it to exactly one caller. */
    fun take(): T? {
        val v = value
        value = null
        return v
    }

    /** Take the value out only when it matches [pred]. */
    fun takeIf(pred: (T) -> Boolean): T? {
        val v = value ?: return null
        if (!pred(v)) return null
        value = null
        return v
    }

    /** Drop the value WITHOUT handing it out. */
    fun clear() {
        value = null
    }
}
