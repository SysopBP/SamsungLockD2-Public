package app.d2lock.root

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.content.pm.PackageManager
import android.util.Log
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
    private var lastRepairAt = 0L
    private var lostFocusAt = 0L
    private const val CHECK_MS = 750L
    private const val REPAIR_COOLDOWN_MS = 1500L
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
        if (owner.get() === activity) owner.clear()
        enabled = false
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
                return
            }
            val manager = activity.getSystemService(ActivityManager::class.java)
            val now = SystemClock.elapsedRealtime()
            if (RootKiosk.isEnforced()) {
                val telecom = activity.getSystemService(TelecomManager::class.java)
                val callActive = activity.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                    runCatching { telecom?.isInCall == true }.getOrDefault(false)
                val modeLost = manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_LOCKED
                val focusLost = !activity.hasWindowFocus()
                if (focusLost) {
                    if (lostFocusAt == 0L) {
                        lostFocusAt = now
                        Log.i(TAG, "GUARDIAN_FOCUS_LOST")
                    }
                } else if (lostFocusAt != 0L) {
                    Log.i(TAG, "GUARDIAN_FOCUS_RESTORED elapsed=${now - lostFocusAt}")
                    lostFocusAt = 0L
                }
                val staleFocus = lostFocusAt != 0L && now - lostFocusAt >= FOCUS_GRACE_MS
                if (callActive && staleFocus && !modeLost) {
                    Log.i(TAG, "GUARDIAN_FOCUS_DEFERRED reason=active_call")
                }
                if ((modeLost || (staleFocus && !callActive)) && now - lastRepairAt >= REPAIR_COOLDOWN_MS) {
                    lastRepairAt = now
                    Log.w(TAG, "GUARDIAN_REASSERT reason=${if (modeLost) "lock_task_lost" else "focus_lost"} mode=${manager.lockTaskModeState}")
                    RootKiosk.reassert(activity, bringToFront = staleFocus)
                }
            } else {
                lostFocusAt = 0L
            }
            main.postDelayed(this, CHECK_MS)
        }
    }
}
