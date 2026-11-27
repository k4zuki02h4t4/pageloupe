package jp.kaleidpixel.pageloupe

/**
 * Decision logic and loop for one DevTools target search. The
 * classification is pure functions; the loop itself
 * (runTargetSearch) is a higher-order function whose I/O —
 * session liveness, one attempt's observation, the retry
 * pause — is injected by the caller in MainActivity. No
 * Android types here, so the search's decision logic — the
 * classification, the loop, and the target matching — is
 * covered by JVM unit tests (tests/TargetSearchTest.kt) with
 * fakes. The I/O adapter in MainActivity (the UI snapshots,
 * the socket fetch, the post-fetch re-verification) is what
 * runs on device.
 *
 * The loop's job: find the one registered DevTools target whose
 * URL exactly matches the inspected tab's URL. Each attempt's
 * observations are classified here, in exactly one place, and
 * the loop state is updated here only — an attempt records ONE
 * outcome, so a specific failure (the target changed mid-search)
 * can never be overwritten by a generic one (not found) later in
 * the same attempt, and the terminal decision at the end reads
 * the state those outcomes left behind.
 *
 * The target type is a parameter ([T]) so the verified target
 * itself travels through the classification: a Found step
 * always carries the very target its attempt verified, with no
 * side channel back into the caller's lists.
 */

/** Loop-carried state of a target search. */
data class TargetSearchState(
    /** At least one attempt got a UI snapshot. */
    val everSnapshotted: Boolean = false,
    /**
     * The latest attempt failed in a way a tab rebuild (Phase 2)
     * cannot fix: the inspected target changed mid-search, or
     * the UI stopped answering after having answered (a silent
     * verification, or a silent attempt after a successful
     * snapshot). Attempts with an ordinary outcome — not found,
     * fetch failed — clear the flag again: the decision always
     * follows the latest attempt. One accepted consequence: if
     * the FINAL attempt is the silent one, after a run of
     * not-found attempts the search still ends terminal instead
     * of rebuilding. That is the safe direction — a rebuild
     * during a UI stall cannot fix the stall and would destroy
     * the tab's state, while a terminal error only asks the
     * user to reopen the panel.
     */
    val rebuildFutile: Boolean = false,
    /** Error text of the latest attempt with a recorded outcome. */
    val lastError: String? = null,
)

/** What a single attempt observed; all I/O is the caller's. */
sealed interface TargetAttempt<out T> {
    /** The UI snapshot never arrived. */
    object NoSnapshot : TargetAttempt<Nothing>

    /** Several app-side tabs share the inspected URL. */
    data class AmbiguousTabs(val count: Int) : TargetAttempt<Nothing>

    /** The registered-target list could not be fetched. */
    data class FetchFailed(val message: String?) : TargetAttempt<Nothing>

    /** The list was fetched; no page carries the inspected URL. */
    data class NotListed(val candidates: Int) : TargetAttempt<Nothing>

    /**
     * The list was fetched and [count] pages carry the inspected
     * URL. (The single-match case goes through verification and
     * is reported as [Verified] / [VerifyFailed], so a count of
     * 1 cannot be expressed here.)
     */
    data class ManyMatches(val count: Int, val candidates: Int) : TargetAttempt<Nothing>

    /**
     * One match, and the verification snapshot still equals the
     * first. Carries the matched target itself.
     */
    data class Verified<T>(val target: T) : TargetAttempt<T>

    /**
     * One match, but verification failed: the inspected target
     * changed ([uiSilent] = false), or the verification snapshot
     * never arrived ([uiSilent] = true).
     */
    data class VerifyFailed(val uiSilent: Boolean) : TargetAttempt<Nothing>
}

/** What the loop should do with an attempt's outcome. */
sealed interface TargetStep<out T> {
    /** The target is identified; the caller returns [target]. */
    data class Found<T>(val target: T) : TargetStep<T>

    /** Stop the whole search now, terminal, with this error. */
    data class Stop(val error: String) : TargetStep<Nothing>

    /** Record the outcome in [state] and try the next attempt. */
    data class Again(val state: TargetSearchState) : TargetStep<Nothing>
}

private const val UI_SILENT_ERROR = "UI スレッドが応答しません"
private const val TARGET_CHANGED_ERROR = "検査対象が接続中に変化しました"

/** The plain not-found wording, shared with the caller's fallback. */
const val TARGET_NOT_FOUND_FALLBACK = "対象ページが見つかりません"

private fun ambiguousError(count: Int): String =
    "同じURLのタブが${count}件あるため、検査対象を特定できません"

private fun notListedError(candidates: Int): String =
    "$TARGET_NOT_FOUND_FALLBACK（候補 ${candidates} 件）"

/**
 * Classify one attempt. [attemptIndex] is the 0-based attempt
 * number: registered-side duplicates get a few retries before
 * they count as ambiguous, because right after a rebuild the
 * destroyed WebView's target can linger in the list briefly
 * next to its replacement. (App-side duplicates, in contrast,
 * cannot resolve themselves and stop the search at once.)
 */
