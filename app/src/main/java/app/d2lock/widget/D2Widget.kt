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

class D2Widget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val intent = Intent(context, WidgetTapActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val pending = PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val view = RemoteViews(context.packageName, R.layout.d2_widget)
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
        if (taps.getOrPut(id) { DoubleTap() }.tap(SystemClock.elapsedRealtime())) {
            val target = if (PinStore(this).configured()) LockScreenActivity::class.java else MainActivity::class.java
            startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
