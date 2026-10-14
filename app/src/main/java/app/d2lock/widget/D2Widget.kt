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
import app.d2lock.root.DmcRecovery
import android.content.ComponentName
import android.view.View
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
    fun density(c: Context, id: Int) = p(c).getString("density_$id", "comfortable") ?: "comfortable"
    fun save(c: Context, id: Int, style: String, action: String, label: Boolean) = p(c).edit().putString("style_$id", style).putString("action_$id", action).putBoolean("label_$id", label).apply()
    fun srp(c: Context, id: Int) = p(c).getBoolean("srp_$id", true)
    fun remove(c: Context, id: Int) = p(c).edit().remove("style_$id").remove("action_$id").remove("label_$id").remove("density_$id").remove("srp_$id").apply()
}

class D2Widget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it) } }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetPrefs.remove(context, it) } }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH) {
            val pending = goAsync()
            DmcRecovery.request(context, "widget_refresh", retry = 3, force = true, done = {
                try { updateAll(context) } finally { pending.finish() }
            })
        }
    }
    companion object {
        private const val REFRESH = "app.d2lock.widget.REFRESH_SRP"
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, D2Widget::class.java)).forEach { update(context, manager, it) }
        }
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val intent = Intent(context, WidgetTapActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val view = RemoteViews(context.packageName, R.layout.d2_widget)
            val options = manager.getAppWidgetOptions(id)
            val compact = WidgetPrefs.density(context, id) == "compact" ||
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140) < 110 ||
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80) < 65
            val label = if (compact) "D2" else if (WidgetPrefs.action(context,id) == "single") "Lock D2" else "Lock D2 · Double-tap"
            view.setTextViewText(R.id.d2_widget_title, if (WidgetPrefs.label(context,id)) label else "D2")
            val textSize = if (compact) 12f else when (WidgetPrefs.density(context,id)) { "large" -> 20f; else -> 16f }
            view.setTextViewTextSize(R.id.d2_widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
            val status = DmcRecovery.status(context)
            val age = System.currentTimeMillis() - status.checkedAt
            val fresh = status.checkedAt > 0 && age in 0..900_000L
            val summary = when {
                status.checkedAt == 0L -> "SRP: unknown"
                !fresh -> "SRP: stale"
                status.targetsMet -> "SRP: verified"
                else -> "SRP: unverified"
            }
            val checked = if (status.checkedAt > 0) java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                .format(java.util.Date(status.checkedAt)) else "never"
            fun flag(value: Int?) = when(value) { 0 -> "off"; 1 -> "on"; else -> "?" }
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140)
            fun available(value: Boolean?) = when (value) { true -> "OK"; false -> "No"; null -> "?" }
            // SRP verification describes DMC recovery targets only; it is not proof
            // that Android's periodic credential / strong-auth hook executed.
            val rows = if (height >= 120) listOf(
                summary,
                "Server: ${available(status.systemServer)}",
                "SRP lock: ${flag(status.screenLock)}",
                "Maint: ${flag(status.maintenance)}",
                "AT: ${flag(status.atCommand)}",
                "Download: ${available(status.downloadAllowed)}",
                "PIN timer: unverified",
                "Checked $checked"
            ) else if (height >= 80) listOf(
                summary,
                "Lock ${flag(status.screenLock)} · Maint ${flag(status.maintenance)}",
                "AT ${flag(status.atCommand)} · DL ${available(status.downloadAllowed)}",
                "PIN timer: unverified",
                checked
            ) else listOf(summary, "PIN timer: unverified", checked)
            val stats = rows.joinToString("\n")
            view.setTextViewText(R.id.d2_widget_srp, stats)
            view.setViewVisibility(R.id.d2_widget_srp, if (WidgetPrefs.srp(context, id)) View.VISIBLE else View.GONE)
            view.setInt(R.id.d2_widget_srp, "setMaxLines", rows.size)
            view.setTextViewTextSize(R.id.d2_widget_srp, android.util.TypedValue.COMPLEX_UNIT_SP,
                if (width < 110 || height < 80) 9f else 11f)
            val light = WidgetPrefs.style(context,id) == "light"
            val background = when (WidgetPrefs.style(context,id)) {
                "galaxy" -> 0xB8141820.toInt()
                "amoled" -> android.graphics.Color.BLACK
                "light" -> 0xEAF4F6FA.toInt()
                else -> 0xD9232730.toInt()
            }
            view.setInt(R.id.d2_widget, "setBackgroundColor", background)
            val color = if (light) 0xFF15171C.toInt() else android.graphics.Color.WHITE
            view.setTextColor(R.id.d2_widget_title, color)
            view.setTextColor(R.id.d2_widget_srp, color)
            val padding = ((if (compact) 4 else 8) * context.resources.displayMetrics.density).toInt()
            view.setViewPadding(R.id.d2_widget, padding, padding, padding, padding)
            view.setOnClickPendingIntent(R.id.d2_widget, pending)
            view.setOnClickPendingIntent(R.id.d2_widget_title, pending)
            val refresh = PendingIntent.getBroadcast(context, id,
                Intent(context, D2Widget::class.java).setAction(REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            view.setOnClickPendingIntent(R.id.d2_widget_srp, refresh)
            view.setContentDescription(R.id.d2_widget_srp, "$stats. Tap to refresh SRP status.")
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