fun <T> targetSearchStep(
    state: TargetSearchState,
    attempt: TargetAttempt<T>,
    attemptIndex: Int
): TargetStep<T> = when (attempt) {
    TargetAttempt.NoSnapshot -> TargetStep.Again(
        state.copy(
            // Silence AFTER a successful snapshot is a stall a
            // rebuild cannot fix either (the UI answered, then
            // stopped): count it as a rebuild-futile failure,
            // like a silent verification. Silence before any
            // snapshot leaves the flag alone; the end-of-loop
            // rule already makes that case terminal.
            rebuildFutile = state.rebuildFutile || state.everSnapshotted,
            lastError = UI_SILENT_ERROR
        )
    )
    is TargetAttempt.AmbiguousTabs -> TargetStep.Stop(ambiguousError(attempt.count))
    is TargetAttempt.FetchFailed -> TargetStep.Again(
        state.copy(everSnapshotted = true, rebuildFutile = false, lastError = attempt.message)
    )
    is TargetAttempt.NotListed -> TargetStep.Again(
        state.copy(
            everSnapshotted = true,
            rebuildFutile = false,
            lastError = notListedError(attempt.candidates)
        )
    )
    is TargetAttempt.ManyMatches ->
        if (attemptIndex >= 2) {
            TargetStep.Stop(ambiguousError(attempt.count))
        } else {
            TargetStep.Again(
                state.copy(
                    everSnapshotted = true,
                    rebuildFutile = false,
                    lastError = notListedError(attempt.candidates)
                )
            )
        }
    is TargetAttempt.Verified -> TargetStep.Found(attempt.target)
    is TargetAttempt.VerifyFailed -> TargetStep.Again(
        state.copy(
            everSnapshotted = true,
            rebuildFutile = true,
            lastError = if (attempt.uiSilent) UI_SILENT_ERROR else TARGET_CHANGED_ERROR
        )
    )
}

/**
 * Whether a search that used up its attempts ends terminal (no
 * Phase 2): the UI never answered even once, or the latest
 * attempt was a failure a rebuild cannot fix.
 */
fun targetSearchTerminal(state: TargetSearchState): Boolean =
    !state.everSnapshotted || state.rebuildFutile

/** The kind of an attempt's observation, for the search trace. */
enum class ObservationKind {
    NONE, NO_SNAPSHOT, AMBIGUOUS_TABS, FETCH_FAILED,
    NOT_LISTED, MANY_MATCHES, VERIFIED, VERIFY_FAILED
}

/** How a target search ended. */
enum class SearchEnd { FOUND, STOP, EXHAUSTED, SESSION }

/**
 * The full outcome of one target search, trace included.
 * [terminal] errors must not fall through to Phase 2: they are
 * failures a tab rebuild cannot fix, and the reload would just
 * destroy the page's state for nothing. A search ends terminal
 * by exactly three paths: the session died, an attempt
 * classified as Stop, or the attempts ran out with
 * targetSearchTerminal true. The field correspondences those
 * paths imply are enforced in init, so an inconsistent outcome
 * cannot be constructed silently.
 */
data class TargetSearchOutcome<T : Any>(
    val target: T?,
    val error: String?,
    val terminal: Boolean,
    /** Attempts that actually ran (a session death mid-loop does not count its own attempt). */
    val attemptsUsed: Int,
    val lastKind: ObservationKind,
    val end: SearchEnd
) {
    init {
        require((end == SearchEnd.FOUND) == (target != null)) {
            "a target is present exactly when the search found one"
        }
        require(end != SearchEnd.FOUND || error == null) {
            "a found search carries no error"
        }
        require(end != SearchEnd.SESSION || error == null) {
            "a session death carries no error"
        }
        require(end != SearchEnd.STOP || error != null) {
            "a stopped search carries its error"
        }
        require(end != SearchEnd.FOUND || !terminal) {
            "a found search is never terminal"
        }
        require(end != SearchEnd.STOP || terminal) {
            "a stopped search is terminal"
        }
        require(end != SearchEnd.SESSION || terminal) {
            "a session death is terminal"
        }
    }
}

/** The kind of an observation, as a stable typed value. */
fun <T> observationKind(attempt: TargetAttempt<T>): ObservationKind = when (attempt) {
    TargetAttempt.NoSnapshot -> ObservationKind.NO_SNAPSHOT
    is TargetAttempt.AmbiguousTabs -> ObservationKind.AMBIGUOUS_TABS
    is TargetAttempt.FetchFailed -> ObservationKind.FETCH_FAILED
    is TargetAttempt.NotListed -> ObservationKind.NOT_LISTED
    is TargetAttempt.ManyMatches -> ObservationKind.MANY_MATCHES
    is TargetAttempt.Verified -> ObservationKind.VERIFIED
    is TargetAttempt.VerifyFailed -> ObservationKind.VERIFY_FAILED
}

