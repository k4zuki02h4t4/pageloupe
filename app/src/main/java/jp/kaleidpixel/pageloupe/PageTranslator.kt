package jp.kaleidpixel.pageloupe

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** What the user is told when a translation run settles. The
 * wording lives in MainActivity; this class only decides. */
enum class TranslateNotice {
    NO_TEXT,
    ALREADY_TARGET,
    UNKNOWN_LANGUAGE,
    UNSUPPORTED_LANGUAGE,
    TRANSLATED,
    TRANSLATED_PARTIAL,
    TRANSLATED_WITH_SKIPS,
    PARTIALLY_FAILED,
    FAILED,
    MODEL_DOWNLOAD_FAILED,
    BUSY_OTHER_TAB,
}

/** Which phase the progress dialog is showing. */
enum class TranslateProgress { DETECTING, DOWNLOADING_MODEL, TRANSLATING }

/**
 * Whole-page, in-place translation for one tab (v0.41.0),
 * backed by on-device ML Kit: the page's text nodes are
 * extracted with a fixed script, translated on the device and
 * written back into the same nodes, so layout, links and
 * images stay where they were. The originals are snapshotted
 * by the script at extraction time and "show original" writes
 * them back without a reload. Page text itself never leaves
 * the device (ML Kit's NL APIs process input on-device; the
 * network traffic of this feature is the language-model
 * download, plus whatever usage telemetry Play services
 * exchanges for its own APIs, which carries no page content).
 *
 * Languages are decided per block (v0.42.0): the extraction
 * script tags every text node with its block-level container,
 * each block with enough text gets its own language
 * identification, and every block is translated with its own
 * language's model (or left alone when it is already Japanese
 * or unsupported). Feeding a mixed page through the single
 * document language's model -- the v0.41.0 behavior -- left
 * the other languages' blocks garbled or untouched, which is
 * what this fixes. Small blocks take the document-level
 * decision; the document sample is still identified first,
 * both as their fallback and as the run's failure floor.
 *
 * This class owns the run's state machine and the ML Kit
 * clients; all user interface (consent dialog, progress,
 * toasts) belongs to the Host, which is MainActivity. The pure
 * decisions (what is worth translating, language gating,
 * model set, caps) live in TranslatePlan.
 *
 * Injection discipline: the scripts are fixed templates
 * written by the app. Page-derived and model-derived text
 * reaches a script only as a JSON payload produced by
 * org.json, never by concatenation of raw text, and
 * extraction results are parsed with org.json, never
 * evaluated. The page-side state object lives under a
 * per-run random key, installed as a non-enumerable
 * property and frozen at install; the apply and
 * restore wrappers re-check the key and the document origin
 * before touching the payload, so a payload built for one
 * document cannot be delivered to another. The expected
 * origin is derived from the tab's committed URL on the
 * Kotlin side, never from the page's own report, and opaque
 * origins are refused. The freeze and the random key protect
 * an honest page's snapshot; a hostile page controls its own
 * JS environment and can defeat them, which costs it only
 * its own document's translation integrity. The scripts hold
 * node references, not selectors, so a page that reshapes
 * its DOM mid-run can at worst make a write land on a
 * detached node; nodes whose text changed since extraction
 * are skipped, and restore touches only nodes still holding
 * exactly what the apply wrote.
 *
 * Threading: entry points and ML Kit listeners all run on
 * the main thread (the default listener executor), so the run
 * state needs no synchronization. A generation counter
 * invalidates every outstanding callback when a run is
 * cancelled, superseded or its document is replaced, and a
 * watchdog bounds the silence between a run's callbacks (a
 * dead renderer can swallow an evaluateJavascript answer):
 * it is re-armed at every sign of progress and disarmed
 * while the user is deciding on the consent dialog, so it
 * fires only on a genuine stall, never on a slow but healthy
 * run or a user who is still reading.
 *
 * Only one run may be active in the whole activity at a time
 * (the dialogs are activity-level): start() claims the run
 * slot through the Host and every terminal path releases it.
 */
