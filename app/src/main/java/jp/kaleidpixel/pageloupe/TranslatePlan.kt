package jp.kaleidpixel.pageloupe

/**
 * The pure decisions behind page translation (v0.41.0): which
 * text nodes are worth translating, how a node's surrounding
 * whitespace survives the round trip, which detected language
 * may proceed to the on-device translator, and where the work
 * is capped. Everything here is free of Android and ML Kit
 * types so the JVM tests pin the rules directly; the ML Kit
 * orchestration and the in-page scripts live in PageTranslator.
 *
 * The translation target is always Japanese (owner decision,
 * 2026-11-27: the UI is Japanese; a target setting belongs to
 * the future full settings page). The source is detected --
 * per block since v0.42.0, because a page that mixes
 * languages (the canonical example.com carries six) fed
 * through the one document language's model left the other
 * blocks garbled or untouched. Blocks in the target language
 * or an unsupported one are left exactly as they are.
 */
object TranslatePlan {

    const val TARGET_LANGUAGE = "ja"

    /** Upper bounds for one translation run. A page past the
     * caps is translated up to the cap and the truncation is
     * surfaced to the user, never silent. */
    const val MAX_NODES = 2000
    const val MAX_CHARS = 300_000

    /** How much text the language identifier gets: its accuracy
     * plateaus well before this, and a bounded sample keeps the
     * detection step O(1) in page size. */
    const val DETECTION_SAMPLE_CHARS = 1000

    /** How much of one block the identifier gets when the page
     * is checked block by block. A block is one paragraph or
     * cell, so less text suffices than for the whole document. */
    const val GROUP_SAMPLE_CHARS = 500

    /** Blocks shorter than this are not worth an identifier
     * call of their own (a heading, a short link): they take
     * the document-level decision instead. */
    const val GROUP_DETECTION_MIN_CHARS = 20

    /** The most distinct source languages one run translates.
     * A page past this keeps its smallest languages (by
     * character count) untranslated rather than prompting for
     * an unbounded set of model downloads; eight covers any
     * realistic mixed page (example.com has six). */
    const val MAX_SOURCE_LANGUAGES = 8

    /** The most characters one block group accumulates
     * before [subGroupKeys] starts a sub-group. A block is
     * the unit of identification, but a page can put a whole
     * mixed document inside one block element (or use no
     * block elements at all); sub-groups keep the decision
     * local -- each is identified on its own sample and
     * routed on its own decision. */
    const val MAX_GROUP_CHARS = 1000

    /** One language model's approximate size in megabytes,
     * the number the download consent quotes. */
    const val MODEL_SIZE_MB = 30

    /** The most blocks one run identifies individually.
     * Past this the largest blocks win and the rest take
     * the document decision: the identification phase is
     * bounded like every other phase of the run. */
    const val MAX_DETECTION_TARGETS = 150

    /** Node index used for the document title, which is not a
     * text node in the walk. */
    const val TITLE_INDEX = -1

    /**
     * A text node split into the whitespace around its
     * translatable core. Only the core is sent to the
     * translator: leading and trailing whitespace is layout,
     * and feeding it through the model risks it being eaten or
     * duplicated. [reassemble] puts it back afterwards.
     */
    data class Split(val lead: String, val core: String, val trail: String)

    /**
     * The split for [raw], or null when the node carries
     * nothing to translate: blank, or no letter at all (pure
     * digits, punctuation, symbols and emoji pass through a
     * translator unchanged, so spending model calls on them
     * buys nothing).
     */
    fun split(raw: String): Split? {
        val start = raw.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return null
        val end = raw.indexOfLast { !it.isWhitespace() }
        val core = raw.substring(start, end + 1)
        if (core.none { it.isLetter() }) return null
        return Split(raw.substring(0, start), core, raw.substring(end + 1))
    }

    fun reassemble(split: Split, translatedCore: String): String =
        split.lead + translatedCore + split.trail

    enum class SourceKind { PROCEED, ALREADY_TARGET, UNSUPPORTED, UNKNOWN }

    data class SourceDecision(val kind: SourceKind, val tag: String?)

