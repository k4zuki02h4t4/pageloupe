package jp.kaleidpixel.pageloupe

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One recorded visit in the browsing history: the page URL, the
 * title as it stood when the load finished, and when it happened.
 */
data class HistoryEntry(
    val url: String,
    val title: String,
    val visitedAtMillis: Long,
)

/**
 * A day's worth of history entries for the history page, newest
 * first within the day.
 */
data class HistoryDayGroup(
    val date: LocalDate,
    val entries: List<HistoryEntry>,
)

/**
 * Deletion ranges offered on the clear-data page. The range
 * applies to the browsing history only (owner decision,
 * 2026-11-21): cookies, cache and exported logs can only be
 * wiped wholesale by the platform APIs behind them.
 */
enum class HistoryPeriod {
    LAST_HOUR,
    LAST_24_HOURS,
    LAST_7_DAYS,
    ALL,
    ;

    /**
     * The instant from which entries are deleted, or null when
     * the range is unbounded ([ALL]).
     */
    fun cutoffMillis(nowMillis: Long): Long? = when (this) {
        LAST_HOUR -> nowMillis - 3_600_000L
        LAST_24_HOURS -> nowMillis - 86_400_000L
        LAST_7_DAYS -> nowMillis - 7 * 86_400_000L
        ALL -> null
    }
}

/**
 * The browsing history: an in-memory, newest-first list of
 * visits backed by a JSON document the activity persists.
 *
 * Recording policy lives with the caller (MainActivity records
 * committed page loads of non-secret tabs only); this class owns
 * the list rules:
 *
 * - A load of the same URL as the newest entry refreshes that
 *   entry's title and timestamp instead of stacking a duplicate
 *   row (reloads and late title fixes stay one row).
 * - There is no cap on the number of entries (owner
 *   decision, 2026-11-28, aligning with ordinary browsers:
 *   every visit is kept, including same-document commits —
 *   pushState and friends record like any other visit).
 *   The earlier design capped the list at 5000 and replaced
 *   the newest entry on a same-document commit so one page
 *   looping pushState could not flush the rest of the
 *   history at the cap (SEN-387); with the cap gone the
 *   replacement went with it. The accepted residual is
 *   the mirror image: a page looping URL changes grows
 *   the file by one entry per change, the same exposure
 *   any browser's history has to visit count.
 * - Deletion by period removes entries at or after the cutoff
 *   and never touches anything else — open tabs and the saved
 *   tab session are separate stores (owner decision, 2026-11-21).
 *
 * Pure JVM (org.json aside) so the rules are unit-testable. Not
 * thread-safe: every call site runs on the UI thread, and the
 * activity serializes persistence snapshots itself.
 */
class HistoryStore {

    private val entries = ArrayDeque<HistoryEntry>()

    val size: Int get() = entries.size

    /** The newest entry, or null when the history is empty. */
    val newest: HistoryEntry? get() = entries.firstOrNull()

    fun isEmpty(): Boolean = entries.isEmpty()

    /** Newest-first copy of the current entries. */
    fun snapshot(): List<HistoryEntry> = entries.toList()

    /**
     * Record one visit. A visit to the same URL as the current
     * newest entry replaces that entry (title and timestamp
     * move to the new visit).
     *
     * Both fields are page-controlled, so their size is capped
     * here: a URL longer than [MAX_URL_LENGTH] is not recorded
     * at all (the tab session applies the same cap), and the
     * title is truncated to [MAX_TITLE_LENGTH]. The per-entry
     * bound stays even though the entry count is uncapped:
     * one entry must never be able to dominate the file.
     */
    fun record(url: String, title: String, visitedAtMillis: Long) {
        if (url.length > MAX_URL_LENGTH) return
        val newest = entries.firstOrNull()
        if (newest != null && newest.url == url) {
            entries.removeFirst()
        }
        entries.addFirst(
            HistoryEntry(url, title.take(MAX_TITLE_LENGTH), visitedAtMillis),
        )
    }

    /**
     * Remove the entry at [index] in [snapshot] order (0 is the
     * newest). Out-of-range indices are ignored.
     */
    fun removeAt(index: Int) {
        if (index in entries.indices) entries.removeAt(index)
    }

    /**
     * Remove this exact entry instance (identity, not value
     * equality — two visits can share URL, title and even
     * timestamp). Returns whether it was present.
     */
    fun removeEntry(entry: HistoryEntry): Boolean {
        val idx = entries.indexOfFirst { it === entry }
        if (idx < 0) return false
        entries.removeAt(idx)
        return true
    }

