package app.d2lock.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

data class LockNotification(val key: String, val app: String, val title: String, val text: String, val time: Long)

object NotificationStore {
    val items = CopyOnWriteArrayList<LockNotification>()
    @Volatile var onChanged: (() -> Unit)? = null
}

class LockNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationStore.items.clear()
        activeNotifications?.forEach(::put)
        NotificationStore.onChanged?.invoke()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        put(sbn)
        NotificationStore.onChanged?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationStore.items.removeAll { it.key == sbn.key }
        NotificationStore.onChanged?.invoke()
    }

    private fun put(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || !sbn.isClearable) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        NotificationStore.items.removeAll { it.key == sbn.key }
        NotificationStore.items.add(0, LockNotification(sbn.key, app, title, text, sbn.postTime))
        while (NotificationStore.items.size > 5) NotificationStore.items.removeAt(NotificationStore.items.lastIndex)
    }
}
