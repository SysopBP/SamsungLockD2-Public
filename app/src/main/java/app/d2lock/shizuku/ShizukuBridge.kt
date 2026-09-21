package app.d2lock.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuBridge {
    const val REQUEST_CODE = 8401

    @Volatile private var binderSeen = false
    @Volatile private var initialized = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { binderSeen = true }
    private val binderDead = Shizuku.OnBinderDeadListener { binderSeen = false }

    fun initialize() {
        if (initialized) return
        initialized = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            binderSeen = Shizuku.pingBinder()
        }
    }

    fun isRunning(): Boolean {
        initialize()
        return runCatching {
            val alive = Shizuku.pingBinder()
            binderSeen = alive
            alive
        }.getOrElse { binderSeen }
    }

    fun isAuthorized(): Boolean =
        isRunning() && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun requestPermission() {
        initialize()
        if (isRunning() && !isAuthorized()) {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
        }
    }

    fun status(): String = when {
        !isRunning() -> "Shizuku not running"
        isAuthorized() -> "Shizuku connected"
        else -> "Shizuku running · permission required"
    }
}
