package app.d2lock.root

import java.util.concurrent.TimeUnit

object RootManager {
    fun isAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val finished = process.waitFor(2, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0 &&
                process.inputStream.bufferedReader().use { it.readText() }.contains("uid=0")
        }
    }.getOrDefault(false)

    /** Uses root only to request the companion Activity launch; never touches keyguard data. */
    fun launchCompanion(): Boolean = runCatching {
        val process = ProcessBuilder(
            "su", "-c",
            "am start -n app.d2lock/.lockscreen.LockScreenActivity --activity-single-top"
        ).redirectErrorStream(true).start()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.exitValue() == 0 && !output.contains("Error", ignoreCase = true)
        }
    }.getOrDefault(false)

    private fun currentUsbFunctions(): List<String> {
        val raw = rootOutput("getprop persist.sys.usb.config").ifBlank {
            rootOutput("getprop sys.usb.config")
        }
        return raw.split(',').map { it.trim() }
            .filter { it.matches(Regex("[A-Za-z0-9_.-]+")) && it != "none" && it != "adb" }
            .distinct()
    }

    fun setAdbUsbEnabled(enabled: Boolean): Boolean {
        val functions = currentUsbFunctions().toMutableList()
        if (enabled) functions += "adb"
        val config = functions.distinct().joinToString(",").ifBlank { "none" }
        if (!config.matches(Regex("[A-Za-z0-9_,.-]+"))) return false
        val block = if (enabled) 0 else 1
        return runRoot(
            "settings put global block_usb_lock $block; " +
                "setprop persist.sys.usb.config $config; setprop sys.usb.config $config"
        )
    }

    fun adbUsbEnabled(): Boolean {
        val block = rootOutput("settings get global block_usb_lock")
        val config = rootOutput("getprop sys.usb.config")
        return block == "0" && config.split(',').any { it.trim() == "adb" }
    }

    fun usbDiagnostics(): String = rootOutput(
        "echo BLOCK=$(settings get global block_usb_lock); " +
            "echo ADB=$(settings get global adb_enabled); " +
            "echo PERSIST=$(getprop persist.sys.usb.config); " +
            "echo CURRENT=$(getprop sys.usb.config); " +
            "echo STATE=$(getprop sys.usb.state)"
    ).ifBlank { "USB diagnostics unavailable" }

    fun resetAdbUsb(): Boolean {
        val functions = currentUsbFunctions().toMutableList().apply { add("adb") }
        val config = functions.distinct().joinToString(",").ifBlank { "adb" }
        if (!config.matches(Regex("[A-Za-z0-9_,.-]+"))) return false
        return runRoot(
            "settings put global block_usb_lock 0; stop adbd; setprop sys.usb.config none; sleep 1; " +
                "setprop persist.sys.usb.config $config; setprop sys.usb.config $config; start adbd"
        )
    }

    /** Android userspace restart; faster than a full hardware reboot. */
    fun softReboot(): Boolean = runRoot(
        "(setprop ctl.restart zygote || setprop ctl.restart zygote_secondary || killall zygote) >/dev/null 2>&1 &"
    )

    fun restartSystemUi(): Boolean = runRoot(
        "(pkill -TERM -f com.android.systemui || killall com.android.systemui) >/dev/null 2>&1 &"
    )

    private fun rootOutput(command: String): String = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(4, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            ""
        } else {
            process.inputStream.bufferedReader().use { it.readText().trim() }
        }
    }.getOrDefault("")

    private fun runRoot(command: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val finished = process.waitFor(4, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    }.getOrDefault(false)
}
