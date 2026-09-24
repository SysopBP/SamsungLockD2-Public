package app.d2lock.notifications

import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.os.Build
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

data class CallControl(val label: String, val intent: PendingIntent)
data class CallNotification(val key: String, val controls: List<CallControl>, val open: PendingIntent?)

/** Phone controls stay separate from message previews; no caller name or number is stored. */
object CallNotificationStore {
    val items = CopyOnWriteArrayList<CallNotification>()
    @Volatile var onChanged: (() -> Unit)? = null

    fun update(sbn: StatusBarNotification, allowedPackages: Set<String>) {
        items.removeAll { it.key == sbn.key }
        val notification = sbn.notification
        if (sbn.packageName !in allowedPackages || notification.category != Notification.CATEGORY_CALL) {
            onChanged?.invoke()
            return
        }
        // A notification cannot delegate lock-screen access to an unrelated intent creator.
        fun trusted(intent: PendingIntent?) = intent?.takeIf { it.creatorPackage in allowedPackages }
        val controls = notification.actions.orEmpty().mapNotNull { action ->
            if (action.isAuthenticationRequired || !action.remoteInputs.isNullOrEmpty()) null
            else trusted(action.actionIntent)?.let { CallControl(action.title.toString(), it) }
        }.take(3)
        val open = trusted(notification.contentIntent) ?: trusted(notification.fullScreenIntent)
        if (controls.isNotEmpty() || open != null) items.add(CallNotification(sbn.key, controls, open))
        onChanged?.invoke()
    }

    fun send(activity: Activity, pending: PendingIntent) {
        // Only invoked by an explicit tap on a trusted phone control while D2 is visible.
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34)
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        pending.send(activity, 0, null, null, null, null, options.toBundle())
    }
}
