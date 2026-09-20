package app.d2lock

import android.app.Notification
import app.d2lock.notifications.LockNotification
import app.d2lock.notifications.NotificationPresentation
import org.junit.Assert.*
import org.junit.Test

class NotificationPresentationTest {
    private fun item(visibility: Int) = LockNotification("key", "Messages", "Private sender", "Private message", 1, visibility)
    @Test fun secretAndHiddenNeverProduceBanner() {
        for (privacy in 0..4) assertNull(NotificationPresentation.banner(item(Notification.VISIBILITY_SECRET), privacy))
        assertNull(NotificationPresentation.banner(item(Notification.VISIBILITY_PUBLIC), 0))
    }
    @Test fun countAndAppModesDoNotLeakMessageOrSender() {
        assertEquals("New notification", NotificationPresentation.banner(item(Notification.VISIBILITY_PRIVATE), 1))
        for (mode in listOf(2, 3)) assertEquals("Messages\nNew notification",
            NotificationPresentation.banner(item(Notification.VISIBILITY_PRIVATE), mode))
    }
    @Test fun contentRequiresPublicVisibilityOrExplicitPrivatePreviewChoice() {
        assertTrue(NotificationPresentation.banner(item(Notification.VISIBILITY_PUBLIC), 3)!!.contains("Private message"))
        assertTrue(NotificationPresentation.banner(item(Notification.VISIBILITY_PRIVATE), 4)!!.contains("Private message"))
    }
}
