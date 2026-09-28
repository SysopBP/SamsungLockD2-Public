package app.d2lock.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.content.Intent
import java.util.concurrent.CopyOnWriteArrayList

data class LockNotification(val key: String, val app: String, val title: String, val text: String, val time: Long, val visibility: Int,
    val contentIntent: android.app.PendingIntent? = null, val packageName: String = app)

object NotificationStore {
    @Volatile var listener: LockNotificationListener? = null
    val items = CopyOnWriteArrayList<LockNotification>()
    @Volatile var onChanged: (() -> Unit)? = null
    @Volatile var onPosted: ((LockNotification) -> Unit)? = null
}

class LockNotificationListener : NotificationListenerService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Notification listeners receive callbacks through a system binding.
        // Reject accidental startForegroundService calls promptly so their
        // timeout cannot crash the app process.
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onListenerConnected() {
        NotificationStore.listener = this
        NotificationStore.items.clear()
        CallNotificationStore.items.clear()
        CallNotificationStore.onChanged?.invoke()
        activeNotifications?.forEach(::put)
        NotificationStore.onChanged?.invoke()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val previous = NotificationStore.items.firstOrNull { it.key == sbn.key }
        val posted = put(sbn)
        NotificationStore.onChanged?.invoke()
        if (posted != null && (previous == null || previous.title != posted.title || previous.text != posted.text ||
            (sbn.notification.category == Notification.CATEGORY_MESSAGE && previous.time != posted.time)))
            NotificationStore.onPosted?.invoke(posted)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationStore.items.removeAll { it.key == sbn.key }
        CallNotificationStore.items.removeAll { it.key == sbn.key }
        CallNotificationStore.onChanged?.invoke()
        NotificationStore.onChanged?.invoke()
    }

    override fun onListenerDisconnected() {
        NotificationStore.listener = null
        CallNotificationStore.items.clear()
        CallNotificationStore.onChanged?.invoke()
        NotificationStore.items.clear()
        NotificationStore.onChanged?.invoke()
    }

    fun dismiss(key: String) {
        cancelNotification(key)
    }

    private fun put(sbn: StatusBarNotification): LockNotification? {
        CallNotificationStore.update(sbn, app.d2lock.root.KioskCallApps.resolve(this))
        NotificationStore.items.removeAll { it.key == sbn.key }
        if (sbn.packageName == packageName || sbn.notification.category == Notification.CATEGORY_CALL ||
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            (!sbn.isClearable && sbn.notification.category != Notification.CATEGORY_MESSAGE)) return null
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val item = LockNotification(sbn.key, app, title, text, sbn.postTime,
            sbn.notification.visibility, sbn.notification.contentIntent, sbn.packageName)
        // Some apps/services repost the same visible notification under a new key.
        // Collapse only true presentation duplicates; distinct messages from the same
        // package remain separate notifications.
        NotificationStore.items.removeAll {
            it.packageName == item.packageName &&
                it.title == item.title && it.text == item.text
        }
        NotificationStore.items.add(0, item)
        while (NotificationStore.items.size > 20) NotificationStore.items.removeAt(NotificationStore.items.lastIndex)
        return item
    }
}
