package app.d2lock

import android.app.Notification
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.notifications.LockNotification
import app.d2lock.notifications.NotificationStore
import app.d2lock.security.PinStore
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NotificationPreviewTest {
    @Test fun privatePreviewsRequireExplicitChoiceAndSecretStaysHidden() {
        TestDevice.wake()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val credential = File(context.noBackupFilesDir, "d2-pin.json")
        assertFalse("Clean emulator required", credential.exists())
        PinStore(context).create("246810".toCharArray())
        Prefs.setKiosk(context, false)
        Prefs.setNotificationPrivacy(context, 3)
        // Grant only to exercise the app's ordinary notification-access check.
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cmd notification allow_listener app.d2lock/app.d2lock.notifications.LockNotificationListener"
        )).use { it.readBytes() }
        try {
            ActivityScenario.launch<LockScreenActivity>(Intent(context, LockScreenActivity::class.java)).use { scenario ->
                scenario.onActivity {
                    NotificationStore.items.clear()
                    NotificationStore.items.add(LockNotification("private", "Messages", "Private title",
                        "Private text", 1L, Notification.VISIBILITY_PRIVATE))
                    NotificationStore.items.add(LockNotification("public", "Calendar", "Public title",
                        "Public text", 2L, Notification.VISIBILITY_PUBLIC))
                    NotificationStore.items.add(LockNotification("secret", "Secret app", "Secret title",
                        "Secret text", 3L, Notification.VISIBILITY_SECRET))
                    NotificationStore.onChanged?.invoke()
                }
                onView(withText("Public title")).check(matches(isDisplayed()))
                onView(withText("Private title")).check(doesNotExist())
                onView(withText("Secret app")).check(doesNotExist())
                Prefs.setNotificationPrivacy(context, 4)
                scenario.recreate()
                onView(withText("Private title")).check(matches(isDisplayed()))
                onView(withText("Secret title")).check(doesNotExist())
            }
        } finally {
            NotificationStore.items.clear()
            Prefs.setNotificationPrivacy(context, 2)
            credential.delete()
            File(credential.path + ".bak").delete()
        }
    }
}
