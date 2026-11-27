package tests

import jp.kaleidpixel.pageloupe.startPageHtml

// JVM tests for the start page's HTML (startPageHtml). The
// load-bearing check is the no-subresource invariant: the
// DevTools recorder excludes only the start page's own
// Document load and records everything else on the tab's
// stream, on the premise that the start page emits nothing
// else. If the page ever gains a script, image, stylesheet,
// frame or CSS load, that traffic would silently enter the
// recordings — this test fails first. Run via
// tests/run_tests.sh.

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
    val html = startPageHtml(
        ink = "#111111",
        sub = "#222222",
        brand = "#333333",
        line = "#444444",
        bg = "#555555",
        nonce = "NONCE-123",
    )
    val lower = html.lowercase()

    // The invariant, allowlist side: every tag in the page is
    // one of the known inert elements. Anything else — a
    // script, an image, a form, a base — fails here no matter
    // how it is spelled. (A refresh meta uses an allowed tag,
    // so that path is closed on the denylist side below, via
    // http-equiv.)
    val allowedTags = setOf(
        "html", "head", "meta", "title", "style", "body",
        "h1", "p", "a", "span",
    )
    val tagNames = Regex("<\\s*/?\\s*([a-zA-Z][a-zA-Z0-9]*)")
        .findAll(html).map { it.groupValues[1].lowercase() }.toList()
    check("page has tags", tagNames.isNotEmpty())
    for (tag in tagNames) {
        check("tag allowed: $tag", tag in allowedTags)
    }
    // Links live only on the card anchors: one href per <a>.
    val anchorCount = Regex("<a[\\s>]").findAll(html).count()
    check("href only on anchors", Regex("href=").findAll(html).count() == anchorCount)

    // The invariant, denylist side: no attribute or syntax
    // that makes even an allowed element emit traffic or
    // navigate on its own.
    for (token in listOf(
        "<script", "<img", "<link", "<iframe", "<frame",
        "<embed", "<object", "<video", "<audio", "<source",
        "<track", "<form", "<base", "<input", "<button",
        "src=", "srcset=", "url(", "@import",
        "background-image", "fetch(", "xmlhttprequest",
        "ping=", "http-equiv", "background=", "poster=",
        "style=", "formaction=", "@font-face", "image-set",
        "src:",
    )) {
        check("no subresource syntax: $token", !lower.contains(token))
    }

    // Structure: the four cards, the palette colours and the
    // nonce all land in the page.
    for (url in listOf(
        "https://www.google.com",
        "https://github.com",
        "https://developer.mozilla.org",
        "https://example.com",
    )) {
        check("card link present: $url", html.contains("href=\"$url\""))
    }
    check("nonce embedded", html.contains("<!-- pageloupe-home NONCE-123 -->"))
    check("ink colour applied", html.contains("color:#111111"))
    check("background applied", html.contains("background:#555555"))
    check("brand colour applied", html.contains("color:#333333"))

    println("StartPageHtml: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
