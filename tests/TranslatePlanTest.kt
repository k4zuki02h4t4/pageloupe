package tests

import jp.kaleidpixel.pageloupe.TranslatePlan

// JVM tests for the pure decisions behind page translation
// (v0.41.0): node splitting, source-language gating, the
// detection sample and the work caps. The ML Kit calls and
// the in-page scripts are device-verified; what is decided
// before and after them is pinned here.
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

fun main() {
    // -- split / reassemble -----------------------------------
    check(
        "plain text is all core",
        TranslatePlan.split("Hello world") ==
            TranslatePlan.Split("", "Hello world", "")
    )
    check(
        "surrounding whitespace is layout, not content",
        TranslatePlan.split("  Hello\n") ==
            TranslatePlan.Split("  ", "Hello", "\n")
    )
    check(
        "inner whitespace stays in the core",
        TranslatePlan.split("a  b") == TranslatePlan.Split("", "a  b", "")
    )
    check(
        "blank node has nothing to translate",
        TranslatePlan.split("  \n\t ") == null
    )
    check(
        "digits and punctuation alone are skipped",
        TranslatePlan.split(" 12,300! ") == null
    )
    check(
        "a single letter among digits is enough",
        TranslatePlan.split("v2.0") == TranslatePlan.Split("", "v2.0", "")
    )
    check(
        "CJK text counts as letters",
        TranslatePlan.split(" 本文 ") ==
            TranslatePlan.Split(" ", "本文", " ")
    )
    check(
        "full-width space is whitespace",
        TranslatePlan.split("\u3000本文\u3000") ==
            TranslatePlan.Split("\u3000", "本文", "\u3000")
    )
    // (U+3000 ideographic spaces, written as escapes)
    check(
        "reassemble restores the node's shape",
        TranslatePlan.reassemble(TranslatePlan.Split("  ", "Hello", "\n"), "こんにちは") ==
            "  こんにちは\n"
    )

    // -- decideSource -----------------------------------------
    check(
        "english proceeds",
        TranslatePlan.decideSource("en", "") ==
            TranslatePlan.SourceDecision(TranslatePlan.SourceKind.PROCEED, "en")
    )
    check(
        "regional tag proceeds with the full tag",
        TranslatePlan.decideSource("zh-CN", "") ==
            TranslatePlan.SourceDecision(TranslatePlan.SourceKind.PROCEED, "zh-cn")
    )
    check(
        "japanese is already the target",
        TranslatePlan.decideSource("ja", "") ==
            TranslatePlan.SourceDecision(TranslatePlan.SourceKind.ALREADY_TARGET, "ja")
    )
    check(
        "an unsupported language is refused",
        TranslatePlan.decideSource("mn", "") ==
            TranslatePlan.SourceDecision(TranslatePlan.SourceKind.UNSUPPORTED, "mn")
    )
    check(
        "und falls back to the document lang",
        TranslatePlan.decideSource("und", "ko") ==
            TranslatePlan.SourceDecision(TranslatePlan.SourceKind.PROCEED, "ko")
    )
    check(
        "null detection falls back to the document lang",
        TranslatePlan.decideSource(null, "fr-FR") ==
            TranslatePlan.SourceDecision(TranslatePlan.SourceKind.PROCEED, "fr-fr")
    )
    check(
        "no signal at all is unknown",
        TranslatePlan.decideSource("und", "") ==
            TranslatePlan.SourceDecision(TranslatePlan.SourceKind.UNKNOWN, null)
    )
    check(
        "document lang in japanese is already the target",
        TranslatePlan.decideSource(null, "ja-JP") ==
            TranslatePlan.SourceDecision(TranslatePlan.SourceKind.ALREADY_TARGET, "ja-jp")
    )
    check(
        "underscores and case are normalized",
        TranslatePlan.decideSource("EN_US", "") ==
            TranslatePlan.SourceDecision(TranslatePlan.SourceKind.PROCEED, "en-us")
    )

    check(
        "legacy hebrew tag folds to the modern form and proceeds",
        TranslatePlan.decideSource("iw", "").kind == TranslatePlan.SourceKind.PROCEED
    )
    check(
        "the returned tag carries the folded primary",
        TranslatePlan.decideSource("iw", "").tag == "he" &&
            TranslatePlan.decideSource("nb", "").tag == "no"
    )
    check(
        "legacy filipino tag proceeds",
        TranslatePlan.decideSource("fil", "").kind == TranslatePlan.SourceKind.PROCEED
    )
    check(
        "romaji japanese is unsupported (the model reads the native script)",
        TranslatePlan.decideSource("ja-Latn", "").kind == TranslatePlan.SourceKind.UNSUPPORTED
    )
    check(
        "latin-script hindi is unsupported",
        TranslatePlan.decideSource("hi-Latn", "").kind == TranslatePlan.SourceKind.UNSUPPORTED
    )
    check(
        "latin-script english still proceeds",
        TranslatePlan.decideSource("en-Latn", "").kind == TranslatePlan.SourceKind.PROCEED
    )
    check(
        "latin-script german still proceeds",
        TranslatePlan.decideSource("de-Latn", "").kind == TranslatePlan.SourceKind.PROCEED
    )
    check(
        "japanese with a non-latin script stays already-target",
        TranslatePlan.decideSource("ja-Jpan", "").kind ==
            TranslatePlan.SourceKind.ALREADY_TARGET
    )

    // -- isTranslatableUrl / requiredModels --------------------
    check(
        "only http(s) pages are translatable",
        TranslatePlan.isTranslatableUrl("https://example.com/") &&
            TranslatePlan.isTranslatableUrl("http://example.com/") &&
            !TranslatePlan.isTranslatableUrl("pageloupe://newtab/") &&
            !TranslatePlan.isTranslatableUrl("")
    )
    check(
        "english source needs source and target models only",
        TranslatePlan.requiredModels("en") == setOf("en", "ja")
    )
    check(
        "other sources add the english pivot model",
        TranslatePlan.requiredModels("ko") == setOf("ko", "ja", "en")
    )

    // -- detectionSample --------------------------------------
    check(
        "sample joins cores with spaces",
        TranslatePlan.detectionSample(listOf("Hello", "world")) == "Hello world"
    )
    check(
        "sample is bounded near the cap",
        TranslatePlan.detectionSample(List(2000) { "ab" }).length <=
            TranslatePlan.DETECTION_SAMPLE_CHARS + 3
    )
    check(
        "short cores join fully",
        TranslatePlan.detectionSample(listOf("ab", "cd", "ef")) == "ab cd ef"
    )
    check(
        "empty sample stays empty",
        TranslatePlan.detectionSample(emptyList()) == ""
    )

    // -- buildWork --------------------------------------------
    val work = TranslatePlan.buildWork(
        listOf("Hello", "  ", "123", " World "),
        "My Title"
    )
    check(
        "work keeps translatable nodes with their indices",
        work.items.map { it.nodeIndex } == listOf(0, 3, TranslatePlan.TITLE_INDEX)
    )
    check("work under the caps is not truncated", !work.truncated)
    check(
        "title rides as its own item",
        work.items.last().split.core == "My Title"
    )
    val noTitle = TranslatePlan.buildWork(listOf("Hello"), "  ")
    check(
        "a blank title adds no item",
        noTitle.items.map { it.nodeIndex } == listOf(0)
    )
    val many = TranslatePlan.buildWork(List(TranslatePlan.MAX_NODES + 50) { "word$it" }, "")
    check(
        "the node cap truncates and says so",
        many.items.size == TranslatePlan.MAX_NODES && many.truncated
    )
    val heavy = TranslatePlan.buildWork(listOf("x".repeat(TranslatePlan.MAX_CHARS + 1)), "")
    check(
        "a single node past the char cap truncates to nothing",
        heavy.items.isEmpty() && heavy.truncated
    )
    check(
        "the script's own cap flag marks the work truncated",
        TranslatePlan.buildWork(listOf("Hello"), "", scriptCapped = true).truncated
    )

    // -- mixed-language planning (v0.42.0) ---------------------
    check(
        "requiredModelsFor unions the per-language sets",
        TranslatePlan.requiredModelsFor(listOf("fr", "de")) ==
            setOf("fr", "de", "ja", "en")
    )
    check(
        "requiredModelsFor with English needs no pivot",
        TranslatePlan.requiredModelsFor(listOf("en")) == setOf("en", "ja")
    )
    check(
        "groupSample joins cores and cuts at its cap",
        TranslatePlan.groupSample(listOf("ab", "cd")).length == 5 &&
            TranslatePlan.groupSample(listOf("x".repeat(600))).length ==
            TranslatePlan.GROUP_SAMPLE_CHARS
    )
    check(
        "dominantScriptFamily reads kana, hangul and cyrillic",
        TranslatePlan.dominantScriptFamily("これはテストです") ==
            TranslatePlan.ScriptFamily.KANA &&
            TranslatePlan.dominantScriptFamily("이것은 테스트입니다") ==
            TranslatePlan.ScriptFamily.HANGUL &&
            TranslatePlan.dominantScriptFamily("Это тестовая страница") ==
            TranslatePlan.ScriptFamily.CYRILLIC
    )
    check(
        "dominantScriptFamily stays out of latin and mixed text",
        TranslatePlan.dominantScriptFamily("This is a test") == null &&
            TranslatePlan.dominantScriptFamily("test テスト test") == null &&
            TranslatePlan.dominantScriptFamily("12345") == null
    )

    // -- planRun ------------------------------------------------
    val proceedEn = TranslatePlan.SourceDecision(TranslatePlan.SourceKind.PROCEED, "en")
    val proceedFr = TranslatePlan.SourceDecision(TranslatePlan.SourceKind.PROCEED, "fr")
    val mixedPlan = TranslatePlan.planRun(
        itemGroups = listOf(0L, 0L, 1L, 2L),
        groupChars = mapOf(0L to 500, 1L to 300, 2L to 100),
        docDecision = proceedEn,
        groupDecisions = mapOf(0L to proceedEn, 1L to proceedFr),
        resolvedLanguages = mapOf(0L to "en", 1L to "fr", 2L to "en"),
        guardedFamilies = emptyMap(),
    )
    check(
        "planRun routes each block to its own language; unidentified blocks follow the document",
        mixedPlan.languages == listOf("en", "fr") &&
            mixedPlan.itemLanguages == listOf("en", "en", "fr", "en") &&
            !mixedPlan.hasSkippedBlocks
    )
    val jaCollapse = TranslatePlan.planRun(
        itemGroups = listOf(0L),
        groupChars = mapOf(0L to 400),
        docDecision = proceedEn,
        groupDecisions = mapOf(0L to proceedFr),
        resolvedLanguages = mapOf(0L to "ja"),
        guardedFamilies = emptyMap(),
    )
    check(
        "planRun counts a block resolving onto the target as target text",
        jaCollapse.languages.isEmpty() && jaCollapse.targetChars == 400 &&
            TranslatePlan.emptyNotice(jaCollapse) ==
            TranslatePlan.EmptyNotice.ALREADY_TARGET
    )
    val unmappable = TranslatePlan.planRun(
        itemGroups = listOf(0L),
        groupChars = mapOf(0L to 400),
        docDecision = proceedEn,
        groupDecisions = mapOf(0L to proceedFr),
        resolvedLanguages = mapOf(0L to null),
        guardedFamilies = emptyMap(),
    )
    check(
        "planRun counts an unmappable block as unsupported",
        unmappable.languages.isEmpty() && unmappable.unsupportedChars == 400 &&
            TranslatePlan.emptyNotice(unmappable) ==
            TranslatePlan.EmptyNotice.UNSUPPORTED
    )
    val guardedPlan = TranslatePlan.planRun(
        itemGroups = listOf(0L, 1L),
        groupChars = mapOf(0L to 500, 1L to 80),
        docDecision = proceedEn,
        groupDecisions = emptyMap(),
        resolvedLanguages = mapOf(0L to "en"),
        guardedFamilies = mapOf(1L to TranslatePlan.ScriptFamily.CYRILLIC),
    )
    check(
        "planRun leaves a script-guarded fallback block untranslated and says the run is partial",
        guardedPlan.itemLanguages == listOf("en", null) && guardedPlan.hasSkippedBlocks
    )
    val jaWithSmallForeign = TranslatePlan.planRun(
        itemGroups = listOf(0L, 1L),
        groupChars = mapOf(0L to 900, 1L to 40),
        docDecision = TranslatePlan.SourceDecision(
            TranslatePlan.SourceKind.ALREADY_TARGET, "ja"
        ),
        groupDecisions = mapOf(
            1L to TranslatePlan.SourceDecision(TranslatePlan.SourceKind.UNSUPPORTED, "mn")
        ),
        resolvedLanguages = emptyMap(),
        guardedFamilies = emptyMap(),
    )
    check(
        "a mostly-target page with one small unsupported block still reports the target",
        jaWithSmallForeign.languages.isEmpty() &&
            TranslatePlan.emptyNotice(jaWithSmallForeign) ==
            TranslatePlan.EmptyNotice.ALREADY_TARGET
    )
    val unknownOnly = TranslatePlan.planRun(
        itemGroups = listOf(0L),
        groupChars = mapOf(0L to 100),
        docDecision = TranslatePlan.SourceDecision(TranslatePlan.SourceKind.UNKNOWN, null),
        groupDecisions = emptyMap(),
        resolvedLanguages = emptyMap(),
        guardedFamilies = emptyMap(),
    )
    check(
        "planRun with nothing decidable reports unknown",
        unknownOnly.languages.isEmpty() &&
            TranslatePlan.emptyNotice(unknownOnly) ==
            TranslatePlan.EmptyNotice.UNKNOWN
    )
    check(
        "dominantScriptFamily reads han-only text",
        TranslatePlan.dominantScriptFamily("会社概要") ==
            TranslatePlan.ScriptFamily.HAN
    )
    check(
        "sanitizeGroups keeps a well-formed list and drops the rest",
        TranslatePlan.sanitizeGroups(listOf(0, 1, 1), 3) == listOf(0, 1, 1) &&
            TranslatePlan.sanitizeGroups(listOf(0, -1), 2).isEmpty() &&
            TranslatePlan.sanitizeGroups(listOf(0, 2), 2).isEmpty() &&
            TranslatePlan.sanitizeGroups(listOf(0), 2).isEmpty()
    )
    check(
        "isFallback and effectiveDecision share one definition",
        TranslatePlan.isFallback(null) &&
            TranslatePlan.isFallback(
                TranslatePlan.SourceDecision(TranslatePlan.SourceKind.UNKNOWN, null)
            ) &&
            !TranslatePlan.isFallback(proceedEn) &&
            TranslatePlan.effectiveDecision(null, proceedFr) == proceedFr &&
            TranslatePlan.effectiveDecision(proceedEn, proceedFr) == proceedEn
    )
    val kanaGuarded = TranslatePlan.planRun(
        itemGroups = listOf(0L, 1L),
        groupChars = mapOf(0L to 500, 1L to 120),
        docDecision = proceedEn,
        groupDecisions = emptyMap(),
        resolvedLanguages = mapOf(0L to "en", 1L to "en"),
        guardedFamilies = mapOf(1L to TranslatePlan.ScriptFamily.KANA),
    )
    check(
        "a kana-guarded block counts as target text and does not mark the run partial",
        kanaGuarded.itemLanguages == listOf("en", null) &&
            kanaGuarded.targetChars == 120 && !kanaGuarded.hasSkippedBlocks
    )
    val unknownRemainder = TranslatePlan.planRun(
        itemGroups = listOf(0L, 1L),
        groupChars = mapOf(0L to 500, 1L to 90),
        docDecision = TranslatePlan.SourceDecision(TranslatePlan.SourceKind.UNKNOWN, null),
        groupDecisions = mapOf(0L to proceedFr),
        resolvedLanguages = mapOf(0L to "fr"),
        guardedFamilies = emptyMap(),
    )
    check(
        "blocks left undecided mark the run partial",
        unknownRemainder.languages == listOf("fr") &&
            unknownRemainder.unknownChars == 90 && unknownRemainder.hasSkippedBlocks
    )
    val mostlyUnknown = TranslatePlan.planRun(
        itemGroups = listOf(0L, 1L),
        groupChars = mapOf(0L to 5000, 1L to 10),
        docDecision = TranslatePlan.SourceDecision(TranslatePlan.SourceKind.UNKNOWN, null),
        groupDecisions = mapOf(
            1L to TranslatePlan.SourceDecision(TranslatePlan.SourceKind.UNSUPPORTED, "mn")
        ),
        resolvedLanguages = emptyMap(),
        guardedFamilies = emptyMap(),
    )
    check(
        "emptyNotice compares all three weights, not just the target",
        TranslatePlan.emptyNotice(mostlyUnknown) ==
            TranslatePlan.EmptyNotice.UNKNOWN
    )
    val capDrop = TranslatePlan.planRun(
        itemGroups = (0 until 9).map { it.toLong() },
        groupChars = (0 until 9).associate { it.toLong() to (100 - it) },
        docDecision = proceedEn,
        groupDecisions = (0 until 9).associate {
            it.toLong() to TranslatePlan.SourceDecision(TranslatePlan.SourceKind.PROCEED, "l$it")
        },
        resolvedLanguages = (0 until 9).associate { it.toLong() to "l$it" },
        guardedFamilies = emptyMap(),
    )
    check(
        "planRun drops languages past the cap and marks the run partial",
        capDrop.languages.size == TranslatePlan.MAX_SOURCE_LANGUAGES &&
            capDrop.droppedLanguages == listOf("l8") &&
            capDrop.itemLanguages[8] == null && capDrop.hasSkippedBlocks
    )
    val titleKey = TranslatePlan.subGroupKeys(listOf(-1), listOf(40))[0]
    val titleRouted = TranslatePlan.planRun(
        itemGroups = listOf(0L, titleKey),
        groupChars = mapOf(0L to 300, titleKey to 40),
        docDecision = proceedEn,
        groupDecisions = mapOf(titleKey to proceedFr),
        resolvedLanguages = mapOf(0L to "en", titleKey to "fr"),
        guardedFamilies = emptyMap(),
    )
    check(
        "the title group routes by its own decision",
        titleRouted.itemLanguages == listOf("en", "fr")
    )
    check(
        "subGroupKeys splits an oversized block and keeps blocks apart",
        TranslatePlan.subGroupKeys(
            listOf(0, 0, 0, 1, 1),
            listOf(600, 600, 600, 2000, 10),
        ).let { keys ->
            keys[0] == keys[1] && keys[1] != keys[2] &&
                keys[3] != keys[4] && keys[0] != keys[3]
        }
    )
    check(
        "subGroupKeys of small blocks is one key per block",
        TranslatePlan.subGroupKeys(listOf(0, 0, 1), listOf(10, 10, 10)).let {
            it[0] == it[1] && it[1] != it[2]
        }
    )
    check(
        "guardFamily guards only a foreign script against a known document language",
        TranslatePlan.guardFamily("Это тестовая страница", "en") ==
            TranslatePlan.ScriptFamily.CYRILLIC &&
            TranslatePlan.guardFamily("Это тестовая страница", "ru") == null &&
            TranslatePlan.guardFamily("This is a test", "en") == null &&
            TranslatePlan.guardFamily("これはテストです", null) == null
    )
    check(
        "guardFamily reads a kanji-heavy sample with any kana as Japanese",
        TranslatePlan.guardFamily("会社概要と沿革について", "en") ==
            TranslatePlan.ScriptFamily.KANA &&
            TranslatePlan.guardFamily("会社概要と沿革について", "ja") == null
    )
    check(
        "subGroupKeys continues a block across separated stretches",
        TranslatePlan.subGroupKeys(
            listOf(0, 1, 0, 0),
            listOf(700, 100, 700, 700),
        ).let { keys ->
            // Block 0 continues across the gap: its two 700s
            // share sub 0 (the cap is checked before adding),
            // and the third crosses into sub 1 -- the keys do
            // not restart at sub 0 after block 1 intervenes.
            keys[0] == keys[2] && keys[2] != keys[3] && keys[1] != keys[0]
        }
    )
    val subKeys = TranslatePlan.subGroupKeys(listOf(0, 0, 0), listOf(600, 600, 100))
    check(
        "inheritDecisions fills an unidentified tail from its block's previous sub-group",
        TranslatePlan.inheritDecisions(
            subKeys,
            mapOf(subKeys[0] to proceedFr),
        ).let { filled ->
            filled[subKeys[1]] == proceedFr && filled[subKeys[2]] == proceedFr
        }
    )
    check(
        "inheritDecisions keeps a group's own decision, UNKNOWN included",
        TranslatePlan.inheritDecisions(
            subKeys,
            mapOf(
                subKeys[0] to proceedFr,
                subKeys[1] to TranslatePlan.SourceDecision(
                    TranslatePlan.SourceKind.UNKNOWN, null
                ),
            ),
        )[subKeys[1]]!!.kind == TranslatePlan.SourceKind.UNKNOWN
    )
    check(
        "inheritedGroups is exactly the keys inheritance filled in",
        TranslatePlan.inheritedGroups(
            mapOf(subKeys[0] to proceedFr),
            mapOf(subKeys[0] to proceedFr, subKeys[2] to proceedFr),
        ) == setOf(subKeys[2])
    )
    val inputs = TranslatePlan.planInputs(
        groupKeys = setOf(0L, 1L),
        groupCores = mapOf(
            0L to listOf("Bonjour le monde"),
            1L to listOf("Это тестовая страница"),
        ),
        docDecision = proceedEn,
        groupDecisions = mapOf(0L to proceedFr),
        resolver = { tag -> if (tag == "und") null else tag },
    )
    check(
        "planInputs resolves proceed blocks and guards fallback blocks",
        inputs.resolvedLanguages[0L] == "fr" &&
            inputs.resolvedLanguages[1L] == "en" &&
            inputs.guardedFamilies[1L] == TranslatePlan.ScriptFamily.CYRILLIC &&
            !inputs.guardedFamilies.containsKey(0L)
    )
    val inheritedInputs = TranslatePlan.planInputs(
        groupKeys = setOf(0L, 1L),
        groupCores = mapOf(
            0L to listOf("Bonjour le monde"),
            1L to listOf("Это тест"),
        ),
        docDecision = proceedEn,
        groupDecisions = mapOf(0L to proceedFr, 1L to proceedFr),
        inheritedGroups = setOf(1L),
        resolver = { tag -> tag },
    )
    check(
        "planInputs guards an inherited block against its inherited language",
        inheritedInputs.guardedFamilies[1L] == TranslatePlan.ScriptFamily.CYRILLIC &&
            !inheritedInputs.guardedFamilies.containsKey(0L)
    )
    val inheritedGuarded = TranslatePlan.planRun(
        itemGroups = listOf(0L, 1L),
        groupChars = mapOf(0L to 500, 1L to 30),
        docDecision = proceedEn,
        groupDecisions = mapOf(0L to proceedFr, 1L to proceedFr),
        resolvedLanguages = mapOf(0L to "fr", 1L to "fr"),
        guardedFamilies = mapOf(1L to TranslatePlan.ScriptFamily.CYRILLIC),
    )
    check(
        "planRun leaves a guarded inherited block untranslated",
        inheritedGuarded.itemLanguages == listOf("fr", null) &&
            inheritedGuarded.unknownChars == 30 && inheritedGuarded.hasSkippedBlocks
    )
    check(
        "selectLanguages orders by total characters",
        TranslatePlan.selectLanguages(
            mapOf(0L to "fr", 1L to "de", 2L to "fr"),
            mapOf(0L to 100, 1L to 300, 2L to 50),
        ) == listOf("de", "fr")
    )
    check(
        "selectLanguages breaks ties on the code",
        TranslatePlan.selectLanguages(
            mapOf(0L to "fr", 1L to "de"),
            mapOf(0L to 100, 1L to 100),
        ) == listOf("de", "fr")
    )
    val manyLangs = (0 until 12).associate { it.toLong() to "l$it" }
    val manyChars = (0 until 12).associate { it.toLong() to (100 - it) }
    check(
        "selectLanguages caps at MAX_SOURCE_LANGUAGES, smallest dropped",
        TranslatePlan.selectLanguages(manyLangs, manyChars).let {
            it.size == TranslatePlan.MAX_SOURCE_LANGUAGES && it.last() == "l7"
        }
    )
    check(
        "selectLanguages of nothing is empty",
        TranslatePlan.selectLanguages(emptyMap(), emptyMap()).isEmpty()
    )

    println("TranslatePlanTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
