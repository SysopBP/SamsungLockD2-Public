package app.d2lock

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView

/** Standard D2 information/choice surface: custom content avoids the platform's square panel. */
object GuardianDialogs {
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun text(context: Context, value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
    }
    private fun shell(context: Context, title: String, icon: Int): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 22), dp(context, 22), dp(context, 22), dp(context, 16))
            background = Appearance.glass(context, 32f, 96, true)
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(context, 28), dp(context, 28)).apply { rightMargin = dp(context, 12) })
        header.addView(text(context, title, 20f).apply {
            setTypeface(typeface, Typeface.BOLD)
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 18) })
        dialog.setContentView(body)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        return dialog to body
    }
    private fun button(context: Context, label: String, primary: Boolean, clicked: () -> Unit) = text(context, label, 15f).apply {
        gravity = Gravity.CENTER; minHeight = dp(context, 48)
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        setTypeface(typeface, Typeface.BOLD)
        background = RippleDrawable(ColorStateList.valueOf(0x33ffffff),
            Appearance.glass(context, 20f, if (primary) 76 else 26, true), null)
        isFocusable = true; setOnClickListener { clicked() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
    }
    private fun finish(context: Context, dialog: Dialog) {
        dialog.show()
        val width = (context.resources.displayMetrics.widthPixels - dp(context, 40)).coerceAtLeast(dp(context, 200))
            .coerceAtMost(dp(context, 480))
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setDimAmount(.55f)
    }
    fun showInfo(context: Context, title: String, message: String, icon: Int = R.drawable.ic_lock_security,
        primary: String = "Got it", onPrimary: (() -> Unit)? = null,
        secondary: String? = null, onSecondary: (() -> Unit)? = null): Dialog {
        val (dialog, body) = shell(context, title, icon)
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (resources.displayMetrics.heightPixels * .55f).toInt()
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(text(context, message, 15f).apply { setLineSpacing(dp(context, 3).toFloat(), 1f) })
        body.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 18) })
        val actions = LinearLayout(context)
        if (secondary != null) actions.addView(button(context, secondary, false) {
            dialog.dismiss(); onSecondary?.invoke()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(context, 8) })
        actions.addView(button(context, primary, true) { dialog.dismiss(); onPrimary?.invoke() }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(actions)
        finish(context, dialog)
        return dialog
    }
    fun showChoices(context: Context, title: String, options: Array<String>, selected: Int,
        onSelected: (Dialog, Int) -> Unit): Dialog {
        val (dialog, body) = shell(context, title, R.drawable.ic_lock_security)
        options.forEachIndexed { index, option ->
            body.addView(RadioButton(context).apply {
                text = option; textSize = 16f; setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(Color.WHITE)
                isChecked = index == selected; minHeight = dp(context, 52)
                setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
                background = Appearance.glass(context, 20f, 28, true)
                setOnClickListener { dialog.dismiss(); onSelected(dialog, index) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 8) })
        }
        body.addView(button(context, "Cancel", false) { dialog.dismiss() })
        finish(context, dialog)
        return dialog
    }
}