    /** Whether a URL can host a translation run at all: a
     * web page over http(s). The menu's enabled state and the
     * run's own gate both read this one predicate. */
    fun isTranslatableUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    /**
     * The language models a source-to-Japanese run needs on
     * the device: the source's, the target's, and -- for pairs
     * with no direct model -- English as ML Kit's pivot. The
     * model-presence check and the download consent both work
     * from this set, so what the user agrees to matches what
     * the library will fetch.
     */
    fun requiredModels(source: String): Set<String> {
        val models = linkedSetOf(source, TARGET_LANGUAGE)
        if (source != TARGET_LANGUAGE && source != PIVOT_LANGUAGE) {
            models.add(PIVOT_LANGUAGE)
        }
        return models
    }

    /** The model set for a mixed-language run: the union over
     * every source language, so the consent prompt names
     * exactly what the library will fetch. */
    fun requiredModelsFor(sources: Collection<String>): Set<String> {
        val models = linkedSetOf<String>()
        for (source in sources) models.addAll(requiredModels(source))
        return models
    }

    /** Split the work items' block groups into sub-groups
     * of at most [MAX_GROUP_CHARS] accumulated characters
     * (a single item larger than the cap forms a sub-group
     * of its own). Returns one key per item, parallel to the
     * inputs: the block id in the high bits, the sub-group
     * index in the low bits. Items stay in document order
     * within a block, so a language change inside one
     * oversized block lands in a later sub-group with its
     * own identification. */
    fun subGroupKeys(itemGroups: List<Int>, itemChars: List<Int>): List<Long> {
        val keys = ArrayList<Long>(itemGroups.size)
        // Per-block state, not a running run: the same block
        // can appear in separated stretches (a div's own text
        // around a nested paragraph), and each stretch
        // continues the block's current sub-group rather than
        // restarting -- and colliding with -- its first one.
        val state = HashMap<Int, IntArray>() // block -> [sub, accumulated chars]
        for (i in itemGroups.indices) {
            val g = itemGroups[i]
            val st = state.getOrPut(g) { intArrayOf(0, 0) }
            if (st[1] >= MAX_GROUP_CHARS) {
                st[0]++
                st[1] = 0
            }
            st[1] += itemChars[i]
            keys.add((g.toLong() shl 32) or (st[0].toLong() and 0xFFFF_FFFFL))
        }
        return keys
    }

    /** Fill in the decisions of sub-groups that were never
     * identified from the previous sub-group of the same
     * block. Splitting one block at [MAX_GROUP_CHARS] can
     * leave a tail sub-group too small to identify on its
     * own; it is the same block's text continuing, so the
     * block's own preceding decision is better evidence for
     * it than the document decision. [keysInOrder] is every
     * group key in document order; groups with a decision of
     * their own (UNKNOWN included) keep it. */
    fun inheritDecisions(
        keysInOrder: List<Long>,
        decisions: Map<Long, SourceDecision>,
    ): Map<Long, SourceDecision> {
        if (decisions.isEmpty()) return decisions
        val result = HashMap<Long, SourceDecision>(decisions)
        val lastByBlock = HashMap<Long, SourceDecision>()
        for (key in keysInOrder) {
            val block = key shr 32
            val own = decisions[key]
            if (own != null) {
                lastByBlock[block] = own
            } else {
                val inherited = lastByBlock[block]
                if (inherited != null) result[key] = inherited
            }
        }
        return result
    }

    /** The group keys that gained a decision from
     * [inheritDecisions]: present in [after], absent in
     * [before]. planInputs guards these against the language
     * they inherited. */
    fun inheritedGroups(
        before: Map<Long, SourceDecision>,
        after: Map<Long, SourceDecision>,
    ): Set<Long> = after.keys.filter { g -> before[g] == null && after[g] != null }.toSet()

    /** The inputs of [planRun] that the caller assembles:
     * each PROCEED block's resolved language, and the
     * script-guard family of each block whose text was never
     * identified (fallback and inherited blocks only -- the
     * membership of [guardedFamilies] is what makes a block
     * guard-eligible in planRun). */
    data class PlanInputs(
        val resolvedLanguages: Map<Long, String?>,
        val guardedFamilies: Map<Long, ScriptFamily>,
    )

