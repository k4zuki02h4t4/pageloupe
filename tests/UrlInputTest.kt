package tests

import jp.kaleidpixel.pageloupe.DedupeCandidate
import jp.kaleidpixel.pageloupe.ExternalLinkAction
import jp.kaleidpixel.pageloupe.UrlInput
import jp.kaleidpixel.pageloupe.classifyUrlInput
import jp.kaleidpixel.pageloupe.externalLinkAction
import jp.kaleidpixel.pageloupe.findDedupeTabIndex
import jp.kaleidpixel.pageloupe.googleSearchUrl
import jp.kaleidpixel.pageloupe.isDedupeSafeUrl
import jp.kaleidpixel.pageloupe.isHomeDisplayed
import jp.kaleidpixel.pageloupe.isHomeUrl
import jp.kaleidpixel.pageloupe.isWebLink
import jp.kaleidpixel.pageloupe.launchLinkToOpen
import jp.kaleidpixel.pageloupe.NAV_STATE_CAPTURE_BUDGET
import jp.kaleidpixel.pageloupe.NavStateCapture
import jp.kaleidpixel.pageloupe.NavStateShape
import jp.kaleidpixel.pageloupe.decideNavStateCapture
import jp.kaleidpixel.pageloupe.launchDataForStart
import jp.kaleidpixel.pageloupe.shouldRestoreNavState
import jp.kaleidpixel.pageloupe.isInternalDocumentUrl
import jp.kaleidpixel.pageloupe.normalizeUrlForCompare
import jp.kaleidpixel.pageloupe.startPageDataUrl
import jp.kaleidpixel.pageloupe.urlDigest

/**
 * JVM unit tests for the address-bar input classification
 * (UrlInput.kt). Covers the stage-5 fixes: SEC-6 is in BrowserTab
 * (not JVM-testable), SEN-18 is here -- localhost and private IPs
 * must become URLs with http://, not searches or https://.
 */

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    checks++
    if (cond) {
        println("ok   $name")
    } else {
        failures++
        println("FAIL $name $detail")
    }
}

private fun expect(name: String, input: String, expected: UrlInput) {
    val actual = classifyUrlInput(input)
    check(name, actual == expected, "input='$input' expected=$expected actual=$actual")
}

