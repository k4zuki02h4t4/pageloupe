package jp.kaleidpixel.pageloupe

import androidx.webkit.WebViewFeature

/**
 * Whether this device's WebView implementation offers
 * multi-profile support. The probe is shared by every profile
 * bridge (SecretProfile, DevToolsProfile) so it runs at most
 * once: a definitive answer is cached; a failed probe is not
 * (a transient failure must not condemn the whole process to
 * "unsupported"), and is logged instead. A failed probe is
 * reported as UNKNOWN, distinct from a definitive UNSUPPORTED:
 * callers that surface failures (DevToolsProfile) must not let
 * "could not tell" pass silently as the accepted degradation.
 */
object ProfileSupport {

    /** The probe's answer. UNKNOWN means the probe itself failed. */
    enum class SupportState { SUPPORTED, UNSUPPORTED, UNKNOWN }

    @Volatile private var supportedCache: Boolean? = null

    fun supportState(): SupportState {
        supportedCache?.let { return if (it) SupportState.SUPPORTED else SupportState.UNSUPPORTED }
        val v = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        } catch (t: Throwable) {
            DebugLog.log("profile", "multi-profile probe failed: ${t.javaClass.simpleName}")
            return SupportState.UNKNOWN
        }
        supportedCache = v
        DebugLog.log("profile", "multi-profile supported=$v")
        return if (v) SupportState.SUPPORTED else SupportState.UNSUPPORTED
    }

    fun isSupported(): Boolean = supportState() == SupportState.SUPPORTED
}
