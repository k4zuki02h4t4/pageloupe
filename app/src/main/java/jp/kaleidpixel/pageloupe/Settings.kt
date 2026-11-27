package jp.kaleidpixel.pageloupe

import android.content.Context

object Settings {
    private const val PREFS = "pageloupe_settings"

    // Legacy key of the persisted remote-debug setting (up to
    // 0.39.0). Nothing reads it anymore; it is only removed (see
    // clearLegacyRemoteDebugKey). Never reuse this key name: a
    // stale true may still sit in the preferences of an install
    // that has not run the cleanup yet, and a future reader of
    // the same name would resurrect it.
    private const val KEY_REMOTE_DEBUG = "remote_debug"
    private const val KEY_CONSOLE_LOG_EXPORT = "console_log_export"
    private const val KEY_DEBUG_LOG = "debug_log"

    /**
     * Remote debugging (PC chrome://inspect access) is session
     * state, not a persisted preference. The owner decided on
     * 2026-11-27 that it must return to OFF at every launch
     * instead of persisting across restarts; that "launch" is
     * implemented as the process, the same unit as WebView's
     * own debugging flag. The value lives only in this
     * process-lifetime flag: a new process starts with it OFF,
     * the menu toggle turns it on for the rest of the process,
     * and nothing is written to disk, so no reader can observe
     * a stale ON. Activity recreation keeps the value; only
     * the process ending clears it. Known edge: if the system
     * keeps the process cached after its task is gone,
     * relaunching into that same process keeps the value --
     * the unit is the process, not the task.
     *
     * The effective WebView debugging flag is owned by
     * MainActivity.applyWebContentsDebugging(), the single
     * writer, which combines this flag with whether the
     * DevTools panel is open.
     */
    @Volatile private var remoteDebugValue = false

    fun remoteDebug(): Boolean = remoteDebugValue

    fun setRemoteDebug(value: Boolean) {
        remoteDebugValue = value
    }

    /**
     * Removes the legacy persisted remote-debug key (written by
     * versions up to 0.39.0) from the preferences, so an upgraded
     * install drops a stale ON at its first launch instead of
     * carrying it on disk indefinitely. Called from
     * MainActivity.onCreate. Idempotent: removing an absent key
     * changes nothing. The key is never read; this is disk
     * hygiene so no future code can resurrect the value.
     */
    fun clearLegacyRemoteDebugKey(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_REMOTE_DEBUG).apply()
    }

    /** Recording of the DevTools console log (JSON + text). Default OFF. */
    fun consoleLogRecording(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONSOLE_LOG_EXPORT, false)

    fun setConsoleLogRecording(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONSOLE_LOG_EXPORT, value).apply()
    }

    private const val KEY_EXPORT_FOLDER_URI = "export_folder_uri"

    /**
     * The SAF tree URI of the folder the user picked as the
     * console-export destination (its PageLoupe subfolder
     * receives the files), as a string; null until the first
     * pick. The persisted URI grant is what makes later
     * exports confirmation-free (owner design, 2026-11-23).
     */
    fun exportFolderUri(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXPORT_FOLDER_URI, null)

    fun setExportFolderUri(ctx: Context, value: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXPORT_FOLDER_URI, value).apply()
    }

    private const val KEY_BOOKMARK_SORT = "bookmark_sort"

    /**
     * The bookmark page's ordering, as a [BookmarkSort] name.
     * The default is title order (owner decision, 2026-11-23);
     * the page's sort picker is the only writer.
     */
    fun bookmarkSort(ctx: Context): BookmarkSort =
        BookmarkSort.fromName(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BOOKMARK_SORT, null),
        )

    fun setBookmarkSort(ctx: Context, value: BookmarkSort) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BOOKMARK_SORT, value.name).apply()
    }

    /** Developer debug log output (file + logcat + diagnostic toasts). Default OFF. */
    fun debugLog(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOG, false)

    fun setDebugLog(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEBUG_LOG, value).apply()
    }

    private const val KEY_CLEAR_HISTORY = "clear_cat_history"
    private const val KEY_CLEAR_COOKIES = "clear_cat_cookies"
    private const val KEY_CLEAR_CACHE = "clear_cat_cache"
    private const val KEY_CLEAR_TABS = "clear_cat_tabs"
    private const val KEY_CLEAR_SITE_SETTINGS = "clear_cat_site_settings"
    private const val KEY_CLEAR_LOGS = "clear_cat_logs"

    /** The six category selections of the clear-data page, as one snapshot. */
    data class ClearSelection(
        val history: Boolean,
        val cookies: Boolean,
        val cache: Boolean,
        val tabs: Boolean,
        val siteSettings: Boolean,
        val logs: Boolean,
    )

    /**
     * The user's clear-data selections. Every category
     * defaults to unchecked, and whatever the user toggles
     * is kept as-is for the next visit and the next launch
     * (owner decision, 2026-11-21).
     */
    fun clearSelection(ctx: Context): ClearSelection {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ClearSelection(
            history = p.getBoolean(KEY_CLEAR_HISTORY, false),
            cookies = p.getBoolean(KEY_CLEAR_COOKIES, false),
            cache = p.getBoolean(KEY_CLEAR_CACHE, false),
            tabs = p.getBoolean(KEY_CLEAR_TABS, false),
            siteSettings = p.getBoolean(KEY_CLEAR_SITE_SETTINGS, false),
            logs = p.getBoolean(KEY_CLEAR_LOGS, false),
        )
    }

    fun setClearSelection(ctx: Context, sel: ClearSelection) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CLEAR_HISTORY, sel.history)
            .putBoolean(KEY_CLEAR_COOKIES, sel.cookies)
            .putBoolean(KEY_CLEAR_CACHE, sel.cache)
            .putBoolean(KEY_CLEAR_TABS, sel.tabs)
            .putBoolean(KEY_CLEAR_SITE_SETTINGS, sel.siteSettings)
            .putBoolean(KEY_CLEAR_LOGS, sel.logs)
            .apply()
    }
}
