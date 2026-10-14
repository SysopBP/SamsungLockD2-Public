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
// CI: incoming-call lock-screen controls
object CallNotificationStore {
    val items = CopyOnWriteArrayList<CallNotification>()
    private val activeKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var onChanged: (() -> Unit)? = null

    /** Telecom is authoritative when the user grants phone-state access. */
    fun isCallActive(context: android.content.Context): Boolean {
        val telecomActive = if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { context.getSystemService(android.telecom.TelecomManager::class.java)?.isInCall == true }
                .getOrDefault(false)
        } else false
        // A false Telecom snapshot can precede a trusted ringing notification.
        return CallStatePolicy.active(telecomActive, activeKeys.isNotEmpty())
    }

    fun update(sbn: StatusBarNotification, allowedPackages: Set<String>) {
        items.removeAll { it.key == sbn.key }
        val notification = sbn.notification
        val trustedFullScreen = notification.fullScreenIntent?.creatorPackage in allowedPackages
        val callStyle = notification.extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle"
        if (!CallStatePolicy.notificationActive(sbn.packageName in allowedPackages,
                notification.category == Notification.CATEGORY_CALL, sbn.isOngoing, trustedFullScreen, callStyle)) {
            activeKeys.remove(sbn.key)
            onChanged?.invoke()
            return
        }
        activeKeys.add(sbn.key) // Call state does not depend on usable PendingIntent controls.
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

    fun remove(key: String) {
        activeKeys.remove(key)
        items.removeAll { it.key == key }
        onChanged?.invoke()
    }

    fun clear() {
        activeKeys.clear()
        items.clear()
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
