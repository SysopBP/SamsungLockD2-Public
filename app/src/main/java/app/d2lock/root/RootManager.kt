package app.d2lock.root

import java.util.concurrent.TimeUnit

object RootManager {
    fun isAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val finished = process.waitFor(2, TimeUnit.SECONDS)
        if (!finished) { process.destroyForcibly(); false }
        else process.exitValue() == 0 && process.inputStream.bufferedReader().use { it.readText() }.contains("uid=0")
    }.getOrDefault(false)

    /** Uses root only to request the companion Activity launch; never touches keyguard data. */
    fun launchCompanion(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "am start -n app.d2lock/.lockscreen.LockScreenActivity --activity-single-top")
            .redirectErrorStream(true).start()
        if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); false }
        else {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.exitValue() == 0 && !output.contains("Error", ignoreCase = true)
        }
    }.getOrDefault(false)
    fun setAdbUsbEnabled(enabled: Boolean): Boolean = runRoot(
        if (enabled)
            "settings put global block_usb_lock 0; setprop persist.sys.usb.config adb; setprop sys.usb.config adb"
        else
            "settings put global block_usb_lock 1; setprop persist.sys.usb.config none; setprop sys.usb.config none"
    )

    fun adbUsbEnabled(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "settings get global block_usb_lock").redirectErrorStream(true).start()
        process.waitFor(2, TimeUnit.SECONDS) && process.inputStream.bufferedReader().use { it.readText().trim() } == "0"
    }.getOrDefault(false)

    fun usbDiagnostics(): String = rootOutput(
        "echo BLOCK=$(settings get global block_usb_lock); echo ADB=$(settings get global adb_enabled); echo PERSIST=$(getprop persist.sys.usb.config); echo CURRENT=$(getprop sys.usb.config); echo STATE=$(getprop sys.usb.state)"
    ).ifBlank { "USB diagnostics unavailable" }

    fun resetAdbUsb(): Boolean = runRoot(
        "settings put global block_usb_lock 0; stop adbd; setprop sys.usb.config none; sleep 1; setprop persist.sys.usb.config adb; setprop sys.usb.config adb; start adbd"
    )

    private fun rootOutput(command: String): String = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(4, TimeUnit.SECONDS)) { process.destroyForcibly(); "" }
        else process.inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrDefault("")

    private fun runRoot(command: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val finished = process.waitFor(4, TimeUnit.SECONDS)
        if (!finished) { process.destroyForcibly(); false } else process.exitValue() == 0
    }.getOrDefault(false)
}
