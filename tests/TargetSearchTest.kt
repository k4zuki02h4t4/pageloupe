package tests

import jp.kaleidpixel.pageloupe.ObservationKind
import jp.kaleidpixel.pageloupe.SearchEnd
import jp.kaleidpixel.pageloupe.TargetAttempt
import jp.kaleidpixel.pageloupe.TargetMatch
import jp.kaleidpixel.pageloupe.TargetSearchOutcome
import jp.kaleidpixel.pageloupe.TargetSearchState
import jp.kaleidpixel.pageloupe.matchTargets
import jp.kaleidpixel.pageloupe.observationKind
import jp.kaleidpixel.pageloupe.TargetStep
import jp.kaleidpixel.pageloupe.runTargetSearch
import jp.kaleidpixel.pageloupe.searchTraceLine
import jp.kaleidpixel.pageloupe.targetSearchStep
import jp.kaleidpixel.pageloupe.targetSearchTerminal

/**
 * JVM unit tests for the DevTools target-search classification,
 * loop skeleton, and target matching (TargetSearch.kt): the
 * retry loop's per-attempt outcomes, its end-of-loop terminal
 * decision, its bookkeeping (attempts used, last kind, end
 * kind, pauses), and the exact-match decision over a fetch's
 * targets, without any Android I/O. The MainActivity I/O
 * around it — the snapshots, the socket fetch, and the
 * post-fetch re-verification comparing tab identity as well
 * as URL — is not covered here; it is exercised on device.
 */

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    checks++
    if (cond) {
        println("ok   $name")
    } else {
        failures++
        println("FAIL $name $detail")
    }
}

/** Run a sequence of attempts through the classifier; returns the final state and any Stop/Found step. */
private fun runSequence(
    attempts: List<TargetAttempt<String>>
): Pair<TargetSearchState, TargetStep<String>?> {
    var state = TargetSearchState()
    for ((i, a) in attempts.withIndex()) {
        when (val step = targetSearchStep(state, a, i)) {
            is TargetStep.Again -> state = step.state
            else -> return state to step
        }
    }
    return state to null
}

