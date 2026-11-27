package jp.kaleidpixel.pageloupe

/**
 * Flood control for one tab's JavaScript dialogs
 * (SEN-386, security-audit run-4 NV#2).
 *
 * A page can re-raise a dialog the instant the previous
 * one is answered (a tight alert loop), and before this
 * policy nothing bounded that: the user had to win a
 * timing race against the next dialog to reach the tab
 * UI at all, and a restored session could return to the
 * same loop after a restart. Chrome's answer is a
 * "prevent this page from creating additional dialogs"
 * affordance; this policy is the state behind the same
 * idea here:
 *
 * - From the second consecutive dialog on, the dialog
 *   offers a suppress option; if the user takes it,
 *   further dialogs are answered unseen (cancelled)
 *   until the next navigation.
 * - As a backstop, after [HARD_CAP] consecutive dialogs
 *   the rest are suppressed even without the option
 *   being taken — a loop the user keeps dismissing out
 *   of habit still ends.
 * - A fresh start resets both the count and the
 *   suppression: a user-initiated navigation or an
 *   origin change (JsDialogResetPolicy). A page-caused
 *   same-origin commit — a self-reload, a script
 *   navigation — does NOT reset: the page is the same
 *   actor asking again.
 *
 * beforeunload is exempt by the caller's construction
 * (it never reaches this policy): it cannot loop on its
 * own — it fires on a navigation attempt — and answering
 * it unseen in either direction would decide a
 * navigation for the user.
 *
 * Pure and JVM-tested (JsDialogFloodPolicyTest);
 * MainActivity presents and answers, BrowserTab owns
 * one instance per tab. Main-thread use only.
 */
class JsDialogFloodPolicy {

    private var consecutive = 0
    private var suppressed = false
    private var hardCapNotified = false

    /** Whether a new dialog request should be answered unseen. */
    fun shouldSuppress(): Boolean = suppressed || consecutive >= HARD_CAP

    /**
     * Whether suppression is the hard cap's doing (not the
     * user's own choice) and the user has not been told yet
     * (review round 2, SEN-14): automatic suppression the
     * user cannot discover reads as "the page stopped
     * responding", so the first hard-cap suppression of a
     * document is announced once. Claimed by the caller
     * when it shows the announcement.
     */
    fun claimHardCapNotice(): Boolean {
        if (suppressed || consecutive < HARD_CAP || hardCapNotified) {
            return false
        }
        hardCapNotified = true
        return true
    }

    /**
     * Whether the dialog about to be built should carry
     * the suppress option: from the second consecutive
     * dialog on (the count is of dialogs already shown).
     */
    fun offerSuppressOption(): Boolean = !suppressed && consecutive >= 1

    /** A dialog was actually presented to the user. */
    fun notePresented() {
        consecutive++
    }

    /** The user took the suppress option on a dialog. */
    fun noteUserChoseSuppress() {
        suppressed = true
    }

    /** A new document committed in the tab: start over. */
    fun noteNavigation() {
        consecutive = 0
        suppressed = false
        hardCapNotified = false
    }

    /**
     * A page-caused commit that is not a fresh start (review
     * round 4, SEN-1/SEC-2): the count, the user's
     * suppression choice and the cap notice all carry over.
     * Which commits reset is decided by
     * [JsDialogResetPolicy.shouldReset] — a user-initiated
     * navigation or an origin change resets; a page-caused
     * same-origin commit (a self-reload, a script navigation,
     * a query rewrite) does not, so a page cannot launder
     * its dialog count through reloads or URL churn.
     */
    fun noteReload() {
        // Intentionally no state change: the page is the
        // same actor asking again.
    }

    companion object {
        /** Consecutive dialogs after which suppression is
         * automatic, option or not. */
        const val HARD_CAP = 10
    }
}

/**
 * Whether a committing document resets a tab's JS-dialog
 * flood state (pure, JVM-tested). The rule (review round 4,
 * SEN-1/SEC-2/PER-5): reset when the load was initiated by
 * the user (address bar, reload button, back/forward, a
 * gestured link click — the userNav the tab tracked), or
 * when the origin changes, or when there is no previous
 * document. A page-caused commit within the same origin —
 * self-reload, script navigation, query-string churn —
 * carries the state over.
 */
object JsDialogResetPolicy {

    fun shouldReset(userInitiated: Boolean, previousUrl: String, newUrl: String): Boolean {
        if (userInitiated) return true
        if (previousUrl.isEmpty()) return true
        return originOf(previousUrl) != originOf(newUrl)
    }

    /**
     * scheme://host[:port] by tolerant string surgery (no
     * URI parsing: the inputs are WebView-reported URLs,
     * and a parse failure must fall back to comparing the
     * whole string, never to "same origin"). Userinfo is
     * stripped (review round 5, SEN-2): it is not part of
     * an origin, and leaving it in let a page reset its
     * count by navigating to the same host with a
     * userinfo part attached.
     */
    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val authStart = schemeEnd + 3
        var authEnd = url.length
        for (i in authStart until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                authEnd = i
                break
            }
        }
        var authority = url.substring(authStart, authEnd)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        return (url.substring(0, authStart) + authority).lowercase()
    }
}
