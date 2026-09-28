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
        if (intent.action != ACTION && intent.action != FINGERPRINT_ACTION && intent.action != SYSTEM_ACTION) return
        val senderUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromUid else -1
        Log.i(TAG, "GUARDIAN_XPOSED_SIGNAL_ACCEPTED uid=$senderUid permission=STATUS_BAR")

        val source = intent.getStringExtra("source") ?: "unknown"
        if (intent.action == SYSTEM_ACTION) {
            val event = intent.getStringExtra("event") ?: return
            val method = intent.getStringExtra("method") ?: "unknown"
            val prefs = directBootPrefs(context)
            val now = System.currentTimeMillis()
            val counterKey = when (event) {
                "WAKE", "SLEEP", "WAKE_OBSERVED" -> KEY_SYSTEM_WAKE_COUNT
                "KEYGUARD", "TASK_KEYGUARD" -> KEY_SYSTEM_KEYGUARD_COUNT
                "BIOMETRIC" -> KEY_SYSTEM_BIOMETRIC_COUNT
                "LOCK_SETTINGS" -> KEY_SYSTEM_LOCKSETTINGS_COUNT
                else -> null
            }
            val timeline = prefs.getString(KEY_SYSTEM_TIMELINE, "").orEmpty()
                .lineSequence().filter { it.isNotBlank() }.take(11).toList()
            val line = "$now|$event|$method"
            val edit = prefs.edit()
                .putLong(KEY_LAST_SYSTEM_EVENT_MS, now)
                .putString(KEY_LAST_SYSTEM_EVENT, event)
                .putString(KEY_LAST_SYSTEM_METHOD, method)
                .putString(KEY_LAST_SYSTEM_SOURCE, source)
                .putLong(KEY_SYSTEM_EVENT_COUNT, prefs.getLong(KEY_SYSTEM_EVENT_COUNT, 0L) + 1L)
                .putString(KEY_SYSTEM_TIMELINE, (listOf(line) + timeline).joinToString("\n"))
            if (counterKey != null) edit.putLong(counterKey, prefs.getLong(counterKey, 0L) + 1L)
            edit.apply()
            recordPipeline(context, event, method)
            Log.i(TAG, "GUARDIAN_SYS_EVENT_RECEIVED event=$event method=$method source=$source")
            val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
            if (unlocked && (event == "BOOT_READY" || event == "WAKE" || event == "KEYGUARD" || event == "TASK_KEYGUARD")) {
                LockScreenService.frameworkSignal(context, event, source)
            }
            return
        }
        if (intent.action == FINGERPRINT_ACTION) {
            val event = intent.getStringExtra("fingerprint_event") ?: return
            val prefs = directBootPrefs(context)
            prefs.edit()
                .putLong(KEY_LAST_FP_EVENT_MS, System.currentTimeMillis())
                .putString(KEY_LAST_FP_EVENT, event)
                .putString(KEY_LAST_FP_SOURCE, source)
                .apply()
            recordPipeline(context, "FINGERPRINT", event)
            Log.i(TAG, "GUARDIAN_XPOSED_FINGERPRINT_RECEIVED event=$event source=$source observational=true")
            return
        }
        val state = intent.getStringExtra("state") ?: return
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        recordPipeline(context, "KEYGUARD_UI", state)
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
        const val SYSTEM_ACTION = "app.d2lock.action.XPOSED_SYSTEM_EVENT"

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
        private const val KEY_LAST_SYSTEM_EVENT_MS = "last_system_event_ms"
        private const val KEY_LAST_SYSTEM_EVENT = "last_system_event"
        private const val KEY_LAST_SYSTEM_METHOD = "last_system_method"
        private const val KEY_LAST_SYSTEM_SOURCE = "last_system_source"
        private const val KEY_SYSTEM_EVENT_COUNT = "system_event_count"
        private const val KEY_SYSTEM_WAKE_COUNT = "system_wake_count"
        private const val KEY_SYSTEM_KEYGUARD_COUNT = "system_keyguard_count"
        private const val KEY_SYSTEM_BIOMETRIC_COUNT = "system_biometric_count"
        private const val KEY_SYSTEM_LOCKSETTINGS_COUNT = "system_locksettings_count"
        private const val KEY_SYSTEM_TIMELINE = "system_timeline"
        private const val KEY_PIPELINE_TIMELINE = "pipeline_timeline"
        private const val KEY_LAST_BIOMETRIC_MS = "pipeline_last_biometric_ms"
        private const val KEY_GUARDIAN_LOG = "guardian_log_center"
        private const val KEY_DECISION_TIMELINE = "guardian_decision_timeline"
        private const val KEY_SUPPRESSED_COUNT = "guardian_suppressed_count"

        private fun directBootPrefs(context: Context) =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        data class BridgeHealth(val status: String, val ageMs: Long?, val source: String?)
        data class SystemHealth(val status: String, val ageMs: Long?, val event: String?, val method: String?, val source: String?)
        data class FingerprintHealth(val ageMs: Long?, val event: String?, val source: String?)
        data class SystemMetrics(
            val total: Long, val wakeSleep: Long, val keyguard: Long, val biometric: Long,
            val lockSettings: Long, val timeline: List<String>
        )

        data class PipelineMetrics(val timeline: List<String>, val lastBiometricMs: Long?)
        data class DecisionMetrics(val suppressed: Long, val timeline: List<String>)
        private fun recordLog(context: Context, category: String, message: String, level: String = "I") {
            val prefs = directBootPrefs(context)
            val now = System.currentTimeMillis()
            val old = prefs.getString(KEY_GUARDIAN_LOG, "").orEmpty()
                .lineSequence().filter { it.isNotBlank() }.take(199).toList()
            prefs.edit().putString(KEY_GUARDIAN_LOG, (listOf("$now|$level|$category|$message") + old).joinToString("\n")).apply()
        }
        fun recordDecision(context: Context, decision: String, reason: String) {
            val prefs = directBootPrefs(context); val now = System.currentTimeMillis()
            val old = prefs.getString(KEY_DECISION_TIMELINE, "").orEmpty().lineSequence().filter { it.isNotBlank() }.take(29).toList()
            val edit = prefs.edit().putString(KEY_DECISION_TIMELINE, (listOf("$now|$decision|$reason") + old).joinToString("\n"))
            if (decision.contains("SUPPRESS") || decision.contains("SKIP") || decision.contains("DEFER"))
                edit.putLong(KEY_SUPPRESSED_COUNT, prefs.getLong(KEY_SUPPRESSED_COUNT, 0L) + 1L)
            edit.apply(); recordLog(context, "DECISION", "$decision • $reason")
        }
        fun decisionMetrics(context: Context): DecisionMetrics {
            val p=directBootPrefs(context)
            return DecisionMetrics(p.getLong(KEY_SUPPRESSED_COUNT,0L),
                p.getString(KEY_DECISION_TIMELINE,"").orEmpty().lineSequence().filter { it.isNotBlank() }.take(30).toList())
        }
        fun guardianLog(context: Context): List<String> =
            directBootPrefs(context).getString(KEY_GUARDIAN_LOG, "").orEmpty()
                .lineSequence().filter { it.isNotBlank() }.take(200).toList()
        fun clearGuardianLog(context: Context) =
            directBootPrefs(context).edit().remove(KEY_GUARDIAN_LOG).apply()

        private fun recordPipeline(context: Context, stage: String, detail: String) {
            recordLog(context, when {
                stage.contains("BIOMETRIC") || stage.contains("FINGERPRINT") -> "BIOMETRIC"
                stage.contains("KEYGUARD") -> "KEYGUARD"
                stage.contains("BOOT") -> "BOOT"
                stage.startsWith("D2_") -> "GUARDIAN"
                else -> "SYSTEM_SERVER"
            }, "$stage • $detail")
            val prefs = directBootPrefs(context)
            val now = System.currentTimeMillis()
            val old = prefs.getString(KEY_PIPELINE_TIMELINE, "").orEmpty()
                .lineSequence().filter { it.isNotBlank() }.take(19).toList()
            val edit = prefs.edit().putString(KEY_PIPELINE_TIMELINE, (listOf("$now|$stage|$detail") + old).joinToString("\n"))
            if (stage == "BIOMETRIC" || stage == "FINGERPRINT") edit.putLong(KEY_LAST_BIOMETRIC_MS, now)
            edit.apply()
        }
        fun recordGuardianStage(context: Context, stage: String, detail: String) = recordPipeline(context, stage, detail)
        fun pipelineMetrics(context: Context): PipelineMetrics {
            val prefs = directBootPrefs(context)
            val bio = prefs.getLong(KEY_LAST_BIOMETRIC_MS, 0L)
            return PipelineMetrics(
                prefs.getString(KEY_PIPELINE_TIMELINE, "").orEmpty().lineSequence().filter { it.isNotBlank() }.take(20).toList(),
                bio.takeIf { it > 0L }
            )
        }

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

        fun systemHealth(context: Context, staleAfterMs: Long = 90_000L): SystemHealth {
            val prefs = directBootPrefs(context)
            val timestamp = prefs.getLong(KEY_LAST_SYSTEM_EVENT_MS, 0L)
            val event = prefs.getString(KEY_LAST_SYSTEM_EVENT, null)
            val method = prefs.getString(KEY_LAST_SYSTEM_METHOD, null)
            val source = prefs.getString(KEY_LAST_SYSTEM_SOURCE, null)
            if (timestamp <= 0L) return SystemHealth("WAITING", null, event, method, source)
            val age = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
            return SystemHealth(if (age <= staleAfterMs) "READY" else "STALE", age, event, method, source)
        }

        fun systemMetrics(context: Context): SystemMetrics {
            val prefs = directBootPrefs(context)
            return SystemMetrics(
                prefs.getLong(KEY_SYSTEM_EVENT_COUNT, 0L),
                prefs.getLong(KEY_SYSTEM_WAKE_COUNT, 0L),
                prefs.getLong(KEY_SYSTEM_KEYGUARD_COUNT, 0L),
                prefs.getLong(KEY_SYSTEM_BIOMETRIC_COUNT, 0L),
                prefs.getLong(KEY_SYSTEM_LOCKSETTINGS_COUNT, 0L),
                prefs.getString(KEY_SYSTEM_TIMELINE, "").orEmpty().lineSequence().filter { it.isNotBlank() }.take(12).toList()
            )
        }

        fun fingerprintHealth(context: Context): FingerprintHealth {
            val prefs = directBootPrefs(context)
            val timestamp = prefs.getLong(KEY_LAST_FP_EVENT_MS, 0L)
            return FingerprintHealth(
                if (timestamp > 0L) (System.currentTimeMillis() - timestamp).coerceAtLeast(0L) else null,
                prefs.getString(KEY_LAST_FP_EVENT, null),
                prefs.getString(KEY_LAST_FP_SOURCE, null)
            )
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
