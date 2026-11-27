package tests

import jp.kaleidpixel.pageloupe.SavedTab
import jp.kaleidpixel.pageloupe.TabSessionCodec
import jp.kaleidpixel.pageloupe.savableTabUrl

// JVM tests for the open-tab session codec (SEN-154): the
// savable-URL rule, the length caps and tolerant decoding.
// Runs with the test-only JSON-java jar ahead of android.jar,
// like RecorderJsonTest, because the codec uses org.json.
// Run via the runUnitTests Gradle task.

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
    // The savable-URL rule.
    check("https url is kept", savableTabUrl("https://a.example/") == "https://a.example/")
    check("http url is kept", savableTabUrl("http://a.example/") == "http://a.example/")
    check(
        "home url becomes the marker",
        savableTabUrl("pageloupe://newtab/") == "pageloupe://newtab/",
    )
    check("blank is empty", savableTabUrl("") == "" && savableTabUrl(null) == "")
    check("data url is dropped", savableTabUrl("data:text/html,hi") == "")
    check("blob url is dropped", savableTabUrl("blob:https://a.example/x") == "")
    check("about blank is dropped", savableTabUrl("about:blank") == "")
    val atCap = "https://a.example/" + "x".repeat(TabSessionCodec.MAX_URL_LENGTH - 18)
    check("url at the cap is kept", savableTabUrl(atCap) == atCap)
    check("url over the cap is dropped", savableTabUrl(atCap + "x") == "")

    // Round trip: urls, modes and the active index survive.
    val tabs = listOf(
        SavedTab("https://a.example/", secret = false, desktop = false),
        SavedTab("https://b.example/", secret = true, desktop = true),
        SavedTab("pageloupe://newtab/", secret = false, desktop = false),
    )
    val decoded = TabSessionCodec.decode(TabSessionCodec.encode(tabs, 1))
    check("round trip succeeds", decoded != null)
    check("round trip tabs", decoded?.tabs == tabs)
    check("round trip active", decoded?.activeIndex == 1)

    // Secret tabs keep their URL in the session file (the
    // system-held Bundle blanks them; this file does not).
    check(
        "secret url is kept",
        decoded?.tabs?.get(1)?.url == "https://b.example/" &&
            decoded?.tabs?.get(1)?.secret == true,
    )

    // Unsavable URLs keep their slot as blanks.
    val withBlank = TabSessionCodec.decode(
        TabSessionCodec.encode(
            listOf(
                SavedTab("data:text/html,hi", secret = false, desktop = false),
                SavedTab("https://a.example/", secret = false, desktop = false),
            ),
            1,
        ),
    )
    check(
        "unsavable url stays as a blank slot",
        withBlank?.tabs?.size == 2 && withBlank?.tabs?.get(0)?.url == "",
    )
    check("blank slot keeps active index", withBlank?.activeIndex == 1)

    // Total budget: URLs past the budget blank out in order,
    // earlier ones are untouched.
    val big = "https://a.example/" + "y".repeat(2_000 - 18)
    val many = (0 until 10).map { SavedTab(big, secret = false, desktop = false) }
    val budgeted = TabSessionCodec.decode(TabSessionCodec.encode(many, 0))
    check("budget keeps all slots", budgeted?.tabs?.size == 10)
    check(
        "budget blanks the tail",
        budgeted?.tabs?.count { it.url.isNotEmpty() } ==
            TabSessionCodec.MAX_TOTAL_LENGTH / 2_000,
    )

    // Active index is clamped into range.
    val clamped = TabSessionCodec.decode(
        """{"v":1,"active":9,"tabs":[{"u":"https://a.example/","s":0,"d":0}]}""",
    )
    check("active index clamps", clamped?.activeIndex == 0)
    val negative = TabSessionCodec.decode(
        """{"v":1,"active":-3,"tabs":[{"u":"https://a.example/","s":0,"d":0}]}""",
    )
    check("negative active clamps", negative?.activeIndex == 0)

    // Corrupt documents decode to null, never a crash.
    check("garbage decodes to null", TabSessionCodec.decode("nope") == null)
    check("empty decodes to null", TabSessionCodec.decode("") == null)
    check(
        "no tabs decodes to null",
        TabSessionCodec.decode("""{"v":1,"active":0,"tabs":[]}""") == null,
    )

    // Decode re-validates URLs through the same rule.
    val hostile = TabSessionCodec.decode(
        """{"v":1,"active":0,"tabs":[{"u":"javascript:alert(1)","s":0,"d":0},""" +
            """{"u":"https://ok.example/","s":1,"d":0}]}""",
    )
    check(
        "decode drops unsavable urls to blanks",
        hostile?.tabs?.get(0)?.url == "" &&
            hostile?.tabs?.get(1)?.url == "https://ok.example/",
    )

    // isCorruptDocument draws the SEN-2 line: a written
    // document (or a well-formed one with no savable tabs)
    // is not corruption; a broken root is.
    check(
        "a written document is not corrupt",
        !TabSessionCodec.isCorruptDocument(TabSessionCodec.encode(tabs, 0))
    )
    check(
        "garbage is corrupt",
        TabSessionCodec.isCorruptDocument("not json")
    )
    check(
        "a foreign version is corrupt",
        TabSessionCodec.isCorruptDocument("""{"v":99,"tabs":[]}""")
    )
    check(
        "a missing tabs array is corrupt",
        TabSessionCodec.isCorruptDocument("""{"v":1}""")
    )
    check(
        "an empty but well-formed document is not corrupt",
        !TabSessionCodec.isCorruptDocument("""{"v":1,"tabs":[]}""")
    )

    if (failures > 0) {
        println("TabSessionStoreTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("TabSessionStoreTest: all $checks checks passed")
}