    /** Assemble the [PlanInputs] for one run. [resolver] is
     * the library's fromLanguageTag (injected so this stays
     * pure and testable); it is consulted once per distinct
     * decided tag. The document language the guard compares
     * against is the document decision's resolved form, like
     * everything else downstream. */
    fun planInputs(
        groupKeys: Set<Long>,
        groupCores: Map<Long, List<String>>,
        docDecision: SourceDecision,
        groupDecisions: Map<Long, SourceDecision>,
        inheritedGroups: Set<Long> = emptySet(),
        resolver: (String) -> String?,
    ): PlanInputs {
        val resolvedCache = HashMap<String, String?>()
        fun resolve(tag: String): String? {
            if (!resolvedCache.containsKey(tag)) {
                resolvedCache[tag] = resolver(tag)
            }
            return resolvedCache[tag]
        }
        val docLanguage = if (docDecision.kind == SourceKind.PROCEED) {
            resolve(docDecision.tag ?: "")
        } else {
            null
        }
        val resolved = HashMap<Long, String?>()
        val guardedFamilies = HashMap<Long, ScriptFamily>()
        for (g in groupKeys) {
            val own = groupDecisions[g]
            val decision = effectiveDecision(own, docDecision)
            if (decision.kind == SourceKind.PROCEED) {
                resolved[g] = resolve(decision.tag ?: "")
            }
            // The guard covers the groups whose text was
            // never identified: the fallback groups (against
            // the document language they would be fed to) and
            // the inherited groups (against the language they
            // inherited). A group the identifier decided for
            // itself is never guarded -- its identification is
            // stronger evidence than its script.
            // A null base (the document language did not
            // resolve, or an inherited decision that feeds
            // no model) guards nothing: guardFamily would
            // return null for it anyway.
            val guardBase = when {
                isFallback(own) -> docLanguage
                g in inheritedGroups -> resolved[g]
                else -> null
            }
            if (guardBase != null) {
                val family = guardFamily(
                    groupSample(groupCores[g] ?: emptyList()),
                    guardBase,
                )
                if (family != null) guardedFamilies[g] = family
            }
        }
        return PlanInputs(resolved, guardedFamilies)
    }

