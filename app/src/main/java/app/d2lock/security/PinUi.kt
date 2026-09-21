package app.d2lock.security

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.d2lock.Appearance
import java.util.concurrent.Executors

object PinUi {
    private val worker = Executors.newSingleThreadExecutor()

    fun protect(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.window.setHideOverlayWindows(true)
    }

    fun show(activity: Activity, setup: Boolean = false, change: Boolean = false,
             success: () -> Unit, cancel: () -> Unit = {}): AlertDialog {
        val store = PinStore(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        form.addView(TextView(activity).apply {
            id = app.d2lock.R.id.d2_pin_prompt
            text = when {
                setup -> "Create a 6-digit PIN for D2"
                change -> "Verify your current PIN, then choose a new one"
                else -> "Enter your 6-digit D2 PIN"
            }
            textSize = 15f
            setTextColor(Appearance.secondary(activity))
            setPadding(dp(4), 0, dp(4), dp(16))
        })

        fun field(label: String, viewId: Int = View.NO_ID) = EditText(activity).apply {
            if (viewId != View.NO_ID) id = viewId
            hint = label
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(6))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            filterTouchesWhenObscured = true
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Appearance.surface(activity))
                setStroke(dp(1), Appearance.secondary(activity))
            }
            form.addView(this, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(12) })
        }

        val old = if (change) field("Current D2 PIN") else null
        val pin = field(if (setup || change) "New 6-digit D2 PIN" else "D2 PIN", app.d2lock.R.id.d2_pin_input)
        val confirm = if (setup || change) field("Confirm new PIN") else null
        val error = TextView(activity).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(220, 70, 70))
            setPadding(dp(4), dp(2), dp(4), dp(4))
            form.addView(this)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (setup) "Create D2 PIN" else if (change) "Change D2 PIN" else "Unlock D2")
            .setMessage("This PIN belongs only to D2. It does not set or change your Samsung screen lock.")
            .setView(form)
            .setPositiveButton(if (setup || change) "Save PIN" else "Unlock", null)
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .create()

        dialog.setOnDismissListener { pin.text.clear(); old?.text?.clear(); confirm?.text?.clear() }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // One UI 9-style rounded D2 security sheet. Keep authentication behavior unchanged.
            dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(Appearance.surface(activity))
                setStroke(dp(1), Appearance.secondary(activity))
            })
            dialog.window?.decorView?.clipToOutline = true
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            listOf(button, negative).forEach { action ->
                action.isAllCaps = false
                action.setPadding(dp(18), 0, dp(18), 0)
                action.background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), Appearance.secondary(activity))
                }
            }
            button.filterTouchesWhenObscured = true
            button.setOnClickListener {
                val entered = pin.text.toString().toCharArray()
                val previous = old?.text?.toString()?.toCharArray()
                val validPin = entered.size == 6 && entered.all { it in '0'..'9' }

                if (!validPin) {
                    entered.fill('\u0000'); previous?.fill('\u0000')
                    error.text = if (setup || change)
                        "Enter a 6-digit PIN in both new PIN fields."
                    else
                        "Enter your 6-digit D2 PIN."
                    return@setOnClickListener
                }
                if (confirm != null && !entered.contentEquals(confirm.text.toString().toCharArray())) {
                    entered.fill('\u0000'); previous?.fill('\u0000')
                    error.text = "New PIN entries do not match."
                    return@setOnClickListener
                }
                if (change && (previous == null || previous.size != 6 || previous.any { it !in '0'..'9' })) {
                    entered.fill('\u0000'); previous?.fill('\u0000')
                    error.text = "Enter your current 6-digit D2 PIN."
                    return@setOnClickListener
                }

                button.isEnabled = false
                error.setTextColor(Appearance.secondary(activity))
                error.text = "Checking PIN…"
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                worker.execute {
                    var message = "PIN incorrect."
                    val ok = try {
                        val accepted = if (setup) { store.create(entered); true }
                        else if (change) store.change(previous!!, entered)
                        else store.verify(entered)
                        if (!accepted) {
                            val seconds = (store.remainingMillis() + 999) / 1000
                            if (seconds > 0) message = "Too many attempts. Wait $seconds seconds."
                        }
                        accepted
                    } catch (_: Exception) {
                        message = "D2 could not read or save its PIN. Access remains locked."
                        false
                    } finally {
                        entered.fill('\u0000'); previous?.fill('\u0000')
                    }
                    activity.runOnUiThread {
                        if (activity.isDestroyed || activity.isFinishing || !dialog.isShowing) return@runOnUiThread
                        if (ok) { dialog.dismiss(); success() }
                        else {
                            pin.text.clear(); old?.text?.clear(); confirm?.text?.clear()
                            error.setTextColor(Color.rgb(220, 70, 70))
                            error.text = message
                            button.isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            dialog.setCancelable(true)
                        }
                    }
                }
            }
        }
        dialog.show()
        return dialog
    }
}
