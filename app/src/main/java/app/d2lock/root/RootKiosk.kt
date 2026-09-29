package app.d2lock.root

import android.app.Activity
import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A process-wide lease survives Activity recreation, but not app death or a frozen UI. */
object RootKiosk {
    class Channel(val reader: BufferedReader, val writer: BufferedWriter, val close: () -> Unit)
    internal var testConnect: ((String) -> Channel)? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var owner = WeakReference<Activity>(null)
    private var observer: ((String) -> Unit)? = null
    private var channel: Channel? = null
    private var events: LinkedBlockingQueue<String>? = null
    private var generation = 0
    private var starting = false
    private var active = false
    private var releasing = false
    private var message = "Kiosk inactive"
    internal val diagnostic: String get() = message
    internal fun isEnforced() = active && !releasing
    internal fun reassert(activity: Activity, bringToFront: Boolean = false) {
        if (!active || activity.isDestroyed || activity.isFinishing) return
        if (bringToFront && !activity.hasWindowFocus()) {
            runCatching {
                activity.startActivity(activity.intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                ))
            }.onSuccess { report("Kiosk Guardian • restoring D2 focus") }
             .onFailure { report("Kiosk Guardian could not restore focus: ${it.message}") }
        }
        if (mode(activity) == ActivityManager.LOCK_TASK_MODE_LOCKED) return
        runCatching { activity.startLockTask() }
            .onSuccess { report("Kiosk Guardian • restoring secure lock") }
            .onFailure { report("Kiosk Guardian could not restore lock: ${it.message}") }
    }
    private var lastPulse = 0L
    private var detachedAt = 0L
    private var lockLostAt = 0L
    private var lockRepairAttempts = 0
    private const val LOCK_REPAIR_WINDOW_MS = 4500L
    private const val LOCK_REPAIR_MAX_ATTEMPTS = 3
    private fun mode(activity: Activity) = activity.getSystemService(ActivityManager::class.java).lockTaskModeState
    private fun credentialName(activity: Activity) = if (app.d2lock.Prefs.unlockMethod(activity) == "pattern") "Pattern" else "PIN"
    private fun report(value: String) {
        message = value
        Log.i("SamsungLockD2", "GUARDIAN_KIOSK $value")
        observer?.invoke(value)
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    internal fun command(activity: Activity): String = "CLASSPATH=" + quote(activity.applicationInfo.sourceDir) +
        " /system/bin/app_process /system/bin app.d2lock.root.KioskBridge " + (Process.myUid() / 100000) +
        KioskCallApps.resolve(activity).joinToString("") { " " + quote(it) }

    fun attach(activity: Activity, status: (String) -> Unit) {
        owner = WeakReference(activity); observer = status; status(message)
        detachedAt = 0L
        if (active || starting) return
        if (mode(activity) != ActivityManager.LOCK_TASK_MODE_NONE) {
            report("Kiosk unavailable: another task is locked"); return
        }
        starting = true
        val ticket = ++generation
        val command = command(activity)
        report("Starting kiosk… ${credentialName(activity)} protection is not active yet")
        worker.execute {
            var opened: Channel? = null
            try {
                opened = testConnect?.invoke(command) ?: run {
                    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                    Channel(process.inputStream.bufferedReader(), process.outputStream.bufferedWriter()) {
                        // Closing stdin tells the independent root helper to restore policy.
                        runCatching { process.outputStream.close() }
                    }
                }
                val lease = checkNotNull(opened)
                val queue = LinkedBlockingQueue<String>()
                Thread {
                    try { lease.reader.forEachLine { if (it.startsWith("D2_")) queue.offer(it) } }
                    catch (_: Exception) { }
                    finally { queue.offer("D2_ERROR root helper stopped") }
                }.apply { isDaemon = true; start() }
                val reply = queue.poll(12, TimeUnit.SECONDS)
                check(reply == "D2_READY") { reply?.removePrefix("D2_ERROR ") ?: "Root permission timed out" }
                main.post {
                    if (ticket != generation) { worker.execute { lease.close() }; return@post }
                    channel = lease; events = queue
                    lastPulse = SystemClock.elapsedRealtime()
                    main.removeCallbacks(pulse); main.post(pulse)
                    enter(ticket, SystemClock.elapsedRealtime() + 5000)
                }
            } catch (error: Exception) {
                opened?.close?.invoke()
                main.post { if (ticket == generation) { starting = false; report("Kiosk unavailable: ${error.message}") } }
            }
        }
    }
    private fun enter(ticket: Int, deadline: Long) {
        if (ticket != generation) return
        val activity = owner.get()
        if (activity == null || activity.isDestroyed || activity.isFinishing || !activity.hasWindowFocus()) {
            if (SystemClock.elapsedRealtime() < deadline) main.postDelayed({ enter(ticket, deadline) }, 100)
            else fail("D2 did not receive focus")
            return
        }
        try {
            // D2_READY means the root bridge completed ATM's synchronous allowlist update.
            // DPM.isLockTaskPermitted reads persisted DPC policy, not this temporary runtime lease.
            // Never fall back to startSystemLockTaskMode/am task lock (escapable screen pinning).
            Log.i("SamsungLockD2", "GUARDIAN_KIOSK_REQUESTED")
            activity.startLockTask()
            confirm(ticket, SystemClock.elapsedRealtime() + 3000)
        } catch (e: Exception) { fail(e.message ?: "Kiosk start failed") }
    }
    private fun confirm(ticket: Int, deadline: Long) {
        if (ticket != generation) return
        val activity = owner.get() ?: return fail("D2 window closed")
        if (mode(activity) == ActivityManager.LOCK_TASK_MODE_LOCKED) {
            starting = false; active = true
            lockLostAt = 0L; lockRepairAttempts = 0
            Log.i("SamsungLockD2", "GUARDIAN_KIOSK_CONFIRMED mode=LOCKED")
            report("Kiosk active • ${credentialName(activity)} required to leave")
        } else if (SystemClock.elapsedRealtime() < deadline) main.postDelayed({ confirm(ticket, deadline) }, 100)
        else fail("Android did not enter full kiosk mode")
    }
    private val pulse = object : Runnable {
        override fun run() {
            val lease = channel ?: return
            val now = SystemClock.elapsedRealtime()
            val event = events?.poll()
            if (now - lastPulse > 15000 || event != null) {
                fail("Recovery released kiosk; lock again to retry"); return
            }
            lastPulse = now
            val activity = owner.get()
            if (activity == null && detachedAt != 0L && now - detachedAt > 5000) {
                fail("D2 window closed; recovery released kiosk"); return
            }
            val callActive = activity != null &&
                app.d2lock.notifications.CallNotificationStore.items.isNotEmpty()
            if (callActive) {
                app.d2lock.lockscreen.GuardianWatchdog.beginCall("kiosk_pulse")
                if (lockLostAt != 0L || lockRepairAttempts != 0) {
                    Log.i("SamsungLockD2", "GUARDIAN_CALL_KIOSK_REPAIR_SUSPENDED")
                    lockLostAt = 0L
                    lockRepairAttempts = 0
                }
            } else if (app.d2lock.lockscreen.GuardianWatchdog.isCallActive()) {
                app.d2lock.lockscreen.GuardianWatchdog.endCall("kiosk_call_cleared")
                Log.i("SamsungLockD2", "GUARDIAN_CALL_REASSERT")
                if (activity != null) reassert(activity, bringToFront = !activity.hasWindowFocus())
            }
            if (!callActive && active && activity != null && mode(activity) != ActivityManager.LOCK_TASK_MODE_LOCKED) {
                if (lockLostAt == 0L) {
                    lockLostAt = now
                    lockRepairAttempts = 0
                    Log.w("SamsungLockD2", "GUARDIAN_LOCK_TASK_LOST")
                }
                if (now - lockLostAt <= LOCK_REPAIR_WINDOW_MS && lockRepairAttempts < LOCK_REPAIR_MAX_ATTEMPTS) {
                    lockRepairAttempts++
                    Log.w("SamsungLockD2", "GUARDIAN_LOCK_TASK_REPAIR attempt=$lockRepairAttempts")
                    reassert(activity, bringToFront = !activity.hasWindowFocus())
                } else {
                    Log.e("SamsungLockD2", "GUARDIAN_LOCK_TASK_RECOVERY_FAILED attempts=$lockRepairAttempts")
                    fail("Kiosk ended and Guardian could not restore secure lock"); return
                }
            } else if (!callActive && active && activity != null && lockLostAt != 0L) {
                Log.i("SamsungLockD2", "GUARDIAN_LOCK_TASK_RECOVERED elapsed=${now - lockLostAt} attempts=$lockRepairAttempts")
                lockLostAt = 0L
                lockRepairAttempts = 0
            }
            worker.execute { runCatching { lease.writer.write("PING\n"); lease.writer.flush() } }
            main.postDelayed(this, 1000)
        }
    }
    private fun fail(reason: String) {
        owner.get()?.let { runCatching { if (active) it.stopLockTask() } }
        release { report("Kiosk unavailable: $reason") }
    }
    /** Call only from a verified D2 authentication callback. Recovery is a deliberately separate escape path. */
    fun unlock(activity: Activity, done: () -> Unit) {
        releasing = true
        if (active) {
            try { activity.stopLockTask() }
            catch (_: Exception) { /* Removing our temporary allowlist below also stops the task. */ }
        }
        release {
            releasing = false
            if (mode(activity) == ActivityManager.LOCK_TASK_MODE_NONE) done()
            else report("Could not release kiosk. Retry ${credentialName(activity)}, or reboot for recovery.")
        }
    }
    private fun release(done: () -> Unit) {
        ++generation; starting = false; active = false
        lockLostAt = 0L; lockRepairAttempts = 0
        main.removeCallbacks(pulse)
        val lease = channel; val queue = events
        channel = null; events = null
        worker.execute {
            if (lease != null) {
                runCatching { lease.writer.write("RELEASE\n"); lease.writer.flush(); queue?.poll(5, TimeUnit.SECONDS) }
                lease.close()
            }
            main.post { report("Kiosk inactive"); done() }
        }
    }
    fun detach(activity: Activity) {
        if (owner.get() === activity) { owner.clear(); observer = null; detachedAt = SystemClock.elapsedRealtime() }
    }
}
