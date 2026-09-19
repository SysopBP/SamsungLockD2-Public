package app.d2lock.security

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 0)
        }
        fun field(label: String) = EditText(activity).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(6))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            filterTouchesWhenObscured = true
            form.addView(this)
        }
        val old = if (change) field("Current D2 PIN") else null
        val pin = field(if (setup || change) "New 6-digit D2 PIN" else "6-digit D2 PIN")
        val confirm = if (setup || change) field("Confirm new PIN") else null
        // Keep validation in the dialog layout. EditText.error creates a floating
        // PopupWindow that can intercept a subsequent tap on Unlock.
        val error = TextView(activity).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            form.addView(this)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (setup) "Create your D2 PIN" else if (change) "Change D2 PIN" else "Enter D2 PIN")
            .setMessage("This PIN belongs only to D2. It does not set a Samsung screen lock.")
            .setView(form).setPositiveButton(if (setup || change) "Save PIN" else "Unlock", null)
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }.create()
        dialog.setOnDismissListener { pin.text.clear(); old?.text?.clear(); confirm?.text?.clear() }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.filterTouchesWhenObscured = true
            button.setOnClickListener {
                val entered = pin.text.toString().toCharArray()
                val previous = old?.text?.toString()?.toCharArray()
                if (entered.size != 6 || entered.any { it !in '0'..'9' } ||
                    (confirm != null && !entered.contentEquals(confirm.text.toString().toCharArray()))) {
                    entered.fill('\u0000'); previous?.fill('\u0000')
                    error.text = "Enter six digits; both new PIN entries must match."
                    return@setOnClickListener
                }
                button.isEnabled = false
                error.text = "Checking PIN…"
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                worker.execute {
                    var message = "PIN incorrect."
                    val ok = try {
                        val accepted = if (setup) { store.create(entered); true }
                            else if (change) store.change(previous!!, entered) else store.verify(entered)
                        if (!accepted) {
                            val seconds = (store.remainingMillis() + 999) / 1000
                            if (seconds > 0) message = "Too many attempts. Wait $seconds seconds."
                        }
                        accepted
                    } catch (_: Exception) { message = "D2 could not read or save its PIN. Access remains locked."; false }
                    finally { entered.fill('\u0000'); previous?.fill('\u0000') }
                    activity.runOnUiThread {
                        if (activity.isDestroyed || activity.isFinishing || !dialog.isShowing) return@runOnUiThread
                        if (ok) { dialog.dismiss(); success() }
                        else {
                            pin.text.clear(); old?.text?.clear(); confirm?.text?.clear()
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
