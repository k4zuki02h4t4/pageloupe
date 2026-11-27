package jp.kaleidpixel.pageloupe

import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The console-export flow's process-wide state machine and
 * its serial executor (SEN-6: extracted from MainActivity's
 * companion, where the state sat among unrelated constants).
 *
 * Process-wide is the point: an Activity instance field
 * would reset on recreation (density / locale / uiMode)
 * while a move keeps running, reopening the double-run the
 * state exists to prevent; the picker can likewise outlive
 * the instance that opened it, and its answer is handled by
 * whichever instance is current then.
 *
 * Every transition runs on the UI thread (set before the
 * executor is handed work, cleared in its completion post),
 * so the state needs no synchronization beyond visibility
 * for the completion post.
 *
 * The legal shapes: IDLE -> PICKING (tryOpenPicker) ->
 * IDLE (pickerLaunchFailed, or onPickerResult with no
 * pending move) or -> MOVING (onPickerResult handing its
 * pending names to a move the caller starts through
 * tryBeginMove); IDLE -> MOVING (tryBeginMove) -> IDLE
 * (finishMove). A picker result that lands while a move is
 * in flight is DROPPED: applying it would swap the
 * destination under the grant the move is copying through.
 */
object ExportController {

    /**
     * Serializes every log-file operation (export writes,
     * tap-debug writes, clear-data deletions) on one daemon
     * thread, so a deletion can never be overtaken by an
     * in-flight export write. Process-wide, exactly one,
     * never shut down: an instance-owned executor would
     * silently break that guarantee whenever the Activity
     * is recreated — the old instance's queued export and
     * the new instance's clear-data delete would then run
     * on different threads.
     */
    val logFileExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "pl-log-files").apply { isDaemon = true }
        }

    /** True while a console-export move runs on the executor. */
    var moveInFlight: Boolean = false
        private set

    /** Recordings waiting for the folder picker's answer.
     * Non-null means the pick completes an export the user
     * already asked for; null means the pick only changes
     * the destination. */
    var pendingNames: List<String>? = null
        private set

    /** Whether the folder picker is currently open. One
     * picker at a time: a second launch could stack two
     * pickers whose results race the destination swap. */
    var pickerOpen: Boolean = false
        private set

    /**
     * The live instance that export-move completions aim
     * their view work at. A move can outlive the instance
     * that started it (recreation mid-move); the page
     * refresh and the picker re-open belong to whichever
     * instance is current when the move lands. Registered
     * in onResume, cleared in onDestroy.
     */
    var uiHost: WeakReference<MainActivity>? = null

    /**
     * Open the picker for [names] (null: destination change
     * only). Refused — false — while a move is copying
     * through the current grant, or while a picker is
     * already open.
     */
    fun tryOpenPicker(names: List<String>?): Boolean {
        if (moveInFlight || pickerOpen) return false
        pickerOpen = true
        pendingNames = names
        return true
    }

    /** The picker launch itself failed: nothing is open. */
    fun pickerLaunchFailed() {
        pickerOpen = false
        pendingNames = null
    }

    /**
     * A fresh Activity instance (not a recreation) found
     * picker state behind: the picker a previous instance
     * opened will never deliver its result here, so the
     * state must not stay and mute the picker for the
     * process's lifetime. (A recreation keeps both: the
     * result still arrives, to the new instance.)
     */
    fun abandonPicker() {
        pickerOpen = false
        pendingNames = null
    }

    /** What a returning picker result means for the flow. */
    class PickerOutcome(
        /** The names the pick was completing, if any. */
        val names: List<String>?,
        /** The pick landed mid-move and must be dropped. */
        val droppedForMove: Boolean
    )

    /**
     * Consume the picker's return: the picker is closed and
     * its pending names are handed over exactly once.
     */
    fun onPickerResult(): PickerOutcome {
        pickerOpen = false
        val names = pendingNames
        pendingNames = null
        return PickerOutcome(names, droppedForMove = moveInFlight)
    }

    /**
     * Start a move. Refused — false — while one is already
     * running (the double-run this state exists to prevent).
     */
    fun tryBeginMove(): Boolean {
        if (moveInFlight) return false
        moveInFlight = true
        return true
    }

    /** The running move has landed (its completion post). */
    fun finishMove() {
        moveInFlight = false
    }

    /** Test-only: return the machine to IDLE between cases. */
    fun resetForTest() {
        moveInFlight = false
        pendingNames = null
        pickerOpen = false
        uiHost = null
    }
}