fun main() {
    // Blank input does nothing.
    expect("empty", "", UrlInput.Empty)
    expect("blank spaces", "   ", UrlInput.Empty)

    // The internal scheme goes home.
    expect("home url", "pageloupe://newtab/", UrlInput.Home)
    expect("home scheme", "pageloupe:settings", UrlInput.Home)

    // Fully qualified URLs load as-is (case preserved).
    expect("https as-is", "https://example.com/x?y=1", UrlInput.Load("https://example.com/x?y=1"))
    expect("http as-is", "http://a.b/c", UrlInput.Load("http://a.b/c"))
    expect("uppercase scheme", "HTTP://EXAMPLE.COM", UrlInput.Load("HTTP://EXAMPLE.COM"))
    expect("about blank", "about:blank", UrlInput.Load("about:blank"))

    // Plain words and phrases search.
    expect("single word", "hello", UrlInput.Search("hello"))
    expect("phrase", "hello world", UrlInput.Search("hello world"))
    expect("word with port-like tail", "word:1234", UrlInput.Search("word:1234"))
    expect("invalid ipv4 tail", "1.2.3.999", UrlInput.Search("1.2.3.999"))

    // Dotted hosts default to https.
    expect("bare domain", "example.com", UrlInput.Load("https://example.com"))
    expect("domain with path", "example.com/a/b?q=1", UrlInput.Load("https://example.com/a/b?q=1"))
    expect("domain with port", "example.com:8080", UrlInput.Load("https://example.com:8080"))
    expect("subdomain", "dev.example.co.jp", UrlInput.Load("https://dev.example.co.jp"))

    // Local authorities get http (SEN-18).
    expect("localhost", "localhost", UrlInput.Load("http://localhost"))
    expect("localhost port", "localhost:3000", UrlInput.Load("http://localhost:3000"))
    expect("localhost path", "localhost:3000/app", UrlInput.Load("http://localhost:3000/app"))
    expect("loopback ip", "127.0.0.1:8080", UrlInput.Load("http://127.0.0.1:8080"))
    expect("private 192", "192.168.1.10:8080", UrlInput.Load("http://192.168.1.10:8080"))
    expect("private 192 no port", "192.168.1.10", UrlInput.Load("http://192.168.1.10"))
    expect("private 10", "10.0.0.5", UrlInput.Load("http://10.0.0.5"))
    expect("private 172.16", "172.16.0.9", UrlInput.Load("http://172.16.0.9"))
    expect("private 172.31", "172.31.255.1", UrlInput.Load("http://172.31.255.1"))
    expect("mdns local", "printer.local", UrlInput.Load("http://printer.local"))

    // Public IPs keep the https default.
    expect("public ip", "8.8.8.8", UrlInput.Load("https://8.8.8.8"))
    expect("172.32 is public", "172.32.0.9", UrlInput.Load("https://172.32.0.9"))

    // Search URL encoding.
    check(
        "search url encoding",
        googleSearchUrl("a b&c") == "https://www.google.com/search?q=a+b%26c",
        googleSearchUrl("a b&c")
    )

    // Dangerous or ambiguous inputs never load as-is (pinned in
    // the stage-5 diff review): non-web schemes and userinfo go to
    // search, where they are inert text.
    expect("javascript scheme", "javascript:alert(1)", UrlInput.Search("javascript:alert(1)"))
    expect("data scheme", "data:text/html,hi", UrlInput.Search("data:text/html,hi"))
    expect("file scheme", "file:///sdcard/x", UrlInput.Search("file:///sdcard/x"))
    expect("intent scheme", "intent://x", UrlInput.Search("intent://x"))
    expect("userinfo", "user:pass@example.com", UrlInput.Search("user:pass@example.com"))
    expect(
        "userinfo spoof", "localhost:3000@evil.com",
        UrlInput.Search("localhost:3000@evil.com")
    )
    expect("ipv6 literal", "[::1]:8080", UrlInput.Search("[::1]:8080"))
    expect("single-label host with port", "myhost:3000", UrlInput.Search("myhost:3000"))
    expect("trailing dot", "example.com.", UrlInput.Search("example.com."))
    expect("underscore host", "foo_bar.example.com", UrlInput.Search("foo_bar.example.com"))
    expect("short ipv4", "127.1", UrlInput.Search("127.1"))
    // Leading-zero octets are octal in URL parsers: never classify.
    expect("leading-zero ipv4", "010.0.0.1", UrlInput.Search("010.0.0.1"))
    expect("uppercase localhost", "LOCALHOST", UrlInput.Load("http://LOCALHOST"))
    expect("this-network ip", "0.0.0.0:8080", UrlInput.Load("http://0.0.0.0:8080"))
    // Unicode digits are not ASCII digits: a full-width
    // leading-zero IPv4 must not slip past the octal guard, and a
    // full-width port is not a port (stage-5 recheck).
    expect("full-width ipv4", "０１０.０.０.１", UrlInput.Search("０１０.０.０.１"))
    expect("full-width port", "localhost:３０００", UrlInput.Search("localhost:３０００"))

    // isHomeUrl: scheme only (SEC-6). The look-alike host is a
    // normal page now.
    check("home blank", isHomeUrl(""))
    check("home scheme", isHomeUrl("pageloupe://newtab/"))
    check("look-alike host is not home", !isHomeUrl("https://pageloupe.home/"))
    check("real page is not home", !isHomeUrl("https://example.com/"))

    // normalizeUrlForCompare (SEC-28 dedupe key).
    check(
        "normalize empty path",
        normalizeUrlForCompare("https://example.com") == "https://example.com/"
    )
    check(
        "normalize case and default port",
        normalizeUrlForCompare("HTTPS://EXAMPLE.COM:443/a") == "https://example.com/a"
    )
    check(
        "normalize http default port",
        normalizeUrlForCompare("http://example.com:80") == "http://example.com/"
    )
    check(
        "normalize keeps non-default port",
        normalizeUrlForCompare("https://example.com:8443/") == "https://example.com:8443/"
    )
    check(
        "normalize drops fragment and userinfo",
        normalizeUrlForCompare("https://user:pw@example.com/a#frag") == "https://example.com/a"
    )
    check(
        "normalize query with empty path",
        normalizeUrlForCompare("https://example.com?q=1") == "https://example.com/?q=1"
    )
    check(
        "normalize keeps path case",
        normalizeUrlForCompare("https://example.com/Case/Path") == "https://example.com/Case/Path"
    )
    check(
        "normalize empty port",
        normalizeUrlForCompare("https://example.com:/a") == "https://example.com/a"
    )
    check(
        "normalize ipv6 literal",
        normalizeUrlForCompare("http://[::1]:8080/x") == "http://[::1]:8080/x"
    )

    // urlDigest: fixed length, stable, input-sensitive.
    check("digest length", urlDigest("https://example.com/").length == 64)
    check(
        "digest stable",
        urlDigest("https://example.com/a") == urlDigest("https://example.com/a")
    )
    check(
        "digest differs",
        urlDigest("https://example.com/a") != urlDigest("https://example.com/b")
    )
    check(
        "digest bounded for long input",
        urlDigest("https://example.com/" + "x".repeat(100_000)).length == 64
    )

    // Dedupe safety and matching (SEC-28 / SEC-30).
    val maxLen = 8192
    check("dedupe safe plain", isDedupeSafeUrl("https://example.com/", maxLen))
    check(
        "dedupe unsafe userinfo",
        !isDedupeSafeUrl("https://user@example.com/", maxLen)
    )
    check(
        "dedupe unsafe backslash",
        !isDedupeSafeUrl("https://evil.com\\@example.com/", maxLen)
    )
    check(
        "dedupe safe at-sign in path",
        isDedupeSafeUrl("https://example.com/@user", maxLen)
    )
    check(
        "dedupe unsafe too long",
        !isDedupeSafeUrl("https://example.com/" + "x".repeat(maxLen), maxLen)
    )
    val tabs = listOf(
        DedupeCandidate("https://a.example/", null, secret = false),
        DedupeCandidate("https://b.example/", null, secret = true),
        DedupeCandidate("", "https://c.example/page", secret = false)
    )
    check(
        "dedupe finds current",
        findDedupeTabIndex("https://a.example", tabs, maxLen) == 0
    )
    check(
        "dedupe skips secret",
        findDedupeTabIndex("https://b.example/", tabs, maxLen) == -1
    )
    check(
        "dedupe finds deferred",
        findDedupeTabIndex("https://c.example/page", tabs, maxLen) == 2
    )
    check(
        "dedupe misses unknown",
        findDedupeTabIndex("https://z.example/", tabs, maxLen) == -1
    )
    check(
        "dedupe unsafe input never matches",
        findDedupeTabIndex("https://user@a.example/", tabs, maxLen) == -1
    )
    check(
        "dedupe unsafe candidate never matches",
        findDedupeTabIndex(
            "https://example.com/",
            listOf(DedupeCandidate("https://evil.com\\@example.com/", null, secret = false)),
            maxLen
        ) == -1
    )
    check(
        "dedupe unsafe deferred never matches",
        findDedupeTabIndex(
            "https://example.com/",
            listOf(DedupeCandidate("", "https://user@example.com/", secret = false)),
            maxLen
        ) == -1
    )
    check(
        "dedupe skips unsafe first, matches second",
        findDedupeTabIndex(
            "https://example.com/",
            listOf(
                DedupeCandidate("https://evil.com\\@example.com/", null, secret = false),
                DedupeCandidate("https://example.com/", null, secret = false)
            ),
            maxLen
        ) == 1
    )
    // Boundary: a URL of exactly maxLength still participates;
    // one char longer does not.
    val exactUrl = "https://example.com/" + "x".repeat(maxLen - "https://example.com/".length)
    check(
        "dedupe boundary exact length",
        findDedupeTabIndex(
            exactUrl,
            listOf(DedupeCandidate(exactUrl, null, secret = false)),
            maxLen
        ) == 0
    )
    check(
        "dedupe boundary over length",
        findDedupeTabIndex(
            exactUrl + "y",
            listOf(DedupeCandidate(exactUrl + "y", null, secret = false)),
            maxLen
        ) == -1
    )

    // externalLinkAction: reuse beats the cap, the cap refuses
    // only creation, boundaries at cap-1 / cap.
    val capCands = listOf(DedupeCandidate("https://example.com/", null, secret = false))
    check(
        "action switch under cap",
        externalLinkAction("https://example.com/", capCands, maxLen, 3, 100) ==
            (ExternalLinkAction.SWITCH to 0)
    )
    check(
        "action switch at cap",
        externalLinkAction("https://example.com/", capCands, maxLen, 100, 100) ==
            (ExternalLinkAction.SWITCH to 0)
    )
    check(
        "action create at cap minus one",
        externalLinkAction("https://other.example/", capCands, maxLen, 99, 100) ==
            (ExternalLinkAction.CREATE to -1)
    )
    check(
        "action refuse at cap",
        externalLinkAction("https://other.example/", capCands, maxLen, 100, 100) ==
            (ExternalLinkAction.REFUSE to -1)
    )
    check(
        "action refuse above cap",
        externalLinkAction("https://other.example/", capCands, maxLen, 150, 100) ==
            (ExternalLinkAction.REFUSE to -1)
    )

    // isWebLink / launchLinkToOpen (restore-branch decision).
    check("weblink https", isWebLink("https://example.com"))
    check("weblink http", isWebLink("http://example.com"))
    check("weblink null", !isWebLink(null))
    check("weblink blank", !isWebLink("  "))
    check("weblink other scheme", !isWebLink("ftp://example.com"))
    check(
        "launch link fresh opens",
        launchLinkToOpen("https://a.example/", null) == "https://a.example/"
    )
    check(
        "launch link consumed stays closed",
        launchLinkToOpen("https://a.example/", urlDigest("https://a.example/")) == null
    )
    check(
        "launch link different from stored opens",
        launchLinkToOpen("https://b.example/", urlDigest("https://a.example/")) ==
            "https://b.example/"
    )
    check("launch link none", launchLinkToOpen(null, null) == null)
    check("launch link non-web", launchLinkToOpen("pageloupe://newtab/", null) == null)

    // launchDataForStart (Recents relaunch decision).
    check(
        "recents relaunch drops the launch link",
        launchDataForStart("https://a.example/", true) == null
    )
    check(
        "ordinary start keeps the launch link",
        launchDataForStart("https://a.example/", false) == "https://a.example/"
    )
    check(
        "recents relaunch without a link stays empty",
        launchDataForStart(null, true) == null
    )

    // shouldRestoreNavState (crash-recovery history decision).
    check(
        "nav state restores for the snapshot's own page",
        shouldRestoreNavState("https://a.example/", "https://a.example/")
    )
    check(
        "nav state restores for an http page too",
        shouldRestoreNavState("http://a.example/", "http://a.example/")
    )
    check(
        "nav state refused when the snapshot is another page",
        !shouldRestoreNavState("https://a.example/", "https://b.example/")
    )
    check(
        "nav state refused without a snapshot",
        !shouldRestoreNavState("https://a.example/", null)
    )
    check(
        "nav state refused for the start page",
        !shouldRestoreNavState("pageloupe://newtab/", "pageloupe://newtab/")
    )
    check(
        "nav state refused for a non-web scheme",
        !shouldRestoreNavState("data:text/html,hi", "data:text/html,hi")
    )
    check(
        "nav state refused for a blank target",
        !shouldRestoreNavState("", "")
    )

    // decideNavStateCapture (per-commit snapshot decision).
    check(
        "capture discards at a start page commit",
        decideNavStateCapture(
            "pageloupe://newtab/", true, 0,
            NavStateShape(2, 1, "https://a.example/"),
            "https://a.example/", NavStateShape(1, 0, "https://a.example/")
        ) == NavStateCapture.DiscardSnapshot
    )
    check(
        "capture discards at a blank commit",
        decideNavStateCapture(
            "", true, 0,
            NavStateShape(1, 0, null),
            null, null
        ) == NavStateCapture.DiscardSnapshot
    )
    check(
        "capture takes when there is no snapshot",
        decideNavStateCapture(
            "https://a.example/", true, 0,
            NavStateShape(1, 0, "https://a.example/"),
            null, null
        ) == NavStateCapture.TakeSnapshot
    )
    check(
        "capture takes for an adopted snapshot without a shape",
        decideNavStateCapture(
            "https://a.example/", false, 0,
            NavStateShape(1, 0, "https://a.example/"),
            "https://a.example/", null
        ) == NavStateCapture.TakeSnapshot
    )
    check(
        "capture skips an unchanged shape",
        decideNavStateCapture(
            "https://b.example/", false, 3,
            NavStateShape(2, 1, "https://a.example/"),
            "https://b.example/", NavStateShape(2, 1, "https://a.example/")
        ) == NavStateCapture.SkipUnchanged
    )
    check(
        "capture takes when only the index moved",
        decideNavStateCapture(
            "https://a.example/", false, 3,
            NavStateShape(2, 0, "https://a.example/"),
            "https://a.example/", NavStateShape(2, 1, "https://a.example/")
        ) == NavStateCapture.TakeSnapshot
    )
    check(
        "capture takes when the list grew",
        decideNavStateCapture(
            "https://c.example/", false, 3,
            NavStateShape(3, 2, "https://a.example/"),
            "https://b.example/", NavStateShape(2, 1, "https://a.example/")
        ) == NavStateCapture.TakeSnapshot
    )
    check(
        "capture takes when the head entry moved at the cap",
        decideNavStateCapture(
            "https://b.example/", false, 3,
            NavStateShape(2, 1, "https://x.example/"),
            "https://b.example/", NavStateShape(2, 1, "https://a.example/")
        ) == NavStateCapture.TakeSnapshot
    )
    check(
        "capture discards when the per-document budget is spent",
        decideNavStateCapture(
            "https://b.example/?n=99", false, NAV_STATE_CAPTURE_BUDGET,
            NavStateShape(2, 1, "https://a.example/"),
            "https://b.example/", NavStateShape(2, 1, "https://a.example/")
        ) == NavStateCapture.DiscardSnapshot
    )
    check(
        "capture budget resets for a new document",
        decideNavStateCapture(
            "https://c.example/", true, NAV_STATE_CAPTURE_BUDGET,
            NavStateShape(3, 2, "https://a.example/"),
            "https://b.example/", NavStateShape(2, 1, "https://a.example/")
        ) == NavStateCapture.TakeSnapshot
    )
    check(
        "capture takes under the budget",
        decideNavStateCapture(
            "https://b.example/?n=1", false, NAV_STATE_CAPTURE_BUDGET - 1,
            NavStateShape(2, 1, "https://a.example/"),
            "https://b.example/", NavStateShape(2, 1, "https://a.example/")
        ) == NavStateCapture.TakeSnapshot
    )

    // normalize strips only the WHATWG whitespace range: a
    // trailing U+3000 is part of the path, not padding (SEC-32).
    check(
        "normalize keeps unicode space in path",
        normalizeUrlForCompare("https://example.com/　") !=
            normalizeUrlForCompare("https://example.com/")
    )
    check(
        "normalize strips ascii padding",
        normalizeUrlForCompare("  https://example.com/ ") == "https://example.com/"
    )

    // startPageDataUrl: the exact form the start page's load
    // reports (SEN-152). Standard-alphabet base64, padded, no
    // line breaks, over the HTML's UTF-8 bytes.
    check(
        "start data url prefix and payload",
        startPageDataUrl("<p>x</p>") ==
            "data:text/html;charset=utf-8;base64,PHA+eDwvcD4="
    )
    check(
        "start data url encodes utf-8",
        startPageDataUrl("あ") == "data:text/html;charset=utf-8;base64,44GC"
    )
    check(
        "start data url empty html",
        startPageDataUrl("") == "data:text/html;charset=utf-8;base64,"
    )
    check(
        "start data url has no line breaks",
        !startPageDataUrl("<p>".repeat(40)).contains("\n")
    )

    // isInternalDocumentUrl (SEN-152): only the app's own
    // internal loads are excluded from the recording. The
    // internal list is exact matches of content-less URLs
    // only — anything carrying content is a real page's.
    val supplied = "data:text/html;charset=utf-8;base64,PGI+"
    check("internal: home url", isInternalDocumentUrl("pageloupe://newtab/", null))
    check("internal: blank", isInternalDocumentUrl("", null))
    check("internal: about blank", isInternalDocumentUrl("about:blank", null))
    check(
        "internal: bare data prefix",
        isInternalDocumentUrl("data:text/html;charset=utf-8;base64,", null)
    )
    check("internal: exact supplied data url", isInternalDocumentUrl(supplied, supplied))
    check(
        "real: other payload data url with supplier",
        !isInternalDocumentUrl("data:text/html;charset=utf-8;base64,QUJD", supplied)
    )
    check(
        "real: payload data url without supplier",
        !isInternalDocumentUrl(supplied, null)
    )
    check(
        "real: different data url without supplier",
        !isInternalDocumentUrl("data:text/html,<p>x</p>", null)
    )
    check("real: about blank fragment", !isInternalDocumentUrl("about:blank#x", null))
    check("real: about srcdoc", !isInternalDocumentUrl("about:srcdoc", null))
    check("real: https page", !isInternalDocumentUrl("https://example.com/", supplied))
    check(
        "real: bare prefix with payload appended",
        !isInternalDocumentUrl("data:text/html;charset=utf-8;base64,QQ==", null)
    )

    // isHomeDisplayed: the phase table of the start page's
    // life (v0.41.0 review). Inputs are (homeCommitted,
    // loadSequenceFromHome, currentIsHome).
    check(
        "home displayed: committed and shown",
        isHomeDisplayed(homeCommitted = true, loadSequenceFromHome = false, currentIsHome = true)
    )
    check(
        "home displayed: outbound load still in flight",
        isHomeDisplayed(homeCommitted = false, loadSequenceFromHome = true, currentIsHome = false)
    )
    check(
        "home displayed: load ended without committing",
        isHomeDisplayed(homeCommitted = false, loadSequenceFromHome = false, currentIsHome = true)
    )
    check(
        "home displayed: load towards home just started (safe side)",
        isHomeDisplayed(homeCommitted = false, loadSequenceFromHome = false, currentIsHome = true)
    )
    check(
        "home not displayed: normal page shown",
        !isHomeDisplayed(homeCommitted = false, loadSequenceFromHome = false, currentIsHome = false)
    )
    check(
        "home not displayed: outbound load from a normal page",
        !isHomeDisplayed(homeCommitted = false, loadSequenceFromHome = false, currentIsHome = false)
    )

    println("---")
    println("$checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