fun main() {
    // A verified single match is found at once, carrying the target itself.
    run {
        val step = targetSearchStep(TargetSearchState(), TargetAttempt.Verified("the-target"), 0)
        check("verified is found", step == TargetStep.Found("the-target"), "$step")
    }

    // App-side duplicates stop the search immediately, on any attempt.
    run {
        val s0 = targetSearchStep(TargetSearchState(), TargetAttempt.AmbiguousTabs(2), 0)
        val s5 = targetSearchStep(TargetSearchState(), TargetAttempt.AmbiguousTabs(3), 5)
        check(
            "app duplicates stop at once",
            s0 is TargetStep.Stop && s0.error.contains("2件"),
            "$s0"
        )
        check(
            "app duplicates stop on any attempt",
            s5 is TargetStep.Stop && s5.error.contains("3件"),
            "$s5"
        )
    }

    // No match: retry with a not-found error; the search is not terminal.
    run {
        val (state, early) = runSequence(listOf(TargetAttempt.NotListed(3)))
        check("not found retries", early == null, "$early")
        check(
            "not found state",
            state.everSnapshotted && !state.rebuildFutile &&
                state.lastError == "対象ページが見つかりません（候補 3 件）",
            "$state"
        )
        check("not found not terminal", !targetSearchTerminal(state))
    }

    // No match stays a retry on later attempts too.
    run {
        val step = targetSearchStep(TargetSearchState(), TargetAttempt.NotListed(1), 3)
        check(
            "not found retries on later attempts",
            step is TargetStep.Again && !step.state.rebuildFutile,
            "$step"
        )
    }

    // Registered-side duplicates get retries first — as a plain
    // not-found outcome — then stop as ambiguous, and stay stopped.
    run {
        val s0 = targetSearchStep(TargetSearchState(), TargetAttempt.ManyMatches(2, 5), 0)
        val s1 = targetSearchStep(TargetSearchState(), TargetAttempt.ManyMatches(2, 5), 1)
        val s2 = targetSearchStep(TargetSearchState(), TargetAttempt.ManyMatches(2, 5), 2)
        val s3 = targetSearchStep(TargetSearchState(), TargetAttempt.ManyMatches(3, 7), 3)
        check(
            "list duplicates retry as not found",
            s0 is TargetStep.Again && s0.state.lastError == "対象ページが見つかりません（候補 5 件）" &&
                !s0.state.rebuildFutile && s1 is TargetStep.Again,
            "$s0 $s1"
        )
        check(
            "list duplicates stop on third attempt",
            s2 is TargetStep.Stop && s2.error.contains("2件"),
            "$s2"
        )
        check(
            "list duplicates stay stopped",
            s3 is TargetStep.Stop && s3.error.contains("3件"),
            "$s3"
        )
    }

    // A changed target is a rebuild-futile failure: terminal, with its own error.
    run {
        val (state, _) = runSequence(listOf(TargetAttempt.VerifyFailed(uiSilent = false)))
        check(
            "verify changed state",
            state.rebuildFutile && state.lastError == "検査対象が接続中に変化しました",
            "$state"
        )
        check("verify changed terminal", targetSearchTerminal(state))
    }

    // A silent verification is also a rebuild-futile failure.
    run {
        val (state, _) = runSequence(listOf(TargetAttempt.VerifyFailed(uiSilent = true)))
        check(
            "verify silent state",
            state.rebuildFutile && state.lastError == "UI スレッドが応答しません",
            "$state"
        )
        check("verify silent terminal", targetSearchTerminal(state))
    }

    // The latest attempt decides: a not-found AFTER a rebuild-
    // futile failure clears the flag (the target may simply be
    // late), and a rebuild-futile failure after a not-found sets
    // it — with the changed-target error kept, never overwritten
    // by a generic not-found.
    run {
        val (state, _) = runSequence(
            listOf(TargetAttempt.VerifyFailed(false), TargetAttempt.NotListed(1))
        )
        check(
            "not found after changed target clears flag",
            !state.rebuildFutile && state.lastError!!.startsWith("対象ページが見つかりません"),
            "$state"
        )
        check("not found after change not terminal", !targetSearchTerminal(state))
    }
    run {
        val (state, _) = runSequence(
            listOf(TargetAttempt.NotListed(1), TargetAttempt.VerifyFailed(false))
        )
        check(
            "changed target after not found keeps its error",
            state.rebuildFutile && state.lastError == "検査対象が接続中に変化しました",
            "$state"
        )
        check("changed target after not found terminal", targetSearchTerminal(state))
    }

    // Silence before any snapshot: not a rebuild-futile failure, but the
    // end-of-loop rule still makes the search terminal.
    run {
        val (state, _) = runSequence(listOf(TargetAttempt.NoSnapshot, TargetAttempt.NoSnapshot))
        check(
            "silent from the start",
            !state.everSnapshotted && !state.rebuildFutile &&
                state.lastError == "UI スレッドが応答しません",
            "$state"
        )
        check("silent from the start terminal", targetSearchTerminal(state))
    }

    // Silence AFTER a successful snapshot counts as a rebuild-
    // futile failure: the UI answered, then stopped — a rebuild cannot
    // fix that and would only destroy the tab's state.
    run {
        val (state, _) = runSequence(
            listOf(TargetAttempt.NotListed(1), TargetAttempt.NoSnapshot)
        )
        check(
            "silent after snapshot is rebuild-futile",
            state.everSnapshotted && state.rebuildFutile &&
                state.lastError == "UI スレッドが応答しません",
            "$state"
        )
        check("silent after snapshot terminal", targetSearchTerminal(state))
    }

    // ...but the search recovers if a later attempt answers
    // again with an ordinary outcome.
    run {
        val (state, _) = runSequence(
            listOf(
                TargetAttempt.NotListed(1),
                TargetAttempt.NoSnapshot,
                TargetAttempt.NotListed(1)
            )
        )
        check(
            "answer after silence clears flag",
            state.everSnapshotted && !state.rebuildFutile &&
                state.lastError!!.startsWith("対象ページが見つかりません"),
            "$state"
        )
        check("answer after silence not terminal", !targetSearchTerminal(state))
    }

    // A fetch failure is an ordinary retry and clears a prior
    // futile failure, keeping the exception's message — including
    // a null message.
    run {
        val (state, _) = runSequence(
            listOf(TargetAttempt.VerifyFailed(false), TargetAttempt.FetchFailed("boom"))
        )
        check(
            "fetch failure clears rebuild-futile flag",
            state.everSnapshotted && !state.rebuildFutile && state.lastError == "boom",
            "$state"
        )
        check("fetch failure not terminal", !targetSearchTerminal(state))
    }
    run {
        val (state, _) = runSequence(listOf(TargetAttempt.FetchFailed(null)))
        check(
            "fetch failure with null message",
            state.everSnapshotted && !state.rebuildFutile && state.lastError == null,
            "$state"
        )
    }

    // Silence does not clear a rebuild-futile failure recorded earlier.
    run {
        val (state, _) = runSequence(
            listOf(TargetAttempt.VerifyFailed(false), TargetAttempt.NoSnapshot)
        )
        check("silence keeps rebuild-futile failure", state.rebuildFutile, "$state")
        check("silence after changed target terminal", targetSearchTerminal(state))
    }

    // --- The loop skeleton (runTargetSearch) with fakes ---

    // Found on the second attempt: one pause, target carried through.
    run {
        var pauses = 0
        val observations = ArrayDeque(
            listOf(TargetAttempt.NotListed(1), TargetAttempt.Verified("the-target"))
        )
        val outcome = runTargetSearch(
            attempts = 8,
            isAlive = { true },
            observe = { observations.removeFirst() },
            pause = { pauses++ }
        )
        check(
            "loop found on second attempt",
            outcome.end == SearchEnd.FOUND && outcome.target == "the-target" &&
                !outcome.terminal && outcome.attemptsUsed == 2 &&
                outcome.lastKind == ObservationKind.VERIFIED,
            "$outcome"
        )
        check("loop found paused once", pauses == 1, "$pauses")
    }

    // The session dies before the third attempt: that attempt
    // observes nothing and does not count as used.
    run {
        var aliveChecks = 0
        var pauses = 0
        val outcome = runTargetSearch(
            attempts = 8,
            isAlive = { ++aliveChecks < 3 },
            observe = { TargetAttempt.NotListed(1) },
            pause = { pauses++ }
        )
        check(
            "loop session death",
            outcome.end == SearchEnd.SESSION && outcome.terminal &&
                outcome.attemptsUsed == 2 &&
                outcome.lastKind == ObservationKind.NOT_LISTED,
            "$outcome"
        )
        check("loop session death paused twice", pauses == 2, "$pauses")
    }

    // Exhaustion on not-found: all attempts used, one pause fewer
    // than attempts (never after the final attempt), not terminal.
    run {
        var pauses = 0
        val outcome = runTargetSearch(
            attempts = 4,
            isAlive = { true },
            observe = { TargetAttempt.NotListed(2) },
            pause = { pauses++ }
        )
        check(
            "loop exhausted not terminal",
            outcome.end == SearchEnd.EXHAUSTED && !outcome.terminal &&
                outcome.attemptsUsed == 4 && outcome.error != null,
            "$outcome"
        )
        check("loop never pauses after final attempt", pauses == 3, "$pauses")
    }

    // Exhaustion after a rebuild-futile failure is terminal.
    run {
        val outcome = runTargetSearch(
            attempts = 2,
            isAlive = { true },
            observe = { TargetAttempt.VerifyFailed(uiSilent = false) },
            pause = {}
        )
        check(
            "loop exhausted terminal",
            outcome.end == SearchEnd.EXHAUSTED && outcome.terminal &&
                outcome.lastKind == ObservationKind.VERIFY_FAILED,
            "$outcome"
        )
    }

    // A Stop ends the search on the spot: no pause, no more attempts.
    run {
        var pauses = 0
        val outcome = runTargetSearch(
            attempts = 8,
            isAlive = { true },
            observe = { TargetAttempt.AmbiguousTabs(2) },
            pause = { pauses++ }
        )
        check(
            "loop stop at once",
            outcome.end == SearchEnd.STOP && outcome.terminal &&
                outcome.attemptsUsed == 1 && pauses == 0,
            "$outcome"
        )
    }

    // The trace line's exact format.
    run {
        val outcome = runTargetSearch(
            attempts = 8,
            isAlive = { true },
            observe = { TargetAttempt.NotListed(1) },
            pause = {}
        )
        check(
            "trace line format",
            searchTraceLine(8, outcome) ==
                "target search: limit=8 used=8 last=NotListed end=exhausted terminal=false",
            searchTraceLine(8, outcome)
        )
    }

    // The session is already dead before the first attempt:
    // nothing is observed, nothing counts as used, and the
    // trace's "none" kind goes through its only path.
    run {
        val outcome = runTargetSearch(
            attempts = 8,
            isAlive = { false },
            observe = { TargetAttempt.NotListed(1) },
            pause = {}
        )
        check(
            "loop dead before first attempt",
            outcome.end == SearchEnd.SESSION && outcome.terminal &&
                outcome.attemptsUsed == 0 &&
                outcome.lastKind == ObservationKind.NONE,
            "$outcome"
        )
        check(
            "trace line for dead session",
            searchTraceLine(8, outcome) ==
                "target search: limit=8 used=0 last=none end=session terminal=true",
            searchTraceLine(8, outcome)
        )
    }

    // Found on the very first attempt: no pause at all.
    run {
        var pauses = 0
        val outcome = runTargetSearch(
            attempts = 8,
            isAlive = { true },
            observe = { TargetAttempt.Verified("first") },
            pause = { pauses++ }
        )
        check(
            "loop found on first attempt",
            outcome.end == SearchEnd.FOUND && outcome.attemptsUsed == 1 &&
                pauses == 0,
            "$outcome pauses=$pauses"
        )
        check(
            "trace line for found",
            searchTraceLine(8, outcome) ==
                "target search: limit=8 used=1 last=Verified end=found terminal=false",
            searchTraceLine(8, outcome)
        )
    }

    // A single-attempt search never pauses.
    run {
        var pauses = 0
        val outcome = runTargetSearch(
            attempts = 1,
            isAlive = { true },
            observe = { TargetAttempt.NotListed(1) },
            pause = { pauses++ }
        )
        check(
            "loop single attempt",
            outcome.end == SearchEnd.EXHAUSTED && outcome.attemptsUsed == 1 &&
                pauses == 0,
            "$outcome pauses=$pauses"
        )
    }

    // The UI never answers at all: exhaustion is terminal
    // through the never-snapshotted path.
    run {
        val outcome = runTargetSearch(
            attempts = 3,
            isAlive = { true },
            observe = { TargetAttempt.NoSnapshot },
            pause = {}
        )
        check(
            "loop no snapshot is terminal",
            outcome.end == SearchEnd.EXHAUSTED && outcome.terminal &&
                outcome.lastKind == ObservationKind.NO_SNAPSHOT,
            "$outcome"
        )
    }

    // The trace line for a stopped search.
    run {
        val outcome = runTargetSearch(
            attempts = 8,
            isAlive = { true },
            observe = { TargetAttempt.AmbiguousTabs(2) },
            pause = {}
        )
        check(
            "trace line for stop",
            searchTraceLine(8, outcome) ==
                "target search: limit=8 used=1 last=AmbiguousTabs end=stop terminal=true",
            searchTraceLine(8, outcome)
        )
    }

    // Every observation maps to its own kind.
    run {
        val kinds = listOf(
            observationKind(TargetAttempt.NoSnapshot) to ObservationKind.NO_SNAPSHOT,
            observationKind(TargetAttempt.AmbiguousTabs(2)) to ObservationKind.AMBIGUOUS_TABS,
            observationKind(TargetAttempt.FetchFailed("x")) to ObservationKind.FETCH_FAILED,
            observationKind(TargetAttempt.NotListed(1)) to ObservationKind.NOT_LISTED,
            observationKind(TargetAttempt.ManyMatches(2, 5)) to ObservationKind.MANY_MATCHES,
            observationKind(TargetAttempt.Verified("t")) to ObservationKind.VERIFIED,
            observationKind(TargetAttempt.VerifyFailed(uiSilent = true)) to
                ObservationKind.VERIFY_FAILED
        )
        check(
            "observation kind mapping",
            kinds.all { (actual, expected) -> actual == expected },
            "$kinds"
        )
    }

    // An inconsistent outcome cannot be constructed: a target
    // without FOUND, and FOUND without a target, both throw.
    run {
        val noTargetFound = try {
            TargetSearchOutcome<String>(
                null, null, terminal = false, attemptsUsed = 1,
                lastKind = ObservationKind.VERIFIED, end = SearchEnd.FOUND
            )
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        val targetNotFound = try {
            TargetSearchOutcome(
                "t", null, terminal = false, attemptsUsed = 1,
                lastKind = ObservationKind.NOT_LISTED, end = SearchEnd.EXHAUSTED
            )
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        check(
            "outcome invariants enforced",
            noTargetFound && targetNotFound,
            "noTargetFound=$noTargetFound targetNotFound=$targetNotFound"
        )
    }

    // Every other init requirement rejects its inconsistent
    // combination too (error correspondence and terminal
    // correspondence), so a require cannot be dropped silently.
    run {
        fun throwsIae(build: () -> TargetSearchOutcome<String>): Boolean = try {
            build()
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        val foundWithError = throwsIae {
            TargetSearchOutcome(
                "t", "boom", terminal = false, attemptsUsed = 1,
                lastKind = ObservationKind.VERIFIED, end = SearchEnd.FOUND
            )
        }
        val sessionWithError = throwsIae {
            TargetSearchOutcome(
                null, "boom", terminal = true, attemptsUsed = 0,
                lastKind = ObservationKind.NONE, end = SearchEnd.SESSION
            )
        }
        val stopWithoutError = throwsIae {
            TargetSearchOutcome(
                null, null, terminal = true, attemptsUsed = 1,
                lastKind = ObservationKind.AMBIGUOUS_TABS, end = SearchEnd.STOP
            )
        }
        val foundTerminal = throwsIae {
            TargetSearchOutcome(
                "t", null, terminal = true, attemptsUsed = 1,
                lastKind = ObservationKind.VERIFIED, end = SearchEnd.FOUND
            )
        }
        val stopNotTerminal = throwsIae {
            TargetSearchOutcome(
                null, "boom", terminal = false, attemptsUsed = 1,
                lastKind = ObservationKind.AMBIGUOUS_TABS, end = SearchEnd.STOP
            )
        }
        val sessionNotTerminal = throwsIae {
            TargetSearchOutcome(
                null, null, terminal = false, attemptsUsed = 0,
                lastKind = ObservationKind.NONE, end = SearchEnd.SESSION
            )
        }
        check(
            "outcome error invariants enforced",
            foundWithError && sessionWithError && stopWithoutError,
            "foundWithError=$foundWithError sessionWithError=$sessionWithError " +
                "stopWithoutError=$stopWithoutError"
        )
        check(
            "outcome terminal invariants enforced",
            foundTerminal && stopNotTerminal && sessionNotTerminal,
            "foundTerminal=$foundTerminal stopNotTerminal=$stopNotTerminal " +
                "sessionNotTerminal=$sessionNotTerminal"
        )
    }

    // --- The matching decision (matchTargets) ---

    // Fixtures are (type, url) pairs.
    run {
        val targets = listOf(
            "page" to "https://a.example/",
            "webview" to "https://b.example/",
            "iframe" to "https://a.example/"
        )
        val one = matchTargets("https://a.example/", targets, { it.first }, { it.second })
        check(
            "match one exact page",
            one is TargetMatch.One && one.target.second == "https://a.example/" &&
                one.target.first == "page",
            "$one"
        )
        val webview = matchTargets("https://b.example/", targets, { it.first }, { it.second })
        check("match webview counts as page", webview is TargetMatch.One, "$webview")
        // A prefix is not a match: exact equality only.
        val prefix = matchTargets("https://a.example", targets, { it.first }, { it.second })
        check(
            "match requires exact url",
            prefix is TargetMatch.None && prefix.pageCount == 2,
            "$prefix"
        )
        // An empty inspected URL matches nothing, not even an
        // empty-URL target.
        val empty = matchTargets(
            "",
            listOf("page" to ""),
            { it.first },
            { it.second }
        )
        check(
            "match empty url matches nothing",
            empty is TargetMatch.None && empty.pageCount == 1,
            "$empty"
        )
        // The reverse direction is not a match either: a
        // candidate that is a prefix of the inspected URL must
        // not match (an inspected /private page must not attach
        // to the target registered for its parent path).
        val reversePrefix = matchTargets(
            "https://a.example/private",
            targets,
            { it.first },
            { it.second }
        )
        check(
            "match reverse prefix is not a match",
            reversePrefix is TargetMatch.None && reversePrefix.pageCount == 2,
            "$reversePrefix"
        )
        // Case-only and fragment-only differences are not matches.
        val caseDiff = matchTargets("HTTPS://A.EXAMPLE/", targets, { it.first }, { it.second })
        check("match is case sensitive", caseDiff is TargetMatch.None, "$caseDiff")
        val fragmentDiff = matchTargets(
            "https://a.example/#frag",
            targets,
            { it.first },
            { it.second }
        )
        check(
            "match fragment difference is not a match",
            fragmentDiff is TargetMatch.None,
            "$fragmentDiff"
        )
        // Two pages at the same URL are Many, with page-only counts.
        val many = matchTargets(
            "https://a.example/",
            targets + ("page" to "https://a.example/"),
            { it.first },
            { it.second }
        )
        check(
            "match many counts pages only",
            many is TargetMatch.Many && many.matchCount == 2 && many.pageCount == 3,
            "$many"
        )
        // No targets at all.
        val none = matchTargets(
            "https://a.example/",
            emptyList<Pair<String, String>>(),
            { it.first },
            { it.second }
        )
        check(
            "match no targets",
            none is TargetMatch.None && none.pageCount == 0,
            "$none"
        )
    }

    println("TargetSearchTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
