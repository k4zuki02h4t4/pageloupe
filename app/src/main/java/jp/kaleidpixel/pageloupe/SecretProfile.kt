package jp.kaleidpixel.pageloupe

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat

/**
 * Bridge to androidx.webkit's multi-profile API: the framework
 * calls behind [SecretProfilePolicy]'s decisions. Every entry
 * point is best-effort and never throws into tab management — if
 * the device's WebView lacks multi-profile support, secret tabs
 * simply keep sharing the default profile (the limitation
 * recorded in the design records for unsupported WebViews). A failure
 * on a SUPPORTED WebView is different: it is
 * reported to the tab's listener so the user can be told the
 * isolation is not in effect (silently degrading on a capable
 * device is not an accepted outcome).
 *
 * A WebView's profile is fixed once the WebView holds data, so
 * [applyTo] runs inside BrowserTab's WebView initializer — the
 * first touch of the WebView, before settings are even read.
 *
 * Deletion semantics (per the ProfileStore reference):
 * [ProfileStore.deleteProfile] returns false only when the
 * profile does not exist; it THROWS IllegalStateException while
 * living WebViews use the profile or while the profile is loaded
 * in memory, and a successful delete is scheduled — the data may
 * leave the disk asynchronously. [deleteIfPossible] therefore
 * treats "false or threw" as one outcome, and callers pair it
 * with [clearInPlace] as the fallback. The in-place clear reaches
 * cookies and web storage only: the HTTP cache and Service
 * Worker registrations survive until a delete actually goes
 * through (the app-start delete in a fresh process is the
 * backstop).
 *
 * androidx.webkit and the androidx.core it pulls in are this
 * app's only runtime library dependencies; the whole
 * dependency set — those two and the compile-only annotation
 * jars alike — is listed with provenance and hashes in
 * docs/dependencies.md.
 */
object SecretProfile {

    /**
     * Whether this device's WebView implementation offers
     * multi-profile. The probe itself lives in ProfileSupport,
     * shared with DevToolsProfile so it runs at most once.
     */
    fun isSupported(): Boolean = ProfileSupport.isSupported()

    /**
     * Put a freshly created secret tab's WebView on the secret
     * profile. Returns true only when the profile was actually
     * assigned; false for normal tabs, on unsupported WebViews,
     * and when the assignment failed.
     */
    fun applyTo(webView: WebView, secret: Boolean): Boolean {
        val name = SecretProfilePolicy.profileNameFor(secret, isSupported()) ?: return false
        return try {
            ProfileStore.getInstance().getOrCreateProfile(name)
            WebViewCompat.setProfile(webView, name)
            DebugLog.log("tab", "secret profile applied")
            true
        } catch (t: Throwable) {
            // The tab stays on the default profile: degraded, not
            // broken — but the caller reports it (see class docs).
            DebugLog.log("tab", "secret profile apply failed: ${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * One attempt to delete the secret profile outright. True
     * when the deletion was accepted (the data may still leave
     * the disk asynchronously); false when the profile does not
     * exist, is still in use, or the call failed. Never throws.
     * Deliberately does NOT touch getProfile/getOrCreateProfile
     * first: loading the profile into memory is itself one of
     * the states deleteProfile refuses.
     */
    fun deleteIfPossible(reason: String): Boolean {
        if (!isSupported()) return false
        val started = System.nanoTime()
        var failure: String? = null
        val deleted = try {
            ProfileStore.getInstance()
                .deleteProfile(SecretProfilePolicy.SECRET_PROFILE_NAME)
        } catch (t: Throwable) {
            // The class name distinguishes "still in use / loaded"
            // (IllegalStateException) from other failures in the
            // device logs; a plain false means "does not exist".
            failure = t.javaClass.simpleName
            false
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        DebugLog.log(
            "tab",
            "secret profile delete ($reason): accepted=$deleted" +
                (failure?.let { ", threw $it" } ?: "") + ", ${ms}ms"
        )
        return deleted
    }

    /**
     * Erase what can be erased while the profile may still be in
     * use: its cookies and web storage, via the profile's own
     * managers. The HTTP cache and Service Worker registrations
     * are NOT reachable this way and survive until a delete goes
     * through. Never throws.
     */
    fun clearInPlace(reason: String) {
        if (!isSupported()) return
        val started = System.nanoTime()
        try {
            val profile = ProfileStore.getInstance()
                .getProfile(SecretProfilePolicy.SECRET_PROFILE_NAME) ?: return
            profile.cookieManager.removeAllCookies(null)
            profile.cookieManager.flush()
            profile.webStorage.deleteAllData()
            val ms = (System.nanoTime() - started) / 1_000_000
            DebugLog.log("tab", "secret profile cleared in place ($reason), ${ms}ms")
        } catch (t: Throwable) {
            DebugLog.log("tab", "secret profile clear failed ($reason): ${t.javaClass.simpleName}")
        }
    }

    /** Delete if possible, otherwise clear in place. Never throws. */
    fun wipe(reason: String) {
        if (!deleteIfPossible(reason)) clearInPlace(reason)
    }

    /**
     * The Cookie header value for a download started from
     * [webView] — the cookies of the session the page actually
     * runs in. For a tab ON the secret profile ([onSecretProfile]
     * true), the value comes from that profile's store ONLY: if
     * it cannot be read, the download goes out with NO cookies
     * rather than falling back to the default profile's session
     * (which would authenticate the secret download as the
     * normal session and mix the two profiles' cookies). For any
     * other tab — normal, or secret-but-unassigned and therefore
     * really on the default profile — the default store is the
     * correct one.
     */
    fun downloadCookie(
        webView: WebView,
        url: String,
        secret: Boolean,
        onSecretProfile: Boolean
    ): String? {
        return try {
            if (secret && onSecretProfile) {
                if (!isSupported()) {
                    null
                } else {
                    WebViewCompat.getProfile(webView).cookieManager.getCookie(url)
                }
            } else {
                android.webkit.CookieManager.getInstance().getCookie(url)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
