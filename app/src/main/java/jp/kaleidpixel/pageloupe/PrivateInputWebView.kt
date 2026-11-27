package jp.kaleidpixel.pageloupe

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * A WebView that can keep the IME from learning what is
 * typed into web page forms (SEN-290). Chromium builds
 * the EditorInfo for page fields itself and never sets
 * IME_FLAG_NO_PERSONALIZED_LEARNING — not even on the
 * secret profile (verified on device 2026-11-24: a secret
 * tab's form reached Gboard with the flag clear and the
 * keyboard in its normal learning mode) — so when
 * [noLearning] is set, the flag is added to every input
 * connection this view creates. The flag is fixed at
 * construction because a tab's secret state never
 * changes, so there is no mid-session switching gap.
 *
 * One assumption, stated rather than left implicit (audit
 * hardening): [noLearning] is a constructor property, so it
 * is assigned only once the WebView super-constructor has
 * returned; if a WebView implementation ever called
 * onCreateInputConnection during construction, the flag
 * would read as false for that one connection. The platform
 * calls it on input binding (focus), never during
 * construction.
 */
class PrivateInputWebView(ctx: Context, private val noLearning: Boolean) : WebView(ctx) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        if (noLearning) {
            outAttrs.imeOptions = outAttrs.imeOptions or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        return connection
    }
}
