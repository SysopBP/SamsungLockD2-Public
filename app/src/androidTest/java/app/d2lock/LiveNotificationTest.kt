package app.d2lock

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.notifications.LockNotification
import app.d2lock.notifications.NotificationStore
import app.d2lock.security.PinStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveNotificationTest {
    @Test fun liveMessageShowsCompactBannerAndOpeningRequiresPin() {
        TestDevice.wake()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credential = File(context.noBackupFilesDir, "d2-pin.json")
        assertFalse(credential.exists())
        PinStore(context).create("246810".toCharArray())
        Prefs.setKiosk(context, false)
        Prefs.setLiveNotifications(context, true)
        Prefs.setNotificationPrivacy(context, 2)
        val pending = PendingIntent.getActivity(context, 77, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val message = LockNotification("live", "Messages", "Private sender", "Secret message body", 1,
            Notification.VISIBILITY_PRIVATE, pending)
        try {
            ActivityScenario.launch<LockScreenActivity>(Intent(context, LockScreenActivity::class.java)).use { scenario ->
                var task = -1
                scenario.onActivity { task = it.taskId }
                TestDevice.focusTask(task)
                SystemClock.sleep(400)
                scenario.onActivity {
                    NotificationStore.items.add(message)
                    NotificationStore.onChanged?.invoke()
                    NotificationStore.onPosted?.invoke(message)
                }
                onView(withText("Messages\nNew notification")).check(matches(isDisplayed())).perform(click())
                onView(withHint("6-digit D2 PIN")).check(matches(isDisplayed()))
                onView(withText("Cancel")).perform(click())
                // Changing visibility on an existing notification must remove stale banner text.
                scenario.onActivity {
                    NotificationStore.items.clear()
                    NotificationStore.items.add(message.copy(visibility = Notification.VISIBILITY_SECRET))
                    NotificationStore.onChanged?.invoke()
                }
                onView(withText("Messages\nNew notification")).check(matches(withEffectiveVisibility(Visibility.GONE)))
            }
        } finally {
            NotificationStore.items.clear()
            pending.cancel()
            Prefs.setNotificationPrivacy(context, 2)
            credential.delete(); File(credential.path + ".bak").delete()
        }
    }
}
