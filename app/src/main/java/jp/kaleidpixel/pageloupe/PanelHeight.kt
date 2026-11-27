package jp.kaleidpixel.pageloupe

/**
 * The DevTools panel's height arithmetic, in pure form so JVM
 * tests can pin it (PanelHeightTest). Three callers share these
 * numbers -- the drag handler, the configuration-change clamp
 * and the applied-height cap in MainActivity -- and they must
 * not drift apart.
 *
 * Two different heights are in play. The CHOSEN height is what
 * the user picked (default, drag, rotation clamp); it is
 * clamped to [MIN_DP, space - RESERVE_DP floored at
 * MAX_FLOOR_DP]. The APPLIED height is what the layout actually
 * gets: the chosen height capped so the bottom toolbar always
 * fits inside the column, even when the IME shrinks the column
 * below what the chosen clamps assume. The applied cap may
 * therefore sit below MIN_DP in a short column; the minimum
 * governs the chosen value only.
 */
object PanelHeight {

    /** Space (dp) left outside the panel: toolbar, divider, a slice of page. */
    const val RESERVE_DP = 110

    /** Smallest chosen height (dp). */
    const val MIN_DP = 200

    /** Floor (dp) for the chosen height's upper bound in roomy spaces. */
    const val MAX_FLOOR_DP = 260

    /** Upper bound for a chosen height in a space of [spacePx]. */
    fun maxChosen(spacePx: Int, reservePx: Int, floorPx: Int): Int =
        (spacePx - reservePx).coerceAtLeast(floorPx)

    /** Clamp a chosen height for a space of [spacePx]. */
    fun clampChosen(newPx: Int, spacePx: Int, minPx: Int, reservePx: Int, floorPx: Int): Int =
        newPx.coerceIn(minPx, maxChosen(spacePx, reservePx, floorPx))

    /**
     * The applied cap for a column of [columnPx] whose bottom
     * toolbar (plus its divider) takes [toolbarPx]: the lower of
     * the preferred bound (column minus the reserve, floored at
     * the chosen minimum) and the hard bound (column minus the
     * toolbar itself, so the toolbar always fits; the page area
     * absorbs the rest, down to zero).
     */
    fun appliedCap(columnPx: Int, toolbarPx: Int, reservePx: Int, minPx: Int): Int =
        minOf(
            (columnPx - reservePx).coerceAtLeast(minPx),
            (columnPx - toolbarPx).coerceAtLeast(0),
        )
}
