package app.d2lock.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Receives keyguard state emitted from D2's LSPosed code running inside SystemUI.
 *
 * This receiver is Direct-Boot aware. SystemUI can reach it before user 0 has
 * unlocked, so all pre-unlock bridge state lives in device-protected storage.
 * Credential-protected Guardian services are only invoked after user unlock.
 */
class KeyguardSignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION && intent.action != FINGERPRINT_ACTION) return
        val senderUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromUid else -1
        Log.i(TAG, "GUARDIAN_XPOSED_SIGNAL_ACCEPTED uid=$senderUid permission=STATUS_BAR")

        val source = intent.getStringExtra("source") ?: "unknown"
        if (intent.action == FINGERPRINT_ACTION) {
            val event = intent.getStringExtra("fingerprint_event") ?: return
            val prefs = directBootPrefs(context)
            prefs.edit()
                .putLong(KEY_LAST_FP_EVENT_MS, System.currentTimeMillis())
                .putString(KEY_LAST_FP_EVENT, event)
                .putString(KEY_LAST_FP_SOURCE, source)
                .apply()
            Log.i(TAG, "GUARDIAN_XPOSED_FINGERPRINT_RECEIVED event=$event source=$source observational=true")
            return
        }
        val state = intent.getStringExtra("state") ?: return
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        Log.i(TAG, "GUARDIAN_XPOSED_KEYGUARD_RECEIVED state=$state source=$source unlocked=$unlocked")

        val prefs = directBootPrefs(context)
        runCatching {
            prefs.edit()
                .putLong(KEY_LAST_EVENT_MS, System.currentTimeMillis())
                .putString(KEY_LAST_STATE, state)
                .putString(KEY_LAST_SOURCE, source)
                .apply()
            if (state == "HEARTBEAT") {
                prefs.edit().putLong(KEY_LAST_HEARTBEAT_MS, System.currentTimeMillis()).apply()
            }
        }.onFailure {
            Log.w(TAG, "GUARDIAN_XPOSED_DIRECT_BOOT_WRITE_FAILED state=$state", it)
        }

        if (state == "HEARTBEAT") return

        if (!unlocked) {
            runCatching {
                prefs.edit()
                    .putBoolean(KEY_PENDING_HANDOFF, true)
                    .putString(KEY_PENDING_STATE, state)
                    .putString(KEY_PENDING_SOURCE, source)
                    .apply()
            }.onFailure {
                Log.w(TAG, "GUARDIAN_XPOSED_DIRECT_BOOT_DEFER_FAILED state=$state", it)
            }
            Log.i(TAG, "GUARDIAN_XPOSED_DIRECT_BOOT state=$state source=$source")
            Log.i(TAG, "GUARDIAN_XPOSED_HANDOFF_DEFERRED state=$state reason=credential_storage_locked")
            return
        }

        LockScreenService.keyguardSignal(context, state, source)
        Log.i(TAG, "GUARDIAN_XPOSED_HANDOFF_DISPATCHED state=$state source=$source")
    }

    companion object {
        private const val TAG = "SamsungLockD2"
        const val ACTION = "app.d2lock.action.XPOSED_KEYGUARD_STATE"
        const val FINGERPRINT_ACTION = "app.d2lock.action.XPOSED_FINGERPRINT_EVENT"

        private const val PREFS = "guardian_xposed_health"
        private const val KEY_LAST_EVENT_MS = "last_systemui_event_ms"
        private const val KEY_LAST_STATE = "last_systemui_state"
        private const val KEY_LAST_SOURCE = "last_systemui_source"
        private const val KEY_LAST_HEARTBEAT_MS = "last_systemui_heartbeat_ms"
        private const val KEY_PENDING_HANDOFF = "pending_handoff"
        private const val KEY_PENDING_STATE = "pending_state"
        private const val KEY_PENDING_SOURCE = "pending_source"
        private const val KEY_LAST_FP_EVENT_MS = "last_fingerprint_event_ms"
        private const val KEY_LAST_FP_EVENT = "last_fingerprint_event"
        private const val KEY_LAST_FP_SOURCE = "last_fingerprint_source"

        private fun directBootPrefs(context: Context) =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        data class BridgeHealth(val status: String, val ageMs: Long?, val source: String?)

        fun bridgeHealth(context: Context, staleAfterMs: Long = 90_000L): BridgeHealth {
            val prefs = directBootPrefs(context)
            val heartbeat = prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0L)
            val lastEvent = prefs.getLong(KEY_LAST_EVENT_MS, 0L)
            val timestamp = maxOf(heartbeat, lastEvent)
            val source = prefs.getString(KEY_LAST_SOURCE, null)
            if (timestamp <= 0L) return BridgeHealth("WAITING", null, source)
            val age = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
            return BridgeHealth(if (age <= staleAfterMs) "READY" else "STALE", age, source)
        }

        fun replayDeferred(context: Context): Boolean {
            val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
            if (!unlocked) {
                Log.i(TAG, "GUARDIAN_XPOSED_REPLAY_SKIPPED reason=user_locked")
                return false
            }

            return runCatching {
                val prefs = directBootPrefs(context)
                if (!prefs.getBoolean(KEY_PENDING_HANDOFF, false)) return@runCatching false

                val state = prefs.getString(KEY_PENDING_STATE, null)
                val source = prefs.getString(KEY_PENDING_SOURCE, "direct_boot_replay") ?: "direct_boot_replay"
                if (state.isNullOrBlank()) {
                    prefs.edit()
                        .remove(KEY_PENDING_HANDOFF)
                        .remove(KEY_PENDING_STATE)
                        .remove(KEY_PENDING_SOURCE)
                        .apply()
                    Log.w(TAG, "GUARDIAN_XPOSED_REPLAY_DROPPED reason=missing_state")
                    return@runCatching false
                }

                prefs.edit()
                    .remove(KEY_PENDING_HANDOFF)
                    .remove(KEY_PENDING_STATE)
                    .remove(KEY_PENDING_SOURCE)
                    .apply()

                Log.i(TAG, "GUARDIAN_XPOSED_USER_UNLOCKED pendingState=$state source=$source")
                LockScreenService.keyguardSignal(context, state, source)
                Log.i(TAG, "GUARDIAN_XPOSED_HANDOFF_RESUMED state=$state source=$source")
                true
            }.onFailure {
                Log.w(TAG, "GUARDIAN_XPOSED_REPLAY_FAILED", it)
            }.getOrDefault(false)
        }
    }
}
