package app.d2lock.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.util.Log
import app.d2lock.Prefs
import app.d2lock.security.PinStore

/**
 * Restores the user-enabled lock-screen companion after reboot or an app update.
 *
 * LOCKED_BOOT_COMPLETED runs before credential-protected D2 settings are
 * available. Record only non-secret pending state in device-protected storage,
 * then restore Guardian and replay any LSPosed keyguard handoff after unlock.
 */
class CompanionStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i(TAG, "GUARDIAN_LOCKED_BOOT_RECEIVED")
                val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
                if (!unlocked) {
                    deferUntilUnlocked(context)
                    Log.i(TAG, "GUARDIAN_WAITING_FOR_CREDENTIAL_STORAGE pendingRestore=true")
                    return
                }
                restore(context, "locked_boot")
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "GUARDIAN_BOOT_RECEIVED")
                val pending = pendingBootRestore(context)
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
            deferUntilUnlocked(context)
            Log.i(TAG, "GUARDIAN_RESTORE_DEFERRED source=$source")
            return
        }

        val enabled = runCatching { Prefs.enabled(context) }.getOrDefault(false)
        val configured = runCatching { PinStore(context).configured() }.getOrDefault(false)
        Log.i(TAG, "GUARDIAN_RESTORE_CHECK source=$source enabled=$enabled configured=$configured")
        if (!enabled || !configured) {
            finishPendingRestore(context)
            return
        }

        try {
            val postBoot = source != "package_replaced"
            LockScreenService.start(context, postBoot = postBoot)
            Log.i(TAG, "GUARDIAN_SERVICE_REQUESTED source=$source postBoot=$postBoot")
            finishPendingRestore(context)
            val replayed = KeyguardSignalReceiver.replayDeferred(context)
            Log.i(TAG, "GUARDIAN_XPOSED_REPLAY_CHECK source=$source replayed=$replayed")
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

    private fun pendingBootRestore(context: Context): Boolean {
        return runCatching {
            val prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(BOOT_STATE_PREFS, Context.MODE_PRIVATE)
            prefs.getBoolean(KEY_PENDING_RESTORE, false)
        }.onFailure {
            Log.w(TAG, "GUARDIAN_BOOT_STATE_READ_FAILED", it)
        }.getOrDefault(false)
    }

    private fun deferUntilUnlocked(context: Context) {
        markPendingBootRestore(context)
        val app = context.applicationContext
        // USER_UNLOCKED is registered-only. Use the application context so the
        // receiver survives this short-lived manifest receiver invocation.
        if (unlockReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                    Log.i(TAG, "GUARDIAN_USER_UNLOCKED_RECEIVED")
                    restore(context, "user_unlocked")
                }
            }
            try {
                // This filter contains only a protected system broadcast.
                app.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
                unlockReceiver = receiver
                Log.i(TAG, "GUARDIAN_UNLOCK_RECEIVER_REGISTERED")
            } catch (error: RuntimeException) {
                Log.w(TAG, "GUARDIAN_UNLOCK_RECEIVER_FAILED fallback=boot_completed", error)
            }
        }
        // Unlock can happen between the initial check and registration.
        if (app.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
            Log.i(TAG, "GUARDIAN_UNLOCK_RACE_RECOVERED")
            restore(app, "user_unlocked")
        }
    }

    private fun finishPendingRestore(context: Context) {
        runCatching {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(BOOT_STATE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_RESTORE).apply()
        }.onFailure { Log.w(TAG, "GUARDIAN_BOOT_STATE_CLEAR_FAILED", it) }
        unlockReceiver?.let { receiver ->
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "GUARDIAN_UNLOCK_RECEIVER_REMOVE_FAILED", it) }
        }
        unlockReceiver = null
    }

    companion object {
        private const val TAG = "SamsungLockD2"
        private const val BOOT_STATE_PREFS = "guardian_boot_state"
        private const val KEY_PENDING_RESTORE = "pending_restore"

        // All entry points run on the app main thread. Boot broadcasts remain
        // the fallback if the process dies before the runtime receiver fires.
        private var unlockReceiver: BroadcastReceiver? = null

        fun restoreFromFramework(context: Context, unlocked: Boolean) {
            if (unlocked) {
                CompanionStartupReceiver().restore(context, "framework_boot_ready")
            } else {
                Log.i(TAG, "GUARDIAN_EARLY_RESTORE_DEFERRED reason=user_locked")
                CompanionStartupReceiver().deferUntilUnlocked(context)
            }
        }
    }
}
