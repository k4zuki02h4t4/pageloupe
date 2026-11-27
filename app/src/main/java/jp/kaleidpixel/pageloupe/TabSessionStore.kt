package jp.kaleidpixel.pageloupe

import org.json.JSONArray
import org.json.JSONObject

/**
 * One tab in the saved open-tab session. [url] is the savable
 * form of the tab's URL (see [savableTabUrl]); an empty URL
 * restores as the home page, keeping the tab's position and
 * mode.
 */
data class SavedTab(
    val url: String,
    val secret: Boolean,
    val desktop: Boolean,
)

/** The saved open-tab session: every tab plus the active one. */
data class SavedSession(
    val tabs: List<SavedTab>,
    val activeIndex: Int,
)

/**
 * The URL form that may be written to a saved state: the home
 * marker for home tabs, the URL itself for http(s) links within
 * the length cap, and "" for anything else (data:, blob:,
 * over-cap and other unsavable URLs restore as home). This is
 * the same rule the instance-state Bundle applies; the session
 * codec below additionally keeps secret tabs' URLs, which the
 * system-held Bundle never does (owner decision, 2026-11-21 —
 * the session file is app-private storage, the Bundle is not).
 */
fun savableTabUrl(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    if (isHomeUrl(url)) return HOME_URL_MARKER
    return if (isWebLink(url) && url.length <= TabSessionCodec.MAX_URL_LENGTH) url else ""
}

/**
 * Serializer for the open-tab session file (owner decision,
 * 2026-11-21: open tabs persist across a cold start, secret
 * tabs included — by URL only; the secret profile itself is
 * still wiped at startup, so a restored secret tab starts
 * logged out).
 *
 * The caps mirror the instance-state Bundle's: each URL at most
 * [MAX_URL_LENGTH] characters and the whole list at most
 * [MAX_TOTAL_LENGTH], spent in tab order; a tab whose URL does
 * not fit stays in the list with an empty URL so positions and
 * the active index survive.
 *
 * Pure JVM (org.json aside) so the rules are unit-testable.
 */
object TabSessionCodec {

    const val MAX_URL_LENGTH = 2048
    const val MAX_TOTAL_LENGTH = 16_384
    private const val FORMAT_VERSION = 1

    /**
     * Encode the current tabs. [tabs] carries each tab's raw
     * current (or deferred) URL; the savable form and the
     * budget are applied here.
     */
    fun encode(tabs: List<SavedTab>, activeIndex: Int): String {
        var budget = MAX_TOTAL_LENGTH
        val arr = JSONArray()
        for (t in tabs) {
            var u = savableTabUrl(t.url)
            if (u.length > budget) u = ""
            budget -= u.length
            arr.put(
                JSONObject()
                    .put("u", u)
                    .put("s", if (t.secret) 1 else 0)
                    .put("d", if (t.desktop) 1 else 0),
            )
        }
        return JSONObject()
            .put("v", FORMAT_VERSION)
            .put("active", activeIndex)
            .put("tabs", arr)
            .toString()
    }

    /**
     * Parse a document written by [encode]. Tolerant: a corrupt
     * file yields null (the caller starts fresh), each URL is
     * re-validated through [savableTabUrl], and the active
     * index is clamped into range. A document with no tabs
     * yields null.
     */
    fun decode(json: String): SavedSession? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val arr = root.optJSONArray("tabs") ?: return null
        val tabs = ArrayList<SavedTab>(arr.length())
        var budget = MAX_TOTAL_LENGTH
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            var u = savableTabUrl(obj.optString("u", ""))
            if (u.length > budget) u = ""
            budget -= u.length
            tabs.add(
                SavedTab(
                    url = u,
                    secret = obj.optInt("s", 0) == 1,
                    desktop = obj.optInt("d", 0) == 1,
                ),
            )
        }
        if (tabs.isEmpty()) return null
        val active = root.optInt("active", 0).coerceIn(0, tabs.size - 1)
        return SavedSession(tabs, active)
    }
}
