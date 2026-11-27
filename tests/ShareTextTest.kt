package tests

import jp.kaleidpixel.pageloupe.ShareText

// JVM tests for the footer share button's text rules: only
// http(s) pages (within the length cap) are shareable, and the
// shared text is the flattened, capped title on one line above
// the URL (owner decision, 2026-11-23), falling back to the URL
// alone when there is no usable title -- including when the
// tab's title does not belong to the URL being shared (a load
// in flight still shows the previous page's title). The Intent
// wiring is covered on device.
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

private fun text(title: String, url: String, belongs: Boolean = true) =
    ShareText.payload(title, url, belongs)?.text

fun main() {
    // Shareable: real web pages only, within the URL cap.
    check("https shareable", ShareText.isShareable("https://example.com/"))
    check("http shareable", ShareText.isShareable("http://example.com/"))
    check("start page not shareable", !ShareText.isShareable("pageloupe://newtab/"))
    check("about:blank not shareable", !ShareText.isShareable("about:blank"))
    check("data not shareable", !ShareText.isShareable("data:text/html,hi"))
    check("blank not shareable", !ShareText.isShareable(""))
    check("ftp not shareable", !ShareText.isShareable("ftp://example.com/f"))
    check(
        "url at cap shareable",
        ShareText.isShareable("https://example.com/" + "a".repeat(ShareText.MAX_URL_CHARS - 20))
    )
    check(
        "url over cap not shareable",
        !ShareText.isShareable("https://example.com/" + "a".repeat(ShareText.MAX_URL_CHARS))
    )

    // Title + URL, title first.
    check(
        "title and url",
        text("Example Domain", "https://example.com/") ==
            "Example Domain\nhttps://example.com/"
    )
    // No title (or a title not arrived yet): the URL alone.
    check(
        "blank title falls back to url",
        text("", "https://example.com/") == "https://example.com/"
    )
    check(
        "whitespace title falls back to url",
        text("  \n ", "https://example.com/") == "https://example.com/"
    )
    // A title that is the URL itself must not duplicate it.
    check(
        "title equal to url not duplicated",
        text("https://example.com/", "https://example.com/") ==
            "https://example.com/"
    )
    // Page-controlled line breaks and runs of whitespace in
    // the title flatten to single spaces, so the share stays
    // exactly two lines. This includes the Unicode line
    // separators and NEL that an ASCII-only \s never matches,
    // and control characters.
    check(
        "title line breaks flattened",
        text("Hello\n\n  World\t!", "https://example.com/") ==
            "Hello World !\nhttps://example.com/"
    )
    check(
        "unicode line separator flattened",
        text("Hello\u2028World", "https://example.com/") ==
            "Hello World\nhttps://example.com/"
    )
    check(
        "paragraph separator flattened",
        text("Hello\u2029World", "https://example.com/") ==
            "Hello World\nhttps://example.com/"
    )
    check(
        "NEL flattened",
        text("Hello\u0085World", "https://example.com/") ==
            "Hello World\nhttps://example.com/"
    )
    check(
        "control char flattened",
        text("Hello\u0001World", "https://example.com/") ==
            "Hello World\nhttps://example.com/"
    )
    // Format characters (bidi override) are dropped, not
    // turned into spaces, so they cannot reorder the text.
    check(
        "bidi override dropped",
        text("Hello\u202eWorld", "https://example.com/") ==
            "HelloWorld\nhttps://example.com/"
    )
    check(
        "title trimmed",
        text("  Example  ", "https://example.com/") ==
            "Example\nhttps://example.com/"
    )

    // A title that does not belong to the URL (previous page's
    // title during a load) is never paired with it.
    check(
        "stale title not paired",
        text("Previous Page", "https://example.com/", belongs = false) ==
            "https://example.com/"
    )
    check(
        "titleForShare null when not belonging",
        ShareText.titleForShare("Previous Page", "https://example.com/", false) == null
    )

    // titleForShare is the same flattened title the text uses
    // (the chooser's EXTRA_TITLE takes this value, never raw).
    check(
        "titleForShare flattens",
        ShareText.titleForShare("Hello\nWorld", "https://example.com/", true) ==
            "Hello World"
    )
    check(
        "titleForShare null for blank",
        ShareText.titleForShare(" ", "https://example.com/", true) == null
    )
    check(
        "titleForShare null when title is url",
        ShareText.titleForShare("https://example.com/", "https://example.com/", true) == null
    )

    // Long titles are capped, and the cap never splits a
    // surrogate pair.
    val capped = ShareText.titleForShare("a".repeat(500), "https://example.com/", true)
    check("title capped", capped != null && capped.length == ShareText.MAX_TITLE_CHARS)
    val emojiTitle = "a".repeat(ShareText.MAX_TITLE_CHARS - 1) + "😀" + "bbbb"
    val cappedEmoji = ShareText.titleForShare(emojiTitle, "https://example.com/", true)
    check(
        "cap does not split surrogate pair",
        cappedEmoji != null &&
            cappedEmoji.length == ShareText.MAX_TITLE_CHARS - 1 &&
            !cappedEmoji.last().isHighSurrogate()
    )

    // Supplementary-plane format characters (tag characters,
    // U+E0041 here) are dropped too: the flattening pass works
    // in code points, not UTF-16 units.
    check(
        "tag character dropped",
        text("Hello\uDB40\uDC41World", "https://example.com/") ==
            "HelloWorld\nhttps://example.com/"
    )

    // The other invisible carriers are dropped too:
    // variation selectors, Hangul fillers, unpaired
    // surrogates.
    check(
        "variation selector dropped",
        text("Hello\uFE0FWorld", "https://example.com/") ==
            "HelloWorld\nhttps://example.com/"
    )
    check(
        "hangul filler dropped",
        text("Hello\u115FWorld", "https://example.com/") ==
            "HelloWorld\nhttps://example.com/"
    )
    check(
        "unpaired surrogate dropped",
        text("Hello\uD800World", "https://example.com/") ==
            "HelloWorld\nhttps://example.com/"
    )

    check(
        "combining grapheme joiner dropped",
        text("Hello\u034FWorld", "https://example.com/") ==
            "HelloWorld\nhttps://example.com/"
    )
    check(
        "mongolian variation selector dropped",
        text("Hello\u180BWorld", "https://example.com/") ==
            "HelloWorld\nhttps://example.com/"
    )
    check(
        "unassigned tag-range character dropped",
        text("Hello\uDB40\uDC80World", "https://example.com/") ==
            "HelloWorld\nhttps://example.com/"
    )

    check(
        "unassigned default-ignorable dropped",
        text("Hello\u2065World", "https://example.com/") ==
            "HelloWorld\nhttps://example.com/"
    )

    // The cap never leaves a trailing space the cut exposed.
    check(
        "cap trims exposed trailing space",
        ShareText.titleForShare(
            "a".repeat(199) + " " + "b".repeat(50), "https://example.com/", true
        ) == "a".repeat(199)
    )

    // payload computes text and title together, consistently.
    check(
        "payload with title",
        ShareText.payload("Example Domain", "https://example.com/", true) ==
            ShareText.Payload(
                "Example Domain\nhttps://example.com/", "Example Domain"
            )
    )
    check(
        "payload url only",
        ShareText.payload("Example Domain", "https://example.com/", false) ==
            ShareText.Payload("https://example.com/", null)
    )
    check(
        "payload null when not shareable",
        ShareText.payload("Example", "pageloupe://newtab/", true) == null
    )

    // Not shareable pages produce no text at all.
    check("start page no text", text("PageLoupe", "pageloupe://newtab/") == null)
    check("blank url no text", text("Example", "") == null)
    check("data url no text", text("Example", "data:text/html,hi") == null)

    if (failures > 0) {
        println("ShareTextTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("ShareTextTest: all $checks checks passed")
}
