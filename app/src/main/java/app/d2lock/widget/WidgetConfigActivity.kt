package app.d2lock.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import app.d2lock.Appearance
import app.d2lock.R

class WidgetConfigActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    override fun onCreate(state: Bundle?) {
        setTheme(if (Appearance.dark(this)) R.style.Theme_D2_Dark else R.style.Theme_SamsungLock)
        super.onCreate(state)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val prefs = getSharedPreferences("d2_widget_preferences", MODE_PRIVATE)
        var style = prefs.getString("style_" + widgetId, "glass") ?: "glass"
        var action = prefs.getString("action_" + widgetId, "double") ?: "double"
        var density = prefs.getString("density_" + widgetId, "comfortable") ?: "comfortable"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setBackgroundColor(Appearance.background(this@WidgetConfigActivity))
        }
        root.addView(TextView(this).apply { text="D2 Widget"; textSize=30f; setTextColor(Appearance.text(this@WidgetConfigActivity)) })
        root.addView(TextView(this).apply { text="Customize this widget"; textSize=14f; setTextColor(Appearance.secondary(this@WidgetConfigActivity)); setPadding(0,dp(4),0,dp(22)) })
        fun addChoice(title:String, values:Array<String>, keys:Array<String>, current:()->String, set:(String)->Unit) {
            lateinit var row: TextView
            // Keep this assignment separate so the row can update itself after a glass-dialog selection.
            row=TextView(this).apply {
                text=title + "\n" + values[keys.indexOf(current()).coerceAtLeast(0)] + "   ›"
                textSize=17f; setTextColor(Appearance.text(this@WidgetConfigActivity)); gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(18),0,dp(18),0); background=Appearance.glass(this@WidgetConfigActivity,28f,34,true)
                setOnClickListener {
                    val selected = keys.indexOf(current()).coerceAtLeast(0)
                    val dialogContent = LinearLayout(this@WidgetConfigActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(6), dp(14), dp(10))
                    }
                    values.forEachIndexed { index, value ->
                        val option = LinearLayout(this@WidgetConfigActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(14), dp(8), dp(14), dp(8))
                            background = Appearance.glass(this@WidgetConfigActivity, 24f, if (index == selected) 52 else 28, true)
                        }
                        val radio = RadioButton(this@WidgetConfigActivity).apply {
                            isChecked = index == selected
                            buttonTintList = android.content.res.ColorStateList(
                                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                intArrayOf(Appearance.accent(this@WidgetConfigActivity), Appearance.secondary(this@WidgetConfigActivity))
                            )
                            isClickable = false
                        }
                        val label = TextView(this@WidgetConfigActivity).apply {
                            this.text = value
                            textSize = 17f
                            setTextColor(Appearance.text(this@WidgetConfigActivity))
                        }
                        option.addView(radio, LinearLayout.LayoutParams(dp(48), dp(54)))
                        option.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                        option.setOnClickListener {
                            set(keys[index])
                            row.text = title + "\n" + values[index] + "   ›"
                            (dialogContent.tag as? AlertDialog)?.dismiss()
                        }
                        dialogContent.addView(option, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(8) })
                    }
                    val dialog = AlertDialog.Builder(this@WidgetConfigActivity)
                        .setTitle(title)
                        .setView(dialogContent)
                        .setNegativeButton("Cancel", null)
                        .create()
                    dialogContent.tag = dialog
                    dialog.setOnShowListener {
                        dialog.window?.setBackgroundDrawable(Appearance.glass(this@WidgetConfigActivity, 30f, 76, true))
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Appearance.accent(this@WidgetConfigActivity))
                    }
                    dialog.show()
                }
            }
            root.addView(row, LinearLayout.LayoutParams(-1,dp(76)).apply{bottomMargin=dp(10)})
        }
        addChoice("Widget style", arrayOf("D2 Glass","Galaxy Glass","AMOLED Black","Light"), arrayOf("glass","galaxy","amoled","light"), {style}) {style=it}
        addChoice("Tap action", arrayOf("Double-tap to lock","Single-tap to lock"), arrayOf("double","single"), {action}) {action=it}
        addChoice("Widget density", arrayOf("Compact","Comfortable","Large"), arrayOf("compact","comfortable","large"), {density}) {density=it}
        val showLabel=CheckBox(this).apply {
            text="Show widget label"; textSize=17f; setTextColor(Appearance.text(this@WidgetConfigActivity))
            isChecked=prefs.getBoolean("label_" + widgetId,true); setPadding(dp(10),dp(8),0,dp(8))
        }
        root.addView(showLabel)
        root.addView(TextView(this).apply {
            text="Save widget"; textSize=17f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@WidgetConfigActivity))
            background=Appearance.glass(this@WidgetConfigActivity,28f,42,true)
            setOnClickListener {
                prefs.edit().putString("style_" + widgetId,style).putString("action_" + widgetId,action).putString("density_" + widgetId,density).putBoolean("label_" + widgetId,showLabel.isChecked).apply()
                D2Widget.update(this@WidgetConfigActivity,getSystemService(AppWidgetManager::class.java),widgetId)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)); finish()
            }
        },LinearLayout.LayoutParams(-1,dp(64)).apply{topMargin=dp(18)})
        setContentView(root)
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
