package app.d2lock.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import app.d2lock.MainActivity
import app.d2lock.R
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.security.PinStore

private object WidgetPrefs {
    private const val FILE = "d2_widget_preferences"
    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun style(c: Context, id: Int) = p(c).getString("style_$id", "glass") ?: "glass"
    fun action(c: Context, id: Int) = p(c).getString("action_$id", "double") ?: "double"
    fun label(c: Context, id: Int) = p(c).getBoolean("label_$id", true)
    fun save(c: Context, id: Int, style: String, action: String, label: Boolean) = p(c).edit().putString("style_$id", style).putString("action_$id", action).putBoolean("label_$id", label).apply()
    fun remove(c: Context, id: Int) = p(c).edit().remove("style_$id").remove("action_$id").remove("label_$id").apply()
}

class D2Widget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it) } }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetPrefs.remove(context, it) } }
    companion object {
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val intent = Intent(context, WidgetTapActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val view = RemoteViews(context.packageName, R.layout.d2_widget)
            val label = if (WidgetPrefs.action(context,id) == "single") "Lock D2" else "Lock D2\nDouble-tap"
            view.setTextViewText(R.id.d2_widget, if (WidgetPrefs.label(context,id)) label else "D2")
            when (WidgetPrefs.style(context,id)) {
                "amoled" -> { view.setInt(R.id.d2_widget, "setBackgroundColor", android.graphics.Color.BLACK); view.setTextColor(R.id.d2_widget, android.graphics.Color.WHITE) }
                "light" -> { view.setInt(R.id.d2_widget, "setBackgroundColor", 0xEAF4F6FA.toInt()); view.setTextColor(R.id.d2_widget, 0xFF15171C.toInt()) }
                else -> { view.setInt(R.id.d2_widget, "setBackgroundColor", 0xD9232730.toInt()); view.setTextColor(R.id.d2_widget, android.graphics.Color.WHITE) }
            }
            view.setOnClickPendingIntent(R.id.d2_widget, pending)
            manager.updateAppWidget(id, view)
        }
    }
}

/** Direct widget Activity PendingIntent avoids background activity launch trampolines. */
class WidgetTapActivity : Activity() {
    companion object { private val taps = mutableMapOf<Int, DoubleTap>() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (WidgetPrefs.action(this, id) == "single" || taps.getOrPut(id) { DoubleTap() }.tap(SystemClock.elapsedRealtime())) {
            val target = if (PinStore(this).configured()) LockScreenActivity::class.java else MainActivity::class.java
            startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
