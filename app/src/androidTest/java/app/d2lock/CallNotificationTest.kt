package app.d2lock

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.d2lock.notifications.CallNotificationStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallNotificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pending(id: Int) = PendingIntent.getBroadcast(context, id,
        Intent("app.d2lock.TEST_CALL_$id").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
    private fun incoming() = Notification.Builder(context, "test-calls")
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setCategory(Notification.CATEGORY_CALL)
        .setOngoing(true)
        .setStyle(Notification.CallStyle.forIncomingCall(
            Person.Builder().setName("Private caller").build(), pending(1), pending(2)))
        .setContentIntent(pending(3)).build()
    private fun wrap(notification: Notification, pkg: String = context.packageName) =
        StatusBarNotification(pkg, pkg, 91, null, Process.myUid(), Process.myPid(), 0,
            notification, Process.myUserHandle(), System.currentTimeMillis())

    @After fun clear() { CallNotificationStore.items.clear() }

    @Test fun ongoingIncomingCallPreservesAnswerDeclineAndOpen() {
        val sbn = wrap(incoming())
        assertFalse(sbn.isClearable)
        CallNotificationStore.update(sbn, setOf(context.packageName))
        val call = CallNotificationStore.items.single()
        assertEquals(setOf(pending(1), pending(2)), call.controls.map { it.intent }.toSet())
        assertEquals(pending(3), call.open)
        assertFalse(call.toString().contains("Private caller"))
    }

    @Test fun unrelatedAppCannotSpoofCallControls() {
        CallNotificationStore.update(wrap(incoming()), setOf("com.android.dialer"))
        assertTrue(CallNotificationStore.items.isEmpty())
        // Even an allowed notification cannot forward a token created by an unrelated app.
        CallNotificationStore.update(wrap(incoming(), "com.android.dialer"), setOf("com.android.dialer"))
        assertTrue(CallNotificationStore.items.isEmpty())
    }

    @Test fun replacementWithMissedCallRemovesLiveControls() {
        CallNotificationStore.update(wrap(incoming()), setOf(context.packageName))
        val missed = Notification.Builder(context, "test-calls")
            .setSmallIcon(android.R.drawable.sym_call_missed).setCategory(Notification.CATEGORY_MISSED_CALL).build()
        CallNotificationStore.update(wrap(missed), setOf(context.packageName))
        assertTrue(CallNotificationStore.items.isEmpty())
    }

    @Test fun authenticationRequiredActionIsNotExposed() {
        val locked = Notification.Builder(context, "test-calls")
            .setSmallIcon(android.R.drawable.sym_call_incoming).setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(null, "Private action", pending(4))
                .setAuthenticationRequired(true).build()).build()
        CallNotificationStore.update(wrap(locked), setOf(context.packageName))
        assertTrue(CallNotificationStore.items.isEmpty())
    }
}
