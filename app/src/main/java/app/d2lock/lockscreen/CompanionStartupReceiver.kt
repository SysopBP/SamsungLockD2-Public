package app.d2lock.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import app.d2lock.Prefs
import app.d2lock.security.PinStore

/**
 * Restores the user-enabled lock-screen companion after reboot or an app update.
 *
 * LOCKED_BOOT_COMPLETED is deliberately treated as an early diagnostic/warm-up
 * signal. D2's PIN and normal preferences live in credential-protected storage,
 * so they must not be read until the user is unlocked. BOOT_COMPLETED then
 * performs the authoritative restore.
 */
class CompanionStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i(TAG, "GUARDIAN_LOCKED_BOOT_RECEIVED")
                val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
                if (!unlocked) {
                    Log.i(TAG, "GUARDIAN_WAITING_FOR_CREDENTIAL_STORAGE")
                    return
                }
                restore(context, "locked_boot")
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "GUARDIAN_BOOT_RECEIVED")
                restore(context, "boot")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "GUARDIAN_PACKAGE_REPLACED")
                restore(context, "package_replaced")
            }
        }
    }

    private fun restore(context: Context, source: String) {
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        if (!unlocked) {
            Log.i(TAG, "GUARDIAN_RESTORE_DEFERRED source=$source")
            return
        }
        val enabled = runCatching { Prefs.enabled(context) }.getOrDefault(false)
        val configured = runCatching { PinStore(context).configured() }.getOrDefault(false)
        Log.i(TAG, "GUARDIAN_RESTORE_CHECK source=$source enabled=$enabled configured=$configured")
        if (!enabled || !configured) return
        try {
            val postBoot = source == "boot"
            LockScreenService.start(context, postBoot = postBoot)
            Log.i(TAG, "GUARDIAN_SERVICE_REQUESTED source=$source postBoot=$postBoot")
        } catch (error: RuntimeException) {
            Log.w(TAG, "GUARDIAN_SERVICE_REQUEST_FAILED source=$source", error)
        }
    }

    companion object {
        private const val TAG = "SamsungLockD2"
    }
}