private fun kindLabel(kind: ObservationKind): String = when (kind) {
    ObservationKind.NONE -> "none"
    ObservationKind.NO_SNAPSHOT -> "NoSnapshot"
    ObservationKind.AMBIGUOUS_TABS -> "AmbiguousTabs"
    ObservationKind.FETCH_FAILED -> "FetchFailed"
    ObservationKind.NOT_LISTED -> "NotListed"
    ObservationKind.MANY_MATCHES -> "ManyMatches"
    ObservationKind.VERIFIED -> "Verified"
    ObservationKind.VERIFY_FAILED -> "VerifyFailed"
}

private fun endLabel(end: SearchEnd): String = when (end) {
    SearchEnd.FOUND -> "found"
    SearchEnd.STOP -> "stop"
    SearchEnd.EXHAUSTED -> "exhausted"
    SearchEnd.SESSION -> "session"
}

/**
 * The one-line trace of a finished search, for the gated debug
 * log: the attempt limit tells the search phases apart, and the
 * end kind separates a dead session from a search that ran its
 * course. Kinds and counts only — never a URL, never error text.
 */
fun searchTraceLine(limit: Int, outcome: TargetSearchOutcome<*>): String =
    "target search: limit=$limit used=${outcome.attemptsUsed} " +
        "last=${kindLabel(outcome.lastKind)} end=${endLabel(outcome.end)} " +
        "terminal=${outcome.terminal}"

/** How one fetch's targets match the inspected tab's URL. */
sealed interface TargetMatch<out T> {
    /** Exactly one page target matches: the candidate to verify. */
    data class One<T>(val target: T) : TargetMatch<T>

    /** No page target matches. [pageCount] counts page targets only. */
    data class None(val pageCount: Int) : TargetMatch<Nothing>

    /** More than one page target matches the same URL. */
    data class Many(val matchCount: Int, val pageCount: Int) : TargetMatch<Nothing>
}

/**
 * Decide how a fetch's targets match the inspected tab's URL.
 * Only "page" / "webview" targets are pages; an empty inspected
 * URL matches nothing; a match is an EXACT URL equality — never
 * a prefix or a fallback to another target, which could
 * silently attach the panel (and its recording) to a different
 * tab than the inspected one, including a secret tab. Pure, so
 * this security-critical decision is JVM-tested; the caller
 * still re-verifies the inspected snapshot after the fetch
 * before accepting a [TargetMatch.One].
 */
fun <T> matchTargets(
    inspectedUrl: String,
    targets: List<T>,
    typeOf: (T) -> String,
    urlOf: (T) -> String
): TargetMatch<T> {
    val pages = targets.filter { val t = typeOf(it); t == "page" || t == "webview" }
    val matches = if (inspectedUrl.isEmpty()) {
        emptyList()
    } else {
        pages.filter { urlOf(it) == inspectedUrl }
    }
    return when {
        matches.size == 1 -> TargetMatch.One(matches[0])
        matches.isEmpty() -> TargetMatch.None(pages.size)
        else -> TargetMatch.Many(matches.size, pages.size)
    }
}

/**
 * The search loop itself, with all I/O injected: [isAlive]
 * reports whether the owning session is still current (checked
 * before each attempt; a dead session's attempt observes
 * nothing and does not count as used), [observe] performs one
 * attempt's I/O and returns what it saw, and [pause] waits
 * between attempts (never after the final one). Pure apart
 * from the injected functions, so the loop's bookkeeping —
 * attempts used, last kind, end kind, terminal — is covered
 * by JVM tests with fakes.
 */
fun <T : Any> runTargetSearch(
    attempts: Int,
    isAlive: () -> Boolean,
    observe: () -> TargetAttempt<T>,
    pause: () -> Unit
): TargetSearchOutcome<T> {
    var state = TargetSearchState()
    var used = 0
    var lastKind = ObservationKind.NONE
    for (attempt in 0 until attempts) {
        if (!isAlive()) {
            return TargetSearchOutcome(
                null, null, terminal = true,
                attemptsUsed = used, lastKind = lastKind, end = SearchEnd.SESSION
            )
        }
        used = attempt + 1
        val observation = observe()
        lastKind = observationKind(observation)
        when (val step = targetSearchStep(state, observation, attempt)) {
            is TargetStep.Found -> return TargetSearchOutcome(
                step.target, null, terminal = false,
                attemptsUsed = used, lastKind = lastKind, end = SearchEnd.FOUND
            )
            is TargetStep.Stop -> return TargetSearchOutcome(
                null, step.error, terminal = true,
                attemptsUsed = used, lastKind = lastKind, end = SearchEnd.STOP
            )
            is TargetStep.Again -> state = step.state
        }
        if (attempt < attempts - 1) pause()
    }
    return TargetSearchOutcome(
        null, state.lastError,
        terminal = targetSearchTerminal(state),
        attemptsUsed = used, lastKind = lastKind, end = SearchEnd.EXHAUSTED
    )
}
