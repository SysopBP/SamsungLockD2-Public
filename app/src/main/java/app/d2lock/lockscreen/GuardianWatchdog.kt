package app.d2lock.lockscreen

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.d2lock.Prefs
import app.d2lock.root.RootManager
import app.d2lock.security.PinStore

object GuardianWatchdog {
    enum class State { PROTECTED, TRUSTED_UI, TEMPORARILY_RELEASED, REASSERTING }

    private const val TAG = "SamsungLockD2"
    private const val REASSERT_COOLDOWN_MS = 2_500L

    @Volatile private var state = State.PROTECTED
    @Volatile private var lastReassertAt = 0L
    @Volatile private var lastReason = "startup"
    @Volatile private var trustedAuthentication = false
    @Volatile private var trustedAuthenticationStartedAt = 0L

    @Synchronized
    fun reassert(context: Context, reason: String, callActive: Boolean = false): Boolean {
        // Phone/InCallUI is a trusted temporary foreground surface. Never turn a
        // proximity wake, voicemail transition, or call UI focus change into a
        // fresh D2 lock while Android still reports an active call notification.
        if (callActive) {
            transition(State.TRUSTED_UI, "call_active")
            Log.i(TAG, "GUARDIAN_REASSERT_DEFERRED_CALL reason=$reason")
            return false
        }
        if (isTrustedAuthenticationActive()) {
            Log.i(TAG, "GUARDIAN_REASSERT_DEFERRED_AUTH reason=$reason")
            return false
        }
        if (!Prefs.enabled(context) || !PinStore(context).configured()) {
            transition(State.TEMPORARILY_RELEASED, "not_armed")
            Log.i(TAG, "GUARDIAN_RELOCATE_SKIPPED reason=not_armed source=$reason")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastReassertAt
        if (lastReassertAt != 0L && elapsed < REASSERT_COOLDOWN_MS) {
            Log.i(TAG, "GUARDIAN_RELOCATE_SKIPPED reason=debounce source=$reason elapsed=$elapsed")
            return false
        }
        lastReassertAt = now
        transition(State.REASSERTING, reason)
        KeyguardSignalReceiver.recordGuardianStage(context, "D2_LAUNCH_REQUEST", reason)
        Log.i(TAG, "GUARDIAN_RELOCATE_REQUESTED reason=$reason callActive=$callActive")

        // Android 17 blocks ordinary background Activity launches from D2's
        // foreground service (BAL_BLOCK). On rooted Guardian installations use
        // the privileged shell launch as the authoritative path and do not fall
        // through to a launch Android has already told us it will reject.
        if (Prefs.rootMode(context)) {
            val appContext = context.applicationContext
            Thread {
                if (!Prefs.enabled(appContext) || !Prefs.rootMode(appContext) || isTrustedAuthenticationActive() ||
                    app.d2lock.notifications.CallNotificationStore.isCallActive(appContext)) return@Thread
                val launched = RootManager.launchCompanion()
                KeyguardSignalReceiver.recordGuardianStage(appContext,
                    if (launched) "D2_LAUNCH_ACCEPTED" else "D2_LAUNCH_REJECTED", "root:$reason")
                if (!launched) transition(State.TEMPORARILY_RELEASED, "root_launch_failed")
            }.start()
            return true
        }

        // Non-root remains a best-effort compatibility path. A successful
        // startActivity() call is only a request; ActivityTaskManager may still
        // reject it under BAL policy, so visibility/first-draw telemetry is the
        // source of truth for health.
        return runCatching {
            context.startActivity(Intent(context, LockScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            KeyguardSignalReceiver.recordGuardianStage(context, "D2_LAUNCH_REQUESTED", "activity:$reason")
            Log.i(TAG, "GUARDIAN_REASSERT_REQUESTED path=activity reason=$reason callActive=$callActive")
            true
        }.getOrElse {
            KeyguardSignalReceiver.recordGuardianStage(context, "D2_LAUNCH_REJECTED", "activity_exception:$reason")
            Log.w(TAG, "GUARDIAN_REASSERT_FAILED reason=$reason callActive=$callActive", it)
            transition(State.TEMPORARILY_RELEASED, "reassert_failed")
            false
        }
    }

    @Synchronized fun beginTrustedAuthentication() {
        trustedAuthentication = true
        trustedAuthenticationStartedAt = SystemClock.elapsedRealtime()
        transition(State.TRUSTED_UI, "authentication")
        Log.i(TAG, "GUARDIAN_AUTH_BEGIN")
    }

    @Synchronized fun endTrustedAuthentication(reason: String) {
        trustedAuthentication = false
        trustedAuthenticationStartedAt = 0L
        transition(State.PROTECTED, reason)
        Log.i(TAG, "GUARDIAN_AUTH_END reason=$reason")
    }

    fun isTrustedAuthenticationActive(): Boolean = trustedAuthentication

    @Synchronized fun markProtected(reason: String) = transition(State.PROTECTED, reason)
    @Synchronized fun markTrustedUi(reason: String) = transition(State.TRUSTED_UI, reason)
    @Synchronized fun markReleased(reason: String) = transition(State.TEMPORARILY_RELEASED, reason)
    @Volatile private var monitorAt = 0L
    @Volatile private var monitorHealth = "Not started"
    @Volatile private var monitorRunning = false
    fun recordMonitor(health: String) {
        monitorRunning = true
        monitorAt = SystemClock.elapsedRealtime()
        if (monitorHealth != health) Log.i(TAG, "GUARDIAN_HEALTH_CHANGE $health")
        monitorHealth = health
    }
    fun monitorStopped() { monitorRunning = false; monitorHealth = "Service stopped" }
    fun snapshot(): String {
        val age = if (monitorAt == 0L) "never" else "${(SystemClock.elapsedRealtime() - monitorAt) / 1000}s ago"
        return "state=$state lastReason=$lastReason\nService monitor: ${if (monitorRunning) "Started" else "Stopped"}; last check $age\n$monitorHealth\n${app.d2lock.root.KioskD2Guardian.snapshot()}"
    }

    private fun transition(next: State, reason: String) {
        val previous = state
        state = next
        lastReason = reason
        Log.i(TAG, "GUARDIAN_STATE from=$previous to=$next reason=$reason")
    }
}
