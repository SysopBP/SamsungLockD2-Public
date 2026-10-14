package app.d2lock.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.os.SystemClock
import android.util.Log
import app.d2lock.root.RootManager

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
        val pending = goAsync()
        signalWorker.execute {
            try { receiveSignal(context.applicationContext, intent) }
            catch (error: Exception) { Log.w(TAG, "GUARDIAN_SIGNAL_FAILED", error) }
            finally {
                if (intent.action == SYSTEM_ACTION) runCatching { app.d2lock.widget.D2Widget.updateAll(context) }
                pending.finish()
            }
        }
    }

    private fun receiveSignal(context: Context, intent: Intent) {
        if (intent.action == SYSTEM_ACTION) {
            HookDiagnostics.record(context, intent)
            if (intent.getStringExtra("event") == "CREDENTIAL_DIAGNOSTIC") {
                return
            }
        }
        if (intent.action == SYSTEM_ACTION && intent.getStringExtra("event") == "SECURITY_SETUP_CHANGED") {
            recordDiagnosticObservation(context, "SECURITY_SETUP_CHANGED", intent.getStringExtra("source") ?: "unknown")
            app.d2lock.root.DmcRecovery.request(context, "security_setup_changed", force = true)
            recordGuardianStage(context, "D2_SRP_SECURITY_SETUP_CHANGED", "refresh_requested")
            return
        }
        if (intent.action == SYSTEM_ACTION && intent.getStringExtra("event") == "BOOT_READY") {
            // Separate worker: VaultKeeper readiness must never delay Guardian's boot token.
            app.d2lock.root.DmcRecovery.request(context, "BOOT_READY")
        }
        val unlockedForPolicy = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        if ((unlockedForPolicy && !app.d2lock.Prefs.xposedMaster(context)) ||
            (!unlockedForPolicy && !app.d2lock.Prefs.earlyBootArmed(context))) {
            Log.i(TAG, "GUARDIAN_SIGNAL_SKIPPED policy=disabled_or_unarmed")
            return
        }
        val senderUid = -1
        Log.i(TAG, "GUARDIAN_XPOSED_SIGNAL_ACCEPTED uid=$senderUid permission=STATUS_BAR")

        val source = intent.getStringExtra("source") ?: "unknown"
        if (intent.action == SYSTEM_ACTION) {
            val event = intent.getStringExtra("event") ?: return
            recordDiagnosticObservation(context, event, source)
            val method = intent.getStringExtra("method") ?: "unknown"
            val prefs = directBootPrefs(context)
            val now = System.currentTimeMillis()
            val counterKey = when (event) {
                "WAKE", "SLEEP" -> KEY_SYSTEM_WAKE_COUNT
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
            if (!unlocked && (event == "WAKE" || event == "BOOT_READY")) {
                // First pre-unlock signal owns this boot launch. WAKE is preferred;
                // BOOT_READY stays as the fallback if the early wake launch cannot run.
                val lastLaunch = prefs.getLong(KEY_EARLY_BOOT_LAUNCH_MS, 0L)
                val elapsed = SystemClock.elapsedRealtime() - prefs.getLong(KEY_EARLY_BOOT_LAUNCH_ELAPSED_MS, 0L)
                val launchedThisBoot = earlyBootLaunchClaimedThisBoot(context)
                if (!launchedThisBoot || elapsed > EARLY_BOOT_DEDUPE_MS) {
                    recordPipeline(context, "WAKE_DETECTED", "$event:$method")
                    Log.i(TAG, "GUARDIAN_WAKE_DETECTED event=$event method=$method source=$source")
                    prefs.edit().putLong(KEY_EARLY_BOOT_LAUNCH_MS, now)
                        .putLong(KEY_EARLY_BOOT_CONFIRMED_MS, 0L)
                        .putInt(KEY_EARLY_BOOT_COUNT, bootCount(context))
                        .putLong(KEY_EARLY_BOOT_LAUNCH_ELAPSED_MS, SystemClock.elapsedRealtime()).commit()
                    // Prefer the root launch during Direct Boot. Android 17 can accept a
                    // background startActivity() call without actually presenting it, while
                    // the root path can place the existing singleTask lock surface immediately.
                    val rootLaunch = runCatching {
                        recordPipeline(context, "LAUNCH_REQUEST", "root:$event")
                        Log.i(TAG, "GUARDIAN_LAUNCH_REQUEST event=$event path=root")
                        RootManager.launchCompanion()
                    }.onFailure {
                        Log.w(TAG, "GUARDIAN_EARLY_BOOT_ROOT_LAUNCH_FAILED event=$event", it)
                    }.getOrDefault(false)
                    val activityFallback = !rootLaunch && runCatching {
                        recordPipeline(context, "LAUNCH_REQUEST", "activity:$event")
                        Log.i(TAG, "GUARDIAN_LAUNCH_REQUEST event=$event path=activity_fallback")
                        context.startActivity(
                            Intent(context, LockScreenActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                .putExtra("guardian_boot_trigger", event)
                        )
                        true
                    }.onFailure {
                        Log.w(TAG, "GUARDIAN_EARLY_BOOT_DIRECT_LAUNCH_FAILED event=$event", it)
                    }.getOrDefault(false)
                    val launched = rootLaunch || activityFallback
                    if (launched) {
                        // Claim already recorded before launch; do not erase a visibility confirmation.
                        Log.i(TAG, "GUARDIAN_BOOT_TOKEN_PENDING event=$event")
                    }
                    if (!launched) prefs.edit().remove(KEY_EARLY_BOOT_LAUNCH_MS).remove(KEY_EARLY_BOOT_CONFIRMED_MS).commit()
                    val launchPath = when { rootLaunch -> "root"; activityFallback -> "activity"; else -> "none" }
                    recordPipeline(context, "EARLY_BOOT_LAUNCH", "$event:$launchPath:$launched")
                    Log.i(TAG, "GUARDIAN_EARLY_BOOT_LAUNCH event=$event accepted=$launched path=$launchPath source=$source")
                } else {
                    recordPipeline(context, "BOOT_FALLBACK_SKIPPED_ALREADY_ACTIVE", "$event:elapsed=$elapsed")
                    Log.i(TAG, "GUARDIAN_EARLY_BOOT_DEDUPED event=$event elapsed=$elapsed")
                }
            } else if (unlocked && (event == "BOOT_READY" || event == "WAKE" || event == "KEYGUARD" || event == "TASK_KEYGUARD")) {
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
        private val signalWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
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
        private const val KEY_EARLY_BOOT_LAUNCH_MS = "early_boot_launch_ms"
        private const val KEY_EARLY_BOOT_COUNT = "early_boot_count"
        private const val KEY_EARLY_BOOT_LAUNCH_ELAPSED_MS = "early_boot_launch_elapsed_ms"
        private fun bootCount(context: Context) = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        private const val KEY_EARLY_BOOT_CONFIRMED_MS = "early_boot_confirmed_ms"
        private const val EARLY_BOOT_DEDUPE_MS = 20_000L
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
        fun recordGuardianStage(context: Context, stage: String, detail: String) {
            recordPipeline(context, stage, detail)
            if (stage == "D2_VISIBLE") {
                val prefs = directBootPrefs(context)
                val launchWallMs = prefs.getLong(KEY_EARLY_BOOT_LAUNCH_MS, 0L)
                val now = System.currentTimeMillis()
                val bootWallMs = now - SystemClock.elapsedRealtime()
                if (earlyBootLaunchClaimedThisBoot(context) && prefs.getLong(KEY_EARLY_BOOT_CONFIRMED_MS, 0L) == 0L) {
                    prefs.edit().putLong(KEY_EARLY_BOOT_CONFIRMED_MS, now).apply()
                    Log.i(TAG, "GUARDIAN_BOOT_TOKEN_CONFIRMED stage=$stage")
                }
            }
        }

        fun earlyBootLaunchClaimedThisBoot(context: Context): Boolean {
            val prefs = directBootPrefs(context)
            val launchWallMs = prefs.getLong(KEY_EARLY_BOOT_LAUNCH_MS, 0L)
            if (launchWallMs <= 0L) return false
            val boot = bootCount(context)
            if (boot >= 0) return prefs.getInt(KEY_EARLY_BOOT_COUNT, -2) == boot
            val now = System.currentTimeMillis()
            val bootWallMs = now - SystemClock.elapsedRealtime()
            return launchWallMs >= (bootWallMs - 5_000L)
        }

        fun earlyBootLaunchConsumedThisBoot(context: Context): Boolean {
            val confirmedWallMs = directBootPrefs(context).getLong(KEY_EARLY_BOOT_CONFIRMED_MS, 0L)
            if (confirmedWallMs <= 0L) return false
            return earlyBootLaunchClaimedThisBoot(context)
        }
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
            val now = System.currentTimeMillis()
            val age = (now - timestamp).coerceAtLeast(0L)
            // A bridge is a capability/connection state, not a heartbeat timer. Once
            // observed during this boot it remains CONNECTED; age is diagnostic only.
            val bootWallMs = now - SystemClock.elapsedRealtime()
            val currentBoot = timestamp >= (bootWallMs - 5_000L)
            return BridgeHealth(if (currentBoot) "CONNECTED" else "WAITING", age, source)
        }

        fun systemHealth(context: Context, staleAfterMs: Long = 90_000L): SystemHealth {
            val prefs = directBootPrefs(context)
            val timestamp = prefs.getLong(KEY_LAST_SYSTEM_EVENT_MS, 0L)
            val event = prefs.getString(KEY_LAST_SYSTEM_EVENT, null)
            val method = prefs.getString(KEY_LAST_SYSTEM_METHOD, null)
            val source = prefs.getString(KEY_LAST_SYSTEM_SOURCE, null)
            if (timestamp <= 0L) return SystemHealth("WAITING", null, event, method, source)
            val now = System.currentTimeMillis()
            val age = (now - timestamp).coerceAtLeast(0L)
            // system_server does not emit periodic heartbeats. A valid event from the
            // current boot verifies the bridge for the remainder of that boot.
            val bootWallMs = now - SystemClock.elapsedRealtime()
            val currentBoot = timestamp >= (bootWallMs - 5_000L)
            return SystemHealth(if (currentBoot) "CONNECTED" else "WAITING", age, event, method, source)
        }

        /** Separate diagnostic observations; never used to authorize Guardian or credentials. */
        @Synchronized private fun recordDiagnosticObservation(context: Context, event: String, source: String) {
            val prefs=directBootPrefs(context)
            val boot=bootCount(context)
            val sameBoot=prefs.getInt("diagnostic_boot", -2)==boot
            val edit=prefs.edit()
            if(!sameBoot) for(key in prefs.all.keys.filter { it.startsWith("diagnostic_count_") || it.startsWith("diagnostic_source_") }) edit.remove(key)
            val count=if(sameBoot) prefs.getLong("diagnostic_count_$event",0L) else 0L
            edit.putInt("diagnostic_boot",boot).putLong("diagnostic_count_$event",count+1L)
                .putString("diagnostic_source_$event",source).apply()
        }

        fun diagnosticCount(context: Context, event: String): Long {
            val prefs=directBootPrefs(context)
            return if(prefs.getInt("diagnostic_boot",-2)==bootCount(context)) prefs.getLong("diagnostic_count_$event",0L) else 0L
        }

        fun diagnosticSource(context: Context, event: String): String? {
            val prefs=directBootPrefs(context)
            return if(prefs.getInt("diagnostic_boot",-2)==bootCount(context)) prefs.getString("diagnostic_source_$event",null) else null
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

        /** Diagnostics only: called after Android accepts D2's biometric prompt. */
        fun recordFingerprintSuccess(context: Context, source: String) {
            recordDiagnosticObservation(context, "D2_FINGERPRINT_SUCCESS", source)
            directBootPrefs(context).edit()
                .putLong(KEY_LAST_FP_EVENT_MS, System.currentTimeMillis())
                .putString(KEY_LAST_FP_EVENT, "VERIFIED · $source")
                .putString(KEY_LAST_FP_SOURCE, source)
                .apply()
            recordPipeline(context, "FINGERPRINT", "VERIFIED · $source")
        }

        fun fingerprintHealth(context: Context): FingerprintHealth {
            val prefs = directBootPrefs(context)
            val timestamp = prefs.getLong(KEY_LAST_FP_EVENT_MS, 0L)
            val bootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            if (timestamp <= 0L || timestamp < bootWallMs - 5_000L) {
                return FingerprintHealth(null, null, null)
            }
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
