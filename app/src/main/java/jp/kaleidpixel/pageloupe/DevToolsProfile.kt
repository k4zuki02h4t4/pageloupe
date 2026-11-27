package jp.kaleidpixel.pageloupe

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat

/**
 * The DevTools frontend's own profile.
 *
 * The frontend (served from chrome-devtools-frontend.appspot.com)
 * keeps its settings — UI language, panel layout, preferences,
 * console history — in its origin's site data. Run on the
 * default profile, that data is indistinguishable from browsing
 * data, and a clear-data wipe takes the settings with it: the
 * frontend comes back reset to defaults — screencast on,
 * language and layout gone — and every setting has to be
 * re-made by hand.
 * Sparing it per origin does not work either: WebStorage's
 * origin enumeration does not list localStorage-only origins
 * and deleteOrigin does not clear localStorage (both
 * confirmed on a real device), so the only
 * complete wipe — deleteAllData — is all-or-nothing within
 * one profile.
 *
 * The way out is the partition desktop Chrome uses: the
 * frontend runs on this dedicated profile, and the clear-data
 * wipe — which only reaches the default profile (and, through
 * SecretProfile, the secret profile) — cannot touch it.
 * Clearing history and data must not reset the DevTools
 * settings; this profile is how that holds. It is never
 * deleted and never wiped, and the state it keeps is the
 * DevTools' own, console history of inspected pages included
 * — the same trade desktop Chrome makes, and an accepted one
 * (recorded in REVIEW.md).
 *
 * On WebViews without multi-profile support the frontend stays
 * on the default profile and the settings are wiped with
 * everything else: degraded, not broken.
 */
object DevToolsProfile {

    /** Outcome of an [applyTo] attempt. */
    enum class ApplyResult {
        /** The WebView is on the DevTools profile. */
        APPLIED,

        /**
         * The WebView implementation has no multi-profile
         * support: the frontend stays on the default profile.
         * The accepted degradation (see the class docs); the
         * caller stays silent.
         */
        UNSUPPORTED,

        /**
         * Multi-profile is supported but the assignment failed:
         * the frontend stays on the default profile and the next
         * clear-data wipe takes the DevTools settings with it.
         * NOT an accepted degradation — the caller surfaces it
         * (the stance SecretProfile takes for secret
         * tabs).
         */
        FAILED
    }

    /**
     * The most recent [applyTo] outcome in this process; null
     * until the first attempt. Process scope on purpose: the
     * clear-data dialog reads it to keep its wording true, and
     * an Activity-scoped record would forget a failure whenever
     * the Activity is recreated.
     */
    @Volatile
    var lastApplyResult: ApplyResult? = null
        private set

    /**
     * Put a freshly created frontend WebView on the DevTools
     * profile (its name is pinned by DevToolsProfilePolicy).
     * Never throws.
     */
    fun applyTo(webView: WebView): ApplyResult {
        val result = apply(webView)
        lastApplyResult = result
        return result
    }

    private fun apply(webView: WebView): ApplyResult {
        when (DevToolsProfilePolicy.probeCourse(ProfileSupport.supportState())) {
            DevToolsProfilePolicy.ProbeCourse.UNSUPPORTED -> return ApplyResult.UNSUPPORTED
            DevToolsProfilePolicy.ProbeCourse.FAILED -> return ApplyResult.FAILED
            DevToolsProfilePolicy.ProbeCourse.PROCEED -> Unit
        }
        return try {
            ProfileStore.getInstance().getOrCreateProfile(DevToolsProfilePolicy.PROFILE_NAME)
            WebViewCompat.setProfile(webView, DevToolsProfilePolicy.PROFILE_NAME)
            DebugLog.log("dt", "devtools profile applied")
            ApplyResult.APPLIED
        } catch (t: Throwable) {
            DebugLog.log("dt", "devtools profile apply failed: ${t.javaClass.simpleName}")
            ApplyResult.FAILED
        }
    }
}
