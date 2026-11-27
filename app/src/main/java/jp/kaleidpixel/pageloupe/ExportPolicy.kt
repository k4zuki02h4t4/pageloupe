package jp.kaleidpixel.pageloupe

/**
 * The console-export flow's pure decisions, split out so JVM
 * tests can pin them (ExportPolicyTest): which failures mean
 * the saved destination itself is gone, how a tree document
 * id reads as a folder name, how sizes and the result toast
 * are worded. No Android imports: the exception types and
 * formatting used here are all JDK.
 */
object ExportPolicy {

    /**
     * Whether an export failure means the destination itself
     * is unusable (the persisted grant was revoked, or the
     * picked folder was deleted) rather than a transient
     * provider failure. Only the former clears the saved
     * destination and asks for a new pick; a transient failure
     * keeps the destination and just reports the failure.
     *
     * Callers apply this ONLY to failures of destination
     * operations (resolving the folder, creating and writing
     * documents). A local recording's failure must never be
     * classified here: its exception types overlap, and
     * clearing a healthy destination over a local hiccup is
     * exactly the outcome this classification exists to avoid.
     */
    fun isAccessLoss(e: Throwable): Boolean =
        e is SecurityException || e is java.io.FileNotFoundException

    /**
     * Display-name fallback from a tree document id
     * ("primary:Documents/Downloads" reads as "Downloads");
     * null when nothing readable remains.
     */
    fun fallbackDisplayName(docId: String): String? =
        docId.substringAfterLast('/').substringAfterLast(':')
            .takeIf { it.isNotEmpty() }

    /** A recording's size for the export page's rows. */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 ->
            String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else ->
            String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Note appended whenever a partial copy could not be
     * cleaned up at the destination. */
    const val PARTIAL_LEFTOVER_NOTE =
        "未完成のファイルが保存先に残っている場合があります"

    /**
     * The completion toast for a move, for every ending. A
     * normal end is [resultMessage] plus the partial-leftover
     * note when a cleanup failed. An access-loss end names
     * what already moved and what failed before the loss, plus
     * the same note: a fragment can be left on either path,
     * and the user should hear about it on both. An aborted
     * end (an Error cut the move short) says so and itemizes
     * what landed, instead of reading as a clean finish; the
     * access-loss wording wins when both apply, since its
     * re-pick guidance is the actionable part.
     */
    fun completionMessage(
        moved: Int,
        failed: Int,
        skipped: Int,
        folderInvalid: Boolean,
        discardFailures: Int,
        aborted: Boolean,
    ): String {
        if (!folderInvalid && aborted) {
            return "書き出しが中断しました" +
                endingNotes(moved, failed, discardFailures)
        }
        if (!folderInvalid) {
            val base = resultMessage(moved, failed, skipped)
            return if (discardFailures > 0) {
                "$base（$PARTIAL_LEFTOVER_NOTE）"
            } else {
                base
            }
        }
        return "保存先にアクセスできませんでした。フォルダを選び直してください" +
            endingNotes(moved, failed, discardFailures)
    }

    /** The itemized notes the non-clean endings share: what
     * landed, what failed, and the leftover warning, in one
     * parenthesized run (empty when there is nothing to add). */
    private fun endingNotes(moved: Int, failed: Int, discardFailures: Int): String {
        val notes = mutableListOf<String>()
        if (moved > 0) notes.add("${moved}件は書き出し済みです")
        if (failed > 0) notes.add("${failed}件は失敗しました")
        if (discardFailures > 0) notes.add(PARTIAL_LEFTOVER_NOTE)
        return if (notes.isEmpty()) {
            ""
        } else {
            "（" + notes.joinToString("、") + "）"
        }
    }

    fun resultMessage(moved: Int, failed: Int, skipped: Int): String {
        val base = when {
            moved == 0 && failed == 0 && skipped > 0 ->
                "対象のファイルは既に削除されていました"
            failed == 0 -> "${moved}件を書き出しました"
            moved == 0 -> "書き出しに失敗しました"
            else -> "${moved}件を書き出し、${failed}件に失敗しました"
        }
        return if (skipped > 0 && (moved > 0 || failed > 0)) {
            "$base（${skipped}件は既に削除されていたため対象外）"
        } else {
            base
        }
    }
}
