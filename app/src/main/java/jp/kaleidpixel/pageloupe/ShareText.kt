package jp.kaleidpixel.pageloupe

/**
 * What the menu footer's share button sends to the system
 * share sheet (owner decision, 2026-11-23): the current page's
 * title and URL, as plain text.
 *
 * Pure functions only (no Android types), so the rules are
 * covered by JVM unit tests (tests/ShareTextTest.kt).
 *
 * Only http(s) pages are shareable. The start page (its URL is
 * the internal pageloupe: marker), about:blank, data: pages and
 * a tab with nothing loaded carry no URL another app could
 * open, so they get no share text at all -- the footer button
 * is disabled for them instead of sharing an internal string.
 */
object ShareText {

    /**
     * Caps that keep one share far inside the Binder
     * transaction budget (~1MB, shared process-wide) the
     * chooser Intent travels through: an uncapped page title
     * or URL is page-controlled text, and a few hundred KB of
     * it would make startActivity throw instead of sharing.
     * 8192 + 200 chars is under 17KB as UTF-16.
     */
    const val MAX_URL_CHARS = 8192
    const val MAX_TITLE_CHARS = 200

    /** Input bound for one flattening pass (see titleForShare). */
    const val MAX_SCAN_CHARS = 8192

    /** True when [url] is a page the share button may send. */
    fun isShareable(url: String): Boolean =
        isWebLink(url) && url.length <= MAX_URL_CHARS

    /**
     * The title as it may go into a share, or null when no
     * title may be used: the caller says the tab's title does
     * not belong to the URL being shared (its attribution --
     * TitleAttribution -- names another page, or none: a
     * previous page's title carried over a navigation, or the
     * placeholder title of a tab whose page has not reported
     * one), the flattened title is empty, or it is just the
     * URL itself.
     *
     * Flattening is a per-code-point pass, not a regex: what
     * counts as a line break differs between regex engines
     * (JVM vs Android's ICU), Unicode has line separators
     * (U+2028/U+2029, NEL) that an ASCII-only \s never
     * matches, and supplementary-plane format characters
     * (the tag characters used for ASCII smuggling, musical
     * format marks) are invisible to a UTF-16 char pass.
     * Here, every whitespace, space/line/paragraph separator
     * and control character collapses to a single space, and
     * the invisible payload carriers are dropped: format
     * characters (bidi controls, tags, joiners), variation
     * selectors (which have their own smuggling technique),
     * Hangul filler characters, and unpaired surrogates. A
     * crafted title therefore cannot push the URL off the
     * text, disguise extra lines or invisible instructions
     * in those carriers as part of the share, or reorder
     * what the receiver sees. The scan is bounded twice --
     * it stops once enough flattened text exists to fill
     * the cap, and never scans more than [MAX_SCAN_CHARS]
     * of input -- so a huge title costs a bounded pass; the
     * result is truncated to [MAX_TITLE_CHARS] (never
     * splitting a surrogate pair, never leaving a trailing
     * space the cut exposed).
     */
    fun titleForShare(title: String, url: String, titleBelongsToUrl: Boolean): String? {
        if (!titleBelongsToUrl) return null
        if (title == url) return null
        val (flat, complete) = flatten(title)
        if (flat.isEmpty()) return null
        if (complete && flat == url) return null
        return truncate(flat)
    }

    /** One share, computed once: the text and its title. */
    data class Payload(val text: String, val title: String?)

    /**
     * The share for a page, or null when the page is not
     * shareable: text is "<title>\n<url>" with the usable
     * title (see [titleForShare]) on its own line above the
     * URL, or the URL alone when there is no usable title;
     * title is that same usable title (for the chooser's
     * preview extra), or null.
     */
    fun payload(title: String, url: String, titleBelongsToUrl: Boolean): Payload? {
        if (!isShareable(url)) return null
        val t = titleForShare(title, url, titleBelongsToUrl)
        return Payload(if (t == null) url else "$t\n$url", t)
    }

    /**
     * Code points dropped outright (see titleForShare): the
     * Default_Ignorable_Code_Point ranges that are not
     * already covered by the FORMAT category -- variation
     * selectors (BMP, plus the Mongolian ones), CGJ, the
     * Khmer inherent marks, and Hangul fillers, and the
     * single wide range U+E0000..U+E0FFF: it covers the tag
     * characters (assigned and unassigned alike, so the set
     * does not depend on the runtime's Unicode version) AND
     * the supplementary variation selectors
     * (U+E0100..U+E01EF), which live inside it. Do not narrow
     * that range to the tag characters proper (up to
     * U+E007F) -- the variation selectors would silently
     * leak through again. A global UNASSIGNED drop is NOT used:
     * it would eat characters simply newer than the
     * runtime's Unicode table (recent emoji on an older
     * JVM), while the smuggling-prone unassigned area (the
     * tag range) is covered explicitly above.
     */
    private fun isDroppedCodePoint(cp: Int): Boolean =
        cp in 0xFE00..0xFE0F ||
            cp in 0x180B..0x180F || cp in 0xE0000..0xE0FFF ||
            cp == 0x2065 || cp in 0xFFF0..0xFFF8 ||
            cp == 0x034F || cp == 0x17B4 || cp == 0x17B5 ||
            cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0

    /**
     * The flattened title and whether the scan ran to the end
     * of the input (false when it stopped early at a cap --
     * the caller must not compare a partial flattening
     * against the full URL).
     */
    private fun flatten(title: String): Pair<String, Boolean> {
        val sb = StringBuilder(minOf(title.length, MAX_TITLE_CHARS + 1))
        val limit = minOf(title.length, MAX_SCAN_CHARS)
        var i = 0
        while (i < limit) {
            if (sb.length > MAX_TITLE_CHARS) return sb.toString() to false
            val cp = Character.codePointAt(title, i)
            i += Character.charCount(cp)
            when {
                isDroppedCodePoint(cp) -> Unit
                else -> when (Character.getType(cp).toByte()) {
                    Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR, Character.CONTROL -> {
                        if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                    }
                    // FORMAT, and SURROGATE (a code point in
                    // the surrogate range here is unpaired by
                    // construction: paired ones are consumed
                    // as a single supplementary code point).
                    Character.FORMAT, Character.SURROGATE -> Unit
                    else -> sb.appendCodePoint(cp)
                }
            }
        }
        if (sb.lastOrNull() == ' ') sb.deleteCharAt(sb.length - 1)
        return sb.toString() to (i >= title.length)
    }

    private fun truncate(s: String): String {
        if (s.length <= MAX_TITLE_CHARS) return s
        var end = MAX_TITLE_CHARS
        if (s[end - 1].isHighSurrogate()) end--
        return s.substring(0, end).trimEnd(' ')
    }
}
