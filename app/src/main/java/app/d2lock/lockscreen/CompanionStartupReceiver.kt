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
        try {
            // Start the persistent listener first, then immediately cover the launcher after boot.\n            LockScreenService.start(context)\n            if (intent.action == Intent.ACTION_BOOT_COMPLETED &&\n                app.d2lock.notifications.CallNotificationStore.items.isEmpty()) {\n                runCatching {\n                    context.startActivity(Intent(context, LockScreenActivity::class.java)\n                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or\n                            Intent.FLAG_ACTIVITY_SINGLE_TOP or\n                            Intent.FLAG_ACTIVITY_CLEAR_TOP))\n                }.onFailure { error ->\n                    Log.w("SamsungLockD2", "Unable to raise Guardian boot lock immediately", error)\n                }\n            }
        } catch (error: RuntimeException) {
            // Foreground starts can be restricted by the OS; settings can retry later.
            Log.w("SamsungLockD2", "Unable to restore wake listener", error)
        }
    }
}
