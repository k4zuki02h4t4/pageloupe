package tests

import jp.kaleidpixel.pageloupe.DownloadInitiatorTracker
import jp.kaleidpixel.pageloupe.PageFeaturePolicy

// JVM tests for the download initiator record (SEN-285 /
// SEN-331 / SEN-334): which frame a download's navigation was
// for, decided from the last recorded attempt. The rules that
// matter are the failure directions: a subframe record is
// sticky for its URL, a matching classification consumes the
// record, a new main-frame document clears it, and anything
// unconfirmed is UNKNOWN (the cookie gate fails closed on
// it). Run via tests/run_tests.sh.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

private val main = PageFeaturePolicy.DownloadFrame.MAIN_FRAME
private val sub = PageFeaturePolicy.DownloadFrame.SUBFRAME
private val unknown = PageFeaturePolicy.DownloadFrame.UNKNOWN

fun main() {
    // No record at all: unconfirmed.
    run {
        val t = DownloadInitiatorTracker()
        check("empty tracker classifies unknown",
            t.classify("https://example.com/f.zip") == unknown)
    }

    // A matching main-frame attempt classifies as main frame.
    run {
        val t = DownloadInitiatorTracker()
        t.record("https://example.com/f.zip", true)
        check("main-frame attempt classifies main",
            t.classify("https://example.com/f.zip") == main)
    }

    // A matching subframe attempt classifies as subframe.
    run {
        val t = DownloadInitiatorTracker()
        t.record("https://example.com/f.zip", false)
        check("subframe attempt classifies subframe",
            t.classify("https://example.com/f.zip") == sub)
    }

    // A record for another URL does not classify this download,
    // and is not consumed by the mismatch.
    run {
        val t = DownloadInitiatorTracker()
        t.record("https://example.com/other", true)
        check("record for another URL is unknown",
            t.classify("https://example.com/f.zip") == unknown)
        check("mismatch does not consume the record",
            t.classify("https://example.com/other") == main)
    }

    // A matching classification consumes the record: the same
    // URL downloaded again (a second response, a retry) is
    // unconfirmed rather than re-spending the old attempt.
    run {
        val t = DownloadInitiatorTracker()
        t.record("https://example.com/f.zip", true)
        check("first classification spends the record",
            t.classify("https://example.com/f.zip") == main)
        check("second classification is unknown",
            t.classify("https://example.com/f.zip") == unknown)
    }

    // Stickiness: a subframe record for a URL is not upgraded
    // by a later main-frame attempt for the same URL — the two
    // in-flight responses cannot be told apart, so the error
    // falls on the safe side.
    run {
        val t = DownloadInitiatorTracker()
        t.record("https://example.com/f.zip", false)
        t.record("https://example.com/f.zip", true)
        check("subframe record is sticky against a main-frame attempt",
            t.classify("https://example.com/f.zip") == sub)
    }

    // The reverse is not sticky: a later subframe attempt
    // replaces a main-frame record for the same URL.
    run {
        val t = DownloadInitiatorTracker()
        t.record("https://example.com/f.zip", true)
        t.record("https://example.com/f.zip", false)
        check("subframe attempt replaces a main-frame record",
            t.classify("https://example.com/f.zip") == sub)
    }

    // Stickiness is per URL: a main-frame attempt for a
    // DIFFERENT URL replaces the subframe record outright.
    run {
        val t = DownloadInitiatorTracker()
        t.record("https://example.com/a.zip", false)
        t.record("https://example.com/b.zip", true)
        check("a different URL replaces the record",
            t.classify("https://example.com/b.zip") == main)
        check("the replaced subframe URL is unknown",
            t.classify("https://example.com/a.zip") == unknown)
    }

    // A new document clears the record (BrowserTab calls
    // clear from onPageStarted).
    run {
        val t = DownloadInitiatorTracker()
        t.record("https://example.com/f.zip", true)
        t.clear()
        check("cleared record classifies unknown",
            t.classify("https://example.com/f.zip") == unknown)
    }

    // Redirect chains: each hop overwrites, so the final hop's
    // frame is what classifies (the frame is constant across a
    // chain in practice; the rule is pinned anyway).
    run {
        val t = DownloadInitiatorTracker()
        t.record("https://example.com/start", true)
        t.record("https://example.com/f.zip", true)
        check("the last hop classifies",
            t.classify("https://example.com/f.zip") == main)
    }

    println("DownloadInitiatorTrackerTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
