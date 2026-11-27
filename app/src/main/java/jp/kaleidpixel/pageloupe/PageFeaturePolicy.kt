package jp.kaleidpixel.pageloupe

/**
 * Pure decisions behind the page-feature bridges (downloads,
 * permission requests), kept Android-free so the rules are unit
 * testable. The framework wiring lives in BrowserTab (callbacks)
 * and MainActivity (pickers, dialogs, system permission flow);
 * this object only answers "what may happen".
 */
object PageFeaturePolicy {

    // WebChromeClient PermissionRequest resource names (the
    // framework constants are plain strings; duplicated here so
    // the policy stays free of android.webkit at test time).
    const val RESOURCE_VIDEO_CAPTURE = "android.webkit.resource.VIDEO_CAPTURE"
    const val RESOURCE_AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"

    // Android runtime permissions the two capture resources need.
    const val PERMISSION_CAMERA = "android.permission.CAMERA"
    const val PERMISSION_RECORD_AUDIO = "android.permission.RECORD_AUDIO"

    /**
     * The one normalized form of a download URL, or null when it
     * is not downloadable: trimmed, with the scheme lowercased.
     * Every consumer (the policy check, Uri parsing, the
     * DownloadManager request) uses THIS string, so the value
     * that was validated is the value that is fetched — the
     * framework's request builder validates the scheme
     * case-sensitively and must never see a different string
     * than the one the policy approved.
     */
    fun normalizeDownloadUrl(url: String?): String? {
        if (url == null) return null
        val u = url.trim()
        return when {
            u.startsWith("http://", ignoreCase = true) ->
                "http://" + u.substring("http://".length)
            u.startsWith("https://", ignoreCase = true) ->
                "https://" + u.substring("https://".length)
            else -> null
        }
    }

    /** Longest file name kept, in UTF-8 bytes (filesystems cap at 255). */
    const val MAX_FILE_NAME_BYTES = 200

