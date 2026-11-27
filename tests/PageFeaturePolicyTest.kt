package tests

import jp.kaleidpixel.pageloupe.PageFeaturePolicy

// JVM tests for PageFeaturePolicy: the pure rules behind
// downloads and page permission requests (SEN-25). Run via
// the runUnitTests Gradle task.

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
    // Grantable resources: camera/microphone only, deduped,
    // request order preserved.
    check(
        "video and audio are grantable",
        PageFeaturePolicy.grantableResources(
            arrayOf(
                PageFeaturePolicy.RESOURCE_VIDEO_CAPTURE,
                PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE
            )
        ) == listOf(
            PageFeaturePolicy.RESOURCE_VIDEO_CAPTURE,
            PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE
        )
    )
    check(
        "unknown resources are dropped",
        PageFeaturePolicy.grantableResources(
            arrayOf("android.webkit.resource.PROTECTED_MEDIA_ID")
        ).isEmpty()
    )
    check(
        "mixed request keeps only capture, in order",
        PageFeaturePolicy.grantableResources(
            arrayOf(
                "android.webkit.resource.PROTECTED_MEDIA_ID",
                PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE,
                PageFeaturePolicy.RESOURCE_VIDEO_CAPTURE
            )
        ) == listOf(
            PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE,
            PageFeaturePolicy.RESOURCE_VIDEO_CAPTURE
        )
    )
    check(
        "duplicates collapse",
        PageFeaturePolicy.grantableResources(
            arrayOf(
                PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE,
                PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE
            )
        ) == listOf(PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE)
    )
    check(
        "empty request grants nothing",
        PageFeaturePolicy.grantableResources(emptyArray()).isEmpty()
    )

    // Runtime permission mapping.
    check(
        "video maps to CAMERA",
        PageFeaturePolicy.androidPermissionsFor(
            listOf(PageFeaturePolicy.RESOURCE_VIDEO_CAPTURE)
        ) == listOf(PageFeaturePolicy.PERMISSION_CAMERA)
    )
    check(
        "audio maps to RECORD_AUDIO",
        PageFeaturePolicy.androidPermissionsFor(
            listOf(PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE)
        ) == listOf(PageFeaturePolicy.PERMISSION_RECORD_AUDIO)
    )
    check(
        "both map in order",
        PageFeaturePolicy.androidPermissionsFor(
            listOf(
                PageFeaturePolicy.RESOURCE_VIDEO_CAPTURE,
                PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE
            )
        ) == listOf(
            PageFeaturePolicy.PERMISSION_CAMERA,
            PageFeaturePolicy.PERMISSION_RECORD_AUDIO
        )
    )
    check(
        "no resources, no permissions",
        PageFeaturePolicy.androidPermissionsFor(emptyList()).isEmpty()
    )

    // URL normalization: the validated string IS the fetched
    // string — trimmed, scheme lowercased, the rest untouched.
    check(
        "normalize keeps a plain https URL",
        PageFeaturePolicy.normalizeDownloadUrl("https://example.com/A.ZIP") ==
            "https://example.com/A.ZIP"
    )
    check(
        "normalize lowercases the scheme only",
        PageFeaturePolicy.normalizeDownloadUrl("HTTPS://Example.COM/a") ==
            "https://Example.COM/a"
    )
    check(
        "normalize trims whitespace",
        PageFeaturePolicy.normalizeDownloadUrl("  http://example.com/a  ") ==
            "http://example.com/a"
    )
    check(
        "normalize refuses blob",
        PageFeaturePolicy.normalizeDownloadUrl("blob:https://example.com/x") == null
    )
    check(
        "normalize refuses empty",
        PageFeaturePolicy.normalizeDownloadUrl("") == null
    )
    check(
        "normalize refuses null",
        PageFeaturePolicy.normalizeDownloadUrl(null) == null
    )

    // File-name sanitizing: bidi overrides and other format /
    // control characters go, separators go, plain names stay.
    check(
        "plain file name is untouched",
        PageFeaturePolicy.sanitizeFileName("invoice.pdf") == "invoice.pdf"
    )
    check(
        "bidi override is stripped",
        PageFeaturePolicy.sanitizeFileName("invoice\u202Efdp.apk") == "invoicefdp.apk"
    )
    check(
        "control characters are stripped",
        PageFeaturePolicy.sanitizeFileName("a\u0007b.txt") == "ab.txt"
    )
    check(
        "separators are stripped",
        PageFeaturePolicy.sanitizeFileName("a/b\\c.txt") == "abc.txt"
    )
    check(
        "all-format name falls back",
        PageFeaturePolicy.sanitizeFileName("\u202E") == "download"
    )

    check(
        "line separator is stripped",
        PageFeaturePolicy.sanitizeFileName("a\u2028b.txt") == "ab.txt"
    )
    check(
        "whitespace runs collapse",
        PageFeaturePolicy.sanitizeFileName("invoice.pdf    .apk") == "invoice.pdf .apk"
    )
    check(
        "single dot falls back",
        PageFeaturePolicy.sanitizeFileName(".") == "download"
    )
    check(
        "double dot falls back",
        PageFeaturePolicy.sanitizeFileName("..") == "download"
    )
    check(
        "long name is capped, extension kept",
        PageFeaturePolicy.sanitizeFileName("a".repeat(300) + ".zip").let {
            it.endsWith(".zip") &&
                it.toByteArray(Charsets.UTF_8).size <= PageFeaturePolicy.MAX_FILE_NAME_BYTES
        }
    )
    check(
        "long multibyte name is capped",
        PageFeaturePolicy.sanitizeFileName("あ".repeat(150) + ".pdf").let {
            it.endsWith(".pdf") &&
                it.toByteArray(Charsets.UTF_8).size <= PageFeaturePolicy.MAX_FILE_NAME_BYTES
        }
    )

    // Referer: origin only, web pages only.
    check(
        "referer is the origin",
        PageFeaturePolicy.refererOrigin("https://example.com/some/path?token=1") ==
            "https://example.com"
    )
    check(
        "referer keeps the port",
        PageFeaturePolicy.refererOrigin("http://localhost:8080/x") == "http://localhost:8080"
    )
    check(
        "referer for an internal page is null",
        PageFeaturePolicy.refererOrigin("pageloupe://newtab/") == null
    )
    check(
        "referer for about:blank is null",
        PageFeaturePolicy.refererOrigin("about:blank") == null
    )
    check(
        "referer for null is null",
        PageFeaturePolicy.refererOrigin(null) == null
    )
    check(
        "referer strips userinfo",
        PageFeaturePolicy.refererOrigin("https://user:pass@example.com/x") ==
            "https://example.com"
    )
    check(
        "referer strips userinfo, keeps port",
        PageFeaturePolicy.refererOrigin("https://user@example.com:8443/x") ==
            "https://example.com:8443"
    )
    check(
        "referer keeps an IPv6 host and port",
        PageFeaturePolicy.refererOrigin("http://[::1]:8080/x") == "http://[::1]:8080"
    )

    check(
        "NBSP run collapses to one space",
        PageFeaturePolicy.sanitizeFileName("a\u00A0\u00A0b.txt") == "a b.txt"
    )
    check(
        "full-width space collapses",
        PageFeaturePolicy.sanitizeFileName("a\u3000b.txt") == "a b.txt"
    )
    check(
        "emoji-heavy name is capped by real bytes",
        PageFeaturePolicy.sanitizeFileName("😀".repeat(100) + ".zip").let {
            it.endsWith(".zip") &&
                it.toByteArray(Charsets.UTF_8).size <= PageFeaturePolicy.MAX_FILE_NAME_BYTES
        }
    )

    // Host extraction and the same-site relation behind the
    // download cookie gate.
    check(
        "hostOf strips userinfo and port",
        PageFeaturePolicy.hostOf("https://user@example.com:8443/x") == "example.com"
    )
    check(
        "hostOf keeps an IPv6 literal",
        PageFeaturePolicy.hostOf("http://[::1]:8080/x") == "[::1]"
    )
    check(
        "hostOf reads a bare origin",
        PageFeaturePolicy.hostOf("https://example.com") == "example.com"
    )
    check(
        "hostOf of about:blank is null",
        PageFeaturePolicy.hostOf("about:blank") == null
    )
    check(
        "same host passes the cookie relation",
        PageFeaturePolicy.isSameOrSubdomain("example.com", "example.com")
    )
    check(
        "subdomain of the page passes",
        PageFeaturePolicy.isSameOrSubdomain("cdn.example.com", "example.com")
    )
    check(
        "parent of the page does NOT pass",
        !PageFeaturePolicy.isSameOrSubdomain("example.com", "www.example.com")
    )
    check(
        "different site does not pass",
        !PageFeaturePolicy.isSameOrSubdomain("example.com", "example.org")
    )
    check(
        "suffix lookalike does not pass",
        !PageFeaturePolicy.isSameOrSubdomain("badexample.com", "example.com")
    )
    check(
        "null host does not pass",
        !PageFeaturePolicy.isSameOrSubdomain(null, "example.com")
    )

    // The cookie gate as a whole, from the two URLs.
    check(
        "cookie for an https download from the same https page",
        PageFeaturePolicy.shouldAttachCookie(
            "https://example.com/f.zip", "https://example.com/page", PageFeaturePolicy.DownloadFrame.MAIN_FRAME
        )
    )
    check(
        "cookie for a subdomain download",
        PageFeaturePolicy.shouldAttachCookie(
            "https://cdn.example.com/f.zip", "https://example.com/page", PageFeaturePolicy.DownloadFrame.MAIN_FRAME
        )
    )
    check(
        "no cookie for a parent-domain download",
        !PageFeaturePolicy.shouldAttachCookie(
            "https://example.com/f.zip", "https://www.example.com/page", PageFeaturePolicy.DownloadFrame.MAIN_FRAME
        )
    )
    check(
        "no cookie from an http page",
        !PageFeaturePolicy.shouldAttachCookie(
            "https://example.com/f.zip", "http://example.com/page", PageFeaturePolicy.DownloadFrame.MAIN_FRAME
        )
    )
    check(
        "no cookie for an http download",
        !PageFeaturePolicy.shouldAttachCookie(
            "http://example.com/f.zip", "https://example.com/page", PageFeaturePolicy.DownloadFrame.MAIN_FRAME
        )
    )
    check(
        "no cookie from a data: page",
        !PageFeaturePolicy.shouldAttachCookie(
            "https://example.com/f.zip", "data:text/html,<p>x</p>", PageFeaturePolicy.DownloadFrame.MAIN_FRAME
        )
    )
    check(
        "no cookie from about:blank",
        !PageFeaturePolicy.shouldAttachCookie(
            "https://example.com/f.zip", "about:blank", PageFeaturePolicy.DownloadFrame.MAIN_FRAME
        )
    )
    check(
        "no cookie from the internal home page (direct navigation)",
        !PageFeaturePolicy.shouldAttachCookie(
            "https://example.com/f.zip", "pageloupe://newtab/", PageFeaturePolicy.DownloadFrame.MAIN_FRAME
        )
    )
    check(
        "no cookie with no committed page",
        !PageFeaturePolicy.shouldAttachCookie("https://example.com/f.zip", null, PageFeaturePolicy.DownloadFrame.MAIN_FRAME)
    )
    check(
        "no cookie with an empty page URL",
        !PageFeaturePolicy.shouldAttachCookie("https://example.com/f.zip", "", PageFeaturePolicy.DownloadFrame.MAIN_FRAME)
    )
    check(
        "no cookie for another site",
        !PageFeaturePolicy.shouldAttachCookie(
            "https://other.example/f.zip", "https://example.com/page", PageFeaturePolicy.DownloadFrame.MAIN_FRAME
        )
    )
    // The initiator gate (SEN-285): even a same-host https
    // download from an https page carries no cookie unless the
    // main frame started it.
    check(
        "no cookie for a subframe-initiated download",
        !PageFeaturePolicy.shouldAttachCookie(
            "https://example.com/f.zip", "https://example.com/page",
            PageFeaturePolicy.DownloadFrame.SUBFRAME
        )
    )
    check(
        "no cookie for an unconfirmed initiator",
        !PageFeaturePolicy.shouldAttachCookie(
            "https://example.com/f.zip", "https://example.com/page",
            PageFeaturePolicy.DownloadFrame.UNKNOWN
        )
    )

    // Frame classification from the recorded attempt lives in
    // DownloadInitiatorTracker and is pinned by
    // DownloadInitiatorTrackerTest.

    // Display rules shared by the three page-feature surfaces.
    check(
        "displayHost shows a plain host",
        PageFeaturePolicy.displayHost("https://example.com/x") == "example.com"
    )
    check(
        "displayHost hides a non-ASCII host",
        PageFeaturePolicy.displayHost("https://例え.jp/x") == null
    )
    check(
        "displayHost of an internal page is null",
        PageFeaturePolicy.displayHost("pageloupe://newtab/") == null
    )
    check(
        "displayText strips format characters",
        PageFeaturePolicy.displayText("https://exa\u202Emple.com") == "https://example.com"
    )
    check(
        "displayText of only-format text is null",
        PageFeaturePolicy.displayText("\u202E") == null
    )

    // Download Referer: an https origin never leaks into http.
    check(
        "referer dropped on https-to-http",
        PageFeaturePolicy.downloadReferer(
            "https://example.com/page", "http://example.com/f.zip"
        ) == null
    )
    check(
        "referer kept on http-to-http",
        PageFeaturePolicy.downloadReferer(
            "http://example.com/page", "http://example.com/f.zip"
        ) == "http://example.com"
    )
    check(
        "referer kept on https-to-https",
        PageFeaturePolicy.downloadReferer(
            "https://example.com/page", "https://example.com/f.zip"
        ) == "https://example.com"
    )

    // Missing runtime permissions: the one computation behind
    // both the prompt flow and the OS-result handler.
    check(
        "nothing missing when all granted",
        PageFeaturePolicy.missingPermissions(
            listOf(
                PageFeaturePolicy.RESOURCE_VIDEO_CAPTURE,
                PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE
            ),
            setOf(
                PageFeaturePolicy.PERMISSION_CAMERA,
                PageFeaturePolicy.PERMISSION_RECORD_AUDIO
            )
        ).isEmpty()
    )
    check(
        "only the ungranted one is missing",
        PageFeaturePolicy.missingPermissions(
            listOf(
                PageFeaturePolicy.RESOURCE_VIDEO_CAPTURE,
                PageFeaturePolicy.RESOURCE_AUDIO_CAPTURE
            ),
            setOf(PageFeaturePolicy.PERMISSION_CAMERA)
        ) == listOf(PageFeaturePolicy.PERMISSION_RECORD_AUDIO)
    )
    check(
        "all missing when none granted",
        PageFeaturePolicy.missingPermissions(
            listOf(PageFeaturePolicy.RESOURCE_VIDEO_CAPTURE),
            emptySet()
        ) == listOf(PageFeaturePolicy.PERMISSION_CAMERA)
    )

    // Popup routing (SEN-286): http(s) becomes a new tab, the
    // blank start page waits, everything else is dropped — a
    // popup must never reach the external-scheme handover or
    // the internal pageloupe: scheme.
    check(
        "popup http opens a tab",
        PageFeaturePolicy.popupAction("http://example.com/landed") ==
            PageFeaturePolicy.PopupAction.OPEN_TAB
    )
    check(
        "popup https opens a tab",
        PageFeaturePolicy.popupAction("https://example.com/") ==
            PageFeaturePolicy.PopupAction.OPEN_TAB
    )
    check(
        "popup scheme match is case-insensitive",
        PageFeaturePolicy.popupAction("HTTPS://example.com/") ==
            PageFeaturePolicy.PopupAction.OPEN_TAB
    )
    check(
        "popup about:blank waits",
        PageFeaturePolicy.popupAction("about:blank") ==
            PageFeaturePolicy.PopupAction.WAIT
    )
    check(
        "popup tel is dropped",
        PageFeaturePolicy.popupAction("tel:000") ==
            PageFeaturePolicy.PopupAction.DROP
    )
    check(
        "popup mailto is dropped",
        PageFeaturePolicy.popupAction("mailto:x@example.invalid") ==
            PageFeaturePolicy.PopupAction.DROP
    )
    check(
        "popup pageloupe scheme is dropped",
        PageFeaturePolicy.popupAction("pageloupe://newtab/") ==
            PageFeaturePolicy.PopupAction.DROP
    )
    check(
        "popup data is dropped",
        PageFeaturePolicy.popupAction("data:text/html,<p>x</p>") ==
            PageFeaturePolicy.PopupAction.DROP
    )
    check(
        "popup file is dropped",
        PageFeaturePolicy.popupAction("file:///sdcard/x") ==
            PageFeaturePolicy.PopupAction.DROP
    )
    check(
        "popup intent is dropped",
        PageFeaturePolicy.popupAction("intent://x#Intent;scheme=https;end") ==
            PageFeaturePolicy.PopupAction.DROP
    )
    check(
        "popup empty is dropped",
        PageFeaturePolicy.popupAction("") ==
            PageFeaturePolicy.PopupAction.DROP &&
            PageFeaturePolicy.popupAction(null) ==
            PageFeaturePolicy.PopupAction.DROP
    )

    // Popup target normalization (SEN-313): the scheme is
    // lowercased for the case-sensitive consumer; the rest of
    // the URL is untouched.
    check(
        "popup target lowercases the scheme only",
        PageFeaturePolicy.normalizePopupTarget("HTTPS://Example.COM/Path?Q=1") ==
            "https://Example.COM/Path?Q=1"
    )
    check(
        "popup target leaves a lowercase URL unchanged",
        PageFeaturePolicy.normalizePopupTarget("https://example.com/") ==
            "https://example.com/"
    )
    check(
        "popup target without a scheme separator is unchanged",
        PageFeaturePolicy.normalizePopupTarget("about:blank") == "about:blank"
    )

    // The lock predicate (SEC-173 / SEN-359): committed https
    // AND the displayed URL is that committed page.
    check(
        "lock shows for a committed https page",
        PageFeaturePolicy.showsLock("https://example.com/", "https://example.com/", sequenceFromHome = false, errorPage = false)
    )
    check(
        "lock hides while an http target is uncommitted",
        !PageFeaturePolicy.showsLock("http://bank.example/", "https://example.com/", sequenceFromHome = false, errorPage = false)
    )
    check(
        "lock hides while an https target is uncommitted",
        !PageFeaturePolicy.showsLock("https://other.example/", "http://example.com/", sequenceFromHome = false, errorPage = false)
    )
    check(
        "lock hides for a committed http page",
        !PageFeaturePolicy.showsLock("http://example.com/", "http://example.com/", sequenceFromHome = false, errorPage = false)
    )
    check(
        "lock hides before anything has committed",
        !PageFeaturePolicy.showsLock("https://example.com/", "", sequenceFromHome = false, errorPage = false)
    )
    check(
        // SEN-361: leaving home for the same https page that
        // was visited before home — the URLs are equal again
        // at load start, but the sequence out of home has
        // committed nothing yet.
        "lock hides for a home-started sequence in flight",
        !PageFeaturePolicy.showsLock("https://example.com/", "https://example.com/", sequenceFromHome = true, errorPage = false)
    )
    check(
        // Run-3 hardening: a failed load commits its URL
        // (SEN-347) but what is on screen is the WebView's
        // error document, not the site's page.
        "lock hides for an error page",
        !PageFeaturePolicy.showsLock("https://example.com/", "https://example.com/", sequenceFromHome = false, errorPage = true)
    )

    // Picker result URIs (SEN-302): content: only. A file:
    // result would be opened by Chromium with the app's UID,
    // which no picker answer may be allowed to cause.
    check(
        "picker content uri is pickable",
        PageFeaturePolicy.isPickableResultUri(
            "content://com.android.providers.media.documents/document/image%3A42",
            "com.android.providers.media.documents"
        )
    )
    check(
        "picker content scheme is case-insensitive",
        PageFeaturePolicy.isPickableResultUri("CONTENT://example/doc/1", "example")
    )
    check(
        "picker file uri is refused",
        !PageFeaturePolicy.isPickableResultUri(
            "file:///data/data/jp.kaleidpixel.pageloupe/shared_prefs/pageloupe_settings.xml",
            null
        )
    )
    check(
        "picker android.resource uri is refused",
        !PageFeaturePolicy.isPickableResultUri(
            "android.resource://jp.kaleidpixel.pageloupe/1", "jp.kaleidpixel.pageloupe"
        )
    )
    check(
        "picker bare path is refused",
        !PageFeaturePolicy.isPickableResultUri("/sdcard/Download/x.bin", null)
    )
    check(
        "picker empty and schemeless are refused",
        !PageFeaturePolicy.isPickableResultUri("", null) &&
            !PageFeaturePolicy.isPickableResultUri("contentx://example/1", "example")
    )
    check(
        "picker lookalike scheme is refused",
        !PageFeaturePolicy.isPickableResultUri("content-file://example/1", "example")
    )

    // Picker MIME types (SEN-354): only MIME-shaped accept
    // tokens may be declared to the picker; extension tokens
    // are dropped, and the caller falls back to */* when the
    // result is empty.
    check(
        "picker mime types keep mime tokens",
        PageFeaturePolicy.pickerMimeTypes(listOf("image/png", "image/*")) ==
            listOf("image/png", "image/*")
    )
    check(
        // SEC-174: all-or-nothing — a partial declaration
        // would grey out the dropped types the page accepts.
        "picker mime types with any extension token declare nothing",
        PageFeaturePolicy.pickerMimeTypes(listOf(".png", ".pdf", "image/jpeg")).isEmpty() &&
            PageFeaturePolicy.pickerMimeTypes(listOf("image/*", ".pdf")).isEmpty()
    )
    check(
        "picker mime types of only extensions is empty",
        PageFeaturePolicy.pickerMimeTypes(listOf(".png")).isEmpty()
    )
    check(
        "picker mime types trim and dedupe",
        PageFeaturePolicy.pickerMimeTypes(listOf(" image/png ", "image/png", "")) ==
            listOf("image/png")
    )

    // Display userinfo stripping (SEN-300): the authority's
    // userinfo goes, everything else — including an '@' in the
    // path — is untouched.
    check(
        "display strips userinfo",
        PageFeaturePolicy.stripUserInfo("https://user:pass@example.com/path") ==
            "https://example.com/path"
    )
    check(
        "display strips encoded userinfo",
        PageFeaturePolicy.stripUserInfo(
            "http://accounts.google.com%2Fsignin@example.com/"
        ) == "http://example.com/"
    )
    check(
        "display splits userinfo at the last @",
        PageFeaturePolicy.stripUserInfo("https://a@b@example.com/") ==
            "https://example.com/"
    )
    check(
        "display keeps an @ in the path",
        PageFeaturePolicy.stripUserInfo("https://example.com/@user/page") ==
            "https://example.com/@user/page"
    )
    check(
        "display keeps a url without userinfo unchanged",
        PageFeaturePolicy.stripUserInfo("https://example.com:8443/a?b=c#d") ==
            "https://example.com:8443/a?b=c#d"
    )
    check(
        "display strips userinfo before an ipv6 host",
        PageFeaturePolicy.stripUserInfo("http://user@[::1]:8080/x") ==
            "http://[::1]:8080/x"
    )
    check(
        "display leaves non-authority urls unchanged",
        PageFeaturePolicy.stripUserInfo("about:blank") == "about:blank" &&
            PageFeaturePolicy.stripUserInfo("data:text/html,x@y") == "data:text/html,x@y"
    )

    check(
        "picker downloads authority is refused",
        !PageFeaturePolicy.isPickableResultUri("content://downloads/my_downloads/42", "downloads") &&
            !PageFeaturePolicy.isPickableResultUri("content://downloads/download/7", "downloads")
    )
    check(
        // SEC-169: the caller passes Uri.getAuthority(), which
        // is percent-decoded — so the encoded spelling in the
        // raw string cannot slip the refusal past the check.
        "picker encoded downloads authority is refused",
        !PageFeaturePolicy.isPickableResultUri("content://download%73/my_downloads/42", "downloads")
    )
    check(
        "picker downloads behind userinfo or a port is refused",
        !PageFeaturePolicy.isPickableResultUri(
            "content://user@downloads/my_downloads/42", "user@downloads"
        ) &&
            !PageFeaturePolicy.isPickableResultUri(
                "content://downloads:8443/my_downloads/42", "downloads:8443"
            )
    )
    check(
        "picker content uri without an authority is refused",
        !PageFeaturePolicy.isPickableResultUri("content:///sdcard/x.bin", null) &&
            !PageFeaturePolicy.isPickableResultUri("content://example/1", "")
    )
    check(
        "picker storage provider uri stays pickable",
        PageFeaturePolicy.isPickableResultUri(
            "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fx.bin",
            "com.android.externalstorage.documents"
        )
    )
    check(
        "display splits userinfo at a backslash boundary",
        PageFeaturePolicy.stripUserInfo("https://evil.example\\@good.example/") ==
            "https://evil.example\\@good.example/"
    )

    // Dialog message text (SEN-346): line breaks survive,
    // invisible and bidi characters go, the cap is marked.
    check(
        "dialog message keeps line breaks",
        PageFeaturePolicy.dialogMessageText("first line\nsecond line") ==
            "first line\nsecond line"
    )
    check(
        "dialog message drops bidi and control characters",
        PageFeaturePolicy.dialogMessageText("pay \u202Enow\u0001 please") ==
            "pay now please"
    )
    check(
        "dialog message turns paragraph separators into breaks",
        PageFeaturePolicy.dialogMessageText("a\u2029b") == "a\nb"
    )
    check(
        // Run-3 hardening: the filter judges by code point, so
        // supplementary-plane tag characters (U+E0001 here) —
        // invisible format characters a char-wise pass sees
        // only as surrogate halves — are dropped too.
        "dialog message drops tag characters",
        PageFeaturePolicy.dialogMessageText("a\uDB40\uDC01b") == "ab"
    )
    check(
        "dialog message keeps astral letters",
        PageFeaturePolicy.dialogMessageText("a\uD83D\uDE00b") == "a\uD83D\uDE00b"
    )
    check(
        // The cap must not split a surrogate pair: an astral
        // character straddling the cap is left out whole.
        "dialog message cap never splits a surrogate pair",
        PageFeaturePolicy.dialogMessageText(
            "x".repeat(PageFeaturePolicy.DIALOG_MESSAGE_MAX - 1) +
                "\uD83D\uDE00" + "y".repeat(10)
        ) == "x".repeat(PageFeaturePolicy.DIALOG_MESSAGE_MAX - 1) + "…"
    )
    check(
        // The bounded scan prefix is cut by char: when the
        // drops leave the result under the cap, a pair severed
        // at the prefix end must not survive as a lone
        // surrogate either.
        "dialog message drops a surrogate severed by the scan bound",
        PageFeaturePolicy.dialogMessageText(
            "\u0001".repeat(PageFeaturePolicy.DIALOG_MESSAGE_MAX * 2 - 1) +
                "\uD83D\uDE00"
        ) == ""
    )
    check(
        // An unpaired surrogate anywhere in the message is
        // dropped the same way (a valid pair is a single
        // supplementary code point and is kept, as the astral
        // check above shows).
        "dialog message drops unpaired surrogates",
        PageFeaturePolicy.dialogMessageText("a\uD83Db") == "ab" &&
            PageFeaturePolicy.dialogMessageText("a\uDE00b") == "ab"
    )
    check(
        "dialog message caps with an explicit ellipsis",
        PageFeaturePolicy.dialogMessageText("x".repeat(5000)).let {
            it.length == PageFeaturePolicy.DIALOG_MESSAGE_MAX + 1 && it.endsWith("…")
        }
    )
    check(
        "dialog message of null and blank is empty",
        PageFeaturePolicy.dialogMessageText(null) == "" &&
            PageFeaturePolicy.dialogMessageText("   ") == ""
    )

    // Approval arm delay (SEN-305): an approval counts only
    // once the dialog has been visible for the full delay.
    check(
        "approval is armed after the delay",
        PageFeaturePolicy.approvalArmed(10_000L, 10_000L + PageFeaturePolicy.APPROVAL_ARM_DELAY_MS)
    )
    check(
        "approval is not armed inside the delay",
        !PageFeaturePolicy.approvalArmed(
            10_000L, 10_000L + PageFeaturePolicy.APPROVAL_ARM_DELAY_MS - 1
        )
    )
    check(
        "approval is never armed for a dialog never shown",
        !PageFeaturePolicy.approvalArmed(0L, Long.MAX_VALUE / 2)
    )

    // Audit hardening: fail-closed authorities.
    check(
        "hostOf refuses a backslash in the host",
        PageFeaturePolicy.hostOf("https://exa\\mple.com/") == null
    )
    check(
        "hostOf refuses a control character in the host",
        PageFeaturePolicy.hostOf("https://example\u0001.com/") == null
    )
    check(
        "refererOrigin refuses a backslash in the host",
        PageFeaturePolicy.refererOrigin("https://exa\\mple.com/page") == null
    )
    check(
        "refererOrigin still reads a plain origin",
        PageFeaturePolicy.refererOrigin("https://user@example.com:8443/x") ==
            "https://example.com:8443"
    )

    // Audit hardening: the cleartext warning predicate
    // mirrors showsLock (committed page only, never home).
    check(
        "cleartext warning for a committed http page",
        PageFeaturePolicy.showsCleartextWarning(
            "http://a.example/", "http://a.example/", sequenceFromHome = false, errorPage = false
        )
    )
    check(
        "no cleartext warning for https",
        !PageFeaturePolicy.showsCleartextWarning(
            "https://a.example/", "https://a.example/", sequenceFromHome = false, errorPage = false
        )
    )
    check(
        "no cleartext warning while the load is uncommitted",
        !PageFeaturePolicy.showsCleartextWarning(
            "http://b.example/", "http://a.example/", sequenceFromHome = false, errorPage = false
        )
    )
    check(
        "no cleartext warning in a sequence out of home",
        !PageFeaturePolicy.showsCleartextWarning(
            "http://a.example/", "http://a.example/", sequenceFromHome = true, errorPage = false
        )
    )
    check(
        "no cleartext warning for an error page",
        !PageFeaturePolicy.showsCleartextWarning(
            "http://a.example/", "http://a.example/", sequenceFromHome = false, errorPage = true
        )
    )

    // Audit hardening: the data: main-frame gate. Chrome
    // blocks page-initiated data: top-level navigations
    // outright, so there is no gesture case to allow.
    check(
        "data main-frame navigation is blocked",
        PageFeaturePolicy.shouldBlockDataMainFrame("data", true)
    )
    check(
        "data subframe is not this gate's business",
        !PageFeaturePolicy.shouldBlockDataMainFrame("data", false)
    )
    check(
        "https main-frame is not blocked",
        !PageFeaturePolicy.shouldBlockDataMainFrame("https", true)
    )
    check(
        "missing scheme is not blocked",
        !PageFeaturePolicy.shouldBlockDataMainFrame(null, true)
    )

    // Audit hardening, second pass: the authority check
    // runs BEFORE the userinfo is stripped, and by code
    // point.
    check(
        "hostOf refuses a backslash in the userinfo",
        PageFeaturePolicy.hostOf("https://evil.example\\@good.example/") == null
    )
    check(
        "hostOf refuses a format character in the host",
        PageFeaturePolicy.hostOf("https://example\u200e.com/") == null
    )
    check(
        "hostOf refuses a line separator in the host",
        PageFeaturePolicy.hostOf("https://example\u2028.com/") == null
    )
    check(
        "refererOrigin refuses a backslash in the userinfo",
        PageFeaturePolicy.refererOrigin("https://evil.example\\@good.example/x") == null
    )
    check(
        "displayText drops a supplementary tag character",
        PageFeaturePolicy.displayText("a\uDB40\uDC01b") == "ab"
    )

    // Audit hardening: the stored-title rule.
    check(
        "titleText collapses a newline to a space",
        PageFeaturePolicy.titleText("Foo\nBar") == "Foo Bar"
    )
    check(
        "titleText collapses whitespace runs and trims",
        PageFeaturePolicy.titleText("  a  \t b  ") == "a b"
    )
    check(
        "titleText keeps a ZWJ emoji sequence",
        PageFeaturePolicy.titleText("a\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67b") ==
            "a\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67b"
    )
    check(
        "titleText keeps a ZWNJ",
        PageFeaturePolicy.titleText("a\u200Cb") == "a\u200Cb"
    )
    check(
        "titleText drops a bidi override",
        PageFeaturePolicy.titleText("a\u202Eb") == "ab"
    )
    check(
        "titleText of only control characters is null",
        PageFeaturePolicy.titleText("\u0001\u0002") == null
    )
    check(
        "titleText cap never splits a surrogate pair",
        PageFeaturePolicy.titleText("a".repeat(199) + "\uD83D\uDE00" + "b".repeat(10))?.length == 199
    )

    println("PageFeaturePolicyTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
