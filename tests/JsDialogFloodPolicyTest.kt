package tests

import jp.kaleidpixel.pageloupe.JsDialogFloodPolicy
import jp.kaleidpixel.pageloupe.JsDialogResetPolicy

// JsDialogFloodPolicy (SEN-386): a page looping JS dialogs
// must not be able to keep the tab UI unreachable. The
// rules pinned here: the first dialog never carries the
// suppress option and is never suppressed, the option
// appears from the second consecutive dialog on, taking
// it suppresses until the next navigation, the hard cap
// suppresses even without the option, and a navigation
// resets everything.
fun main() {
    var failures = 0
    var checks = 0
    fun check(name: String, cond: Boolean) {
        checks++
        if (!cond) {
            failures++
            println("FAIL: $name")
        }
    }

    // The first dialog is plain: shown, no option.
    run {
        val p = JsDialogFloodPolicy()
        check("first dialog is not suppressed", !p.shouldSuppress())
        check("first dialog offers no option", !p.offerSuppressOption())
    }

    // From the second consecutive dialog on, the option is
    // offered; taking it suppresses what follows.
    run {
        val p = JsDialogFloodPolicy()
        p.notePresented()
        check("second dialog offers the option", p.offerSuppressOption())
        check("second dialog is not yet suppressed", !p.shouldSuppress())
        p.noteUserChoseSuppress()
        check("after the user chooses, dialogs are suppressed", p.shouldSuppress())
        check("a suppressed tab offers no further option", !p.offerSuppressOption())
    }

    // The hard cap suppresses even if the user never takes
    // the option.
    run {
        val p = JsDialogFloodPolicy()
        repeat(JsDialogFloodPolicy.HARD_CAP - 1) { p.notePresented() }
        check("one below the cap still shows", !p.shouldSuppress())
        p.notePresented()
        check("at the cap, suppression is automatic", p.shouldSuppress())
    }

    // A navigation resets both the count and a suppression
    // the user chose.
    run {
        val p = JsDialogFloodPolicy()
        p.notePresented()
        p.noteUserChoseSuppress()
        p.noteNavigation()
        check("navigation clears suppression", !p.shouldSuppress())
        check("navigation clears the option", !p.offerSuppressOption())
        p.notePresented()
        check("counting restarts after navigation", p.offerSuppressOption())
    }

    // The hard-cap notice is claimable exactly once per
    // document, and never for a user-chosen suppression
    // (SEN-14).
    run {
        val p = JsDialogFloodPolicy()
        repeat(JsDialogFloodPolicy.HARD_CAP) { p.notePresented() }
        check("hard-cap notice claimable at the cap", p.claimHardCapNotice())
        check("hard-cap notice only once", !p.claimHardCapNotice())
        p.noteNavigation()
        repeat(JsDialogFloodPolicy.HARD_CAP) { p.notePresented() }
        check("hard-cap notice resets on navigation", p.claimHardCapNotice())
    }
    run {
        val p = JsDialogFloodPolicy()
        p.notePresented()
        p.noteUserChoseSuppress()
        repeat(JsDialogFloodPolicy.HARD_CAP) { p.notePresented() }
        check("no hard-cap notice for a chosen suppression", !p.claimHardCapNotice())
    }

    // A same-URL reload does NOT reset the count (SEC-7):
    // a page that reloads after every dialog must still
    // reach the suppression offer and the hard cap.
    run {
        val p = JsDialogFloodPolicy()
        p.notePresented()
        p.noteReload()
        check("reload keeps the count: offer on the 2nd dialog",
            p.offerSuppressOption())
        repeat(JsDialogFloodPolicy.HARD_CAP - 1) { p.notePresented() }
        check("reload loop reaches the hard cap", p.shouldSuppress())
        check("hard-cap notice claimable across reloads", p.claimHardCapNotice())
    }

    // JsDialogResetPolicy (review round 4, SEN-1): which
    // commits reset the flood state.
    run {
        check("reset: user-initiated load resets",
            JsDialogResetPolicy.shouldReset(
                true, "https://a.example/x", "https://a.example/x"))
        check("reset: first document resets",
            JsDialogResetPolicy.shouldReset(
                false, "", "https://a.example/x"))
        check("reset: origin change resets",
            JsDialogResetPolicy.shouldReset(
                false, "https://a.example/x", "https://b.example/x"))
        check("reset: port change is an origin change",
            JsDialogResetPolicy.shouldReset(
                false, "https://a.example/x", "https://a.example:8443/x"))
        check("reset: page-caused same-origin navigation carries",
            !JsDialogResetPolicy.shouldReset(
                false, "https://a.example/x", "https://a.example/y"))
        check("reset: page-caused query churn carries",
            !JsDialogResetPolicy.shouldReset(
                false, "https://a.example/x?t=1", "https://a.example/x?t=2"))
        check("reset: page-caused reload carries",
            !JsDialogResetPolicy.shouldReset(
                false, "https://a.example/x", "https://a.example/x"))
        // Userinfo is not part of an origin (round 5,
        // SEN-2): adding or changing it must not launder
        // the count.
        check("reset: userinfo change is not an origin change",
            !JsDialogResetPolicy.shouldReset(
                false, "https://a.example/x", "https://u@a.example/x"))
        check("reset: userinfo on both sides still same origin",
            !JsDialogResetPolicy.shouldReset(
                false, "https://u:p@a.example/x", "https://a.example/y"))
        check("reset: scheme case is not an origin change",
            !JsDialogResetPolicy.shouldReset(
                false, "HTTPS://a.example/x", "https://a.example/y"))
    }

    println("JsDialogFloodPolicyTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
