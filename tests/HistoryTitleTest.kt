package tests

import jp.kaleidpixel.pageloupe.HistoryTitle

// JVM tests for the history side of title attribution
// (SEN-222): which title a history entry is recorded with at
// the moment its page commits. The wiring (BrowserTab's
// flags read inside the commit callback) is device-verified;
// the decision itself is pinned here.
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
    val a = "https://a.example/"
    val b = "https://b.example/"

    // Same-document commit: an SPA set the title just before
    // pushState -- the arrived title is the committing page's
    // own and is adopted.
    check(
        "same-document commit adopts the arrived title",
        HistoryTitle.atCommit(
            arrivedSinceCommit = true,
            loadStartedSinceCommit = false,
            currentTitle = "New SPA title",
            titleUrl = a,
            committedUrl = b,
            newestHistoryUrl = a,
        ) == "New SPA title"
    )

    // Full-load commit: a title that arrived while the load
    // was in flight is the outgoing page's -- never adopted,
    // even when it is the only title around.
    check(
        "title arrived mid-load is not adopted",
        HistoryTitle.atCommit(
            arrivedSinceCommit = true,
            loadStartedSinceCommit = true,
            currentTitle = "Outgoing page title",
            titleUrl = a,
            committedUrl = b,
            newestHistoryUrl = a,
        ) == ""
    )

    // The plain full load (the only shape observed on device):
    // nothing arrived by commit time, so the entry starts
    // untitled and the arrival after the commit fills it in.
    check(
        "full-load commit with no arrival records an empty title",
        HistoryTitle.atCommit(
            arrivedSinceCommit = false,
            loadStartedSinceCommit = true,
            currentTitle = "Previous page title",
            titleUrl = a,
            committedUrl = b,
            newestHistoryUrl = a,
        ) == ""
    )

    // Reload / revisit: the newest entry is this URL and the
    // tab's title is attributed to it -- the title is the
    // page's own and is kept.
    check(
        "revisit with attribution to the same URL keeps the title",
        HistoryTitle.atCommit(
            arrivedSinceCommit = false,
            loadStartedSinceCommit = true,
            currentTitle = "Page A",
            titleUrl = a,
            committedUrl = a,
            newestHistoryUrl = a,
        ) == "Page A"
    )

    // Same-URL revisit WITHOUT attribution (the carried-over
    // title belongs to a previous page): not adopted.
    check(
        "revisit without attribution records an empty title",
        HistoryTitle.atCommit(
            arrivedSinceCommit = false,
            loadStartedSinceCommit = true,
            currentTitle = "Previous page title",
            titleUrl = b,
            committedUrl = a,
            newestHistoryUrl = a,
        ) == ""
    )

    // Attribution alone is not enough when the newest entry
    // is a different page (the entry being written is not
    // the one the title was kept for).
    check(
        "attribution for another entry does not adopt",
        HistoryTitle.atCommit(
            arrivedSinceCommit = false,
            loadStartedSinceCommit = false,
            currentTitle = "Page A",
            titleUrl = a,
            committedUrl = a,
            newestHistoryUrl = b,
        ) == ""
    )

    // Branch interaction, pinned at the function level: the
    // keep branch does not itself check the load-started
    // flag, so a mid-load arrival paired with an attribution
    // to the committed URL would adopt. In the real wiring
    // this combination is unreachable -- TitleAttribution
    // .onCommit has already cleared the attribution whenever
    // a title arrived mid-load (its KDoc: a mid-load title
    // belongs to the outgoing document even for a same-URL
    // navigation), so the composed behaviour records an
    // empty title and the post-commit re-arrival fills it.
    // The case stays so that tightening the keep branch
    // later is a deliberate, visible change.
    check(
        "keep branch does not check load-started (function level)",
        HistoryTitle.atCommit(
            arrivedSinceCommit = true,
            loadStartedSinceCommit = true,
            currentTitle = "Page A",
            titleUrl = a,
            committedUrl = a,
            newestHistoryUrl = a,
        ) == "Page A"
    )

    // First-ever entry: no newest entry to pair with, no
    // arrival -- untitled.
    check(
        "first entry records an empty title",
        HistoryTitle.atCommit(
            arrivedSinceCommit = false,
            loadStartedSinceCommit = true,
            currentTitle = "",
            titleUrl = null,
            committedUrl = a,
            newestHistoryUrl = null,
        ) == ""
    )

    // A null attribution never adopts through the keep
    // branch, even on a same-URL revisit.
    check(
        "null attribution never adopts",
        HistoryTitle.atCommit(
            arrivedSinceCommit = false,
            loadStartedSinceCommit = true,
            currentTitle = "Carried title",
            titleUrl = null,
            committedUrl = a,
            newestHistoryUrl = a,
        ) == ""
    )

    if (failures > 0) {
        println("HistoryTitleTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("HistoryTitleTest: all $checks checks passed")
}
