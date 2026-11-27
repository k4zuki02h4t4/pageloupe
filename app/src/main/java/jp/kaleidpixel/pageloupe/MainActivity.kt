package jp.kaleidpixel.pageloupe

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), BrowserTab.Listener, PageTranslator.Host {

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTab: BrowserTab? = null
    /**
     * Digest of the LAUNCH intent's link, once this instance chain
     * has consumed it. getIntent() returns the original launch
     * intent on every recreation; the digest (fixed length, so the
     * state Bundle stays bounded) tells the restore branch whether
     * that link still needs opening. Links delivered via
     * onNewIntent never touch this.
     */
    private var launchLinkDigest: String? = null

    private lateinit var root: FrameLayout
    private lateinit var focusSink: View
    private lateinit var pageContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var addressInput: EditText
    private lateinit var lockIcon: ImageView
    private lateinit var tabCountText: TextView
    private lateinit var devToolsBtn: ImageButton
    private lateinit var reloadBtn: ImageButton
    private lateinit var switcher: FrameLayout
    private lateinit var switcherGrid: GridLayout
    private lateinit var switcherTitle: TextView
    private lateinit var switcherSearchInput: EditText
    private lateinit var tabMenuPanel: LinearLayout
    private lateinit var tabMenuScrim: View
    private lateinit var toggleNormalSeg: FrameLayout
    private lateinit var toggleSecretSeg: FrameLayout
    private lateinit var toggleNormalIcon: ImageView
    private lateinit var toggleSecretIcon: ImageView
    /** Which list the tab switcher shows: false = normal tabs, true = secret tabs. */
    private var switcherSecretMode = false
    private var switcherQuery = ""
    private lateinit var menuSheet: LinearLayout
    private lateinit var menuScrim: View
    private lateinit var menuList: LinearLayout
    private lateinit var menuScroll: android.widget.ScrollView
    private lateinit var menuFooter: LinearLayout
    private lateinit var menuFooterDivider: View
    private var menuBackBtn: android.widget.ImageButton? = null
    private var menuForwardBtn: android.widget.ImageButton? = null
    private var menuShareBtn: android.widget.ImageButton? = null
    // Browsing history and the saved open-tab session (SEN-154).
    private var historyStore = HistoryStore()
    private lateinit var historyWriter: QueuedFileWriter
    private lateinit var sessionWriter: QueuedFileWriter
    /** True while a restore/rebuild loop is recreating tabs: session saves are suppressed until the loop completes, so a half-rebuilt tab list never reaches the file. */
    private var restoringTabs = false
    private lateinit var historyPage: FrameLayout
    private lateinit var historyListView: android.widget.ListView
    private lateinit var historyEmptyText: TextView
    private var historyItems: List<HistoryListItem> = emptyList()
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var historySearchInput: EditText
    private var historyQuery = ""
    // Bookmarks (owner spec, 2026-11-23): a nestable folder
    // tree of saved pages, its own full-screen page, and the
    // menu footer's add/remove toggle.
    private var bookmarkStore = BookmarkStore()
    private lateinit var bookmarkWriter: QueuedFileWriter
    private lateinit var bookmarksPage: FrameLayout
    private lateinit var bookmarkListView: android.widget.ListView
    private lateinit var bookmarkEmptyText: TextView
    private var bookmarkItems: List<BookmarkListItem> = emptyList()
    private lateinit var bookmarkAdapter: BookmarkAdapter
    private lateinit var bookmarkSearchInput: EditText
    private lateinit var bookmarkPathRow: LinearLayout
    private lateinit var bookmarkPathScroll: android.widget.HorizontalScrollView
    private lateinit var bookmarkPathSegments: LinearLayout
    private lateinit var bookmarkDeleteAllButton: android.widget.ImageButton
    private var bookmarkQuery = ""
    /** The folder the bookmark page is showing (ROOT_ID at the top level). */
    private var bookmarkFolderId = BookmarkStore.ROOT_ID
    /** The breadcrumb state last drawn by renderBookmarkPath
     * (searching flag + segment id/label pairs); the views are
     * rebuilt only when this changes (SEN-255). */
    private var bookmarkPathRendered: Pair<Boolean, List<Pair<Long, String>>>? = null
    /** Decoded bookmark favicons by URL (entry-capped: the
     * store can hold thousands of bookmarks, the cache only
     * the recently shown icons); filled lazily when a row
     * binds and when an icon is captured. Rows bind from here
     * and fall back to the globe icon while an icon is
     * absent. */
    private val faviconCache = android.util.LruCache<String, android.graphics.Bitmap>(256)
    private var menuBookmarkBtn: android.widget.ImageButton? = null
    private val faviconDir: java.io.File get() = java.io.File(filesDir, FaviconStore.DIR_NAME)
    private lateinit var clearDataPage: FrameLayout
    private lateinit var settingsPage: FrameLayout
    private lateinit var debugLogCheck: android.widget.CheckBox
    private lateinit var debugLogRow: LinearLayout
    private lateinit var exportPage: FrameLayout
    private lateinit var exportListColumn: LinearLayout
    private lateinit var exportDestValue: TextView
    private lateinit var exportEmptyText: TextView
    private lateinit var exportRunButton: TextView
    private lateinit var exportChangeButton: TextView
    private var clearPeriod = HistoryPeriod.ALL
    private lateinit var clearPeriodValue: TextView
    private lateinit var clearHistoryDesc: TextView
    private lateinit var clearCookiesDesc: TextView
    private lateinit var clearHistoryCheck: android.widget.CheckBox
    private lateinit var clearCookiesCheck: android.widget.CheckBox
    private lateinit var clearCacheCheck: android.widget.CheckBox
    private lateinit var clearTabsCheck: android.widget.CheckBox
    private lateinit var clearTabsDesc: TextView
    private lateinit var clearSiteSettingsCheck: android.widget.CheckBox
    private lateinit var clearLogsCheck: android.widget.CheckBox
    /** The clear-data page's category rows (row, title, box),
     * collected by clearCategoryRow so showClearDataPage can
     * re-apply the rows' checkbox semantics after restoring
     * the saved selection into the boxes. */
    private val clearRows =
        mutableListOf<Triple<LinearLayout, String, android.widget.CheckBox>>()
    private lateinit var clearExecuteButton: TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private var addressEditing = false

    /** The text the last focus gain put in the address bar. While
     *  the bar still shows exactly this, the user has not typed
     *  anything in this edit session. */
    private var addressFocusText = ""

    // Real Chrome DevTools (hosted frontend + local proxy), bottom panel
    private var proxy: DevToolsProxy? = null
    private lateinit var dtPanel: LinearLayout
    private lateinit var realWebView: WebView
    private lateinit var realStatus: TextView
    private lateinit var realTargetInfo: TextView
    private var dtOpen = false

    /**
     * Whether the status line currently shows the transient
     * "loading the frontend" message. Only that message is
     * hidden when the frontend's load completes; every other
     * status (connecting, errors, recording) stays until it
     * is replaced. Tracked as state, not by comparing the
     * displayed text, so rewording a message cannot break
     * the behavior.
     */
    private var realStatusLoading = false

    /**
     * Generation counter for DevTools sessions. The connect thread
     * captures the generation (and the port/token pair) when it
     * starts; closing or reopening bumps it, so a stale thread can
     * neither rebuild the user's tab nor load a frontend URL into a
     * newer session.
     */
    @Volatile private var devToolsSession = 0
    private var dtHeightPx: Int = 0
    /**
     * The content column's laid-out height, kept current by the
     * column's layout-change listener. The DevTools panel's
     * applied height is capped against this: the panel has a
     * fixed height inside a column whose only flexible child is
     * the page area, so when the IME shrinks the column the
     * uncapped panel would push the bottom toolbar out of the
     * window (behind the keyboard). A laid-out size is used
     * rather than a value derived during insets dispatch,
     * whose timing against rotation/resize is not guaranteed.
     * 0 until the first layout; no cap is applied then.
     */
    private var columnHeightPx: Int = 0
    /** The bottom toolbar row (URL bar); its height bounds the panel cap. */
    private lateinit var bottomToolbar: LinearLayout
    /**
     * The tab the open DevTools panel inspects. Captured when the
     * panel opens and held for the whole session: switching tabs does
     * not retarget the panel, and target lookup / recording follow
     * THIS tab, not whatever happens to be active later.
     * Rebuilt tabs replace the reference in
     * rebuildTab / recreateTabsPreservingUrls.
     */
    @Volatile private var dtTargetTab: BrowserTab? = null

    /**
     * The current session's frontend state (the load pair plus
     * the sticky redaction flag), grouped in one holder so the
     * load pair is only ever cleared through resetLoadState()
     * (the field docs live on DevToolsSessionState).
     */
    private val dtState = DevToolsSessionState()

    /**
     * The one redaction predicate for DevTools-session log output:
     * redact when the live target is secret OR the session's last
     * real target was secret (sticky, see
     * [DevToolsSessionState.lastTargetSecret]).
     */
    private fun secretRedactionActive(): Boolean =
        dtTargetTab?.secret == true || dtState.lastTargetSecret

    private fun isInspectedTabOnStartPage(): Boolean {
        val u = (dtTargetTab ?: activeTab)?.currentUrl ?: return false
        return BrowserTab.isHomeUrl(u)
    }

    /**
     * The single writer of the WebView debugging flag: it is on when
     * the remote-debug preference is on OR the DevTools panel is
     * open, and every state change goes through here.
     */
    private fun applyWebContentsDebugging() {
        WebView.setWebContentsDebuggingEnabled(Settings.remoteDebug() || dtOpen)
    }

    private val recorder = ConsoleLogRecorder()

    /**
     * Whether the CURRENT DevTools session was opened with console-log
     * recording ON (and therefore built its observation tap). Export on
     * close is decided by this, never by the setting's value at close
     * time: toggling the setting ON mid-session must not export a
     * previous session's leftover recording, and toggling it OFF
     * mid-session must not lose this session's (the per-message check
     * already stopped recording when it was turned off).
     */
    private var dtRecordingSession = false

    /**
     * True only once a DevTools session has actually been established
     * (proxy started, session state captured). A reconnect counts as
     * a continuation only then: if a fresh open failed at proxy
     * start, the retry from the panel's reconnect button must be
     * treated as a fresh open, or it would inherit the never-set
     * session state.
     */
    private var dtSessionEstablished = false

    private companion object {
        const val STATE_TAB_URLS = "pageloupe.tabUrls"
        const val STATE_TAB_SECRETS = "pageloupe.tabSecrets"
        const val STATE_TAB_DESKTOPS = "pageloupe.tabDesktops"
        const val STATE_ACTIVE_IDX = "pageloupe.activeIdx"
        const val STATE_LAUNCH_LINK_DIGEST = "pageloupe.launchLinkDigest"
        /** Browsing-history file in internal storage (SEN-154). */
        const val HISTORY_FILE_NAME = "history.json"
        /** Saved open-tab session file in internal storage (SEN-154). */
        const val TAB_SESSION_FILE_NAME = "tab-session.json"
        /** Bookmarks file in internal storage. */
        const val BOOKMARKS_FILE_NAME = "bookmarks.json"
        /** Separator in folder-path displays (the breadcrumb
         * view draws the glyph without the spaces and pads
         * instead). One notation everywhere (SEN-254). */
        const val PATH_SEPARATOR = " > "
        /** The top level's label in folder-path displays. */
        const val ROOT_FOLDER_LABEL = "トップ"
        /**
         * The bottom toolbar's design height (44dp pill + 8dp of
         * padding above and below), shared by the toolbar
         * construction in buildUi and the DevTools panel cap.
         * Keep the two in step.
         */
        const val TOOLBAR_HEIGHT_DP = 60
        /** Browser menu sheet geometry: buildUi positions the sheet with
         * these, and showMenu caps the list against the same values. */
        const val MENU_SHEET_WIDTH_DP = 280
        const val MENU_SHEET_BOTTOM_MARGIN_DP = 68
        /** Shortest the menu's scrollable list is allowed to become. */
        const val MENU_LIST_MIN_HEIGHT_DP = 120
        /** startActivityForResult code for the page file picker. */
        const val REQ_FILE_CHOOSER = 4101
        /** requestPermissions code for page camera/microphone. */
        const val REQ_PAGE_PERMISSIONS = 4102
        /** startActivityForResult code for the console-export folder picker. */
        const val REQ_EXPORT_TREE = 4103

        /** True while a console-export move runs on the log-file
         * executor. Process-wide like the executor itself: an
         * Activity instance field would reset on recreation
         * (density / locale / uiMode) while the move keeps
         * running, reopening the double-run the flag exists to
         * prevent. Touched only on the UI thread (set before the
         * executor is handed the work, cleared in its completion
         * post). */
        var exportMoveInFlight = false

        /** Recordings waiting for the folder picker's answer.
         * Process-wide like [exportMoveInFlight]: the picker
         * can outlive the instance that opened it (recreation
         * while it shows), and the answer is handled by
         * whichever instance is current then. */
        var pendingExportNames: List<String>? = null

        /** Whether the folder picker is currently open. One
         * picker at a time: a second launch (a fast double
         * tap) could stack two pickers whose results race the
         * destination swap. Cleared when the result returns. */
        var exportPickerOpen = false

        /** The live instance that export-move completions aim
         * their view work at. A move can outlive the instance
         * that started it (recreation mid-move); the page
         * refresh and the picker re-open belong to whichever
         * instance is current when the move lands. Registered
         * in onResume, cleared in onDestroy. */
        var exportUiHost: java.lang.ref.WeakReference<MainActivity>? = null
        /** Consecutive renderer deaths after which a tab stops auto-reloading its URL. */
        const val MAX_RENDERER_AUTO_RELOADS = 2
        /** WebView-side permission cancels per tab after which prompting is suppressed. */
        const val MAX_PERMISSION_WEBVIEW_CANCELS = 3
        /**
         * Longest tab URL considered for the external-link dedupe
         * comparison: normalization copies the string, and a
         * page-inflated URL (~2MB) is never the bounce target of an
         * external intent in practice.
         */
        const val MAX_COMPARE_URL_LENGTH = 8192
        /**
         * Total tab cap for tabs created from EXTERNAL links only
         * (the switcher / menu path is the user's own action and
         * stays uncapped). Generous against the expected scale of
         * a few dozen tabs (REVIEW.md).
         */
        const val MAX_TABS = 100
        /**
         * Serializes every log-file operation (export writes,
         * tap-debug writes, clear-data deletions) on one daemon
         * thread, so a deletion can never be overtaken by an
         * in-flight export write. It lives in the
         * companion — process-wide, exactly one — because an
         * instance field would silently break that guarantee
         * whenever the Activity is recreated: the old instance's
         * queued export and the new instance's clear-data delete
         * would then run on different threads. Never shut down.
         */
        val logFileExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "pl-log-files").apply { isDaemon = true }
            }

        /**
         * The history and tab-session writers' own thread,
         * separate from [logFileExecutor]: closing the last tab
         * flushes the session writer, and that wait must never
         * queue behind a DevTools log export (which can carry
         * thousands of entries). Same contract as the log
         * executor: process-wide, exactly one, never shut down.
         */
        val stateFileExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "pl-state-files").apply { isDaemon = true }
            }

        /**
         * Thumbnail file work (SEN-157): WebP encodes, decodes
         * and prunes. Separate from the state file executor so
         * a slow encode can never delay a session or history
         * write — above all the flush that closing the last
         * tab waits on. The thumbnail operations stay ordered
         * among themselves on this one thread. Same contract:
         * process-wide, exactly one, never shut down.
         */
        val thumbFileExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "pl-thumb-files").apply { isDaemon = true }
            }
    }

    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (dtOpen && dtRecordingSession) {
                // Until the frontend has actually loaded, the status
                // line belongs to the connection outcome (connecting,
                // not found, ambiguous, load error): overwriting it
                // with recording stats would hide an explicit failure
                // behind a false "recording" claim.
                // The ticker keeps running so stats take over as soon
                // as the load finishes.
                if (dtState.frontendLoaded) {
                    // Tap losses are part of the recording's honesty:
                    // surface them next to the counts.
                    val tap = proxy?.lastTap
                    val tapNote = when {
                        tap == null -> ""
                        tap.hasFailed -> " / タップ停止（以降記録されません）"
                        tap.droppedMessages > 0 -> " / タップ破棄 ${tap.droppedMessages}件"
                        else -> ""
                    }
                    // The label names the page being inspected:
                    // the start page itself is never recorded (its
                    // own load is excluded in the recorder), but a
                    // previous page's tail traffic can still land
                    // while it is shown, so the label does not
                    // claim recording is stopped.
                    if (isInspectedTabOnStartPage()) {
                        showRealStatus("● スタートページ表示中（スタートページ自体は記録しません）")
                    } else if (DebugLog.isEnabled()) {
                        showRealStatus(
                            "● 記録 ${recorder.size()}件（受信 ${recorder.messagesSeen} / " +
                                "失敗 ${recorder.parseFailures} / 最終: ${recorder.lastMethod().ifEmpty { "-" }}）$tapNote"
                        )
                    } else {
                        showRealStatus("● ログ記録中 ${recorder.size()}件$tapNote")
                    }
                }
                statsHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // A fresh instance, not a recreation: a folder
            // picker a previous instance opened will never
            // deliver its result here, so its process-wide
            // state must not stay behind and mute the picker
            // for the process's lifetime. (A recreation keeps
            // both: the result still arrives, to this instance.)
            exportPickerOpen = false
            pendingExportNames = null
        }
        Colors.load(this)
        applyWebContentsDebugging()
        DebugLog.init(this)
        DebugLog.log("app", "onCreate (debug log is ON)")
        // Drop the pre-0.40.0 persisted remote-debug key: the
        // setting is process-lifetime state now (see Settings),
        // and a stale ON must not linger on disk. Idempotent,
        // and like the wipe below it runs on every onCreate.
        Settings.clearLegacyRemoteDebugKey(this)
        // A killed process can leave a secret profile behind, and
        // secret data must not survive into a new process. This
        // runs on every onCreate, including activity recreation.
        // On a fresh process the delete goes through before any
        // tab exists. On a recreation the profile is typically
        // still loaded in memory, so the attempt degrades to the
        // in-place clear: cookies and storage are wiped (which is
        // what makes the blank-restored secret tabs honest — they
        // must not revive the previous session's logins), and the
        // cache / Service Worker residue follows the residual
        // recorded in REVIEW.md until a delete succeeds. No-op
        // when no secret profile exists or multi-profile is
        // unsupported.
        SecretProfile.wipe("activity create")
        // Draw edge to edge behind the system bars. While the app stays out
        // of the gesture bar's area, the OS draws a hairline border above
        // the home bar; extending the app's own background underneath
        // removes it. buildUi pads the content by the system bar insets so
        // nothing is covered by either bar.
        try {
            // Only the edge-to-edge switch itself is flipped here. The bar
            // colours and the light-bar appearance (dark OS icons) live in
            // AppTheme: declaring them in the theme applies from the start,
            // while setting the appearance at runtime in onCreate did
            // not stick when measured on a real device.
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setDecorFitsSystemWindows(false)
            // No theme item exists for this one; it is a plain window
            // property, so setting it here is the intended route.
            window.isNavigationBarContrastEnforced = false
            DebugLog.log("app", "edge-to-edge on (bar styling comes from AppTheme)")
        } catch (_: Exception) {
            // Window styling is cosmetic; never let it break startup.
        }
        buildUi()
        historyWriter = QueuedFileWriter(
            java.io.File(filesDir, HISTORY_FILE_NAME), stateFileExecutor,
        ) { DebugLog.log("history", "write failed: $it") }
        sessionWriter = QueuedFileWriter(
            java.io.File(filesDir, TAB_SESSION_FILE_NAME), stateFileExecutor,
        ) { DebugLog.log("tab", "session write failed: $it") }
        bookmarkWriter = QueuedFileWriter(
            java.io.File(filesDir, BOOKMARKS_FILE_NAME), stateFileExecutor,
        ) { DebugLog.log("bookmark", "write failed: $it") }
        loadHistory()
        loadBookmarks()
        // One launch-link decision for every start branch below
        // (audit hardening): a relaunch from Recents redelivers
        // the ORIGINAL launch intent, link included, and the
        // link is not replayed in ANY branch. The digest guard
        // in the Bundle branch only covers recreations with
        // saved state — a fresh process (the typical Recents
        // relaunch after the app was finished) has none.
        val launchedFromHistory =
            ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val launchData = launchDataForStart(intent?.dataString, launchedFromHistory)
        val restoredUrls = savedInstanceState?.getStringArrayList(STATE_TAB_URLS)
        if (!restoredUrls.isNullOrEmpty()) {
            // The activity was recreated (a config change the
            // manifest does not absorb: day/night switch, density,
            // locale or layout direction, or a process restart):
            // rebuild the tab set instead of dropping every tab.
            // History and scroll positions are
            // not part of the saved state.
            val restoredSecrets = savedInstanceState.getBooleanArray(STATE_TAB_SECRETS)
            val restoredDesktops = savedInstanceState.getBooleanArray(STATE_TAB_DESKTOPS)
            val restoredActive = savedInstanceState.getInt(STATE_ACTIVE_IDX, 0)
                .coerceIn(0, restoredUrls.size - 1)
            var newActive: BrowserTab? = null
            withRestoringTabs {
                for ((i, u) in restoredUrls.withIndex()) {
                    // Background tabs are rebuilt but not loaded: loading
                    // every tab at once after a process restart is the
                    // fastest way to die of memory pressure again. Their
                    // URL is deferred until the tab is first shown
                    // (switchTo consumes deferredUrl).
                    val t = newTab(secret = restoredSecrets?.getOrNull(i) ?: false, activate = false)
                    // Desktop mode is part of the saved tab state too;
                    // absent in bundles written by
                    // older builds, where it defaults to mobile.
                    t.adoptDesktopMode(restoredDesktops?.getOrNull(i) ?: false)
                    // The restored URL is re-validated through
                    // the same rule the writer used (run-3
                    // hardening): the Bundle is app-written, so
                    // this is defense in depth — but the session
                    // file's decode re-validates its side, and
                    // the two restore paths should not drift
                    // apart on what a display URL may be.
                    val checkedUrl = savableTabUrl(u)
                    if (BrowserTab.isHomeUrl(checkedUrl)) {
                        t.loadHome()
                    } else {
                        t.currentUrl = checkedUrl
                        t.deferredUrl = checkedUrl
                    }
                    if (i == restoredActive) newActive = t
                }
            }
            // getIntent() keeps returning the ORIGINAL launch
            // intent on every recreation. If that intent carries a
            // link this instance chain has not consumed yet, the
            // recreation was likely triggered by its delivery
            // (task survived, process died): open it INSTEAD of
            // first loading the restored active tab, which nobody
            // may be looking at. Consumption is
            // tracked as a fixed-length digest of the LAUNCH link
            // only -- links delivered later arrive via onNewIntent
            // and are never replayed through this path, so they
            // must not overwrite the marker.
            launchLinkDigest = savedInstanceState.getString(STATE_LAUNCH_LINK_DIGEST)
            val toOpen = launchLinkToOpen(launchData, launchLinkDigest)
            // The link is consumed either way: opened now, or
            // refused at the tab cap. A refusal is a decision, not
            // a deferral -- an unconsumed refused link would retry
            // (and re-toast) on every recreation and could pop
            // open much later, unprompted, once tabs drop below
            // the cap. When it was not opened,
            // the restored active tab is activated instead, so
            // the activity never ends up with no active tab.
            val opened = if (toOpen != null) {
                launchLinkDigest = urlDigest(toOpen)
                openExternalLink(toOpen)
            } else {
                false
            }
            if (!opened) {
                // switchTo loads the active tab's deferred URL.
                newActive?.let { switchTo(it) }
                updateTabCount()
            }
        } else {
            val session = loadSavedSession()
            if (session != null) {
                // Cold start with a saved open-tab session
                // (SEN-154): rebuild the tabs, deferred like the
                // Bundle restore above. Secret tabs come back
                // with their URL — the session file is
                // app-private storage, unlike the system-held
                // Bundle — but their profile was wiped at the
                // top of this onCreate, so their pages start
                // logged out.
                var newActive: BrowserTab? = null
                withRestoringTabs {
                    for ((i, st) in session.tabs.withIndex()) {
                        val t = newTab(secret = st.secret, activate = false)
                        t.adoptDesktopMode(st.desktop)
                        if (st.url.isEmpty() || BrowserTab.isHomeUrl(st.url)) {
                            t.loadHome()
                        } else {
                            t.currentUrl = st.url
                            t.deferredUrl = st.url
                        }
                        if (i == session.activeIndex) newActive = t
                    }
                }
                // A launch link on a cold start opens as its own
                // tab, like a link delivered to a running
                // instance; the digest keeps a later recreation
                // from replaying it (same contract as the Bundle
                // path above).
                val startUrl = launchData
                val opened = if (isWebLink(startUrl)) {
                    launchLinkDigest = urlDigest(startUrl!!)
                    openExternalLink(startUrl)
                } else {
                    false
                }
                if (!opened) {
                    newActive?.let { switchTo(it) }
                    updateTabCount()
                }
            } else {
                val startUrl = launchData
                val first = newTab()
                if (isWebLink(startUrl)) {
                    first.loadInput(startUrl!!)
                    launchLinkDigest = urlDigest(startUrl)
                } else {
                    first.loadHome()
                }
            }
        }
        // Startup sweep of the persisted thumbnails (SEN-157):
        // files whose tab did not come back — a prune that a
        // dying process never got to run — are deleted here,
        // so a thumbnail's lifetime still follows its tab's.
        pruneThumbnails(
            tabs.filter { !it.secret }.map { it.deferredUrl ?: it.currentUrl }
        )
        // System back is the plain, single back path: no gesture
        // customization -- no edge detection, no exclusion rects,
        // no app-drawn arrow, no right-edge "forward" conversion.
        // Every back lands in handleBack(): peel an open overlay,
        // then the page history, then finish. "Forward" is the
        // menu action only. The design history (SEN-165 -> SEN-174
        // -> SEN-180) is recorded in REVIEW.md. minSdk is 33:
        // OnBackInvokedCallback is the newest API this needs.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            android.window.OnBackInvokedCallback { handleBack() }
        )
    }

    /**
     * A tab's URL for debug-log lines: secret tabs' URLs never go
     * to the log file.
     */
    private fun debugUrl(t: BrowserTab?): String = when {
        t == null -> ""
        t.secret -> "<redacted>"
        else -> t.currentUrl
    }

    /** Diagnostics (debug-log option only): show what the back action decided. */
    private fun navToast(wv: WebView?) {
        if (!DebugLog.isEnabled()) return
        val list = wv?.copyBackForwardList()
        val hist = if (list == null) {
            "履歴不明"
        } else {
            "履歴${list.size}件・現在${list.currentIndex + 1}番目"
        }
        val act = if (wv != null && wv.canGoBack()) "戻ります" else "終了します"
        android.widget.Toast.makeText(this, "システム戻る: $hist → $act", android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /** The one and only back action (the system back pipeline). */
    private fun handleBack() {
        if (DebugLog.isEnabled()) {
            val wv0 = activeTab?.webView
            val list0 = wv0?.copyBackForwardList()
            DebugLog.log(
                "back",
                "system back: dtOpen=$dtOpen history=${list0?.size} index=${list0?.currentIndex} " +
                    "canGoBack=${wv0?.canGoBack()} url=${debugUrl(activeTab)}"
            )
        }
        when {
            isFullscreenShowing() -> hideCustomView()
            // Full-screen pages cover the whole main column (the
            // DevTools panel and the find bar included), so back
            // dismisses what the user actually sees first. Clear
            // data is checked before history because it can open
            // on top of it; the settings page never coexists with
            // another page.
            ::clearDataPage.isInitialized && clearDataPage.visibility == View.VISIBLE -> hideClearDataPage()
            ::settingsPage.isInitialized && settingsPage.visibility == View.VISIBLE -> hideSettingsPage()
            ::exportPage.isInitialized && exportPage.visibility == View.VISIBLE -> hideExportPage()
            ::bookmarksPage.isInitialized && bookmarksPage.visibility == View.VISIBLE -> backInBookmarksPage()
            ::historyPage.isInitialized && historyPage.visibility == View.VISIBLE -> hideHistoryPage()
            dtOpen -> closeRealDevTools()
            findBar.visibility == View.VISIBLE -> closeFind()
            menuScrim.visibility == View.VISIBLE -> hideMenu()
            ::tabMenuPanel.isInitialized && tabMenuPanel.visibility == View.VISIBLE -> hideTabMenu()
            switcher.visibility == View.VISIBLE -> hideSwitcher()
            activeTab?.webView?.canGoBack() == true -> {
                navToast(activeTab?.webView)
                activeTab?.noteUserNavigation()
                activeTab?.webView?.goBack()
            }
            else -> {
                navToast(activeTab?.webView)
                finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.dataString
        if (isWebLink(url)) {
            // Delivered links are opened here and only here: the
            // launch-intent consumption marker (launchLinkDigest)
            // is deliberately NOT updated, so a later recreation
            // cannot mistake this link for the launch one.
            openExternalLink(url!!)
        }
    }

    /**
     * Open a link that arrived from another app (VIEW intent),
     * either on a running instance (onNewIntent) or on an instance
     * being restored after process death (onCreate). The pure
     * decision (reuse / create / refuse) runs first; only then is
     * the foreground UI settled -- the switcher and the menu close
     * and the tab badge is refreshed, so the page never lands
     * hidden under an overlay with a stale count. The
     * DevTools panel deliberately stays as it is --
     * closing it would end the session and its recording.
     *
     * Returns false only when the tab cap refused to create a new
     * tab (a toast explains, and the screen is left untouched);
     * the restore-branch caller then falls back to activating
     * the restored tab, so a refused link can never leave the
     * activity with no active tab.
     */
    private fun openExternalLink(url: String): Boolean {
        // The decision runs BEFORE any UI settling: when the cap
        // refuses, nothing about the current screen changes. The
        // rules are the pure externalLinkAction in UrlInput.kt:
        // reuse (normalized comparison, bounded lengths, no
        // dedupe on userinfo/backslash authorities, secret tabs
        // skipped) wins over the cap; the cap refuses only
        // creation. Reuse only switches: it never reloads the tab
        // or follows the fragment. External links always land in
        // a NORMAL tab: secret tabs never take them, and the new
        // tab does not inherit the active tab's mode.
        val (action, idx) = externalLinkAction(
            url,
            tabs.map { DedupeCandidate(it.currentUrl, it.deferredUrl, it.secret) },
            MAX_COMPARE_URL_LENGTH,
            tabs.size,
            MAX_TABS
        )
        if (action == ExternalLinkAction.REFUSE) {
            // External links create at most this many tabs in
            // total: a malfunctioning (or malicious) sender looping
            // distinct URLs must not grow WebViews without bound.
            // The user's own tab creation is not capped.
            showTabLimitToast()
            return false
        }
        closeOverlaysForIncomingTab()
        // The text was being typed for the previous foreground
        // tab; committing it would load it into the new one.
        endAddressEdit()
        // An external app bouncing the same link back (e.g. right
        // after an external-scheme handoff from a page) must not
        // pile up duplicate tabs in a loop: reuse the tab already
        // showing that URL.
        if (action == ExternalLinkAction.SWITCH) {
            switchTo(tabs[idx])
        } else {
            newTab(secret = false).loadInput(url)
        }
        updateTabCount()
        return true
    }

    // ---------- UI construction ----------

    private fun buildUi() {
        root = FrameLayout(this)
        // The window is edge to edge (see onCreate), so the root background
        // is what shows behind the system bars.
        root.setBackgroundColor(Colors.SURFACE)
        // A dedicated focus sink: an invisible view that
        // can hold focus harmlessly when a text field on an
        // overlay page has to give it up (see
        // showClearDataPage / hideHistoryPage). A container
        // cannot be the sink itself — ViewGroup.requestFocus
        // hands focus to a descendant first — so the sink is
        // a plain view, focused directly. It is 1px rather
        // than 0 so that focus search treats it as a real
        // view, and it may also absorb the window's default
        // focus, which is fine: that keeps the address bar
        // out of editing mode.
        focusSink = View(this)
        focusSink.isFocusable = true
        focusSink.isFocusableInTouchMode = true
        focusSink.defaultFocusHighlightEnabled = false
        focusSink.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(focusSink, FrameLayout.LayoutParams(1, 1))
        root.setOnApplyWindowInsetsListener { v, insets ->
            // Keep the content clear of the status bar and the gesture home
            // bar by padding the root with the system bar insets. An
            // edge-to-edge window is not resized for the keyboard by the OS,
            // so pad by whichever reaches higher, the navigation bar or the
            // IME: the bottom bars (URL bar / tab search) then always sit
            // directly above the keyboard.
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            v.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setBackgroundColor(Colors.SURFACE)
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The column is MATCH_PARENT, so its height comes from
        // the root's padding alone and never depends on the
        // panel height this listener may re-apply: the second
        // layout pass sees an unchanged height and stays quiet.
        column.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val h = bottom - top
            if (h != oldBottom - oldTop) {
                columnHeightPx = h
                refreshDtHeight()
            }
        }

        pageContainer = FrameLayout(this)
        pageContainer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        column.addView(pageContainer)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE
        val pbParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3))
        pbParams.gravity = Gravity.TOP
        pageContainer.addView(progressBar, pbParams)

        buildDevToolsPanel(column)

        column.addView(Ui.divider(this))

        // Bottom toolbar. Its height is TOOLBAR_HEIGHT_DP
        // (pill 44dp + 8dp padding above and below); the
        // DevTools panel cap relies on that value.
        val toolbar = LinearLayout(this)
        bottomToolbar = toolbar
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8))
        toolbar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        column.addView(toolbar)

        // Address pill. It takes the full width the toolbar
        // leaves: the home button that used to sit at its left
        // was removed (owner request, 2026-11-22); going home
        // is the menu footer's home button now.
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = getDrawable(R.drawable.bg_pill)
        pill.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0)
        val pillParams = LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f)
        pillParams.leftMargin = Ui.dp(this, 2)
        pillParams.rightMargin = Ui.dp(this, 2)
        pill.layoutParams = pillParams
        lockIcon = ImageView(this)
        lockIcon.setImageResource(R.drawable.ic_lock)
        lockIcon.setColorFilter(Colors.ON_SURFACE_VARIANT)
        val lockParams = LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16))
        lockParams.rightMargin = Ui.dp(this, 6)
        lockIcon.layoutParams = lockParams
        pill.addView(lockIcon)
        addressInput = EditText(this)
        addressInput.setSingleLine(true)
        addressInput.background = null
        addressInput.textSize = 15f
        addressInput.setTextColor(Colors.ON_SURFACE)
        addressInput.hint = "URL または検索ワード"
        // The bar also holds secret tabs' URLs and queries:
        // keep them out of the keyboard's personalized
        // learning (the search-field rule, SEC-5; the owner
        // counts keyboard learning as part of the secret
        // promise, SEN-287).
        Ui.applyPrivateInput(
            addressInput,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            EditorInfo.IME_ACTION_GO,
        )
        addressInput.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addressInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                activeTab?.loadInput(addressInput.text.toString())
                endAddressEdit()
                true
            } else false
        }
        addressInput.setOnFocusChangeListener { _, hasFocus ->
            addressEditing = hasFocus
            if (hasFocus) {
                // Typing a URL is the user's context now: a
                // pending popup must not decide mid-edit and
                // throw the edit away (SEC-155).
                activeTab?.expirePopupProbes()
                // On the start page the bar stays empty even while editing.
                val full = activeTab?.currentUrl?.takeUnless { BrowserTab.isHomeUrl(it) } ?: ""
                addressInput.setText(full)
                addressInput.setSelection(0, full.length)
                // The text the focus gain put there: while the
                // bar still shows exactly this, it is displaying
                // the page, not holding user input (SEN-153).
                addressFocusText = full
            } else {
                refreshAddressBar()
            }
        }
        pill.addView(addressInput)
        reloadBtn = Ui.iconButton(this, R.drawable.ic_refresh, "再読み込み", 36)
        reloadBtn.setOnClickListener {
            val t = activeTab ?: return@setOnClickListener
            if (t.isLoading) t.webView.stopLoading() else { t.noteUserNavigation(); t.webView.reload() }
        }
        pill.addView(reloadBtn)
        toolbar.addView(pill)

        // Tab button with count
        val tabBtnFrame = FrameLayout(this)
        tabBtnFrame.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44))
        val tabBtn = Ui.iconButton(this, R.drawable.ic_tabs, "タブ")
        tabBtn.setOnClickListener { showSwitcher() }
        tabBtnFrame.addView(tabBtn)
        tabCountText = TextView(this)
        tabCountText.textSize = 11f
        tabCountText.setTypeface(null, Typeface.BOLD)
        tabCountText.setTextColor(Colors.ICON)
        tabCountText.gravity = Gravity.CENTER
        val tcParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        tabCountText.layoutParams = tcParams
        tabCountText.setPadding(0, Ui.dp(this, 1), 0, 0)
        tabBtnFrame.addView(tabCountText)
        toolbar.addView(tabBtnFrame)

        devToolsBtn = Ui.iconButton(this, R.drawable.ic_code, "DevTools")
        devToolsBtn.setOnClickListener { if (dtOpen) closeRealDevTools() else openRealDevTools() }
        toolbar.addView(devToolsBtn)

        val moreBtn = Ui.iconButton(this, R.drawable.ic_more_vert, "メニュー")
        moreBtn.setOnClickListener { showMenu() }
        // Pull the menu button 6dp closer to the DevTools button; the
        // address pill (weight 1) absorbs the freed space, so the URL bar
        // grows by the same amount instead of leaving a gap at the end.
        (moreBtn.layoutParams as LinearLayout.LayoutParams).leftMargin = Ui.dp(this, -6)
        toolbar.addView(moreBtn)

        buildFindBar(column)
        buildSwitcher()
        buildMenu()
        buildHistoryPage()
        buildClearDataPage()
        buildSettingsPage()
        buildExportPage()
        buildBookmarksPage()

        setContentView(root)
    }

    private fun buildFindBar(column: LinearLayout) {
        findBar = LinearLayout(this)
        findBar.orientation = LinearLayout.HORIZONTAL
        findBar.gravity = Gravity.CENTER_VERTICAL
        findBar.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4))
        findBar.setBackgroundColor(Colors.SURFACE_CONTAINER_LOW)
        findBar.visibility = View.GONE
        findInput = EditText(this)
        findInput.hint = "ページ内検索"
        findInput.setSingleLine(true)
        // A find query can name what the page is about —
        // in a secret tab, what the secret page is about:
        // no suggestions, no personalized learning (the
        // search-field rule, SEC-5 / SEN-287).
        Ui.applyPrivateInput(findInput, InputType.TYPE_CLASS_TEXT)
        findInput.background = null
        findInput.textSize = 14f
        findInput.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        findInput.setOnEditorActionListener { _, _, _ ->
            activeTab?.webView?.findNext(true)
            true
        }
        findBar.addView(findInput)
        findCount = TextView(this)
        findCount.textSize = 12f
        findCount.setTextColor(Colors.ON_SURFACE_VARIANT)
        findBar.addView(findCount)
        val closeFindBtn = Ui.iconButton(this, R.drawable.ic_close, "検索を閉じる", 36)
        closeFindBtn.setOnClickListener { closeFind() }
        findBar.addView(closeFindBtn)
        // Insert above the toolbar (toolbar is the last child of column)
        column.addView(findBar, column.childCount - 1)
    }

    private fun buildSwitcher() {
        // FrameLayout root so the tab menu panel can float above the
        // bottom tab toolbar, as in the mock.
        val switcherRoot = FrameLayout(this)
        switcherRoot.setBackgroundColor(Colors.SURFACE_CONTAINER_LOW)
        switcherRoot.visibility = View.GONE
        switcher = switcherRoot

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        switcherRoot.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // Header: close button on the left, then the tab count title.
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6))
        val closeBtn = Ui.iconButton(this, R.drawable.ic_close, "タブ一覧を閉じる")
        closeBtn.setOnClickListener { hideSwitcher() }
        header.addView(closeBtn)
        switcherTitle = Ui.textRow(this, "0 件のタブ", 18f)
        switcherTitle.setTypeface(null, Typeface.BOLD)
        header.addView(switcherTitle)
        column.addView(header)

        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        switcherGrid = GridLayout(this)
        switcherGrid.columnCount = 2
        switcherGrid.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 10))
        scroll.addView(switcherGrid)
        column.addView(scroll)

        column.addView(buildTabToolbar())
        column.addView(buildTabSearchBar())

        tabMenuScrim = View(this)
        tabMenuScrim.setBackgroundColor(Colors.SCRIM)
        tabMenuScrim.visibility = View.GONE
        tabMenuScrim.setOnClickListener { hideTabMenu() }
        switcherRoot.addView(
            tabMenuScrim,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        tabMenuPanel = buildTabMenuPanel()
        val panelParams = FrameLayout.LayoutParams(Ui.dp(this, 248), ViewGroup.LayoutParams.WRAP_CONTENT)
        panelParams.gravity = Gravity.BOTTOM or Gravity.END
        panelParams.rightMargin = Ui.dp(this, 8)
        // Float above the bottom block (56 tab toolbar + 52 search area) with
        // an 8dp gap, matching the panel's right margin.
        panelParams.bottomMargin = Ui.dp(this, 116)
        tabMenuPanel.layoutParams = panelParams
        tabMenuPanel.visibility = View.GONE
        switcherRoot.addView(tabMenuPanel)

        root.addView(switcher, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Bottom tab toolbar: mode toggle (left), new tab (center), menu (right). */
    private fun buildTabToolbar(): View {
        val bar = FrameLayout(this)
        bar.setBackgroundColor(Colors.SURFACE)
        bar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56))
        val border = View(this)
        border.setBackgroundColor(Colors.OUTLINE_VARIANT)
        border.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        bar.addView(border)

        val toggle = LinearLayout(this)
        toggle.orientation = LinearLayout.HORIZONTAL
        toggle.background = Ui.roundBg(Colors.SURFACE_CONTAINER, 12f, this)
        toggle.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3))
        val toggleParams = FrameLayout.LayoutParams(Ui.dp(this, 108), Ui.dp(this, 36))
        toggleParams.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        toggleParams.leftMargin = Ui.dp(this, 10)
        toggle.layoutParams = toggleParams
        toggleNormalSeg = makeToggleSegment(R.drawable.ic_globe, "通常タブ") { setSwitcherMode(false) }
        toggleSecretSeg = makeToggleSegment(R.drawable.ic_eye_off, "シークレットタブ") { setSwitcherMode(true) }
        toggleNormalIcon = toggleNormalSeg.getChildAt(0) as ImageView
        toggleSecretIcon = toggleSecretSeg.getChildAt(0) as ImageView
        toggle.addView(toggleNormalSeg, LinearLayout.LayoutParams(Ui.dp(this, 51), Ui.dp(this, 30)))
        toggle.addView(toggleSecretSeg, LinearLayout.LayoutParams(Ui.dp(this, 51), Ui.dp(this, 30)))
        bar.addView(toggle)

        val plus = FrameLayout(this)
        plus.background = Ui.roundBg(Colors.PRIMARY_CONTAINER, 12f, this)
        plus.contentDescription = "新しいタブ"
        val plusParams = FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40))
        plusParams.gravity = Gravity.CENTER
        plus.layoutParams = plusParams
        val plusIcon = ImageView(this)
        plusIcon.setImageResource(R.drawable.ic_plus)
        plusIcon.setColorFilter(Colors.PRIMARY)
        val plusIconParams = FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24))
        plusIconParams.gravity = Gravity.CENTER
        plusIcon.layoutParams = plusIconParams
        plus.addView(plusIcon)
        plus.setOnClickListener {
            DebugLog.log("tab", "new tab from switcher, secret=$switcherSecretMode")
            newTab(switcherSecretMode).loadHome()
            hideSwitcher()
        }
        bar.addView(plus)

        // Same button as the browser toolbar's menu button: default 44dp
        // size and the default dark tint (no blue override), so the two
        // toolbars show the same ⋮.
        val moreBtn = Ui.iconButton(this, R.drawable.ic_more_vert, "タブメニュー")
        val moreParams = FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44))
        moreParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        moreParams.rightMargin = Ui.dp(this, 4)
        moreBtn.layoutParams = moreParams
        moreBtn.setOnClickListener { showTabMenu() }
        bar.addView(moreBtn)
        return bar
    }

    private fun makeToggleSegment(resId: Int, desc: String, onClick: () -> Unit): FrameLayout {
        val seg = FrameLayout(this)
        seg.contentDescription = desc
        val iv = ImageView(this)
        iv.setImageResource(resId)
        // Icons carry no tint of their own; the unselected colour is the
        // default until applyToggleSegment states otherwise.
        iv.setColorFilter(Colors.ON_SURFACE_VARIANT)
        val p = FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20))
        p.gravity = Gravity.CENTER
        iv.layoutParams = p
        seg.addView(iv)
        seg.setOnClickListener { onClick() }
        return seg
    }

    private fun setSwitcherMode(secret: Boolean) {
        if (switcherSecretMode == secret) return
        switcherSecretMode = secret
        DebugLog.log("tab", "switcher mode secret=$secret")
        updateToggleVisuals()
        // Synchronous: a coalesced (posted) rebuild would leave the
        // OTHER mode's cards -- possibly secret tabs' thumbnails --
        // visible for one frame after the toggle.
        renderSwitcherNow()
    }

    private fun updateToggleVisuals() {
        applyToggleSegment(toggleNormalSeg, toggleNormalIcon, !switcherSecretMode)
        applyToggleSegment(toggleSecretSeg, toggleSecretIcon, switcherSecretMode)
    }

    private fun applyToggleSegment(seg: FrameLayout, icon: ImageView, selected: Boolean) {
        seg.background = if (selected) Ui.roundBg(Colors.SURFACE, 9f, this) else null
        icon.setColorFilter(if (selected) Colors.PRIMARY else Colors.ON_SURFACE_VARIANT)
    }

    /** Tab search form under the tab toolbar. The pill is the same 44dp as
     *  the address pill, but unlike the browser toolbar there is no top
     *  padding: the tab toolbar sits directly above, and its own inner
     *  space already gives the buttons their margin, so an extra 8dp here
     *  made the white space below the tab toolbar twice the space above
     *  it. */
    private fun buildTabSearchBar(): View {
        val area = LinearLayout(this)
        area.orientation = LinearLayout.VERTICAL
        area.setBackgroundColor(Colors.SURFACE)
        // Both edges sit 10dp in from the screen edge: the left lines up
        // with the mode toggle in the tab toolbar above, and the right grew
        // by the same 6dp, so the bar is longer rather than shifted.
        area.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 8))
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = Ui.roundBg(Colors.SURFACE_CONTAINER, 22f, this)
        pill.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        pill.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44))
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_search)
        icon.setColorFilter(Colors.ON_SURFACE_VARIANT)
        icon.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20))
        pill.addView(icon)
        switcherSearchInput = EditText(this)
        switcherSearchInput.hint = "タブを検索"
        switcherSearchInput.textSize = 14f
        switcherSearchInput.maxLines = 1
        // The query runs over tab titles, secret tabs
        // included: no suggestions, no personalized
        // learning (the search-field rule, SEC-5 / SEN-287).
        Ui.applyPrivateInput(switcherSearchInput, InputType.TYPE_CLASS_TEXT)
        switcherSearchInput.background = null
        switcherSearchInput.setPadding(Ui.dp(this, 10), 0, 0, 0)
        switcherSearchInput.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        switcherSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                switcherQuery = s?.toString()?.trim() ?: ""
                renderSwitcher()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        pill.addView(switcherSearchInput)
        area.addView(pill)
        return area
    }

    private fun buildTabMenuPanel(): LinearLayout {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = Ui.roundBg(Colors.SURFACE, 16f, this)
        panel.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8))
        panel.addView(menuRow(R.drawable.ic_close, "すべてのタブを閉じる") {
            hideTabMenu()
            closeAllTabsInMode()
        })
        panel.addView(menuRow(R.drawable.ic_history, "閲覧履歴") {
            hideTabMenu()
            hideSwitcher()
            showHistoryPage()
        })
        return panel
    }



    private fun showTabMenu() {
        tabMenuScrim.visibility = View.VISIBLE
        tabMenuPanel.visibility = View.VISIBLE
    }

    private fun hideTabMenu() {
        if (!::tabMenuPanel.isInitialized) return
        tabMenuScrim.visibility = View.GONE
        tabMenuPanel.visibility = View.GONE
    }

    /**
     * Remove one tab from the list and destroy it
     * completely: out of the container, pending callbacks
     * settled (beforeTabDestroyed), WebView destroyed.
     * Callers handle what the removal means (activation,
     * secret-profile wipe, session saves); closeTab
     * interleaves its own save and keeps its open-coded
     * sequence.
     */
    private fun detachAndDestroyTab(t: BrowserTab) {
        tabs.remove(t)
        pageContainer.removeView(t.webView)
        beforeTabDestroyed(t)
        t.destroy()
    }

    /** Close every tab in the list the switcher is currently showing. */
    private fun closeAllTabsInMode() {
        val targets = tabs.filter { it.secret == switcherSecretMode }
        if (targets.isEmpty()) return
        if (dtOpen && dtTargetTab != null && targets.contains(dtTargetTab)) closeRealDevTools()
        DebugLog.log("tab", "close all tabs secret=$switcherSecretMode count=${targets.size}")
        val activeWasClosed = activeTab != null && targets.contains(activeTab)
        for (t in targets) {
            detachAndDestroyTab(t)
        }
        onSecretTabsClosed(switcherSecretMode, "all secret tabs closed")
        if (tabs.isEmpty()) {
            // No tabs left in either mode: exit, like closing
            // the last tab. The session delete must be on disk
            // before the process can die, or the closed tabs
            // would come back on the next cold start.
            saveTabSession()
            sessionWriter.flush()
            finish()
            return
        }
        if (activeWasClosed) {
            switchTo(tabs.last())
            switcherSecretMode = activeTab?.secret ?: false
            updateToggleVisuals()
        } else {
            // The closed tabs were the other mode's, so no
            // switch follows to save for us: record the
            // closure now, or those tabs would come back on
            // the next cold start.
            saveTabSession()
        }
        updateTabCount()
        renderSwitcher()
    }

    private fun buildMenu() {
        menuScrim = View(this)
        menuScrim.setBackgroundColor(Colors.SCRIM)
        menuScrim.visibility = View.GONE
        menuScrim.setOnClickListener { hideMenu() }
        root.addView(menuScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        menuSheet = LinearLayout(this)
        menuSheet.orientation = LinearLayout.VERTICAL
        // Same floating-panel presentation as the tab menu: rounded
        // card, right-aligned, floating above the bottom toolbar
        // (60dp tall) with an 8dp gap. Inside, the item list
        // scrolls (its height is capped in showMenu) while the
        // icon footer stays pinned below it (MenuSpec).
        menuSheet.background = Ui.roundBg(Colors.SURFACE, 16f, this)
        // The sheet consumes touches its children do not (the
        // gaps around the footer buttons, the list's padding):
        // without this they would fall through to the scrim and
        // close the menu, which is a reaction the inert footer
        // buttons must not have.
        menuSheet.isClickable = true
        menuSheet.visibility = View.GONE
        menuList = LinearLayout(this)
        menuList.orientation = LinearLayout.VERTICAL
        menuList.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8))
        menuScroll = android.widget.ScrollView(this)
        menuScroll.addView(menuList)
        menuSheet.addView(
            menuScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val footerDivider = View(this)
        menuFooterDivider = footerDivider
        footerDivider.setBackgroundColor(Colors.OUTLINE_VARIANT)
        val footerDividerParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        footerDividerParams.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0)
        menuSheet.addView(footerDivider, footerDividerParams)
        menuFooter = buildMenuFooter()
        menuSheet.addView(menuFooter)
        val sheetParams = FrameLayout.LayoutParams(Ui.dp(this, MENU_SHEET_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
        sheetParams.gravity = Gravity.BOTTOM or Gravity.END
        sheetParams.rightMargin = Ui.dp(this, 8)
        sheetParams.bottomMargin = Ui.dp(this, MENU_SHEET_BOTTOM_MARGIN_DP)
        root.addView(menuSheet, sheetParams)
    }

    // ---------- Real Chrome DevTools (bottom panel) ----------

    private fun buildDevToolsPanel(column: LinearLayout) {
        dtPanel = LinearLayout(this)
        dtPanel.orientation = LinearLayout.VERTICAL
        dtPanel.setBackgroundColor(Colors.SURFACE)
        dtPanel.visibility = View.GONE
        dtHeightPx = (resources.displayMetrics.heightPixels * 0.55).toInt()
        dtPanel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dtHeightPx)
        column.addView(dtPanel)

        // Subtle boundary between the web page and the panel
        val topBorder = View(this)
        topBorder.setBackgroundColor(Colors.OUTLINE_VARIANT)
        topBorder.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1))
        dtPanel.addView(topBorder)

        // Drag handling shared by the grab strip and the header row, so the
        // grabbable area is tall. Buttons inside the header consume their
        // own touches, so they keep working.
        var dragStartY = 0f
        var dragStartH = 0
        val dragListener = View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragStartY = ev.rawY
                    dragStartH = (dtPanel.layoutParams as? LinearLayout.LayoutParams)?.height
                        ?: dtHeightPx
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val newH = PanelHeight.clampChosen(
                        dragStartH + (dragStartY - ev.rawY).toInt(),
                        column.height,
                        Ui.dp(this, PanelHeight.MIN_DP),
                        Ui.dp(this, PanelHeight.RESERVE_DP),
                        Ui.dp(this, PanelHeight.MAX_FLOOR_DP)
                    )
                    applyDtHeight(newH)
                    true
                }
                else -> true
            }
        }

        // Grab strip: pull up/down to resize the panel
        val strip = FrameLayout(this)
        strip.setBackgroundColor(Colors.SURFACE_CONTAINER_LOW)
        strip.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 30))
        val pill = View(this)
        pill.background = Ui.roundBg(Colors.OUTLINE, 3f, this)
        val pillParams = FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 5))
        pillParams.gravity = Gravity.CENTER
        pill.layoutParams = pillParams
        strip.addView(pill)
        strip.setOnTouchListener(dragListener)
        dtPanel.addView(strip)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(Colors.SURFACE_CONTAINER_LOW)
        header.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 4), Ui.dp(this, 2))
        header.setOnTouchListener(dragListener)
        val title = Ui.textRow(this, "Chrome DevTools", 15f)
        title.setTypeface(null, Typeface.BOLD)
        header.addView(title)
        realTargetInfo = Ui.textRow(this, "", 12f, Colors.ON_SURFACE_VARIANT)
        realTargetInfo.maxLines = 1
        realTargetInfo.ellipsize = android.text.TextUtils.TruncateAt.END
        realTargetInfo.setTextIsSelectable(true)
        val infoParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        infoParams.leftMargin = Ui.dp(this, 10)
        realTargetInfo.layoutParams = infoParams
        header.addView(realTargetInfo)
        val reloadReal = Ui.iconButton(this, R.drawable.ic_refresh, "DevTools を再接続", 40)
        reloadReal.setOnClickListener { openRealDevTools(reconnect = true) }
        header.addView(reloadReal)
        val closeReal = Ui.iconButton(this, R.drawable.ic_close, "DevTools を閉じる", 40)
        closeReal.setOnClickListener { closeRealDevTools() }
        header.addView(closeReal)
        dtPanel.addView(header)
        dtPanel.addView(Ui.divider(this))

        realStatus = TextView(this)
        realStatus.textSize = 12f
        realStatus.setTextColor(Colors.ON_SURFACE_VARIANT)
        realStatus.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8))
        realStatus.setTextIsSelectable(true)
        realStatus.visibility = View.GONE
        dtPanel.addView(realStatus)

        // The frontend WebView is created lazily on the first
        // DevTools open (see ensureRealWebView): building it here
        // would charge every app start for a panel most sessions
        // never open.
    }

    /** Create the frontend WebView on first use and attach it. */
    private fun ensureRealWebView(): WebView {
        if (::realWebView.isInitialized) return realWebView
        val wv = createRealWebView()
        realWebView = wv
        dtPanel.addView(wv)
        return wv
    }

    /** Set once the DevTools-profile failure notice has been shown in this activity. */
    private var devToolsProfileFailureNotified = false

    private fun notifyDevToolsProfileFailure() {
        // A supported WebView, but the profile assignment failed:
        // the frontend stays on the default profile, so the next
        // clear-data wipe takes the DevTools settings with it.
        // Surface that once per activity rather than degrading
        // silently (unsupported WebViews are the accepted
        // degradation and stay silent).
        if (devToolsProfileFailureNotified) return
        devToolsProfileFailureNotified = true
        android.widget.Toast.makeText(
            this,
            "DevTools 専用プロファイルの適用に失敗しました。データ消去で DevTools の設定も消去されます",
            android.widget.Toast.LENGTH_LONG
        ).show()
        DebugLog.log("dt", "devtools profile failure notified")
    }

    /**
     * Build the DevTools frontend WebView. Extracted so a renderer
     * death can replace it: the dead instance must be removed and
     * destroyed, never reused.
     */
    private fun createRealWebView(): WebView {
        // The frontend's console can inspect a secret tab,
        // and nothing typed there is worth the keyboard
        // learning: no learning, always (SEN-290).
        val wv = PrivateInputWebView(this, noLearning = true)
        // The frontend runs on its own profile so the DevTools
        // settings survive a clear-data wipe (see DevToolsProfile).
        // Must be assigned before anything is loaded into it.
        val applyResult = DevToolsProfile.applyTo(wv)
        if (applyResult == DevToolsProfile.ApplyResult.FAILED) {
            notifyDevToolsProfileFailure()
        }
        val ws = wv.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        // Stated explicitly, as on the page WebView (audit
        // hardening): the frontend gets no content:// or file://
        // reach through this WebView either.
        ws.allowContentAccess = false
        ws.allowFileAccess = false
        // Same explicitness for mixed content (run-3
        // hardening): the page WebViews state NEVER_ALLOW
        // rather than relying on the targetSdk default, and
        // the frontend — which is https-only by its own
        // navigation gate — states it too.
        ws.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                // The frontend WebView may only ever navigate inside
                // the hosted frontend itself (plus the internal
                // schemes its own pages use). A link or window.open
                // that tries to take this WebView -- which sits next
                // to a live CDP session -- to an arbitrary page is
                // refused.
                val u = request.url
                // data:/blob: are allowed only for subframes (the
                // frontend's own workers/frames); a TOP-level page in
                // the panel is only ever the hosted frontend itself
                // or about:blank.
                val allowed = if (request.isForMainFrame) {
                    u.scheme == "about" ||
                        (u.scheme == "https" && u.host == DevToolsProxy.FRONTEND_HOST)
                } else {
                    when (u.scheme) {
                        "about", "blob", "data" -> true
                        "https" -> u.host == DevToolsProxy.FRONTEND_HOST
                        else -> false
                    }
                }
                if (!allowed) {
                    // Same redaction as every other session log line:
                    // the host of a page open in a secret target must
                    // not reach the debug log.
                    val host = if (secretRedactionActive()) "<redacted>" else u.host
                    DebugLog.log("devtools", "blocked frontend navigation to ${u.scheme}://$host")
                }
                return !allowed
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                // The tab WebViews share this renderer; returning the
                // default false here would kill the whole app even
                // though every tab recovered.
                onRealWebViewRendererGone(view)
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    // Remember WHICH load failed: WebView still
                    // calls onPageFinished for it, and that callback
                    // must not mark the frontend as loaded (the
                    // error would be overwritten by the stats ticker).
                    dtState.noteLoadFailed(request.url?.toString())
                    // The description is Chromium-generated
                    // text shown in the status line: clean and
                    // bound it like the HTTP reason phrase
                    // below (run-3 hardening).
                    val desc = PageFeaturePolicy.displayText(error?.description?.toString())
                        ?.take(64)
                    showRealStatus(
                        if (desc != null) "読み込みエラー: $desc（インターネット接続が必要です）"
                        else "読み込みエラー（インターネット接続が必要です）"
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                response: android.webkit.WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    // HTTP errors never reach onReceivedError:
                    // without this, a 404 frontend page would finish
                    // as loaded and no error would be shown. Which
                    // statuses count as failures is the holder's
                    // rule (DevToolsSessionState.noteHttpError).
                    // (response is non-null per the API contract;
                    // the null check is only defensive.)
                    val code = response?.statusCode ?: return
                    if (dtState.noteHttpError(request.url?.toString(), code)) {
                        // The reason phrase is server-controlled
                        // text shown in the status line: clean it
                        // like every other displayed string (audit
                        // hardening) before bounding it.
                        val phrase = PageFeaturePolicy.displayText(response?.reasonPhrase)
                            ?.take(64)
                        showRealStatus(
                            if (phrase != null) "読み込みエラー: HTTP $code $phrase"
                            else "読み込みエラー: HTTP $code"
                        )
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // The two finish rules (re-derive the sticky flag
                // only for a live target's real load, and never
                // mark the failed load as loaded) live in the
                // holder, unit-tested there.
                dtState.notePageFinished(
                    url,
                    targetPresent = dtTargetTab != null,
                    targetSecret = dtTargetTab?.secret == true
                )
            }
        }
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100 && realStatusLoading) {
                    realStatusLoading = false
                    realStatus.visibility = View.GONE
                }
            }

            override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                // Surface the frontend's own console output and
                // errors: if the panel ever comes up broken, the
                // frontend's own exception is the evidence that says
                // why.
                // With the debug log off there is nothing to build:
                // skip the string work entirely. Still returns true
                // below/above so Chromium never echoes the message
                // (and its token-carrying source URL) to logcat.
                if (!DebugLog.isEnabled()) return true
                val msg = cm?.message() ?: return false
                // The secret check comes FIRST: a page can make the
                // frontend quote arbitrary strings, so nothing may
                // bypass redaction. dtState.lastTargetSecret is
                // sticky: it stays set from any secret session
                // until a NEW frontend load for a live target
                // finishes, so late messages from a closed or
                // replaced secret frontend stay redacted whether
                // or not a target is current.
                if (secretRedactionActive()) {
                    DebugLog.log("dtconsole", "[${cm.messageLevel()}] <redacted: secret tab>")
                } else {
                    DebugLog.log(
                        "dtconsole",
                        "[${cm.messageLevel()}] $msg (${cm.sourceId()}:${cm.lineNumber()})"
                    )
                }
                // true = handled. Returning false would let Chromium
                // echo the message to logcat itself, source URL and
                // all, outside DebugLog's secret masking: the
                // frontend's source URL carries the proxy token.
                return true
            }
        }
        wv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        return wv
    }

    /**
     * The DevTools frontend's renderer died. Close the session WITHOUT
     * touching the dead WebView (no stopLoading/loadUrl on it), then
     * replace it with a fresh instance so the panel can be opened
     * again. The panel itself stays closed.
     *
     * The replacement is created immediately rather than deferred
     * to the next open (the lazy path the first creation takes):
     * it costs ~13ms in total (measured on device: ~4.4ms to
     * construct, ~8.4ms to apply the profile) and happens at most
     * once per renderer death, so deferring it would complicate
     * the lifecycle for no user-visible gain.
     */
    private fun onRealWebViewRendererGone(dead: WebView) {
        if (!::realWebView.isInitialized || dead !== realWebView) return
        DebugLog.log("devtools", "frontend renderer gone; closing panel and replacing the WebView")
        closeRealDevTools(unloadWebView = false)
        (dead.parent as? ViewGroup)?.removeView(dead)
        dead.destroy()
        realWebView = createRealWebView()
        dtPanel.addView(realWebView)
    }

    private fun showRealStatus(msg: String, loading: Boolean = false) {
        realStatusLoading = loading
        realStatus.text = msg
        realStatus.visibility = View.VISIBLE
    }

    private fun openRealDevTools(reconnect: Boolean = false) {
        hideMenu()
        DebugLog.log("devtools", "open (reconnect=$reconnect) url=${debugUrl(activeTab)}")
        // Decided BEFORE dtOpen is touched, from whether a session
        // was actually established (see dtSessionEstablished).
        val continuing = reconnect && dtSessionEstablished
        dtPanel.visibility = View.VISIBLE
        refreshDtHeight()
        dtOpen = true
        // The frontend (re)loads below; until it finishes, the status
        // line shows the connection outcome, not recording stats.
        dtState.resetLoadState()
        Ui.setButtonActive(devToolsBtn, true)
        refreshToolbarButtons()
        realTargetInfo.text = ""
        showRealStatus("接続中…（対象ページを検索しています）")

        // A reconnect is a continuation of the live session, not a
        // new one: the inspected tab and the recording decision made
        // when the session STARTED both carry over. Only a fresh open
        // (panel was closed) captures them.
        // The DevTools needs the debugging socket while it is open,
        // regardless of the remote-debug setting. Restored on close.
        applyWebContentsDebugging()
        if (!continuing) {
            dtTargetTab = activeTab
        } else if (dtTargetTab == null || !tabs.contains(dtTargetTab)) {
            dtTargetTab = activeTab
        }

        val p = proxy ?: DevToolsProxy().also { proxy = it }
        val port = try {
            p.start()
        } catch (e: Exception) {
            showRealStatus("プロキシを起動できませんでした: ${e.message}")
            return
        }
        // Port and token belong to one session; capture them together
        // (reading the token later could pair an old port with a
        // newer session's token after a close/reopen).
        val session = ++devToolsSession
        val token = p.sessionToken
        dtSessionEstablished = true
        // Console log recording is opt-in. The listener is set only
        // when the option is ON now, so the proxy does not even build
        // its observation tap for the default (off) case. The check
        // still runs per message, so turning the option OFF
        // mid-session stops recording at once; turning it ON takes
        // effect at the next panel open (a permessage-deflate stream
        // cannot be joined mid-way). Nothing is stored otherwise.
        if (!continuing) dtRecordingSession = Settings.consoleLogRecording(this)
        // Set for the recorder's lifetime (the recorder is
        // activity-scoped, like this supplier's capture): it is
        // consulted only while a recording session feeds the
        // recorder, and the session guard keeps stale deliveries
        // out, so it is not restored when a session closes.
        // dtTargetTab is always set while a panel session feeds
        // the recorder; the activeTab fallback only covers the
        // brief window before the target is captured, and a
        // stale read there costs at most one extra recorded
        // Document (the classifier falls to recording).
        recorder.startPageDataUrl = { (dtTargetTab ?: activeTab)?.homeDataUrl }
        p.messageListener = if (dtRecordingSession) {
            { msg ->
                // The session guard drops messages from a session
                // that has already closed: closeRealDevTools bumps
                // devToolsSession first thing and stops the proxy
                // before the final clear. A message already inside
                // the recorder at that instant can still land after
                // the clear, but it can never be exported (exports
                // snapshot before the clear) and is dropped at the
                // next fresh open or clear-data. The start page's
                // own load is excluded inside the recorder, by
                // the Document request's own URL (see
                // isInternalDocumentUrl): the tab's tracked URL
                // lags the CDP stream at a navigation boundary,
                // so it cannot judge the boundary messages itself.
                if (session == devToolsSession && Settings.consoleLogRecording(this)) {
                    recorder.onCdpMessage(msg)
                }
            }
        } else {
            null
        }
        // Remembered for the frontend console log: after the panel
        // closes, dtTargetTab is null, but late frontend messages
        // must stay redacted if the session inspected a secret tab.
        // Sticky: opening a new session can only RAISE the flag; it
        // is lowered only when a new frontend load for a live target
        // finishes (see onPageFinished), so a quick secret -> normal
        // reopen cannot un-redact the old frontend's late messages.
        dtState.noteSessionOpened(dtTargetTab?.secret == true)
        if (!continuing) {
            // A fresh open starts from an empty recorder even when
            // this session will not record: a straggler message that
            // landed after the previous close's final clear must
            // not linger in memory until clear-data. A previous
            // session's tap must likewise never be dumped as this
            // session's diagnostics, whatever the recording
            // setting.
            recorder.clear()
            p.lastTap = null
        }
        // A reconnect continues the same session's recording; a
        // fresh open starts a new one, with fresh tap counters.
        if (dtRecordingSession && !continuing) {
            p.tapBytes = 0
        }
        statsHandler.removeCallbacks(statsRunnable)
        statsHandler.postDelayed(statsRunnable, 1000)
        kotlin.concurrent.thread(name = "pl-devtools-connect") {
            // Phase 1: look for the active tab among already-registered targets.
            var found = findDevToolsTarget(port, attempts = 8, session = session)
            if (found.target == null && !found.terminal && session == devToolsSession) {
                // Phase 2: WebViews created while debugging was off may never
                // register. Recreate the active tab's WebView (URL preserved)
                // and look again. Only while this session is still the
                // live one: the panel may have been closed (e.g. the app
                // went to the background) while this thread was waiting,
                // and rebuilding the user's tab then would be wrong.
                val latch = java.util.concurrent.CountDownLatch(1)
                runOnUiThread {
                    if (!dtOpen || session != devToolsSession) {
                        latch.countDown()
                        return@runOnUiThread
                    }
                    showRealStatus("接続中…（ページを再読み込みして再試行しています）")
                    // Rebuild the INSPECTED tab (the one the target
                    // lookup is waiting for), not whichever tab the
                    // user may have switched to in the meantime.
                    (dtTargetTab?.takeIf { tabs.contains(it) } ?: activeTab)
                        ?.let { recreateTab(it) }
                    latch.countDown()
                }
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                // No fixed extra wait here: whether the rebuilt
                // WebView has registered is a state, not a
                // duration — the polling loop below observes it,
                // with its own retry pacing.
                // The session may have ended while waiting (panel
                // closed, app backgrounded): stop instead of polling
                // a dead session's socket.
                if (session != devToolsSession) return@thread
                found = findDevToolsTarget(port, attempts = 12, session = session)
            }
            val c = found.target
            val err = found.error
            runOnUiThread {
                if (!dtOpen || session != devToolsSession) return@runOnUiThread
                if (c == null) {
                    showRealStatus("接続できませんでした: ${err ?: TARGET_NOT_FOUND_FALLBACK}")
                    return@runOnUiThread
                }
                val fe = rewriteFrontendUrl(c, port, token)
                DebugLog.log(
                    "devtools",
                    "frontend: " + if (token.isNotEmpty()) fe.replace(token, "***") else fe
                )
                // The title is the inspected page's own text:
                // it goes through the shared title rule like
                // every stored title (run-3 hardening), so bidi
                // or format characters in it cannot reorder
                // the panel's own display.
                realTargetInfo.text = PageFeaturePolicy.titleText(c.title) ?: c.url
                showRealStatus("読み込み中…", loading = true)
                ensureRealWebView().loadUrl(fe)
            }
        }
    }

    /**
     * One UI snapshot for target matching: the inspected tab
     * itself, its URL, and the app-side count of tabs at that URL,
     * read together in one round trip (reading them separately
     * could pair values from different moments). The tab identity
     * rides along so the post-fetch verification also catches
     * an inspected-tab swap with an unchanged URL.
     */
    private data class InspectedSnapshot(
        val url: String,
        val count: Int,
        val tab: BrowserTab?
    )

    private fun inspectedSnapshot(): InspectedSnapshot? =
        runOnUiThreadSyncOrNull {
            val t = dtTargetTab ?: activeTab
            val u = t?.currentUrl ?: ""
            val n = if (u.isEmpty()) 0 else tabs.count {
                (it.deferredUrl ?: it.currentUrl) == u
            }
            InspectedSnapshot(u, n, t)
        }

    private fun findDevToolsTarget(
        port: Int, attempts: Int, session: Int
    ): TargetSearchOutcome<DevToolsTarget> {
        // The loop and its classification live in TargetSearch.kt
        // (pure, JVM-tested with fakes): this adapter only injects
        // the real I/O — session liveness, one attempt's
        // observation, and the retry pause — and logs the trace.
        // A dead session's search stops at once: polling a closed
        // panel's socket for the full attempt budget would only
        // stack up reconnect threads.
        val outcome = runTargetSearch(
            attempts = attempts,
            isAlive = { session == devToolsSession },
            observe = { observeTargetAttempt(port) },
            pause = { Thread.sleep(300) }
        )
        // One line per search, so a "could not connect" report
        // can be classified from the attached log alone.
        DebugLog.log("devtools", searchTraceLine(attempts, outcome))
        return outcome
    }

    /** One attempt's I/O for the target search: snapshot, fetch, verify. */
    private fun observeTargetAttempt(port: Int): TargetAttempt<DevToolsTarget> {
        // One UI snapshot per attempt, re-read on EVERY attempt:
        // a tab rebuilt in Phase 2 reports its URL only once
        // loading starts, and redirects move it at any time.
        val snapshot = inspectedSnapshot()
        return when {
            snapshot == null -> TargetAttempt.NoSnapshot
            snapshot.count > 1 -> TargetAttempt.AmbiguousTabs(snapshot.count)
            else -> try {
                val targets = DevToolsSocket.fetchTargets(port)
                // The matching decision (page filter, exact URL
                // only, match count) is pure and JVM-tested:
                // matchTargets in TargetSearch.kt. No match
                // means Phase 2 / a later attempt, never a guess.
                when (val match = matchTargets(snapshot.url, targets, { it.type }, { it.url })) {
                    is TargetMatch.One -> {
                        // Close the snapshot-to-fetch race: the
                        // inspected tab could have navigated — or
                        // another tab could have taken over its
                        // URL — between the snapshot above and
                        // this fetch. Re-read the snapshot (the
                        // same helper, so the two reads can never
                        // drift apart) and accept the match only
                        // if it is unchanged.
                        val verify = inspectedSnapshot()
                        if (verify != null && verify == snapshot) {
                            TargetAttempt.Verified(match.target)
                        } else {
                            TargetAttempt.VerifyFailed(uiSilent = verify == null)
                        }
                    }
                    is TargetMatch.None -> TargetAttempt.NotListed(match.pageCount)
                    is TargetMatch.Many -> TargetAttempt.ManyMatches(
                        match.matchCount, match.pageCount
                    )
                }
            } catch (e: Exception) {
                TargetAttempt.FetchFailed(e.message)
            }
        }
    }


    /**
     * Read a value on the UI thread from a background thread, with a
     * bounded wait. Returns null on timeout instead of force-casting
     * a missing result (a non-null contract would crash its
     * caller in exactly that case). The result hand-off goes
     * through atomics + the latch, so a late UI execution
     * after a timeout is harmless (callers pass read-only
     * blocks).
     */
    private fun <T> runOnUiThreadSyncOrNull(block: () -> T): T? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val ref = java.util.concurrent.atomic.AtomicReference<T?>()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            ref.set(block())
            done.set(true)
            latch.countDown()
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return if (done.get()) ref.get() else null
    }

    /**
     * Recreate a tab's WebView in place, keeping its position, URL and
     * mode. Used by the DevTools phase-2 reconnect: one dead page must
     * not take the whole tab list down. Only the active tab is
     * switched to afterwards. The URL is loaded via
     * [BrowserTab.loadSavedUrl], never loadInput: it is a saved URL,
     * not user input.
     */
    private fun recreateTab(old: BrowserTab) {
        rebuildTab(old) { t, url, _ -> t.loadSavedUrl(url) }
    }

    /**
     * Renderer-crash recovery. Consecutive deaths are counted per
     * tab (see BrowserTab.rendererDeaths): past
     * [MAX_RENDERER_AUTO_RELOADS] the tab is NOT auto-reloaded again
     * -- a page that reliably kills its renderer would otherwise loop
     * forever -- and lands on the home page instead.
     * Background tabs are rebuilt without loading at all; their URL
     * is deferred until first shown, so one shared-renderer death
     * does not reload every tab at once.
     */
    private fun recreateTabAfterCrash(old: BrowserTab) {
        rebuildTab(old, crashed = true) { t, url, wasActive ->
            when {
                !wasActive -> {
                    // Background tabs do not earn a death count: with
                    // a shared renderer their callback fires for a
                    // death they did not cause, and a deferred tab may
                    // not even have been loaded. They are rebuilt
                    // unloaded (below) with a clean counter.
                    t.rendererDeaths = 0
                    if (!BrowserTab.isHomeUrl(url)) {
                        t.currentUrl = url
                        t.deferredUrl = url
                        // Carry the dead tab's navigation state:
                        // the deferred load in switchTo restores
                        // it instead of loading just this URL,
                        // when the snapshot matches (takeNavStateFor
                        // hands it over and drops the dead tab's own
                        // reference in the same step).
                        old.takeNavStateFor(url)?.let { t.adoptNavState(it, url) }
                    } else {
                        t.loadHome()
                    }
                }
                else -> {
                    // Per-TAB consecutive count for the tab the user
                    // was actually looking at. The URL is not part of
                    // the key: the page chooses it, so a per-URL count
                    // could be reset by the crashing page itself.
                    val deaths = old.rendererDeaths + 1
                    t.rendererDeaths = deaths
                    if (deaths > MAX_RENDERER_AUTO_RELOADS && !BrowserTab.isHomeUrl(url)) {
                        t.loadHome()
                        android.widget.Toast.makeText(
                            this,
                            "ページが繰り返しクラッシュしたため、自動の再読み込みを止めました",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else if (!restoreCrashedTabHistory(old, t, url)) {
                        t.loadSavedUrl(url)
                    }
                }
            }
        }
    }

    /**
     * Restore a crash-rebuilt tab's back/forward history from
     * its predecessor's last-commit snapshot (see
     * BrowserTab.takeNavStateFor), so recovery keeps more than the
     * current URL alone. False when there is no usable snapshot
     * for [url], or the fresh WebView refused it — the caller
     * then loads the URL on its own, which is what recovery
     * did before saved state.
     */
    private fun restoreCrashedTabHistory(old: BrowserTab, t: BrowserTab, url: String): Boolean {
        val state = old.takeNavStateFor(url) ?: return false
        t.adoptNavState(state, url)
        val restored = t.restoreNavState(url)
        DebugLog.log("tab", "crash recovery history restore=$restored")
        return restored
    }

    /** Shared body of the recreate paths: swap the tab at its index. */
    private fun rebuildTab(
        old: BrowserTab,
        crashed: Boolean = false,
        load: (BrowserTab, String, Boolean) -> Unit
    ) {
        val idx = tabs.indexOf(old)
        if (idx < 0) return
        val url = old.deferredUrl ?: old.currentUrl
        val wasActive = activeTab === old
        pageContainer.removeView(old.webView)
        beforeTabDestroyed(old)
        if (crashed) old.destroyAfterRendererDeath() else old.destroy()
        val t = BrowserTab(this, this, old.secret)
        tabs[idx] = t
        if (dtTargetTab === old) dtTargetTab = t
        // The death counter survives a rebuild by default (e.g. the
        // DevTools phase-2 reconnect must not silently reset it);
        // the crash path overrides it explicitly in its load block.
        t.rendererDeaths = old.rendererDeaths
        // The prompt-suppression state survives too: it records
        // the USER's refusals (and cancel abuse), and a page that
        // can crash its own renderer must not be able to shed it
        // by being rebuilt.
        t.downloadPromptSuppressed = old.downloadPromptSuppressed
        t.permissionPromptSuppressed = old.permissionPromptSuppressed
        t.permissionWebViewCancels = old.permissionWebViewCancels
        // Desktop mode survives too: a crash recovery or Phase-2
        // rebuild must not silently revert the tab to mobile.
        // Set before the load block runs.
        t.adoptDesktopMode(old.desktopMode)
        t.webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        pageContainer.addView(t.webView, pageContainer.childCount - 1)
        t.webView.visibility = if (wasActive) View.VISIBLE else View.GONE
        // A background rebuild starts paused, like any other
        // background tab.
        if (!wasActive) t.webView.onPause()
        // Preset the URL the tab is about to load: until
        // onPageStarted arrives, currentUrl would read as "" and the
        // DevTools target lookup (which matches on it) could not
        // find this tab.
        t.currentUrl = url
        // Point activeTab at the new tab BEFORE switchTo, always:
        // switchTo would otherwise capture a thumbnail from the old
        // tab -- which this method has just destroyed (draw on a dead
        // or destroyed WebView), and the capture would be discarded
        // with the old tab object anyway.
        if (wasActive) activeTab = t
        try {
            load(t, url, wasActive)
        } finally {
            // Whichever branch the load block took, the old
            // tab's navigation snapshot is settled now: a crash
            // branch either handed it to the replacement
            // (takeNavStateFor already dropped the old
            // reference) or has no use for it — the crash-loop
            // stop and the home branches never take it. Drop
            // whatever remains so the dead tab cannot keep its
            // history for as long as the object itself happens
            // to be referenced. In a finally so a load that
            // throws cannot leave it behind either.
            old.discardNavState()
        }
        if (wasActive) switchTo(t)
    }

    /**
     * Build the frontend URL. The page itself is always the hosted
     * frontend; only its serve_rev path is taken from the socket's
     * report, and the query is built here. (The reported ws=
     * value is NOT rewritten in place: look-alike parameters
     * and percent-encoded values could slip the proxy address
     * past unchanged.)
     */
    private fun rewriteFrontendUrl(c: DevToolsTarget, port: Int, token: String): String {
        var path = ""
        val reported = c.devtoolsFrontendUrl
        val candidate =
            if (reported.startsWith("/")) {
                "${DevToolsProxy.FRONTEND_ORIGIN}$reported"
            } else {
                reported
            }
        val origin = DevToolsProxy.FRONTEND_ORIGIN
        if (candidate.startsWith("$origin/")) {
            val q = candidate.indexOf('?')
            path = candidate.substring(origin.length, if (q >= 0) q else candidate.length)
        }
        if (!path.startsWith("/serve_rev/") ||
            !path.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "/_@.+-"
            } ||
            path.split('/').any { it == ".." }
        ) {
            // Not a plain serve_rev path (or it carries characters
            // like '#' that would swallow the query into a fragment):
            // fall back to the known shape.
            path = "/serve_rev/@0/inspector.html"
        }
        // The ws= endpoint carries the session token as a path prefix;
        // the proxy accepts nothing else. The token therefore rides
        // in this URL to the frontend host — an accepted residue
        // recorded in REVIEW.md, bounded by the token dying with
        // the panel session.
        return "$origin$path?ws=127.0.0.1:$port/t/$token/devtools/page/${c.id}"
    }

    private fun closeRealDevTools() = closeRealDevTools(unloadWebView = true)

    /**
     * @param unloadWebView false when the frontend WebView's renderer
     * has died: the dead instance must not be called into at all; the
     * caller replaces it after this returns.
     */
    private fun closeRealDevTools(unloadWebView: Boolean) {
        devToolsSession++
        statsHandler.removeCallbacks(statsRunnable)
        dtPanel.visibility = View.GONE
        dtOpen = false
        // The status line belongs to the session that is ending;
        // do not carry a stale "loading" flag into the next one
        // (every showRealStatus call re-decides it anyway).
        realStatusLoading = false
        dtState.resetLoadState()
        Ui.setButtonActive(devToolsBtn, false)
        refreshToolbarButtons()
        if (unloadWebView && ::realWebView.isInitialized) {
            realWebView.stopLoading()
            realWebView.loadUrl("about:blank")
        }
        // Export is a property of the session that is ending (was
        // recording built for it?), not of the setting right now.
        if (dtRecordingSession) {
            if (DebugLog.isEnabled()) writeTapDebug()
            recordConsoleLog()
            // Stop the flow BEFORE the final clear: with the
            // sockets cut and the session number bumped (top of
            // this function), the recorder is as quiet as it can
            // be made; recordConsoleLog already snapshotted
            // synchronously, so clearing here cannot lose the
            // export. proxy.stop() is idempotent
            // and runs again at the end of this function.
            proxy?.stop()
            recorder.clear()
        }
        dtRecordingSession = false
        dtSessionEstablished = false
        dtTargetTab = null
        proxy?.stop()
        applyWebContentsDebugging()
    }

    /** Diagnostics dump for the console-log tap, written next to the logs. */
    private fun writeTapDebug() {
        val tap = proxy?.lastTap
        val text = buildString {
            appendLine("tapBytes=${proxy?.tapBytes ?: 0}")
            appendLine("delivered=${tap?.deliveredCount ?: -1}")
            appendLine("inflateTried=${tap?.inflateTried ?: -1}")
            appendLine("inflateOk=${tap?.inflateOk ?: -1}")
            appendLine("recorderSeen=${recorder.messagesSeen}")
            appendLine("recorderFailures=${recorder.parseFailures}")
            appendLine("entries=${recorder.size()}")
            appendLine("methods=${recorder.recentMethods()}")
            // The samples and the head hex are raw CDP fragments
            // and can carry the inspected page's URL (the head hex
            // is the first frames hex-encoded): redacted for a
            // secret target, like every other log surface.
            val secretTarget = secretRedactionActive()
            // A sealed capture (after a clear) reads <cleared>, so
            // an empty headHex means "nothing flowed", not "sealed".
            val headText = when {
                secretTarget -> "<redacted>"
                tap == null -> ""
                tap.isHeadSealed() -> "<cleared>"
                else -> tap.headHex()
            }
            appendLine("headHex=$headText")
            val s = tap?.samplesSnapshot() ?: emptyList()
            for ((i, sample) in s.withIndex()) {
                appendLine(
                    "sample[$i]=${if (secretTarget) "<redacted>" else DebugLog.escapeLine(sample)}"
                )
            }
            appendLine(
                "lastParseSample=${if (secretTarget) "<redacted>" else DebugLog.escapeLine(recorder.lastParseSample)}"
            )
        }
        logFileExecutor.execute {
            try {
                java.io.File(ensureLogsDir(), "tap-debug.txt").writeText(text)
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureLogsDir(): java.io.File = DebugLog.logsDir(this)

    /** Write the recorded console log as .json + .log into the recordings folder. */
    private fun recordConsoleLog() {
        // EVERYTHING the export needs is captured synchronously:
        // the caller clears the recorder right after this returns,
        // so the formatters must work from this immutable snapshot
        // alone (they are pure functions of it). Formatting itself
        // happens on the log-file executor: formatting thousands
        // of entries on the UI thread would block it, and reading
        // live recorder state from the export thread would race
        // clear() and lose the network correlation and the
        // dropped-count notes.
        val snapshot = recorder.snapshot()
        val (requests, network) = recorder.requestsAndNetwork()
        val droppedEntries = recorder.entriesDropped
        val droppedRequests = recorder.requestsDropped
        // Tap-level losses, captured with the rest of the
        // snapshot: they belong in the export's header notes.
        // The tap reference is read ONCE here: two reads could
        // straddle a reconnect and mix two taps' counters.
        val exportTap = proxy?.lastTap
        val tapDropped = exportTap?.droppedMessages ?: 0
        val tapFailed = exportTap?.hasFailed ?: false
        val seen = recorder.messagesSeen
        val failures = recorder.parseFailures
        // Decided here, not on the executor: millisecond resolution
        // keeps two exports in the same second from overwriting
        // each other.
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
            .format(java.util.Date())
        if (snapshot.isEmpty() && requests.isEmpty()) {
            val methods = recorder.recentMethods().takeLast(3).joinToString(", ")
            // The parse sample is a raw page-influenced fragment:
            // redacted when the session inspected a secret tab,
            // like every other log surface.
            val sample = if (secretRedactionActive()) {
                "<redacted>"
            } else {
                recorder.lastParseSample
            }
            android.widget.Toast.makeText(
                this,
                "記録0件（受信 $seen / 失敗 $failures / " +
                    "メソッド: $methods / 失敗例: $sample）",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        logFileExecutor.execute {
            try {
                // One file at a time -- format, write, release -- so
                // a near-cap recording does not hold the renderings
                // in memory at once (three are built as strings;
                // the requests JSON streams). Throwable is
                // caught: an OutOfMemoryError here must degrade to
                // a failed-export toast, not a dead process.
                // The destination is the app-specific recordings
                // folder (owner design, 2026-11-23): recordings
                // stay the app's own until the user exports them
                // from the console-export page, and an uninstall
                // takes them with the app. Each write goes through
                // a temp file renamed into place, so a killed
                // write leaves no truncated recording behind.
                if (snapshot.isNotEmpty()) {
                    RecordedLogs.writeText(
                        this, "devtools-console-$stamp.json",
                        recorder.toJson(snapshot, network)
                    )
                    RecordedLogs.writeText(
                        this, "devtools-console-$stamp.log",
                        recorder.toText(snapshot, network, droppedEntries, tapDropped, tapFailed)
                    )
                }
                if (requests.isNotEmpty()) {
                    // The requests JSON streams straight to its file:
                    // at the caps the rendered string alone is megabytes,
                    // and formatting it in memory first would keep exactly
                    // that peak. The write goes through a temp file
                    // renamed into place on completion, so a failure
                    // leaves no truncated recording behind and the
                    // outer catch reports it.
                    RecordedLogs.write(this, "devtools-requests-$stamp.json") { writer ->
                        // writeRequestsJson takes a Writer;
                        // AtomicFileWrite's writer is buffered and
                        // closes -- and so flushes -- before the
                        // temp file is renamed into place.
                        recorder.writeRequestsJson(requests, writer)
                    }
                    RecordedLogs.writeText(
                        this, "devtools-requests-$stamp.log",
                        recorder.toRequestsText(requests, droppedRequests, tapDropped, tapFailed)
                    )
                }
                RecordedLogs.prune(this)
                DebugLog.log(
                    "export",
                    "saved console=${snapshot.size} requests=${requests.size} " +
                        "seen=$seen failures=$failures"
                )
                val droppedNote = when {
                    droppedEntries > 0 || droppedRequests > 0 ->
                        "（上限で古い記録を破棄: コンソール ${droppedEntries}件 / リクエスト ${droppedRequests}件）"
                    else -> ""
                }
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        "ログを記録しました（コンソール ${snapshot.size}件 / リクエスト ${requests.size}件）$droppedNote",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        "ログの記録に失敗しました: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    private fun toggleRemoteDebug() {
        hideMenu()
        // Recreating every tab WebView would silently kill the live
        // DevTools session (its target WebView is destroyed too), so
        // an open panel is closed first -- with its export -- exactly
        // as when the inspected tab is closed.
        if (dtOpen) closeRealDevTools()
        val newValue = !Settings.remoteDebug()
        Settings.setRemoteDebug(newValue)
        applyWebContentsDebugging()
        // Recreate tab WebViews so the new debugging state applies everywhere.
        recreateTabsPreservingUrls()
    }

    private fun recreateTabsPreservingUrls() {
        if (tabs.isEmpty()) return
        // Each tab keeps its OWN mode: rebuilding a secret tab as a
        // normal one would silently move its pages out of the secret
        // space. The desktop-mode flag travels too.
        // dtTargetTab needs no carry: the only caller closes
        // the panel first, which nulls it — a targetIdx carry
        // here would be unreachable for that reason.
        val saved = tabs.map { Triple(it.deferredUrl ?: it.currentUrl, it.secret, it.desktopMode) }
        val activeIdx = tabs.indexOf(activeTab).coerceAtLeast(0)
        for (t in tabs.toList()) {
            pageContainer.removeView(t.webView)
            beforeTabDestroyed(t)
            t.destroy()
        }
        tabs.clear()
        // The old active tab is destroyed; do not let the final
        // switchTo capture a thumbnail from it.
        activeTab = null
        var newActive: BrowserTab? = null
        withRestoringTabs {
            for ((i, entry) in saved.withIndex()) {
                // Same deferred rule as restore: only the active tab
                // loads now; background tabs load when first shown, so a
                // settings toggle does not reload every tab at once.
                val t = newTab(secret = entry.second, activate = false)
                t.adoptDesktopMode(entry.third)
                if (BrowserTab.isHomeUrl(entry.first)) {
                    t.loadHome()
                } else {
                    t.currentUrl = entry.first
                    t.deferredUrl = entry.first
                }
                if (i == activeIdx) newActive = t
            }
        }
        newActive?.let { switchTo(it) }
        updateTabCount()
    }

    /** One row style for both menus (browser menu and tab menu):
     * 48dp tall, 20dp icon, 14sp label, optional checkbox right
     * (the row carries checkbox semantics, so TalkBack reads
     * the state). */
    private fun menuRow(iconRes: Int, label: String, checked: Boolean? = null, enabled: Boolean = true, action: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48))
        row.isClickable = enabled
        val iv = ImageView(this)
        iv.setImageResource(iconRes)
        iv.setColorFilter(if (enabled) Colors.ON_SURFACE_VARIANT else Colors.OUTLINE)
        iv.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20))
        row.addView(iv)
        val labelView = Ui.textRow(this, label, 14f, if (enabled) Colors.ON_SURFACE else Colors.OUTLINE)
        labelView.setPadding(Ui.dp(this, 12), 0, 0, 0)
        labelView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(labelView)
        if (checked != null) {
            val box = ImageView(this)
            box.setImageResource(if (checked) R.drawable.ic_square_check else R.drawable.ic_square)
            box.setColorFilter(if (checked) Colors.PRIMARY else Colors.ON_SURFACE_VARIANT)
            box.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22))
            // A visual mirror of the row's state, not a
            // separate TalkBack stop (the settings and export
            // rows' treatment of their native checkboxes).
            box.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.addView(box)
            Ui.applyCheckboxSemantics(row, checked)
        }
        if (enabled) row.setOnClickListener { action() }
        return row
    }

    private fun showMenu() {
        expireActiveTabPopupProbes()
        val t = activeTab ?: return
        menuList.removeAllViews()
        for (entry in MenuSpec.ITEMS) {
            val row: View? = when (entry) {
                MenuSpec.Separator -> {
                    menuList.addView(menuDivider())
                    null
                }
                is MenuSpec.Action -> actionRow(entry, t)
                is MenuSpec.Checkbox -> checkboxRow(entry, t)
                // Not implemented yet: listed, but not clickable --
                // tapping does nothing at all, not even a click
                // sound.
                is MenuSpec.Placeholder ->
                    menuRow(menuIcon(entry.icon), entry.label) { }.apply { isClickable = false }
            }
            if (row != null) menuList.addView(row)
        }

        // Cap the list's height so the sheet, footer included,
        // fits the area the sheet actually gets: the parent's
        // inset-applied height minus the sheet's bottom margin,
        // minus the measured footer and the divider. Beyond the
        // cap the list scrolls and the footer stays pinned.
        menuFooter.measure(
            View.MeasureSpec.makeMeasureSpec(Ui.dp(this, MENU_SHEET_WIDTH_DP), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val parent = menuSheet.parent as View
        val sheetArea = parent.height - parent.paddingTop - parent.paddingBottom -
            Ui.dp(this, MENU_SHEET_BOTTOM_MARGIN_DP)
        val cap = (sheetArea - menuFooter.measuredHeight - menuFooterDivider.layoutParams.height)
            .coerceAtLeast(Ui.dp(this, MENU_LIST_MIN_HEIGHT_DP))
        menuList.measure(
            View.MeasureSpec.makeMeasureSpec(Ui.dp(this, MENU_SHEET_WIDTH_DP), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val lp = menuScroll.layoutParams as LinearLayout.LayoutParams
        lp.height = minOf(menuList.measuredHeight, cap)
        menuScroll.layoutParams = lp
        refreshMenuFooter(t)
        menuScrim.visibility = View.VISIBLE
        menuSheet.visibility = View.VISIBLE
        menuScroll.post { menuScroll.scrollTo(0, 0) }
    }

    /** The menu footer's state for the active tab: back and
     * forward mirror the tab's history, and share is live only
     * for a real web page -- the start page and blank tabs have
     * no URL another app could open (ShareText). Tint,
     * clickability and enabled state move together, so the
     * disabled share reads as disabled to accessibility too.
     * The state is a snapshot taken when the menu opens; if
     * the page navigates while the menu is up it is not
     * re-evaluated here -- shareCurrentPage re-checks at tap
     * time, so a stale enabled state still cannot share an
     * unshareable page. */
    private fun refreshMenuFooter(t: BrowserTab) {
        menuBackBtn?.setColorFilter(if (t.webView.canGoBack()) Colors.ICON else Colors.OUTLINE)
        menuForwardBtn?.setColorFilter(if (t.webView.canGoForward()) Colors.ICON else Colors.OUTLINE)
        val shareable = ShareText.isShareable(t.currentUrl)
        menuShareBtn?.setColorFilter(if (shareable) Colors.ICON else Colors.OUTLINE)
        menuShareBtn?.isClickable = shareable
        menuShareBtn?.isEnabled = shareable
        // The bookmark toggle mirrors the store: a minus icon
        // while this page is bookmarked, greyed out entirely
        // where a bookmark makes no sense (start page, blank).
        val bookmarkable = isWebLink(t.currentUrl) && !BrowserTab.isHomeUrl(t.currentUrl)
        val bookmarked = bookmarkable && bookmarkStore.findByUrl(t.currentUrl) != null
        menuBookmarkBtn?.setImageResource(
            if (bookmarked) R.drawable.ic_bookmark_minus else R.drawable.ic_bookmark_plus,
        )
        menuBookmarkBtn?.setColorFilter(if (bookmarkable) Colors.ICON else Colors.OUTLINE)
        menuBookmarkBtn?.isClickable = bookmarkable
        menuBookmarkBtn?.isEnabled = bookmarkable
        menuBookmarkBtn?.contentDescription =
            if (bookmarked) "ブックマークを削除" else "ブックマークに追加"
    }

    /** One ACTION entry's row. Exhaustive over ActionId: a new
     * action in MenuSpec without a branch here is a compile
     * error. */
    private fun actionRow(item: MenuSpec.Action, t: BrowserTab): LinearLayout = when (item.id) {
        MenuSpec.ActionId.HISTORY -> menuRow(menuIcon(item.icon), item.label) {
            hideMenu(); showHistoryPage()
        }
        MenuSpec.ActionId.BOOKMARKS -> menuRow(menuIcon(item.icon), item.label) {
            hideMenu(); showBookmarksPage()
        }
        MenuSpec.ActionId.FIND -> menuRow(menuIcon(item.icon), item.label) {
            hideMenu(); openFind()
        }
        MenuSpec.ActionId.TRANSLATE -> {
            // Rendering reads the state without creating a
            // translator: a tab that never translated is IDLE
            // by definition.
            val translateState = pageTranslators[t]?.state ?: PageTranslator.State.IDLE
            menuRow(
                menuIcon(item.icon),
                // State-dependent labels, mirroring the DevTools
                // item: while a run is working the item cancels
                // it; while this page is translated the item
                // restores the original instead.
                when (translateState) {
                    PageTranslator.State.TRANSLATED -> MenuSpec.TRANSLATE_RESTORE_LABEL
                    PageTranslator.State.WORKING -> MenuSpec.TRANSLATE_CANCEL_LABEL
                    PageTranslator.State.IDLE -> item.label
                },
                // The run acts on the committed (displayed)
                // document, so the gate reads its URL, not the
                // in-flight target currentUrl may already name.
                // While the start page is displayed,
                // lastCommittedUrl still names the previous
                // page, so that display is excluded through the
                // tab's homeDisplayed predicate (the same gate
                // PageTranslator.start applies).
                enabled = !t.homeDisplayed &&
                    TranslatePlan.isTranslatableUrl(t.lastCommittedUrl)
            ) {
                hideMenu()
                translatorFor(t).toggle()
            }
        }
        MenuSpec.ActionId.DEVTOOLS -> menuRow(
            menuIcon(item.icon),
            // The one state-dependent label (owner decision,
            // 2026-11-22): the spec label is the closed-state
            // name; while the panel is open the item closes it,
            // and says so.
            if (dtOpen) MenuSpec.DEVTOOLS_CLOSE_LABEL else item.label,
            enabled = dtOpen || !BrowserTab.isHomeUrl(t.currentUrl)
        ) {
            hideMenu()
            if (dtOpen) closeRealDevTools() else openRealDevTools()
        }
        MenuSpec.ActionId.CONSOLE_EXPORT_PAGE -> menuRow(
            menuIcon(item.icon), item.label,
            // Greyed out while there is nothing to export
            // (owner design, 2026-11-23): the count is a single
            // small directory listing, taken as the menu opens.
            enabled = RecordedLogs.count(this) > 0
        ) {
            hideMenu(); showExportPage()
        }
        MenuSpec.ActionId.SETTINGS -> menuRow(menuIcon(item.icon), item.label) {
            hideMenu(); showSettingsPage()
        }
        MenuSpec.ActionId.EXIT -> menuRow(menuIcon(item.icon), item.label) {
            finish()
        }
    }

    /** One CHECKBOX entry's row: the checkbox reflects the live
     * state, and tapping runs the same toggle (with the same
     * side effects) as before the menu restructure. Exhaustive
     * over CheckboxId. */
    private fun checkboxRow(item: MenuSpec.Checkbox, t: BrowserTab): LinearLayout = when (item.id) {
        MenuSpec.CheckboxId.DESKTOP -> menuRow(
            menuIcon(item.icon), item.label, checked = t.desktopMode
        ) {
            hideMenu(); t.setDesktopMode(!t.desktopMode); saveTabSession()
        }
        MenuSpec.CheckboxId.REMOTE_DEBUG -> menuRow(
            menuIcon(item.icon), item.label, checked = Settings.remoteDebug()
        ) {
            toggleRemoteDebug()
        }
        MenuSpec.CheckboxId.CONSOLE_RECORD -> menuRow(
            menuIcon(item.icon), item.label, checked = Settings.consoleLogRecording(this)
        ) {
            hideMenu()
            val newValue = !Settings.consoleLogRecording(this)
            Settings.setConsoleLogRecording(this, newValue)
            // No recorder.clear() here: a fresh open clears
            // unconditionally, and clearing on toggle would silently
            // wipe the in-progress recording when the user flips
            // OFF -> ON mid-session. Nothing else to prepare: the
            // recordings folder is created on the first write.
        }
    }

    // -- PageTranslator.Host: the translation UI -------------

    /** The tab holding the one activity-wide translation run
     * (the dialogs are activity-level, so runs cannot overlap;
     * PageTranslator claims and releases through here). */
    private var translateRunOwner: BrowserTab? = null

    override fun claimTranslateRun(tab: BrowserTab): Boolean {
        if (translateRunOwner != null && translateRunOwner !== tab) return false
        translateRunOwner = tab
        return true
    }

    override fun releaseTranslateRun(tab: BrowserTab) {
        if (translateRunOwner === tab) translateRunOwner = null
    }

    override fun requestModelDownloadConsent(
        singleLanguageName: String?,
        modelCount: Int,
        isUpperBound: Boolean,
        onDecision: (Boolean) -> Unit,
    ) {
        // The decision must come back exactly once on EVERY
        // exit path -- approve, decline, back button, outside
        // tap: a path that answers zero times would strand the
        // run in WORKING with its clients open. The once-guard
        // below owns that contract.
        var decided = false
        fun decide(approved: Boolean) {
            if (decided) return
            decided = true
            onDecision(approved)
        }
        // Defensive: the run starts from the menu, which no
        // other modal can share the screen with, but if one is
        // somehow pending the consent dialog must not stack on
        // it (SEN-356) -- decline instead and let the run end.
        if (isUserModalPending() || activityStopped || isFinishing) {
            decide(false)
            return
        }
        // A single-language run names its language; a mixed
        // page names none (which models are missing -- a
        // source's, the shared pivot's, the target's -- is
        // not a property of any one language, and a partial
        // name list misleads). The amount is the caller's
        // real missing-model count (about 30 MB each), or
        // its stated upper bound when the inventory could
        // not be read: the user agrees to the number they
        // see.
        val subject = if (singleLanguageName != null) {
            "${singleLanguageName}の翻訳に必要なモデル"
        } else {
            "このページには複数の言語が含まれています。翻訳に必要なモデル"
        }
        val amount = if (isUpperBound) {
            "（最大約${modelCount}個、合計約${modelCount * TranslatePlan.MODEL_SIZE_MB}MB）"
        } else {
            "（約${modelCount}個、合計約${modelCount * TranslatePlan.MODEL_SIZE_MB}MB）"
        }
        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(this)
            .setTitle("翻訳モデルのダウンロード")
            .setMessage(
                "${subject}をダウンロードします${amount}。" +
                    "ダウンロード後はオフラインでも翻訳できます。" +
                    "モバイルデータ通信中は通信量にご注意ください。"
            )
            .setPositiveButton("ダウンロード") { _, _ -> decide(true) }
            .setNegativeButton("キャンセル") { _, _ -> decide(false) }
            .setOnCancelListener { decide(false) }
            .setOnDismissListener {
                // Identity check: approving swaps straight into
                // the progress dialog, whose own tracking must
                // survive this dialog's dismissal.
                if (translateConsentDialog === dialog) translateConsentDialog = null
                // The decision comes back exactly once on every
                // exit path (the Host contract): a dismissal
                // that bypassed the buttons and the cancel
                // listener — the tab being torn down, the run
                // retired by a tab switch — still answers, as a
                // decline. The once-guard in decide() makes
                // this a no-op after any button answer.
                decide(false)
            }
            .create()
        translateConsentDialog = dialog
        // A modal coming up ends the run owner's undecided
        // popups (run-3 hardening): a probe that decided
        // under this dialog would open its tab over the
        // consent (onPopupTab also refuses while a modal is
        // pending — the two together close the window
        // between the probe's creation and its decision).
        translateRunOwner?.expirePopupProbes()
        dialog.show()
        // The consent can directly replace this run's own
        // DETECTING progress dialog, whose lone button sits
        // where this dialog's positive button lands: a tap
        // aimed at cancelling the detection must not become
        // a download consent. The approval arm delay
        // (SEN-305) covers the swap; the refusal side stays
        // live from the first moment.
        armApprovalButton(dialog)
    }

    override fun showTranslateProgress(
        progress: TranslateProgress,
        done: Int,
        total: Int,
        onCancel: () -> Unit,
    ) {
        val message = when (progress) {
            TranslateProgress.DETECTING ->
                if (total > 0) "言語を判定しています…（$done/$total）" else "言語を判定しています…"
            TranslateProgress.DOWNLOADING_MODEL -> "翻訳モデルをダウンロードしています…"
            TranslateProgress.TRANSLATING ->
                if (total > 0) "翻訳しています…（$done/$total）" else "翻訳しています…"
        }
        val existing = translateProgressDialog
        if (existing != null && existing.isShowing) {
            translateProgressMessage?.text = message
            return
        }
        // Never stack on another feature's modal (SEN-356):
        // the run continues without its progress UI rather
        // than covering a prompt the page is waiting on.
        if (otherModalPending()) {
            return
        }
        if (activityStopped || isFinishing) return
        val messageView = TextView(this).apply {
            text = message
            setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 4))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                ProgressBar(this@MainActivity),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = Ui.dp(this@MainActivity, 24) }
            )
            addView(messageView)
        }
        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(this)
            .setView(body)
            .setNegativeButton("キャンセル") { _, _ -> onCancel() }
            .setOnDismissListener {
                if (translateProgressDialog === dialog) {
                    translateProgressDialog = null
                    translateProgressMessage = null
                }
            }
            .create()
        dialog.setCancelable(false)
        translateProgressDialog = dialog
        translateProgressMessage = messageView
        // Same popup expiry as the consent's (run-3
        // hardening): the progress dialog is a modal too.
        translateRunOwner?.expirePopupProbes()
        dialog.show()
    }

    override fun dismissTranslateDialogs() {
        // The fields clear synchronously, before dismiss():
        // a dialog's OnDismissListener runs later, on a
        // posted message, so a caller that dismisses and
        // immediately consults the modal predicate (the
        // consent path does exactly that) would still see
        // the run's own progress dialog as pending and
        // decline itself. The listeners identity-check
        // their dialog, so clearing first is safe: when
        // the posted dismissal lands, the field no longer
        // points at that dialog.
        val consent = translateConsentDialog
        val progress = translateProgressDialog
        translateConsentDialog = null
        translateProgressDialog = null
        translateProgressMessage = null
        consent?.let { if (it.isShowing) it.dismiss() }
        progress?.let { if (it.isShowing) it.dismiss() }
    }

    override fun toast(notice: TranslateNotice) {
        val message = when (notice) {
            TranslateNotice.NO_TEXT -> "翻訳できるテキストが見つかりませんでした"
            TranslateNotice.ALREADY_TARGET -> "このページは日本語です"
            TranslateNotice.UNKNOWN_LANGUAGE -> "言語を判定できませんでした"
            TranslateNotice.UNSUPPORTED_LANGUAGE -> "この言語の翻訳には対応していません"
            TranslateNotice.TRANSLATED -> "翻訳しました"
            TranslateNotice.TRANSLATED_PARTIAL -> "翻訳しました（ページが大きいため一部のみ）"
            TranslateNotice.TRANSLATED_WITH_SKIPS -> "翻訳しました（一部は原文のままです）"
            TranslateNotice.PARTIALLY_FAILED -> "翻訳しました（一部は翻訳できませんでした）"
            TranslateNotice.FAILED -> "翻訳に失敗しました"
            TranslateNotice.MODEL_DOWNLOAD_FAILED -> "翻訳モデルのダウンロードに失敗しました"
            TranslateNotice.BUSY_OTHER_TAB -> "別のタブで翻訳中です"
        }
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun menuDivider(): View {
        val v = View(this)
        v.setBackgroundColor(Colors.OUTLINE_VARIANT)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        lp.setMargins(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8))
        v.layoutParams = lp
        return v
    }

    /** The menu's fixed footer: icon-only buttons from MenuSpec.FOOTER. */
    private fun buildMenuFooter(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6))
        for (item in MenuSpec.FOOTER) {
            val b = footerButton(item)
            // Kept as fields so showMenu can tint them by the
            // active tab's history state. Nullable on purpose:
            // if the footer ever loses one of them, the tinting
            // is skipped instead of crashing on an unassigned
            // field.
            if (item.id == MenuSpec.FooterId.BACK) menuBackBtn = b
            if (item.id == MenuSpec.FooterId.FORWARD) menuForwardBtn = b
            if (item.id == MenuSpec.FooterId.BOOKMARK_TOGGLE) menuBookmarkBtn = b
            if (item.id == MenuSpec.FooterId.SHARE) menuShareBtn = b
            bar.addView(b)
        }
        return bar
    }

    /** One footer button, configured by its id. Exhaustive over
     * FooterId: a new footer entry in MenuSpec without a branch
     * here is a compile error. */
    private fun footerButton(item: MenuSpec.FooterItem): android.widget.ImageButton {
        val b = Ui.iconButton(this, menuIcon(item.icon), item.label, 44)
        b.layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f)
        return when (item.id) {
            MenuSpec.FooterId.BACK -> b.apply {
                setOnClickListener {
                    val t = activeTab ?: return@setOnClickListener
                    hideMenu()
                    if (t.webView.canGoBack()) { t.noteUserNavigation(); t.webView.goBack() }
                }
            }
            MenuSpec.FooterId.HOME -> b.apply {
                setOnClickListener {
                    val t = activeTab ?: return@setOnClickListener
                    hideMenu(); t.noteUserNavigation(); t.loadHome()
                }
            }
            MenuSpec.FooterId.FORWARD -> b.apply {
                setOnClickListener {
                    val t = activeTab ?: return@setOnClickListener
                    hideMenu()
                    if (t.webView.canGoForward()) { t.noteUserNavigation(); t.webView.goForward() }
                }
            }
            MenuSpec.FooterId.SHARE -> b.apply {
                setOnClickListener {
                    val t = activeTab ?: return@setOnClickListener
                    shareCurrentPage(t)
                }
            }
            MenuSpec.FooterId.BOOKMARK_TOGGLE -> b.apply {
                setOnClickListener {
                    val t = activeTab ?: return@setOnClickListener
                    toggleBookmark(t)
                }
            }
        }
    }

    /**
     * Share the current page through the system share sheet:
     * title + URL as plain text (ShareText, owner decision
     * 2026-11-23). The menu closes first, like every other
     * footer action. A page that is not shareable (start page,
     * blank) never reaches the sheet -- showMenu disables the
     * button then, and this guard is the second line. Sharing
     * is the user's explicit act, so a secret tab shares the
     * same way; the debug log records only that a share
     * happened, never the title or URL.
     */
    private fun shareCurrentPage(t: BrowserTab) {
        // The title is paired with the URL only when the tab's
        // attribution (TitleAttribution) names this URL: in
        // every other state the current title is a previous
        // page's or a placeholder, and the share is the URL
        // alone.
        val belongs = TitleAttribution.belongs(t.titleUrl, t.currentUrl)
        val payload = ShareText.payload(t.title, t.currentUrl, belongs)
        if (payload == null) {
            DebugLog.log("share", "share skipped (page not shareable)")
            return
        }
        hideMenu()
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, payload.text)
            // The chooser preview title is the same flattened,
            // capped title that goes into the text, never the
            // raw page-controlled string; absent when the
            // share carries the URL alone.
            if (payload.title != null) {
                putExtra(android.content.Intent.EXTRA_TITLE, payload.title)
            }
        }
        try {
            startActivity(android.content.Intent.createChooser(send, "共有"))
            DebugLog.log("share", "share sheet opened")
        } catch (e: Exception) {
            // ActivityNotFoundException when nothing can take
            // the share, and framework-wrapped failures (a
            // Binder overflow stays impossible under ShareText's
            // caps, but a chooser failure must never crash the
            // browser either way).
            DebugLog.log("share", "share failed: ${e.javaClass.simpleName}")
            android.widget.Toast.makeText(
                this, "共有を開始できませんでした", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Icon (MenuSpec) to drawable. Exhaustive: a new MenuSpec
     * icon without a drawable here is a compile error. */
    private fun menuIcon(icon: MenuSpec.Icon): Int = when (icon) {
        MenuSpec.Icon.HISTORY -> R.drawable.ic_history
        MenuSpec.Icon.DOWNLOAD -> R.drawable.ic_download
        MenuSpec.Icon.BOOKMARK -> R.drawable.ic_bookmark
        MenuSpec.Icon.SEARCH -> R.drawable.ic_search
        MenuSpec.Icon.LANGUAGES -> R.drawable.ic_languages
        MenuSpec.Icon.MONITOR -> R.drawable.ic_monitor
        MenuSpec.Icon.CODE -> R.drawable.ic_code
        MenuSpec.Icon.MONITOR_SMARTPHONE -> R.drawable.ic_monitor_smartphone
        MenuSpec.Icon.FILE_TEXT -> R.drawable.ic_file_text
        MenuSpec.Icon.FILE_UP -> R.drawable.ic_file_up
        MenuSpec.Icon.SHARE_2 -> R.drawable.ic_share_2
        MenuSpec.Icon.SETTINGS -> R.drawable.ic_settings
        MenuSpec.Icon.POWER -> R.drawable.ic_power
        MenuSpec.Icon.ARROW_LEFT -> R.drawable.ic_arrow_left
        MenuSpec.Icon.HOME -> R.drawable.ic_home
        MenuSpec.Icon.ARROW_RIGHT -> R.drawable.ic_arrow_right
        MenuSpec.Icon.BOOKMARK_PLUS -> R.drawable.ic_bookmark_plus
        MenuSpec.Icon.BOOKMARK_MINUS -> R.drawable.ic_bookmark_minus
    }

    private fun hideMenu() {
        menuScrim.visibility = View.GONE
        menuSheet.visibility = View.GONE
    }

    // ---------- History, saved tabs and clear data (SEN-154) ----------

    private fun loadHistory() {
        val file = java.io.File(filesDir, HISTORY_FILE_NAME)
        historyStore = runCatching {
            HistoryStore.fromJson(file.readText(Charsets.UTF_8))
        }.getOrElse { HistoryStore() }
    }

    private fun persistHistory() {
        // Only the snapshot (a reference copy) is taken here;
        // the writer serializes it on its own thread.
        val snapshot = historyStore.snapshot()
        historyWriter.submit { HistoryStore.toJson(snapshot) }
    }

    // ---------- Bookmarks ----------

    private fun loadBookmarks() {
        val file = java.io.File(filesDir, BOOKMARKS_FILE_NAME)
        bookmarkStore = runCatching {
            BookmarkStore.fromJson(file.readText(Charsets.UTF_8))
        }.getOrElse { BookmarkStore() }
        // Housekeeping off the UI thread: prune orphaned icon
        // files (deletes that never reached the disk side —
        // the prune runs even when nothing is flagged, or the
        // orphans left by a delete-all would never be swept)
        // and self-heal the store's flags: an entry claiming
        // an icon whose file is gone drops the flag, so a
        // later visit can capture it again. Existence is all
        // this pass checks: decoding is the rows' lazy load's
        // job, so startup cost stays flat however many icons
        // exist.
        val iconEntries = bookmarkStore.allBookmarks().filter { it.hasFavicon }
        thumbFileExecutor.execute {
            val present = ArrayList<String>()
            val missing = ArrayList<Long>()
            for (e in iconEntries) {
                if (FaviconStore.fileFor(faviconDir, e.url).isFile) {
                    present.add(e.url)
                } else {
                    missing.add(e.id)
                }
            }
            FaviconStore.prune(faviconDir, present)
            if (missing.isNotEmpty()) {
                runOnUiThread {
                    var changed = false
                    for (id in missing) {
                        val e = bookmarkStore.bookmark(id)
                        if (e != null && e.hasFavicon) {
                            bookmarkStore.setHasFavicon(id, false)
                            changed = true
                        }
                    }
                    if (changed) persistBookmarks()
                }
            }
        }
    }

    private fun persistBookmarks() {
        // Serialized here on the UI thread (the store is not
        // thread-safe); the writer only does the file I/O.
        val json = bookmarkStore.toJson()
        bookmarkWriter.submit { json }
    }

    /**
     * Persist [icon] as the favicon of the bookmark [entryId]
     * at [url], off the UI thread. The store's favicon flag is
     * confirmed only after the file lands (and only if the
     * entry still sits at that URL), so a failed write leaves
     * the flag down and a later visit retries. The cached
     * bitmap is the same scaled copy the file is written
     * from: the WebView's source bitmap may be recycled under
     * us, and cache and disk must agree on size.
     */
    private fun saveFavicon(entryId: Long, url: String, icon: android.graphics.Bitmap) {
        // One save per entry at a time: the title/icon
        // callbacks can fire repeatedly before the flag lands,
        // and each would otherwise redo the scale + write.
        if (!faviconSavesInFlight.add(entryId)) return
        val scaled = runCatching { FaviconStore.scaledCopy(icon) }.getOrNull()
        if (scaled == null) {
            faviconSavesInFlight.remove(entryId)
            return
        }
        thumbFileExecutor.execute {
            val failure = runCatching { FaviconStore.save(faviconDir, url, scaled) }
                .exceptionOrNull()
            if (failure != null) {
                // Kind only — never the URL (the log's rule).
                DebugLog.log(
                    "bookmark",
                    "favicon save failed: ${failure.javaClass.simpleName}",
                )
            }
            runOnUiThread {
                faviconSavesInFlight.remove(entryId)
                if (failure != null) return@runOnUiThread
                val cur = bookmarkStore.bookmark(entryId)
                if (cur != null && cur.url == url) {
                    // The cache is filled only for an entry
                    // that still lives at this URL: a save
                    // landing after a delete must not leave
                    // the icon behind in memory.
                    faviconCache.put(url, scaled)
                    faviconLoadFailed.remove(url)
                    if (!cur.hasFavicon) {
                        bookmarkStore.setHasFavicon(entryId, true)
                        persistBookmarks()
                    }
                }
                refreshBookmarkIcons()
            }
        }
    }

    /** Entry ids with a favicon save currently in flight. */
    private val faviconSavesInFlight = HashSet<Long>()

    /** URLs with a favicon lazy load currently in flight. */
    private val faviconLoadsInFlight = HashSet<String>()

    /** URLs whose stored favicon failed to decode in this
     * session: binds stop retrying them until the next launch
     * or a fresh capture. */
    private val faviconLoadFailed = HashSet<String>()

    /**
     * Load [entry]'s stored favicon in the background (a row
     * bound with a cache miss). On success the icon joins the
     * cache — only while an entry still lives at that URL
     * with its flag up (the saveFavicon rule) — and the list
     * rebinds. A decode failure is NOT treated as corruption
     * (the thumbnail rule: a decoder can fail transiently
     * under memory pressure): the file and the flag stay, the
     * URL is remembered as failed for this session so binds
     * stop asking, and a later capture or the next launch
     * retries.
     */
    private fun loadFaviconLazy(entry: BookmarkEntry) {
        val url = entry.url
        if (url in faviconLoadFailed) return
        if (!faviconLoadsInFlight.add(url)) return
        thumbFileExecutor.execute {
            val bmp = FaviconStore.load(faviconDir, url)
            runOnUiThread {
                faviconLoadsInFlight.remove(url)
                if (bmp != null) {
                    val cur = bookmarkStore.findByUrl(url)
                    if (cur != null && cur.hasFavicon) {
                        faviconCache.put(url, bmp)
                    }
                } else {
                    faviconLoadFailed.add(url)
                }
                refreshBookmarkIcons()
            }
        }
    }

    /** Rebind the bookmark list's visible rows after an icon
     * landed or failed: the list's contents did not change,
     * so this is a rebind only, never a full re-render (which
     * would re-sort and rebuild every row per icon). */
    private fun refreshBookmarkIcons() {
        if (::bookmarksPage.isInitialized &&
            bookmarksPage.visibility == View.VISIBLE &&
            ::bookmarkAdapter.isInitialized
        ) {
            bookmarkAdapter.notifyDataSetChanged()
        }
    }

    private fun deleteFavicon(url: String) {
        faviconCache.remove(url)
        faviconLoadFailed.remove(url)
        thumbFileExecutor.execute { FaviconStore.delete(faviconDir, url) }
    }

    /** Clear the bookmark search, if any (the watcher
     * re-renders). Used after an add, so the new row is not
     * hidden behind a stale query. */
    private fun clearBookmarkSearch() {
        if (bookmarkQuery.isNotEmpty()) bookmarkSearchInput.setText("")
    }

    /** Go up one folder level on the bookmark page (used
     * by the back action). */
    private fun goUpFolder() {
        bookmarkFolderId = bookmarkStore.folder(bookmarkFolderId)?.parentId
            ?: BookmarkStore.ROOT_ID
        renderBookmarks()
    }

    /**
     * The menu footer's bookmark toggle: add the current page
     * (at the top level; the bookmark page's edit moves it) or
     * remove its bookmark. Adding is the user's explicit act,
     * so a secret tab adds the same way (the share rule). The
     * title is taken only when TitleAttribution pairs it with
     * this URL, and the icon only when the tab attributes it
     * to this URL; a missing title or icon fills in on a later
     * visit (onTitleChanged). Neither is ever logged.
     */
    private fun toggleBookmark(t: BrowserTab) {
        val url = t.currentUrl
        if (!isWebLink(url) || BrowserTab.isHomeUrl(url)) return
        hideMenu()
        val existing = bookmarkStore.findByUrl(url)
        if (existing != null) {
            bookmarkStore.removeBookmark(existing.id)
            persistBookmarks()
            deleteFavicon(url)
            DebugLog.log("bookmark", "removed via footer toggle")
            android.widget.Toast.makeText(
                this, "ブックマークを削除しました", android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val title = if (TitleAttribution.belongs(t.titleUrl, url)) t.title else ""
        val icon = t.favicon?.takeIf { t.faviconUrl == url }
        val result = bookmarkStore.addBookmark(
            title, url, BookmarkStore.ROOT_ID, System.currentTimeMillis(), false,
        )
        if (result !is AddBookmarkResult.Added) {
            // Duplicate cannot happen (the existing entry was
            // handled above); Invalid means the URL stopped
            // being storable between the guard and the add.
            android.widget.Toast.makeText(
                this, "ブックマークに追加できませんでした", android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (icon != null) saveFavicon(result.entry.id, url, icon)
        persistBookmarks()
        DebugLog.log("bookmark", "added via footer toggle")
        android.widget.Toast.makeText(
            this, "ブックマークに追加しました", android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun showBookmarksPage() {
        expireActiveTabPopupProbes()
        // Every open starts at the top level with no query:
        // where the user left the page last time is not where
        // they expect to land from the menu.
        bookmarkFolderId = BookmarkStore.ROOT_ID
        if (bookmarkSearchInput.text.isNotEmpty()) {
            bookmarkSearchInput.setText("")
        } else {
            bookmarkQuery = ""
            renderBookmarks()
        }
        bookmarksPage.visibility = View.VISIBLE
    }

    private fun hideBookmarksPage() {
        if (bookmarksPage.findFocus() != null) moveFocusToSink()
        hideKeyboard()
        bookmarksPage.visibility = View.GONE
    }

    /** Back inside the bookmark page peels one layer at a
     * time: clear the search, then go up a folder, then close. */
    private fun backInBookmarksPage() {
        when {
            bookmarkQuery.isNotEmpty() -> bookmarkSearchInput.setText("")
            bookmarkFolderId != BookmarkStore.ROOT_ID -> goUpFolder()
            else -> hideBookmarksPage()
        }
    }

    /** One row of the bookmark list: a folder or a bookmark. */
    private sealed class BookmarkListItem {
        data class FolderRow(val folder: BookmarkFolder, val childCount: Int) : BookmarkListItem()
        data class EntryRow(val entry: BookmarkEntry) : BookmarkListItem()
    }

    /** The local state of an in-progress bookmark drag: what
     * is being dragged (a folder or a bookmark, by id). Kept
     * in the drag's local state only — the ClipData carries
     * no title, URL, or id (a drag can leave the app). */
    private class BookmarkDrag(val folder: Boolean, val id: Long)

    private class BookmarkFolderHolder(
        val name: TextView,
        val count: TextView,
        var folder: BookmarkFolder? = null,
    )

    private class BookmarkEntryHolder(
        val icon: ImageView,
        val title: TextView,
        val url: TextView,
        var entry: BookmarkEntry? = null,
    )

    private inner class BookmarkAdapter : android.widget.BaseAdapter() {
        override fun getCount(): Int = bookmarkItems.size

        override fun getItem(position: Int): BookmarkListItem = bookmarkItems[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int =
            if (bookmarkItems[position] is BookmarkListItem.FolderRow) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val item = bookmarkItems[position]) {
                is BookmarkListItem.FolderRow -> {
                    val v = convertView ?: buildBookmarkFolderRowView()
                    clearStaleDropHighlight(v)
                    val holder = v.tag as BookmarkFolderHolder
                    holder.folder = item.folder
                    holder.name.text = item.folder.name
                    holder.count.text = "${item.childCount} 件"
                    v
                }

                is BookmarkListItem.EntryRow -> {
                    val v = convertView ?: buildBookmarkEntryRowView()
                    val holder = v.tag as BookmarkEntryHolder
                    holder.entry = item.entry
                    // The row's entry is a snapshot from the
                    // last render; the favicon flag is read live
                    // from the store, because an icon save can
                    // land after the render and the completion
                    // path only rebinds (it does not rebuild the
                    // rows).
                    val hasFavicon = bookmarkStore.bookmark(item.entry.id)
                        ?.hasFavicon ?: item.entry.hasFavicon
                    val bmp = faviconCache.get(item.entry.url)
                    if (hasFavicon && bmp != null) {
                        holder.icon.setImageBitmap(bmp)
                        holder.icon.clearColorFilter()
                    } else {
                        holder.icon.setImageResource(R.drawable.ic_globe)
                        holder.icon.setColorFilter(Colors.ON_SURFACE_VARIANT)
                        // A flagged icon that is not cached yet
                        // loads in the background and rebinds
                        // when it lands.
                        if (hasFavicon) loadFaviconLazy(item.entry)
                    }
                    holder.title.text = item.entry.title.ifEmpty { item.entry.url }
                    holder.url.text = bookmarkUrlLine(item.entry)
                    v
                }
            }
        }
    }

    /** The entry row's second line: the URL, prefixed with
     * its folder path in search results (where the folder is
     * not otherwise visible). */
    private fun bookmarkUrlLine(entry: BookmarkEntry): String {
        if (bookmarkQuery.isEmpty() || entry.folderId == BookmarkStore.ROOT_ID) {
            return entry.url
        }
        val path = bookmarkStore.folderPath(entry.folderId)
        if (path.isEmpty()) return entry.url
        return path.joinToString(PATH_SEPARATOR) { it.name } + " · " + entry.url
    }

    private fun buildBookmarkFolderRowView(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10))
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_folder)
        icon.setColorFilter(Colors.ON_SURFACE_VARIANT)
        icon.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22))
        row.addView(icon)
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(Ui.dp(this, 14), 0, 0, 0)
        texts.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        )
        val name = Ui.textRow(this, "", 15f)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(name)
        val count = Ui.textRow(this, "", 13f, Colors.ON_SURFACE_VARIANT)
        texts.addView(count)
        row.addView(texts)
        val holder = BookmarkFolderHolder(name, count)
        row.tag = holder
        val menu = Ui.iconButton(this, R.drawable.ic_more_vert, "フォルダの操作", 36)
        menu.setOnClickListener {
            val folder = holder.folder ?: return@setOnClickListener
            showBookmarkFolderMenu(folder)
        }
        row.addView(menu)
        row.setOnClickListener {
            val folder = holder.folder ?: return@setOnClickListener
            bookmarkFolderId = folder.id
            renderBookmarks()
        }
        row.setOnLongClickListener {
            val folder = holder.folder ?: return@setOnLongClickListener false
            startBookmarkDrag(row, BookmarkDrag(folder = true, id = folder.id))
        }
        // A drop target: dropping a bookmark or folder on
        // this row moves it into this folder. The target is
        // read from the holder at event time (rows recycle).
        row.setOnDragListener { v, event ->
            onBookmarkDragEvent(v, event) {
                (v.tag as? BookmarkFolderHolder)?.folder?.id
            }
        }
        return row
    }

    private fun buildBookmarkEntryRowView(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10))
        val icon = ImageView(this)
        icon.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22))
        row.addView(icon)
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(Ui.dp(this, 14), 0, 0, 0)
        texts.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        )
        val title = Ui.textRow(this, "", 15f)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(title)
        val url = Ui.textRow(this, "", 13f, Colors.ON_SURFACE_VARIANT)
        url.maxLines = 1
        url.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(url)
        row.addView(texts)
        val holder = BookmarkEntryHolder(icon, title, url)
        row.tag = holder
        val menu = Ui.iconButton(this, R.drawable.ic_more_vert, "ブックマークの操作", 36)
        menu.setOnClickListener {
            val entry = holder.entry ?: return@setOnClickListener
            showBookmarkEntryMenu(entry)
        }
        row.addView(menu)
        row.setOnClickListener {
            val entry = holder.entry ?: return@setOnClickListener
            hideBookmarksPage()
            val t = activeTab ?: newTab()
            t.noteUserNavigation()
            t.loadSavedUrl(entry.url)
        }
        row.setOnLongClickListener {
            val entry = holder.entry ?: return@setOnLongClickListener false
            startBookmarkDrag(row, BookmarkDrag(folder = false, id = entry.id))
        }
        return row
    }

    // ---------- Bookmark drag and drop ----------

    /**
     * Begin dragging a bookmark row. Disabled while searching:
     * the results are a flat cross-folder list, where a drop
     * target's meaning would be unclear. The clip carries an
     * empty text item only; everything the drop needs is in
     * the local state.
     */
    private fun startBookmarkDrag(view: View, drag: BookmarkDrag): Boolean {
        if (bookmarkQuery.isNotEmpty()) return false
        val clip = android.content.ClipData.newPlainText("pageloupe-bookmark", "")
        return view.startDragAndDrop(
            clip, View.DragShadowBuilder(view), drag, 0,
        )
    }

    /** Whether dropping [drag] into [targetFolderId] would
     * change anything and is allowed. Hover feedback and the
     * drop itself share this rule. Never while searching: a
     * drag cannot start then, and if a search begins mid-drag
     * (an external keyboard), every target goes inert. */
    private fun canDropBookmark(drag: BookmarkDrag, targetFolderId: Long): Boolean {
        if (bookmarkQuery.isNotEmpty()) return false
        return if (drag.folder) {
            val current = bookmarkStore.folder(drag.id) ?: return false
            current.parentId != targetFolderId &&
                bookmarkStore.canMoveFolder(drag.id, targetFolderId)
        } else {
            val entry = bookmarkStore.bookmark(drag.id) ?: return false
            entry.folderId != targetFolderId &&
                bookmarkStore.canMoveBookmark(drag.id, targetFolderId)
        }
    }

    /** Carry out a drop: the store applies its own guards
     * again (the state may have changed mid-drag). */
    private fun performBookmarkDrop(drag: BookmarkDrag, targetFolderId: Long): Boolean {
        val moved = if (drag.folder) {
            bookmarkStore.moveFolder(drag.id, targetFolderId)
        } else {
            bookmarkStore.moveBookmark(drag.id, targetFolderId)
        }
        if (moved) {
            persistBookmarks()
            renderBookmarks()
        }
        return moved
    }

    /** Backgrounds saved when a drop highlight goes on, per
     * view, so clearing restores what the view had (not a
     * hard-coded transparent). A row scrapped mid-drag keeps
     * its entry until it is rebound, and the adapter's bind
     * restores it then (see BookmarkAdapter.getView). */
    private val dropHighlightSaved =
        java.util.WeakHashMap<View, android.graphics.drawable.Drawable?>()

    private fun setDropHighlight(view: View, on: Boolean) {
        if (on) {
            if (!dropHighlightSaved.containsKey(view)) {
                dropHighlightSaved[view] = view.background
            }
            view.setBackgroundColor(Colors.SURFACE_CONTAINER)
        } else if (dropHighlightSaved.containsKey(view)) {
            view.background = dropHighlightSaved.remove(view)
        }
    }

    /** Undo a drop highlight left on a row that was scrapped
     * mid-drag and is now being rebound to another item. */
    private fun clearStaleDropHighlight(view: View) {
        if (dropHighlightSaved.containsKey(view)) {
            view.background = dropHighlightSaved.remove(view)
        }
    }

    /**
     * The drag-event handler shared by every drop target on
     * the bookmarks page. [target] resolves the folder the
     * view stands for at event time (rows recycle; the path
     * row's target depends on the current folder), or null
     * when the view is not a target right now.
     */
    private fun onBookmarkDragEvent(
        view: View,
        event: android.view.DragEvent,
        target: () -> Long?,
    ): Boolean {
        val drag = event.localState as? BookmarkDrag
        return when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> drag != null
            android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                val t = target()
                if (drag != null && t != null && canDropBookmark(drag, t)) {
                    setDropHighlight(view, true)
                }
                true
            }
            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                setDropHighlight(view, false)
                true
            }
            android.view.DragEvent.ACTION_DROP -> {
                setDropHighlight(view, false)
                val t = target() ?: return false
                // The drop goes through the same rule as the
                // hover (a same-folder drop is a no-op that
                // must not even persist); the store re-guards
                // inside performBookmarkDrop as a backstop.
                drag != null && canDropBookmark(drag, t) &&
                    performBookmarkDrop(drag, t)
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                setDropHighlight(view, false)
                true
            }
            else -> true
        }
    }

    /** Scroll the breadcrumb so the current folder (the end
     * of the chain) is in view. scrollTo only — fullScroll
     * would move focus on the way (SEN-253). Called from the
     * layout listeners of both the segments and the scroll
     * view, so content growth and row resizing both follow
     * (SEN-258). */
    private fun scrollBookmarkPathToEnd() {
        val target = bookmarkPathSegments.width - bookmarkPathScroll.width
        if (target > 0) bookmarkPathScroll.scrollTo(target, 0)
    }

    /** The padding every breadcrumb item carries (segments
     * and the search label alike), so the gaps around the
     * ">" separators are uniform, the last segment
     * included (SEN-269). */
    private fun padBookmarkPathItem(view: TextView) {
        view.setPadding(
            Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10),
        )
    }

    /** Rebuild the breadcrumb segments for the current
     * folder: "トップ > A > B". Ancestor segments are
     * tappable and jump straight to that folder; the last
     * segment is the current folder and stays plain. While
     * searching, the row shows a plain "検索結果" label
     * instead (results are flat, there is no location). */
    private fun renderBookmarkPath(searching: Boolean) {
        // The desired content as data: the searching flag plus
        // the (folder id, label) segments from the top level
        // down to and including the current folder. The views
        // are rebuilt only when this changes — renderBookmarks
        // runs on every search keystroke. The labels are part
        // of the state, so a folder rename still rebuilds.
        val segments = ArrayList<Pair<Long, String>>()
        if (!searching) {
            segments.add(BookmarkStore.ROOT_ID to ROOT_FOLDER_LABEL)
            for (f in bookmarkStore.folderPath(bookmarkFolderId)) {
                segments.add(f.id to f.name)
            }
        }
        val state = searching to segments
        if (state == bookmarkPathRendered) return
        bookmarkPathRendered = state
        bookmarkPathSegments.removeAllViews()
        if (searching) {
            val label = Ui.textRow(this, "検索結果", 14f, Colors.ON_SURFACE_VARIANT)
            // The same padding as a segment, so the label
            // sits where a segment would (SEN-267).
            padBookmarkPathItem(label)
            bookmarkPathSegments.addView(label)
            return
        }
        segments.forEachIndexed { index, (id, label) ->
            if (index > 0) {
                val sep = Ui.textRow(
                    this, PATH_SEPARATOR.trim(), 14f, Colors.ON_SURFACE_VARIANT,
                )
                sep.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0)
                // Pure decoration: keep screen readers from
                // announcing every separator (SEN-261).
                sep.importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
                bookmarkPathSegments.addView(sep)
            }
            val isCurrent = index == segments.lastIndex
            val seg = Ui.textRow(
                this, label, 14f,
                if (isCurrent) Colors.ON_SURFACE else Colors.ON_SURFACE_VARIANT,
            )
            seg.maxLines = 1
            // The same padding on every segment, the current
            // one included: without it the gap before the
            // last segment is narrower than the others.
            padBookmarkPathItem(seg)
            if (!isCurrent) {
                seg.setOnClickListener {
                    bookmarkFolderId = id
                    renderBookmarks()
                }
            }
            bookmarkPathSegments.addView(seg)
        }
    }

    private fun renderBookmarks() {
        val sort = Settings.bookmarkSort(this)
        val searching = bookmarkQuery.isNotEmpty()
        val atTop = bookmarkFolderId == BookmarkStore.ROOT_ID
        // The whole path row is hidden at the top level (a
        // lone "トップ" breadcrumb says nothing). While
        // searching the row stays, showing the "検索結果"
        // label instead. Going up is done with the back
        // action or by tapping an ancestor segment.
        bookmarkPathRow.visibility =
            if (!searching && atTop) View.GONE else View.VISIBLE
        // While the row is hidden at the top level the
        // segments are not rebuilt (SEN-265). That is safe
        // because the render cache always describes the
        // views currently in place: skipping touches
        // neither, so when the row returns, a matching
        // state reuses views that are already correct and
        // a different state rebuilds (SEN-268).
        if (searching || !atTop) renderBookmarkPath(searching)
        bookmarkDeleteAllButton.isEnabled = !bookmarkStore.isEmpty()
        bookmarkDeleteAllButton.setColorFilter(
            if (bookmarkStore.isEmpty()) Colors.OUTLINE else Colors.ICON,
        )
        val items = ArrayList<BookmarkListItem>()
        if (searching) {
            for (e in bookmarkStore.search(bookmarkQuery, sort)) {
                items.add(BookmarkListItem.EntryRow(e))
            }
        } else {
            val counts = bookmarkStore.childCounts()
            for (f in bookmarkStore.foldersIn(bookmarkFolderId, sort)) {
                items.add(BookmarkListItem.FolderRow(f, counts[f.id] ?: 0))
            }
            for (e in bookmarkStore.bookmarksIn(bookmarkFolderId, sort)) {
                items.add(BookmarkListItem.EntryRow(e))
            }
        }
        bookmarkItems = items
        if (items.isEmpty()) {
            bookmarkEmptyText.text = when {
                searching -> "見つかりませんでした"
                bookmarkStore.isEmpty() -> "ブックマークはありません"
                else -> "このフォルダは空です"
            }
            bookmarkEmptyText.visibility = View.VISIBLE
            bookmarkListView.visibility = View.GONE
        } else {
            bookmarkEmptyText.visibility = View.GONE
            bookmarkListView.visibility = View.VISIBLE
        }
        bookmarkAdapter.notifyDataSetChanged()
    }

    private fun buildBookmarksPage() {
        val pageRoot = FrameLayout(this)
        pageRoot.setBackgroundColor(Colors.SURFACE)
        pageRoot.visibility = View.GONE
        bookmarksPage = pageRoot
        // Consume touches in any area the content does not
        // cover, so they cannot fall through to the browser
        // underneath (the history page's rule).
        pageRoot.isClickable = true

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        pageRoot.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Header: close, title, then the page actions (sort,
        // add, delete-all) — management lives on this page,
        // not on the clear-data page (owner decision,
        // 2026-11-23).
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6))
        val closeBtn = Ui.iconButton(this, R.drawable.ic_close, "ブックマークを閉じる")
        closeBtn.setOnClickListener { hideBookmarksPage() }
        header.addView(closeBtn)
        val title = Ui.textRow(this, "ブックマーク", 18f)
        title.setTypeface(null, Typeface.BOLD)
        title.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        )
        header.addView(title)
        val sortBtn = Ui.iconButton(this, R.drawable.ic_sort, "並び順")
        sortBtn.setOnClickListener { showBookmarkSortDialog() }
        header.addView(sortBtn)
        val addBtn = Ui.iconButton(this, R.drawable.ic_plus, "追加")
        addBtn.setOnClickListener { showBookmarkAddDialog() }
        header.addView(addBtn)
        bookmarkDeleteAllButton = Ui.iconButton(this, R.drawable.ic_delete, "すべて削除")
        bookmarkDeleteAllButton.setOnClickListener { showDeleteAllBookmarksConfirm() }
        header.addView(bookmarkDeleteAllButton)
        column.addView(header)

        // Search pill, same shape as the history page's.
        val searchArea = LinearLayout(this)
        searchArea.orientation = LinearLayout.VERTICAL
        searchArea.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8))
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = Ui.roundBg(Colors.SURFACE_CONTAINER, 22f, this)
        pill.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        pill.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44),
        )
        val searchIcon = ImageView(this)
        searchIcon.setImageResource(R.drawable.ic_search)
        searchIcon.setColorFilter(Colors.ON_SURFACE_VARIANT)
        searchIcon.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20))
        pill.addView(searchIcon)
        bookmarkSearchInput = EditText(this)
        bookmarkSearchInput.hint = "ブックマークを検索"
        bookmarkSearchInput.textSize = 14f
        bookmarkSearchInput.maxLines = 1
        bookmarkSearchInput.background = null
        // Same privacy treatment as the history search: no
        // suggestions, no personalized learning.
        Ui.applyPrivateInput(bookmarkSearchInput, InputType.TYPE_CLASS_TEXT)
        bookmarkSearchInput.setPadding(Ui.dp(this, 10), 0, 0, 0)
        bookmarkSearchInput.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
        )
        bookmarkSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                bookmarkQuery = s?.toString()?.trim() ?: ""
                renderBookmarks()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        pill.addView(bookmarkSearchInput)
        searchArea.addView(pill)
        column.addView(searchArea)

        // Folder path row, below the search bar: a breadcrumb
        // of the current location ("トップ > A > B"). Each
        // ancestor segment is tappable and jumps straight to
        // that folder (see renderBookmarkPath). The row
        // itself is hidden at the top level (see
        // renderBookmarks). The row's left padding aligns
        // the first segment's text with the left edge of
        // the folder / favicon icons in the list rows
        // below: those measure 42–44px (about 16dp) from
        // the left edge (A301SO), so 7dp here plus a
        // segment's own 6dp puts the text box at 13dp and
        // the glyph itself lands on about 16dp once its
        // font bearing is counted (owner instruction,
        // v0.29.0; the chevron alignment of SEN-271 was
        // superseded by this). The bearing (about 3dp) is
        // the value measured on the v0.28.0 build — glyph
        // at 69px for a 60px text box, with "トップ" as
        // the first segment (SEN-274). Verified on device
        // (SEN-272): the first glyph measured 42px, the
        // same as the favicon's left edge and the folder
        // icon's frame (its stroke sits at 44px). The
        // "検索結果" label shares this row and its first
        // glyph's bearing differs, so it can sit a few px
        // off the icons' edge (SEN-273, accepted).
        val pathRow = LinearLayout(this)
        bookmarkPathRow = pathRow
        pathRow.orientation = LinearLayout.HORIZONTAL
        pathRow.gravity = Gravity.CENTER_VERTICAL
        pathRow.setPadding(Ui.dp(this, 7), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 2))
        // A deep chain or long names can outgrow the row, so
        // the segments scroll horizontally; the render keeps
        // the current folder (the end) in view.
        bookmarkPathSegments = LinearLayout(this)
        bookmarkPathSegments.orientation = LinearLayout.HORIZONTAL
        bookmarkPathSegments.gravity = Gravity.CENTER_VERTICAL
        bookmarkPathScroll = android.widget.HorizontalScrollView(this)
        bookmarkPathScroll.isHorizontalScrollBarEnabled = false
        bookmarkPathScroll.isFocusable = false
        bookmarkPathScroll.addView(bookmarkPathSegments)
        // Keep the current folder (the end of the chain) in
        // view when the chain outgrows the row: when the
        // segments' layout grows past the visible width,
        // follow to the end (see scrollBookmarkPathToEnd).
        // One listener on both views (SEN-262): the segments
        // growing, and the row itself resizing (rotation,
        // split screen — the segments do not resize then),
        // both follow to the end (SEN-258).
        val followPathEnd = View.OnLayoutChangeListener {
                _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                scrollBookmarkPathToEnd()
            }
        }
        bookmarkPathSegments.addOnLayoutChangeListener(followPathEnd)
        bookmarkPathScroll.addOnLayoutChangeListener(followPathEnd)
        pathRow.addView(
            bookmarkPathScroll,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        // The path row is the drop target for "one level up":
        // dropping a row here moves it into the current
        // folder's parent. At the top level there is no
        // parent, so it is not a target.
        pathRow.setOnDragListener { v, event ->
            onBookmarkDragEvent(v, event) {
                if (bookmarkFolderId == BookmarkStore.ROOT_ID) {
                    null
                } else {
                    bookmarkStore.folder(bookmarkFolderId)?.parentId
                }
            }
        }
        column.addView(pathRow)

        // A ListView with recycled rows, like the history page.
        val listFrame = FrameLayout(this)
        listFrame.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        )
        bookmarkListView = android.widget.ListView(this)
        bookmarkListView.divider = null
        bookmarkAdapter = BookmarkAdapter()
        bookmarkListView.adapter = bookmarkAdapter
        listFrame.addView(
            bookmarkListView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        bookmarkEmptyText = Ui.textRow(this, "", 14f, Colors.ON_SURFACE_VARIANT)
        bookmarkEmptyText.gravity = Gravity.CENTER_HORIZONTAL
        bookmarkEmptyText.setPadding(0, Ui.dp(this, 48), 0, 0)
        bookmarkEmptyText.visibility = View.GONE
        listFrame.addView(
            bookmarkEmptyText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        column.addView(listFrame)

        root.addView(
            bookmarksPage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    // ---------- Bookmark dialogs ----------

    private fun showBookmarkAddDialog() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("ブックマークを追加", "フォルダを作成")) { _, which ->
                if (which == 0) showBookmarkEditDialog(null) else showNewFolderDialog()
            }
            .show()
    }

    private fun showBookmarkSortDialog() {
        val sorts = BookmarkSort.entries
        val labels = arrayOf("タイトル順", "追加が新しい順", "追加が古い順")
        val current = Settings.bookmarkSort(this)
        AlertDialog.Builder(this)
            .setTitle("並び順")
            .setSingleChoiceItems(labels, sorts.indexOf(current)) { dialog, which ->
                Settings.setBookmarkSort(this, sorts[which])
                renderBookmarks()
                dialog.dismiss()
            }
            .show()
    }

    private fun showDeleteAllBookmarksConfirm() {
        if (bookmarkStore.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("すべて削除")
            .setMessage("すべてのブックマークとフォルダを削除します。この操作は元に戻せません。")
            .setPositiveButton("削除") { _, _ ->
                bookmarkStore.clear()
                persistBookmarks()
                faviconCache.evictAll()
                faviconLoadFailed.clear()
                thumbFileExecutor.execute { FaviconStore.clearAll(faviconDir) }
                bookmarkFolderId = BookmarkStore.ROOT_ID
                renderBookmarks()
                DebugLog.log("bookmark", "all deleted from bookmark page")
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showBookmarkEntryMenu(entry: BookmarkEntry) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("編集", "削除")) { _, which ->
                if (which == 0) {
                    showBookmarkEditDialog(entry)
                } else {
                    val removed = bookmarkStore.removeBookmark(entry.id)
                    if (removed != null) {
                        persistBookmarks()
                        deleteFavicon(removed.url)
                        renderBookmarks()
                    }
                }
            }
            .show()
    }

    private fun showBookmarkFolderMenu(folder: BookmarkFolder) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("名前を変更", "フォルダを移動", "フォルダを削除")) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folder)
                    1 -> showFolderPicker(
                        "移動先のフォルダ",
                        excludeSubtreeOf = folder.id,
                        current = folder.parentId,
                    ) { parentId ->
                        if (bookmarkStore.moveFolder(folder.id, parentId)) {
                            persistBookmarks()
                            renderBookmarks()
                        }
                    }
                    2 -> showDeleteFolderConfirm(folder)
                }
            }
            .show()
    }

    private fun showDeleteFolderConfirm(folder: BookmarkFolder) {
        AlertDialog.Builder(this)
            .setTitle("フォルダを削除")
            .setMessage(
                "「${folder.name}」を削除します。" +
                    "中身のブックマークとフォルダは、親フォルダへ移動します。",
            )
            .setPositiveButton("削除") { _, _ ->
                if (bookmarkStore.removeFolder(folder.id)) {
                    persistBookmarks()
                    renderBookmarks()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * A single-choice list of every folder (the top level
     * first), indented by depth. [excludeSubtreeOf] hides a
     * folder and its descendants (a folder cannot move into
     * its own subtree; the store refuses it too).
     */
    private fun showFolderPicker(
        title: String,
        excludeSubtreeOf: Long?,
        current: Long,
        onPick: (Long) -> Unit,
    ) {
        val flat = ArrayList<Pair<Long, Int>>()
        flat.add(BookmarkStore.ROOT_ID to -1)
        for ((folder, depth) in bookmarkStore.flattenFolders(excludeSubtreeOf)) {
            flat.add(folder.id to depth)
        }
        val labels = flat.map { (id, depth) ->
            if (id == BookmarkStore.ROOT_ID) {
                "トップレベル"
            } else {
                "　".repeat(depth + 1) + (bookmarkStore.folder(id)?.name ?: "")
            }
        }.toTypedArray()
        val checked = flat.indexOfFirst { it.first == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onPick(flat[which].first)
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** A folder's display path, for dialog labels. */
    private fun folderPathLabel(folderId: Long): String {
        if (folderId == BookmarkStore.ROOT_ID) return "トップレベル"
        val path = bookmarkStore.folderPath(folderId)
        if (path.isEmpty()) return "トップレベル"
        return ROOT_FOLDER_LABEL + PATH_SEPARATOR +
            path.joinToString(PATH_SEPARATOR) { it.name }
    }

    /**
     * The add/edit form for one bookmark: title, URL, and a
     * folder picker row. Validation failures keep the dialog
     * open (the positive button is intercepted) so the typed
     * text is never lost to a typo.
     */
    private fun showBookmarkEditDialog(entry: BookmarkEntry?) {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 24)
        container.setPadding(pad, Ui.dp(this, 8), pad, 0)
        val titleInput = EditText(this)
        titleInput.hint = "タイトル"
        titleInput.setText(entry?.title ?: "")
        titleInput.maxLines = 1
        // Bookmark titles can name private sites and projects:
        // the same IME treatment as the search fields (SEC-5's
        // rule) — no suggestions, no personalized learning.
        Ui.applyPrivateInput(titleInput, InputType.TYPE_CLASS_TEXT)
        container.addView(titleInput)
        val urlInput = EditText(this)
        urlInput.hint = "URL"
        urlInput.setText(entry?.url ?: "")
        urlInput.maxLines = 1
        Ui.applyPrivateInput(
            urlInput,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        container.addView(urlInput)
        var chosenFolder = entry?.folderId ?: bookmarkFolderId
        val folderValue = Ui.textRow(this, folderPathLabel(chosenFolder), 15f)
        folderValue.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14))
        folderValue.setOnClickListener {
            showFolderPicker("フォルダを選択", excludeSubtreeOf = null, current = chosenFolder) {
                chosenFolder = it
                folderValue.text = folderPathLabel(it)
            }
        }
        container.addView(Ui.textRow(this, "フォルダ", 13f, Colors.ON_SURFACE_VARIANT))
        container.addView(folderValue)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (entry == null) "ブックマークを追加" else "ブックマークを編集")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // A bare host gains https:// (normalizeBookmarkUrl);
                // every other rule is the store's, reported back
                // as a result — the dialog only maps outcomes to
                // messages and never re-checks them itself.
                val url = normalizeBookmarkUrl(urlInput.text.toString())
                val title = titleInput.text.toString().trim()
                if (entry == null) {
                    when (val result = bookmarkStore.addBookmark(
                        title, url, chosenFolder, System.currentTimeMillis(), false,
                    )) {
                        is AddBookmarkResult.Added -> {
                            persistBookmarks()
                            clearBookmarkSearch()
                            renderBookmarks()
                            dialog.dismiss()
                        }
                        is AddBookmarkResult.Duplicate -> {
                            android.widget.Toast.makeText(
                                this, "その URL は既にブックマークされています",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        AddBookmarkResult.InvalidUrl -> {
                            android.widget.Toast.makeText(
                                this, "URL が正しくありません", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        AddBookmarkResult.LimitReached -> {
                            android.widget.Toast.makeText(
                                this, "ブックマークの上限（5,000 件）に達しています",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        AddBookmarkResult.NoSuchFolder -> {
                            android.widget.Toast.makeText(
                                this, "保存できませんでした", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                } else {
                    when (val result = bookmarkStore.updateBookmark(
                        entry.id, title, url, chosenFolder,
                    )) {
                        is UpdateBookmarkResult.Updated -> {
                            if (result.previousUrl != result.entry.url) {
                                // The stored icon belongs to the
                                // old URL (the store has already
                                // dropped the flag); the new URL's
                                // icon fills in on a visit.
                                deleteFavicon(result.previousUrl)
                            }
                            persistBookmarks()
                            renderBookmarks()
                            dialog.dismiss()
                        }
                        UpdateBookmarkResult.DuplicateUrl -> {
                            android.widget.Toast.makeText(
                                this, "その URL は既にブックマークされています",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        UpdateBookmarkResult.InvalidUrl -> {
                            android.widget.Toast.makeText(
                                this, "URL が正しくありません", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        UpdateBookmarkResult.NotFound, UpdateBookmarkResult.NoSuchFolder -> {
                            android.widget.Toast.makeText(
                                this, "保存できませんでした", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showNewFolderDialog() {
        val input = EditText(this)
        input.hint = "フォルダ名"
        input.maxLines = 1
        Ui.applyPrivateInput(input, InputType.TYPE_CLASS_TEXT)
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 24)
        container.setPadding(pad, Ui.dp(this, 8), pad, 0)
        container.addView(input)
        // The parent is choosable (owner request, 2026-11-23),
        // defaulting to the folder the page is showing; the
        // row mirrors the bookmark form's folder picker.
        var chosenParent = bookmarkFolderId
        val parentValue = Ui.textRow(this, folderPathLabel(chosenParent), 15f)
        parentValue.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14))
        parentValue.setOnClickListener {
            showFolderPicker("作成先のフォルダ", excludeSubtreeOf = null, current = chosenParent) {
                chosenParent = it
                parentValue.text = folderPathLabel(it)
            }
        }
        container.addView(Ui.textRow(this, "作成先", 13f, Colors.ON_SURFACE_VARIANT))
        container.addView(parentValue)
        val dialog = AlertDialog.Builder(this)
            .setTitle("フォルダを作成")
            .setView(container)
            .setPositiveButton("作成", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    android.widget.Toast.makeText(
                        this, "フォルダ名を入力してください", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                if (bookmarkStore.addFolder(name, chosenParent, System.currentTimeMillis()) == null) {
                    android.widget.Toast.makeText(
                        this, "作成できませんでした", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                persistBookmarks()
                // Show the new folder: navigate to the
                // parent the user picked for it. The folder
                // is set before clearing the search so the
                // watcher's render (or the explicit one, when
                // there was no search) already uses it — one
                // render, with the new location.
                bookmarkFolderId = chosenParent
                if (bookmarkQuery.isNotEmpty()) {
                    clearBookmarkSearch()
                } else {
                    renderBookmarks()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showRenameFolderDialog(folder: BookmarkFolder) {
        val input = EditText(this)
        input.setText(folder.name)
        input.maxLines = 1
        Ui.applyPrivateInput(input, InputType.TYPE_CLASS_TEXT)
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 24)
        container.setPadding(pad, Ui.dp(this, 8), pad, 0)
        container.addView(input)
        val dialog = AlertDialog.Builder(this)
            .setTitle("名前を変更")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!bookmarkStore.renameFolder(folder.id, input.text.toString())) {
                    android.widget.Toast.makeText(
                        this, "フォルダ名を入力してください", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                persistBookmarks()
                renderBookmarks()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Run [block] with tab-session saves suppressed (a restore or rebuild loop is recreating tabs; only the completed state may reach the file). The flag is always restored. */
    private inline fun <T> withRestoringTabs(block: () -> T): T {
        restoringTabs = true
        try {
            return block()
        } finally {
            restoringTabs = false
        }
    }

    private fun loadSavedSession(): SavedSession? {
        val file = java.io.File(filesDir, TAB_SESSION_FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            TabSessionCodec.decode(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * Persist the open-tab session (SEN-154). Every write goes
     * through [sessionWriter], the file's single writer, in
     * submission order — a queued older snapshot can never land
     * after a newer one and resurrect a closed tab. Closing the
     * last tab additionally flushes the writer before the app
     * finishes (see closeTab). With no tabs left the file is
     * deleted instead: an empty session and no session restore
     * identically. Saves are suppressed while a restore loop is
     * recreating tabs ([restoringTabs]).
     */
    private fun saveTabSession() {
        if (restoringTabs) return
        if (tabs.isEmpty()) {
            sessionWriter.submit { null }
            pruneThumbnails(emptyList())
            return
        }
        val snapshot = tabs.map { t ->
            SavedTab(t.deferredUrl ?: t.currentUrl, t.secret, t.desktopMode)
        }
        val active = tabs.indexOf(activeTab).coerceAtLeast(0)
        sessionWriter.submit { TabSessionCodec.encode(snapshot, active) }
        pruneThumbnails(snapshot.filter { !it.secret }.map { it.url })
    }

    /**
     * Keep the persisted thumbnails in step with the open
     * tabs: a thumbnail's file lives exactly as long as a
     * tab with its URL is open (SEN-157). Runs on the
     * thumbnail executor, ordered against the saves and
     * loads there. Repeated calls with an unchanged keep
     * set are coalesced away — saveTabSession calls this on
     * every navigation, and the directory listing plus the
     * keep set's hashes are only worth redoing when the set
     * actually changed.
     */
    private var lastThumbPruneKeep: Set<String>? = null

    private fun pruneThumbnails(keepUrls: List<String>) {
        val keep = keepUrls.toSet()
        if (keep == lastThumbPruneKeep) return
        lastThumbPruneKeep = keep
        thumbFileExecutor.execute {
            runCatching { ThumbnailStore.prune(thumbDir, keepUrls) }
                .onFailure { DebugLog.log("thumb", "prune failed: $it") }
        }
    }

    private fun buildHistoryPage() {
        val pageRoot = FrameLayout(this)
        pageRoot.setBackgroundColor(Colors.SURFACE)
        pageRoot.visibility = View.GONE
        historyPage = pageRoot
        // Consume touches in any area the content does not
        // cover, so they cannot fall through to the browser
        // underneath (the settings page's rule).
        pageRoot.isClickable = true

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        pageRoot.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Header: close button on the left, then the title — the
        // switcher's header shape.
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6))
        val closeBtn = Ui.iconButton(this, R.drawable.ic_close, "履歴を閉じる")
        closeBtn.setOnClickListener { hideHistoryPage() }
        header.addView(closeBtn)
        val title = Ui.textRow(this, "閲覧履歴", 18f)
        title.setTypeface(null, Typeface.BOLD)
        header.addView(title)
        column.addView(header)

        // The clear-data link sits at the top, as on Chrome's
        // history page.
        val clearRow = LinearLayout(this)
        clearRow.orientation = LinearLayout.HORIZONTAL
        clearRow.gravity = Gravity.CENTER_VERTICAL
        clearRow.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12))
        val clearIcon = ImageView(this)
        clearIcon.setImageResource(R.drawable.ic_delete)
        clearIcon.setColorFilter(Colors.PRIMARY)
        clearIcon.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20))
        clearRow.addView(clearIcon)
        val clearText = Ui.textRow(this, "閲覧履歴データを削除...", 14f, Colors.PRIMARY)
        clearText.setPadding(Ui.dp(this, 12), 0, 0, 0)
        clearRow.addView(clearText)
        clearRow.setOnClickListener { showClearDataPage() }
        column.addView(clearRow)
        column.addView(hairline())

        // Search pill, same shape as the tab search.
        val searchArea = LinearLayout(this)
        searchArea.orientation = LinearLayout.VERTICAL
        searchArea.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8))
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = Ui.roundBg(Colors.SURFACE_CONTAINER, 22f, this)
        pill.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        pill.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44),
        )
        val searchIcon = ImageView(this)
        searchIcon.setImageResource(R.drawable.ic_search)
        searchIcon.setColorFilter(Colors.ON_SURFACE_VARIANT)
        searchIcon.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20))
        pill.addView(searchIcon)
        historySearchInput = EditText(this)
        historySearchInput.hint = "履歴を検索"
        historySearchInput.textSize = 14f
        historySearchInput.maxLines = 1
        historySearchInput.background = null
        // History queries can name the sites the user visits:
        // keep them out of the keyboard's suggestions and
        // personalized learning, like the address bar (SEC-5 in
        // the SEN-154 review).
        Ui.applyPrivateInput(historySearchInput, InputType.TYPE_CLASS_TEXT)
        historySearchInput.setPadding(Ui.dp(this, 10), 0, 0, 0)
        historySearchInput.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
        )
        historySearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                historyQuery = s?.toString()?.trim() ?: ""
                renderHistory()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        pill.addView(historySearchInput)
        searchArea.addView(pill)
        column.addView(searchArea)

        // A ListView with recycled rows: the history can hold
        // 5000 entries, and building a View per entry (the
        // switcher's pattern, fine for dozens of tabs) would
        // hitch the UI thread on every render (SEN-154 review).
        val listFrame = FrameLayout(this)
        listFrame.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        )
        historyListView = android.widget.ListView(this)
        historyListView.divider = null
        historyAdapter = HistoryAdapter()
        historyListView.adapter = historyAdapter
        listFrame.addView(
            historyListView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        historyEmptyText = Ui.textRow(this, "", 14f, Colors.ON_SURFACE_VARIANT)
        historyEmptyText.gravity = Gravity.CENTER_HORIZONTAL
        historyEmptyText.setPadding(0, Ui.dp(this, 48), 0, 0)
        historyEmptyText.visibility = View.GONE
        listFrame.addView(
            historyEmptyText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        column.addView(listFrame)

        root.addView(
            historyPage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun hairline(): View {
        val v = View(this)
        v.setBackgroundColor(Colors.OUTLINE_VARIANT)
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1,
        )
        return v
    }

    private fun showHistoryPage() {
        expireActiveTabPopupProbes()
        renderHistory()
        historyPage.visibility = View.VISIBLE
    }

    private fun hideHistoryPage() {
        // If the focus is still inside the page (the search
        // field, typically), move it to the focus sink first:
        // a page going GONE with the focus inside leaves the
        // reassignment to the framework (SEN-159).
        if (historyPage.findFocus() != null) moveFocusToSink()
        hideKeyboard()
        historyPage.visibility = View.GONE
    }

    /** One row of the history list: a day header or an entry. */
    private sealed class HistoryListItem {
        data class DayHeader(val label: String) : HistoryListItem()
        data class EntryRow(val entry: HistoryEntry) : HistoryListItem()
    }

    private class HistoryRowHolder(
        val title: TextView,
        val url: TextView,
        var entry: HistoryEntry? = null,
    )

    private inner class HistoryAdapter : android.widget.BaseAdapter() {
        override fun getCount(): Int = historyItems.size

        override fun getItem(position: Int): HistoryListItem = historyItems[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int =
            if (historyItems[position] is HistoryListItem.DayHeader) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val item = historyItems[position]) {
                is HistoryListItem.DayHeader -> {
                    val v = convertView as? TextView ?: run {
                        val t = Ui.textRow(this@MainActivity, "", 14f)
                        t.setTypeface(null, Typeface.BOLD)
                        t.setPadding(
                            Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16),
                            Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 4),
                        )
                        t
                    }
                    v.text = item.label
                    v
                }

                is HistoryListItem.EntryRow -> {
                    val v = convertView ?: buildHistoryRowView()
                    val holder = v.tag as HistoryRowHolder
                    holder.entry = item.entry
                    holder.title.text = item.entry.title.ifEmpty { item.entry.url }
                    holder.url.text = item.entry.url
                    v
                }
            }
        }
    }

    /**
     * Build one (recyclable) history entry row. The click
     * listeners are installed once and read the bound entry
     * from the holder, so recycling needs no re-wiring.
     */
    private fun buildHistoryRowView(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10))
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_globe)
        icon.setColorFilter(Colors.ON_SURFACE_VARIANT)
        icon.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22))
        row.addView(icon)
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(Ui.dp(this, 14), 0, 0, 0)
        texts.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        )
        val title = Ui.textRow(this, "", 15f)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(title)
        val url = Ui.textRow(this, "", 13f, Colors.ON_SURFACE_VARIANT)
        url.maxLines = 1
        url.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(url)
        row.addView(texts)
        val holder = HistoryRowHolder(title, url)
        row.tag = holder
        val del = Ui.iconButton(this, R.drawable.ic_close, "この履歴を削除", 36)
        del.setOnClickListener {
            val entry = holder.entry ?: return@setOnClickListener
            if (historyStore.removeEntry(entry)) {
                persistHistory()
                renderHistory()
            }
        }
        row.addView(del)
        row.setOnClickListener {
            val entry = holder.entry ?: return@setOnClickListener
            hideHistoryPage()
            val t = activeTab ?: newTab()
            // The URL comes from saved state, not from typing:
            // loadSavedUrl loads http(s) as-is and sends
            // anything else home, where loadInput would
            // reinterpret it as a search query (SEN-154 review).
            t.noteUserNavigation()
            t.loadSavedUrl(entry.url)
        }
        return row
    }

    private fun renderHistory() {
        val entries = historyStore.search(historyQuery)
        if (entries.isEmpty()) {
            historyItems = emptyList()
            historyEmptyText.text =
                if (historyStore.isEmpty()) "履歴はありません" else "見つかりませんでした"
            historyEmptyText.visibility = View.VISIBLE
            historyListView.visibility = View.GONE
            historyAdapter.notifyDataSetChanged()
            return
        }
        historyEmptyText.visibility = View.GONE
        historyListView.visibility = View.VISIBLE
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val dayMonth = java.time.format.DateTimeFormatter.ofPattern("M月d日")
        val full = java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日")
        val items = ArrayList<HistoryListItem>()
        for (group in historyDayGroups(entries, zone)) {
            val label = when (group.date) {
                today -> "今日"
                today.minusDays(1) -> "昨日"
                else -> if (group.date.year == today.year) {
                    group.date.format(dayMonth)
                } else {
                    group.date.format(full)
                }
            }
            items.add(HistoryListItem.DayHeader(label))
            for (entry in group.entries) {
                items.add(HistoryListItem.EntryRow(entry))
            }
        }
        historyItems = items
        historyAdapter.notifyDataSetChanged()
    }

    private fun buildClearDataPage() {
        val pageRoot = FrameLayout(this)
        pageRoot.setBackgroundColor(Colors.SURFACE)
        pageRoot.visibility = View.GONE
        clearDataPage = pageRoot
        // Consume touches in any area the content does not
        // cover, so they cannot fall through to the browser
        // underneath (the settings page's rule).
        pageRoot.isClickable = true

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        pageRoot.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6))
        val closeBtn = Ui.iconButton(this, R.drawable.ic_close, "データ削除を閉じる")
        closeBtn.setOnClickListener { hideClearDataPage() }
        header.addView(closeBtn)
        val title = Ui.textRow(this, "閲覧履歴データの削除", 18f)
        title.setTypeface(null, Typeface.BOLD)
        header.addView(title)
        column.addView(header)

        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        )
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        scroll.addView(body)
        column.addView(scroll)

        // Period picker row. The period scopes the history
        // category only (owner decision, 2026-11-21); the note
        // under the row says so.
        val periodRow = LinearLayout(this)
        periodRow.orientation = LinearLayout.HORIZONTAL
        periodRow.gravity = Gravity.CENTER_VERTICAL
        periodRow.setPadding(
            Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12),
        )
        val periodTexts = LinearLayout(this)
        periodTexts.orientation = LinearLayout.VERTICAL
        periodTexts.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        )
        periodTexts.addView(Ui.textRow(this, "期間", 15f))
        clearPeriodValue = Ui.textRow(this, "全期間", 13f, Colors.ON_SURFACE_VARIANT)
        periodTexts.addView(clearPeriodValue)
        periodRow.addView(periodTexts)
        val periodArrow = ImageView(this)
        periodArrow.setImageResource(R.drawable.ic_arrow_right)
        periodArrow.setColorFilter(Colors.ON_SURFACE_VARIANT)
        periodArrow.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20))
        periodRow.addView(periodArrow)
        periodRow.setOnClickListener { showPeriodPicker() }
        body.addView(periodRow)
        val periodNote = Ui.textRow(
            this, "期間は閲覧履歴にのみ適用されます", 12f, Colors.ON_SURFACE_VARIANT,
        )
        periodNote.setPadding(
            Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8),
        )
        body.addView(periodNote)
        body.addView(hairline())

        val historyRowParts = clearCategoryRow(
            R.drawable.ic_history, "閲覧履歴",
        )
        clearHistoryDesc = historyRowParts.second
        clearHistoryCheck = historyRowParts.third
        body.addView(historyRowParts.first)
        val cookiesRowParts = clearCategoryRow(
            R.drawable.ic_cookie, "Cookies とサイトデータ",
        )
        clearCookiesDesc = cookiesRowParts.second
        clearCookiesCheck = cookiesRowParts.third
        body.addView(cookiesRowParts.first)
        val cacheRowParts = clearCategoryRow(
            R.drawable.ic_image, "キャッシュされた画像とファイル",
        )
        cacheRowParts.second.text = "開いているすべてのタブのキャッシュを消去します"
        clearCacheCheck = cacheRowParts.third
        body.addView(cacheRowParts.first)
        val tabsRowParts = clearCategoryRow(
            R.drawable.ic_app_window, "タブ",
        )
        clearTabsDesc = tabsRowParts.second
        clearTabsCheck = tabsRowParts.third
        body.addView(tabsRowParts.first)
        val siteSettingsRowParts = clearCategoryRow(
            R.drawable.ic_sliders, "サイト設定",
        )
        siteSettingsRowParts.second.text =
            "WebView が保持するサイトごとの認証情報などを消去します"
        clearSiteSettingsCheck = siteSettingsRowParts.third
        body.addView(siteSettingsRowParts.first)
        val logsRowParts = clearCategoryRow(
            R.drawable.ic_file_text, "記録したログファイル",
        )
        // The wording matches the actual scope (owner decision
        // on the v0.22.0 review, 2026-11-23): recordings, the
        // pre-v0.22.0 exports and the debug log are deleted;
        // files the user exported to their own folder are
        // theirs and stay out of reach.
        logsRowParts.second.text =
            "DevTools の記録、旧バージョンの書き出し、デバッグログを消去します（書き出し先へ書き出したファイルは対象外です）"
        clearLogsCheck = logsRowParts.third
        body.addView(logsRowParts.first)
        // The check state comes from the saved selections
        // when the page is shown (showClearDataPage); every
        // category starts unchecked for a new user.
        val buttonArea = LinearLayout(this)
        buttonArea.setPadding(
            Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 16),
        )
        clearExecuteButton = Ui.textRow(this, "データを削除", 15f, Colors.PRIMARY)
        clearExecuteButton.gravity = Gravity.CENTER
        clearExecuteButton.setTypeface(null, Typeface.BOLD)
        clearExecuteButton.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48),
        )
        clearExecuteButton.setOnClickListener { executeClearData() }
        buttonArea.addView(clearExecuteButton)
        column.addView(buttonArea)

        root.addView(
            clearDataPage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * One checkbox category row of the clear-data page: icon,
     * title + description, checkbox. Returns the row, the
     * description view (two categories rewrite theirs when the
     * page is refreshed) and the checkbox. Tapping the row
     * toggles the checkbox; the checkbox itself is not directly
     * clickable so the row is the single toggle path.
     */
    private fun clearCategoryRow(
        iconRes: Int,
        title: String,
    ): Triple<LinearLayout, TextView, android.widget.CheckBox> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(
            Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12),
        )
        val icon = ImageView(this)
        icon.setImageResource(iconRes)
        icon.setColorFilter(Colors.ON_SURFACE_VARIANT)
        icon.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24))
        row.addView(icon)
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(Ui.dp(this, 14), 0, 0, 0)
        texts.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        )
        texts.addView(Ui.textRow(this, title, 15f))
        val desc = Ui.textRow(this, "", 13f, Colors.ON_SURFACE_VARIANT)
        texts.addView(desc)
        row.addView(texts)
        val check = android.widget.CheckBox(this)
        check.buttonTintList =
            android.content.res.ColorStateList.valueOf(Colors.PRIMARY)
        check.isClickable = false
        // The row carries the toggle's accessibility semantics
        // (the menu rows' treatment); the box itself is a
        // visual mirror of the state, not a separate
        // TalkBack stop.
        check.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(check)
        Ui.applyCheckboxSemantics(row, check.isChecked)
        clearRows.add(Triple(row, title, check))
        row.setOnClickListener {
            check.isChecked = !check.isChecked
            Ui.applyCheckboxSemantics(row, check.isChecked)
            refreshClearExecuteButton()
            persistClearSelection()
        }
        return Triple(row, desc, check)
    }

    private fun showClearDataPage() {
        expireActiveTabPopupProbes()
        // The history page stays alive underneath this one,
        // so a search still in progress there would keep its
        // focus — and the keyboard — across the transition
        // (owner request, 2026-11-22). Move the focus onto
        // the focus sink instead of clearFocus(): an
        // implicit refocus could land on the address bar and
        // put it back into editing mode (SEN-159).
        if (historySearchInput.hasFocus()) moveFocusToSink()
        hideKeyboard()
        // The page opens with the user's saved selections,
        // exactly as they left them (owner decision,
        // 2026-11-21); every category defaults to unchecked
        // until the user first toggles it.
        val sel = Settings.clearSelection(this)
        clearHistoryCheck.isChecked = sel.history
        clearCookiesCheck.isChecked = sel.cookies
        clearCacheCheck.isChecked = sel.cache
        clearTabsCheck.isChecked = sel.tabs
        clearSiteSettingsCheck.isChecked = sel.siteSettings
        clearLogsCheck.isChecked = sel.logs
        for ((row, _, check) in clearRows) {
            Ui.applyCheckboxSemantics(row, check.isChecked)
        }
        refreshClearDataPage()
        clearDataPage.visibility = View.VISIBLE
    }

    /**
     * Persist the page's current selections as the user
     * left them. Called from the category rows' click
     * handling only, which runs after the page is built —
     * the same precondition refreshClearExecuteButton
     * already relies on.
     */
    private fun persistClearSelection() {
        Settings.setClearSelection(
            this,
            Settings.ClearSelection(
                history = clearHistoryCheck.isChecked,
                cookies = clearCookiesCheck.isChecked,
                cache = clearCacheCheck.isChecked,
                tabs = clearTabsCheck.isChecked,
                siteSettings = clearSiteSettingsCheck.isChecked,
                logs = clearLogsCheck.isChecked,
            ),
        )
    }

    private fun hideClearDataPage() {
        clearDataPage.visibility = View.GONE
    }

    /**
     * The provisional settings page (owner decision, 2026-11-22).
     * Until a real settings page is designed, this page exists so
     * the debug-log gate always has a UI path: the browser menu
     * no longer carries the toggle, and a gate that is on with no
     * way to turn it off would record indefinitely.
     */
    private fun buildSettingsPage() {
        val pageRoot = FrameLayout(this)
        pageRoot.setBackgroundColor(Colors.SURFACE)
        pageRoot.visibility = View.GONE
        settingsPage = pageRoot
        // The page is mostly empty space below its single row:
        // the root consumes touches so they cannot fall through
        // to the browser hidden underneath.
        pageRoot.isClickable = true

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        pageRoot.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Header: close button on the left, then the title -- the
        // history page's header shape.
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6))
        val closeBtn = Ui.iconButton(this, R.drawable.ic_close, "設定を閉じる")
        closeBtn.setOnClickListener { hideSettingsPage() }
        header.addView(closeBtn)
        val title = Ui.textRow(this, "設定", 18f)
        title.setTypeface(null, Typeface.BOLD)
        header.addView(title)
        column.addView(header)

        // The debug-log toggle: icon, label + description and a
        // native checkbox, the clear-data page's row shape. The
        // checkbox itself is not directly clickable so the row is
        // the single toggle path.
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12))
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_bug)
        icon.setColorFilter(Colors.ON_SURFACE_VARIANT)
        icon.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24))
        row.addView(icon)
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(Ui.dp(this, 14), 0, 0, 0)
        texts.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        )
        texts.addView(Ui.textRow(this, "デバッグログを出力", 15f))
        texts.addView(
            Ui.textRow(
                this, "診断用のログをファイルに記録します。通常はオフのままにしてください",
                13f, Colors.ON_SURFACE_VARIANT,
            )
        )
        row.addView(texts)
        val check = android.widget.CheckBox(this)
        check.buttonTintList =
            android.content.res.ColorStateList.valueOf(Colors.PRIMARY)
        check.isClickable = false
        // The row carries the toggle's accessibility semantics
        // (see syncDebugLogRow); the box itself is a visual
        // mirror of the state, not a separate TalkBack stop.
        check.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        debugLogCheck = check
        row.addView(check)
        debugLogRow = row
        row.setOnClickListener {
            val newValue = !Settings.debugLog(this)
            Settings.setDebugLog(this, newValue)
            DebugLog.setEnabled(this, newValue)
            syncDebugLogRow()
        }
        column.addView(row)

        root.addView(
            settingsPage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }


    // ---------- Console log export (recordings -> user folder) ----------

    /** The export page's selection, by recording name, kept
     * across list rebuilds: a rebuild preserves the checks of
     * recordings that are still there. */
    private val exportCheckedNames = mutableSetOf<String>()

    /** Recording names the last list rebuild showed; tells a
     * newly appeared recording (starts checked) apart from one
     * the user unchecked (stays unchecked across rebuilds). */
    private var exportKnownNames: Set<String> = emptySet()

    /** Names the current list shows, in display order. */
    private var exportListedNames: List<String> = emptyList()


    /** The destination folder's display name, resolved off the
     * UI thread: (saved URI string, name). */
    private var exportDestName: Pair<String, String>? = null

    /** The URI string whose display name is being resolved, so
     * a rebuild during the lookup does not spawn a second
     * thread for the same name. */
    private var exportDestResolving: String? = null

    /** The URI string whose display name could not be resolved;
     * not retried on every rebuild (the value stays "設定済み"). */
    private var exportDestFailed: String? = null

    private fun buildExportPage() {
        val pageRoot = FrameLayout(this)
        pageRoot.setBackgroundColor(Colors.SURFACE)
        pageRoot.visibility = View.GONE
        exportPage = pageRoot
        // Consume touches in any area the content does not
        // cover, so they cannot fall through to the browser
        // underneath (the settings page's rule).
        pageRoot.isClickable = true

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        pageRoot.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Header: close button on the left, then the title --
        // the history page's header shape.
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6))
        val closeBtn = Ui.iconButton(this, R.drawable.ic_close, "コンソール書き出しを閉じる")
        closeBtn.setOnClickListener { hideExportPage() }
        header.addView(closeBtn)
        val title = Ui.textRow(this, "コンソール書き出し", 18f)
        title.setTypeface(null, Typeface.BOLD)
        header.addView(title)
        column.addView(header)

        // Destination row: the picked folder (its PageLoupe
        // subfolder receives the files) and the button that
        // re-picks it. Before the first pick the value says so;
        // the pick then happens as part of the first export.
        val destRow = LinearLayout(this)
        destRow.orientation = LinearLayout.HORIZONTAL
        destRow.gravity = Gravity.CENTER_VERTICAL
        destRow.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12))
        val destTexts = LinearLayout(this)
        destTexts.orientation = LinearLayout.VERTICAL
        destTexts.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        )
        destTexts.addView(Ui.textRow(this, "書き出し先", 15f))
        val destValue = Ui.textRow(this, "", 13f, Colors.ON_SURFACE_VARIANT)
        exportDestValue = destValue
        destTexts.addView(destValue)
        destRow.addView(destTexts)
        val changeBtn = Ui.textRow(this, "変更", 14f, Colors.PRIMARY)
        changeBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8))
        changeBtn.setOnClickListener { openExportFolderPicker(null) }
        exportChangeButton = changeBtn
        destRow.addView(changeBtn)
        column.addView(destRow)

        // The recordings, newest first, each row a checkbox the
        // row itself toggles (the clear-data page's row rule).
        val scroll = android.widget.ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        )
        val listColumn = LinearLayout(this)
        listColumn.orientation = LinearLayout.VERTICAL
        exportListColumn = listColumn
        scroll.addView(listColumn)
        column.addView(scroll)
        val emptyText = Ui.textRow(this, "記録ファイルはありません", 14f, Colors.ON_SURFACE_VARIANT)
        emptyText.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12))
        emptyText.visibility = View.GONE
        exportEmptyText = emptyText
        column.addView(emptyText)

        val buttonArea = LinearLayout(this)
        buttonArea.setPadding(
            Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 16),
        )
        val runButton = Ui.textRow(this, "書き出す", 15f, Colors.PRIMARY)
        runButton.gravity = Gravity.CENTER
        runButton.setTypeface(null, Typeface.BOLD)
        runButton.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48),
        )
        runButton.setOnClickListener {
            val names = exportListedNames.filter { it in exportCheckedNames }
            if (names.isNotEmpty()) runExportMove(names)
        }
        exportRunButton = runButton
        buttonArea.addView(runButton)
        column.addView(buttonArea)

        root.addView(
            exportPage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun showExportPage() {
        expireActiveTabPopupProbes()
        // Same focus evacuation as the settings page: nothing
        // behind this page may keep the keyboard.
        if (currentFocus != null) moveFocusToSink()
        hideKeyboard()
        refreshExportPage()
        exportPage.visibility = View.VISIBLE
    }

    private fun hideExportPage() {
        exportPage.visibility = View.GONE
    }

    /** Rebuilds the destination row and the file list from the
     * current state (page open, a pick returning, an export
     * finishing). Checks survive the rebuild per recording;
     * recordings that appear for the first time start checked:
     * the user came here to move files out, and unchecking is
     * the opt-out. */
    private fun refreshExportPage() {
        val treeStr = Settings.exportFolderUri(this)
        if (treeStr == null) {
            exportDestValue.text = "未設定（書き出すときに選択します）"
        } else {
            val cached = exportDestName?.takeIf { it.first == treeStr }?.second
            if (cached != null) {
                exportDestValue.text = "$cached / PageLoupe"
            } else {
                // The name comes from the provider, which may be
                // slow: show the settled state now and fill the
                // name in when the off-thread lookup returns.
                exportDestValue.text = "設定済み"
                resolveExportDestName(treeStr)
            }
        }
        val recordings = RecordedLogs.list(this)
        val present = recordings.map { it.name }
        exportCheckedNames.retainAll(present.toSet())
        for (name in present) {
            if (name !in exportKnownNames) exportCheckedNames.add(name)
        }
        exportKnownNames = present.toSet()
        exportListedNames = present
        exportListColumn.removeAllViews()
        val dateFmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US)
        for (rec in recordings) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10))
            val check = android.widget.CheckBox(this)
            check.buttonTintList =
                android.content.res.ColorStateList.valueOf(Colors.PRIMARY)
            check.isChecked = rec.name in exportCheckedNames
            check.isClickable = false
            // The row carries the toggle's accessibility
            // semantics (the menu rows' treatment); the box
            // itself is a visual mirror of the state, not a
            // separate TalkBack stop.
            check.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.addView(check)
            Ui.applyCheckboxSemantics(row, check.isChecked)
            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.setPadding(Ui.dp(this, 10), 0, 0, 0)
            texts.addView(Ui.textRow(this, rec.name, 14f))
            texts.addView(
                Ui.textRow(
                    this,
                    "${dateFmt.format(java.util.Date(rec.modifiedMillis))} ・ " +
                        ExportPolicy.formatSize(rec.sizeBytes),
                    12f, Colors.ON_SURFACE_VARIANT,
                )
            )
            row.addView(texts)
            row.setOnClickListener {
                if (rec.name in exportCheckedNames) {
                    exportCheckedNames.remove(rec.name)
                } else {
                    exportCheckedNames.add(rec.name)
                }
                check.isChecked = rec.name in exportCheckedNames
                Ui.applyCheckboxSemantics(row, check.isChecked)
                refreshExportRunButton()
            }
            exportListColumn.addView(row)
        }
        exportEmptyText.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
        refreshExportRunButton()
    }

    /** Resolves the picked folder's display name off the UI
     * thread (a DocumentsProvider query can be slow) and fills
     * it in if the page is still showing that destination. */
    private fun resolveExportDestName(treeStr: String) {
        if (exportDestResolving == treeStr || exportDestFailed == treeStr) return
        exportDestResolving = treeStr
        kotlin.concurrent.thread(name = "pl-export-dest") {
            val name = ExportTree.displayName(this, android.net.Uri.parse(treeStr))
            runOnUiThread {
                if (exportDestResolving == treeStr) exportDestResolving = null
                if (name == null) {
                    exportDestFailed = treeStr
                    return@runOnUiThread
                }
                exportDestName = treeStr to name
                if (::exportPage.isInitialized &&
                    exportPage.visibility == View.VISIBLE &&
                    Settings.exportFolderUri(this) == treeStr
                ) {
                    exportDestValue.text = "$name / PageLoupe"
                }
            }
        }
    }

    private fun refreshExportRunButton() {
        val any = !exportMoveInFlight &&
            exportListedNames.any { it in exportCheckedNames }
        exportRunButton.isEnabled = any
        exportRunButton.background = Ui.roundBg(
            if (any) Colors.PRIMARY_CONTAINER else Colors.SURFACE_CONTAINER,
            24f,
            this,
        )
        exportRunButton.setTextColor(
            if (any) Colors.PRIMARY else Colors.ON_SURFACE_VARIANT,
        )
        // Changing the destination mid-move would release the
        // grant the move is copying through, stranding partial
        // files in the old folder and possibly clearing the
        // folder the user just picked: the change control waits
        // for the move like the run button does.
        exportChangeButton.isEnabled = !exportMoveInFlight
        exportChangeButton.setTextColor(
            if (!exportMoveInFlight) Colors.PRIMARY else Colors.ON_SURFACE_VARIANT,
        )
    }

    /** Opens the system folder picker. [pendingNames] non-null
     * means the pick completes an export the user already asked
     * for; null means the pick only changes the destination. */
    private fun openExportFolderPicker(pendingNames: List<String>?) {
        // Never while a move is copying through the current
        // grant (the change button is also disabled then; this
        // is the same rule at the entry itself), and never
        // twice: one picker at a time.
        if (exportMoveInFlight) return
        if (exportPickerOpen) return
        exportPickerOpen = true
        pendingExportNames = pendingNames
        val intent = android.content.Intent(
            android.content.Intent.ACTION_OPEN_DOCUMENT_TREE
        ).apply {
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, REQ_EXPORT_TREE)
        } catch (e: Throwable) {
            // Whatever the launch failure, the picker is not
            // open: the flag must not stay up and mute every
            // later pick for the process's lifetime.
            exportPickerOpen = false
            pendingExportNames = null
            if (e is android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(
                    this, "フォルダ選択を開けませんでした",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                throw e
            }
        }
    }

    /** Saves (or clears) the export destination, releasing every
     * persisted tree grant that is not the new value: a replaced
     * or invalidated folder must not keep its read/write access
     * for the app's lifetime. */
    private fun updateExportFolder(newUri: android.net.Uri?) {
        val newStr = newUri?.toString()
        try {
            for (grant in contentResolver.persistedUriPermissions) {
                if (grant.uri.toString() != newStr) {
                    try {
                        contentResolver.releasePersistableUriPermission(
                            grant.uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        DebugLog.log(
                            "export",
                            "grant release failed: ${e.javaClass.simpleName}"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("export", "grant sweep failed: ${e.javaClass.simpleName}")
        }
        Settings.setExportFolderUri(this, newStr)
        exportDestFailed = null
        if (newStr == null) exportDestName = null
    }

    /**
     * Moves the named recordings into the picked folder's
     * PageLoupe subfolder: copy, then delete the original --
     * the move the owner asked for, with the original kept
     * whenever its copy did not complete (and the partial copy
     * removed, so a failed move leaves nothing behind at the
     * destination either). With no destination picked yet, the
     * picker opens first and the move runs when it returns. A
     * destination that no longer answers because the grant or
     * the folder is gone is cleared and re-picked with the same
     * selection; a transient provider failure keeps the
     * destination and just reports the failure.
     */
    private fun runExportMove(names: List<String>) {
        val treeStr = Settings.exportFolderUri(this)
        if (treeStr == null) {
            openExportFolderPicker(names)
            return
        }
        if (exportMoveInFlight) return
        val treeUri = android.net.Uri.parse(treeStr)
        exportMoveInFlight = true
        refreshExportRunButton()
        logFileExecutor.execute {
            // The single source the report below is built
            // from, on every ending: ExportMove records into
            // it as files land, and the folder resolution
            // records its own outcome into it too.
            val progress = ExportMove.Progress()
            try {
                val dir = RecordedLogs.dir(this)
                ExportMove.run(
                    names, dir,
                    resolveTarget = {
                        // Resolving (or creating) the
                        // subfolder exercises the grant;
                        // ExportMove calls this only when
                        // something is present, and classifies
                        // its failure.
                        ExportTree.targetFor(
                            this, ExportTree.pageLoupeDir(this, treeUri)
                        )
                    },
                    progress = progress,
                    onFailure = { name, e ->
                        DebugLog.log(
                            "export",
                            "export failed for $name: ${e.javaClass.simpleName}"
                        )
                    },
                    onOriginalRemains = { name ->
                        DebugLog.log(
                            "export",
                            "exported but original remains: $name"
                        )
                    },
                    onResolveFailure = { e ->
                        DebugLog.log(
                            "export",
                            "export folder failed: ${e.javaClass.simpleName}"
                        )
                    },
                )
            } catch (e: Throwable) {
                // Last resort (an Error included): the UI
                // restore in finally must still run -- a stuck
                // disabled button is worse. The counts in
                // progress cover what was attempted; files an
                // Error left untouched are not reported as
                // failed, and the abort mark keeps the wording
                // from reading as a clean finish.
                progress.aborted = true
                DebugLog.log("export", "export move failed: ${e.javaClass.simpleName}")
            } finally {
                runOnUiThread {
                    exportMoveInFlight = false
                    if (progress.folderInvalid) {
                        // State first, before any UI work: the
                        // dead destination is cleared and its
                        // grant released even when this instance
                        // is already destroyed.
                        updateExportFolder(null)
                    }
                    // The result reaches the user even when the
                    // instance that started the move was
                    // recreated mid-move: a toast is not view
                    // work, so it goes out on the application
                    // context. One builder covers both endings,
                    // the partial-leftover note included.
                    val message = ExportPolicy.completionMessage(
                        progress.moved, progress.failed, progress.skipped,
                        progress.folderInvalid, progress.discardFailures,
                        progress.aborted
                    )
                    android.widget.Toast.makeText(
                        applicationContext, message,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    // View work goes to the live instance, which
                    // may not be the one that started the move.
                    val host = exportUiHost?.get()
                    if (host != null && !host.isDestroyed) {
                        host.onExportMoveFinished(
                            progress.folderInvalid, names, progress.movedNames.toList()
                        )
                    }
                }
            }
        }
    }

    /** The view side of a finished move, called on the live
     * instance (see exportUiHost): refresh the export page if
     * it is showing, and after an invalid folder re-open the
     * picker for what has not moved yet. */
    private fun onExportMoveFinished(
        folderInvalid: Boolean,
        names: List<String>,
        movedNames: List<String>,
    ) {
        if (folderInvalid) {
            // The starter instance already cleared the shared
            // state; clearing here too resets THIS instance's
            // display-name caches (the call is idempotent) --
            // and it applies whether or not this instance's
            // page is the one showing.
            updateExportFolder(null)
        }
        if (!::exportPage.isInitialized || exportPage.visibility != View.VISIBLE) return
        refreshExportPage()
        // Re-picking is offered automatically only in the
        // foreground: a background start of the system picker
        // is blocked on current Android, and surprising the
        // user over another app would be worse. In the
        // background the toast's guidance stands, and picking
        // again by hand works the same.
        if (folderInvalid && activityResumed) {
            // Already-moved recordings are not part of the
            // re-pick's selection: they are at the old
            // destination, not "already deleted".
            val remaining = names.filter { it !in movedNames }
            if (remaining.isNotEmpty()) {
                openExportFolderPicker(remaining)
            }
        }
    }

    private fun showSettingsPage() {
        expireActiveTabPopupProbes()
        // The page has no input of its own: a focus left behind
        // it (the address bar, a web form, the DevTools console)
        // would keep the keyboard and take typing meant for
        // this page. Evacuate it the SEN-159 way.
        if (currentFocus != null) moveFocusToSink()
        hideKeyboard()
        syncDebugLogRow()
        settingsPage.visibility = View.VISIBLE
    }

    private fun hideSettingsPage() {
        settingsPage.visibility = View.GONE
    }

    /** Reflect the saved debug-log state in the settings page's
     * row: the checkbox, and the row's checkbox semantics so
     * TalkBack reads the state (the menu rows' treatment). */
    private fun syncDebugLogRow() {
        val on = Settings.debugLog(this)
        debugLogCheck.isChecked = on
        Ui.applyCheckboxSemantics(debugLogRow, on)
    }

    private fun periodLabel(p: HistoryPeriod): String = when (p) {
        HistoryPeriod.LAST_HOUR -> "過去1時間"
        HistoryPeriod.LAST_24_HOURS -> "過去24時間"
        HistoryPeriod.LAST_7_DAYS -> "過去7日間"
        HistoryPeriod.ALL -> "全期間"
    }

    private fun showPeriodPicker() {
        val labels = HistoryPeriod.values().map { periodLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("期間")
            .setSingleChoiceItems(labels, clearPeriod.ordinal) { d, which ->
                clearPeriod = HistoryPeriod.values()[which]
                refreshClearDataPage()
                d.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun refreshClearDataPage() {
        clearPeriodValue.text = periodLabel(clearPeriod)
        val cutoff = clearPeriod.cutoffMillis(System.currentTimeMillis())
        clearHistoryDesc.text = "${historyStore.countSince(cutoff)} 件"
        clearTabsDesc.text = "開いているタブ ${tabs.size} 件をすべて閉じます"
        // The message must stay true where the frontend could
        // not move to its own profile (an unsupported WebView,
        // or the assignment failed): there it still lives on
        // the default profile, and this wipe takes the DevTools
        // settings with it.
        val devToolsSettingsKept =
            ProfileSupport.isSupported() &&
                DevToolsProfile.lastApplyResult != DevToolsProfile.ApplyResult.FAILED
        clearCookiesDesc.text = if (devToolsSettingsKept) {
            "Cookie とサイトの保存データを消去します"
        } else {
            "Cookie とサイトの保存データを消去します。この端末では DevTools の設定も消去されます"
        }
        refreshClearExecuteButton()
    }

    private fun refreshClearExecuteButton() {
        val any = clearHistoryCheck.isChecked || clearCookiesCheck.isChecked ||
            clearCacheCheck.isChecked || clearTabsCheck.isChecked ||
            clearSiteSettingsCheck.isChecked || clearLogsCheck.isChecked
        clearExecuteButton.isEnabled = any
        clearExecuteButton.background = Ui.roundBg(
            if (any) Colors.PRIMARY_CONTAINER else Colors.SURFACE_CONTAINER,
            24f,
            this,
        )
        clearExecuteButton.setTextColor(
            if (any) Colors.PRIMARY else Colors.ON_SURFACE_VARIANT,
        )
    }

    /**
     * Run the checked categories of the clear-data page. Each
     * category is independent: the history category never
     * touches open tabs or the saved session (owner decision,
     * 2026-11-21), closing tabs is its own opt-in category,
     * and the site-data / cache / log bodies are the ones the
     * retired all-in-one wipe ran.
     */
    private fun executeClearData() {
        var clearFailed = false
        if (clearHistoryCheck.isChecked) {
            val cutoff = clearPeriod.cutoffMillis(System.currentTimeMillis())
            historyStore.deleteSince(cutoff)
            persistHistory()
        }
        if (clearCookiesCheck.isChecked) {
            // deleteAllData and the cookie manager below only
            // reach the default profile. That is the whole
            // design: the DevTools frontend runs on its own
            // profile (DevToolsProfile), so this wipe clears
            // every site's data and still cannot touch the
            // DevTools settings. The app's own settings live in
            // SharedPreferences, which the clear never touches
            // either. A provider failure here must not crash
            // the clear or skip the steps after it: each store
            // is cleared inside runCatching and failures go to
            // the debug log (cf. SecretProfile's never-throws
            // contract).
            runCatching {
                WebStorage.getInstance().deleteAllData()
            }.onFailure {
                clearFailed = true
                DebugLog.log("clear", "storage clear failed: $it")
            }
            runCatching {
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            }.onFailure {
                clearFailed = true
                DebugLog.log("clear", "cookie clear failed: $it")
            }
            // The managers above only reach the default
            // profile: the secret profile is wiped too. While
            // secret tabs are open the profile cannot be
            // deleted, so it is cleared in place; with none
            // open, wipe() attempts the delete and falls back
            // to the same in-place clear when it is refused.
            if (tabs.any { it.secret }) {
                SecretProfile.clearInPlace("clear data")
            } else {
                SecretProfile.wipe("clear data")
            }
        }
        if (clearCacheCheck.isChecked) {
            for (t in tabs) {
                t.webView.clearCache(true)
            }
        }
        if (clearSiteSettingsCheck.isChecked) {
            if (!clearSiteSettingsStores()) clearFailed = true
        }
        if (clearTabsCheck.isChecked) {
            // After the categories that still use the tabs,
            // before the log sweep: closing the panel can
            // still write out the session's last exports, and
            // the sweep must have the final word on them.
            closeAllTabsAndRestart()
        }
        if (clearLogsCheck.isChecked) {
            // Exported logs can carry the same URLs and payloads
            // as the history being erased. The tap's diagnostic
            // residue (first bytes, first samples) is pre-clear
            // session data too: left in place, closing the panel
            // would write it back into a fresh tap-debug.txt.
            // Only the content is forgotten — the tap object
            // itself stays reachable so its loss counters and
            // failure flag keep showing in the stats line and
            // export notes. This runs BEFORE recorder.clear():
            // bumping the tap's epoch first retires every
            // in-flight pre-clear message, and anything a
            // post-bump feed still delivers is wiped by the
            // clear below.
            proxy?.lastTap?.clearDiagnostics()
            // The in-memory recording goes too: otherwise an
            // open DevTools session would re-export everything
            // recorded before the clear when the panel closes.
            // Recording continues from empty.
            recorder.clear()
            // The debug log's own files are DebugLog's business
            // (its writer is closed, the files deleted, and
            // later lines reopen a fresh file); the sweep below
            // covers only the export files, and runs on the same
            // executor as the export writes so the two can
            // never interleave.
            DebugLog.deleteLogFiles(this)
            logFileExecutor.execute {
                // The recordings are the category's main target
                // now. The same block also sweeps the two retired
                // destinations -- exports left in the app's log
                // folder from before v0.21.0 (plus the tap
                // diagnostic that still lives there) and the
                // v0.21.0 Documents/PageLoupe files -- each in
                // its own block, so one side's failure cannot
                // skip the others.
                RecordedLogs.deleteAll(this@MainActivity)
                try {
                    ensureLogsDir().listFiles { f ->
                        f.name.startsWith("devtools-") || f.name == "tap-debug.txt"
                    }?.forEach { it.delete() }
                } catch (_: Exception) {
                }
                ExportedLogs.deleteAll(this@MainActivity)
            }
        }
        hideClearDataPage()
        if (historyPage.visibility == View.VISIBLE) renderHistory()
        android.widget.Toast.makeText(
            this,
            if (clearFailed) "一部のデータを削除できませんでした" else "削除しました",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * The clear-data page's site-settings category: what
     * WebView holds per site outside cookies, cache and web
     * storage — HTTP auth credentials, saved form data and
     * client-certificate choices. PageLoupe keeps no per-site
     * permission grants (every request is confirmed), so
     * there is no permission store to clear, and in the
     * current app these stores are normally empty (there is
     * no HTTP-auth or client-cert handler, so both requests
     * are always cancelled, and form data is not saved);
     * the stores are the platform's own and this is their
     * clear. These stores belong to the default profile;
     * the secret profile's equivalents live and die with
     * the profile itself. Returns false when any store
     * failed to clear; failures follow the cookies block's
     * contract (log, flag, keep going).
     */
    private fun clearSiteSettingsStores(): Boolean {
        var ok = true
        runCatching {
            val db = android.webkit.WebViewDatabase.getInstance(this)
            db.clearFormData()
            @Suppress("DEPRECATION")
            db.clearHttpAuthUsernamePassword()
        }.onFailure {
            ok = false
            DebugLog.log("clear", "site settings clear failed: $it")
        }
        runCatching {
            android.webkit.WebView.clearClientCertPreferences(null)
        }.onFailure {
            ok = false
            DebugLog.log("clear", "client cert clear failed: $it")
        }
        return ok
    }

    /**
     * The clear-data page's tabs category: close every open
     * tab (normal and secret) and start over from a single
     * fresh home tab. The saved session ends up describing
     * exactly that fresh state, so a cold start restores the
     * fresh tab rather than the closed ones. Session saves
     * are suppressed while the list is in flux and written
     * once at the end.
     */
    private fun closeAllTabsAndRestart() {
        if (dtOpen) closeRealDevTools()
        val hadSecret = tabs.any { it.secret }
        withRestoringTabs {
            for (t in tabs.toList()) detachAndDestroyTab(t)
            activeTab = null
            switcherSecretMode = false
            val fresh = newTab(secret = false, activate = false)
            fresh.loadHome()
            switchTo(fresh)
        }
        if (hadSecret) onSecretTabsClosed(true, "clear data: all tabs closed")
        updateTabCount()
        saveTabSession()
        DebugLog.log("tab", "all tabs closed from clear data")
    }

    // ---------- Tabs ----------

    private fun newTab(
        secret: Boolean = activeTab?.secret ?: false,
        activate: Boolean = true
    ): BrowserTab {
        val t = BrowserTab(this, this, secret)
        tabs.add(t)
        DebugLog.log("tab", "new tab secret=$secret, tabs=${tabs.size}")
        t.webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Keep progress bar on top: insert webview below it
        pageContainer.addView(t.webView, pageContainer.childCount - 1)
        if (activate) {
            switchTo(t)
        } else {
            t.webView.visibility = View.GONE
            // A tab created in the background starts paused, like
            // one switched away from.
            t.webView.onPause()
        }
        updateTabCount()
        saveTabSession()
        return t
    }

    private fun switchTo(t: BrowserTab) {
        val prev = activeTab
        if (prev != null && prev !== t && tabs.contains(prev)) {
            // Only a LIVE previous tab is touched at all: switchTo
            // can also be reached with an already-destroyed previous
            // tab (closeTab destroys before switching), whose
            // WebView must not be called into -- not even for the
            // thumbnail capture.
            // The capture itself is skipped when it cannot be a
            // good one: while the activity is not resumed, draw()
            // can snapshot a blank surface over a previously good
            // thumbnail (a paused-but-visible window in
            // multi-window keeps its older thumbnail instead —
            // the safe direction). While the switcher is open,
            // showSwitcher's capture owns this tab's freshness:
            // it is deferred to just after the open frame, so a
            // switch that lands before the
            // deferred capture runs takes it over synchronously
            // here (and consumes the pending request); once the
            // deferred capture has run, capturing again here
            // would be pure waste.
            val switcherUp = ::switcher.isInitialized &&
                switcher.visibility == View.VISIBLE
            if (activityResumed && (!switcherUp || thumbCapturePendingFor === prev)) {
                if (thumbCapturePendingFor === prev) thumbCapturePendingFor = null
                prev.captureThumbnail()
                persistThumbnail(prev)
            }
            // Background tabs pause their WebView: onPause pauses
            // rendering, animations and media on a best-effort
            // basis (JavaScript and network keep running; the
            // process-global pauseTimers() is deliberately NOT
            // used). Visibility alone does not stop any of it.
            prev.webView.onPause()
            // Losing the foreground also ends the tab's
            // undecided popups (SEN-309): a probe must not
            // decide later and open a tab over whatever the
            // user moved on to.
            prev.expirePopupProbes()
            // And its JS dialog (SEN-301): the question belongs
            // to the page that asked it, and must not stay up
            // over the tab the user switched to.
            retireJsDialog(prev)
            // And its pending download / permission prompts
            // (audit hardening): the same rule — only the tab on
            // screen may hold an unanswered approval. These two
            // were the ones that could outlive the switch and be
            // answered over another tab's page (an external VIEW
            // intent switching tabs mid-prompt is the concrete
            // path). Retirement mirrors beforeTabDestroyed: the
            // download is dropped, the permission denied.
            pendingDownload.takeIf { it.tab === prev }?.let {
                it.dialog?.let { d -> if (d.isShowing) d.dismiss() }
            }
            pendingPermission.takeIf { it.tab === prev }
                ?.let { denyPermission(it, "tab switched away") }
            // And its translation run (run-3 hardening): the
            // run's consent dialog names the page it would
            // translate, and must not surface over the tab the
            // user switched to — the same rule as the prompts
            // above. Cancelling is the run's own terminal path
            // (the run slot is activity-wide, so the dialogs it
            // dismisses can only be its own).
            pageTranslators[prev]?.cancelIfWorking()
        }
        // Resume only while the ACTIVITY is resumed: switchTo can
        // run while the app is backgrounded (renderer-death
        // recovery, an external link delivered to onNewIntent), and
        // a WebView resumed then would keep working invisibly until
        // the next pause. While paused, the new
        // active tab is paused too; Activity.onResume resumes it.
        if (activityResumed) t.webView.onResume() else t.webView.onPause()
        for (tab in tabs) tab.webView.visibility = if (tab === t) View.VISIBLE else View.GONE
        activeTab = t
        // A switch -- to another tab, or a rebuild of this one --
        // ends any address-bar edit: the text was being typed for
        // the tab (or tab instance) that was displayed before, and
        // leaving the edit alive would keep refreshAddressBar
        // refusing to run, so the bar would go on showing the
        // previous tab's URL (SEN-153).
        endAddressEdit()
        // A tab rebuilt without loading (restore / crash recovery of a
        // background tab) loads its deferred URL the first time it is
        // shown.
        val deferred = t.deferredUrl
        if (deferred != null) {
            t.deferredUrl = null
            // A crash-rebuilt background tab may carry its
            // predecessor's navigation state: restoring it
            // keeps the back/forward history a plain load of
            // the deferred URL would drop. Tabs without a
            // snapshot (session restore, other rebuilds) fall
            // through to the plain load.
            if (!t.restoreNavState(deferred)) t.loadSavedUrl(deferred)
        } else {
            // Already loaded: the switcher pick armed this tab's
            // user-navigation marker, but no navigation follows
            // to consume it -- drop it here.
            t.disarmUserNavigation()
        }
        refreshAddressBar()
        progressBar.visibility = if (t.isLoading) View.VISIBLE else View.GONE
        // The active index is part of the saved session.
        saveTabSession()
    }

    /**
     * After secret tabs were closed and destroyed: if none remain,
     * erase the secret profile. Deletion is attempted at once; a
     * refusal (the native teardown can lag destroy()) is retried
     * once on the next main-loop cycle — re-checking that no new
     * secret tab has appeared in the meantime — and only then does
     * the in-place clear run, so the retry's chance of a full
     * delete is not spent by loading the profile first. When the
     * app is finishing, the posted retry may never run; onDestroy's
     * own wipe is the backstop for that path.
     */
    private fun onSecretTabsClosed(closedWasSecret: Boolean, reason: String) {
        if (!SecretProfilePolicy.shouldWipeAfterSecretClose(
                closedWasSecret, tabs.count { it.secret }
            )
        ) return
        if (SecretProfile.deleteIfPossible(reason)) return
        pageContainer.post {
            when {
                tabs.none { it.secret } -> {
                    if (!SecretProfile.deleteIfPossible("$reason (retry)")) {
                        SecretProfile.clearInPlace("$reason (fallback)")
                    }
                }
                else -> {
                    // A new secret tab appeared before the retry:
                    // deleting is off the table (its WebView uses
                    // the profile), but the pending erasure must
                    // not be dropped — the previous session's data
                    // must not bleed into the new tab. The in-place
                    // clear empties the profile under it; the new
                    // tab's own data is moments old, and losing it
                    // is the safe direction.
                    SecretProfile.clearInPlace("$reason (superseded)")
                }
            }
        }
    }

    private fun closeTab(t: BrowserTab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        // The panel cannot inspect a tab that no longer exists.
        if (dtOpen && t === dtTargetTab) closeRealDevTools()
        DebugLog.log("tab", "close tab idx=$idx secret=${t.secret} url=${debugUrl(t)} tabs=${tabs.size}")
        val wasActive = activeTab === t
        tabs.remove(t)
        // The saved session follows the close like every other
        // state change (single writer, in order); when this was
        // the last tab, closeTab's empty branch flushes the
        // delete before finishing.
        saveTabSession()
        pageContainer.removeView(t.webView)
        beforeTabDestroyed(t)
        t.destroy()
        onSecretTabsClosed(t.secret, "last secret tab closed")
        if (tabs.isEmpty()) {
            // Closing the last tab (across both modes) exits
            // the app. The session delete queued by the save
            // above must be on disk before the process can die,
            // or the closed tab would come back on the next
            // cold start: flush the writer's queue first.
            sessionWriter.flush()
            finish()
            return
        }
        if (wasActive) {
            // Prefer a neighbour in the same mode, else any remaining tab.
            val sameMode = tabs.filter { it.secret == t.secret }
            val next = sameMode.getOrNull(minOf(idx, sameMode.size - 1))
                ?: tabs[minOf(idx, tabs.size - 1)]
            switchTo(next)
            if (switcher.visibility == View.VISIBLE && switcherSecretMode != next.secret) {
                switcherSecretMode = next.secret
                updateToggleVisuals()
            }
        }
        updateTabCount()
        renderSwitcher()
    }

    /** The tab button shows the count of tabs in the active tab's mode. */
    private fun updateTabCount() {
        val mode = activeTab?.secret ?: false
        tabCountText.text = tabs.count { it.secret == mode }.toString()
    }

    private fun showSwitcher() {
        expireActiveTabPopupProbes()
        val opening = activeTab
        switcherSecretMode = opening?.secret ?: false
        switcherQuery = ""
        if (::switcherSearchInput.isInitialized) switcherSearchInput.setText("")
        updateToggleVisuals()
        // Synchronous on open: a coalesced (posted) rebuild would
        // show the previous mode's cards -- possibly secret tabs'
        // thumbnails -- for one frame.
        renderSwitcherNow()
        switcher.visibility = View.VISIBLE
        // The thumbnail capture itself is deferred until after
        // this open is on screen: WebView.draw costs ~5-8ms
        // warm and up to ~30ms for a page's first capture on
        // the UI thread (measured on a real device at the
        // 360x403 capture size), which would delay the
        // switcher itself.
        // The card first shows the previous thumbnail (or the
        // placeholder) and is refreshed in place when the
        // capture lands.
        if (opening != null) deferThumbnailCapture(opening)
    }

    private fun hideSwitcher() {
        hideTabMenu()
        hideKeyboard()
        switcher.visibility = View.GONE
        // Drop the built cards: their ImageViews keep the last
        // rendered thumbnails alive for as long as the switcher
        // stays hidden. Opening the switcher
        // always renders synchronously first, so nothing
        // user-visible is lost.
        if (::switcherGrid.isInitialized) switcherGrid.removeAllViews()
    }

    /** The tab a deferred thumbnail capture is queued for, if any. */
    private var thumbCapturePendingFor: BrowserTab? = null

    /** Directory holding the persisted tab thumbnails (ThumbnailStore). */
    private val thumbDir: java.io.File by lazy { java.io.File(filesDir, ThumbnailStore.DIR_NAME) }

    /** Tabs with a persisted-thumbnail load already in flight (UI thread only). */
    private val thumbLoadInFlight = HashSet<BrowserTab>()

    /**
     * Tabs whose persisted-thumbnail lookup already missed,
     * by the URL that missed (UI thread only). Without this,
     * every card bind of a never-captured tab would post
     * another lookup. A capture of a URL clears every tab's
     * entry for that URL, and a lookup whose result arrives
     * after a capture does not record a miss at all (see
     * the generation check in loadPersistedThumbnail).
     */
    private val thumbLoadMisses = HashMap<BrowserTab, String>()

    /**
     * Per-URL thumbnail generation (UI thread only), bumped
     * by every persisted capture. Keyed by URL, not by tab:
     * the file is per-URL and duplicate tabs share it, so a
     * capture from any tab must invalidate a lookup another
     * tab started earlier. A load remembers the generation
     * when it is posted; if the generation has moved by the
     * time the result lands, the result predates the newest
     * capture — its file may have landed after the lookup
     * read — so a miss must not be recorded from it.
     * Entries are tiny and bounded by the distinct captured
     * URLs of the process.
     */
    private val thumbGenerations = HashMap<String, Int>()

    /**
     * Persist [tab]'s just-captured thumbnail, so the
     * switcher can show it again after a process restart or
     * a memory trim (SEN-157). Normal tabs only: a secret
     * tab's page image is never written to disk. The start
     * page is included (SEN-158): it is one fixed local
     * page, so a single shared file serves every home tab.
     * Best effort, off the UI thread — the capture path
     * never recycles bitmaps, so encoding this one on the
     * executor is safe.
     */
    private fun persistThumbnail(tab: BrowserTab) {
        if (tab.secret) return
        val bmp = tab.thumbnail ?: return
        val url = tab.currentUrl
        if (url.isBlank()) return
        // Under the shared home key, file only a capture of
        // the start page itself: until it has committed to
        // the screen, the WebView may still show the previous
        // page under the home URL (SEN-158).
        if (BrowserTab.isHomeUrl(url) && !tab.homeCommitted) return
        // Any tab's recorded miss for this URL is stale now:
        // misses are remembered per tab, but the file is
        // per-URL, so clear every tab's entry for it.
        thumbLoadMisses.entries.removeAll { it.value == url }
        thumbGenerations[url] = (thumbGenerations[url] ?: 0) + 1
        thumbFileExecutor.execute {
            runCatching { ThumbnailStore.save(thumbDir, url, bmp) }
                .onFailure { DebugLog.log("thumb", "save failed: $it") }
        }
    }

    /**
     * Load [tab]'s persisted thumbnail (if any) into memory
     * and refresh its switcher card. Runs when a card binds
     * without a bitmap — a restored tab, or one whose bitmap
     * a memory trim dropped. Normal tabs only: secret tabs
     * never touch the disk store, not even for a lookup. A
     * fresher in-memory capture always wins: the loaded
     * image is applied only while the tab still has none,
     * still exists, and still shows the same URL.
     */
    private fun loadPersistedThumbnail(tab: BrowserTab) {
        if (tab.secret || tab.thumbnail != null) return
        val url = tab.deferredUrl ?: tab.currentUrl
        if (url.isBlank()) return
        if (thumbLoadMisses[tab] == url) return
        if (!thumbLoadInFlight.add(tab)) return
        val gen = thumbGenerations[url] ?: 0
        thumbFileExecutor.execute {
            val result = runCatching { ThumbnailStore.load(thumbDir, url) }
                .getOrElse {
                    DebugLog.log("thumb", "load failed: $it")
                    ThumbLoad.Failed
                }
            runOnUiThread {
                thumbLoadInFlight.remove(tab)
                val samePage = tabs.contains(tab) &&
                    (tab.deferredUrl ?: tab.currentUrl) == url
                when (result) {
                    is ThumbLoad.Ok -> if (samePage && tab.thumbnail == null) {
                        tab.thumbnail = result.bitmap
                        refreshSwitcherCard(tab)
                    }
                    ThumbLoad.Failed, ThumbLoad.Corrupt -> {
                        // Retryable: record nothing, so the
                        // next bind tries again. A decode that
                        // returns null is not proof of
                        // corruption — under memory pressure
                        // the decoder can fail transiently —
                        // so the file is left in place; if it
                        // really is corrupt, the next capture
                        // overwrites it atomically anyway.
                    }
                    ThumbLoad.Absent, ThumbLoad.Oversized -> if (samePage &&
                        tab.thumbnail == null &&
                        (thumbGenerations[url] ?: 0) == gen
                    ) {
                        // Record the miss only while it is
                        // still true — the tab is alive on the
                        // same page, holds no capture, and no
                        // capture of this URL has been
                        // persisted since this lookup started.
                        // Oversized lands here too (audit
                        // hardening): unlike a decode failure it
                        // is deterministic, so retrying it on
                        // every bind would re-read the file
                        // forever; the next capture overwrites
                        // it like any miss.
                        thumbLoadMisses[tab] = url
                    }
                }
            }
        }
    }

    /**
     * Queue [tab]'s thumbnail capture behind the current frame
     * and refresh its switcher card when it lands.
     * Only the latest queued request runs: a newer request (or
     * switchTo taking the capture over synchronously) replaces
     * it, so a tab is never captured twice for one open. The
     * capture re-checks the moment at execution time: the tab
     * must still be the live, active one while the activity is
     * resumed — the same blank-surface concern as the switchTo
     * capture. If the user already switched away, the thumbnail
     * keeps its previous value; the next switcher open with
     * that tab active captures it again. The capture runs even
     * if the switcher has already closed: the thumbnail itself
     * is the product (it is what the next open shows), and the
     * card refresh is the best-effort part.
     */
    private fun deferThumbnailCapture(tab: BrowserTab) {
        if (thumbCapturePendingFor === tab) return
        thumbCapturePendingFor = tab
        window.decorView.post {
            if (thumbCapturePendingFor !== tab) return@post
            thumbCapturePendingFor = null
            if (!activityResumed || activeTab !== tab || !tabs.contains(tab)) return@post
            tab.captureThumbnail()
            persistThumbnail(tab)
            refreshSwitcherCard(tab)
        }
    }

    /**
     * Rebind [tab]'s already-built switcher card to its current
     * thumbnail (the deferred capture has landed). The card's
     * thumbnail frame carries its tab in the tag — set where
     * the frame is built — so this finds it by identity among
     * the card's children, without assuming child positions. A
     * frame that is not on screen (switcher hidden, filtered
     * out by the search, replaced by a rebuild) is simply not
     * found, and the next render binds the thumbnail anyway.
     */
    private fun refreshSwitcherCard(tab: BrowserTab) {
        if (!::switcherGrid.isInitialized || switcher.visibility != View.VISIBLE) return
        for (i in 0 until switcherGrid.childCount) {
            val card = switcherGrid.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until card.childCount) {
                val frame = card.getChildAt(j)
                if (frame.tag === tab && frame is FrameLayout) {
                    bindThumbFrame(frame, tab)
                    return
                }
            }
        }
    }

    /**
     * Fill a card's thumbnail frame from [tab]'s current state:
     * the captured image, or the placeholder letter when there
     * is none. The one place that knows the frame's contents —
     * used by renderSwitcherNow when building a card and by
     * refreshSwitcherCard when the deferred capture lands.
     */
    private fun bindThumbFrame(frame: FrameLayout, tab: BrowserTab) {
        frame.removeAllViews()
        val bmp = tab.thumbnail
        if (bmp != null) {
            frame.addView(thumbImage(bmp))
        } else {
            // No bitmap in memory: if one was persisted, it
            // arrives asynchronously and rebinds this card.
            loadPersistedThumbnail(tab)
            val letter = TextView(this)
            letter.text = (tab.displayUrl().firstOrNull()?.uppercase() ?: "P")
            letter.textSize = 30f
            letter.setTextColor(Colors.PRIMARY)
            letter.gravity = Gravity.CENTER
            letter.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            frame.addView(letter)
        }
    }

    /** The card's thumbnail image. */
    private fun thumbImage(bmp: android.graphics.Bitmap): ImageView {
        val img = ImageView(this)
        img.setImageBitmap(bmp)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        return img
    }

    private var switcherRenderPending = false

    /**
     * Generation of the latest switcher render request. A
     * synchronous render bumps it, which invalidates any coalesced
     * rebuild still posted -- without it, opening the switcher
     * (whose search-field reset fires the text watcher) rebuilt
     * every card twice.
     * Invariant: seq is bumped only in renderSwitcher() (together
     * with setting switcherRenderPending) and in
     * renderSwitcherNow() (together with clearing it), so a stale
     * posted runnable that returns without touching pending can
     * never leave pending stuck.
     */
    private var switcherRenderSeq = 0

    /**
     * Rebuilding every tab card is the switcher's expensive path,
     * and events that ask for it (title, favicon, progress-driven
     * refreshes, search typing) can arrive in bursts. Coalesce all
     * requests within one frame into a single rebuild, posted to
     * the next frame -- an event-driven batching, not a timed wait.
     */
    private fun renderSwitcher() {
        if (!::switcherGrid.isInitialized) return
        if (switcherRenderPending) return
        switcherRenderPending = true
        val seq = ++switcherRenderSeq
        switcherGrid.post {
            if (seq != switcherRenderSeq) return@post
            switcherRenderPending = false
            // A rebuild that lands after the switcher was hidden is
            // wasted work: opening it always renders synchronously
            // first.
            if (::switcher.isInitialized && switcher.visibility == View.VISIBLE) {
                if (!activityStopped) {
                    renderSwitcherNow()
                } else {
                    // Stopped with the switcher up: rebuilding here
                    // would re-hold every thumbnail the trim path is
                    // trying to release. Empty
                    // the grid instead, like onTrimMemory does;
                    // onResume re-renders an empty visible grid.
                    // Paused-but-visible (multi-window) still
                    // rebuilds: the user is looking at the cards.
                    switcherGrid.removeAllViews()
                }
            }
        }
    }

    private fun renderSwitcherNow() {
        if (!::switcherGrid.isInitialized) return
        // Any coalesced rebuild still posted is now stale.
        switcherRenderSeq++
        switcherRenderPending = false
        switcherGrid.removeAllViews()
        val modeTabs = tabs.filter { it.secret == switcherSecretMode }
        if (::switcherTitle.isInitialized) switcherTitle.text = "${modeTabs.size} 件のタブ"
        val shown = if (switcherQuery.isEmpty()) modeTabs else modeTabs.filter {
            it.title.contains(switcherQuery, ignoreCase = true) ||
                it.currentUrl.contains(switcherQuery, ignoreCase = true)
        }
        if (shown.isEmpty()) {
            val empty = Ui.textRow(
                this,
                when {
                    switcherQuery.isNotEmpty() -> "「$switcherQuery」に一致するタブはありません"
                    switcherSecretMode -> "シークレットタブはありません"
                    else -> "タブはありません"
                },
                14f, Colors.ON_SURFACE_VARIANT
            )
            empty.gravity = Gravity.CENTER
            val p = GridLayout.LayoutParams()
            p.columnSpec = GridLayout.spec(0, 2)
            p.width = resources.displayMetrics.widthPixels - Ui.dp(this, 20)
            p.height = Ui.dp(this, 120)
            empty.layoutParams = p
            switcherGrid.addView(empty)
            return
        }
        val cardWidth = (resources.displayMetrics.widthPixels / 2) - Ui.dp(this, 26)
        // The thumbnail keeps only a slim frame inside the card and uses the
        // same aspect ratio as the capture, so nothing is cropped sideways.
        val thumbHeight = ((cardWidth - Ui.dp(this, 12)) * BrowserTab.THUMB_ASPECT).toInt()
        for (t in shown) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(0, 0, 0, 0)
            val isActive = t === activeTab
            card.background = Ui.roundBg(if (isActive) Colors.CARD_ACTIVE else Colors.SURFACE, 16f, this)
            val params = GridLayout.LayoutParams()
            params.width = cardWidth
            params.setMargins(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5))
            card.layoutParams = params

            // Tab card toolbar: favicon / page title (one line, ellipsized so
            // it never runs under the close button) / close at the right edge.
            val headRow = LinearLayout(this)
            headRow.orientation = LinearLayout.HORIZONTAL
            headRow.gravity = Gravity.CENTER_VERTICAL
            headRow.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 8))
            val favBox = FrameLayout(this)
            favBox.background = Ui.roundBg(if (isActive) Colors.PRIMARY_CONTAINER else Colors.SURFACE_CONTAINER_LOW, 12f, this)
            favBox.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24))
            val favBitmap = t.favicon
            if (favBitmap != null) {
                val favImg = ImageView(this)
                favImg.setImageBitmap(favBitmap)
                favImg.scaleType = ImageView.ScaleType.FIT_CENTER
                val fp = FrameLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16))
                fp.gravity = Gravity.CENTER
                favImg.layoutParams = fp
                favBox.addView(favImg)
            } else {
                val favIcon = ImageView(this)
                favIcon.setImageResource(R.drawable.ic_globe)
                favIcon.setColorFilter(if (isActive) Colors.PRIMARY else Colors.ON_SURFACE_VARIANT)
                val fp = FrameLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14))
                fp.gravity = Gravity.CENTER
                favIcon.layoutParams = fp
                favBox.addView(favIcon)
            }
            headRow.addView(favBox)
            val titleView = Ui.textRow(this, t.title.ifBlank { "新しいタブ" }, 12f)
            titleView.maxLines = 1
            titleView.ellipsize = android.text.TextUtils.TruncateAt.END
            val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            titleParams.leftMargin = Ui.dp(this, 6)
            titleView.layoutParams = titleParams
            headRow.addView(titleView)
            val closeView = ImageView(this)
            closeView.setImageResource(R.drawable.ic_close)
            closeView.setColorFilter(Colors.ON_SURFACE_VARIANT)
            closeView.contentDescription = "タブを閉じる"
            val closeParams = LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24))
            closeParams.leftMargin = Ui.dp(this, 2)
            closeView.layoutParams = closeParams
            closeView.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3))
            closeView.setOnClickListener { closeTab(t) }
            headRow.addView(closeView)
            card.addView(headRow)

            // Page thumbnail under the card toolbar; nothing below it.
            val thumbFrame = FrameLayout(this)
            thumbFrame.background = Ui.roundBg(Colors.SURFACE_CONTAINER_HIGH, 10f, this)
            thumbFrame.clipToOutline = true
            val thumbParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbHeight)
            thumbParams.leftMargin = Ui.dp(this, 6)
            thumbParams.rightMargin = Ui.dp(this, 6)
            thumbParams.bottomMargin = Ui.dp(this, 6)
            thumbFrame.layoutParams = thumbParams
            // The tag lets the deferred capture find this frame
            // again and rebind it in place.
            thumbFrame.tag = t
            bindThumbFrame(thumbFrame, t)
            card.addView(thumbFrame)

            card.setOnClickListener {
                // A stale card for a tab closed since the grid was
                // built must do nothing: switching to a destroyed
                // tab would point activeTab at a dead WebView.
                if (!tabs.contains(t)) return@setOnClickListener
                t.noteUserNavigation()
                switchTo(t)
                hideSwitcher()
            }
            switcherGrid.addView(card)
        }
    }

    // ---------- Find ----------

    private var findWatcherAdded = false

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        showKeyboard()
        activeTab?.webView?.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            findCount.text = if (numberOfMatches > 0) "${activeMatchOrdinal + 1}/$numberOfMatches" else "0/0"
        }
        if (findWatcherAdded) return
        findWatcherAdded = true
        findInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                activeTab?.webView?.findAllAsync(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        activeTab?.webView?.clearMatches()
        findInput.text.clear()
        findCount.text = ""
        hideKeyboard()
    }

    // ---------- Misc ----------

    // Retained but currently unreachable: the 2026-11-22 menu
    // spec (MenuSpec) has no About entry. The feature itself is
    // kept by the owner's instruction (removal from the menu is
    // not removal of the feature).
    private fun showAbout() {
        // The version comes from the package, never a second
        // hard-coded copy that can drift from build.sh.
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("PageLoupe")
            .setMessage("バージョン $version\n\nブラウザエンジンは Android WebView（Chromium）。DevTools は本物の Chrome DevTools フロントエンドを下部パネルに表示します（フロントエンドの読み込みにインターネット接続が必要、パネル上端のドラッグで伸縮可能）。\nリモートデバッグ（PC の chrome://inspect からの接続）は初期設定オフで、⋮メニューから切り替えます。")
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * End an in-progress address-bar edit: drop the input's focus
     * (the focus listener clears [addressEditing] and refreshes
     * the bar) and hide the keyboard. The typed text is never
     * loaded by this call -- a caller that means to commit (the
     * IME GO action) loads it first. Every context change that
     * replaces what the edit was about -- a tab switch, a
     * user-initiated navigation, an external link taking over --
     * ends the edit through here, so no path can leave the
     * editing guard holding a stale URL on the bar (SEN-153).
     */
    private fun endAddressEdit() {
        if (!addressEditing) return
        addressInput.clearFocus()
        hideKeyboard()
    }

    private fun refreshAddressBar() {
        if (addressEditing) return
        val t = activeTab ?: return
        addressInput.setText(t.displayUrl())
        updateAddressBarIndicators(t)
    }

    /** The lock icon and toolbar buttons for [t]'s current URL:
     *  the part of the address-bar display that the normal
     *  refresh and the editing resync must keep in step. */
    private fun updateAddressBarIndicators(t: BrowserTab) {
        lockIcon.visibility = if (t.currentUrl.startsWith("http")) View.VISIBLE else View.GONE
        // The lock vouches for the page ON SCREEN (SEC-173):
        // the single predicate lives in PageFeaturePolicy
        // (SEN-359) — committed https, and the displayed URL
        // is that committed page. The neutral icon stands
        // while a navigation is uncommitted.
        // A committed cleartext page is marked as such (audit
        // hardening, Chrome's "Not secure"): with only the
        // search icon it would look like a fresh tab, and the
        // user could not tell the page on screen is unprotected.
        val lockShown = PageFeaturePolicy.showsLock(
            t.currentUrl, t.lastCommittedUrl,
            sequenceFromHome = t.loadSequenceFromHome,
            errorPage = t.lastCommitWasErrorPage
        )
        val warningShown = !lockShown && PageFeaturePolicy.showsCleartextWarning(
            t.currentUrl, t.lastCommittedUrl,
            sequenceFromHome = t.loadSequenceFromHome,
            errorPage = t.lastCommitWasErrorPage
        )
        lockIcon.setImageResource(
            when {
                lockShown -> R.drawable.ic_lock
                warningShown -> R.drawable.ic_alert_triangle
                else -> R.drawable.ic_search
            }
        )
        // The state is also named, not just drawn (audit
        // hardening): shape alone does not reach a screen
        // reader, and the warning takes the warn tint so it
        // differs from the neutral states at a glance too.
        lockIcon.setColorFilter(
            if (warningShown) Colors.WARN else Colors.ON_SURFACE_VARIANT
        )
        lockIcon.contentDescription = when {
            lockShown -> "保護された接続"
            warningShown -> "保護されていない接続"
            else -> null
        }
        refreshToolbarButtons()
    }

    /** Start-page state for the toolbar buttons: reload and DevTools have
     *  nothing to act on there, so both are greyed out and disabled. While
     *  the DevTools panel is open its button keeps the active look and
     *  stays tappable, so the panel can still be closed from it. Runs on
     *  every address bar refresh (URL change, tab switch) and on DevTools
     *  open/close. */
    private fun refreshToolbarButtons() {
        val t = activeTab ?: return
        val home = BrowserTab.isHomeUrl(t.currentUrl)
        reloadBtn.isEnabled = !home
        reloadBtn.setColorFilter(if (home) Colors.OUTLINE else Colors.ICON)
        if (dtOpen) {
            devToolsBtn.isEnabled = true
            Ui.setButtonActive(devToolsBtn, true)
        } else {
            devToolsBtn.isEnabled = !home
            Ui.setButtonActive(devToolsBtn, false)
            if (home) devToolsBtn.setColorFilter(Colors.OUTLINE)
        }
    }

    /**
     * Park the focus on the focus sink (buildUi) and log
     * whether the sink actually took it, so the hand-off
     * can be verified on a device (SEN-159).
     */
    private fun moveFocusToSink() {
        val ok = focusSink.requestFocus()
        DebugLog.log("focus", "sink requestFocus -> $ok")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addressInput.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---------- BrowserTab.Listener ----------

    override fun onTitleChanged(tab: BrowserTab) {
        if (switcher.visibility == View.VISIBLE) renderSwitcher()
        // Fill in a history title that had not landed when its
        // page committed (SEN-154). Three guards keep the fill
        // truthful:
        // - titleArrivedSinceCommit must be set: this callback
        //   also fires for favicon arrivals (the switcher card
        //   refresh), at which point tab.title may still be the
        //   PREVIOUS page's title — filling from it would pin a
        //   wrong title for good, since the fill is one-shot.
        // - The newest entry must still be untitled (fill-only):
        //   overwriting a recorded title would corrupt the
        //   previous entry when an SPA sets document.title just
        //   before pushState (the new page adopts that title at
        //   its own commit via the same flag), and re-recording
        //   on every change would rewrite the whole history
        //   file for pages whose titles tick (unread counters,
        //   timers).
        // - The entry must be the tab's last committed page.
        // - The title must be ATTRIBUTED to that page
        //   (TitleAttribution.belongs): going home leaves the
        //   committed URL pointing at the previous page while
        //   the start page's own title ("PageLoupe") arrives
        //   and sets the flag — without the attribution check
        //   that title would be filled into the previous
        //   page's entry (run-3 hardening). On the home load
        //   the attribution names the start page, never the
        //   committed URL, so the check fails closed there.
        // The flag is NOT consumed here: an SPA that pushStates
        // without changing its title still adopts the current
        // title at that commit.
        if (!tab.secret && tab.titleArrivedSinceCommit) {
            val newest = historyStore.newest
            if (newest != null && newest.title.isEmpty() &&
                newest.url == tab.lastCommittedUrl &&
                TitleAttribution.belongs(tab.titleUrl, newest.url) &&
                tab.title.isNotEmpty()
            ) {
                historyStore.record(newest.url, tab.title, newest.visitedAtMillis)
                persistHistory()
                if (::historyPage.isInitialized &&
                    historyPage.visibility == View.VISIBLE
                ) {
                    renderHistory()
                }
            }
        }
        // Bookmark fill-ins for the tab's committed page: a
        // bookmark saved before its title landed (or added by
        // hand with no title) adopts the title once — the same
        // arrived-flag guard as the history fill, so a previous
        // page's title can never be adopted — and a bookmark
        // without an icon picks up the page's icon when the
        // tab attributes it to this exact URL (faviconUrl).
        // Both are one-shot: a filled value is never
        // overwritten here.
        //
        // Normal tabs only, like the history fill above: the
        // fill-ins are implicit writes driven by a visit, and
        // a secret visit must leave no trace in a persistent
        // file (a title can carry login state — "Inbox (3) -
        // user@…"). Adding FROM a secret tab stays allowed:
        // that is the user's explicit act, like sharing. A
        // bookmark added there fills in on a later NORMAL
        // visit instead.
        if (!tab.secret) {
            val committed = tab.lastCommittedUrl
            val entry = if (committed.isNotEmpty()) {
                bookmarkStore.findByUrl(committed)
            } else {
                null
            }
            if (entry != null) {
                if (entry.title.isEmpty() && tab.titleArrivedSinceCommit &&
                    TitleAttribution.belongs(tab.titleUrl, committed) &&
                    tab.title.isNotEmpty()
                ) {
                    val result = bookmarkStore.updateBookmark(
                        entry.id, tab.title, entry.url, entry.folderId,
                    )
                    if (result is UpdateBookmarkResult.Updated) {
                        persistBookmarks()
                        if (::bookmarksPage.isInitialized &&
                            bookmarksPage.visibility == View.VISIBLE
                        ) {
                            renderBookmarks()
                        }
                    }
                }
                val icon = tab.favicon
                // An entry whose stored icon failed to decode
                // this session is re-captured too: the atomic
                // overwrite is its recovery path (a plain
                // flagged entry is left alone).
                if (icon != null && tab.faviconUrl == committed &&
                    (!entry.hasFavicon || entry.url in faviconLoadFailed)
                ) {
                    // The flag is confirmed inside saveFavicon
                    // once the file lands.
                    saveFavicon(entry.id, committed, icon)
                }
            }
        }
    }

    override fun onPageCommitted(tab: BrowserTab, url: String) {
        // A commit is what the address bar vouches for: refresh
        // its indicators NOW (SEN-358), for every tab kind —
        // the history recording below is normal-tabs-only, but
        // the lock must not wait for onPageFinished to catch
        // up. This runs BEFORE BrowserTab updates currentUrl
        // to the committed URL, so for a redirect, SPA or
        // history commit the icon transiently reads neutral
        // here; the onUrlChanged that immediately follows (the
        // same synchronous callback chain, so nothing draws in
        // between) re-evaluates it with both URLs aligned
        // (SEN-362). The refresh earns its keep on the plain
        // full-load commit, where currentUrl already names the
        // committed page and no onUrlChanged follows.
        if (tab === activeTab) updateAddressBarIndicators(tab)
        // Browsing history (SEN-154): normal tabs only — a
        // secret visit leaves no trace, including a restored
        // secret tab's loads. The start page and other internal
        // URLs are not web links and never record.
        if (tab.secret) return
        if (!isWebLink(url)) return
        // Which title belongs to the page being committed?
        // HistoryTitle decides (SEN-222): the tab's current
        // title is adopted at a commit only for a
        // same-document commit (an SPA set document.title
        // just before pushState) or when TitleAttribution
        // already pairs it with this URL — a title that
        // arrived while a load was in flight is the outgoing
        // page's and adopting it would pin a wrong title the
        // fill-in can never correct (it only fills empty
        // titles). Otherwise the title has not landed yet:
        // record an empty title and let onTitleChanged fill
        // it in when it arrives. Both tab flags still read as
        // they stood at the commit here: BrowserTab clears
        // them only after this callback returns.
        val title = HistoryTitle.atCommit(
            arrivedSinceCommit = tab.titleArrivedSinceCommit,
            loadStartedSinceCommit = tab.loadStartedSinceCommit,
            currentTitle = tab.title,
            titleUrl = tab.titleUrl,
            committedUrl = url,
            newestHistoryUrl = historyStore.newest?.url,
        )
        historyStore.record(url, title, System.currentTimeMillis())
        persistHistory()
        if (::historyPage.isInitialized &&
            historyPage.visibility == View.VISIBLE
        ) {
            renderHistory()
        }
    }

    override fun onUrlChanged(tab: BrowserTab) {
        // The saved session follows the tab's URL. This callback
        // runs after currentUrl was updated in every path that
        // fires it (page start, visited-history, page finish) —
        // unlike onPageCommitted, which runs before the update
        // and saved the previous URL for SPA navigations
        // (SEN-154 review).
        saveTabSession()
        if (tab !== activeTab) return
        if (addressEditing) resyncEditingAddressBar() else refreshAddressBar()
    }

    /**
     * Keep an untouched address-bar edit truthful. While the user
     * has typed into the bar, its text is their input and stays
     * untouched until the edit ends. But as long as the text is
     * still exactly what the focus gain put there, the bar is
     * only DISPLAYING the current page in full-URL form: when a
     * navigation the edit survives (a page-driven one) changes
     * the page, re-display the new page -- URL text and lock icon
     * together -- so the bar never shows a previous page's URL
     * and lock over the page actually on screen (SEN-153).
     */
    private fun resyncEditingAddressBar() {
        val t = activeTab ?: return
        // The indicators track the real page on every change,
        // typed or not: only the text is the user's to protect.
        updateAddressBarIndicators(t)
        if (addressInput.text.toString() != addressFocusText) return
        val full = t.currentUrl.takeUnless { BrowserTab.isHomeUrl(it) } ?: ""
        if (addressInput.text.toString() != full) {
            addressInput.setText(full)
            addressInput.setSelection(0, full.length)
            addressFocusText = full
        }
    }

    override fun onProgressChanged(tab: BrowserTab, progress: Int) {
        if (tab !== activeTab) return
        progressBar.progress = progress
        progressBar.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
    }

    override fun onDocumentReplaced(tab: BrowserTab) {
        // A committed new document replaces the DOM a
        // translation run refers to (and holds the originals
        // in): cancel any run and drop the translated state
        // for this tab. (Page-started would fire for loads
        // that never commit, falsely resetting a live document.)
        pageTranslators[tab]?.onNewDocument()
    }

    override fun onPageStarted(tab: BrowserTab, userInitiated: Boolean) {
        if (tab === activeTab) {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            if (addressEditing && userInitiated) {
                // A user-initiated navigation of the displayed
                // tab (a link or start-page card tap, the reload
                // button or the menu's home button) replaces the
                // URL the edit was about, so the edit ends with
                // it (SEN-153).
                // Page-driven navigations do NOT end the edit:
                // a page must not be able to wipe what the user
                // is typing by navigating under it; the bar
                // refreshes when the edit ends.
                endAddressEdit()
            }
        }
    }

    override fun onSecretProfileApplyFailed(tab: BrowserTab) {
        // The tab is not in the tab list yet (this fires from its
        // init): only surface the fact, once per tab (audit
        // hardening — an activity-wide flag would keep a second,
        // unrelated failure on another tab silent for the whole
        // session). The flag lives on the tab itself, so it dies
        // with the tab. The tab keeps working on the shared
        // default profile.
        if (tab.secretIsolationFailureNotified) return
        tab.secretIsolationFailureNotified = true
        android.widget.Toast.makeText(
            this,
            "シークレットタブの分離に失敗しました。通常タブとデータが共有されます",
            android.widget.Toast.LENGTH_LONG
        ).show()
        DebugLog.log("tab", "secret isolation failure notified")
    }

    override fun onRenderProcessGone(tab: BrowserTab) {
        // A renderer died (OOM or a crash in the page). Rebuild just
        // that tab; a tab already closed needs nothing.
        if (!tabs.contains(tab)) return
        DebugLog.log("tab", "renderer gone idx=${tabs.indexOf(tab)}; recreating the tab")
        if (dtOpen && (tab === activeTab || tab === dtTargetTab)) {
            // The renderer is shared, so the frontend WebView may be
            // dead too (its own callback may not have run yet): close
            // WITHOUT unloading it. Its own onRenderProcessGone, when
            // it arrives, replaces it; if it never arrives, the next
            // open loads a fresh frontend URL anyway.
            closeRealDevTools(unloadWebView = false)
        }
        recreateTabAfterCrash(tab)
        if (switcher.visibility == View.VISIBLE) renderSwitcher()
    }

    // ---------- Page features ----------
    // Downloads, file picking, capture permissions, HTML5
    // fullscreen. Each interaction is held in a PendingSlot,
    // whose take() hands the pending value
    // to exactly one answerer — the framework callback attached
    // to it is answered exactly once on every path (superseded,
    // cancelled, tab destroyed, activity destroyed), and
    // beforeTabDestroyed() flushes a dying tab's pendings so no
    // page is left waiting on a dead WebView.

    private data class PendingDownload(
        val tab: BrowserTab,
        val ask: Downloads.Ask,
        var dialog: android.app.AlertDialog? = null,
        /** When the confirmation appeared (SEN-305's arm delay). */
        var shownAtMs: Long = 0
    )

    private val pendingDownload = PendingSlot<PendingDownload>()

    // Page translation (v0.41.0): one PageTranslator per tab,
    // created on first use and dropped with the tab. The two
    // dialog fields are the translation UI's modal presence;
    // the SEN-356 predicate below reads them directly (each is
    // nulled by its own dismiss listener).
    private val pageTranslators = HashMap<BrowserTab, PageTranslator>()
    private var translateConsentDialog: AlertDialog? = null
    private var translateProgressDialog: AlertDialog? = null
    private var translateProgressMessage: TextView? = null

    private fun translatorFor(tab: BrowserTab): PageTranslator =
        pageTranslators.getOrPut(tab) { PageTranslator(tab, this) }

    override fun downloadConfirmationPending(): Boolean = pendingDownload.isPending()

    /**
     * Disarm a page-triggered approval dialog's positive button
     * for the arm delay (SEN-305) and re-arm it afterwards,
     * returning the show timestamp the click handler's
     * [PageFeaturePolicy.approvalArmed] check compares against.
     * The page controls when these dialogs appear and the
     * button sits at a fixed spot, so without this a tap aimed
     * at the page — one beat of a rhythm — approves before the
     * user has seen what they are approving. The refusal side
     * (negative button, back, outside touch) stays live from
     * the first moment: protection must never trap an approval
     * the user wants to refuse.
     */
    private fun armApprovalButton(dialog: android.app.AlertDialog): Long {
        val shownAt = android.os.SystemClock.uptimeMillis()
        val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        positive.isEnabled = false
        positive.postDelayed({
            if (dialog.isShowing) positive.isEnabled = true
        }, PageFeaturePolicy.APPROVAL_ARM_DELAY_MS)
        return shownAt
    }

    /** The one download-refusal toast instance, reused across refusals. */
    private var downloadRefusalToast: android.widget.Toast? = null

    override fun onDownloadRefused(tab: BrowserTab, busy: Boolean) {
        // Only the tab on screen may put anything on it: a
        // background tab's refusals are logged by the caller and
        // never shown — the on-screen-only rule, applied to notices too.
        if (tab !== activeTab) return
        downloadRefusalToast?.cancel()
        downloadRefusalToast = android.widget.Toast.makeText(
            this,
            if (busy) "別のダウンロードの確認中です" else "このファイルはダウンロードできません",
            android.widget.Toast.LENGTH_LONG
        )
        downloadRefusalToast?.show()
    }

    private fun showTabLimitToast() {
        android.widget.Toast.makeText(
            this,
            "タブの上限（$MAX_TABS 件）に達しているため、リンクを開けませんでした",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Close every overlay before an incoming tab (an external
     * link, a page popup) becomes the foreground tab: content
     * landing behind the menu, the switcher or a full-screen
     * page would look like nothing happened (SEN-154 review),
     * and a fullscreen video from the previous tab would stay
     * on top of the new page, looking like its own display.
     */
    private fun closeOverlaysForIncomingTab() {
        hideMenu()
        hideSwitcher()
        if (::historyPage.isInitialized) hideHistoryPage()
        if (::clearDataPage.isInitialized) hideClearDataPage()
        if (::settingsPage.isInitialized) hideSettingsPage()
        if (::exportPage.isInitialized) hideExportPage()
        if (::bookmarksPage.isInitialized) hideBookmarksPage()
        hideCustomView()
        if (findBar.visibility == View.VISIBLE) closeFind()
    }

    /**
     * Opening an overlay (the menu, the switcher, a
     * full-screen page) moves the user's context away from
     * the page, so the visible tab's undecided popup expires
     * with it: a page must not be able to time a late popup
     * decision to dismiss the overlay the user is working in
     * and take the foreground (SEC-154).
     */
    private fun expireActiveTabPopupProbes() {
        activeTab?.expirePopupProbes()
    }

    /**
     * Whether any full-screen overlay (the menu, the
     * switcher, a full-screen page, a fullscreen video) is
     * currently up — the same enumeration
     * [closeOverlaysForIncomingTab] closes. While one is up
     * the page cannot be tapped, so a popup request arriving
     * then can only be script riding on a leftover tap
     * activation (SEN-325).
     */
    // Called only from canOpenPopup, which runs on a page
    // tap — always after the UI is built — so the lateinit
    // menu and switcher need no isInitialized guard here
    // (SEN-328).
    private fun isAnyOverlayVisible(): Boolean {
        if (menuScrim.visibility == View.VISIBLE) return true
        if (switcher.visibility == View.VISIBLE) return true
        if (::historyPage.isInitialized && historyPage.visibility == View.VISIBLE) return true
        if (::clearDataPage.isInitialized && clearDataPage.visibility == View.VISIBLE) return true
        if (::settingsPage.isInitialized && settingsPage.visibility == View.VISIBLE) return true
        if (::exportPage.isInitialized && exportPage.visibility == View.VISIBLE) return true
        if (::bookmarksPage.isInitialized && bookmarksPage.visibility == View.VISIBLE) return true
        if (isFullscreenShowing()) return true
        return false
    }

    /**
     * The one answer to "is a user-facing modal already
     * awaiting the user" (SEN-356): a download confirmation,
     * a permission prompt, a JS dialog, or a translation
     * dialog (model-download consent or run progress, v0.41.0).
     * Every page-driven
     * path that would put a new modal in front of the user —
     * popups, JS dialogs, download confirmations, permission
     * requests — consults this single predicate instead of
     * re-listing the slots, so a path added later cannot
     * silently miss an exclusion the others have. (The slots
     * are declared across this class; the predicate sits
     * above their first consumer, and Kotlin resolves the
     * references regardless of declaration order.)
     */
    private fun isUserModalPending(): Boolean =
        otherModalPending() ||
            translateConsentDialog != null || translateProgressDialog != null

    /**
     * The non-translation modals. The translation progress
     * dialog consults this (not the full predicate): it must
     * never stack on another feature's prompt, while its own
     * presence is what makes the full predicate refuse
     * page-driven modals for the run's duration — deliberate:
     * a page that fires alert() or a permission request
     * mid-translation is refused rather than interrupting the
     * run (the SEN-356 pattern; v0.41.0 review).
     */
    private fun otherModalPending(): Boolean =
        pendingDownload.isPending() || pendingPermission.isPending() ||
            pendingJsDialog.isPending()

    /**
     * Which modals are pending, for the refusal logs: the
     * predicate says THAT one is up, this says WHICH, so a
     * refusal in the log names its blocker — including the
     * caller's own kind, which the slots' offer backstops can
     * no longer distinguish now that the predicate runs first
     * (SEN-371).
     */
    private fun pendingModalKinds(): String {
        val kinds = ArrayList<String>(4)
        if (pendingDownload.isPending()) kinds.add("download")
        if (pendingPermission.isPending()) kinds.add("permission")
        if (pendingJsDialog.isPending()) kinds.add("jsdialog")
        if (translateConsentDialog != null || translateProgressDialog != null) kinds.add("translate")
        return kinds.joinToString("+")
    }

    override fun canOpenPopup(tab: BrowserTab): Boolean =
        // The tab on screen, while the activity is in the
        // foreground: after onStop the page's script keeps
        // running, and a tap's transient activation outlives
        // the stop by seconds — a popup STARTED then must not
        // slip past the backgrounding expiry (SEN-318).
        // (activityResumed already implies !activityStopped;
        // both are stated so the foreground requirement reads
        // at a glance — SEN-322.) The editing and prompt
        // conditions mirror the expiry events on the other
        // side: a popup must not START while the user is
        // typing a URL or reading a prompt either (SEN-327).
        tab === activeTab && activityResumed && !activityStopped &&
            !isAnyOverlayVisible() && !addressEditing &&
            !isUserModalPending()

    override fun onPopupTab(tab: BrowserTab, url: String) {
        // A late probe callback must not open a tab for an
        // opener that is already gone (SEN-310), and popups
        // respect the same tab limit as external links.
        if (tab !in tabs) return
        // A modal that came up after the probe was created
        // (an approval, a translation consent) ends the
        // popup unseen (run-3 hardening): canOpenPopup gates
        // the probe's CREATION on no modal pending, and this
        // gates its DECISION on the same predicate, so the
        // window between the two is closed too.
        if (isUserModalPending()) return
        if (tabs.size >= MAX_TABS) {
            showTabLimitToast()
            return
        }
        // The new tab must not land behind an overlay (the
        // menu, the switcher, a full-screen page): the same
        // closing the external-link path does (SEN-319).
        closeOverlaysForIncomingTab()
        // The popup tab inherits the OPENER's secrecy, stated
        // explicitly: newTab's default copies the ACTIVE tab's
        // secret, and the opener is not necessarily the tab on
        // screen in every flow that reaches here.
        val t = newTab(secret = tab.secret, activate = true)
        if (tab.secret && t.secretProfileApplied != tab.secretProfileApplied) {
            // SEC-146's rule, at the tab the popup opens too:
            // the probe was refused for running on a different
            // profile footing than its opener, and the tab it
            // opens gets the same refusal — loading the
            // popup's URL into a tab whose profile application
            // disagreed with the opener's would put a secret
            // page's navigation on the wrong profile. The tab
            // opens on the start page instead (run-3
            // hardening).
            DebugLog.log("nav", "popup tab opened on home (profile mismatch)")
            t.loadHome()
            return
        }
        t.loadSavedUrl(url)
    }

    override fun onDownloadRequested(tab: BrowserTab, ask: Downloads.Ask) {
        if (tab !== activeTab) {
            // Only the tab on screen may ask (the same rule as
            // permission requests): the dialog names a file and a
            // host, and a background tab's prompt would be mistaken
            // for the visible page's.
            DebugLog.log("download", "refused (background tab), secret=${ask.secret}")
            return
        }
        if (tab.downloadPromptSuppressed) {
            // Backstop: BrowserTab's listener checks this first
            // and never builds the Ask, so this is unreachable in
            // practice — kept so the rule holds even if a future
            // caller bypasses the listener path. (A page must not
            // trap the user in a loop of dialogs.)
            DebugLog.log("download", "refused (suppressed after refusal), secret=${ask.secret}")
            return
        }
        if (isUserModalPending()) {
            // Never stack over another modal (SEN-356): a
            // permission prompt or a JS dialog is already the
            // user's context, and a confirmation raised over it
            // would take the taps meant for that modal — the
            // tapjacking shape SEN-305's arm delay alone cannot
            // cover, since the delay runs from THIS dialog's
            // own showing. The download is refused, not queued:
            // the page can offer it again once the modal is
            // answered. (BrowserTab's pre-check covers the
            // own-slot case with the busy toast; this is the
            // cross-modal backstop.)
            DebugLog.log("download", "refused (another modal is pending: ${pendingModalKinds()}), secret=${ask.secret}")
            return
        }
        val entry = PendingDownload(tab, ask)
        if (!pendingDownload.offer(entry)) {
            // One confirmation at a time. BrowserTab pre-checks
            // this and shows the busy toast, so this backstop is
            // log-only.
            DebugLog.log("download", "refused (confirmation pending), secret=${ask.secret}")
            return
        }
        // The host only, through the shared display rule
        // (PageFeaturePolicy.displayHost): anything unparseable,
        // huge, or carrying display trickery shows as unknown
        // rather than going on screen verbatim.
        val host = PageFeaturePolicy.displayHost(ask.url) ?: "不明"
        val message = StringBuilder()
        message.append(ask.fileName).append('\n').append("取得元: ").append(host)
        if (ask.url.startsWith("http://")) {
            // Cleartext: on the same network the body can be
            // swapped in transit. The user decides knowing that.
            message.append("\n\nこのダウンロードは暗号化されません (http)。" +
                "同じネットワーク上の第三者に内容を差し替えられる可能性があります。")
        }
        // A download whose navigation was not for the page's
        // main frame gets no cookie (SEN-285): say which case
        // it is, so the approval is an informed one — a
        // subframe the user never interacted with must not
        // borrow the page's session silently, and a frame that
        // could not be confirmed (a POST navigation leaves no
        // record) is named as such rather than blamed on a
        // frame.
        when (ask.frame) {
            PageFeaturePolicy.DownloadFrame.SUBFRAME ->
                message.append("\n\nこのダウンロードは、ページに埋め込まれたフレームへの" +
                    "遷移によるものです。ログイン情報 (Cookie) は付けずに保存します。")
            PageFeaturePolicy.DownloadFrame.UNKNOWN ->
                message.append("\n\nこのダウンロードの開始元を確認できなかったため、" +
                    "ログイン情報 (Cookie) は付けずに保存します。")
            PageFeaturePolicy.DownloadFrame.MAIN_FRAME -> {}
        }
        // The confirmation dialog is the user's context
        // now: a pending popup from this tab must not decide
        // behind it and swap the page under the dialog
        // (SEC-155).
        tab.expirePopupProbes()
        // The approval itself, run only from the post-show
        // click handler installed below (SEN-345).
        val approve: () -> Unit = {
            // Identity-checked take: this may only ever answer
            // its OWN entry.
            val approved = pendingDownload.takeIf { it === entry }
            if (approved != null) {
                // The session cookie is read only now, at
                // approval, and only when the Ask's precomputed
                // gate allows it (PageFeaturePolicy
                // .shouldAttachCookie: an https download whose
                // navigation was for the main frame, from an
                // https page on the same host or its subdomain —
                // everything else fails closed). A download the
                // user refuses never touches the cookie store
                // at all. What cannot be controlled is the
                // system DownloadManager's own redirect handling
                // after the hand-off; that residual is recorded
                // in REVIEW.md.
                val cookie = if (approved.ask.cookieAllowed) {
                    SecretProfile.downloadCookie(
                        approved.tab.webView, approved.ask.url,
                        approved.ask.secret, approved.tab.secretProfileApplied
                    )
                } else {
                    null
                }
                Downloads.enqueue(this, approved.ask.copy(cookie = cookie))
            }
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("ダウンロード")
            .setMessage(message.toString())
            // No builder listener: the framework's button
            // handler dismisses after ANY click, even one the
            // arm delay refuses (SEN-345), which would close
            // the dialog while leaving the entry pending. The
            // real handler is installed on the button after
            // show(), where dismissal is explicit.
            .setPositiveButton("保存", null)
            .setNegativeButton("キャンセル") { _, _ ->
                if (pendingDownload.takeIf { it === entry } != null) {
                    entry.tab.downloadPromptSuppressed = true
                    DebugLog.log("download", "refused by user, secret=${ask.secret}")
                }
            }
            .setOnCancelListener {
                if (pendingDownload.takeIf { it === entry } != null) {
                    entry.tab.downloadPromptSuppressed = true
                    DebugLog.log("download", "confirmation cancelled, secret=${ask.secret}")
                }
            }
            .create()
        entry.dialog = dialog
        try {
            dialog.show()
            entry.shownAtMs = armApprovalButton(dialog)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    // SEN-305/SEN-345: an approval inside the
                    // arm delay is not the user's — a true
                    // no-op (no dismiss, nothing consumed), so
                    // the dialog stays up for a deliberate
                    // answer. The button is disabled during the
                    // delay anyway; this is the enforcement if
                    // a click still arrives (a view state change,
                    // an accessibility performClick).
                    if (!PageFeaturePolicy.approvalArmed(
                            entry.shownAtMs, android.os.SystemClock.uptimeMillis()
                        )
                    ) return@setOnClickListener
                    // The dismissal is unconditional (SEN-351):
                    // the entry is consumed at the head of
                    // approve(), so a failure inside it must not
                    // strand a live dialog over a released slot.
                    try {
                        approve()
                    } catch (e: Exception) {
                        DebugLog.log(
                            "download",
                            "approval failed: ${e.javaClass.simpleName}, secret=${ask.secret}"
                        )
                    } finally {
                        dialog.dismiss()
                    }
                }
        } catch (e: Exception) {
            // No dialog, no pending: dismiss what is up (a
            // failure after show() would otherwise strand a
            // handler-less dialog, SEN-352) and release the
            // slot, or every later download would be refused as
            // "pending" forever.
            if (dialog.isShowing) dialog.dismiss()
            pendingDownload.takeIf { it === entry }
            DebugLog.log(
                "download",
                "confirmation dialog failed: ${e.javaClass.simpleName}, secret=${ask.secret}"
            )
        }
    }

    private data class PendingFilePick(
        val tab: BrowserTab,
        val callback: android.webkit.ValueCallback<Array<android.net.Uri>>
    )

    private val pendingFilePick = PendingSlot<PendingFilePick>()

    override fun onShowFileChooser(
        tab: BrowserTab,
        callback: android.webkit.ValueCallback<Array<android.net.Uri>>,
        params: android.webkit.WebChromeClient.FileChooserParams
    ): Boolean {
        if (tab !== activeTab) {
            // Only the tab on screen may raise the system
            // picker — the same rule as downloads and
            // permission requests (SEN-304). A background tab's
            // picker would be mistaken for the visible page's,
            // and the pick's result would go to a page the user
            // is not looking at. (On this WebView a hidden page
            // does not even reach this callback; the gate keeps
            // the rule true where it does.)
            DebugLog.log("file", "chooser refused (background tab), secret=${tab.secret}")
            answerFilePick(PendingFilePick(tab, callback), null)
            return true
        }
        if (!activityResumed || activityStopped) {
            // The same foreground requirement the popup and
            // JS-dialog paths state (run-3 hardening): a
            // page's tap activation can outlive the activity's
            // stop by seconds, and a picker raised while the
            // app is stopped would appear over whatever the
            // user moved on to, for a page they may no longer
            // be looking at.
            DebugLog.log("file", "chooser refused (activity not in foreground), secret=${tab.secret}")
            answerFilePick(PendingFilePick(tab, callback), null)
            return true
        }
        // A new pick supersedes a pending one: answer the old
        // callback with null so its page is not left hanging.
        // (In practice a second pick cannot even be launched while
        // the picker activity is on top — the activity is stopped
        // then — so the slot's take in onActivityResult always
        // meets the pick that launched it.)
        pendingFilePick.replace(PendingFilePick(tab, callback))?.let {
            answerFilePick(it, null)
        }
        return try {
            startActivityForResult(fileChooserIntent(params), REQ_FILE_CHOOSER)
            true
        } catch (e: Exception) {
            DebugLog.log("file", "chooser intent failed: ${e.javaClass.simpleName}")
            pendingFilePick.take()?.let { answerFilePick(it, null) }
            // Already answered above; returning false would invite
            // a second handling of the same callback.
            true
        }
    }

    /**
     * The intent a file pick launches with (SEC-164): for the
     * ordinary open modes, ACTION_OPEN_DOCUMENT rather than
     * the params' ACTION_GET_CONTENT, and — when the system's
     * own handler for it resolves — PINNED to that handler's
     * package (SEC-170). The pin is the wall: no other app can
     * answer, so no other app can mint result URIs at all.
     * Resolving with MATCH_SYSTEM_ONLY finds the device's
     * system default WITHOUT hardcoding a package name, so the
     * OEM variance that ruled out a hardcoded pin (SEN-355)
     * does not apply. When nothing resolves, the intent stays
     * unpinned and the backstops carry the pick: the result
     * filter (PageFeaturePolicy.isPickableResultUri) judges
     * whatever comes back, whoever sent it. Modes other than
     * open keep the framework's own intent, with the same
     * filter behind them.
     */
    private fun fileChooserIntent(
        params: android.webkit.WebChromeClient.FileChooserParams
    ): android.content.Intent {
        val mode = params.mode
        if (mode != android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN &&
            mode != android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        ) {
            return params.createIntent()
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
        // Only MIME-shaped accept tokens are declared
        // (SEN-354): an extension token is not a type the
        // picker UI can filter by.
        val mimes = PageFeaturePolicy.pickerMimeTypes(
            params.acceptTypes?.toList().orEmpty()
        )
        intent.type = mimes.firstOrNull() ?: "*/*"
        if (mimes.size > 1) {
            intent.putExtra(
                android.content.Intent.EXTRA_MIME_TYPES, mimes.toTypedArray()
            )
        }
        if (mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val systemHandler = try {
            packageManager.resolveActivity(
                intent,
                android.content.pm.PackageManager.MATCH_SYSTEM_ONLY or
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
        // "android" is the ResolverActivity, not a picker: it
        // comes back when several system handlers exist with
        // no default, and pinning to it would strand the pick
        // in a chooser that cannot answer as a documents UI
        // (SEN-357). It — like an unresolved handler — leaves
        // the intent unpinned, with the result filter behind
        // it. A pinned launch that fails is NOT retried
        // unpinned (the caller's catch answers null): silently
        // reopening the deputy path in exactly the environments
        // that behave unusually is the wrong trade, and the
        // failure is visible in the log below.
        var pinned = false
        if (systemHandler != null && systemHandler != packageName &&
            systemHandler != "android"
        ) {
            intent.setPackage(systemHandler)
            pinned = true
        }
        DebugLog.log(
            "file",
            "picker system handler: ${systemHandler ?: "unresolved"}, pinned=$pinned"
        )
        return intent
    }

    private fun answerFilePick(pick: PendingFilePick, result: Array<android.net.Uri>?) {
        try {
            pick.callback.onReceiveValue(result)
        } catch (_: Exception) {
            // The requesting WebView may already be gone.
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EXPORT_TREE) {
            exportPickerOpen = false
            val names = pendingExportNames
            pendingExportNames = null
            if (exportMoveInFlight) {
                // A pick returning while a move copies through
                // the current grant must not swap the
                // destination under it: applying it would
                // release the grant in use. The pick is
                // dropped; choosing again after the move works.
                android.widget.Toast.makeText(
                    this,
                    "書き出し中です。終わってから保存先を変えてください",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri != null) {
                if (!android.provider.DocumentsContract.isTreeUri(uri)) {
                    // The contract is a tree; anything else (a
                    // document from a non-conforming picker) is
                    // not a destination this flow can use.
                    DebugLog.log("export", "picker returned a non-tree uri")
                    android.widget.Toast.makeText(
                        this,
                        "保存先として使えないフォルダでした",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    val granted = try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        true
                    } catch (e: Exception) {
                        DebugLog.log(
                            "export",
                            "folder grant failed: ${e.javaClass.simpleName}"
                        )
                        false
                    }
                    if (granted) {
                        updateExportFolder(uri)
                        if (::exportPage.isInitialized &&
                            exportPage.visibility == View.VISIBLE
                        ) {
                            refreshExportPage()
                        }
                        if (names != null) {
                            runExportMove(names)
                        } else {
                            android.widget.Toast.makeText(
                                this,
                                "保存先を設定しました",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        android.widget.Toast.makeText(
                            this,
                            "保存先を保存できませんでした",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        if (requestCode == REQ_FILE_CHOOSER) {
            pendingFilePick.take()?.let {
                val parsed = android.webkit.WebChromeClient.FileChooserParams
                    .parseResult(resultCode, data)
                // The picker's answer is data, not authority
                // (SEN-302): only content: URIs may reach the
                // upload machinery. A file: result would be
                // opened by Chromium with THIS app's UID — the
                // picker cannot read the app's private files
                // itself, but the upload path can, so an
                // unfiltered result turns any picker into a
                // deputy for the app's whole private directory.
                // Dropped URIs are counted, never logged: a
                // result can name a private path.
                val kept = parsed?.filter { uri ->
                    // The authority is judged as the resolver
                    // sees it — Uri.getAuthority(), decoded —
                    // not as the raw string spells it (SEC-169).
                    PageFeaturePolicy.isPickableResultUri(uri.toString(), uri.authority)
                }
                if (parsed != null && kept != null && kept.size != parsed.size) {
                    DebugLog.log(
                        "file",
                        "picker results filtered: ${parsed.size - kept.size} dropped"
                    )
                }
                // An all-dropped result is a cancellation, not
                // an empty selection (SEN-348): null is the one
                // "no files" answer the callback contract has.
                answerFilePick(it, kept?.takeIf { k -> k.isNotEmpty() }?.toTypedArray())
            }
        }
    }

    /**
     * A JavaScript dialog awaiting the user. [kind] is null for
     * prompt(), whose result is the JsPromptResult itself (a
     * JsResult subclass) and whose answer carries the input's
     * text. Every answer funnels through the identity-checked
     * take on [pendingJsDialog], so each result is answered
     * exactly once however the dialog ends.
     */
    private data class PendingJsDialog(
        val tab: BrowserTab,
        val kind: PageFeaturePolicy.JsDialogKind?,
        val result: android.webkit.JsResult,
        var dialog: android.app.AlertDialog? = null,
        var input: android.widget.EditText? = null
    )

    private val pendingJsDialog = PendingSlot<PendingJsDialog>()

    override fun onJsDialog(
        tab: BrowserTab,
        kind: PageFeaturePolicy.JsDialogKind,
        url: String?,
        message: String?,
        result: android.webkit.JsResult
    ) {
        presentJsDialog(PendingJsDialog(tab, kind, result), url, message, null)
    }

    override fun onJsPrompt(
        tab: BrowserTab,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: android.webkit.JsPromptResult
    ) {
        presentJsDialog(PendingJsDialog(tab, null, result), url, message, defaultValue)
    }

    private fun presentJsDialog(
        entry: PendingJsDialog,
        url: String?,
        message: String?,
        defaultValue: String?
    ) {
        if (entry.tab !== activeTab || !activityResumed || activityStopped) {
            // Only the tab on screen may put a dialog up
            // (SEN-301): the framework's built-in dialogs appear
            // over whatever tab is showing, so a background
            // page could ask its question — and take the typed
            // answer — behind the visible page's identity. A
            // background request is cancelled unseen; the page
            // can ask again once it is in front.
            cancelJsResult(entry.result)
            DebugLog.log("jsdialog", "cancelled (tab not on screen), secret=${entry.tab.secret}")
            return
        }
        if (isUserModalPending()) {
            // Never stack over another modal (SEC-163): while a
            // download confirmation or permission prompt is up,
            // a JS dialog on top would let the page burn down
            // SEN-305's arm delay behind a dialog of its own —
            // the user taps through the alerts and the last tap
            // lands on an already-armed "approve". This entry is
            // not in the slot yet, so the predicate reads the
            // OTHER modals (a previous JS dialog included); the
            // offer below stays as the backstop.
            cancelJsResult(entry.result)
            DebugLog.log("jsdialog", "cancelled (another modal is pending: ${pendingModalKinds()}), secret=${entry.tab.secret}")
            return
        }
        if (!pendingJsDialog.offer(entry)) {
            // One dialog at a time: a second request is
            // cancelled, never stacked invisibly over the first.
            cancelJsResult(entry.result)
            DebugLog.log("jsdialog", "cancelled (another dialog pending), secret=${entry.tab.secret}")
            return
        }
        // Everything past the offer is one failure boundary
        // (SEN-343): any exception while building or showing
        // must release the slot and answer the result, or the
        // page's script hangs and every later dialog is refused
        // as "another pending" until the tab goes away — the
        // download and permission flows already guard their
        // show() the same way.
        try {
            presentJsDialogBody(entry, url, message, defaultValue)
        } catch (e: Exception) {
            if (pendingJsDialog.takeIf { it === entry } != null) {
                cancelJsResult(entry.result)
            }
            DebugLog.log(
                "jsdialog",
                "dialog failed: ${e.javaClass.simpleName}, secret=${entry.tab.secret}"
            )
        }
    }

    /** Build and show [entry]'s dialog; the caller owns failures. */
    private fun presentJsDialogBody(
        entry: PendingJsDialog,
        url: String?,
        message: String?,
        defaultValue: String?
    ) {
        // The dialog is the user's context now: a pending popup
        // from this tab must not decide behind it (SEC-155,
        // the same rule as the other prompts).
        entry.tab.expirePopupProbes()
        // The title names the requesting origin — host through
        // the shared display rule, so display trickery shows as
        // unknown rather than going on screen verbatim. A page
        // with no displayable host (data:, about:) is just
        // "this page": it must not borrow a name either.
        val host = PageFeaturePolicy.displayHost(url)
        val title = if (host != null) {
            val scheme = url?.substringBefore("://")?.lowercase() ?: ""
            "$scheme://$host のページ"
        } else {
            "このページ"
        }
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            // The dialog-body rule (SEN-346), not the origin
            // rule: line breaks survive, invisible characters
            // go, and the cap is marked — a confirm's second
            // paragraph must not vanish into a 200-char cut.
            .setMessage(PageFeaturePolicy.dialogMessageText(message))
        when (entry.kind) {
            PageFeaturePolicy.JsDialogKind.ALERT ->
                builder.setPositiveButton("OK") { _, _ ->
                    answerJsDialog(entry, confirmed = true)
                }
            PageFeaturePolicy.JsDialogKind.CONFIRM -> {
                builder.setPositiveButton("OK") { _, _ ->
                    answerJsDialog(entry, confirmed = true)
                }
                builder.setNegativeButton("キャンセル") { _, _ ->
                    answerJsDialog(entry, confirmed = false)
                }
            }
            PageFeaturePolicy.JsDialogKind.BEFORE_UNLOAD -> {
                // Confirming means LEAVING — the reverse of the
                // other kinds, so the buttons say so.
                builder.setPositiveButton("離れる") { _, _ ->
                    answerJsDialog(entry, confirmed = true)
                }
                builder.setNegativeButton("このページにとどまる") { _, _ ->
                    answerJsDialog(entry, confirmed = false)
                }
            }
            null -> {
                // prompt(): the input is an app-built EditText,
                // so the private-input rule reaches it (SEN-303)
                // — in a secret tab it carries the IME
                // no-learning flag the framework's own prompt
                // field never gets.
                val input = android.widget.EditText(this)
                Ui.applyPrivateInput(
                    input, android.text.InputType.TYPE_CLASS_TEXT
                )
                if (entry.tab.secret) {
                    // applyPrivateInput already keeps every app
                    // input out of Autofill; for a secret tab's
                    // prompt the exclusion is strengthened to
                    // match the tab's WebView (SEC-168) —
                    // descendants out too — so this field cannot
                    // leak what the WebView itself would not.
                    input.importantForAutofill =
                        android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
                // The default value is page-sized (PER-160):
                // cap it before it reaches the field's layout.
                input.setText(
                    defaultValue?.take(PageFeaturePolicy.DIALOG_VALUE_MAX) ?: ""
                )
                input.setSelection(0, input.text.length)
                entry.input = input
                builder.setView(input)
                builder.setPositiveButton("OK") { _, _ ->
                    answerJsDialog(entry, confirmed = true)
                }
                builder.setNegativeButton("キャンセル") { _, _ ->
                    answerJsDialog(entry, confirmed = false)
                }
            }
        }
        builder.setOnCancelListener {
            // A back-button / outside-touch dismissal: an alert
            // has no cancel outcome, so it answers as the
            // acknowledgement it is; the rest answer as refusal.
            answerJsDialog(entry, confirmed = entry.kind == PageFeaturePolicy.JsDialogKind.ALERT)
        }
        val dialog = builder.create()
        entry.dialog = dialog
        dialog.show()
    }

    /** Answer [entry]'s result once, if it is still this slot's. */
    private fun answerJsDialog(entry: PendingJsDialog, confirmed: Boolean) {
        if (pendingJsDialog.takeIf { it === entry } == null) return
        try {
            if (entry.kind == null) {
                val promptResult = entry.result as android.webkit.JsPromptResult
                if (confirmed) {
                    promptResult.confirm(entry.input?.text?.toString() ?: "")
                } else {
                    promptResult.cancel()
                }
            } else if (confirmed) {
                entry.result.confirm()
            } else {
                entry.result.cancel()
            }
        } catch (_: Exception) {
            // The requesting WebView may already be gone.
        }
    }

    private fun cancelJsResult(result: android.webkit.JsResult) {
        try {
            result.cancel()
        } catch (_: Exception) {
            // The requesting WebView may already be gone.
        }
    }

    /**
     * Drop [tab]'s pending JS dialog without a user answer:
     * the tab went away or off screen, so its question must not
     * stay up over another tab (SEN-301). The result is
     * cancelled, which for beforeunload means "stay" — the
     * safe side for a navigation nobody confirmed.
     */
    private fun retireJsDialog(tab: BrowserTab) {
        pendingJsDialog.takeIf { it.tab === tab }?.let { entry ->
            entry.dialog?.let { if (it.isShowing) it.dismiss() }
            cancelJsResult(entry.result)
            DebugLog.log("jsdialog", "retired with its tab, secret=${tab.secret}")
        }
    }

    private data class PendingPermission(
        val tab: BrowserTab,
        val request: android.webkit.PermissionRequest,
        var dialog: android.app.AlertDialog? = null,
        var siteApproved: Boolean = false,
        /** When the prompt appeared (SEN-305's arm delay). */
        var shownAtMs: Long = 0
    )

    private val pendingPermission = PendingSlot<PendingPermission>()

    override fun onPermissionRequest(tab: BrowserTab, request: android.webkit.PermissionRequest) {
        val grantable = PageFeaturePolicy.grantableResources(request.resources)
        if (grantable.isEmpty()) {
            // Nothing this browser ever grants (protected media,
            // MIDI, ...): deny without bothering the user.
            denyQuietly(request)
            DebugLog.log("perm", "denied (nothing grantable) origin=${permissionOriginForLog(tab, request)}")
            return
        }
        if (tab !== activeTab) {
            // Only the tab on screen may ask: a background tab's
            // page runs JavaScript (onPause only) and could
            // otherwise pop a system prompt the user would mistake
            // for the visible page's request. The page can ask
            // again once it is in front.
            denyQuietly(request)
            DebugLog.log("perm", "denied (background tab) origin=${permissionOriginForLog(tab, request)}")
            return
        }
        if (request.origin == null || request.origin.toString().isBlank()) {
            // No origin to show the user: there is nothing an
            // informed approval could attach to.
            denyQuietly(request)
            DebugLog.log("perm", "denied (no origin)")
            return
        }
        if (tab.permissionPromptSuppressed) {
            // The user already refused this tab a prompt; no
            // re-prompting until they navigate explicitly.
            denyQuietly(request)
            DebugLog.log("perm", "denied (suppressed after refusal) origin=${permissionOriginForLog(tab, request)}")
            return
        }
        if (isUserModalPending()) {
            // Never stack over another modal (SEN-356): a
            // download confirmation or a JS dialog is already
            // the user's context. Denied, not queued — the page
            // can ask again once the modal is answered, the
            // same treatment as a second permission request.
            denyQuietly(request)
            DebugLog.log("perm", "denied (another modal is pending: ${pendingModalKinds()}) origin=${permissionOriginForLog(tab, request)}")
            return
        }
        if (!pendingPermission.offer(PendingPermission(tab, request))) {
            // One prompt at a time; a second page's request is
            // denied rather than queued invisibly. (Backstop:
            // the modal predicate above refuses first, so this
            // is unreachable on the current call path — kept so
            // the rule holds even if a future caller bypasses
            // the predicate.)
            denyQuietly(request)
            DebugLog.log("perm", "denied (another request pending) origin=${permissionOriginForLog(tab, request)}")
            return
        }
        // The prompt is the user's context now: a pending
        // popup from this tab must not decide behind it and
        // swap the page under the prompt (SEC-155).
        tab.expirePopupProbes()
        // The per-site prompt comes FIRST: the OS permission
        // dialog is only worth raising for a site the user has
        // actually approved.
        showPermissionPrompt(grantable)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PAGE_PERMISSIONS) return
        val entry = pendingPermission.peek() ?: return
        if (!entry.siteApproved) return
        val grantable = PageFeaturePolicy.grantableResources(entry.request.resources)
        if (missingPagePermissions(grantable).isEmpty()) {
            pendingPermission.takeIf { it === entry }?.let { grantPermission(it, grantable) }
        } else {
            pendingPermission.takeIf { it === entry }
                ?.let { denyPermission(it, "system permission refused", suppress = true) }
        }
    }

    /**
     * The runtime permissions a grant of [grantable] still needs,
     * computed the one way (the policy decides, this only reads
     * the OS grant state) so the prompt flow and the result
     * handler can never drift.
     */
    private fun missingPagePermissions(grantable: List<String>): List<String> {
        val granted = PageFeaturePolicy.androidPermissionsFor(grantable)
            .filter {
                checkSelfPermission(it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            .toSet()
        return PageFeaturePolicy.missingPermissions(grantable, granted)
    }

    private fun showPermissionPrompt(grantable: List<String>) {
        val entry = pendingPermission.peek() ?: return
        val names = grantable.joinToString(" と ") {
            if (it == PageFeaturePolicy.RESOURCE_VIDEO_CAPTURE) "カメラ" else "マイク"
        }
        val origin = PageFeaturePolicy.displayText(entry.request.origin?.toString())
            ?: "不明なサイト"
        val message = StringBuilder("$origin が $names の使用を求めています。許可しますか？")
        if (entry.tab.secret) {
            // The prompt names the origin; say which kind of tab
            // is asking, so a secret tab's request is not mistaken
            // for the normal session's.
            message.append("\n（シークレットタブからの要求です）")
        }
        // The approval itself, run only from the post-show
        // click handler installed below (SEN-345, the same
        // shape as the download confirmation).
        val approve: () -> Unit = {
            // The site is approved. Grant right away when the
            // OS permissions are already in hand; otherwise ask
            // the OS now, and the result handler answers. The
            // take is identity-checked: this may only ever
            // answer its OWN entry. Idempotent (SEC-172): a
            // second invocation — a click racing the dismissal,
            // an accessibility double-activation — must not
            // launch a second OS request (the system answers a
            // request made while one is in flight with an
            // immediate empty result, which the result handler
            // would read as a refusal).
            val current = pendingPermission.peek()
            if (current === entry && !current.siteApproved) {
                current.siteApproved = true
                current.dialog = null
                val missing = missingPagePermissions(grantable)
                if (missing.isEmpty()) {
                    pendingPermission.takeIf { it === entry }
                        ?.let { grantPermission(it, grantable) }
                } else {
                    requestPermissions(missing.toTypedArray(), REQ_PAGE_PERMISSIONS)
                }
            }
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("権限の要求")
            .setMessage(message.toString())
            // No builder listener (SEN-345): the real handler
            // is installed on the button after show(), where
            // dismissal is explicit.
            .setPositiveButton("許可", null)
            .setNegativeButton("拒否") { _, _ ->
                pendingPermission.takeIf { it === entry }
                    ?.let { denyPermission(it, "user refused", suppress = true) }
            }
            .setOnCancelListener {
                pendingPermission.takeIf { it === entry }
                    ?.let { denyPermission(it, "prompt cancelled", suppress = true) }
            }
            .create()
        entry.dialog = dialog
        try {
            dialog.show()
            entry.shownAtMs = armApprovalButton(dialog)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    // SEN-305/SEN-345: an approval inside the
                    // arm delay is a true no-op (no dismiss,
                    // nothing consumed); an armed one approves
                    // and dismisses explicitly — unconditionally
                    // (SEN-351), so a failure inside approve()
                    // cannot strand the dialog. (On this path
                    // approve() leaves the slot pending for the
                    // OS result; the catch below is what answers
                    // and releases it when approve() itself
                    // fails — PER-163, SEC-172.)
                    if (!PageFeaturePolicy.approvalArmed(
                            entry.shownAtMs, android.os.SystemClock.uptimeMillis()
                        )
                    ) return@setOnClickListener
                    try {
                        approve()
                    } catch (e: Exception) {
                        DebugLog.log(
                            "perm",
                            "approval failed: ${e.javaClass.simpleName}"
                        )
                        // Unlike the download approval, this
                        // approve() does not consume the slot
                        // on the OS-request path (PER-163): left
                        // pending, the entry would refuse every
                        // later permission request and — through
                        // the SEC-163 gate — every JS dialog.
                        // Answer and release here. This cannot
                        // double-answer: an exception escaping
                        // approve() means the OS request never
                        // launched (and its result handler peeks
                        // and finds nothing once taken), while
                        // grantPermission swallows its own
                        // failures.
                        pendingPermission.takeIf { it === entry }
                            ?.let { denyPermission(it, "approval failed") }
                    } finally {
                        dialog.dismiss()
                    }
                }
        } catch (e: Exception) {
            // No prompt, no pending: dismiss what is up (a
            // failure after show() would otherwise strand a
            // handler-less dialog, SEN-352), answer and release,
            // or the request would hang and every later request
            // would be refused as "another pending" (same guard
            // as the download confirmation).
            if (dialog.isShowing) dialog.dismiss()
            pendingPermission.takeIf { it === entry }
                ?.let { denyPermission(it, "prompt failed to show") }
        }
    }

    private fun grantPermission(entry: PendingPermission, grantable: List<String>) {
        try {
            entry.request.grant(grantable.toTypedArray())
            DebugLog.log("perm", "granted by user")
        } catch (_: Exception) {
            // The requesting WebView may already be gone.
            DebugLog.log("perm", "grant failed (WebView gone?)")
        }
    }

    private fun denyPermission(entry: PendingPermission, reason: String, suppress: Boolean = false) {
        entry.dialog?.let { if (it.isShowing) it.dismiss() }
        if (suppress) {
            // A refusal the USER expressed (the site prompt or
            // the OS dialog): this tab's pages do not get to ask
            // again until the user navigates explicitly.
            entry.tab.permissionPromptSuppressed = true
        }
        denyQuietly(entry.request)
        DebugLog.log("perm", "denied ($reason)")
    }

    private fun denyQuietly(request: android.webkit.PermissionRequest) {
        try {
            request.deny()
        } catch (_: Exception) {
            // The requesting WebView may already be gone.
        }
    }

    override fun onPermissionRequestCanceled(tab: BrowserTab, request: android.webkit.PermissionRequest) {
        // Cancelled by the WebView: drop the prompt WITHOUT
        // answering the request again.
        pendingPermission.takeIf { it.request === request }?.let { entry ->
            entry.dialog?.let { if (it.isShowing) it.dismiss() }
            // A page that asks and instantly cancels in a loop
            // never gives the user a refusal to press, so the
            // refusal suppression would never start. Count the
            // cancels per tab; past a few, suppress just as a
            // refusal would.
            entry.tab.permissionWebViewCancels++
            if (entry.tab.permissionWebViewCancels >= MAX_PERMISSION_WEBVIEW_CANCELS) {
                entry.tab.permissionPromptSuppressed = true
                DebugLog.log("perm", "suppressed after repeated WebView cancels")
            } else {
                DebugLog.log("perm", "request cancelled by WebView; prompt dropped")
            }
        }
    }

    private fun permissionOriginForLog(tab: BrowserTab, request: android.webkit.PermissionRequest): String =
        if (tab.secret) "<redacted>" else request.origin?.toString() ?: ""

    private data class FullscreenState(
        val tab: BrowserTab,
        val container: FrameLayout,
        val callback: android.webkit.WebChromeClient.CustomViewCallback,
        /** systemBarsBehavior captured on entry; -1 = not captured. */
        var savedBarsBehavior: Int = -1
    )

    private val fullscreen = PendingSlot<FullscreenState>()

    /** The one fullscreen predicate every caller uses. */
    private fun isFullscreenShowing(): Boolean = fullscreen.isPending()

    override fun onShowCustomView(
        tab: BrowserTab,
        view: View,
        callback: android.webkit.WebChromeClient.CustomViewCallback
    ) {
        if (tab !== activeTab) {
            // Only the tab on screen may cover the whole UI (the
            // same rule as permission requests and downloads).
            callback.onCustomViewHidden()
            DebugLog.log("page", "fullscreen refused (background tab), secret=${tab.secret}")
            return
        }
        if (fullscreen.isPending()) {
            // One fullscreen at a time: refuse the newcomer, as
            // the contract requires its callback to be told at
            // once.
            callback.onCustomViewHidden()
            return
        }
        val container = FrameLayout(this)
        container.setBackgroundColor(android.graphics.Color.BLACK)
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        (window.decorView as ViewGroup).addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val state = FullscreenState(tab, container, callback)
        fullscreen.offer(state)
        // Entering an overlay ends the tab's undecided
        // popups (SEC-154's rule, run-3 hardening): every
        // other overlay opening expires them, and fullscreen
        // — which closeOverlaysForIncomingTab counts as an
        // overlay on the way out — was the one entrance that
        // did not. A probe left alive could otherwise decide
        // later and pull a new tab in over (and out of) the
        // fullscreen the user is watching.
        tab.expirePopupProbes()
        hideSystemBars(state)
        // A fullscreen page covers the whole UI — including any
        // browser chrome it might imitate — so name where it came
        // from and how to leave, briefly, on entry. The host goes
        // through the shared display rule, like the dialogs', and
        // is named from the COMMITTED URL (audit hardening):
        // currentUrl already follows an uncommitted load, which
        // would let a page put another site's name on this toast.
        val host = PageFeaturePolicy.displayHost(tab.lastCommittedUrl)
        android.widget.Toast.makeText(
            this,
            if (host == null) "全画面表示中（戻る操作で終了）"
            else "$host を全画面表示中（戻る操作で終了）",
            android.widget.Toast.LENGTH_LONG
        ).show()
        DebugLog.log("page", "fullscreen shown, secret=${tab.secret}")
    }

    override fun onHideCustomView(tab: BrowserTab) {
        if (fullscreen.peek()?.tab === tab) hideCustomView()
    }

    /**
     * The single funnel for leaving fullscreen, whoever asked
     * (page, back action, tab teardown, external link). Idempotent:
     * the slot hands the state out once, so the callback is
     * answered exactly once, and a WebView-initiated
     * onHideCustomView arriving after an app-initiated exit finds
     * nothing left to do. The orientation is never forced:
     * landscape-forcing would show portrait videos sideways, and
     * the user can rotate the device themselves.
     */
    private fun hideCustomView() {
        val state = fullscreen.take() ?: return
        (state.container.parent as? ViewGroup)?.removeView(state.container)
        showSystemBars(state)
        try {
            state.callback.onCustomViewHidden()
        } catch (_: Exception) {
            // The fullscreen WebView may already be gone.
        }
        DebugLog.log("page", "fullscreen hidden")
    }

    /**
     * Hide the system bars for fullscreen, capturing the current
     * bar state INTO [state] so exit can restore it symmetrically.
     */
    private fun hideSystemBars(state: FullscreenState) {
        val controller = window.insetsController
        if (controller == null) {
            // The fullscreen itself still shows; the bars
            // just stay visible. Degraded, never broken.
            DebugLog.log("page", "fullscreen: no insets controller; bars stay visible")
            return
        }
        state.savedBarsBehavior = controller.systemBarsBehavior
        controller.hide(android.view.WindowInsets.Type.systemBars())
        controller.systemBarsBehavior =
            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** Restore the bars to exactly what [hideSystemBars] captured. */
    private fun showSystemBars(state: FullscreenState) {
        val controller = window.insetsController ?: return
        controller.show(android.view.WindowInsets.Type.systemBars())
        if (state.savedBarsBehavior >= 0) {
            controller.systemBarsBehavior = state.savedBarsBehavior
        }
    }

    /**
     * Flush every page-feature interaction [tab] may have pending
     * before its WebView is destroyed: a file pick is answered
     * with null, a permission prompt is denied and dismissed, a
     * fullscreen owned by the tab is left. Called from every
     * tab-destroy path in this activity.
     */
    private fun beforeTabDestroyed(tab: BrowserTab) {
        thumbLoadInFlight.remove(tab)
        thumbLoadMisses.remove(tab)
        // A translation run dies with its tab; if it was mid-run
        // its dialogs are this tab's and come down with it.
        pageTranslators.remove(tab)?.let { translator ->
            if (translator.state == PageTranslator.State.WORKING) dismissTranslateDialogs()
            translator.destroy()
        }
        pendingFilePick.takeIf { it.tab === tab }?.let { answerFilePick(it, null) }
        pendingPermission.takeIf { it.tab === tab }
            ?.let { denyPermission(it, "tab destroyed") }
        pendingDownload.takeIf { it.tab === tab }
            ?.let { it.dialog?.let { d -> if (d.isShowing) d.dismiss() } }
        retireJsDialog(tab)
        if (fullscreen.peek()?.tab === tab) hideCustomView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // What may be saved, per tab:
        // - Secret tabs: nothing. Their URLs must not outlive the
        //   process in the system-held Bundle; the tab is restored
        //   blank (home), keeping its position and mode.
        // - Other tabs: http(s) URLs only, with a per-URL cap and a
        //   total budget. currentUrl is page-controlled and can be
        //   ~2MB; an uncapped Bundle throws
        //   TransactionTooLargeException when the app backgrounds.
        // - Home tabs save their (short) home marker; anything else
        //   (data:, blob:, over-cap) saves blank and restores as home.
        // A deferred (not yet loaded) tab saves its deferred URL.
        var budget = TabSessionCodec.MAX_TOTAL_LENGTH
        val savedUrls = ArrayList(tabs.map { t ->
            val u = t.deferredUrl ?: t.currentUrl
            // The per-URL rule is the session codec's
            // (savableTabUrl: home marker, http(s) within the
            // length cap, else blank) so the Bundle and the
            // session file can never drift apart; only the
            // secret blanking and the total budget are applied
            // here on top.
            val keep = when {
                t.secret -> ""
                else -> {
                    val s = savableTabUrl(u)
                    if (s.length <= budget) s else ""
                }
            }
            budget -= keep.length
            keep
        })
        outState.putString(STATE_LAUNCH_LINK_DIGEST, launchLinkDigest)
        outState.putStringArrayList(STATE_TAB_URLS, savedUrls)
        outState.putBooleanArray(STATE_TAB_SECRETS, tabs.map { it.secret }.toBooleanArray())
        outState.putBooleanArray(STATE_TAB_DESKTOPS, tabs.map { it.desktopMode }.toBooleanArray())
        outState.putInt(STATE_ACTIVE_IDX, tabs.indexOf(activeTab).coerceAtLeast(0))
    }

    /** True between onResume and onPause; switchTo consults it. */
    @Volatile private var activityResumed = false

    /**
     * True between onStop and onStart. The coalesced switcher
     * rebuild consults this — not [activityResumed] — because a
     * paused-but-visible activity (multi-window without focus)
     * must keep its cards.
     */
    @Volatile private var activityStopped = true

    override fun onStart() {
        super.onStart()
        activityStopped = false
    }

    override fun onPause() {
        activityResumed = false
        // The visible tab pauses with the activity.
        activeTab?.webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        activeTab?.webView?.onResume()
        // A memory trim while stopped may have emptied a still-open
        // switcher's grid: refill it now that the
        // screen is visible again.
        if (::switcher.isInitialized && switcher.visibility == View.VISIBLE &&
            ::switcherGrid.isInitialized && switcherGrid.childCount == 0
        ) {
            renderSwitcherNow()
        }
        // Become the instance export-move completions aim
        // their view work at (a move can outlive its starter).
        exportUiHost = java.lang.ref.WeakReference(this)
    }

    /**
     * The tallest the panel may be APPLIED at in the space the
     * column currently has. Two bounds, the lower wins:
     * - preferred: the column minus the same 110dp reserve the
     *   drag handler leaves (toolbar, divider and a slice of
     *   page), floored at the drag minimum of 200dp;
     * - hard: the column minus the toolbar and divider as laid
     *   out, so the toolbar itself always fits -- in a short
     *   column (landscape + IME) this is the bound that keeps
     *   the URL bar on screen, at the page area's expense.
     * Derived from [columnHeightPx], so it already accounts
     * for the IME: with the keyboard up the column is shorter
     * and so is the cap. MAX_VALUE until the first layout
     * (nothing to cap against).
     */
    private fun dtMaxAppliedHeightPx(): Int {
        if (columnHeightPx <= 0) return Int.MAX_VALUE
        // The toolbar's height is fixed by design (44dp pill +
        // 16dp of vertical padding), so the laid-out height is
        // read directly; if the toolbar ever becomes dynamic,
        // its layout changes must also trigger a refresh. The
        // measured value is floored at the design height: a
        // measurement squeezed by an over-tall panel must not
        // feed back into a looser cap.
        val toolbarH = if (::bottomToolbar.isInitialized) {
            maxOf(bottomToolbar.height, Ui.dp(this, TOOLBAR_HEIGHT_DP)) + 1 // + the 1px divider above it
        } else {
            Ui.dp(this, TOOLBAR_HEIGHT_DP) + 1 // the toolbar's design height, pre-layout
        }
        return PanelHeight.appliedCap(
            columnHeightPx, toolbarH,
            Ui.dp(this, PanelHeight.RESERVE_DP), Ui.dp(this, PanelHeight.MIN_DP)
        )
    }

    /**
     * Re-apply the chosen panel height under the current space
     * cap. Called when the column's laid-out height changes
     * and when the panel opens, so the toolbar is never pushed
     * out of the window; the chosen height itself is left
     * alone and takes effect again when the space comes back --
     * unless the user drags meanwhile: a drag re-chooses the
     * height (clamped to the shrunken column, as drags are),
     * and that choice is what comes back.
     * A hidden panel is skipped: opening it re-applies anyway,
     * and relayouts of a GONE panel would be pure waste.
     */
    private fun refreshDtHeight() {
        if (::dtPanel.isInitialized && dtPanel.visibility == View.VISIBLE) {
            applyDtHeight(dtHeightPx)
        }
    }

    /**
     * Set the DevTools panel's chosen height (already clamped by
     * the caller). Shared by the drag handler and
     * [onConfigurationChanged]: an unchanged
     * applied height is a no-op instead of a fresh LayoutParams
     * and a relayout on every drag MOVE. The height actually
     * applied to the layout is capped by
     * [dtMaxAppliedHeightPx] so the fixed-height panel can never
     * push the bottom toolbar out of the (IME-shrunk) column;
     * [dtHeightPx] keeps the chosen value.
     */
    private fun applyDtHeight(newH: Int) {
        dtHeightPx = newH
        val applied = minOf(newH, dtMaxAppliedHeightPx())
        val lp = dtPanel.layoutParams as? LinearLayout.LayoutParams
        if (lp?.height == applied) return
        if (lp != null) {
            // Reuse the existing params: a fresh LayoutParams per
            // drag MOVE is pure waste; writing the height and
            // re-setting the same params still requests the
            // relayout.
            lp.height = applied
            dtPanel.layoutParams = lp
        } else {
            dtPanel.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, applied
            )
        }
    }

    /**
     * The manifest absorbs orientation / screen-size / smallest-size
     * changes without recreating the activity, so size-derived state
     * must be re-derived here: the DevTools panel's
     * chosen height is computed once from the then-current screen
     * height, and unclamped it could exceed a landscape screen
     * and squeeze the page area to zero. Clamp the chosen value
     * with the drag handler's formula, but over the full screen
     * height rather than the column height: this clamp is a
     * per-screen sanity bound for the chosen value, while the
     * layout invariant itself is the applied cap's job, keyed
     * to the column (the applied height is re-derived by the
     * column's layout listener);
     * a visible switcher is re-rendered
     * because its card width depends on the current width.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::menuSheet.isInitialized && menuSheet.visibility == View.VISIBLE) hideMenu()
        if (::dtPanel.isInitialized) {
            // Clamp the CHOSEN height only. Applying it here
            // would use the pre-rotation column height; the
            // column's layout listener re-applies under the
            // fresh height as soon as the new layout lands.
            dtHeightPx = PanelHeight.clampChosen(
                dtHeightPx, resources.displayMetrics.heightPixels,
                Ui.dp(this, PanelHeight.MIN_DP),
                Ui.dp(this, PanelHeight.RESERVE_DP),
                Ui.dp(this, PanelHeight.MAX_FLOOR_DP)
            )
        }
        if (::switcher.isInitialized && switcher.visibility == View.VISIBLE) {
            // Coalesced, not the synchronous render: requests in
            // the same message turn merge into one rebuild, and the
            // rebuild runs after this configuration pass instead of
            // inside it. Changes that arrive in separate turns
            // (e.g. a split-screen resize drag) can still rebuild
            // once per turn — no worse than a synchronous render.
            renderSwitcher()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Under real memory pressure, background tabs' thumbnails
        // (~0.58MB each) are the cheapest thing to give up: the
        // switcher reloads the persisted copy when the card
        // binds, and a fresh capture happens on the next switch.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            for (t in tabs) {
                if (t !== activeTab) t.thumbnail = null
            }
            // The already-built switcher cards hold the same
            // Bitmaps in their ImageViews: dropping only the tab
            // fields would not actually release them. A hidden
            // grid is emptied outright; a VISIBLE one is rebuilt
            // at once with placeholders instead -- emptying it
            // would leave an open switcher blank with no trigger
            // to refill it.
            if (::switcherGrid.isInitialized) {
                if (::switcher.isInitialized && switcher.visibility == View.VISIBLE && !activityStopped) {
                    renderSwitcherNow()
                } else {
                    // Also when the switcher is merely VISIBLE but
                    // the activity is stopped: rebuilding every card
                    // synchronously for a screen nobody sees wastes
                    // the trim's purpose. onResume refills an emptied
                    // visible switcher. A paused-but-visible switcher
                    // (multi-window) rebuilds instead, like the coalesced
                    // path.
                    switcherGrid.removeAllViews()
                }
            }
        }
    }

    override fun onStop() {
        activityStopped = true
        // Leaving the foreground ends the visible tab's
        // undecided popup too (SEC-151): its page's script
        // keeps running in the background, and a probe that
        // decided later would open a tab over whatever the
        // user moved on to.
        activeTab?.expirePopupProbes()
        // The DevTools session is a live bridge into every tab; it
        // ends when the app leaves the foreground rather than
        // lingering in the background.
        if (dtOpen) closeRealDevTools()
        super.onStop()
    }

    override fun onDestroy() {
        if (exportUiHost?.get() === this) exportUiHost = null
        // Full teardown: stop the stats loop, end the proxy, and
        // detach every WebView from its parent before destroying it
        // (a WebView destroyed while attached can leak its view tree
        // and keep rendering into a dead window).
        statsHandler.removeCallbacks(statsRunnable)
        proxy?.stop()
        // Page-feature pendings must not outlive their WebViews:
        // answer the file pick, deny the permission prompt, leave
        // fullscreen, all before the tabs go away.
        pendingFilePick.take()?.let { answerFilePick(it, null) }
        pendingPermission.take()?.let { denyPermission(it, "activity destroyed") }
        pendingDownload.take()
            ?.let { it.dialog?.let { d -> if (d.isShowing) d.dismiss() } }
        // The JS dialog too (SEN-344): the tab teardown below
        // destroys WebViews directly and does not pass through
        // beforeTabDestroyed, so without this the dialog window
        // leaks with the dead activity and its result is never
        // answered.
        pendingJsDialog.take()?.let { entry ->
            entry.dialog?.let { d -> if (d.isShowing) d.dismiss() }
            cancelJsResult(entry.result)
        }
        // Translation runs likewise: close the ML Kit clients
        // and bring their dialogs down before the WebViews go.
        dismissTranslateDialogs()
        pageTranslators.values.forEach { it.destroy() }
        pageTranslators.clear()
        translateRunOwner = null
        hideCustomView()
        if (::realWebView.isInitialized) {
            (realWebView.parent as? ViewGroup)?.removeView(realWebView)
            realWebView.destroy()
        }
        for (t in tabs.toList()) {
            pageContainer.removeView(t.webView)
            t.destroy()
        }
        tabs.clear()
        if (isFinishing) {
            // The app is going away (not a recreation): make the
            // same erasure attempt the close paths make. A posted
            // retry is pointless here, so this is the synchronous
            // delete-or-clear; the next start's wipe backstops
            // anything the teardown timing refuses.
            SecretProfile.wipe("activity finish")
        }
        super.onDestroy()
    }
}
