package app.d2lock.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.d2lock.Prefs
import app.d2lock.security.PinStore\nimport app.d2lock.root.AdbManager

/** Restores the user-enabled wake listener after reboot or an app update. */
class CompanionStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.rootMode(context) && Prefs.adbOnBoot(context)) {\n            runCatching { AdbManager.setEnabled(true) }\n        }\n        if (!Prefs.enabled(context) || !PinStore(context).configured()) return
        try {
            LockScreenService.start(context)
        } catch (error: RuntimeException) {
            // Foreground starts can be restricted by the OS; settings can retry later.
            Log.w("SamsungLockD2", "Unable to restore wake listener", error)
        }
    }
}
