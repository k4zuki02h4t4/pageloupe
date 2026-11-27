package tests

import jp.kaleidpixel.pageloupe.HOME_URL_MARKER
import jp.kaleidpixel.pageloupe.HistoryPeriod
import jp.kaleidpixel.pageloupe.HistoryStore
import jp.kaleidpixel.pageloupe.historyDayGroups
import java.time.ZoneId

// JVM tests for HistoryStore: the list rules behind the
// browsing-history page and the period-scoped deletion
// (SEN-154). Runs with the test-only JSON-java jar ahead of
// android.jar, like RecorderJsonTest, because the store's
// persistence side uses org.json. Run via tests/run_tests.sh.

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
    // Recording: newest first, snapshot order.
    val store = HistoryStore()
    check("new store is empty", store.isEmpty() && store.size == 0)
    store.record("https://a.example/", "A", 1_000L)
    store.record("https://b.example/", "B", 2_000L)
    check("two entries recorded", store.size == 2)
    check(
        "newest first",
        store.snapshot().map { it.url } ==
            listOf("https://b.example/", "https://a.example/"),
    )

    // A revisit of the newest URL refreshes it in place.
    store.record("https://b.example/", "B2", 3_000L)
    check("revisit of newest does not stack", store.size == 2)
    check(
        "revisit refreshes title and time",
        store.snapshot()[0].title == "B2" &&
            store.snapshot()[0].visitedAtMillis == 3_000L,
    )

    // A revisit of an older URL is a new entry.
    store.record("https://a.example/", "A", 4_000L)
    check("revisit of older URL stacks", store.size == 3)
    check("older revisit lands on top", store.snapshot()[0].url == "https://a.example/")

    // removeAt follows snapshot order and tolerates bad indices.
    store.removeAt(0)
    check("removeAt drops the newest", store.snapshot()[0].url == "https://b.example/")
    store.removeAt(99)
    store.removeAt(-1)
    check("out-of-range removeAt is a no-op", store.size == 2)

    // Search: title and URL, case-insensitive; blank = all.
    check("search by title", store.search("b2").size == 1)
    check("search by url fragment", store.search("A.EXAMPLE").size == 1)
    check("blank search returns all", store.search("  ").size == 2)
    check("search miss is empty", store.search("zzz").isEmpty())

    // Period cutoffs.
    val now = 10 * 86_400_000L
    check("ALL has no cutoff", HistoryPeriod.ALL.cutoffMillis(now) == null)
    check(
        "LAST_HOUR cutoff",
        HistoryPeriod.LAST_HOUR.cutoffMillis(now) == now - 3_600_000L,
    )
    check(
        "LAST_24_HOURS cutoff",
        HistoryPeriod.LAST_24_HOURS.cutoffMillis(now) == now - 86_400_000L,
    )
    check(
        "LAST_7_DAYS cutoff",
        HistoryPeriod.LAST_7_DAYS.cutoffMillis(now) == now - 7 * 86_400_000L,
    )

    // deleteSince: at-or-after the cutoff goes, older stays.
    val ranged = HistoryStore()
    ranged.record("https://old.example/", "old", 1_000L)
    ranged.record("https://edge.example/", "edge", 5_000L)
    ranged.record("https://new.example/", "new", 9_000L)
    val removed = ranged.deleteSince(5_000L)
    check("deleteSince removes at-or-after", removed == 2)
    check(
        "deleteSince keeps older entries",
        ranged.snapshot().map { it.url } == listOf("https://old.example/"),
    )
    check("deleteSince null clears all", ranged.deleteSince(null) == 1 && ranged.isEmpty())

    // Cap: the oldest entries fall off beyond MAX_ENTRIES.
    val capped = HistoryStore()
    for (i in 0 until HistoryStore.MAX_ENTRIES + 5) {
        capped.record("https://e$i.example/", "t$i", i.toLong())
    }
    check("cap holds", capped.size == HistoryStore.MAX_ENTRIES)
    check(
        "cap drops the oldest",
        capped.snapshot().last().url == "https://e5.example/",
    )
    check(
        "cap keeps the newest",
        capped.snapshot().first().url ==
            "https://e${HistoryStore.MAX_ENTRIES + 4}.example/",
    )

    // JSON round trip preserves order, titles and timestamps.
    val original = HistoryStore()
    original.record("https://a.example/", "A \"quoted\" <title>", 111L)
    original.record("https://b.example/path?q=1", "", 222L)
    val restored = HistoryStore.fromJson(original.toJson())
    check("json round trip size", restored.size == 2)
    check(
        "json round trip content",
        restored.snapshot() == original.snapshot(),
    )

    // Corrupt documents degrade to an empty store, never a crash.
    check("garbage json is empty", HistoryStore.fromJson("not json").isEmpty())
    check("empty json is empty", HistoryStore.fromJson("").isEmpty())
    check(
        "missing entries key is empty",
        HistoryStore.fromJson("""{"v":1}""").isEmpty(),
    )
    val mixed = HistoryStore.fromJson(
        """{"v":1,"entries":[{"u":"https://ok.example/","t":"ok","at":5},""" +
            """{"t":"no url","at":6},{"u":"https://bad.example/","at":0},42]}""",
    )
    check(
        "malformed entries are skipped",
        mixed.size == 1 && mixed.snapshot()[0].url == "https://ok.example/",
    )

    // Day grouping: newest day first, input order within a day.
    val utc = ZoneId.of("UTC")
    val day1 = 1_704_067_200_000L // 2024-01-01T00:00:00Z
    val grouped = historyDayGroups(
        listOf(
            jp.kaleidpixel.pageloupe.HistoryEntry("https://n.example/", "n", day1 + 86_400_000L + 1_000L),
            jp.kaleidpixel.pageloupe.HistoryEntry("https://m.example/", "m", day1 + 86_400_000L),
            jp.kaleidpixel.pageloupe.HistoryEntry("https://o.example/", "o", day1 + 1_000L),
        ),
        utc,
    )
    check("two day groups", grouped.size == 2)
    check("newest day first", grouped[0].entries.size == 2 && grouped[1].entries.size == 1)
    check(
        "group dates descend",
        grouped[0].date.isAfter(grouped[1].date),
    )
    check("empty input groups to nothing", historyDayGroups(emptyList(), utc).isEmpty())

    // Recording caps: over-long URLs are refused, titles are
    // truncated (the page controls both).
    val capped2 = HistoryStore()
    val longUrl = "https://a.example/" + "x".repeat(HistoryStore.MAX_URL_LENGTH)
    capped2.record(longUrl, "too long", 1_000L)
    check("over-cap url is not recorded", capped2.isEmpty())
    capped2.record("https://a.example/", "t".repeat(600), 1_000L)
    check(
        "title is truncated to the cap",
        capped2.newest?.title?.length == HistoryStore.MAX_TITLE_LENGTH,
    )

    // fromJson re-validates: non-web links and over-cap URLs
    // are dropped even from a hand-written file.
    val hostile = HistoryStore.fromJson(
        """{"v":1,"entries":[{"u":"javascript:alert(1)","t":"x","at":5},""" +
            """{"u":"https://ok.example/","t":"ok","at":6}]}""",
    )
    check(
        "fromJson drops non-web links",
        hostile.size == 1 && hostile.newest?.url == "https://ok.example/",
    )

    // countSince mirrors deleteSince without copying.
    val counted = HistoryStore()
    counted.record("https://a.example/", "a", 100L)
    counted.record("https://b.example/", "b", 200L)
    counted.record("https://c.example/", "c", 300L)
    check("countSince null counts all", counted.countSince(null) == 3)
    check("countSince boundary is at-or-after", counted.countSince(200L) == 2)
    check("countSince future counts none", counted.countSince(400L) == 0)

    // removeEntry removes by identity: an equal-valued twin
    // survives.
    val twins = HistoryStore()
    twins.record("https://a.example/", "same", 100L)
    twins.record("https://b.example/", "other", 200L)
    val twinEntry = jp.kaleidpixel.pageloupe.HistoryEntry("https://a.example/", "same", 100L)
    check(
        "removeEntry ignores an equal-valued twin",
        !twins.removeEntry(twinEntry) && twins.size == 2,
    )
    check(
        "removeEntry removes the stored instance",
        twins.removeEntry(twins.snapshot()[1]) && twins.size == 1,
    )

    // The home marker constant is pinned so it cannot drift
    // away from BrowserTab.HOME_URL silently.
    check("home marker value", HOME_URL_MARKER == "pageloupe://newtab/")

    if (failures > 0) {
        println("HistoryStoreTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("HistoryStoreTest: all $checks checks passed")
}
