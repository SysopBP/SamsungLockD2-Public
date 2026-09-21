package app.d2lock.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuBridge {
    const val REQUEST_CODE = 8401

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isAuthorized(): Boolean =
        isRunning() && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun requestPermission() {
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
