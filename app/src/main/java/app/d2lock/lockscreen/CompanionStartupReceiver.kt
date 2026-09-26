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
 * LOCKED_BOOT_COMPLETED runs before credential-protected D2 settings are
 * available. Record only a non-secret pending-restore marker in device-protected
 * storage, leave the platform keyguard authoritative, and perform the real
 * restore after Android reports the user unlocked.
 */
class CompanionStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i(TAG, "GUARDIAN_LOCKED_BOOT_RECEIVED")
                val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
                if (!unlocked) {
                    markPendingBootRestore(context)
                    Log.i(TAG, "GUARDIAN_WAITING_FOR_CREDENTIAL_STORAGE pendingRestore=true")
                    return
                }
                restore(context, "locked_boot")
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "GUARDIAN_BOOT_RECEIVED")
                val pending = consumePendingBootRestore(context)
                Log.i(TAG, "GUARDIAN_BOOT_PENDING_RESTORE pending=$pending")
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
            if (source == "locked_boot") markPendingBootRestore(context)
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

    private fun markPendingBootRestore(context: Context) {
        runCatching {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(BOOT_STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING_RESTORE, true)
                .apply()
        }.onFailure {
            Log.w(TAG, "GUARDIAN_BOOT_STATE_WRITE_FAILED", it)
        }
    }

    private fun consumePendingBootRestore(context: Context): Boolean {
        return runCatching {
            val prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(BOOT_STATE_PREFS, Context.MODE_PRIVATE)
            val pending = prefs.getBoolean(KEY_PENDING_RESTORE, false)
            if (pending) prefs.edit().remove(KEY_PENDING_RESTORE).apply()
            pending
        }.onFailure {
            Log.w(TAG, "GUARDIAN_BOOT_STATE_READ_FAILED", it)
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "SamsungLockD2"
        private const val BOOT_STATE_PREFS = "guardian_boot_state"
        private const val KEY_PENDING_RESTORE = "pending_restore"
    }
}
