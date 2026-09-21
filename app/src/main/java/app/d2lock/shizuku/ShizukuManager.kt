package app.d2lock.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {
    const val REQUEST_CODE = 3702
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun isGranted(): Boolean = runCatching {
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    fun status(): String = when {
        !isRunning() -> "Not running"
        isGranted() -> "Connected • permission granted"
        else -> "Connected • permission required"
    }
    fun requestPermission() {
        if (isRunning() && !isGranted()) Shizuku.requestPermission(REQUEST_CODE)
    }
}
