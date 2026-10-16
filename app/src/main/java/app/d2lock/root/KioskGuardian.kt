package app.d2lock.root

import android.app.Activity
import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.d2lock.lockscreen.GuardianWatchdog
import java.lang.ref.WeakReference

/**
 * Independent watchdog for the D2 lock surface.
 *
 * Guardian never authenticates or releases kiosk policy. It only detects a
 * lost lock-task surface and asks RootKiosk to reassert the existing lease.
 */
object KioskD2Guardian {
    private val main = Handler(Looper.getMainLooper())
    private var owner = WeakReference<Activity>(null)
    private var enabled = false
    private val repairBudget = app.d2lock.security.WatchdogRecoveryBudget()
    @Volatile private var lastCheckAt = 0L
    @Volatile private var lastOutcome = "Not started"
    private var lastDeferredReason = ""
    fun snapshot(): String {
        val age = if (lastCheckAt == 0L) "not checked" else "${(SystemClock.elapsedRealtime() - lastCheckAt) / 1000}s ago"
        return "Surface watchdog: ${if (enabled) "Running" else "Stopped"}; checked $age\n$lastOutcome; requests in last retry window=${repairBudget.attempts()}"
    }
    private fun deferred(reason: String) {
        lastOutcome = reason
        if (lastDeferredReason != reason) Log.i(TAG, "GUARDIAN_WATCHDOG_DEFERRED reason=$reason")
        lastDeferredReason = reason
    }
    private var lostFocusAt = 0L
    private const val CHECK_MS = 750L
    private const val FOCUS_GRACE_MS = 1200L
    private const val TAG = "SamsungLockD2"

    fun start(activity: Activity) {
        owner = WeakReference(activity)
        if (enabled) return
        enabled = true
        lostFocusAt = 0L
        Log.i(TAG, "GUARDIAN_WATCHDOG_STARTED")
        main.post(check)
    }

    fun stop(activity: Activity) {
        if (owner.get() !== activity) return
        owner.clear()
        enabled = false
        lastOutcome = "Stopped"
        lostFocusAt = 0L
        main.removeCallbacks(check)
        Log.i(TAG, "GUARDIAN_WATCHDOG_STOPPED")
    }

    private val check = object : Runnable {
        override fun run() {
            if (!enabled) return
            val activity = owner.get()
            if (activity == null || activity.isDestroyed || activity.isFinishing) {
                enabled = false
                lastOutcome = "Owner not active; waiting for lock-surface recovery"
                return
            }
            var nextCheck = CHECK_MS
            try {
                lastCheckAt = SystemClock.elapsedRealtime()
                val awake = activity.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
                val reason = when {
                    !awake -> "screen_asleep"
                    !RootKiosk.isEnforced() -> "kiosk_not_enforced"
                    app.d2lock.notifications.CallNotificationStore.isCallActive(activity) -> "call_active"
                    GuardianWatchdog.isTrustedAuthenticationActive() -> "trusted_authentication"
                    app.d2lock.lockscreen.LockScreenService.isKeyguardHandoffActive() -> "samsung_keyguard_handoff"
                    else -> null
                }
                if (reason != null) {
                    lostFocusAt = 0L
                    deferred(reason)
                    if (!awake) nextCheck = 5000L
                } else {
                    lastDeferredReason = ""
                    val manager = activity.getSystemService(ActivityManager::class.java)
                    val now = SystemClock.elapsedRealtime()
                    val modeLost = manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_LOCKED
                    val focusLost = !activity.hasWindowFocus()
                    if (focusLost && lostFocusAt == 0L) lostFocusAt = now
                    if (!focusLost) lostFocusAt = 0L
                    val staleFocus = lostFocusAt != 0L && now - lostFocusAt >= FOCUS_GRACE_MS
                    if (modeLost || staleFocus) {
                        if (repairBudget.allow(now)) {
                            lastOutcome = "Repair requested: " + if (modeLost) "lock_task_lost" else "focus_lost"
                            Log.w(TAG, "GUARDIAN_REASSERT reason=$lastOutcome attempt=${repairBudget.attempts()}")
                            RootKiosk.reassert(activity, bringToFront = staleFocus)
                        } else lastOutcome = "Repair cooling down / limited to 3 per minute"
                    } else lastOutcome = "Protected surface healthy"
                }
            } catch (error: Exception) {
                lastOutcome = "Check failed: ${error.javaClass.simpleName}; retry scheduled"
                Log.w(TAG, "GUARDIAN_WATCHDOG_CHECK_FAILED", error)
                nextCheck = 5000L
            } finally {
                if (enabled) main.postDelayed(this, nextCheck)
            }
        }
    }
}
