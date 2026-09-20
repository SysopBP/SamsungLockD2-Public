package app.d2lock.notifications

import android.app.Notification

object NotificationPresentation {
    fun banner(item: LockNotification, privacy: Int): String? {
        if (privacy == 0 || item.visibility == Notification.VISIBILITY_SECRET) return null
        if (privacy == 1) return "New notification"
        val showContent = privacy == 4 || (privacy == 3 && item.visibility == Notification.VISIBILITY_PUBLIC)
        if (!showContent) return "${item.app}\nNew notification"
        return listOf(item.app, item.title, item.text).filter { it.isNotBlank() }.joinToString("\n").take(240)
    }
}
