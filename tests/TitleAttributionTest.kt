package tests

import jp.kaleidpixel.pageloupe.TitleAttribution

// JVM tests for the title-attribution transitions BrowserTab
// drives from its title/commit callbacks: which URL the tab's
// current title belongs to (the share sheet pairs a title
// with a URL only when this says they belong together).
// Run via tests/run_tests.sh.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

fun main() {
    // Arrival: attributed to the last committed page, or to
    // nothing before the first commit.
    check(
        "first title before any commit is unattributed",
        TitleAttribution.onTitleReceived("", "https://a.example/") == null
    )
    check(
        "title attributed to committed page",
        TitleAttribution.onTitleReceived("https://a.example/", "https://b.example/") ==
            "https://a.example/"
    )

    // While the start page shows, an arriving title is the
    // start page's own, never the previous page's (the home
    // load does not update the committed URL).
    check(
        "title arriving on the start page belongs to the start page",
        TitleAttribution.onTitleReceived(
            "https://a.example/", "pageloupe://newtab/"
        ) == "pageloupe://newtab/"
    )

    // Same-document commit (no load started): a title that
    // arrived since the last commit is this document's own
    // (set just before pushState) and is adopted.
    check(
        "same-document commit adopts a fresh title",
        TitleAttribution.onCommit(
            "https://a.example/", arrivedSinceCommit = true,
            loadStartedSinceCommit = false, committedUrl = "https://a.example/app"
        ) == "https://a.example/app"
    )
    // Full navigation (a load started): a title that arrived
    // mid-load came from the OUTGOING document and is never
    // adopted for the incoming URL.
    check(
        "full-navigation commit never adopts a mid-load title",
        TitleAttribution.onCommit(
            "https://a.example/", arrivedSinceCommit = true,
            loadStartedSinceCommit = true, committedUrl = "https://b.example/"
        ) == null
    )
    // A commit with no fresh title clears a previous page's
    // attribution...
    check(
        "commit without a fresh title clears the old page's",
        TitleAttribution.onCommit(
            "https://a.example/", arrivedSinceCommit = false,
            loadStartedSinceCommit = true, committedUrl = "https://b.example/"
        ) == null
    )
    // ...but keeps one that already names the committed URL
    // (same-URL reload, history move within a page).
    check(
        "same-url commit keeps the title",
        TitleAttribution.onCommit(
            "https://a.example/", arrivedSinceCommit = false,
            loadStartedSinceCommit = true, committedUrl = "https://a.example/"
        ) == "https://a.example/"
    )

    // A title that arrived mid-load is the outgoing
    // document's even on a same-URL navigation: the keep
    // branch does not apply when a title arrived.
    check(
        "same-url commit with a mid-load title clears attribution",
        TitleAttribution.onCommit(
            "https://a.example/", arrivedSinceCommit = true,
            loadStartedSinceCommit = true, committedUrl = "https://a.example/"
        ) == null
    )

    // Belonging is an exact match against the current URL.
    check(
        "belongs only on exact match",
        TitleAttribution.belongs("https://a.example/", "https://a.example/") &&
            !TitleAttribution.belongs("https://a.example/", "https://b.example/") &&
            !TitleAttribution.belongs(null, "https://a.example/")
    )

    if (failures > 0) {
        println("TitleAttributionTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("TitleAttributionTest: all $checks checks passed")
}
