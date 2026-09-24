package app.d2lock.root

import android.app.Activity
import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference

/**
 * Independent watchdog for the D2 lock surface.
 *
 * Guardian does not authenticate or release kiosk policy. It only detects a
 * lost/invalid lock surface and asks RootKiosk to reassert the existing lease.
 */
object KioskGuardian {
    private val main = Handler(Looper.getMainLooper())
    private var owner = WeakReference<Activity>(null)
    private var enabled = false
    private var lastRepairAt = 0L
    private const val CHECK_MS = 750L
    private const val REPAIR_COOLDOWN_MS = 1500L

    fun start(activity: Activity) {
        owner = WeakReference(activity)
        if (enabled) return
        enabled = true
        main.post(check)
    }

    fun stop(activity: Activity) {
        if (owner.get() === activity) owner.clear()
        enabled = false
        main.removeCallbacks(check)
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
            if (RootKiosk.isEnforced() &&
                manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_LOCKED &&
                now - lastRepairAt >= REPAIR_COOLDOWN_MS) {
                lastRepairAt = now
                RootKiosk.reassert(activity)
            }
            main.postDelayed(this, CHECK_MS)
        }
    }
}
