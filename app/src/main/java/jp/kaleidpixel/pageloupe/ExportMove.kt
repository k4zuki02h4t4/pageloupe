package jp.kaleidpixel.pageloupe

import java.io.File
import java.io.InputStream

/**
 * The console-export move loop, split out of MainActivity so
 * its rules are pinned by JVM tests (ExportMoveTest) instead
 * of only on device: the destination side is the [Target]
 * interface, which the app backs with the SAF tree and tests
 * back with a fake.
 *
 * The rules the loop owns:
 * - A name that is not an existing completed recording counts
 *   as skipped (it was listed when the user chose it, then
 *   pruned or cleared), never as a failure.
 * - The recording opens BEFORE any destination work: a local
 *   open failure is a plain per-file failure and is never
 *   classified as the destination's loss. Access loss
 *   (ExportPolicy.isAccessLoss) is judged only on destination
 *   operations' failures, and stops the move: the remaining
 *   files would all fail the same way.
 * - A created-but-unfinished document is discarded, whatever
 *   went wrong; an Error still propagates after the discard,
 *   with the move marked aborted in [Progress] so the caller's
 *   report never phrases it as a clean finish.
 * - The destination is resolved through a resolver the caller
 *   passes, after the existence partition: resolution failure
 *   is classified here (access loss invalidates the folder,
 *   anything else fails every present file at once), so the
 *   whole counting rule lives in this one place.
 * - The original is deleted only after its copy completes
 *   AND the destination's reported length agrees with the
 *   source's (SEN-22): a copy operation that reports success
 *   is not proof the bytes all landed. A destination that
 *   cannot report a length (negative) is taken on the copy's
 *   word — verification where the provider supports it,
 *   never a new failure mode where it does not. A mismatch
 *   is a per-file failure: the partial destination is
 *   discarded and the original stays.
 *   If the delete itself fails the file still counts as
 *   moved (the copy is complete and verified); the caller
 *   is told via [onOriginalRemains] and the leftover stays
 *   listed.
 * - Bookkeeping for a completed copy lands before any
 *   callback runs. All counts live in a caller-kept [Progress]:
 *   an Error that propagates out of the loop loses the
 *   returned Outcome, never the progress already recorded.
 */
object ExportMove {

    /** The destination side of a move. Handles are opaque to
     * the loop: whatever [create] returns identifies the
     * document to [copy] and [discard]. */
    interface Target {
        /** Creates one destination document; returns its handle. Throws on failure. */
        fun create(name: String, mimeType: String): Any

        /** Streams [input] into the document [handle] refers to. Throws on failure. */
        fun copy(handle: Any, input: InputStream)

        /** The length the destination reports for the
         * document [handle] refers to, or a negative value
         * when the destination cannot say (SEN-22). */
        fun writtenLength(handle: Any): Long

        /** Best-effort delete of a created document (a partial
         * copy). Returns whether it is gone. */
        fun discard(handle: Any): Boolean
    }

    /**
     * Mutable progress of a move, updated as files land. The
     * caller keeps the instance and reads it after [run]
     * returns -- or after it throws: an Error loses the
     * Outcome, not what is already recorded here.
     */
    class Progress {
        var moved = 0
        var failed = 0
        var skipped = 0
        var folderInvalid = false
        var discardFailures = 0
        /** The loop ended by propagation (an Error, or a
         * callback that threw), not by finishing: counts cover
         * what was attempted, and the ending must not be
         * phrased as a clean finish. */
        var aborted = false
        val movedNames = mutableListOf<String>()

        fun snapshot(): Outcome = Outcome(
            moved, failed, skipped, movedNames.toList(),
            folderInvalid, discardFailures, aborted
        )
    }

    data class Outcome(
        val moved: Int,
        val failed: Int,
        val skipped: Int,
        val movedNames: List<String>,
        /** The destination itself turned out unusable mid-move. */
        val folderInvalid: Boolean,
        /** Partial copies whose cleanup delete did not succeed. */
        val discardFailures: Int,
        /** The loop ended by propagation, not by finishing. */
        val aborted: Boolean,
    )

