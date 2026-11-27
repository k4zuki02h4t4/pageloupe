package tests

import jp.kaleidpixel.pageloupe.CommitUrl

// JVM tests for CommitUrl: what the tab's current URL becomes
// when a load finishes (SEN-299). A finish is not a commit —
// a 204 or a window.stop() fires onPageFinished with the
// target URL while the previous page is still on screen, and
// the address bar must keep naming the committed page.
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
    val page = "https://example.com/old"

    // The normal case: the finished URL is the committed one.
    check(
        "a finish matching the commit is adopted",
        CommitUrl.onPageFinished("https://example.com/new", "https://example.com/new", false) ==
            "https://example.com/new"
    )
    // 204 / window.stop(): the load finished at a URL that
    // never committed — the committed page stands.
    check(
        "an uncommitted https finish keeps the committed url",
        CommitUrl.onPageFinished("https://example.com/never", page, false) == page
    )
    check(
        "an uncommitted http finish keeps the committed url",
        CommitUrl.onPageFinished("http://127.0.0.1:8077/slow204", "http://127.0.0.1:8077/stopnav", false) ==
            "http://127.0.0.1:8077/stopnav"
    )
    // A cross-host uncommitted finish (a redirect chain the
    // page aborted) must not move the bar to the other host.
    check(
        "an uncommitted cross-host finish keeps the committed url",
        CommitUrl.onPageFinished("https://other.example/x", page, false) == page
    )
    // Nothing committed yet: there is no previous page to
    // protect, so the finished URL is adopted as-is.
    check(
        "a first load adopts the finished url",
        CommitUrl.onPageFinished("https://example.com/first", "", false) ==
            "https://example.com/first"
    )
    // Non-web finishes keep the historical behavior (the start
    // page's data: load has its own commit bookkeeping).
    check(
        "a data finish is adopted over a committed page",
        CommitUrl.onPageFinished("data:text/html;base64,AAAA", page, false) ==
            "data:text/html;base64,AAAA"
    )
    check(
        "an about finish is adopted over a committed page",
        CommitUrl.onPageFinished("about:blank", page, false) == "about:blank"
    )
    // The non-web adoption is decided BEFORE startedFromHome
    // is consulted (SEN-368): the start page's own data: load
    // can finish inside a home-started sequence (home -> home),
    // and its finish must be adopted, never rewritten to the
    // home marker it already is.
    check(
        "a data finish from home is adopted as-is",
        CommitUrl.onPageFinished("data:text/html;base64,AAAA", page, true) ==
            "data:text/html;base64,AAAA"
    )
    check(
        "an about finish from home is adopted as-is",
        CommitUrl.onPageFinished("about:blank", page, true) == "about:blank"
    )

    // A load away from home that never commits leaves the
    // start page on screen (SEC-167): lastCommittedUrl still
    // names the page visited BEFORE home, and reverting to it
    // would put that site's name over the start page.
    check(
        "an uncommitted finish from home keeps the home url",
        CommitUrl.onPageFinished("https://example.com/never", page, true) ==
            "pageloupe://newtab/"
    )
    // A load from home that DOES commit reaches this finish
    // with startedFromHome already cleared by the commit
    // (LoadStartLatch.onCommit), so it is the plain matching
    // case above ("a finish matching the commit is adopted",
    // startedFromHome=false). What equality with
    // startedFromHome=true actually means is SEN-365: the
    // sequence out of home targeted the very page visited
    // before home and never committed — the start page is
    // still on screen, and the home marker stands.
    check(
        "an uncommitted finish from home at the pre-home url keeps the home url",
        CommitUrl.onPageFinished(page, page, true) == "pageloupe://newtab/"
    )

    println("CommitUrlTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
