package jp.kaleidpixel.pageloupe

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Palette: res/values/colors.xml is the single source of truth. The vars
 * below only cache what load() read from resources (defaults are the
 * light values, so a read before load is still correct); load() runs at
 * the top of MainActivity.onCreate, so the values-night palette takes
 * effect per configuration (a day/night switch recreates the activity).
 */
object Colors {
    var PRIMARY = 0xFF0B57D0.toInt()
    var PRIMARY_CONTAINER = 0xFFD3E3FD.toInt()
    var ON_SURFACE = 0xFF1F1F1F.toInt()
    var ON_SURFACE_VARIANT = 0xFF5F6368.toInt()
    var SURFACE = 0xFFFFFFFF.toInt()
    var SURFACE_CONTAINER_LOW = 0xFFF8F9FA.toInt()
    var SURFACE_CONTAINER = 0xFFF0F3F8.toInt()
    var SURFACE_CONTAINER_HIGH = 0xFFE8EAED.toInt()
    var OUTLINE = 0xFF80868B.toInt()
    var OUTLINE_VARIANT = 0xFFDADCE0.toInt()
    var ERROR = 0xFFB3261E.toInt()
    var SCRIM = 0x66000000
    // App-specific roles below the MD3 scheme roles.
    var ICON = 0xFF3C4043.toInt()
    var WARN = 0xFFB06000.toInt()
    var CARD_ACTIVE = 0xFFE8F0FE.toInt()

    fun load(ctx: Context) {
        fun c(res: Int) = ctx.getColor(res)
        PRIMARY = c(R.color.primary)
        PRIMARY_CONTAINER = c(R.color.primary_container)
        ON_SURFACE = c(R.color.on_surface)
        ON_SURFACE_VARIANT = c(R.color.on_surface_variant)
        SURFACE = c(R.color.surface)
        SURFACE_CONTAINER_LOW = c(R.color.surface_container_low)
        SURFACE_CONTAINER = c(R.color.surface_container)
        SURFACE_CONTAINER_HIGH = c(R.color.surface_container_high)
        OUTLINE = c(R.color.outline)
        OUTLINE_VARIANT = c(R.color.outline_variant)
        ERROR = c(R.color.error)
        SCRIM = c(R.color.scrim)
        ICON = c(R.color.icon)
        WARN = c(R.color.warn)
        CARD_ACTIVE = c(R.color.card_active)
    }
}

object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun roundBg(color: Int, radiusDp: Float, ctx: Context): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.setColor(color)
        g.cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        return g
    }

    fun circleBg(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    fun iconButton(ctx: Context, resId: Int, desc: String, sizeDp: Int = 44): ImageButton {
        val b = ImageButton(ctx)
        b.setImageResource(resId)
        b.contentDescription = desc
        b.background = null
        b.setColorFilter(Colors.ICON)
        b.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(ctx, 10)
        b.setPadding(pad, pad, pad, pad)
        b.layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        return b
    }

    fun setButtonActive(b: ImageButton, active: Boolean) {
        if (active) {
            b.background = circleBg(Colors.PRIMARY_CONTAINER)
            b.setColorFilter(Colors.PRIMARY)
        } else {
            b.background = null
            b.setColorFilter(Colors.ICON)
        }
    }

    fun textRow(ctx: Context, text: String, sizeSp: Float = 15f, color: Int = Colors.ON_SURFACE): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        t.gravity = Gravity.CENTER_VERTICAL
        return t
    }

    /**
     * Give a custom row the semantics of a checkbox in the
     * given state: the state goes to the state description,
     * and the node reports the checkbox role with its checked
     * state, so TalkBack announces role and state from the
     * semantics instead of a state baked into the label text
     * (which no resource lookup could ever localize). The
     * "ON"/"OFF" state words are literals for now; they join
     * the string resources in the i18n resource pass.
     *
     * The row deliberately carries NO content description
     * (SEN-283): a content description on the row replaces
     * its children's text in the announcement, so a row with
     * a description line under its label (the settings and
     * clear-data rows) or a sub-line (the export rows' date
     * and size) would never have that text read. Without one,
     * TalkBack composes the row's announcement from the child
     * texts — label and description alike — and the semantics
     * here add the checkbox role and state on top.
     * Re-apply whenever the row's state changes: the delegate
     * captures the state it was applied with.
     */
    fun applyCheckboxSemantics(row: View, checked: Boolean) {
        row.stateDescription = if (checked) "ON" else "OFF"
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfo,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = CheckBox::class.java.name
                info.isCheckable = true
                info.isChecked = checked
            }
        }
    }

    /**
     * Apply the search-field privacy rule (SEC-5) to an
     * input field whose text can name private data (URLs,
     * queries, titles): no suggestions and no personalized
     * IME learning. [baseInputType] and [extraImeOptions]
     * carry the field's own needs (the URI variation,
     * IME_ACTION_GO). Every EditText goes through this so a
     * new field cannot silently miss the pair — SEC-138 and
     * SEN-287 were both a field that missed it.
     */
    fun applyPrivateInput(input: EditText, baseInputType: Int, extraImeOptions: Int = 0) {
        input.inputType = baseInputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.imeOptions = extraImeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        // The browser's own inputs are never Autofill material
        // (audit hardening): what is typed into the address bar
        // or a JS prompt must not be offered to the Autofill
        // service for saving or later reuse, in any tab.
        input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(Colors.OUTLINE_VARIANT)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        return v
    }

}