class PageTranslator(
    private val tab: BrowserTab,
    private val host: Host,
) {

    interface Host {
        /** Claim the single activity-wide run slot for [tab].
         * False when another tab's run holds it. */
        fun claimTranslateRun(tab: BrowserTab): Boolean

        fun releaseTranslateRun(tab: BrowserTab)

        /** Ask before language models (about 30 MB each;
         * TranslatePlan.MODEL_SIZE_MB) are downloaded.
         * [singleLanguageName] is the display
         * name of the run's one source language, or null for
         * a mixed-language run (whose wording names no
         * language: which models are missing -- a shared
         * pivot, the target -- is not a property of any one
         * of them). [modelCount] is how many models will
         * actually be fetched -- or, when [isUpperBound]
         * holds (the downloaded-model inventory could not be
         * read), the run's full requirement as an upper
         * bound. The decision comes back exactly once,
         * including when the dialog is cancelled. */
        fun requestModelDownloadConsent(
            singleLanguageName: String?,
            modelCount: Int,
            isUpperBound: Boolean,
            onDecision: (Boolean) -> Unit,
        )

        /** Show or update the progress dialog for the current
         * run. onCancel aborts the run. */
        fun showTranslateProgress(
            progress: TranslateProgress,
            done: Int,
            total: Int,
            onCancel: () -> Unit,
        )

        /** Bring down whichever translation dialog is up
         * (consent or progress); a no-op when none is. The
         * implementation dismisses programmatically, which
         * must not fire the progress dialog's cancel action:
         * the consent flow dismisses the DETECTING progress
         * right before asking, and a cancel leaking through
         * would kill the run it is asking for. The dialogs
         * must also stop counting as pending before this
         * call returns: the consent's own modal check runs
         * synchronously right after, and a dismissal that
         * only lands on a posted message would leave the
         * run's own progress dialog in the predicate --
         * the consent would decline itself (the v0.42.0
         * E2E found exactly that). */
        fun dismissTranslateDialogs()

        fun toast(notice: TranslateNotice)
    }

    enum class State { IDLE, WORKING, TRANSLATED }

    private enum class FailReason {
        EXTRACT, DETECT, MODEL_DOWNLOAD, TRANSLATE, APPLY, TIMEOUT,
    }

    var state: State = State.IDLE
        private set

    private var generation = 0
    private var claimed = false
    private var identifier: LanguageIdentifier? = null

    /** One translator per source language of the live run,
     * keyed by primary subtag. All are closed together when
     * the run settles. */
    private val translators = LinkedHashMap<String, Translator>()

    /** SEN-1101 gate bookkeeping: the gate token the live
     * run's translateAll issued under; the clients holding
     * an unsettled claim (at most one in practice -- the
     * gate allows a single claim process-wide); and the
     * subset of those whose close closeClients deferred
     * to the claim's settlement, so the claimed Task
     * settles with its load's real completion instead of
     * an early cancellation. Both sets are keyed by the
     * client itself, so a later run's claim can never be
     * mistaken for an earlier one's. */
    private var gateToken: Any? = null
    private val gateOutstandingClients = HashSet<Translator>()
    private val gateDeferredClients = HashSet<Translator>()

    /** The page-side state key of the live document, and the
     * origin it was extracted from. Both die with the run's
     * document; the apply/restore wrappers re-check them. */
    private var installedKey: String? = null
    private var expectedOrigin: String? = null

    /** The apply script has been sent and its answer is still
     * outstanding: from here on, an ending that would drop the
     * page-side state must restore first, because the apply
     * may already have written into the page and deleting the
     * state would strand the translation with no way back. */
    private var applyInFlight = false

    private val watchdog = Handler(Looper.getMainLooper())

    /** The generation the watchdog is currently armed for. */
    private var watchdogGen = -1

    /** One reusable watchdog runnable (re-arming happens per
     * settled node, so allocating one each time would churn):
     * it fires only for the generation it was last armed
     * with, and only while that run is still working. */
    private val watchdogRunnable = Runnable {
        if (watchdogGen == generation && state == State.WORKING) {
            DebugLog.log("translate", "watchdog fired")
            failRun(watchdogGen, FailReason.TIMEOUT)
        }
    }

    /** The menu entry's one action: translate, restore the
     * original when this document is already translated, or
     * cancel while a run is working. */
    fun toggle() {
        when (state) {
            State.IDLE -> start()
            State.TRANSLATED -> restore()
            State.WORKING -> cancelRun(generation)
        }
    }

    /** Cancel the live run, if one is working (the tab-switch
     * retirement in MainActivity.switchTo): the run's consent
     * names this tab's page and must not surface over the tab
     * the user switched to — the same rule as the download and
     * permission prompts. A run that already settled (IDLE or
     * TRANSLATED) is untouched: a finished translation stays
     * restorable on its own tab. Dismissing the dialogs from
     * here is safe because the run slot is activity-wide —
     * while this run works, no other run can hold the
     * translation dialogs. */
    fun cancelIfWorking() {
        if (state == State.WORKING) cancelRun(generation)
    }

    /** The tab's document was replaced by a committed load
     * (BrowserTab.Listener.onDocumentReplaced): the DOM the
     * run refers to, and the originals in it, are gone. */
    fun onNewDocument() {
        if (state == State.IDLE && identifier == null && translators.isEmpty()) return
        // The page-side state died with the old document, so
        // there is nothing to delete in the new one: only the
        // Kotlin-side references are dropped. Dialogs are
        // dismissed only when this tab's own run was working
        // (they belong to the run, not to the tab).
        settle(
            translated = false,
            pageState = PageStateAction.CLEAR_REFS,
            dismiss = state == State.WORKING
        )
    }

    fun destroy() {
        // Dialog dismissal stays with MainActivity's tab
        // teardown, which knows whether THIS tab's run owned
        // the activity-level dialogs; dismissing here would
        // let closing an unrelated tab kill another tab's
        // run UI.
        settle(translated = false, pageState = PageStateAction.CLEAR_REFS, dismiss = false)
    }

    private fun start() {
        applyInFlight = false
        // The run acts on the document on screen, which is
        // the last COMMITTED one: currentUrl already names an
        // in-flight navigation's target while the old document
        // is still displayed (and is what extraction would
        // read), so both the gate and the origin work from
        // lastCommittedUrl. One exception: while the start
        // page is displayed, lastCommittedUrl deliberately
        // still names the page visited before it (the home
        // load never moves it), so that display is excluded
        // through the tab's homeDisplayed predicate, which
        // covers the committed state, an outbound load still
        // in flight, and a load that ended without committing.
        if (tab.homeDisplayed) return
        if (!TranslatePlan.isTranslatableUrl(tab.lastCommittedUrl)) return
        // The origin the apply/restore wrappers will check is
        // derived HERE, from the committed URL -- never from
        // the page's own report, which a hostile page
        // controls. Documents with no usable origin (opaque,
        // non-web) are refused outright.
        val origin = expectedOriginFor(tab.lastCommittedUrl)
        if (origin == null) {
            host.toast(TranslateNotice.FAILED)
            return
        }
        if (!host.claimTranslateRun(tab)) {
            host.toast(TranslateNotice.BUSY_OTHER_TAB)
            return
        }
        claimed = true
        val gen = ++generation
        state = State.WORKING
        armWatchdog(gen, PHASE_TIMEOUT_MS)
        val key = "__plT_" + UUID.randomUUID().toString().replace("-", "")
        installedKey = key
        expectedOrigin = origin
        DebugLog.log("translate", "extract start")
        tab.webView.evaluateJavascript(extractScript(key)) { result ->
            if (gen != generation) return@evaluateJavascript
            val extracted = parseExtracted(result)
            if (extracted == null || extracted.origin != origin) {
                failRun(gen, FailReason.EXTRACT)
                return@evaluateJavascript
            }
            val work = TranslatePlan.buildWork(extracted.texts, extracted.title, extracted.capped)
            if (work.items.isEmpty()) {
                endRun(gen)
                host.toast(TranslateNotice.NO_TEXT)
                return@evaluateJavascript
            }
            ensureMlKitInitialized(tab.webView.context)
            detectAndTranslate(gen, work, extracted.lang, extracted.groups)
        }
    }

    /**
     * ML Kit's own startup provider is removed from the
     * merged manifest (PER-3: it ran at every process start
     * although translation is the only ML Kit use), so the
     * SDK is initialized here instead — once per process,
     * at the first run that actually reaches the ML Kit
     * calls. Only a successful attempt is marked done
     * (review round 2, SEN-17): a transient failure must
     * not disable translation until the process restarts.
     * Retries stay bounded by the user starting another
     * translation run — this is called once per run, never
     * in a loop.
     */
    private fun ensureMlKitInitialized(context: Context) {
        if (mlKitInitialized) return
        synchronized(PageTranslator::class.java) {
            if (mlKitInitialized) return
            try {
                MlKit.initialize(context.applicationContext)
                mlKitInitialized = true
            } catch (e: Exception) {
                DebugLog.log("translate", "ML Kit init failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The block a work item belongs to, as the raw block
     * id subGroupKeys later encodes: the title rides under
     * its own index; every other item takes the group the
     * extraction script assigned to its node (0 when the
     * script's group list is missing or short, which folds
     * the run back to deciding everything as one block). */
    private fun groupOf(item: TranslatePlan.WorkItem, nodeGroups: List<Int>): Int =
        if (item.nodeIndex == TranslatePlan.TITLE_INDEX) TranslatePlan.TITLE_INDEX
        else nodeGroups.getOrElse(item.nodeIndex) { 0 }

    private fun detectAndTranslate(
        gen: Int,
        work: TranslatePlan.WorkList,
        htmlLang: String,
        nodeGroups: List<Int>,
    ) {
        // The per-block structure of the work list: each
        // block's cores (for its identification sample) and
        // its total size, plus the block of every item.
        // Oversized blocks are split into sub-groups
        // (TranslatePlan.subGroupKeys), so the keys here are
        // sub-group keys, not raw block ids.
        val itemGroups = TranslatePlan.subGroupKeys(
            work.items.map { groupOf(it, nodeGroups) },
            work.items.map { it.split.core.length },
        )
        val groupCores = LinkedHashMap<Long, MutableList<String>>()
        val groupChars = HashMap<Long, Int>()
        for ((i, item) in work.items.withIndex()) {
            val g = itemGroups[i]
            groupCores.getOrPut(g) { ArrayList() }.add(item.split.core)
            groupChars[g] = (groupChars[g] ?: 0) + item.split.core.length
        }
        val id = LanguageIdentification.getClient()
        identifier = id
        // The document sample is identified first: it is the
        // fallback decision for blocks too small or too
        // unclear to decide on their own, and its failure is
        // the run's failure floor (with no decision at all,
        // nothing can be routed to a model safely).
        val sample = TranslatePlan.detectionSample(work.items.map { it.split.core })
        id.identifyLanguage(sample)
            .addOnSuccessListener { tag ->
                if (gen != generation) return@addOnSuccessListener
                val docDecision = TranslatePlan.decideSource(tag, htmlLang)
                detectGroups(gen, work, itemGroups, groupCores, groupChars, docDecision)
            }
            .addOnFailureListener {
                if (gen != generation) return@addOnFailureListener
                closeIdentifier()
                DebugLog.log("translate", "language detection failed")
                failRun(gen, FailReason.DETECT)
            }
    }

    /** Identify every block big enough to carry its own
     * decision, with the same bounded concurrency as the
     * translation pump. A block whose identification fails or
     * returns nothing usable falls back to the document
     * decision at assembly time; only the document-level
     * failure (handled by the caller) fails the run. */
    private fun detectGroups(
        gen: Int,
        work: TranslatePlan.WorkList,
        itemGroups: List<Long>,
        groupCores: Map<Long, List<String>>,
        groupChars: Map<Long, Int>,
        docDecision: TranslatePlan.SourceDecision,
    ) {
        val id = identifier
        val eligible = groupCores.keys.filter {
            (groupChars[it] ?: 0) >= TranslatePlan.GROUP_DETECTION_MIN_CHARS
        }
        // The identification phase is bounded like every
        // other phase: past the cap, the largest blocks win
        // and the rest take the document decision.
        val targets = if (eligible.size > TranslatePlan.MAX_DETECTION_TARGETS) {
            DebugLog.log(
                "translate",
                "detection capped groups=${eligible.size} cap=${TranslatePlan.MAX_DETECTION_TARGETS}"
            )
            eligible.sortedByDescending { groupChars[it] ?: 0 }.take(TranslatePlan.MAX_DETECTION_TARGETS)
        } else {
            eligible
        }
        if (id == null || targets.isEmpty()) {
            closeIdentifier()
            assemblePlan(gen, work, itemGroups, groupCores, groupChars, docDecision, emptyMap())
            return
        }
        // One predicate for the whole phase: a single target
        // never shows the dialog, at the start or at its
        // settlement.
        val progressShown = targets.size >= 2
        if (progressShown) {
            host.showTranslateProgress(TranslateProgress.DETECTING, 0, targets.size) {
                cancelRun(gen)
            }
        }
        val decisions = HashMap<Long, TranslatePlan.SourceDecision>()
        var next = 0
        var inFlight = 0
        var settledCount = 0
        var pump: () -> Unit = {}
        fun onGroupSettled() {
            armWatchdog(gen, PHASE_TIMEOUT_MS)
            if (progressShown &&
                (settledCount == targets.size || settledCount % PROGRESS_STEP == 0)
            ) {
                host.showTranslateProgress(TranslateProgress.DETECTING, settledCount, targets.size) {
                    cancelRun(gen)
                }
            }
            if (settledCount == targets.size) {
                closeIdentifier()
                assemblePlan(gen, work, itemGroups, groupCores, groupChars, docDecision, decisions)
            } else {
                pump()
            }
        }
        fun record(g: Long, decision: TranslatePlan.SourceDecision) {
            decisions[g] = decision
            inFlight--
            settledCount++
            onGroupSettled()
        }
        pump = {
            while (gen == generation && inFlight < MAX_IN_FLIGHT && next < targets.size) {
                val g = targets[next++]
                inFlight++
                id.identifyLanguage(TranslatePlan.groupSample(groupCores[g] ?: emptyList()))
                    .addOnSuccessListener { tag ->
                        if (gen != generation) return@addOnSuccessListener
                        // No html-lang fallback at block
                        // level: the block's own text was
                        // identified, and a block the
                        // identifier cannot tell must come
                        // back UNKNOWN so it falls back to
                        // the document decision (and the
                        // script guard can see it) -- the
                        // page's declared language is a
                        // weaker claim about one block than
                        // the document sample is.
                        record(g, TranslatePlan.decideSource(tag, ""))
                    }
                    .addOnFailureListener {
                        if (gen != generation) return@addOnFailureListener
                        record(
                            g,
                            TranslatePlan.SourceDecision(
                                TranslatePlan.SourceKind.UNKNOWN, null
                            ),
                        )
                    }
            }
        }
        pump()
    }

    /** Turn the per-block decisions into the run's plan via
     * TranslatePlan.planRun (the pure core, unit-tested).
     * This method only wires the pure pieces together --
     * decision inheritance for unidentified sub-groups, the
     * plan inputs (the library's resolution of each decided
     * tag, injected; the script guard) -- and routes the
     * plan's outcome. */
    private fun assemblePlan(
        gen: Int,
        work: TranslatePlan.WorkList,
        itemGroups: List<Long>,
        groupCores: Map<Long, List<String>>,
        groupChars: Map<Long, Int>,
        docDecision: TranslatePlan.SourceDecision,
        groupDecisions: Map<Long, TranslatePlan.SourceDecision>,
    ) {
        val decisions = TranslatePlan.inheritDecisions(groupCores.keys.toList(), groupDecisions)
        val inheritedGroups = TranslatePlan.inheritedGroups(groupDecisions, decisions)
        val inputs = TranslatePlan.planInputs(
            groupChars.keys, groupCores, docDecision, decisions, inheritedGroups,
        ) { tag -> TranslateLanguage.fromLanguageTag(tag) }
        val plan = TranslatePlan.planRun(
            itemGroups, groupChars, docDecision, decisions,
            inputs.resolvedLanguages, inputs.guardedFamilies,
        )
        if (plan.languages.isEmpty()) {
            endRun(gen)
            host.toast(
                when (TranslatePlan.emptyNotice(plan)) {
                    TranslatePlan.EmptyNotice.ALREADY_TARGET -> TranslateNotice.ALREADY_TARGET
                    TranslatePlan.EmptyNotice.UNSUPPORTED -> TranslateNotice.UNSUPPORTED_LANGUAGE
                    TranslatePlan.EmptyNotice.UNKNOWN -> TranslateNotice.UNKNOWN_LANGUAGE
                }
            )
            return
        }
        val items = ArrayList<TranslatePlan.WorkItem>()
        val itemLanguages = ArrayList<String>()
        for ((i, item) in work.items.withIndex()) {
            val lang = plan.itemLanguages[i] ?: continue
            items.add(item)
            itemLanguages.add(lang)
        }
        if (items.isEmpty()) {
            // Unreachable while plan.languages is non-empty
            // (every selected language owns at least one
            // block's items); kept as a guard so a future
            // refactor fails as a notice, not as a run that
            // translates nothing.
            endRun(gen)
            host.toast(TranslateNotice.UNSUPPORTED_LANGUAGE)
            return
        }
        DebugLog.log(
            "translate",
            "languages=" + plan.languages.joinToString(",") +
                " nodes=${items.size} groups=${groupChars.size}" +
                if (plan.droppedLanguages.isNotEmpty()) {
                    " dropped=" + plan.droppedLanguages.joinToString(",")
                } else {
                    ""
                }
        )
        prepareModels(
            gen,
            TranslatePlan.WorkList(items, work.truncated),
            itemLanguages,
            plan.languages,
            plan.hasSkippedBlocks,
        )
    }

    private fun prepareModels(
        gen: Int,
        work: TranslatePlan.WorkList,
        itemLanguages: List<String>,
        languages: List<String>,
        skippedBlocks: Boolean,
    ) {
        for (lang in languages) {
            translators[lang] = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(lang)
                    .setTargetLanguage(TranslatePlan.TARGET_LANGUAGE)
                    .build()
            )
        }
        val clients = itemLanguages.map { translators.getValue(it) }
        val required = TranslatePlan.requiredModelsFor(languages)
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                if (gen != generation) return@addOnSuccessListener
                val have = models.map { it.language }.toSet()
                val missing = required - have
                if (missing.isEmpty()) {
                    translateAll(gen, work, clients, skippedBlocks)
                } else {
                    // Only the languages contributing missing
                    // models are downloaded (and consented
                    // to), with the real count: what the user
                    // agrees to is what will be fetched.
                    val downloadLanguages = languages.filter { lang ->
                        TranslatePlan.requiredModels(lang).any { it in missing }
                    }
                    askModelConsent(
                        gen, work, clients, languages,
                        downloadLanguages, missing.size,
                        isUpperBound = false,
                        skippedBlocks = skippedBlocks,
                    )
                }
            }
            .addOnFailureListener {
                if (gen != generation) return@addOnFailureListener
                // The inventory check failing must not strand
                // the feature: fall through to the consent path,
                // whose download call is a no-op when the models
                // are in fact present. The count is then the
                // full requirement, presented as an upper bound,
                // and every language is in the download list.
                askModelConsent(
                    gen, work, clients, languages,
                    languages, required.size,
                    isUpperBound = true,
                    skippedBlocks = skippedBlocks,
                )
            }
    }

    private fun askModelConsent(
        gen: Int,
        work: TranslatePlan.WorkList,
        clients: List<Translator>,
        languages: List<String>,
        downloadLanguages: List<String>,
        modelCount: Int,
        isUpperBound: Boolean,
        skippedBlocks: Boolean,
    ) {
        // The consent names the run's language only when
        // there is exactly one: with several, which models
        // are missing (a source's, the shared pivot's, the
        // target's) is not a property of any one language,
        // and the count carries the information instead.
        val singleName = if (languages.size == 1) {
            Locale.forLanguageTag(languages[0])
                .getDisplayLanguage(Locale.JAPANESE)
                .ifEmpty { languages[0] }
        } else {
            null
        }
        // Waiting on the user is not a stall: the watchdog is
        // disarmed while the consent dialog is up and re-armed
        // for the download phase when the decision arrives.
        disarmWatchdog()
        // The consent dialog must not stack on this run's own
        // progress dialog (the DETECTING phase may have one
        // up, and the activity's modal predicate counts it):
        // the progress UI steps aside and the download phase
        // brings its own back.
        host.dismissTranslateDialogs()
        host.requestModelDownloadConsent(singleName, modelCount, isUpperBound) { approved ->
            if (gen != generation) return@requestModelDownloadConsent
            if (!approved) {
                endRun(gen)
                return@requestModelDownloadConsent
            }
            armWatchdog(gen, DOWNLOAD_TIMEOUT_MS)
            DebugLog.log(
                "translate",
                "model download start langs=" + downloadLanguages.joinToString(",")
            )
            host.showTranslateProgress(TranslateProgress.DOWNLOADING_MODEL, 0, 0) {
                cancelRun(gen)
            }
            // Downloads run one language at a time, in the
            // plan's order (largest first): a failure or
            // cancellation then starts nothing further (a
            // download already in flight inside ML Kit may
            // still finish, and its models stay for the next
            // run -- the v0.41.0 consent contract). Each
            // success is progress, so it re-arms the stall
            // watchdog like every other phase's settlements.
            var step: (Int) -> Unit = {}
            step = { index ->
                if (index >= downloadLanguages.size) {
                    DebugLog.log("translate", "model download done")
                    translateAll(gen, work, clients, skippedBlocks)
                } else {
                    val lang = downloadLanguages[index]
                    translators.getValue(lang)
                        .downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            if (gen != generation) return@addOnSuccessListener
                            DebugLog.log("translate", "model download done lang=$lang")
                            armWatchdog(gen, DOWNLOAD_TIMEOUT_MS)
                            step(index + 1)
                        }
                        .addOnFailureListener {
                            if (gen != generation) return@addOnFailureListener
                            DebugLog.log("translate", "model download failed lang=$lang")
                            failRun(gen, FailReason.MODEL_DOWNLOAD)
                        }
                }
            }
            step(0)
        }
    }

    private fun translateAll(
        gen: Int,
        work: TranslatePlan.WorkList,
        clients: List<Translator>,
        skippedBlocks: Boolean,
    ) {
        armWatchdog(gen, PHASE_TIMEOUT_MS)
        host.showTranslateProgress(TranslateProgress.TRANSLATING, 0, work.items.size) {
            cancelRun(gen)
        }
        val results = arrayOfNulls<String>(work.items.size)
        var next = 0
        var inFlight = 0
        var finished = 0
        var failedCount = 0
        // SEN-1101: until FirstLoadGate proves the
        // translate library's native initialization, this
        // pump issues under the gate's discipline (one
        // first load in flight in the whole process). The
        // claim is released from a completion listener
        // rather than the success/failure pair because a
        // cancelled Task settles through neither of the
        // pair but does settle -- and the claiming client
        // is kept open past the run's end (closeClients
        // defers to settleGate) so that its Task can only
        // settle when the load itself has finished: a
        // cancellation would settle the Task immediately
        // while the load keeps running on ML Kit's thread
        // pool, which is exactly the overlap the gate
        // exists to prevent.
        val gateToken = Any()
        this.gateToken = gateToken

        // One settlement path for both outcomes: progress is
        // progress, so every settlement re-arms the watchdog
        // (it bounds a stall, not the whole phase) and updates
        // the counter, throttled so a fast page does not make
        // the dialog relayout hundreds of times. (pump is a
        // var because the two local functions call each other.)
        var pump: () -> Unit = {}
        fun onSettled() {
            armWatchdog(gen, PHASE_TIMEOUT_MS)
            if (finished == work.items.size || finished % PROGRESS_STEP == 0) {
                host.showTranslateProgress(
                    TranslateProgress.TRANSLATING, finished, work.items.size
                ) { cancelRun(gen) }
            }
            if (finished == work.items.size) {
                if (failedCount == work.items.size) {
                    failRun(gen, FailReason.TRANSLATE)
                } else {
                    applyResults(gen, work, results, failedCount, skippedBlocks)
                }
            } else {
                pump()
            }
        }
        // Releases this run's gate claim when the claimed
        // item's Task settles: closes the claimed client
        // first when closeClients deferred that close to
        // here, then wakes the runs waiting on the claim
        // (oldest first) and resumes this run's own pump;
        // whichever of the item's listeners runs first,
        // the other finds the gate already settled and the
        // two paths converge.
        fun settleGate(succeeded: Boolean, claimedClient: Translator) {
            gateOutstandingClients.remove(claimedClient)
            // The gate settles before the deferred close:
            // the settlement must not depend on close()
            // succeeding. Nothing runs between the two on
            // this thread, and the waiters only act when
            // their resumes run below, after the close.
            val resumes = firstLoadGate.settle(gateToken, succeeded)
            DebugLog.log(
                "translate",
                "first-load gate settled ok=$succeeded proven=${firstLoadGate.isProven}"
            )
            if (gateDeferredClients.remove(claimedClient)) {
                // A close failure must not strand the
                // resumes extracted above: the gate is
                // already settled, and the waiting runs
                // proceed either way.
                try {
                    claimedClient.close()
                } catch (e: RuntimeException) {
                    DebugLog.log("translate", "first-load gate close failed")
                }
            }
            for (resume in resumes) {
                resume()
            }
            pump()
        }
        pump = {
            while (gen == generation && inFlight < MAX_IN_FLIGHT && next < work.items.size) {
                var wantClaim = false
                when (firstLoadGate.decision(gateToken)) {
                    FirstLoadGate.Decision.FREE -> Unit
                    FirstLoadGate.Decision.HELD -> {
                        // This run's claimed item is still
                        // out; its settlement resumes the
                        // pump through settleGate.
                        break
                    }
                    FirstLoadGate.Decision.WAIT -> {
                        // A previous run's first load is
                        // still out: its run may already be
                        // over, but the load itself cannot
                        // be stopped, so this run waits for
                        // the settlement. The watchdog
                        // bounds the wait like any other
                        // stall.
                        DebugLog.log("translate", "first-load gate wait")
                        firstLoadGate.waitForRelease(gateToken) { pump() }
                        break
                    }
                    FirstLoadGate.Decision.CLAIM -> wantClaim = true
                }
                val pos = next++
                inFlight++
                // The claim is taken only once the Task
                // exists, and the completion listener is
                // registered before the pair, so no
                // synchronous failure in between can leak
                // the claim: an exception before the claim
                // leaves the gate untouched, and one after
                // it still settles through the listener (a
                // registration failure keeps the claim held
                // instead -- see the catch below).
                val task = clients[pos].translate(work.items[pos].split.core)
                if (wantClaim) {
                    val claimedClient = clients[pos]
                    gateOutstandingClients.add(claimedClient)
                    firstLoadGate.claim(gateToken)
                    DebugLog.log("translate", "first-load gate claimed")
                    try {
                        task.addOnCompleteListener { settled ->
                            settleGate(settled.isSuccessful, claimedClient)
                        }
                    } catch (e: RuntimeException) {
                        // Fail closed: the load is already
                        // submitted and may still be running,
                        // so the claim is NOT released here
                        // (releasing it would reopen the
                        // overlap). The claim then stays held
                        // for the process's life: this run and
                        // every later one wait on the gate and
                        // fail by their watchdogs, and only a
                        // restart clears it -- the accepted
                        // cost, recorded with SEN-1101, of
                        // never reopening the overlap.
                        // Listener registration is not a path
                        // the Tasks runtime is known to fail.
                        DebugLog.log("translate", "first-load gate listener failed")
                        throw e
                    }
                }
                task
                    .addOnSuccessListener { text ->
                        if (gen != generation) return@addOnSuccessListener
                        results[pos] = text
                        inFlight--
                        finished++
                        onSettled()
                    }
                    .addOnFailureListener {
                        if (gen != generation) return@addOnFailureListener
                        // One node's failure does not discard
                        // the whole page: the node keeps its
                        // original text and the run continues.
                        // Only a total failure fails the run.
                        failedCount++
                        inFlight--
                        finished++
                        DebugLog.log("translate", "node translate failed count=$failedCount")
                        onSettled()
                    }
            }
        }
        DebugLog.log("translate", "translate start nodes=${work.items.size}")
        pump()
    }

    private fun applyResults(
        gen: Int,
        work: TranslatePlan.WorkList,
        results: Array<String?>,
        failedCount: Int,
        skippedBlocks: Boolean,
    ) {
        val key = installedKey
        val origin = expectedOrigin
        if (key == null || origin == null) {
            failRun(gen, FailReason.APPLY)
            return
        }
        val nodes = JSONArray()
        var translatedTitle: String? = null
        for ((pos, item) in work.items.withIndex()) {
            val text = results[pos] ?: continue
            val full = TranslatePlan.reassemble(item.split, text)
            if (item.nodeIndex == TranslatePlan.TITLE_INDEX) {
                translatedTitle = full
            } else {
                nodes.put(JSONArray().put(item.nodeIndex).put(full))
            }
        }
        val payload = JSONObject()
        payload.put("nodes", nodes)
        if (translatedTitle != null) payload.put("title", translatedTitle)
        val script = "(function(){var T=window['" + key + "'];" +
            "if(!T||location.origin!==" + JSONObject.quote(origin) + ")return 'mismatch';" +
            "return T.apply(" + payload.toString() + ");})()"
        applyInFlight = true
        tab.webView.evaluateJavascript(script) { result ->
            applyInFlight = false
            if (gen != generation) return@evaluateJavascript
            val applied = parseApplyResult(result)
            if (applied == null || (applied.first == 0 && nodes.length() > 0)) {
                DebugLog.log("translate", "apply rejected")
                failRun(gen, FailReason.APPLY)
                return@evaluateJavascript
            }
            endRun(gen, translated = true)
            DebugLog.log(
                "translate",
                "translate done nodes=${work.items.size} applied=${applied.first} " +
                    "skipped=${applied.second} failed=$failedCount truncated=${work.truncated}"
            )
            host.toast(
                when {
                    failedCount > 0 -> TranslateNotice.PARTIALLY_FAILED
                    work.truncated -> TranslateNotice.TRANSLATED_PARTIAL
                    skippedBlocks -> TranslateNotice.TRANSLATED_WITH_SKIPS
                    else -> TranslateNotice.TRANSLATED
                }
            )
        }
    }

    private fun restore() {
        generation++
        disarmWatchdog()
        // Fire and forget: if the document is already gone
        // the callback may never arrive, and a stale
        // TRANSLATED would mislabel the menu, so the state
        // flips now rather than when the script answers. On
        // a live document the script runs before the next
        // frame, so the user never sees the gap.
        restoreInstalledState()
        state = State.IDLE
    }

    private fun cancelRun(gen: Int) {
        if (gen != generation) return
        settle(translated = false, pageState = PageStateAction.DELETE_SCRIPT, dismiss = true)
        DebugLog.log("translate", "cancelled")
    }

    /** End a run that produced a definite outcome (a toast has
     * been or is about to be shown by the caller). */
    private fun endRun(gen: Int, translated: Boolean = false) {
        if (gen != generation) return
        settle(
            translated = translated,
            pageState = if (translated) PageStateAction.KEEP else PageStateAction.DELETE_SCRIPT,
            dismiss = true
        )
    }

    private fun failRun(gen: Int, reason: FailReason) {
        if (gen != generation) return
        // A failed apply may already have written a title or
        // some nodes: try to put them back before the state
        // object is dropped, so a failure never strands a
        // half-translated page with no way back.
        if (reason == FailReason.APPLY) restoreInstalledState()
        settle(translated = false, pageState = PageStateAction.DELETE_SCRIPT, dismiss = true)
        DebugLog.log("translate", "failed reason=$reason")
        host.toast(
            when (reason) {
                FailReason.MODEL_DOWNLOAD -> TranslateNotice.MODEL_DOWNLOAD_FAILED
                else -> TranslateNotice.FAILED
            }
        )
    }

    /** What happens to the page-side state at a run ending. */
    private enum class PageStateAction {
        /** Send the delete script and drop the references. */
        DELETE_SCRIPT,

        /** Drop only the references: the document (and its
         * state object) is already gone. */
        CLEAR_REFS,

        /** Keep both: a translated document restores from
         * them. */
        KEEP,
    }

    /**
     * The one terminal path every run ending shares: bump the
     * generation so no outstanding callback of this run can
     * act afterwards, stop the watchdog, close the ML Kit
     * clients, handle the page-side state as told, dismiss
     * the dialogs when this run owns them, release the run
     * slot and land in the given state.
     */
    private fun settle(translated: Boolean, pageState: PageStateAction, dismiss: Boolean) {
        generation++
        disarmWatchdog()
        // A run that ends while waiting on the gate drops
        // its registration; a run holding the claim keeps
        // it (its settlement is the waiters' release).
        gateToken?.let { firstLoadGate.cancelWait(it) }
        gateToken = null
        closeClients()
        when (pageState) {
            PageStateAction.DELETE_SCRIPT ->
                if (applyInFlight) {
                    // The apply may already have written:
                    // scripts run in send order, so a restore
                    // sent now undoes whatever it did (and is
                    // a no-op where it wrote nothing).
                    applyInFlight = false
                    restoreInstalledState()
                } else {
                    cleanupPageState()
                }
            PageStateAction.CLEAR_REFS -> {
                applyInFlight = false
                clearPageStateRefs()
            }
            PageStateAction.KEEP -> Unit
        }
        if (dismiss) host.dismissTranslateDialogs()
        releaseClaim()
        state = if (translated) State.TRANSLATED else State.IDLE
    }

    private fun releaseClaim() {
        if (claimed) {
            claimed = false
            host.releaseTranslateRun(tab)
        }
    }

    /** Drop the page-side state object of a run that did not
     * end in a translation (a translated document keeps its
     * state for the restore path). */
    private fun cleanupPageState() {
        val key = installedKey ?: return
        clearPageStateRefs()
        tab.webView.evaluateJavascript("delete window['" + key + "']", null)
    }

    private fun clearPageStateRefs() {
        installedKey = null
        expectedOrigin = null
    }

    /** Ask the page to write the originals back (only where
     * its current text is still exactly what the apply wrote)
     * and drop the state object. Fire and forget. */
    private fun restoreInstalledState() {
        val key = installedKey
        val origin = expectedOrigin
        clearPageStateRefs()
        if (key != null && origin != null) {
            val script = "(function(){var T=window['" + key + "'];" +
                "if(T&&location.origin===" + JSONObject.quote(origin) + ")T.restore();" +
                "delete window['" + key + "'];return 'done';})()"
            tab.webView.evaluateJavascript(script, null)
        }
    }

    /** The origin of a URL, in the form location.origin
     * reports it, or null when the URL has no usable origin:
     * non-web schemes, and file: (whose origin is one opaque
     * value shared by every local file, so documents cannot
     * be told apart -- the opaque-origin problem again).
     * Documents whose real origin is opaque for other reasons
     * (a CSP sandbox) cannot be detected from the URL; they
     * fail later at the origin equality check instead, which
     * is the safe side. */
    private fun expectedOriginFor(url: String): String? {
        val uri = try {
            android.net.Uri.parse(url)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        // Uri.getHost() may or may not keep an IPv6 literal's
        // brackets; normalize to the bare address first, then
        // bracket it the way location.origin prints it.
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
        val authority = if (host.contains(':')) "[$host]" else host
        val port = uri.port
        val defaultPort = if (scheme == "http") 80 else 443
        return if (port == -1 || port == defaultPort) {
            "$scheme://$authority"
        } else {
            "$scheme://$authority:$port"
        }
    }

    private fun armWatchdog(gen: Int, timeoutMs: Long) {
        watchdogGen = gen
        watchdog.removeCallbacks(watchdogRunnable)
        watchdog.postDelayed(watchdogRunnable, timeoutMs)
    }

    private fun disarmWatchdog() {
        watchdog.removeCallbacks(watchdogRunnable)
    }

    private fun closeIdentifier() {
        identifier?.close()
        identifier = null
    }

    private fun closeClients() {
        closeIdentifier()
        for (tr in translators.values) {
            if (tr in gateOutstandingClients) {
                // SEN-1101: closing this client now would
                // cancel the claimed Task while its load
                // may still be running; settleGate closes
                // it when the claim settles instead.
                gateDeferredClients.add(tr)
            } else {
                tr.close()
            }
        }
        translators.clear()
    }

    private data class Extracted(
        val title: String,
        val lang: String,
        val origin: String,
        val capped: Boolean,
        val texts: List<String>,

        /** The block group of each text, parallel to [texts],
         * sanitized by TranslatePlan.sanitizeGroups. Empty
         * when the script's list was missing, did not line up
         * with the texts, or held an out-of-range value;
         * groupOf then folds every node into group 0 (the
         * title always keeps its own group). */
        val groups: List<Int>,
    )

    /** The extract script returns a JSON string, which the
     * evaluateJavascript bridge itself JSON-encodes once more:
     * unwrap the outer string first, then parse the object.
     * Oversized or malformed results are refused whole: the
     * page controls the script's environment, so its output
     * is untrusted input. */
    private fun parseExtracted(result: String?): Extracted? {
        if (result == null || result.length > MAX_EXTRACT_RESULT_CHARS) return null
        return try {
            val outer = JSONTokener(result).nextValue() as? String ?: return null
            val obj = JSONObject(outer)
            val arr = obj.optJSONArray("texts") ?: JSONArray()
            val texts = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) texts.add(arr.optString(i, ""))
            val garr = obj.optJSONArray("groups")
            val groups: List<Int> =
                if (garr != null) {
                    val rawGroups = (0 until garr.length()).map { garr.optInt(it, 0) }
                    val cleanGroups = TranslatePlan.sanitizeGroups(rawGroups, texts.size)
                    if (cleanGroups.isEmpty() && rawGroups.isNotEmpty()) {
                        // The page-supplied groups failed
                        // validation (length or range) and the
                        // run falls back to one block. Logged
                        // so a silent loss of per-block
                        // handling stays diagnosable.
                        DebugLog.log(
                            "translate",
                            "groups discarded raw=" + rawGroups.size + " nodes=" + texts.size,
                        )
                    }
                    cleanGroups
                } else {
                    emptyList()
                }
            Extracted(
                obj.optString("title", ""),
                obj.optString("lang", ""),
                obj.optString("origin", ""),
                obj.optBoolean("capped", false),
                texts,
                groups,
            )
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    /** The apply script answers a JSON object string with the
     * applied/skipped counts, or the literal "mismatch" when
     * the state key or origin did not check out. The answer is
     * a few dozen characters by construction, so the ceiling
     * is small; the page produces this string (it can replace
     * JSON.stringify), and a deeply nested forgery must not
     * take the UI thread down with it. */
    private fun parseApplyResult(result: String?): Pair<Int, Int>? {
        if (result == null || result.length > MAX_APPLY_RESULT_CHARS) return null
        return try {
            val outer = JSONTokener(result).nextValue() as? String ?: return null
            if (outer == "mismatch") return null
            val obj = JSONObject(outer)
            Pair(obj.optInt("applied", 0), obj.optInt("skipped", 0))
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    companion object {
        /** Concurrent model calls during one run: enough to
         * keep the on-device engine busy without flooding it
         * with a large page's worth of tasks at once. */
        private const val MAX_IN_FLIGHT = 8

        /** Set once [PageTranslator.ensureMlKitInitialized]
         * has run (PER-3). */
        @Volatile private var mlKitInitialized = false

        // SEN-1101: the first-load gate shared by every
        // run in the process (see FirstLoadGate).
        private val firstLoadGate = FirstLoadGate()

        /** The watchdog's stall bound for the phases that
         * report progress or are quick by construction
         * (extraction, detection, per-node translation): it is
         * re-armed at every node settlement, so it bounds the
         * silence between callbacks, never the length of a
         * healthy run. The consent wait disarms it entirely
         * (waiting on the user is not a stall). */
        private const val PHASE_TIMEOUT_MS = 180_000L

        /** The stall bound for the model-download phase, which
         * reports no progress callbacks: generous, because a
         * slow link downloading up to three models is the
         * slowest legitimate silence in the feature. Still a
         * failure bound, not a completion guess. */
        private const val DOWNLOAD_TIMEOUT_MS = 900_000L

        /** Progress dialog updates are throttled to one per
         * this many settled nodes (plus the final one). */
        private const val PROGRESS_STEP = 25

        /** Hard ceiling for the extraction bridge payload,
         * comfortably above the script's own caps (2,500 nodes,
         * 400,000 chars, JSON escaping included). */
        private const val MAX_EXTRACT_RESULT_CHARS = 4_000_000

        /** The apply answer is two small counts; anything
         * longer is not ours. */
        private const val MAX_APPLY_RESULT_CHARS = 1024

        /**
         * The extraction script template; __PLT_STATE_KEY__
         * stands for the per-run state key. It walks the main
         * document and same-origin iframes (cross-origin
         * frames refuse access and are skipped, an accepted
         * limitation, as is shadow DOM content), collecting
         * visible text nodes outside code, form controls,
         * SVG/MathML and notranslate subtrees, each tagged
         * with the id of its block-level container for the
         * per-block language decision. Parent-derived
         * verdicts are cached per parent element, the cheap
         * blank check runs first, and the walk is bounded by
         * node, character and time budgets. Two cap layers:
         * the script's bound the EXTRACTION (every candidate
         * it visits, translatable or not), TranslatePlan's
         * tighter caps then bound the set actually sent to
         * the translator. The time budget is enforced inside
         * acceptNode, so nodes the walker rejects count
         * against it too. The originals are snapshotted at
         * install and the state object is frozen: on an honest
         * page that keeps the snapshot intact and the random
         * key unguessable in advance; a hostile page controls
         * its own JS environment and can defeat the freeze,
         * which costs it only its own document's translation
         * integrity (the cross-document checks live on the
         * Kotlin side: the key and the URL-derived origin).
         * Apply writes only nodes whose current text still
         * equals the snapshot, records what it wrote, and
         * restore writes back only nodes still holding exactly
         * what apply put there, so a page's own updates after
         * translation are never clobbered.
         */
        private fun extractScript(key: String): String = """
(function() {
  var SKIP = {script:1, style:1, noscript:1, textarea:1, input:1, select:1, option:1, code:1, pre:1, template:1, svg:1, math:1};
  var MAXNODES = 2500;
  var MAXNODECHARS = 20000;
  var MAXTOTALCHARS = 400000;
  var TIMEBUDGET = 1500;
  var startedAt = Date.now();
  var capped = false;
  var totalChars = 0;
  var seen = 0;
  var verdicts = new WeakMap();
  function parentOk(p) {
    var v = verdicts.get(p);
    if (v !== undefined) return v;
    var ok = true;
    var ln = (p.localName || '').toLowerCase();
    if (SKIP[ln]) ok = false;
    else if (p.isContentEditable) ok = false;
    else if (p.closest && p.closest('[translate="no"], .notranslate, svg, math')) ok = false;
    else if (p.getClientRects().length === 0) ok = false;
    verdicts.set(p, ok);
    return ok;
  }
  var BLOCK = {p:1, div:1, li:1, td:1, th:1, h1:1, h2:1, h3:1, h4:1, h5:1, h6:1, blockquote:1, section:1, article:1, aside:1, header:1, footer:1, main:1, figure:1, figcaption:1, dd:1, dt:1, summary:1, caption:1, address:1, ul:1, ol:1, tr:1, table:1, nav:1, form:1};
  var groupIds = new WeakMap();
  var groupSeq = 0;
  var miscGroup = -1;
  // The block a text node belongs to: its nearest block-level
  // ancestor (or the outermost element when there is none).
  // Blocks are the unit of language identification -- a page
  // that mixes languages is decided paragraph by paragraph,
  // not by its dominant language alone. The climb is capped
  // and memoized per element: the page controls its DOM's
  // depth and shape, and a hostile parentElement chain must
  // not turn extraction into an unbounded walk (the verdict
  // cache in parentOk works the same way).
  function blockIdOf(el) {
    if (!el) {
      if (miscGroup < 0) miscGroup = groupSeq++;
      return miscGroup;
    }
    var cached = groupIds.get(el);
    if (cached !== undefined) return cached;
    var e = el;
    var steps = 0;
    var path = [];
    var id;
    var arrived = false;
    while (e && steps < 64) {
      var ln = (e.localName || '').toLowerCase();
      if (BLOCK[ln] === 1 || !e.parentElement) {
        id = groupIds.get(e);
        if (id === undefined) { id = groupSeq++; groupIds.set(e, id); }
        arrived = true;
        break;
      }
      // An ancestor resolved by an earlier climb answers
      // for this element too: siblings under a deep inline
      // nest stop here instead of re-climbing it per node.
      var hit = groupIds.get(e);
      if (hit !== undefined) { id = hit; arrived = true; break; }
      path.push(e);
      e = e.parentElement;
      steps++;
    }
    if (id === undefined) {
      // The step cap stopped the climb on an arbitrary
      // ancestor: the starting element gets its own group,
      // and the landing spot is not cached -- a later climb
      // from a nearer element may still arrive properly.
      id = groupSeq++;
    } else if (arrived) {
      // Every element on the climb shares the answer;
      // writing it back along the path keeps a deep nest
      // of inline elements from being re-climbed.
      for (var i = 0; i < path.length; i++) groupIds.set(path[i], id);
    }
    groupIds.set(el, id);
    return id;
  }
  function collect(doc, out) {
    var walker = doc.createTreeWalker(doc.body || doc.documentElement, NodeFilter.SHOW_TEXT, {
      acceptNode: function(n) {
        // Every visited node counts against the time budget,
        // rejected ones included: a page of nothing but blank
        // or hidden text must not walk unbounded. Once the
        // budget is gone, accept everything so nextNode
        // returns at once and the collect loop can stop.
        seen++;
        if ((seen & 255) === 0 && Date.now() - startedAt > TIMEBUDGET) capped = true;
        if (capped) return NodeFilter.FILTER_ACCEPT;
        var t = n.nodeValue;
        if (!t || !/\S/.test(t)) return NodeFilter.FILTER_REJECT;
        var p = n.parentElement;
        if (!p) return NodeFilter.FILTER_REJECT;
        if (!parentOk(p)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var n;
    while ((n = walker.nextNode())) {
      if (capped) break;
      var len = n.nodeValue.length;
      if (len > MAXNODECHARS) { capped = true; continue; }
      if (out.length >= MAXNODES || totalChars + len > MAXTOTALCHARS) { capped = true; break; }
      out.push(n);
      nodeGroups.push(blockIdOf(n.parentElement));
      totalChars += len;
    }
  }
  var nodes = [];
  var nodeGroups = [];
  function walkFrames(doc, depth) {
    collect(doc, nodes);
    if (depth >= 3 || capped) return;
    var frames = doc.querySelectorAll('iframe');
    for (var i = 0; i < frames.length; i++) {
      var d = null;
      try { d = frames[i].contentDocument; } catch (e) { d = null; }
      if (d) walkFrames(d, depth + 1);
    }
  }
  walkFrames(document, 0);
  var texts = nodes.map(function(x) { return x.nodeValue; });
  var T = { nodes: nodes, orig: texts.slice(), origTitle: document.title, applied: {}, extra: { title: null } };
  T.apply = function(p) {
    var applied = 0;
    var skipped = 0;
    var list = p.nodes || [];
    for (var i = 0; i < list.length; i++) {
      var idx = list[i][0];
      var text = list[i][1];
      if (idx >= 0 && idx < T.nodes.length && typeof text === 'string') {
        if (T.nodes[idx].nodeValue === T.orig[idx]) {
          T.nodes[idx].nodeValue = text;
          T.applied[idx] = text;
          applied++;
        } else {
          skipped++;
        }
      }
    }
    if (typeof p.title === 'string' && document.title === T.origTitle) {
      document.title = p.title;
      // Recorded in the unfrozen holder: T itself is frozen
      // below, so a direct T.appliedTitle assignment would be
      // silently dropped.
      T.extra.title = p.title;
    }
    return JSON.stringify({applied: applied, skipped: skipped});
  };
  T.restore = function() {
    for (var i = 0; i < T.orig.length && i < T.nodes.length; i++) {
      // Only nodes still holding exactly what apply wrote
      // are put back; anything the page itself updated after
      // the translation is left alone.
      if (Object.prototype.hasOwnProperty.call(T.applied, i) &&
          T.nodes[i].nodeValue === T.applied[i]) {
        T.nodes[i].nodeValue = T.orig[i];
      }
    }
    if (T.extra.title !== null && document.title === T.extra.title) {
      document.title = T.origTitle;
    }
    return 'restored';
  };
  Object.freeze(T.orig);
  Object.freeze(T.nodes);
  Object.freeze(T);
  Object.defineProperty(window, '__PLT_STATE_KEY__', { value: T, writable: true, configurable: true, enumerable: false });
  return JSON.stringify({
    title: document.title,
    lang: (document.documentElement.getAttribute('lang') || ''),
    origin: location.origin,
    capped: capped,
    texts: texts,
    groups: nodeGroups
  });
})()
""".replace("__PLT_STATE_KEY__", key)
    }
}