    /**
     * Moves the named recordings out of [sourceDir] to the
     * destination [resolveTarget] provides, recording into
     * [progress] as files land. [openSource] opens a recording
     * (the app uses the file's own stream; tests substitute
     * failures). [onFailure] receives each failed recording's
     * name and error, and [onResolveFailure] a transient
     * resolution failure's error, both for the caller's debug
     * log. Returns [progress]'s snapshot.
     */
    fun run(
        names: List<String>,
        sourceDir: File,
        resolveTarget: () -> Target,
        progress: Progress = Progress(),
        openSource: (File) -> InputStream = { it.inputStream() },
        onFailure: (String, Throwable) -> Unit = { _, _ -> },
        onOriginalRemains: (String) -> Unit = {},
        onResolveFailure: (Throwable) -> Unit = {},
    ): Outcome {
        try {
            // Existence is partitioned here, once: a name that
            // is not an existing completed recording counts as
            // skipped (it was listed when the user chose it,
            // then pruned or cleared), never as a failure --
            // and with nothing present, the destination is
            // never resolved for.
            val present = names.filter { name ->
                File(sourceDir, name).isFile &&
                    RecordedLogsPolicy.isRecordingFile(name)
            }
            progress.skipped += names.size - present.size
            if (present.isEmpty()) return progress.snapshot()
            // Resolving the destination first exercises it
            // before any file moves, so a dead destination
            // fails as a whole instead of once per file. Its
            // failure is classified by the same rule as the
            // loop's own: access loss invalidates the folder,
            // anything else fails every present file at once.
            // An Error propagates (marking the move aborted),
            // like the loop's rule.
            val target = try {
                resolveTarget()
            } catch (e: Exception) {
                if (ExportPolicy.isAccessLoss(e)) {
                    progress.folderInvalid = true
                } else {
                    progress.failed += present.size
                    onResolveFailure(e)
                }
                return progress.snapshot()
            }
            for (name in present) {
                val file = File(sourceDir, name)
                if (!file.isFile || !RecordedLogsPolicy.isRecordingFile(name)) {
                    // Vanished between the partition and its
                    // turn: not present after all, so skipped.
                    progress.skipped++
                    continue
                }
                val sourceLength = file.length()
                val input = try {
                    openSource(file)
                } catch (e: Exception) {
                    progress.failed++
                    onFailure(name, e)
                    continue
                }
                var handle: Any? = null
                var failure: Throwable? = null
                try {
                    input.use { inp ->
                        val h = target.create(name, ExportSelection.mimeTypeOf(name))
                        handle = h
                        target.copy(h, inp)
                    }
                    // Verify before the original is deleted
                    // (SEN-22, class doc): a reported length
                    // that disagrees means the copy did not
                    // all land, whatever copy() said.
                    val destLength = target.writtenLength(handle!!)
                    // A reported 0 is "cannot report", not a
                    // mismatch (review round 2, SEN-13):
                    // cloud document providers answer
                    // COLUMN_SIZE with 0 or a stale value for
                    // a just-written file, and failing every
                    // export to such a provider would make
                    // the feature unusable there while the
                    // original is still safe. Only a positive
                    // length that disagrees is evidence the
                    // copy did not land. (A genuinely empty
                    // source reports 0 == 0 and passes either
                    // way.)
                    if (destLength > 0 && destLength != sourceLength) {
                        failure = java.io.IOException(
                            "export copy length mismatch for $name:" +
                                " source $sourceLength, destination $destLength"
                        )
                    }
                } catch (e: Throwable) {
                    failure = e
                }
                if (failure == null) {
                    // The copy is complete. The bookkeeping lands
                    // BEFORE the callbacks, outside the try: a
                    // throwing callback must not turn a completed
                    // copy into a reported failure.
                    val remains = !file.delete()
                    progress.moved++
                    progress.movedNames.add(name)
                    if (remains) onOriginalRemains(name)
                    continue
                }
                val e = failure
                val h = handle
                if (h != null) {
                    // A discard that itself throws (a Target
                    // breaking its contract) counts as a failed
                    // discard, never as a second failure path.
                    val gone = try {
                        target.discard(h)
                    } catch (d: Throwable) {
                        false
                    }
                    if (!gone) progress.discardFailures++
                }
                if (e !is Exception) throw e
                // Bookkeeping before the callback, like the success
                // path: a throwing callback must not lose the
                // access-loss verdict.
                progress.failed++
                if (ExportPolicy.isAccessLoss(e)) {
                    progress.folderInvalid = true
                }
                onFailure(name, e)
                if (progress.folderInvalid) break
            }
        } catch (t: Throwable) {
            // Whatever escapes (an Error from the resolver or a
            // Target, a callback that threw) ends the move here:
            // mark it so the report does not phrase a cut-short
            // move as a clean finish, then let it propagate.
            progress.aborted = true
            throw t
        }
        return progress.snapshot()
    }
}
