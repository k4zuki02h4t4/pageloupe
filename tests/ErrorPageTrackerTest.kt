package tests

import jp.kaleidpixel.pageloupe.ErrorPageTracker

// ErrorPageTracker (v0.43.0, review r1-r11 + E2E fix): a
// failed load's commit is marked as an error page. The
// rules pinned here: records of different provenances do
// not displace or block each other (the SSL set's bounded
// overflow clear aside), a record lives until the next
// load start or commit (a finish never retires one —
// an error page's finish precedes its commit on device),
// the main-frame filter is enforced by the tracker
// itself, and the SSL set's bounds can never evict the
// main frame's authoritative record.
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

    val a = "https://a.example/"
    val b = "https://b.example/"
    val s = "https://cdn.example/img.png"

    // A main-frame failure marks its own commit, and the
    // commit consumes it: the same URL committed again (a
    // later, successful load) is not marked.
    run {
        val t = ErrorPageTracker()
        t.recordError(a, isForMainFrame = true)
        check("main-frame failure marks its commit", t.consumeAtCommit(a))
        check("consumed record does not mark again", !t.consumeAtCommit(a))
    }

    // An SSL failure marks the commit of the URL that
    // failed validation.
    run {
        val t = ErrorPageTracker()
        t.recordSslError(a)
        check("ssl failure marks its commit", t.consumeAtCommit(a))
    }

    // A subresource's SSL record does not mark the page's
    // commit (different URL), and that commit consumes it.
    run {
        val t = ErrorPageTracker()
        t.recordSslError(s)
        check("subresource record does not mark the page", !t.consumeAtCommit(a))
        check("consumed subresource record is gone", !t.consumeAtCommit(s))
    }

    // r3 window: a subresource SSL error arriving AFTER the
    // main frame's failure must not displace its record.
    run {
        val t = ErrorPageTracker()
        t.recordError(a, isForMainFrame = true)
        t.recordSslError(s)
        check("later subresource record keeps the main frame's", t.consumeAtCommit(a))
    }

    // r4 mirror window: a subresource SSL record standing
    // BEFORE the main frame's SSL failure must not block
    // it (no first-wins).
    run {
        val t = ErrorPageTracker()
        t.recordSslError(s)
        t.recordSslError(a)
        check("earlier subresource record does not block", t.consumeAtCommit(a))
    }

    // An SSL record for the outgoing page must not
    // displace the main frame's onReceivedError record
    // either (provenance: the slot is written by
    // onReceivedError only).
    run {
        val t = ErrorPageTracker()
        t.recordSslError(a)
        t.recordError(b, isForMainFrame = true)
        check("main-frame slot is independent of ssl records", t.consumeAtCommit(b))
    }

    // r2 delivery order: the failure is recorded BEFORE
    // its own load's start callback arrives; the start
    // must keep the record.
    run {
        val t = ErrorPageTracker()
        t.recordError(a, isForMainFrame = true)
        check("start for the same url drops nothing", !t.onLoadStarted(a))
        check("record survives its own start", t.consumeAtCommit(a))
    }

    // The same order on the SSL side, with a mixed set
    // (r6, corrected in r7): the start goes through the
    // retainAll branch, drops the OTHER record, reports
    // the drop, and keeps its own URL's record. With a
    // single-element set the branch would not run at
    // all, and a clear() in its place would pass.
    run {
        val t = ErrorPageTracker()
        t.recordSslError(s)
        t.recordSslError(a)
        check("start reports dropping the other ssl record", t.onLoadStarted(a))
        check("ssl record survives its own start", t.consumeAtCommit(a))
        val t2 = ErrorPageTracker()
        t2.recordSslError(s)
        t2.recordSslError(a)
        t2.onLoadStarted(a)
        check("other ssl record is dropped at the start", !t2.consumeAtCommit(s))
    }

    // The main-frame filter is the tracker's own (r6):
    // a subresource failure passed to recordError is not
    // recorded, whatever the caller did.
    run {
        val t = ErrorPageTracker()
        t.recordError(s, isForMainFrame = false)
        check("subresource call is not recorded", !t.consumeAtCommit(s))
    }

    // A start for a different URL retires the earlier
    // load's records (both provenances) and reports it.
    run {
        val t = ErrorPageTracker()
        t.recordError(a, isForMainFrame = true)
        t.recordSslError(s)
        check("start for another url reports the drop", t.onLoadStarted(b))
        check("main-frame record retired at start", !t.consumeAtCommit(a))
        val t2 = ErrorPageTracker()
        t2.recordSslError(s)
        t2.onLoadStarted(b)
        check("ssl record retired at start", !t2.consumeAtCommit(s))
    }

    // A start with no records drops nothing.
    run {
        val t = ErrorPageTracker()
        check("idle start drops nothing", !t.onLoadStarted(a))
    }

    // Post-commit subresource record (r4 security): it
    // stands after its page's commit consumed the set,
    // and the NEXT load's start retires it when the user
    // navigates elsewhere.
    run {
        val t = ErrorPageTracker()
        t.recordSslError(a)
        check("page commit marked by its own failure", t.consumeAtCommit(a))
        t.recordSslError(s)
        check("next start retires the post-commit record", t.onLoadStarted(b))
        check("retired record cannot mark its url later", !t.consumeAtCommit(s))
    }

    // A finish retires nothing (device evidence, WebView
    // 158, v0.43.0 E2E): an error page's finish is
    // delivered BEFORE its history commit, so the tracker
    // has no finish hook — a failure recorded for a load
    // is still there when that load's commit arrives
    // after the finish, for both provenances.
    run {
        val t = ErrorPageTracker()
        t.recordError(a, isForMainFrame = true)
        check("error commit after the finish still marks", t.consumeAtCommit(a))
        val t2 = ErrorPageTracker()
        t2.recordSslError(a)
        check("ssl error commit after the finish still marks", t2.consumeAtCommit(a))
    }

    // Overflow (r5 SEC-1): flooding distinct SSL records
    // past the cap clears the SSL set, but the main
    // frame's authoritative record survives.
    run {
        val t = ErrorPageTracker()
        t.recordError(a, isForMainFrame = true)
        repeat(ErrorPageTracker.MAX_SSL_RECORDS + 8) { i ->
            t.recordSslError("https://sub$i.example/")
        }
        check("overflow never evicts the main-frame record", t.consumeAtCommit(a))
    }

    // Overflow drops the flooded SSL records themselves:
    // the first flooded URL no longer marks a commit
    // (accepted residual (3), fail-open).
    run {
        val t = ErrorPageTracker()
        val first = "https://sub0.example/"
        t.recordSslError(first)
        repeat(ErrorPageTracker.MAX_SSL_RECORDS + 8) { i ->
            t.recordSslError("https://sub${i + 1}.example/")
        }
        check("overflow clears the ssl set", !t.consumeAtCommit(first))
    }

    // Duplicate records do not count toward the cap.
    run {
        val t = ErrorPageTracker()
        repeat(ErrorPageTracker.MAX_SSL_RECORDS + 8) { t.recordSslError(s) }
        check("duplicates do not overflow the set", t.consumeAtCommit(s))
    }

    // Overlong SSL URLs are not recorded (r5 SEC-2), but
    // one exactly at the limit is.
    run {
        val t = ErrorPageTracker()
        val overlong = "https://a.example/" + "x".repeat(ErrorPageTracker.MAX_SSL_URL_LENGTH)
        t.recordSslError(overlong)
        check("overlong ssl url is not recorded", !t.consumeAtCommit(overlong))
        val atLimit = "https://a.example/" +
            "x".repeat(ErrorPageTracker.MAX_SSL_URL_LENGTH - "https://a.example/".length)
        val t2 = ErrorPageTracker()
        t2.recordSslError(atLimit)
        check("ssl url at the limit is recorded", t2.consumeAtCommit(atLimit))
    }

    // Null records are ignored.
    run {
        val t = ErrorPageTracker()
        t.recordError(null, isForMainFrame = true)
        t.recordSslError(null)
        check("null records mark nothing", !t.consumeAtCommit(a))
    }

    println("ErrorPageTrackerTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
