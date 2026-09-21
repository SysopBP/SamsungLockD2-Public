package app.d2lock.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {
    const val REQUEST_CODE = 3702

    @Volatile private var binderSeen = false

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        binderSeen = true
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        binderSeen = false
    }

    fun initialize() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            binderSeen = Shizuku.pingBinder()
        }
    }

    fun isRunning(): Boolean = runCatching {
        val alive = Shizuku.pingBinder()
        binderSeen = alive
        alive
    }.getOrDefault(false)

    fun isGranted(): Boolean = runCatching {
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun status(): String = when {
        !isRunning() -> "Connecting / not running"
        isGranted() -> {
            val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            if (uid == 0) "Running (root) • authorized"
            else "Running • authorized"
        }
        else -> "Running • permission required"
    }

    fun requestPermission() {
        if (isRunning() && !isGranted()) {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
        }
    }
}
