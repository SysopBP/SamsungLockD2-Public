package app.d2lock.lockscreen

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.d2lock.Prefs
import app.d2lock.root.RootManager
import app.d2lock.security.PinStore

object GuardianWatchdog {
    enum class State { PROTECTED, TRUSTED_UI, CALL_ACTIVE, TEMPORARILY_RELEASED, REASSERTING }

    private const val TAG = "SamsungLockD2"
    private const val REASSERT_COOLDOWN_MS = 2_500L
    private const val TRUSTED_AUTH_TIMEOUT_MS = 15_000L

    @Volatile private var state = State.PROTECTED
    @Volatile private var lastReassertAt = 0L
    @Volatile private var lastReason = "startup"
    @Volatile private var trustedAuthentication = false
    @Volatile private var trustedAuthenticationStartedAt = 0L
    @Volatile private var callActive = false

    @Synchronized
    fun reassert(context: Context, reason: String, callActive: Boolean = false): Boolean {
        if (callActive || this.callActive) {
            beginCall("reassert:$reason")
            Log.i(TAG, "GUARDIAN_CALL_REASSERT_SUPPRESSED reason=$reason")
            return false
        }
        if (trustedAuthentication) {
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
            val launched = RootManager.launchCompanion()
            KeyguardSignalReceiver.recordGuardianStage(
                context,
                if (launched) "D2_LAUNCH_ACCEPTED" else "D2_LAUNCH_REJECTED",
                "root:$reason"
            )
            KeyguardSignalReceiver.recordDecision(
                context,
                if (launched) "LAUNCH" else "DEFERRED",
                "root_launch:$reason"
            )
            Log.i(TAG, "GUARDIAN_REASSERT_REQUESTED path=root accepted=$launched reason=$reason callActive=$callActive")
            if (!launched) transition(State.TEMPORARILY_RELEASED, "root_launch_failed")
            return launched
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

    @Synchronized fun beginCall(reason: String) {
        if (!callActive) Log.i(TAG, "GUARDIAN_CALL_ENTER reason=$reason")
        callActive = true
        transition(State.CALL_ACTIVE, reason)
    }

    @Synchronized fun endCall(reason: String) {
        if (callActive) Log.i(TAG, "GUARDIAN_CALL_EXIT reason=$reason")
        callActive = false
        transition(State.PROTECTED, reason)
    }

    fun isCallActive(): Boolean = callActive

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

    fun isTrustedAuthenticationActive(): Boolean {
        if (!trustedAuthentication) return false
        val elapsed = SystemClock.elapsedRealtime() - trustedAuthenticationStartedAt
        if (elapsed <= TRUSTED_AUTH_TIMEOUT_MS) return true
        synchronized(this) {
            if (trustedAuthentication && SystemClock.elapsedRealtime() - trustedAuthenticationStartedAt > TRUSTED_AUTH_TIMEOUT_MS) {
                trustedAuthentication = false
                trustedAuthenticationStartedAt = 0L
                transition(State.PROTECTED, "auth_timeout")
                Log.w(TAG, "GUARDIAN_AUTH_TIMEOUT")
            }
        }
        return trustedAuthentication
    }

    @Synchronized fun markProtected(reason: String) = transition(State.PROTECTED, reason)
    @Synchronized fun markTrustedUi(reason: String) = transition(State.TRUSTED_UI, reason)
    @Synchronized fun markReleased(reason: String) = transition(State.TEMPORARILY_RELEASED, reason)
    fun snapshot(): String = "state=$state lastReason=$lastReason lastReassertAt=$lastReassertAt"

    private fun transition(next: State, reason: String) {
        val previous = state
        state = next
        lastReason = reason
        Log.i(TAG, "GUARDIAN_STATE from=$previous to=$next reason=$reason")
    }
}
