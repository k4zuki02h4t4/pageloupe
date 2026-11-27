package jp.kaleidpixel.pageloupe

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class BrowserTab(
    private val ctx: Context,
    private val listener: Listener,
    /**
     * Secret (incognito-style) tab. What "secret" concretely means
     * in this app — stated precisely: the tab's history is
     * dropped when it closes, its URL is never written into the
     * saved state, the gated debug log redacts its URLs/hosts
     * during DevTools sessions (the exported console/request logs
     * are NOT redacted — plaintext exports are an accepted
     * trade-off, recorded in REVIEW.md), and Autofill is told
     * not to save from it.
     *
     * Where the device's WebView supports androidx.webkit
     * multi-profile, the tab additionally runs on a dedicated
     * profile (see [SecretProfile]): cookies, cache, DOM storage /
     * IndexedDB and Service Workers are then NOT shared with
     * normal tabs. Deletion of the profile is attempted when the
     * last secret tab closes and again at app start, so a killed
     * process leaves nothing behind either; when a delete is
     * refused, cookies and storage are cleared in place instead
     * and the cache/Service Worker residue waits for the next
     * successful delete (see SecretProfile's docs). Where
     * multi-profile is unsupported, that data remains shared with
     * normal tabs; that residual is the accepted limitation
     * recorded in REVIEW.md.
     *
     * WebSettings.saveFormData is a no-op on API 26+, so it is not
     * part of the protection either way.
     */
    val secret: Boolean = false
) {

    interface Listener {
        fun onTitleChanged(tab: BrowserTab)
        fun onUrlChanged(tab: BrowserTab)
        /**
         * A navigation committed (doUpdateVisitedHistory): the
         * point where the page becomes this tab's current page
         * for good — full loads, SPA navigations and history
         * moves alike. The browsing history records here. [url]
         * is the committed URL, passed explicitly because
         * currentUrl is only updated after this callback.
         */
        fun onPageCommitted(tab: BrowserTab, url: String)

        /**
         * A navigation committed AND began a new document (a
         * load sequence started it and reached this commit):
         * the previous document is gone for good. Same-document
         * commits (SPA pushState and the like) do NOT fire
         * this, and neither does anything after a load sequence
         * that ended uncommitted (NewDocumentLatch owns the
         * distinction). Fired just before [onPageCommitted]
         * for the same commit, isolated from it: an exception
         * here is logged, never propagated into the commit.
         * Consumers that hold per-document state (page
         * translation, v0.41.0) reset here rather than at
         * page-started, which an aborted or download-bound
         * navigation would falsely trigger.
         */
        fun onDocumentReplaced(tab: BrowserTab)
        fun onProgressChanged(tab: BrowserTab, progress: Int)
        fun onPageStarted(tab: BrowserTab, userInitiated: Boolean)
        /** The renderer process died; this tab's WebView is unusable. */
        fun onRenderProcessGone(tab: BrowserTab)
        /**
         * A secret tab on a multi-profile-capable WebView could
         * not be put on the secret profile and is running on the
         * shared default profile instead. Fired once from init;
         * the tab is not in any list yet, so implementations must
         * only surface the fact, not touch tab state.
         */
        fun onSecretProfileApplyFailed(tab: BrowserTab)

        /**
         * The page asked to pick file(s) for an
         * `<input type=file>`. The implementation runs the system
         * picker and must answer [callback] exactly once (with
         * null when the pick is cancelled or superseded). Return
         * true when the request was taken over.
         */
        fun onShowFileChooser(
            tab: BrowserTab,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean

        /**
         * The page asked for capture resources (getUserMedia and
         * friends). The implementation confirms with the user and
         * must answer the request exactly once (grant or deny).
         * Only camera/microphone can ever be granted (see
         * [PageFeaturePolicy]).
         */
        fun onPermissionRequest(tab: BrowserTab, request: PermissionRequest)

        /**
         * A permission request was cancelled by the WebView (the
         * page navigated or the frame went away). The
         * implementation drops any pending prompt for it WITHOUT
         * answering it again.
         */
        fun onPermissionRequestCanceled(tab: BrowserTab, request: PermissionRequest)

        /** The page entered HTML5 fullscreen (a video, usually). */
        fun onShowCustomView(
            tab: BrowserTab,
            view: View,
            callback: WebChromeClient.CustomViewCallback
        )

        /** The page left HTML5 fullscreen. */
        fun onHideCustomView(tab: BrowserTab)

        /**
         * The page started a download the user may want to keep.
         * The implementation confirms with the user and, on
         * approval, hands [ask] to Downloads.enqueue. Nothing is
         * downloaded before that confirmation.
         */
        fun onDownloadRequested(tab: BrowserTab, ask: Downloads.Ask)

        /**
         * Whether a download confirmation is already pending.
         * Checked BEFORE the (string-processing) Ask is built, so
         * a page bursting downloads does not make the UI thread
         * redo that work for requests that will be refused anyway.
         */
        fun downloadConfirmationPending(): Boolean

        /**
         * A download was refused before any confirmation (an
         * unsupported scheme, or a confirmation already pending).
         * The implementation owns the user-facing notice: it
         * decides whether this tab may put anything on screen at
         * all (a background tab may not).
         */
        fun onDownloadRefused(tab: BrowserTab, busy: Boolean)

        /**
         * Whether [tab] may open a popup at all right now:
         * the tab must be on screen, the activity in the
         * foreground, and the user must not be in the middle
         * of something else (an overlay, an address-bar edit,
         * a pending prompt). The same moments that expire a
         * pending probe refuse a new one (SEN-316, SEN-327).
         */
        fun canOpenPopup(tab: BrowserTab): Boolean

        /**
         * A page popup (window.open / target=_blank) tried to
         * navigate to an http(s) URL: popups are normalized
         * into real tabs (SEN-286). The implementation opens a
         * new tab with THIS tab's secret — never the active
         * tab's — and loads [url] there.
         */
        fun onPopupTab(tab: BrowserTab, url: String)

        /**
         * The page raised a JavaScript dialog (alert, confirm
         * or beforeunload). The implementation owns the dialog:
         * it must answer [result] exactly once, and a tab that
         * is not on screen gets an immediate cancel instead of
         * a dialog over another tab's page (SEN-301) — the
         * framework's built-in dialogs appear over whatever tab
         * is showing, titled only with the requesting host,
         * while the address bar keeps naming the visible tab.
         * [url] is the requesting frame's URL, for naming the
         * origin in the dialog.
         */
        fun onJsDialog(
            tab: BrowserTab,
            kind: PageFeaturePolicy.JsDialogKind,
            url: String?,
            message: String?,
            result: android.webkit.JsResult
        )

        /**
         * The page called prompt(). Same contract as
         * [onJsDialog]; the implementation's input field is an
         * app-built EditText, so the private-input rule reaches
         * it (SEN-303) — the framework's built-in prompt field
         * is created by the WebView and carries no IME
         * no-learning flag even in a secret tab.
         */
        fun onJsPrompt(
            tab: BrowserTab,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: android.webkit.JsPromptResult
        )
    }

    /**
     * Whether this tab's WebView actually runs on the secret
     * profile. Only meaningful for secret tabs; stays false on
     * WebViews without multi-profile support (the accepted
     * fallback) and when the assignment failed (the listener is
     * told via [Listener.onSecretProfileApplyFailed]).
     */
    var secretProfileApplied: Boolean = false
        private set

    val webView: WebView = PrivateInputWebView(ctx, noLearning = secret).also { wv ->
        // The profile assignment is part of construction itself:
        // a WebView's profile is fixed once the WebView is used,
        // and even reading settings first (the UA strings below)
        // would already count as use. This must be the very first
        // touch of the new WebView.
        secretProfileApplied = SecretProfile.applyTo(wv, secret)
    }

    /**
     * The popup probe created by onCreateWindow and not yet
     * decided, tracked by [PopupProbeRegistry] (at most one
     * per tab). A probe never displays anything and never
     * loads a main-frame navigation: the first one is
     * classified by [PageFeaturePolicy.popupAction] and the
     * probe is retired either way (SEN-286). Content the
     * opener writes into the probe's document can still run
     * with the opener's own privileges — the probe has no
     * powers of its own — but it can put no UI on screen
     * (SEN-314, SEC-152). A pending probe expires on the
     * events listed in REVIEW.md (the opener's next
     * main-frame navigation, a tab switch, the app stopping,
     * an overlay opening, the address bar taking focus, this
     * tab showing a dialog, tab destruction — SEN-309).
     */
    private val popupRegistry = PopupProbeRegistry<WebView>()
    private val popupHandler = Handler(Looper.getMainLooper())

    private fun destroyProbeQuietly(probe: WebView) {
        try {
            probe.destroy()
        } catch (_: Exception) {
        }
    }

    /**
     * Unregister [probe] and destroy it. Returns true when the
     * probe was still registered — the only state in which a
     * late callback may still act on it (SEN-310). With
     * [deferDestroy] the actual destroy is posted to the main
     * thread: callers inside the probe's own callbacks must let
     * the callback return before the WebView goes away
     * (SEN-312). The one deliberate exception is the probe's
     * onRenderProcessGone, which destroys synchronously: that
     * callback is the WebView's terminal event — no further
     * callbacks for it can arrive — and destroying there is
     * the documented Android pattern (SEN-320).
     */
    private fun retirePopupProbe(probe: WebView, deferDestroy: Boolean = false): Boolean {
        if (!popupRegistry.retire(probe)) return false
        if (deferDestroy) {
            popupHandler.post { destroyProbeQuietly(probe) }
        } else {
            destroyProbeQuietly(probe)
        }
        return true
    }

    /**
     * Drop this tab's still-undecided popup probe, if any. The
     * unregistering is synchronous; the destroy is posted, so
     * an expiry that lands while the probe's transport or a
     * renderer-death callback is still being delivered never
     * destroys the WebView mid-delivery (SEC-150, PER-153).
     */
    fun expirePopupProbes() {
        val probe = popupRegistry.expire() ?: return
        DebugLog.log("nav", "popup expired")
        popupHandler.post { destroyProbeQuietly(probe) }
    }
    /**
     * The current page's title, or "" while no title has
     * arrived yet. The empty value is the "no title" signal
     * for every consumer (history and bookmark fill-ins wait
     * for a non-empty title; the commit-time adoption in
     * MainActivity adopts a title only when it is attributed
     * to the committed URL and otherwise records "" for the
     * fill-in to correct). Display code substitutes the
     * localized placeholder for a blank title (the switcher
     * card does). Never seed this with a display string: a
     * seeded placeholder was once adoptable as a real title
     * by the history recorder when a fresh tab's first
     * commit matched the newest history entry before any
     * title arrived.
     */
    var title: String = ""
    @Volatile var currentUrl: String = ""

    /**
     * The data: URL of the current start page load, as
     * constructed from its HTML (Chromium turns the
     * loadDataWithBaseURL call in [loadHome] into a data:
     * navigation). Recomputed on every [loadHome]; the
     * console/request recorder matches it exactly when a
     * WebView reports the load's Document URL in full, so only
     * this tab's own start page load is classified as internal
     * that way — never a real page's data: Document. (This
     * device's WebView reports the bare prefix instead; see
     * isInternalDocumentUrl.) Null until the first [loadHome].
     */
    @Volatile var homeDataUrl: String? = null
        private set

    var desktopMode: Boolean = false
        private set
    var isLoading: Boolean = false
        private set
    /**
     * Consecutive renderer deaths of this tab, counted per TAB --
     * never per URL and never reset by anything the page can do
     * (a completed load, a redirect, a URL change): a crashing page
     * controls all of those and could otherwise reset its own death
     * counter on every reload and loop forever. Reset only
     * by explicit user action, via [noteUserNavigation]:
     * typed/intent navigation ([loadInput]),
     * the reload button and the menu's home button,
     * back/forward, and picking the tab in the switcher.
     */
    var rendererDeaths: Int = 0

    /**
     * Page-feature prompt suppression: after the
     * user refuses a download confirmation or a permission
     * prompt in this tab, further prompts of that kind are denied
     * silently — a page must not be able to trap the user in a
     * re-prompt loop that keeps the tab UI out of reach. Cleared
     * by [noteUserNavigation], like the renderer-death count:
     * an explicit user action on the tab earns prompts back.
     */
    var downloadPromptSuppressed: Boolean = false
    var permissionPromptSuppressed: Boolean = false

    /**
     * Whether the secret-isolation failure notice has been
     * shown for THIS tab (set by the listener when it fires):
     * the notice is once per tab, and keeping the flag here
     * means it dies with the tab instead of pinning the tab in
     * a listener-side collection.
     */
    var secretIsolationFailureNotified: Boolean = false

    /**
     * How often the WebView itself cancelled this tab's
     * permission request while its prompt was up:
     * a page that asks and instantly cancels in a loop never lets
     * the user refuse, so past a few repetitions the cancellation
     * count starts the same suppression a refusal would.
     */
    var permissionWebViewCancels: Int = 0

    /**
     * The last URL this tab actually COMMITTED as a page (set
     * ONLY from doUpdateVisitedHistory, the commit signal).
     * Unlike [currentUrl], which follows onPageStarted and
     * therefore already names the target while a navigation is
     * still loading, this stays at the page the user is coming
     * FROM — which is what the download Referer and the cookie
     * gate must judge from.
     */
    var lastCommittedUrl: String = ""

    /**
     * True when a title has arrived since the last committed
     * navigation (and since the current load started). The
     * browsing history reads this at commit time (through
     * HistoryTitle) together with [loadStartedSinceCommit]:
     * a title adopted at a commit must be a same-document
     * one (an SPA set document.title just before pushState).
     * Device evidence (2026-11-24, WebView 158, SEN-222): a
     * full load's title lands just AFTER its commit, never
     * before — at every observed full-load commit this flag
     * was still false — and the post-commit arrival is what
     * the history fill-in runs on. The commit path clears it
     * once consumed, and a new load start clears it too, so
     * a title can never be adopted by an unrelated later
     * navigation.
     */
    @Volatile var titleArrivedSinceCommit: Boolean = false
        private set

    /**
     * The URL the current [title] belongs to, or null when it
     * belongs to nothing current (a previous page's title
     * carried over a commit, a placeholder). Maintained by
     * the pure transitions in TitleAttribution from the title
     * and commit callbacks; consumers that present the title
     * as the current page's own (the share sheet) must check
     * TitleAttribution.belongs against [currentUrl] first.
     */
    @Volatile var titleUrl: String? = null
        private set

    /**
     * True when a load has started since the last commit.
     * TitleAttribution.onCommit reads this to tell a full
     * navigation (titles arriving mid-load are the outgoing
     * document's) from a same-document commit (a fresh title
     * is the committing document's own). Set in onPageStarted,
     * consumed at every commit and cleared only after the
     * listener's commit callback returns, so the callback
     * (the history recording, SEN-222) reads it — and
     * [titleArrivedSinceCommit] — exactly as they stood at
     * the commit. Main-thread only (WebView callbacks),
     * hence no @Volatile.
     */
    var loadStartedSinceCommit: Boolean = false
        private set

    /**
     * Where the in-flight load sequence started from,
     * latched by [LoadStartLatch] (SEC-167): a load away
     * from home that never commits leaves the start page on
     * screen, and lastCommittedUrl — which the home load
     * deliberately does not move — would name the page
     * visited before home instead. The latch owns its
     * lifetime (first start samples; commit and finish each
     * end the sequence) rather than borrowing
     * [loadStartedSinceCommit]'s, which outlives uncommitted
     * finishes (PER-162).
     */
    private val loadStartLatch = LoadStartLatch()

    /**
     * Whether the in-flight load sequence will replace the
     * document when it commits, latched by [NewDocumentLatch]:
     * unlike [loadStartedSinceCommit], the sequence also ends
     * at an uncommitted finish, so a later same-document
     * commit is not misread as a replacement (v0.41.0 review).
     */
    private val newDocumentLatch = NewDocumentLatch()

    /**
     * True while a load sequence that started from the start
     * page is still in flight (nothing of it has committed):
     * the address bar must not vouch for a page yet in that
     * window (SEN-361). Cleared by the sequence's own commit
     * or finish, via the latch.
     */
    val loadSequenceFromHome: Boolean
        get() = loadStartLatch.startedFromHome

    /**
     * Whether the document on screen is the start page, in
     * every phase of its life (the phase table lives on the
     * pure [isHomeDisplayed], which this delegates to).
     * [homeCommitted] is the primary term — the same flag the
     * capture rules key on — and is kept even where the
     * current-URL term currently overlaps it: its meaning
     * (the home document became visible) does not depend on
     * the currentUrl bookkeeping, whose rules have changed
     * before. Consumers that act on the displayed document
     * (page translation's gate) read this single predicate:
     * while it holds, [lastCommittedUrl] still names the page
     * visited before home and must not be treated as what is
     * shown.
     */
    val homeDisplayed: Boolean
        get() = isHomeDisplayed(homeCommitted, loadSequenceFromHome, isHomeUrl(currentUrl))

    /** An explicit user navigation/reload happened: restart the death count. */
    fun noteUserNavigation() {
        rendererDeaths = 0
        downloadPromptSuppressed = false
        permissionPromptSuppressed = false
        permissionWebViewCancels = 0
        userNavArmed = true
    }

    /**
     * One-shot marker: this tab's next main-frame navigation was
     * initiated by an explicit user action -- a typed address, a
     * toolbar or menu button, back/forward or a switcher pick
     * (all via [noteUserNavigation]), or a gestured in-page
     * request such as a link tap (shouldOverrideUrlLoading's
     * hasGesture). Page-driven navigations (JS redirects, meta
     * refresh, a page reloading itself) never set it. Consumed by
     * onPageStarted and reported to the listener, so the activity
     * can end an in-progress address-bar edit for user-initiated
     * navigations only: a page must not be able to wipe what the
     * user is typing by navigating under the edit (SEN-153).
     */
    private var userNavArmed = false

    /**
     * Drop the user-navigation marker without a navigation: the
     * action that set it turned out to navigate nowhere (a
     * switcher pick of an already-loaded tab), so no
     * onPageStarted will consume it, and leaving it set would
     * let an unrelated later navigation spend it.
     */
    fun disarmUserNavigation() {
        userNavArmed = false
    }

    /**
     * The last http(s) navigation attempt in this tab, recorded
     * for the download gate (SEN-285): the DownloadListener
     * reports only a URL, so which frame the navigation was
     * for has to be remembered from shouldOverrideUrlLoading.
     * The record's lifetime rules (stickiness, consumption,
     * clearing when a new document starts) live in the tracker
     * itself.
     */
    private val downloadInitiatorTracker = DownloadInitiatorTracker()
    /** URL deferred until this tab is first shown (crash recovery / restore of background tabs). */
    var deferredUrl: String? = null

    /**
     * This tab's navigation state (the WebView's back/forward
     * list) as of its last commit, captured with
     * WebView.saveState at commit time — while the renderer is
     * alive, so renderer-crash recovery never has to ask a
     * dead WebView for its state. Memory only: the snapshot is
     * never written to disk (the saved tab session keeps
     * carrying just URLs) and it dies with the tab object.
     * [navStateUrl] is the URL that committed when the
     * snapshot was taken; recovery restores the snapshot only
     * for exactly that URL (see shouldRestoreNavState in
     * UrlInput.kt).
     */
    private var navState: Bundle? = null
    private var navStateUrl: String? = null

    /** The back/forward list's shape at capture time. */
    private var navStateShape: NavStateShape? = null

    /**
     * Snapshot attempts made for the document currently
     * loading or loaded. The capture budget
     * (NAV_STATE_CAPTURE_BUDGET) is counted in attempts, not
     * successes, and a failed capture does NOT reset it — a
     * document whose captures keep failing must not be able
     * to loop them. Only a new document's commit resets it
     * (see captureNavState).
     */
    private var navStateCaptures: Int = 0

    /**
     * Which recent loads failed, so their commits are
     * marked as error pages: the rules and their accepted
     * residuals live in [ErrorPageTracker]; this tab only
     * feeds it the WebView callbacks and reads the verdict
     * at each commit into [lastCommitWasErrorPage].
     */
    private val errorPageTracker = ErrorPageTracker()

    /**
     * Whether the last commit was an error page (its load
     * failed): what is on screen is the WebView's own error
     * document, so the address bar shows neither the lock nor
     * the cleartext warning for it (PageFeaturePolicy).
     */
    var lastCommitWasErrorPage: Boolean = false
        private set

    /**
     * Drop the snapshot and the shape it was captured at. The
     * per-document attempt count is deliberately NOT reset
     * here (see [navStateCaptures]).
     */
    private fun clearNavState() {
        navState = null
        navStateUrl = null
        navStateShape = null
    }

    /**
     * Drop the snapshot from outside (the tab rebuild path):
     * whichever rebuild branch ran — including the ones that
     * never take the snapshot, like the crash-loop stop —
     * the old tab must not keep its history once its
     * replacement exists.
     */
    fun discardNavState() {
        clearNavState()
    }

    /**
     * Notify the listener that the document was replaced,
     * isolated: a failing hook must not take the commit
     * itself (history recording, URL bookkeeping) down with
     * it, so an exception is logged, never propagated.
     */
    private fun fireDocumentReplaced() {
        try {
            listener.onDocumentReplaced(this)
        } catch (e: Exception) {
            DebugLog.log("nav", "onDocumentReplaced hook failed")
        }
    }

    /**
     * Refresh the navigation-state snapshot after a commit.
     * saveState on a live WebView is a plain read of its
     * navigation state; the pure decision in
     * decideNavStateCapture (UrlInput.kt) says whether this
     * commit pays for that
     * read, keeps the snapshot as is, or drops it — start
     * page commits drop it, an unchanged list shape keeps it
     * (a page can fire such commits in a tight loop), and a
     * document that has spent its capture budget drops it
     * too and captures nothing more: by then the snapshot no
     * longer describes the list, and keeping it would let a
     * later URL coincidence restore a history that is not
     * the tab's anymore.
     *
     * A failed capture (null return or exception) INVALIDATES
     * the old snapshot instead of keeping it: a kept snapshot
     * whose URL happens to match a later crash's target would
     * restore a history that is no longer this tab's — the
     * same URL can head a different list (A, then B, then A
     * again, with the last two captures failing).
     *
     * [newDocument] is whether this commit began a new
     * document (a load started it), as opposed to a
     * same-document move (pushState, an in-document history
     * traversal): the capture budget is per document.
     */
    private fun captureNavState(committedUrl: String, newDocument: Boolean) {
        if (newDocument) navStateCaptures = 0
        try {
            val list = webView.copyBackForwardList()
            val listShape = NavStateShape(
                list.size,
                list.currentIndex,
                if (list.size > 0) list.getItemAtIndex(0).url else null
            )
            when (
                decideNavStateCapture(
                    committedUrl, newDocument, navStateCaptures,
                    listShape, navStateUrl, navStateShape
                )
            ) {
                NavStateCapture.DiscardSnapshot -> clearNavState()
                NavStateCapture.SkipUnchanged -> Unit
                NavStateCapture.TakeSnapshot -> {
                    navStateCaptures++
                    val state = Bundle()
                    if (webView.saveState(state) != null) {
                        navState = state
                        navStateUrl = committedUrl
                        navStateShape = listShape
                    } else {
                        clearNavState()
                        DebugLog.log("tab", "nav state capture missed")
                    }
                }
            }
        } catch (_: Exception) {
            clearNavState()
            DebugLog.log("tab", "nav state capture missed")
        }
    }

    /**
     * Hand this tab's snapshot to its crash replacement for
     * [url], or null when recovery must load the URL on its
     * own. A TAKE: the snapshot leaves this tab either way, so
     * the (dead) tab never keeps a second reference to the
     * history its replacement now owns — however long this
     * object itself happens to be referenced afterwards.
     */
    fun takeNavStateFor(url: String): Bundle? {
        val state = navState
        val usable = state != null && shouldRestoreNavState(url, navStateUrl)
        clearNavState()
        return if (usable) state else null
    }

    /**
     * Take over a crashed predecessor's snapshot. Crash
     * recovery only: the replacement's WebView is fresh, and
     * [restoreNavState] is what turns the snapshot back into
     * its history. A tab rebuilt any other way loads just its
     * current URL, so adopting a snapshot there would hand a
     * later crash recovery a history the tab never had. The
     * captured shape is NOT carried over: it described the
     * predecessor's WebView, so this tab's first commit
     * re-captures unconditionally.
     */
    fun adoptNavState(state: Bundle, stateUrl: String) {
        navState = state
        navStateUrl = stateUrl
        navStateShape = null
    }

    /**
     * Restore the adopted snapshot into this tab's fresh
     * WebView, which reloads the snapshot's current page with
     * its back/forward list intact. The URL gate lives here,
     * not at the call sites, so no caller can restore a
     * snapshot into a tab that is about to show a different
     * URL ([expectedUrl] is the URL the caller means to put
     * on screen). A snapshot is spent by the call, whichever
     * way it goes: if the gate refuses it, it was taken for
     * some other page and this tab will never show that page
     * from it; after a failed restore the caller loads the
     * URL on its own and this tab's history starts over, so
     * the snapshot no longer describes it; after a success
     * the restored load's commit re-captures anyway. False
     * when there is no usable snapshot or the WebView refused
     * it (restoreState returns null) — the caller then loads
     * the URL itself.
     */
    fun restoreNavState(expectedUrl: String): Boolean {
        val state = navState ?: return false
        val usable = shouldRestoreNavState(expectedUrl, navStateUrl)
        clearNavState()
        if (!usable) return false
        return try {
            webView.restoreState(state) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Whether the start page's content has been committed to
     * the screen in the current load. It is reset at every
     * load start (onPageStarted, and in [loadHome] itself for
     * the gap before that callback) and set by
     * onPageCommitVisible for the home URL only. currentUrl
     * becomes HOME_URL synchronously in loadHome, before the
     * WebView has drawn the start page, so a capture taken in
     * between would still show the previous page; the activity
     * persists a home tab's thumbnail only once this is set,
     * so a previous page's screen can never be filed under
     * the shared home key (SEN-158).
     */
    @Volatile var homeCommitted: Boolean = false
        private set
    /** Latest favicon reported by the page, shown on the tab card. */
    var favicon: Bitmap? = null

    /**
     * The URL [favicon] was reported for (the loading page's
     * URL at receipt time). Consumers that persist the icon
     * (the bookmark store) check this against the page they
     * mean to attach it to, so an icon is never paired with a
     * page it does not belong to.
     */
    @Volatile var faviconUrl: String = ""
    /** Last captured screenshot of the visible page, shown on the tab card. */
    var thumbnail: Bitmap? = null

    private val appVersion: String = cachedAppVersion(ctx)

    // Brand the browser in the User-Agent (like Edge's "Edg/" or Samsung
    // Internet's "SamsungBrowser/") so logs show PageLoupe as the browser.
    private val mobileUa: String = webView.settings.userAgentString + " PageLoupe/" + appVersion
    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36" +
            " PageLoupe/" + appVersion

    init {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // Popups are taken over in onCreateWindow and normalized
        // (http(s) becomes a new tab, everything else is
        // dropped): without this, a popup request collapses
        // into a main-frame navigation of THIS tab and slips
        // past the subframe gates in shouldOverrideUrlLoading
        // (SEN-286).
        s.setSupportMultipleWindows(true)
        // Stated explicitly rather than left to platform defaults:
        // pages get no content:// or file:// reach
        // through this WebView.
        s.allowContentAccess = false
        s.allowFileAccess = false
        // Stated for the same reason (audit hardening): mixed
        // content is refused outright rather than inherited
        // from the platform default, so an https page can never
        // pull cleartext subresources even if a future WebView
        // or targetSdk relaxes the default.
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Third-party cookies stay off, fixed in source rather
        // than left to the (currently identical) default.
        android.webkit.CookieManager.getInstance()
            .setAcceptThirdPartyCookies(webView, false)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = mobileUa
        if (secret) {
            // Kept for documentation value only: saveFormData is a
            // no-op on API 26+, so it stops
            // nothing. The live lever is Autofill: mark the whole
            // WebView as not-to-be-saved, so credentials or form
            // values entered in a secret tab are not offered to the
            // Autofill service for later reuse.
            s.saveFormData = false
            webView.importantForAutofill =
                android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            // Content capture is the same kind of leak with a
            // different reader (audit hardening): a system
            // service may collect the page's text for assistant
            // features. A secret tab's pages are no more for it
            // than they are for Autofill.
            webView.importantForContentCapture =
                android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                DebugLog.log("nav", "page started")
                // This tab moving on ends any popup it left
                // undecided: a probe that has not navigated by
                // the time its opener navigates is stale, and
                // letting it decide later would open a tab the
                // user no longer connects to any tap (SEN-309).
                expirePopupProbes()
                // Failures recorded for an earlier load must
                // not mark this load's commit: the tracker
                // drops every record but this start URL's own
                // (see ErrorPageTracker for why that one must
                // survive its start, v0.43.0 review r2).
                if (errorPageTracker.onLoadStarted(url)) {
                    DebugLog.log("nav", "stale error records dropped at load start")
                }
                // A new main-frame document starts loading: the
                // recorded navigation attempt belongs to the
                // page being left and must not classify the new
                // page's downloads (SEN-285). This cannot lose a
                // download's own record, whichever order this
                // callback and shouldOverrideUrlLoading fire in:
                // a navigation whose response is a download
                // never reaches this callback at all, and a
                // navigation that does reach it commits, so it
                // needs no record. SPA history updates
                // (pushState) do not fire this callback and
                // deliberately do NOT clear the record
                // (SEN-335).
                downloadInitiatorTracker.clear()
                isLoading = true
                // Remember where this load SEQUENCE started
                // FROM: if it never commits, the start page is
                // what stays on screen when it began at home —
                // not the page visited before home, which is
                // what lastCommittedUrl still names then
                // (SEC-167). The latch samples only at the
                // sequence's first start (SEN-353) and owns
                // its lifetime (PER-162). A blank currentUrl —
                // a fresh tab that has loaded nothing yet —
                // counts as home (SEN-366): the tab's screen at
                // that point is the start page state, and an
                // uncommitted first finish reverting to the
                // home marker matches it.
                loadStartLatch.onLoadStarted(isHomeUrl(currentUrl))
                newDocumentLatch.onLoadStarted()
                currentUrl = url
                // A new load invalidates the home commit
                // state: history moves back to the start page
                // do not pass through loadHome, so without
                // this a stale "committed" from an earlier
                // home visit would cover the window before
                // the restored page commits (SEN-158).
                homeCommitted = false
                // A title adopted at the coming commit must have
                // arrived during THIS load (or just before an
                // SPA's pushState, which has no onPageStarted);
                // anything older belongs to a previous page.
                titleArrivedSinceCommit = false
                loadStartedSinceCommit = true
                val userNav = userNavArmed
                userNavArmed = false
                listener.onPageStarted(this@BrowserTab, userNav)
                listener.onUrlChanged(this@BrowserTab)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                // The start page is really on screen now; a
                // capture from here on shows it, not whatever
                // page preceded it (SEN-158).
                if (isHomeUrl(url)) homeCommitted = true
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                // Remember a failed main-frame load for its
                // commit (see ErrorPageTracker): the error
                // page is the WebView's own document, and the
                // commit must not let the address bar vouch
                // for it as the site's page. This callback
                // also fires for subresources, which are not
                // the page's identity: the tracker records
                // main-frame calls only, and the filter is
                // enforced there, not here.
                errorPageTracker.recordError(request.url?.toString(), request.isForMainFrame)
                if (request.isForMainFrame) {
                    DebugLog.log("nav", "main-frame load failed")
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError
            ) {
                // The framework default, stated explicitly:
                // a certificate error cancels the load, never
                // proceeds. The failure is recorded like any
                // other (see onReceivedError): the error page
                // that follows commits the URL all the same.
                // The recorded URL is the one that FAILED
                // validation (error.url), never view.url: this
                // callback also fires for subresources and
                // subframes, and a record can only mark a
                // commit of that same URL — which for the main
                // frame's failure is exactly its commit's URL
                // (see ErrorPageTracker for the residuals).
                errorPageTracker.recordSslError(error.url)
                DebugLog.log("nav", "ssl error, load cancelled")
                handler.cancel()
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                // The WebView cannot be used any more; the listener
                // rebuilds the tab in place. Returning true keeps the
                // app alive instead of the system killing it.
                listener.onRenderProcessGone(this@BrowserTab)
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // SPA navigations (pushState/replaceState) and
                // history moves do not always come through
                // onPageStarted: keep currentUrl truthful, since the
                // address bar, saved state and the DevTools target
                // matching all read it.
                if (url == "about:blank" && isHomeUrl(currentUrl)) {
                    // Internal blank of the home page's data load;
                    // the home URL stands. The document IS being
                    // replaced by the home document, though: if a
                    // load sequence was in flight, per-document
                    // consumers (page translation) must still
                    // hear about the replacement, or their state
                    // would outlive the document it belongs to.
                    if (newDocumentLatch.onCommit()) fireDocumentReplaced()
                    // The home navigation also prunes the
                    // back/forward list (the forward entries past
                    // the page home was reached from are gone),
                    // so the crash-recovery snapshot taken before
                    // home is stale in exactly the same way a
                    // home COMMIT discards it in
                    // decideNavStateCapture — which this early
                    // return never reaches (run-3 hardening).
                    // Without the discard, a renderer death on
                    // the next load out of home could restore
                    // the pruned forward entries and lose the
                    // home entry itself.
                    clearNavState()
                    lastCommitWasErrorPage = false
                    return
                }
                lastCommittedUrl = url
                // An error page commits its failed URL too
                // (SEN-347): mark exactly that commit, so the
                // address bar does not vouch for a document the
                // site never presented (run-3 hardening). The
                // pending failures are consumed either way, so
                // once a commit has passed, a failure can no
                // longer mark a later commit (for the leftover
                // case, see ErrorPageTracker residual (1)).
                val errorCommit = errorPageTracker.consumeAtCommit(url)
                lastCommitWasErrorPage = if (loadStartedSinceCommit) {
                    errorCommit
                } else {
                    // A same-document commit keeps the document
                    // on screen: if that document is an error
                    // page, it still is one, so the standing
                    // mark is inherited (v0.43.0 review r2).
                    // The tracker's verdict is NOT newly
                    // adopted here (r9): a record left over
                    // from before must not newly mark this
                    // document — pushState must not be able to
                    // clear an http page's cleartext warning.
                    // The records are still consumed above.
                    lastCommitWasErrorPage
                }
                // The two flags as they stood at this commit
                // (SEN-222's device evidence): a title that
                // arrived with loadStarted set came from the
                // outgoing document, never the incoming one.
                DebugLog.log(
                    "nav",
                    "commit arrived=$titleArrivedSinceCommit " +
                        "loadStarted=$loadStartedSinceCommit " +
                        "error=$lastCommitWasErrorPage"
                )
                // Re-attribute the title BEFORE the arrived
                // flag is consumed below and before the load-
                // started flag is cleared: TitleAttribution
                // reads both, and swapping this order silently
                // breaks the attribution (the flags' own
                // transitions are pinned by JVM tests, this
                // wiring order is pinned by this comment and
                // the review ledger).
                titleUrl = TitleAttribution.onCommit(
                    titleUrl, titleArrivedSinceCommit, loadStartedSinceCommit, url
                )
                // The load sequence has committed: the home
                // latch's lifetime ends with it (PER-162). The
                // finish that follows matches the committed URL
                // and never consults the latch.
                loadStartLatch.onCommit()
                // Whether this commit began a new document (a
                // load started it) or was a same-document move:
                // the nav-state capture budget is per document.
                // Read BEFORE the finally below clears the flag
                // (the same wiring-order constraint as the title
                // attribution above).
                val newDocument = loadStartedSinceCommit
                // Document replacement reads its own latch, not
                // the flag above: loadStartedSinceCommit outlives
                // an uncommitted finish by design (SEN-222),
                // which would misreport a later same-document
                // commit as a replacement. The latch's sequence
                // ends at its finish too (NewDocumentLatch).
                val replacedDocument = newDocumentLatch.onCommit()
                try {
                    if (replacedDocument) fireDocumentReplaced()
                    listener.onPageCommitted(this@BrowserTab, url)
                } finally {
                    // The commit consumed any pending title: it
                    // now belongs to the page just committed.
                    // Both flags clear only here, after the
                    // callback, so the history recording read
                    // them as they stood at the commit
                    // (SEN-222) — and they clear even if the
                    // callback throws, so a wedged flag can
                    // never poison the next commit's decision
                    // (SEN-369).
                    titleArrivedSinceCommit = false
                    loadStartedSinceCommit = false
                }
                if (url != currentUrl) {
                    currentUrl = url
                    listener.onUrlChanged(this@BrowserTab)
                }
                // The commit just rewrote the back/forward
                // list: refresh the crash-recovery snapshot
                // while the renderer that owns it is alive.
                captureNavState(url, newDocument)
            }

            override fun onPageFinished(view: WebView, url: String) {
                DebugLog.log("nav", "page finished")
                isLoading = false
                // The error tracker is deliberately NOT fed
                // finishes: an error page's finish precedes
                // its commit (see ErrorPageTracker), so a
                // finish must never retire a failure record.
                // A finish is not a commit: for a load that
                // never committed (a 204, a window.stop(), an
                // aborted chain) this fires with the target URL
                // while the previous page is still on screen,
                // and adopting it would leave the address bar
                // naming a site the user is not looking at
                // (SEN-299). The pure rule in CommitUrl decides;
                // lastCommittedUrl below stays untouched either
                // way, for the reasons its own comment gives.
                newDocumentLatch.onFinish()
                currentUrl = CommitUrl.onPageFinished(
                    url, lastCommittedUrl, loadStartLatch.onFinish()
                )
                // Backstop for the user-navigation marker: a load
                // that never committed (stopped, a 204, an aborted
                // redirect chain) can end here without its
                // onPageStarted having consumed the marker. A load
                // that did start already consumed it, so this only
                // ever clears a leftover.
                userNavArmed = false
                // lastCommittedUrl is deliberately NOT updated
                // here: onPageFinished can also fire for loads
                // that never committed (a navigation that turned
                // into a download, an aborted load), and the
                // cookie gate's premise is that it only ever
                // names a committed page. doUpdateVisitedHistory
                // is the commit signal.
                listener.onUrlChanged(this@BrowserTab)
                listener.onProgressChanged(this@BrowserTab, 100)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase() ?: return false
                // A page-driven main-frame navigation to a data:
                // URL is refused (audit hardening; the pure rule
                // is PageFeaturePolicy's — Chrome blocks these
                // outright, gesture or not). Blocked: no
                // navigation follows, so no onPageStarted will
                // consume the marker.
                if (PageFeaturePolicy.shouldBlockDataMainFrame(
                        scheme, request.isForMainFrame
                    )
                ) {
                    userNavArmed = false
                    DebugLog.log("nav", "data: main-frame navigation dropped (page-initiated)")
                    return true
                }
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") {
                    // A main-frame load proceeds in this tab: note
                    // whether the user gestured it (a link tap, a
                    // form submit) or the page drove it, for the
                    // SEN-153 edit rule in onPageStarted. Redirect
                    // hops do not overwrite the marker: a gestured
                    // or browser-initiated navigation stays
                    // user-initiated across its redirects.
                    if (request.isForMainFrame && !request.isRedirect) {
                        userNavArmed = request.hasGesture()
                    }
                    // For the download gate (SEN-285): remember
                    // this attempt's URL and target frame.
                    // Unlike the marker above, redirect hops DO
                    // overwrite: the record must name the hop
                    // that produced the response, not the one
                    // that started the chain.
                    if (scheme == "http" || scheme == "https") {
                        downloadInitiatorTracker.record(
                            u.toString(), request.isForMainFrame
                        )
                    }
                    return false
                }
                if (scheme == "pageloupe") {
                    // The internal scheme navigates the WHOLE tab,
                    // so it is main-frame only: a subframe must not
                    // be able to yank the tab home with no user
                    // action at all.
                    if (request.isForMainFrame) {
                        if (!request.isRedirect) userNavArmed = request.hasGesture()
                        loadHome()
                    } else {
                        DebugLog.log("nav", "internal scheme dropped (subframe)")
                    }
                    return true
                }
                // Local / system schemes never leave the browser: a
                // page must not hand a content: or file: URI to
                // another app to open under that app's permissions.
                if (scheme == "content" || scheme == "file" || scheme == "blob" ||
                    scheme == "javascript" || scheme == "intent"
                ) {
                    // Blocked: no navigation follows in this tab,
                    // so no onPageStarted will consume the marker.
                    if (request.isForMainFrame) userNavArmed = false
                    DebugLog.log("nav", "local scheme dropped: ${scheme.take(32)}")
                    return true
                }
                // External schemes (tel:, mailto:, app-specific
                // ones) leave the browser only for a main-frame
                // navigation the user actually gestured at: a page
                // must not be able to fire another app on load or
                // from a subframe. CATEGORY_BROWSABLE
                // keeps the handoff to browser-safe handlers. Blocks
                // and failures are logged (gated) so a dead deep
                // link is diagnosable instead of silent.
                if (!request.isForMainFrame || !request.hasGesture()) {
                    // Blocked (or handed off below): the main-frame
                    // navigation does not proceed in this tab, so
                    // no onPageStarted will consume the marker.
                    if (request.isForMainFrame) userNavArmed = false
                    DebugLog.log(
                        "nav",
                        "external scheme blocked (mainFrame=${request.isForMainFrame} " +
                            "gesture=${request.hasGesture()}): ${scheme.take(32)}"
                    )
                    return true
                }
                // The handoff leaves this tab unnavigated: same
                // marker reasoning as the blocked branch above.
                userNavArmed = false
                return try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, u)
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } catch (e: Exception) {
                    DebugLog.log(
                        "nav",
                        "external scheme handoff failed: ${scheme.take(32)} " +
                            "(${e.javaClass.simpleName})"
                    )
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, t: String?) {
                // The title is stored already cleaned (audit
                // hardening): every place that shows it (tab
                // cards, history, the share sheet) then inherits
                // the title rule (PageFeaturePolicy.titleText)
                // instead of each consumer having to remember
                // it, so a title can never smuggle bidi or
                // control characters into a string it is later
                // concatenated with.
                val clean = PageFeaturePolicy.titleText(t)
                if (clean != null) {
                    title = clean
                    titleArrivedSinceCommit = true
                    titleUrl = TitleAttribution.onTitleReceived(lastCommittedUrl, currentUrl)
                    listener.onTitleChanged(this@BrowserTab)
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                if (icon != null) {
                    favicon = icon
                    faviconUrl = view.url ?: currentUrl
                    // Reuse the title callback as a generic "card data changed" refresh.
                    listener.onTitleChanged(this@BrowserTab)
                }
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                listener.onProgressChanged(this@BrowserTab, newProgress)
            }

            // JavaScript dialogs are the app's own (SEN-301):
            // the framework's versions appear over whatever tab
            // is on screen — including for a background tab —
            // and the prompt's input field is the framework's,
            // beyond the private-input rule's reach (SEN-303).
            // The listener decides (background tabs are
            // cancelled unseen) and answers exactly once.
            override fun onJsAlert(
                view: WebView, url: String?, message: String?,
                result: android.webkit.JsResult
            ): Boolean {
                listener.onJsDialog(
                    this@BrowserTab, PageFeaturePolicy.JsDialogKind.ALERT,
                    url, message, result
                )
                return true
            }

            override fun onJsConfirm(
                view: WebView, url: String?, message: String?,
                result: android.webkit.JsResult
            ): Boolean {
                listener.onJsDialog(
                    this@BrowserTab, PageFeaturePolicy.JsDialogKind.CONFIRM,
                    url, message, result
                )
                return true
            }

            override fun onJsBeforeUnload(
                view: WebView, url: String?, message: String?,
                result: android.webkit.JsResult
            ): Boolean {
                listener.onJsDialog(
                    this@BrowserTab, PageFeaturePolicy.JsDialogKind.BEFORE_UNLOAD,
                    url, message, result
                )
                return true
            }

            override fun onJsPrompt(
                view: WebView, url: String?, message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult
            ): Boolean {
                listener.onJsPrompt(this@BrowserTab, url, message, defaultValue, result)
                return true
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                // Page console output is viewed in the DevTools
                // panel, not accumulated here: nothing would read a
                // per-tab buffer, so keeping one is pure UI-thread cost.
                return true
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Only a real tap may open anything: without a
                // gesture the request is refused outright (the
                // renderer used to drop these before they ever
                // reached the app; with multiple windows on, this
                // check is the app's).
                if (resultMsg == null) return false
                if (!isUserGesture) {
                    DebugLog.log("nav", "popup refused (no gesture)")
                    return false
                }
                // Only the tab on screen may open a popup —
                // the same rule as downloads, permission
                // requests and fullscreen (SEN-316). A
                // background tab's pending probe would also
                // never see a foreground-loss expiry.
                if (!listener.canOpenPopup(this@BrowserTab)) {
                    DebugLog.log("nav", "popup refused (not allowed now)")
                    return false
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                // The probe is built exactly like the tab's own
                // WebView: the profile assignment is the very
                // first touch, so a secret tab's popup can never
                // touch the shared default profile (SEN-286).
                val probe = PrivateInputWebView(ctx, noLearning = secret)
                val probeApplied = SecretProfile.applyTo(probe, secret)
                if (secret && probeApplied != secretProfileApplied) {
                    // The probe must run on the same profile
                    // footing as its opener: a secret tab on the
                    // secret profile must never spawn a popup on
                    // the default profile (or the reverse).
                    // Refuse the popup instead (SEC-146).
                    destroyProbeQuietly(probe)
                    DebugLog.log("nav", "popup refused (profile mismatch)")
                    return false
                }
                // Script must run so a popup whose URL is set
                // from script after opening (window.open('')
                // then location = ...) still reaches the
                // decision. The probe itself gets no multiple
                // windows, no downloads and no display, and the
                // same explicit access restrictions as a tab
                // (SEC-149).
                val ps = probe.settings
                ps.javaScriptEnabled = true
                ps.allowContentAccess = false
                ps.allowFileAccess = false
                // The tab's stated restrictions apply to the
                // probe too (audit hardening): it loads the same
                // page, just off-screen.
                ps.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                android.webkit.CookieManager.getInstance()
                    .setAcceptThirdPartyCookies(probe, false)
                if (secret) {
                    probe.importantForAutofill =
                        View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    probe.importantForContentCapture =
                        View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                }
                // Registering supersedes any still-undecided
                // probe from an earlier tap (SEN-309); the
                // superseded probe's destroy is posted, like
                // every expiry (SEC-150).
                popupRegistry.begin(probe)?.let { old ->
                    popupHandler.post { destroyProbeQuietly(old) }
                }
                fun decide(url: String?) {
                    // One gate for every late or repeated
                    // callback: only the still-pending probe
                    // may decide, and deciding retires it, so
                    // nothing decides twice and a probe that
                    // was superseded or expired is never
                    // touched again (SEN-321).
                    if (!popupRegistry.isPending(probe)) return
                    val u = url?.trim().orEmpty()
                    when (PageFeaturePolicy.popupAction(u)) {
                        PageFeaturePolicy.PopupAction.WAIT -> Unit
                        PageFeaturePolicy.PopupAction.OPEN_TAB -> {
                            // Retire first: the registration is
                            // the decision's record, and the
                            // guard below is only a backstop
                            // against a callback re-entering
                            // synchronously out of stopLoading
                            // (SEN-324). The destroy is posted,
                            // so stopLoading still reaches a
                            // live WebView.
                            if (retirePopupProbe(probe, deferDestroy = true)) {
                                probe.stopLoading()
                                listener.onPopupTab(
                                    this@BrowserTab,
                                    PageFeaturePolicy.normalizePopupTarget(u)
                                )
                            }
                        }
                        PageFeaturePolicy.PopupAction.DROP -> {
                            if (retirePopupProbe(probe, deferDestroy = true)) {
                                probe.stopLoading()
                                DebugLog.log("nav", "popup dropped")
                            }
                        }
                    }
                }
                probe.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        // Only the main frame's navigation is
                        // the popup's destination; a subframe
                        // written into the probe document is
                        // not (SEN-311). Either way the probe
                        // loads nothing itself: even the blank
                        // start page stays unloaded.
                        if (request.isForMainFrame) {
                            decide(request.url?.toString())
                        }
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        // Backstop for navigations that skip
                        // shouldOverrideUrlLoading (POST popups).
                        // The classification happens here, at
                        // navigation start, and decide() stops
                        // the load; a POST response may already
                        // have set cookies on the probe's profile
                        // by now — the same profile and privileges
                        // as the opener's, so nothing is gained
                        // by it (SEC-152).
                        decide(url)
                    }

                    override fun onRenderProcessGone(
                        v: WebView,
                        detail: RenderProcessGoneDetail
                    ): Boolean {
                        // The tab's own client answers true and
                        // rebuilds; a probe is simply dropped.
                        // The default false would kill the
                        // whole app for a popup's renderer
                        // (SEN-308). The destroy here is
                        // synchronous on purpose — the terminal
                        // callback exception in
                        // retirePopupProbe's contract (SEN-320).
                        retirePopupProbe(probe)
                        return true
                    }
                }
                probe.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        // The page closed its popup before it
                        // navigated anywhere (SEC-147).
                        retirePopupProbe(probe, deferDestroy = true)
                    }

                    // The probe is invisible: it must never put
                    // UI on screen. Every dialog-shaped
                    // callback is refused explicitly instead of
                    // falling back to the framework's default
                    // handling, which would show a dialog for
                    // a window the user cannot see (SEN-314).
                    override fun onJsAlert(
                        view: WebView, url: String, message: String, result: JsResult
                    ): Boolean {
                        result.cancel()
                        return true
                    }

                    override fun onJsConfirm(
                        view: WebView, url: String, message: String, result: JsResult
                    ): Boolean {
                        result.cancel()
                        return true
                    }

                    override fun onJsPrompt(
                        view: WebView,
                        url: String,
                        message: String,
                        defaultValue: String?,
                        result: JsPromptResult
                    ): Boolean {
                        result.cancel()
                        return true
                    }

                    override fun onJsBeforeUnload(
                        view: WebView, url: String, message: String, result: JsResult
                    ): Boolean {
                        result.cancel()
                        return true
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean = false

                    override fun onPermissionRequest(request: PermissionRequest) {
                        request.deny()
                    }

                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String,
                        callback: GeolocationPermissions.Callback
                    ) {
                        callback.invoke(origin, false, false)
                    }
                }
                transport.webView = probe
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                return listener.onShowFileChooser(
                    this@BrowserTab, filePathCallback, fileChooserParams
                )
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                // Unlike downloads, a permission request has no
                // heavy per-request construction to skip, so ALL
                // the checks (visible tab, origin, suppression)
                // intentionally live on the listener side.
                listener.onPermissionRequest(this@BrowserTab, request)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (request == null) return
                listener.onPermissionRequestCanceled(this@BrowserTab, request)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: android.webkit.GeolocationPermissions.Callback
            ) {
                // The app declares no location permission, so
                // the answer is always no — stated explicitly,
                // as the popup probe's client already does
                // (run-3 hardening): the framework's default
                // of simply never answering would leave the
                // page waiting and the refusal implicit.
                callback.invoke(origin, false, false)
            }

            override fun onShowCustomView(
                view: View?,
                callback: WebChromeClient.CustomViewCallback?
            ) {
                if (view == null || callback == null) return
                listener.onShowCustomView(this@BrowserTab, view, callback)
            }

            override fun onHideCustomView() {
                listener.onHideCustomView(this@BrowserTab)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            // A navigation that turned into a download never
            // reaches onPageStarted: drop the user-navigation
            // marker so it cannot be spent by a later,
            // unrelated navigation.
            userNavArmed = false
            val normalized = PageFeaturePolicy.normalizeDownloadUrl(url)
            // Classify FIRST, whichever way this download goes
            // (SEN-337 / SEC-157): a download that is refused,
            // unsupported or queued behind another confirmation
            // must not leave its navigation record behind for a
            // later download to match. classify consumes the
            // record only on an exact URL match; a record for a
            // different URL belongs to a navigation still in
            // flight and stays. (For an unnormalizable URL the
            // raw string is classified instead: records only
            // ever hold http(s) URLs, so it cannot match.)
            val frame = downloadInitiatorTracker.classify(normalized ?: url)
            if (downloadPromptSuppressed) {
                // The user refused this tab's downloads: stay
                // silent — for every kind of download, fetchable
                // or not — and spend nothing on Ask building
                // until they navigate explicitly.
                DebugLog.log("download", "refused (suppressed after refusal), secret=$secret")
                return@setDownloadListener
            }
            if (normalized == null) {
                // blob: / data: and friends: DownloadManager cannot
                // fetch them. Say so instead of failing silently.
                listener.onDownloadRefused(this, busy = false)
                DebugLog.log("download", "refused (unsupported scheme), secret=$secret")
                return@setDownloadListener
            }
            if (listener.downloadConfirmationPending()) {
                // Refused before any Ask building: past the URL
                // normalization above, one boolean check is all a
                // bursting page costs per extra download.
                listener.onDownloadRefused(this, busy = true)
                DebugLog.log("download", "refused (confirmation pending), secret=$secret")
                return@setDownloadListener
            }
            // No cookie is read here: the session cookie is only
            // fetched if and when the user approves the download
            // (MainActivity), so a refused or superseded download
            // never touches the cookie store at all. Whether it
            // MAY be fetched is decided here, from the download
            // URL and the last COMMITTED page (never the
            // still-loading target: see lastCommittedUrl) — and,
            // since SEN-285, from which frame the navigation
            // was for (the classification above). A subframe
            // navigation — or a frame that cannot be confirmed
            // — gets no cookie, and the confirmation dialog
            // says so.
            DebugLog.log(
                "download",
                "frame=${frame.name.lowercase()}, secret=$secret"
            )
            listener.onDownloadRequested(
                this,
                Downloads.Ask(
                    url = normalized,
                    fileName = PageFeaturePolicy.sanitizeFileName(
                        URLUtil.guessFileName(normalized, contentDisposition, mimeType)
                    ),
                    mimeType = mimeType,
                    userAgent = userAgent,
                    refererOrigin = PageFeaturePolicy.downloadReferer(
                        lastCommittedUrl, normalized
                    ),
                    secret = secret,
                    frame = frame,
                    cookieAllowed = PageFeaturePolicy.shouldAttachCookie(
                        normalized, lastCommittedUrl, frame
                    )
                )
            )
        }

        if (secret && !secretProfileApplied &&
            ProfileSupport.supportState() != ProfileSupport.SupportState.UNSUPPORTED
        ) {
            // Supported device, failed assignment: NOT the
            // accepted fallback. A probe that could not tell
            // (UNKNOWN) counts as failure too, not as the
            // accepted fallback. Tell the listener so the user
            // hears about it instead of browsing on a silently
            // shared profile. Done last in init, with the tab
            // fully constructed.
            listener.onSecretProfileApplyFailed(this)
        }
    }

    fun displayUrl(): String {
        if (currentUrl.isBlank()) return ""
        // Like Chrome's new tab page, the start page shows an empty URL bar.
        if (isHomeUrl(currentUrl)) return ""
        // Userinfo is credentials, not identity: showing it
        // lets a page push the real host off the bar's visible
        // edge behind a familiar-looking name (SEN-300). The
        // stored URL is untouched; only the display drops it.
        return PageFeaturePolicy.stripUserInfo(currentUrl)
            .removePrefix("https://").removePrefix("http://")
    }

    fun loadInput(input: String) {
        noteUserNavigation()
        // The classification rules live in UrlInput.kt as pure
        // functions with JVM unit tests.
        when (val target = classifyUrlInput(input)) {
            // An empty commit navigates nowhere: drop the marker
            // noteUserNavigation just set, so it cannot be spent
            // by an unrelated later navigation.
            is UrlInput.Empty -> userNavArmed = false
            is UrlInput.Home -> loadHome()
            is UrlInput.Load -> webView.loadUrl(target.url)
            is UrlInput.Search -> webView.loadUrl(googleSearchUrl(target.query))
        }
    }

    /**
     * Load a URL that came from saved state or a tab being rebuilt --
     * NOT from user input. Only http(s) is loaded as-is; anything else
     * (data:, blob:, a search-looking string) goes home instead of
     * being reinterpreted by [loadInput], which could send a
     * page-generated data: URL to a search engine as a query.
     */
    fun loadSavedUrl(url: String) {
        when {
            isHomeUrl(url) -> loadHome()
            isWebLink(url) -> webView.loadUrl(url)
            else -> loadHome()
        }
    }

    fun loadHome() {
        // Colours come from the shared palette (colors.xml via
        // Colors.load), never literals, so a values-night palette
        // reaches the start page too. Opaque tokens become
        // #RRGGBB; a future translucent token becomes rgba()
        // instead of silently losing its alpha.
        fun css(c: Int): String {
            val a = (c ushr 24) and 0xFF
            return if (a == 0xFF) {
                "#%06X".format(c and 0xFFFFFF)
            } else {
                // Locale.US: the alpha's decimal point must not
                // become a comma in comma-decimal locales.
                String.format(
                    java.util.Locale.US,
                    "rgba(%d,%d,%d,%.3f)",
                    (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, a / 255.0
                )
            }
        }
        // The HTML comes from startPageHtml (a fresh nonce per
        // load makes its data URL unique to the load, so no web
        // page can reproduce the URL and pose as the start
        // page's own load). Never add subresource references to
        // that HTML: see its doc for the recording invariant.
        val html = startPageHtml(
            ink = css(Colors.ON_SURFACE),
            sub = css(Colors.ON_SURFACE_VARIANT),
            brand = css(Colors.PRIMARY),
            line = css(Colors.OUTLINE_VARIANT),
            bg = css(Colors.SURFACE),
            nonce = java.util.UUID.randomUUID().toString(),
        )
        // The URL of this load as constructed from its HTML
        // (see homeDataUrl). Computed before the load starts so
        // the recorder can recognise the load when it arrives.
        homeDataUrl = startPageDataUrl(html)
        // historyUrl is HOME_URL explicitly: with null, the history
        // entry becomes about:blank and doUpdateVisitedHistory would
        // overwrite currentUrl with it, breaking every isHomeUrl
        // check (start-page detection, saved state, matching).
        // Reset before the load starts: onPageStarted resets
        // it too, but this covers the gap before that
        // callback arrives (SEN-158).
        homeCommitted = false
        webView.loadDataWithBaseURL(HOME_URL, html, "text/html", "UTF-8", HOME_URL)
        title = "PageLoupe"
        titleUrl = HOME_URL
        currentUrl = HOME_URL
    }

    fun setDesktopMode(on: Boolean) {
        if (desktopMode == on) return
        desktopMode = on
        webView.settings.userAgentString = if (on) desktopUa else mobileUa
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = on
        if (isHomeUrl(currentUrl)) loadHome() else if (currentUrl.isNotBlank()) webView.reload()
    }

    /**
     * Carry the desktop-mode flag (and its UA / viewport settings)
     * into a freshly created tab WITHOUT loading or reloading
     * anything. Rebuild paths (renderer-death
     * recovery, Phase-2 rebuild, remote-debug recreate, state
     * restore) set the tab's URL up themselves right after creation;
     * routing the carry through [setDesktopMode] would fire a reload
     * against a URL that is not loaded yet, or lose the flag
     * entirely and silently revert a desktop tab to mobile.
     * Call only before the tab's first load.
     */
    fun adoptDesktopMode(on: Boolean) {
        desktopMode = on
        // The values written here must be EXACTLY what a tab of this
        // mode carries: for mobile that is the init block's values
        // (loadWithOverviewMode = true), not setDesktopMode's
        // toggle values — copying the latter would silently flip
        // every rebuilt mobile tab's initial zoom behaviour.
        webView.settings.userAgentString = if (on) desktopUa else mobileUa
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
    }

    /**
     * Snapshot the page into [thumbnail] for the tab switcher card. The full
     * page width is kept and only the top of the viewport is taken, at exactly
     * the card's aspect ratio, so the card never crops the left/right edges.
     * Best effort.
     */
    fun captureThumbnail() {
        try {
            val w = webView.width
            val h = webView.height
            if (w <= 0 || h <= 0) return
            val targetW = 360
            val scale = targetW.toFloat() / w
            val targetH = (targetW * THUMB_ASPECT).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            webView.draw(canvas)
            thumbnail = bmp
        } catch (_: Exception) {
            // A missing thumbnail just falls back to the placeholder on the card.
        }
    }

    fun destroy() {
        expirePopupProbes()
        // The navigation snapshot dies with the tab: it holds
        // this tab's history (a secret tab's included), and
        // nothing outliving the tab may keep it. The crash
        // path is the one exception by construction — it reads
        // the snapshot from the old tab AFTER
        // destroyAfterRendererDeath, which therefore must NOT
        // clear it; the old object itself is dropped when the
        // rebuild returns.
        clearNavState()
        // A destroyed tab starts no new sequences, but reset
        // the latch anyway (SEN-360): if this object is ever
        // reused after a renderer death swallowed a finish,
        // no stale sequence may suppress sampling.
        loadStartLatch.reset()
        newDocumentLatch.reset()
        webView.stopLoading()
        if (secret) {
            // Secret tabs leave no browsing history behind.
            webView.clearHistory()
        }
        webView.destroy()
    }

    /**
     * Destroy after the renderer has died: the WebView must not be
     * called into beyond destroy() itself (no stopLoading /
     * clearHistory on a dead renderer). This must NOT route
     * through destroy(): the crash rebuild reads this tab's
     * navigation snapshot (takeNavStateFor) after this returns,
     * and destroy() clears it — routing through it would
     * silently disable crash-recovery history for every tab.
     */
    fun destroyAfterRendererDeath() {
        // The probes are separate WebViews, but they belong to
        // this tab and must not outlive it.
        expirePopupProbes()
        // The dead renderer may have swallowed the in-flight
        // sequence's finish (SEN-360).
        loadStartLatch.reset()
        newDocumentLatch.reset()
        try { webView.destroy() } catch (_: Exception) {}
    }

    companion object {
        /** Tab card thumbnail aspect ratio (height / width), shared by capture and display. */
        const val THUMB_ASPECT = 1.12f

        // The app version never changes within a process, so the
        // PackageManager IPC runs once, not once per tab.
        @Volatile private var appVersionCache: String? = null

        private fun cachedAppVersion(ctx: Context): String {
            appVersionCache?.let { return it }
            val v = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
            appVersionCache = v
            return v
        }

        /** Start page URL, in the style of Chrome's chrome://newtab/. */
        const val HOME_URL = HOME_URL_MARKER

        /**
         * Home is decided by the scheme only. Also matching the
         * look-alike host "https://pageloupe.home" would be wrong:
         * any real page served from that host would show an empty
         * address bar (it poses as the internal start page) and
         * have its Document load left out of the console/request
         * recording (isInternalDocumentUrl builds on this check).
         * The logic lives in UrlInput.kt as a pure function so
         * the JVM tests can pin it.
         */
        fun isHomeUrl(url: String): Boolean =
            jp.kaleidpixel.pageloupe.isHomeUrl(url)
    }
}