    /**
     * A page-supplied file name made safe to show and to save:
     * Unicode control, format and line/paragraph separator
     * characters are removed outright (including the bidi
     * overrides, e.g. U+202E, that can make "invoice‮fdp.apk"
     * render as a PDF — and including tab/newline, which vanish
     * rather than becoming spaces), path separators are removed,
     * the remaining whitespace runs (ASCII or Unicode spaces)
     * collapse to one space (a long blank run can hide a second
     * extension), and the result is capped at
     * [MAX_FILE_NAME_BYTES] UTF-8 bytes with a short extension
     * preserved. Falls back to a plain name when nothing
     * survives, and for "." / "..".
     */
    fun sanitizeFileName(name: String): String {
        val stripped = name.filter { c ->
            val type = Character.getType(c)
            type != Character.CONTROL.toInt() &&
                type != Character.FORMAT.toInt() &&
                type != Character.LINE_SEPARATOR.toInt() &&
                type != Character.PARAGRAPH_SEPARATOR.toInt() &&
                c != '/' && c != '\\'
        }
        // Collapse whitespace runs to one ASCII space. The run
        // test covers Unicode space separators (NBSP, full-width
        // space, ...) as well as ASCII whitespace: a run of any
        // of them can hide a second extension just as well.
        val collapsed = StringBuilder()
        var inRun = false
        for (c in stripped) {
            val ws = Character.isWhitespace(c) ||
                Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
            if (ws) {
                if (!inRun) {
                    collapsed.append(' ')
                    inRun = true
                }
            } else {
                collapsed.append(c)
                inRun = false
            }
        }
        val cleaned = collapsed.toString().trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            return "download"
        }
        return capFileNameLength(cleaned)
    }

    private fun capFileNameLength(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_FILE_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 11) name.substring(dot) else ""
        val base = if (ext.isEmpty()) name else name.substring(0, dot)
        val budget = MAX_FILE_NAME_BYTES - ext.toByteArray(Charsets.UTF_8).size
        // Walk CODE POINTS with their true UTF-8 lengths: sizing
        // per UTF-16 char undercounts a surrogate pair (each half
        // alone encodes as a 1-byte replacement, the pair is 4
        // bytes), which would let an emoji-heavy name sail past
        // the cap and fail at the filesystem instead.
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < base.length) {
            val cp = base.codePointAt(i)
            val b = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (used + b > budget) break
            sb.appendCodePoint(cp)
            used += b
            i += Character.charCount(cp)
        }
        val result = sb.toString() + ext
        return result.ifEmpty { "download" }
    }

    /**
     * The host of a URL or origin string, lowercased, without
     * userinfo or port (an [IPv6] literal keeps its brackets).
     * Null when there is no host to speak of.
     */
    fun hostOf(urlOrOrigin: String?): String? {
        if (urlOrOrigin == null) return null
        val u = urlOrOrigin.trim()
        val schemeEnd = u.indexOf("://")
        if (schemeEnd <= 0) return null
        val rest = u.substring(schemeEnd + 3)
        val rawAuthority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        // Fail closed on an authority no strict parser would
        // produce (audit hardening), checked BEFORE the userinfo
        // is stripped: a backslash in the userinfo is exactly
        // the confusion attack — WHATWG parsers treat '\' as
        // '/' in special schemes, so in
        // "https://evil.example\@good.example/" the real host
        // is evil.example while a lenient split names
        // good.example (the shape SEN-349 closed in
        // stripUserInfo). An invisible character anywhere in
        // the authority fails it too.
        if (rawAuthority.codePoints()
                .anyMatch { it == '\\'.code || isInvisibleCodePoint(it) }
        ) {
            return null
        }
        val authority = rawAuthority.substringAfterLast('@')
        if (authority.isEmpty()) return null
        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            authority.substring(0, close + 1)
        } else {
            authority.substringBefore(':')
        }
        return host.lowercase().ifEmpty { null }
    }

    /**
     * Whether [downloadHost] is [pageHost] itself or a subdomain
     * of it — the only cross-host relation the download cookie
     * gate accepts. The reverse (a page on a subdomain pulling
     * the parent domain's cookies) is NOT accepted: on shared
     * hosting (github.io and friends) the parent is effectively
     * another site. This is a suffix approximation without the
     * Public Suffix List, recorded as such in the design records.
     */
    fun isSameOrSubdomain(downloadHost: String?, pageHost: String?): Boolean {
        if (downloadHost.isNullOrEmpty() || pageHost.isNullOrEmpty()) return false
        return downloadHost == pageHost || downloadHost.endsWith(".$pageHost")
    }

    /**
     * Which frame the navigation that became a download was
     * FOR, as recorded by DownloadInitiatorTracker (SEN-285).
     * This is the navigation's target frame — the only frame
     * fact the WebView callbacks carry — not proof of which
     * frame's script initiated it: a subframe that navigates
     * the top frame classifies as MAIN_FRAME (the residual is
     * recorded in the design records).
     */
    enum class DownloadFrame {
        /** The tab's own main frame: the page the user is looking at. */
        MAIN_FRAME,

        /** A subframe (an iframe inside the page). */
        SUBFRAME,

        /**
         * No matching navigation attempt is on record: a POST
         * navigation (shouldOverrideUrlLoading never sees those),
         * an app-initiated load, or a race that overwrote the
         * record. Fails closed, like SUBFRAME.
         */
        UNKNOWN
    }

    /**
     * The whole download-cookie decision, from the two URLs
     * themselves (never inferred from a header value computed
     * for another purpose). [pageUrl] is the tab's last
     * COMMITTED page (BrowserTab.lastCommittedUrl), never the
     * still-loading navigation target: a navigation that turns
     * into a download never commits, so the gate must judge
     * from the page the user is coming from.
     * - The navigation that became the download must have been
     *   for the MAIN frame (SEN-285): a subframe navigation —
     *   or a frame that cannot be confirmed — never receives
     *   the page's cookies, even for a same-host https download.
     * - The download must be https.
     * - The starting page must be https too, and the download
     *   host must be the page's host or a subdomain of it.
     * - Everything else fails CLOSED: an http, data: or about:
     *   page, an unrelated host — and no committed page at all
     *   (a typed or restored navigation that goes straight to a
     *   download carries no cookie; go via the site instead).
     *   That trade-off is recorded in the design records.
     */
    fun shouldAttachCookie(
        downloadUrl: String,
        pageUrl: String?,
        frame: DownloadFrame
    ): Boolean {
        if (frame != DownloadFrame.MAIN_FRAME) return false
        if (!downloadUrl.startsWith("https://")) return false
        val page = pageUrl?.trim()
        if (page.isNullOrEmpty()) return false
        val schemeEnd = page.indexOf("://")
        val scheme = if (schemeEnd > 0) {
            page.substring(0, schemeEnd).lowercase()
        } else {
            ""
        }
        return when (scheme) {
            "https" -> isSameOrSubdomain(hostOf(downloadUrl), hostOf(page))
            else -> false
        }
    }

    /**
     * A host safe to SHOW the user, or null when there is none:
     * extracted, and only printable ASCII within the DNS length
     * limit — anything else (unparseable, huge, or carrying
     * display trickery) is not put on screen verbatim. All three
     * page-feature surfaces (download dialog, permission prompt,
     * fullscreen notice) show hosts through this one rule.
     */
    fun displayHost(urlOrOrigin: String?): String? {
        val u = urlOrOrigin?.trim() ?: return null
        if (!u.startsWith("http://", ignoreCase = true) &&
            !u.startsWith("https://", ignoreCase = true)
        ) return null
        val h = hostOf(u) ?: return null
        return if (h.length <= 253 && h.all { it.code in 33..126 }) h else null
    }

    /**
     * Free text of page origin safe to SHOW the user (the
     * permission prompt shows the full origin, scheme included,
     * because http-vs-https is part of the decision): control,
     * format and line/paragraph separator characters stripped,
     * capped at 200 characters. Null when nothing survives.
     * The pass is by code point, not by char (audit hardening):
     * supplementary-plane format characters (the tag characters
     * U+E0001..E007F) are invisible payload carriers too, and a
     * char-wise filter sees only their surrogate halves and
     * lets them through; the cap likewise never splits a pair.
     */
    fun displayText(text: String?): String? {
        if (text == null) return null
        val cleaned = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isInvisibleCodePoint(cp)) cleaned.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return capChars(cleaned.toString().trim(), 200).ifEmpty { null }
    }

    /**
     * A page title made safe to STORE (audit hardening): the
     * stored title is what tab cards, the history and the share
     * sheet later show, so it is cleaned once, here, instead of
     * every consumer re-deriving it. The rule differs from
     * [displayText] on purpose: a title is prose, not an origin.
     * Whitespace and the separator characters collapse to
     * single spaces (a stored title is shown on one line
     * everywhere); control characters are dropped; format
     * characters are dropped EXCEPT the two joiners (U+200C /
     * U+200D), which are load-bearing in emoji sequences and
     * several scripts' orthography — the bidi overrides and
     * tag characters that make a title lie are format
     * characters too and go. By code point throughout, capped
     * at [TITLE_MAX_CHARS] without splitting a surrogate pair.
     * Null when nothing survives.
     */
    fun titleText(text: String?): String? {
        if (text == null) return null
        val out = StringBuilder()
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                Character.isWhitespace(cp) ||
                    type == Character.SPACE_SEPARATOR.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() ||
                    type == Character.PARAGRAPH_SEPARATOR.toInt() -> {
                    if (out.isNotEmpty()) pendingSpace = true
                }
                type == Character.CONTROL.toInt() -> Unit
                type == Character.FORMAT.toInt() -> {
                    if (cp == 0x200C || cp == 0x200D) {
                        if (pendingSpace) {
                            out.append(' ')
                            pendingSpace = false
                        }
                        out.appendCodePoint(cp)
                    }
                }
                else -> {
                    if (pendingSpace) {
                        out.append(' ')
                        pendingSpace = false
                    }
                    out.appendCodePoint(cp)
                }
            }
        }
        return capChars(out.toString(), TITLE_MAX_CHARS).ifEmpty { null }
    }

    /**
     * The Referer a download may carry: [refererOrigin], except
     * that an https page's origin is never sent into a cleartext
     * (http) download, where anyone on the network could read it.
     */
    fun downloadReferer(pageUrl: String?, downloadUrl: String): String? {
        val origin = refererOrigin(pageUrl) ?: return null
        val pageHttps = pageUrl!!.trim().startsWith("https://", ignoreCase = true)
        val downloadHttp = downloadUrl.startsWith("http://", ignoreCase = true)
        return if (pageHttps && downloadHttp) null else origin
    }

    /**
     * The Referer a download may carry: the page's ORIGIN only
     * (scheme + host + port), never the full URL — paths and query
     * strings can hold tokens the download host has no business
     * seeing, and userinfo is credentials, not an origin. Null
     * for non-web pages (internal pages, about:), which send no
     * Referer at all.
     */
    fun refererOrigin(pageUrl: String?): String? {
        if (pageUrl == null) return null
        val u = pageUrl.trim()
        val schemeEnd = u.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = u.substring(0, schemeEnd)
        if (!scheme.equals("http", ignoreCase = true) &&
            !scheme.equals("https", ignoreCase = true)
        ) return null
        val rest = u.substring(schemeEnd + 3)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        // The same fail-closed rule as hostOf, on the authority
        // BEFORE the userinfo is stripped (audit hardening).
        if (authority.codePoints()
                .anyMatch { it == '\\'.code || isInvisibleCodePoint(it) }
        ) {
            return null
        }
        // Drop any userinfo: everything through the LAST '@' is
        // credentials (a host cannot contain '@'), leaving
        // host[:port] — or an [IPv6] literal with its port.
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return null
        return scheme.lowercase() + "://" + hostPort
    }

    /**
     * The subset of a page's requested resources this browser
     * will ever grant: camera and microphone capture only.
     * Anything else (protected media, MIDI, ...) is dropped here
     * and denied by the caller, never prompted for.
     */
    fun grantableResources(requested: Array<String>): List<String> =
        requested.filter {
            it == RESOURCE_VIDEO_CAPTURE || it == RESOURCE_AUDIO_CAPTURE
        }.distinct()

    /**
     * The Android runtime permissions a grant of [grantable]
     * needs, in request order (camera before microphone, the
     * order the resources were filtered in).
     */
    fun androidPermissionsFor(grantable: List<String>): List<String> =
        grantable.mapNotNull {
            when (it) {
                RESOURCE_VIDEO_CAPTURE -> PERMISSION_CAMERA
                RESOURCE_AUDIO_CAPTURE -> PERMISSION_RECORD_AUDIO
                else -> null
            }
        }.distinct()

    /**
     * The runtime permissions for [grantable] that are not in
     * [granted] — the one computation both the site-prompt flow
     * and the OS-result handler need, so the two can never drift.
     */
    fun missingPermissions(grantable: List<String>, granted: Set<String>): List<String> =
        androidPermissionsFor(grantable).filter { it !in granted }

    /** What a popup window's navigation means for the browser. */
    enum class PopupAction {
        /** http(s): the popup is normalized into a new tab. */
        OPEN_TAB,

        /** The blank page a popup starts on: no decision yet. */
        WAIT,

        /**
         * Everything else (external schemes, the internal
         * pageloupe: scheme, data:, file:, ...): the popup is
         * dropped. A frame that asked for a window must never
         * reach the external-scheme handover or the internal
         * scheme through the popup route (SEN-286).
         */
        DROP
    }

    /**
     * Classify a popup navigation URL. Pure string work, like
     * the rest of this policy, so the routing is pinned by JVM
     * tests. The scheme match is case-insensitive and the URL
     * is trimmed, matching how the value will be consumed.
     */
    fun popupAction(url: String?): PopupAction {
        val u = url?.trim().orEmpty()
        return when {
            u.startsWith("about:blank", ignoreCase = true) -> PopupAction.WAIT
            u.startsWith("http://", ignoreCase = true) ||
                u.startsWith("https://", ignoreCase = true) -> PopupAction.OPEN_TAB
            else -> PopupAction.DROP
        }
    }

    /**
     * Whether a file-picker RESULT URI may be handed to the
     * WebView's upload machinery (SEN-302): content: URIs only.
     * A picker is an arbitrary app the user was offered; what it
     * returns is data, not authority. A file: result would be
     * opened by Chromium with THIS app's UID — the picker itself
     * cannot read another app's private files, but the upload
     * path can, which turns any picker into a confused deputy
     * for the app's whole private directory (settings, history,
     * bookmarks, the WebView cookie store). A content: result,
     * by contrast, is served by the provider that owns it under
     * the picker's own grant, and a provider can only serve its
     * own data. Every other scheme (file:, android.resource:,
     * a bare path with no scheme at all) fails closed.
     *
     * [authority] is the URI's authority AS THE RESOLVER SEES
     * IT — the caller passes Uri.getAuthority(), which is
     * percent-DECODED. Judging the raw string instead would
     * let "download%73" slip past the downloads refusal below
     * while still resolving to the downloads provider
     * (SEC-169). A content: URI whose authority cannot be
     * established at all fails closed too.
     */
    fun isPickableResultUri(uri: String, authority: String?): Boolean {
        val u = uri.trim()
        val scheme = u.substringBefore(':', "").lowercase()
        if (scheme != "content") return false
        // The downloads provider's own authority stays refused
        // even as content: (SEC-164). Its my_downloads URIs name
        // files THIS app downloaded — the provider opens them
        // for the owning UID, which is the app itself when
        // Chromium does the opening — so a picker minting one
        // would have the page read the app's downloads
        // (including a secret tab's) without the user ever
        // choosing that file. A file the user actually picks in
        // the system UI arrives under the storage provider's
        // authority instead, so nothing legitimate is lost.
        // The comparison is against the provider's name alone:
        // userinfo and a port are stripped first, since the
        // resolver routes on the host part.
        val provider = authority?.trim()?.lowercase()
            ?.substringAfterLast('@')
            ?.substringBefore(':')
        if (provider.isNullOrEmpty()) return false
        return provider != "downloads"
    }

    /**
     * A URL with the authority's userinfo removed — everything
     * through the LAST '@' of the authority, the same split
     * [hostOf] and [refererOrigin] use (a host cannot contain
     * '@'; an '@' in the path or query is data and stays). For
     * DISPLAY only (SEN-300): the address bar shows the URL from
     * its head, and a long userinfo — real sites put encoded
     * path-looking text there — pushes the actual host off the
     * visible edge while the page content pretends to be the
     * named site. Navigation, history and the cookie gate keep
     * using the untouched URL; this only changes what is shown.
     * URLs without "://" or without '@' in the authority are
     * returned unchanged.
     */
    fun stripUserInfo(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return url
        val authStart = schemeEnd + 3
        var authEnd = url.length
        for (i in authStart until url.length) {
            val c = url[i]
            // '\\' ends the authority too (SEN-349): WHATWG
            // treats it as a delimiter for special schemes, so
            // "https://evil.example\@good.example/" has host
            // evil.example — splitting only on '/' would show
            // good.example as the host. URLs the WebView reports
            // are already normalized ('\\' became '/'), so this
            // only hardens values that arrive unnormalized.
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                authEnd = i
                break
            }
        }
        val authority = url.substring(authStart, authEnd)
        val at = authority.lastIndexOf('@')
        if (at < 0) return url
        return url.substring(0, authStart) +
            authority.substring(at + 1) + url.substring(authEnd)
    }

    /**
     * Whether the address bar may show the lock for a tab
     * displaying [currentUrl] whose last COMMITTED url is
     * [lastCommittedUrl] (SEC-173, SEN-359): the lock vouches
     * for the page on screen, so it requires the committed
     * page to be https AND the displayed URL to be that
     * committed page. While a navigation is uncommitted the
     * bar already names the target but the screen still shows
     * the committed page — an https page steering to an http
     * target it then stops would otherwise pair the target's
     * name with the old page's lock. One predicate, here, so
     * no future caller can re-derive half of the rule.
     *
     * [sequenceFromHome] closes the one case equality alone
     * cannot (SEN-361): the committed URL deliberately does
     * not move while the start page shows, so leaving home
     * for the SAME https page that was visited before home
     * makes the two URLs equal again at load start — while
     * the screen still shows the start page. A load sequence
     * out of home has committed nothing yet, so it shows no
     * lock until its own commit lands (which clears the
     * flag).
     *
     * [errorPage] closes the last gap (run-3 hardening): a
     * load that FAILED still commits its URL (SEN-347), but
     * what is on screen then is the WebView's own error
     * document, not the site's — the site never got to
     * present anything under its name. Chrome shows no lock
     * on its error pages; neither does this predicate (and
     * its mirror below shows no cleartext warning either:
     * the error document is not the cleartext page).
     */
    fun showsLock(
        currentUrl: String,
        lastCommittedUrl: String,
        sequenceFromHome: Boolean,
        errorPage: Boolean
    ): Boolean =
        !sequenceFromHome && !errorPage &&
            lastCommittedUrl.startsWith("https://") && currentUrl == lastCommittedUrl

    /**
     * Whether the address bar shows the cleartext warning for
     * the page ON SCREEN: the mirror of [showsLock] for http.
     * Same discipline — the warning names the committed page,
     * never an in-flight navigation (while a load is
     * uncommitted the neutral icon stands, SEC-173) and never
     * the start page ([sequenceFromHome], SEN-361). Chrome
     * marks cleartext pages "Not secure"; a page that merely
     * shows the search icon would look like a fresh tab.
     */
    fun showsCleartextWarning(
        currentUrl: String,
        lastCommittedUrl: String,
        sequenceFromHome: Boolean,
        errorPage: Boolean
    ): Boolean =
        !sequenceFromHome && !errorPage &&
            lastCommittedUrl.startsWith("http://") && currentUrl == lastCommittedUrl

    /**
     * Whether a page-driven main-frame navigation to a data:
     * URL is refused (audit hardening, Chrome parity): Chrome
     * blocks renderer-initiated data: top-level navigations
     * outright, because the resulting document has no origin
     * to show and its content is whatever the linking page
     * chose. There is deliberately no gesture exception:
     * hasGesture() reports transient activation — a tap
     * anywhere on the page within the last seconds (the
     * property recorded as SEC-4) — which is not an intent to
     * open THIS document. Browser-initiated loads (the
     * address bar, menus) never pass through the
     * shouldOverrideUrlLoading gate this rule serves at all,
     * so typing a data: URL keeps working.
     */
    fun shouldBlockDataMainFrame(
        scheme: String?,
        isForMainFrame: Boolean
    ): Boolean =
        scheme == "data" && isForMainFrame

    /** The stored-title cap, shared with the share sheet's
     *  title cap (ShareText.MAX_TITLE_CHARS). */
    private const val TITLE_MAX_CHARS = 200

    /**
     * Invisible code points — control, format, and the line /
     * paragraph separators — judged by code point so
     * supplementary-plane format characters count. The single
     * definition behind [displayText], [titleText] and the
     * fail-closed authority checks in [hostOf] / [refererOrigin].
     */
    private fun isInvisibleCodePoint(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt()
    }

    /** [s] capped at [max] UTF-16 chars without splitting a
     *  surrogate pair: a cut that would strand a high surrogate
     *  drops it too. */
    private fun capChars(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end)
    }

    /**
     * The MIME types a file pick may declare, from the page's
     * accept list (SEN-354): only MIME-shaped tokens are
     * declarable (a '/' and no leading '.'). Chromium usually
     * converts accept extensions to MIME types before they
     * reach the params, but a token that is still an extension
     * (".png") or a bare word is not a type DocumentsUI can
     * filter by — declared as-is it can leave every file
     * greyed out.
     *
     * All-or-nothing (SEC-174): if ANY token had to be
     * dropped, the result is empty and the caller declares the
     * any-type wildcard instead. A partial declaration is
     * worse than none — with an accept list mixing a MIME
     * wildcard and ".pdf", the images would stay pickable
     * while the PDFs the page also accepts grey out. Widening
     * costs no safety: the declared types are the page's own
     * values and were never a defense, only a picker filter.
     */
    fun pickerMimeTypes(acceptTypes: List<String>): List<String> {
        val tokens = acceptTypes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val mimes = tokens.filter { it.contains('/') && !it.startsWith(".") }
        return if (mimes.size == tokens.size) mimes else emptyList()
    }

    /**
     * A JavaScript dialog's message made safe and bounded to
     * SHOW (SEN-346): unlike [displayText] — which serves short
     * origin strings and therefore strips line breaks and cuts
     * at 200 — a dialog body keeps its line breaks (a confirm's
     * conditions are often the second paragraph), drops only
     * the invisible and direction-overriding characters, and
     * is capped at [DIALOG_MESSAGE_MAX] characters with the cut
     * MARKED, so the user never approves text they could not
     * see. The input is cut to a bounded prefix BEFORE the
     * per-character pass (PER-160): the string is page-sized,
     * and the main thread must not scan megabytes to show 2000
     * characters. Null becomes the empty string.
     */
    const val DIALOG_MESSAGE_MAX = 2000

    /** Longest prompt() default value handed to the input field. */
    const val DIALOG_VALUE_MAX = 2000

    fun dialogMessageText(text: String?): String {
        if (text == null) return ""
        // A bounded prefix with slack for the characters the
        // filter drops: at most a few times the output cap is
        // ever scanned, however long the page's string is.
        val bounded = text.take(DIALOG_MESSAGE_MAX * 2)
        val cleaned = StringBuilder()
        // The pass is by code point, like displayText's
        // (run-3 hardening): judged per char, the tag
        // characters U+E0001..E007F read as two surrogate
        // halves — neither CONTROL nor FORMAT — and slipped
        // through a filter whose whole point is dropping
        // invisible format characters.
        var i = 0
        while (i < bounded.length) {
            val cp = bounded.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code) {
                cleaned.append('\n')
                continue
            }
            val type = Character.getType(cp)
            if (type == Character.SURROGATE.toInt()) {
                // A surrogate that reaches here as its own
                // code point is unpaired — its other half is
                // missing (the page sent it that way, or the
                // bounded prefix above severed the pair).
                // Letting it through would hand the dialog a
                // broken character; valid pairs arrive as a
                // single supplementary code point instead and
                // are unaffected.
                continue
            }
            if (type == Character.CONTROL.toInt() ||
                type == Character.FORMAT.toInt()
            ) {
                // Invisible controls and bidi overrides go;
                // line and paragraph separators become the
                // line break they mean.
                continue
            }
            if (type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt()
            ) {
                cleaned.append('\n')
                continue
            }
            cleaned.appendCodePoint(cp)
        }
        val s = cleaned.toString().trim()
        return if (s.length > DIALOG_MESSAGE_MAX) {
            capChars(s, DIALOG_MESSAGE_MAX) + "…"
        } else {
            s
        }
    }

    /**
     * How long a page-triggered approval dialog's positive
     * button stays disarmed after the dialog appears (SEN-305).
     * The page controls WHEN the dialog appears (a navigation
     * it starts becomes a download; a getUserMedia call raises
     * the permission prompt), and the button sits at a fixed
     * spot, so a tap the user aimed at the page — one beat of a
     * rhythm, a double-tap — can land on "approve" before the
     * dialog has even registered. The delay is a human-factors
     * constant of the dialog itself (the user must have had the
     * chance to SEE what they are approving), not a guess about
     * asynchronous work completing.
     */
    const val APPROVAL_ARM_DELAY_MS = 1000L

    /**
     * Whether an approval given at [nowMs] for a dialog shown
     * at [shownAtMs] may be honored: only once the arm delay
     * has passed. A never-shown dialog (shownAtMs = 0) is never
     * armed. The Android side disables the button for the same
     * interval; this predicate is the enforcement the click
     * handlers check, so the rule holds even if a view state
     * change re-enables the button early.
     */
    fun approvalArmed(shownAtMs: Long, nowMs: Long): Boolean =
        shownAtMs > 0 && nowMs - shownAtMs >= APPROVAL_ARM_DELAY_MS

    /** The JavaScript dialogs a page can raise (SEN-301). */
    enum class JsDialogKind {
        /** alert(): one acknowledgement button. */
        ALERT,

        /** confirm(): approve / cancel. */
        CONFIRM,

        /**
         * beforeunload: leaving loses page state. Confirming
         * means "leave", the reverse of the other two kinds.
         */
        BEFORE_UNLOAD
    }

    /**
     * The URL handed to the new tab for an OPEN_TAB popup: the
     * scheme lowercased, everything else untouched. The
     * classification above is case-insensitive, but the
     * consumer (loadSavedUrl's web-link check) is not, so the
     * value that was approved is normalized into the form the
     * consumer accepts (SEN-313).
     */
    fun normalizePopupTarget(url: String): String {
        val schemeEnd = url.indexOf("://")
        return if (schemeEnd > 0) {
            url.substring(0, schemeEnd).lowercase() + url.substring(schemeEnd)
        } else {
            url
        }
    }
}