    /**
     * Choose the source languages a mixed-language run will
     * translate: [groupLanguages] maps each block to the
     * language it was decided to be, [groupChars] sizes the
     * blocks. Languages are ordered by their total character
     * count (ties break on the code, so the result is
     * deterministic) and capped at MAX_SOURCE_LANGUAGES;
     * blocks in a language past the cap stay untranslated.
     */
    fun selectLanguages(
        groupLanguages: Map<Long, String>,
        groupChars: Map<Long, Int>,
    ): List<String> {
        val totals = HashMap<String, Int>()
        for ((group, language) in groupLanguages) {
            totals[language] = (totals[language] ?: 0) + (groupChars[group] ?: 0)
        }
        return totals.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_SOURCE_LANGUAGES)
            .map { it.key }
    }

    /** ML Kit's pivot language for pairs with no direct model. */
    private const val PIVOT_LANGUAGE = "en"

    /**
     * Decide the source language from the identifier's BCP-47
     * tag, falling back to the document's lang attribute when
     * detection returned nothing usable ("und" or null). The
     * returned tag is the full normalized tag; the caller maps
     * it through ML Kit's fromLanguageTag and treats a null
     * there as UNSUPPORTED too (this plan's allowlist is the
     * documented ML Kit language set, so the two should agree;
     * the library stays the final arbiter). Legacy primary
     * subtags the identifier may emit (iw, fil, nb, nn) are
     * folded to their modern forms first, and the returned
     * tag carries the folded primary, so the value handed to
     * fromLanguageTag is the modern code too. Text in the
     * Latin script of a language whose model expects its
     * native script ("ja-Latn" romaji, "hi-Latn" Hinglish) is
     * UNSUPPORTED: the model cannot read that script, and for
     * Japanese the tag would also collapse the source onto
     * the target. Native-script Japanese (no script subtag,
     * or a non-Latin one) is ALREADY_TARGET.
     */
    fun decideSource(detectedTag: String?, htmlLang: String?): SourceDecision {
        val tag = normalizeTag(detectedTag) ?: normalizeTag(htmlLang)
            ?: return SourceDecision(SourceKind.UNKNOWN, null)
        val subtags = tag.split('-')
        val primary = PRIMARY_ALIASES[subtags[0]] ?: subtags[0]
        val foldedTag = (listOf(primary) + subtags.drop(1)).joinToString("-")
        val script = subtags.getOrNull(1)?.takeIf { it.length == 4 }
        return when {
            primary == TARGET_LANGUAGE && script != "latn" ->
                SourceDecision(SourceKind.ALREADY_TARGET, foldedTag)
            script == "latn" && primary in NON_LATIN_SCRIPT_PRIMARIES ->
                SourceDecision(SourceKind.UNSUPPORTED, foldedTag)
            primary !in SUPPORTED_PRIMARY_SUBTAGS ->
                SourceDecision(SourceKind.UNSUPPORTED, foldedTag)
            else -> SourceDecision(SourceKind.PROCEED, foldedTag)
        }
    }

    /** Supported languages whose ML Kit model expects a
     * non-Latin native script: a "-Latn" tag for one of these
     * names text the model cannot read. */
    private val NON_LATIN_SCRIPT_PRIMARIES = setOf(
        "am", "ar", "be", "bg", "bn", "el", "fa", "gu", "he", "hi",
        "hy", "ja", "ka", "kk", "km", "kn", "ko", "ky", "lo", "mk",
        "ml", "mn", "mr", "my", "ne", "pa", "ru", "si", "sr", "ta",
        "te", "tg", "th", "uk", "ur", "yi", "zh",
    )

    /** Legacy primary subtags and their modern equivalents. */
    private val PRIMARY_ALIASES = mapOf(
        "iw" to "he",
        "fil" to "tl",
        "nb" to "no",
        "nn" to "no",
    )

    private fun normalizeTag(tag: String?): String? {
        val t = tag?.trim()?.lowercase()?.replace('_', '-') ?: return null
        if (t.isEmpty() || t == "und") return null
        return t
    }

    /** Primary subtags of the languages ML Kit Translate
     * supports (the documented set for 17.0.x). Japanese is in
     * the set as a source too, but decideSource returns
     * ALREADY_TARGET for it before consulting this list. */
    private val SUPPORTED_PRIMARY_SUBTAGS = setOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de",
        "el", "en", "eo", "es", "et", "fa", "fi", "fr", "ga", "gl",
        "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja",
        "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl",
        "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw",
        "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    /** The text the language identifier sees: the first cores,
     * space-joined, up to DETECTION_SAMPLE_CHARS. */
    fun detectionSample(cores: List<String>): String = joinSample(cores, DETECTION_SAMPLE_CHARS)

    /** The text the identifier sees for one block: its cores,
     * space-joined, up to GROUP_SAMPLE_CHARS. */
    fun groupSample(cores: List<String>): String = joinSample(cores, GROUP_SAMPLE_CHARS)

    private fun joinSample(cores: List<String>, cap: Int): String {
        val sb = StringBuilder()
        for (core in cores) {
            if (sb.length >= cap) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(core)
        }
        // One long core can overshoot the cap on its own; the
        // cap is the contract, so the joined sample is cut to
        // it (a surrogate pair split at the cut is harmless
        // input for the identifier).
        if (sb.length > cap) sb.setLength(cap)
        return sb.toString()
    }

    /** Script families whose letters map to a small, known
     * set of languages. Used only to protect blocks that take
     * the document decision without an identification of
     * their own: a block visibly written in one of these
     * scripts cannot belong to a document language outside
     * the family, so feeding it to that language's model
     * would garble it; leaving it untranslated is the safe
     * side. Latin is deliberately absent -- too many
     * languages share it for the script to prove anything.
     * Han is present as a three-language family: within the
     * supported language set it narrows a block to Chinese,
     * Japanese or Korean, which still proves a negative
     * against any other document language. */
    enum class ScriptFamily(val languages: Set<String>) {
        KANA(setOf("ja")),
        HAN(setOf("zh", "ja", "ko")),
        HANGUL(setOf("ko")),
        HEBREW(setOf("he")),
        GREEK(setOf("el")),
        ARABIC(setOf("ar", "fa", "ur")),
        CYRILLIC(setOf("be", "bg", "kk", "ky", "mk", "mn", "ru", "sr", "tg", "uk")),
        DEVANAGARI(setOf("hi", "mr", "ne")),
        THAI(setOf("th")),
    }

    private val SCRIPT_FAMILIES = ScriptFamily.values()

    /** The dominant script family of [text]: the family
     * holding at least half of its letters, or null when no
     * family does (mixed text, or a script with no family). */
    fun dominantScriptFamily(text: String): ScriptFamily? {
        val counts = IntArray(SCRIPT_FAMILIES.size)
        var letters = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            letters++
            val family = when (ch.code) {
                in 0x3040..0x30FF -> ScriptFamily.KANA
                in 0x3400..0x4DBF, in 0x4E00..0x9FFF -> ScriptFamily.HAN
                in 0x1100..0x11FF, in 0xAC00..0xD7AF -> ScriptFamily.HANGUL
                in 0x0590..0x05FF -> ScriptFamily.HEBREW
                in 0x0370..0x03FF -> ScriptFamily.GREEK
                in 0x0600..0x06FF, in 0x0750..0x077F -> ScriptFamily.ARABIC
                in 0x0400..0x04FF -> ScriptFamily.CYRILLIC
                in 0x0900..0x097F -> ScriptFamily.DEVANAGARI
                in 0x0E00..0x0E7F -> ScriptFamily.THAI
                else -> null
            }
            if (family != null) counts[family.ordinal]++
        }
        if (letters == 0) return null
        val top = counts.indices.maxByOrNull { counts[it] } ?: return null
        return if (counts[top] * 2 >= letters) SCRIPT_FAMILIES[top] else null
    }

    /** Whether a block has no usable decision of its own and
     * falls back to the document decision: it was never
     * identified, or its identification came back UNKNOWN --
     * an identification that could not tell is weaker
     * evidence than the document sample. */
    fun isFallback(own: SourceDecision?): Boolean =
        own == null || own.kind == SourceKind.UNKNOWN

    /** A block's effective decision: its own, or the
     * document's when it falls back (see [isFallback]). The
     * one definition both the planner and its caller use. */
    fun effectiveDecision(own: SourceDecision?, docDecision: SourceDecision): SourceDecision =
        if (isFallback(own)) docDecision else own!!

    /** The script guard's verdict for one fallback block:
     * its dominant script family when that family cannot
     * belong to [docLanguage] (the block then stays
     * untranslated), or null when the block may take the
     * document language's model. A null [docLanguage] --
     * the document decision did not resolve to a language --
     * guards nothing. */
    fun guardFamily(sample: String, docLanguage: String?): ScriptFamily? {
        if (docLanguage == null) return null
        val dominant = dominantScriptFamily(sample) ?: return null
        // A Han-dominant sample containing any kana at all
        // is Japanese, not Chinese: kana exists only in
        // Japanese text. Counting it as KANA keeps a
        // kanji-heavy Japanese block on the target side of
        // the notices instead of the unknown side.
        val family = if (dominant == ScriptFamily.HAN &&
            sample.any { it.code in 0x3040..0x30FF }
        ) {
            ScriptFamily.KANA
        } else {
            dominant
        }
        return if (docLanguage in family.languages) null else family
    }

    /** The extraction script's group list, made safe to use:
     * the list itself when it lines up with the node list and
     * every value names a node, empty otherwise. The values
     * are page-controlled (the script's environment is the
     * page's), so one out-of-range id -- a negative colliding
     * with the title's group, or an invented one -- discards
     * the whole list and the run folds back to deciding as
     * one block. The range half of the check rests on an
     * invariant of the script: it issues at most one new
     * block id per collected node, so an honest id is
     * always below the node count. */
    fun sanitizeGroups(groups: List<Int>, nodeCount: Int): List<Int> =
        if (groups.size == nodeCount && groups.all { it in 0 until nodeCount }) {
            groups
        } else {
            emptyList()
        }

    /** The outcome of planning one run (see [planRun]). */
    data class RunPlan(
        /** Source languages to translate, largest first. */
        val languages: List<String>,

        /** The language for each item of the work list, or
         * null where the item's block stays untranslated.
         * Parallel to the caller's item list. */
        val itemLanguages: List<String?>,

        /** Character totals per non-translated outcome; the
         * empty-run notice is decided from these weights. */
        val targetChars: Int,
        val unsupportedChars: Int,
        val unknownChars: Int,

        /** Resolved languages dropped by the language cap. */
        val droppedLanguages: List<String>,

        /** Some blocks stay untranslated although the run
         * translates others (an unsupported block, a dropped
         * language, a script-guarded block): the result
         * notice must say the translation is partial. */
        val hasSkippedBlocks: Boolean,
    )

    enum class EmptyNotice { ALREADY_TARGET, UNSUPPORTED, UNKNOWN }

    /**
     * Plan one run from the per-block decisions.
     *
     * A block's effective decision is its own
     * ([groupDecisions]), unless it falls back to
     * [docDecision] (see [isFallback]). A block listed in
     * [guardedFamilies] (the unidentified blocks -- fallback
     * and inherited ones -- whose dominant script cannot
     * belong to the language they would be fed to) is left
     * untranslated instead of being fed to that language's
     * model; its characters still count toward the notices
     * -- as target text when the family is the target's own
     * script (kana: the block is Japanese, already readable),
     * and as unknown text otherwise.
     *
     * [resolvedLanguages] carries, for every block whose
     * effective decision is PROCEED, the language ML Kit's
     * fromLanguageTag resolved its tag to (null when
     * unmappable): the library is the final arbiter, and the
     * resolved value -- not the raw tag -- is what
     * translators, model sets and the language cap all work
     * with. A block resolving onto the target counts as
     * already-target text.
     */
    fun planRun(
        itemGroups: List<Long>,
        groupChars: Map<Long, Int>,
        docDecision: SourceDecision,
        groupDecisions: Map<Long, SourceDecision>,
        resolvedLanguages: Map<Long, String?>,
        guardedFamilies: Map<Long, ScriptFamily>,
    ): RunPlan {
        val groupLanguages = HashMap<Long, String>()
        var targetChars = 0
        var unsupportedChars = 0
        var unknownChars = 0
        var skipped = false
        for ((g, chars) in groupChars) {
            val own = groupDecisions[g]
            if (g in guardedFamilies) {
                if (guardedFamilies[g] == ScriptFamily.KANA) {
                    targetChars += chars
                } else {
                    unknownChars += chars
                    skipped = true
                }
                continue
            }
            val decision = effectiveDecision(own, docDecision)
            when (decision.kind) {
                SourceKind.PROCEED -> {
                    val lang = resolvedLanguages[g]
                    when {
                        lang == null -> unsupportedChars += chars
                        lang == TARGET_LANGUAGE -> targetChars += chars
                        else -> groupLanguages[g] = lang
                    }
                }
                SourceKind.ALREADY_TARGET -> targetChars += chars
                SourceKind.UNSUPPORTED -> unsupportedChars += chars
                SourceKind.UNKNOWN -> unknownChars += chars
            }
        }
        val selected = selectLanguages(groupLanguages, groupChars)
        val selectedSet = selected.toSet()
        val dropped = (groupLanguages.values.toSet() - selectedSet).sorted()
        if (dropped.isNotEmpty() || unsupportedChars > 0 || unknownChars > 0) skipped = true
        val itemLanguages = itemGroups.map { g ->
            groupLanguages[g]?.takeIf { it in selectedSet }
        }
        return RunPlan(
            selected, itemLanguages,
            targetChars, unsupportedChars, unknownChars,
            dropped, skipped,
        )
    }

    /** Which notice a run that translated nothing shows: the
     * outcome holding the most of the page's characters wins,
     * so a mostly-Japanese page with one small unsupported
     * block is a Japanese page, not an unsupported one. Ties
     * break toward the least alarming reading: target first,
     * then unsupported (a definite answer), then unknown. */
    fun emptyNotice(plan: RunPlan): EmptyNotice = when {
        plan.targetChars > 0 &&
            plan.targetChars >= plan.unsupportedChars &&
            plan.targetChars >= plan.unknownChars -> EmptyNotice.ALREADY_TARGET
        plan.unsupportedChars > 0 &&
            plan.unsupportedChars >= plan.unknownChars -> EmptyNotice.UNSUPPORTED
        else -> EmptyNotice.UNKNOWN
    }

    /** One unit of translation work: the node it belongs to and
     * its split. The title rides along with TITLE_INDEX. */
    data class WorkItem(val nodeIndex: Int, val split: Split)

    data class WorkList(val items: List<WorkItem>, val truncated: Boolean)

    /**
     * Build the work list from the extracted node texts (index
     * = position in [texts]) plus the document title. Nodes
     * with nothing to translate are skipped. The caps stop the
     * list mid-page if needed; [WorkList.truncated] reports
     * that so the UI can say the translation is partial.
     * [scriptCapped] carries the extraction script's own cap
     * flag: nodes past the script's limits were never
     * extracted, which is truncation too even when this list
     * itself stayed under the caps.
     */
    fun buildWork(texts: List<String>, title: String, scriptCapped: Boolean = false): WorkList {
        val items = ArrayList<WorkItem>()
        var chars = 0
        var truncated = scriptCapped
        fun offer(nodeIndex: Int, raw: String): Boolean {
            val s = split(raw) ?: return true
            if (items.size >= MAX_NODES || chars + s.core.length > MAX_CHARS) {
                truncated = true
                return false
            }
            items.add(WorkItem(nodeIndex, s))
            chars += s.core.length
            return true
        }
        for ((i, raw) in texts.withIndex()) {
            if (!offer(i, raw)) break
        }
        if (!truncated) offer(TITLE_INDEX, title)
        return WorkList(items, truncated)
    }
}