    /** How many entries a [deleteSince] with this cutoff would remove. */
    fun countSince(cutoffMillis: Long?): Int {
        if (cutoffMillis == null) return entries.size
        return entries.count { it.visitedAtMillis >= cutoffMillis }
    }

    /**
     * Remove every entry visited at or after [cutoffMillis];
     * a null cutoff removes everything. Returns how many
     * entries were removed.
     */
    fun deleteSince(cutoffMillis: Long?): Int {
        if (cutoffMillis == null) {
            val removed = entries.size
            entries.clear()
            return removed
        }
        val before = entries.size
        entries.removeAll { it.visitedAtMillis >= cutoffMillis }
        return before - entries.size
    }

    fun clear() {
        entries.clear()
    }

    /**
     * Entries whose title or URL contains [query]
     * (case-insensitive), newest first. A blank query returns
     * the full snapshot.
     */
    fun search(query: String): List<HistoryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return snapshot()
        return entries.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.url.contains(q, ignoreCase = true)
        }
    }

    /** Serialize this store for persistence. Newest first. */
    fun toJson(): String = toJson(entries)

    companion object {
        /** URLs longer than this are not recorded (cf. TabSessionCodec). */
        const val MAX_URL_LENGTH = 2048

        /** Titles are stored truncated to this length. */
        const val MAX_TITLE_LENGTH = 512
        private const val FORMAT_VERSION = 1

        /** Serialize [entries] (newest first) in the file format. */
        fun toJson(entries: List<HistoryEntry>): String {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(
                    JSONObject()
                        .put("u", e.url)
                        .put("t", e.title)
                        .put("at", e.visitedAtMillis),
                )
            }
            return JSONObject().put("v", FORMAT_VERSION).put("entries", arr).toString()
        }

        /**
         * Parse a document written by [toJson]. Tolerant by
         * design: a corrupt file yields an empty store rather
         * than a crash and malformed entries are skipped.
         * Entries keep their file order (newest first).
         */
        fun fromJson(json: String): HistoryStore =
            parseDocument(json) ?: HistoryStore()

        /**
         * Parse a document written by [toJson], or null when
         * the document is corrupt AT THE ROOT: not a JSON
         * object, a format version this build does not write,
         * or no entries array. Entry-level damage is not
         * corruption — malformed entries are skipped, as
         * [fromJson] documents. The distinction is what lets
         * the caller evacuate a root-corrupt file instead of
         * letting the next save silently overwrite the only
         * copy of the data (SEN-2).
         */
        fun parseDocument(json: String): HistoryStore? {
            // Exception-only on purpose (review round 3,
            // PER-2): runCatching would also catch an
            // OutOfMemoryError and report a huge but valid
            // file as corrupt, evacuating it. An Error
            // propagates to the loader, which treats it as a
            // read failure (writes blocked, file untouched).
            val root = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            if (root.optInt("v", -1) != FORMAT_VERSION) return null
            val arr = root.optJSONArray("entries") ?: return null
            val store = HistoryStore()
            // The file is newest-first; appending in file order
            // rebuilds that order.
            // Entries are re-validated on the way in (a web link
            // within the recording caps), so a damaged or
            // hand-edited file cannot resurrect values the
            // recorder itself would never have written.
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("u", "")
                val at = obj.optLong("at", 0L)
                if (url.isEmpty() || at <= 0L) continue
                if (!isWebLink(url) || url.length > MAX_URL_LENGTH) continue
                store.entries.addLast(
                    HistoryEntry(
                        url,
                        obj.optString("t", "").take(MAX_TITLE_LENGTH),
                        at,
                    ),
                )
            }
            return store
        }
    }
}

/**
 * Group [entries] (newest first) by their local calendar day in
 * [zone], newest day first. The input order is preserved within
 * each day.
 */
fun historyDayGroups(entries: List<HistoryEntry>, zone: ZoneId): List<HistoryDayGroup> {
    val byDate = LinkedHashMap<LocalDate, MutableList<HistoryEntry>>()
    for (e in entries) {
        val date = Instant.ofEpochMilli(e.visitedAtMillis).atZone(zone).toLocalDate()
        byDate.getOrPut(date) { mutableListOf() }.add(e)
    }
    return byDate.entries
        .sortedByDescending { it.key }
        .map { HistoryDayGroup(it.key, it.value) }
}
