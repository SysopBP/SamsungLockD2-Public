package app.d2lock.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.d2lock.Prefs
import app.d2lock.security.PinStore

/** Restores the user-enabled wake listener after reboot or an app update. */
class CompanionStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.enabled(context) || !PinStore(context).configured()) return
        Log.i("SamsungLockD2", "GUARDIAN_BOOT_RECEIVED action=${intent.action}")
        try {
            // Start the persistent listener first, then immediately cover the launcher after boot.
            LockScreenService.start(context)
            if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
                app.d2lock.notifications.CallNotificationStore.items.isEmpty()) {
                runCatching {
                    Log.i("SamsungLockD2", "GUARDIAN_LOCKSCREEN_REQUESTED")
                    context.startActivity(Intent(context, LockScreenActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }.onFailure { error ->
                    Log.w("SamsungLockD2", "Unable to raise Guardian boot lock immediately", error)
                }
            }
        } catch (error: RuntimeException) {
            // Foreground starts can be restricted by the OS; settings can retry later.
            Log.w("SamsungLockD2", "Unable to restore wake listener", error)
        }
    }
}
