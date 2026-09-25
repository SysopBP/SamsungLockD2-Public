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
    private const val REASSERT_COOLDOWN_MS = 750L

    @Volatile private var state = State.PROTECTED
    @Volatile private var lastReassertAt = 0L
    @Volatile private var lastReason = "startup"
    @Volatile private var trustedAuthentication = false

    @Synchronized
    fun reassert(context: Context, reason: String, callActive: Boolean = false): Boolean {
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
        Log.i(TAG, "GUARDIAN_RELOCATE_REQUESTED reason=$reason callActive=$callActive")
        if (Prefs.rootMode(context) && RootManager.launchCompanion()) {
            Log.i(TAG, "GUARDIAN_REASSERT_REQUESTED path=root reason=$reason callActive=$callActive")
            return true
        }
        return runCatching {
            context.startActivity(Intent(context, LockScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            Log.i(TAG, "GUARDIAN_REASSERT_REQUESTED path=activity reason=$reason callActive=$callActive")
            true
        }.getOrElse {
            Log.w(TAG, "GUARDIAN_REASSERT_FAILED reason=$reason callActive=$callActive", it)
            transition(State.TEMPORARILY_RELEASED, "reassert_failed")
            false
        }
    }

    @Synchronized fun beginTrustedAuthentication() {
        trustedAuthentication = true
        transition(State.TRUSTED_UI, "authentication")
        Log.i(TAG, "GUARDIAN_AUTH_BEGIN")
    }

    @Synchronized fun endTrustedAuthentication(reason: String) {
        trustedAuthentication = false
        transition(State.PROTECTED, reason)
        Log.i(TAG, "GUARDIAN_AUTH_END reason=$reason")
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
